package uk.aigoat.dmmdriver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class OfflineDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "dmm_driver_offline.db";
    private static final int DB_VERSION = 5;
    private static final long MAX_RETRY_DELAY_MS = 5L * 60L * 1000L;

    public OfflineDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    private void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_pending ON jobs(pending)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (job_id TEXT PRIMARY KEY, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, next_retry_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        if (hasColumn(db, "sync_queue", "next_retry_at")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status_retry ON sync_queue(status,next_retry_at)");
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS kv_store (k TEXT PRIMARY KEY, v TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id TEXT PRIMARY KEY, job_id TEXT, kind TEXT, local_path TEXT, remote_url TEXT, status TEXT NOT NULL DEFAULT 'local', updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_job_status ON attachments(job_id,status)");
        db.execSQL("CREATE TABLE IF NOT EXISTS completion_commits (job_id TEXT PRIMARY KEY, checksum TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', committed_at INTEGER NOT NULL, synced_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_completion_status ON completion_commits(status)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Non-destructive migration: create missing tables first, then add versioned columns.
        createSchema(db);
        if (oldVersion < 5) {
            addColumnIfMissing(db, "sync_queue", "next_retry_at", "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status_retry ON sync_queue(status,next_retry_at)");
        }
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (hasColumn(db, table, column)) return;
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(c.getString(nameIndex))) return true;
            }
        }
        return false;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }

    private boolean isAlreadyPending(SQLiteDatabase db, String id) {
        try (Cursor c = db.query("jobs", new String[]{"pending"}, "id=?", new String[]{id}, null, null, null, "1")) {
            return c.moveToFirst() && c.getInt(0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String getItem(String key) {
        if ("dmmJobsV3".equals(key)) return getJobsJson();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("kv_store", new String[]{"v"}, "k=?", new String[]{key}, null, null, null)) {
            if (c.moveToFirst()) return c.isNull(0) ? null : c.getString(0);
        }
        return null;
    }

    public synchronized boolean setItem(String key, String value) {
        if ("dmmJobsV3".equals(key)) return setJobsJson(value);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("k", key);
        cv.put("v", value);
        cv.put("updated_at", System.currentTimeMillis());
        return db.insertWithOnConflict("kv_store", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public synchronized void removeItem(String key) {
        SQLiteDatabase db = getWritableDatabase();
        if ("dmmJobsV3".equals(key)) {
            db.beginTransaction();
            try {
                db.delete("jobs", "pending=0", null);
                db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return;
        }
        db.delete("kv_store", "k=?", new String[]{key});
    }

    public synchronized String getJobsJson() {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("jobs", new String[]{"json"}, null, null, null, null, "updated_at ASC")) {
            while (c.moveToNext()) {
                try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) {}
            }
        }
        return out.toString();
    }

    public synchronized String getJobJson(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("jobs", new String[]{"json"}, "id=?", new String[]{id}, null, null, null, "1")) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    private String upsertJob(SQLiteDatabase db, JSONObject job, long now) throws Exception {
        String id = job.optString("id", "").trim();
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            job.put("id", id);
        }
        boolean explicitPending = job.optBoolean("_pendingCloudUpload", false);
        boolean stickyPending = isAlreadyPending(db, id);
        boolean pending = explicitPending || stickyPending;
        if (pending) job.put("_pendingCloudUpload", true);

        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("json", job.toString());
        cv.put("pending", pending ? 1 : 0);
        cv.put("updated_at", now);
        if (db.insertWithOnConflict("jobs", null, cv, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("jobs upsert failed");
        }

        if (pending) {
            // Preserve previous retry state when the same pending job is saved again.
            ContentValues fresh = new ContentValues();
            fresh.put("job_id", id);
            fresh.put("status", "pending");
            fresh.put("attempts", 0);
            fresh.putNull("last_error");
            fresh.put("next_retry_at", 0);
            fresh.put("updated_at", now);
            db.insertWithOnConflict("sync_queue", null, fresh, SQLiteDatabase.CONFLICT_IGNORE);

            ContentValues touch = new ContentValues();
            touch.put("status", "pending");
            touch.put("updated_at", now);
            if (db.update("sync_queue", touch, "job_id=?", new String[]{id}) != 1) {
                throw new IllegalStateException("sync queue upsert failed");
            }
        }
        return id;
    }

    public synchronized boolean saveJobJson(String json) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject job = new JSONObject(json == null ? "{}" : json);
            upsertJob(db, job, System.currentTimeMillis());
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized String completeJobAtomic(String json) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject job = new JSONObject(json == null ? "{}" : json);
            job.put("_pendingCloudUpload", true);
            if (!"delivered".equalsIgnoreCase(job.optString("status", ""))) {
                throw new IllegalStateException("completion status must be Delivered");
            }
            String id = upsertJob(db, job, System.currentTimeMillis());
            String payload = job.toString();
            String checksum = sha256(payload);

            ContentValues commit = new ContentValues();
            commit.put("job_id", id);
            commit.put("checksum", checksum);
            commit.put("payload_json", payload);
            commit.put("status", "pending");
            commit.put("committed_at", System.currentTimeMillis());
            if (db.insertWithOnConflict("completion_commits", null, commit, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                throw new IllegalStateException("completion commit insert failed");
            }

            String storedJson;
            int storedPending;
            try (Cursor c = db.query("jobs", new String[]{"json", "pending"}, "id=?", new String[]{id}, null, null, null, "1")) {
                if (!c.moveToFirst()) throw new IllegalStateException("job read-back failed");
                storedJson = c.getString(0);
                storedPending = c.getInt(1);
            }
            int queueCount = 0;
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE job_id=? AND status='pending'", new String[]{id})) {
                if (c.moveToFirst()) queueCount = c.getInt(0);
            }
            if (storedPending != 1 || queueCount != 1) throw new IllegalStateException("pending queue verification failed");
            if (!checksum.equals(sha256(storedJson))) throw new IllegalStateException("payload checksum verification failed");
            JSONObject verified = new JSONObject(storedJson);
            if (!"delivered".equalsIgnoreCase(verified.optString("status", ""))) throw new IllegalStateException("Delivered status verification failed");

            db.setTransactionSuccessful();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("id", id);
            out.put("pending", true);
            out.put("status", verified.optString("status", ""));
            out.put("checksum", checksum);
            return out.toString();
        } catch (Exception ex) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            } catch (Exception ignored) {}
            return out.toString();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean markSyncFailed(String id, String error) {
        if (id == null || id.trim().isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            int attempts = 0;
            try (Cursor c = db.query("sync_queue", new String[]{"attempts"}, "job_id=?", new String[]{id}, null, null, null, "1")) {
                if (c.moveToFirst()) attempts = c.getInt(0);
            }
            int nextAttempts = Math.min(attempts + 1, 30);
            int exponent = Math.min(Math.max(nextAttempts - 1, 0), 8);
            long delay = Math.min(MAX_RETRY_DELAY_MS, 2000L * (1L << exponent));

            ContentValues q = new ContentValues();
            q.put("job_id", id);
            q.put("status", "pending");
            q.put("attempts", nextAttempts);
            q.put("last_error", error == null ? "Sync failed" : error.substring(0, Math.min(error.length(), 500)));
            q.put("next_retry_at", now + delay);
            q.put("updated_at", now);
            if (db.insertWithOnConflict("sync_queue", null, q, SQLiteDatabase.CONFLICT_REPLACE) == -1) return false;

            ContentValues jobPending = new ContentValues();
            jobPending.put("pending", 1);
            jobPending.put("updated_at", now);
            db.update("jobs", jobPending, "id=?", new String[]{id});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int retryReadyCount() {
        SQLiteDatabase db = getReadableDatabase();
        long now = System.currentTimeMillis();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE status='pending' AND COALESCE(next_retry_at,0)<=?", new String[]{String.valueOf(now)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized boolean markJobSynced(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String json = null;
            try (Cursor c = db.query("jobs", new String[]{"json"}, "id=?", new String[]{id}, null, null, null, "1")) {
                if (c.moveToFirst()) json = c.getString(0);
            }
            if (json == null) return false;
            JSONObject job = new JSONObject(json);
            job.remove("_pendingCloudUpload");
            ContentValues cv = new ContentValues();
            cv.put("json", job.toString());
            cv.put("pending", 0);
            cv.put("updated_at", System.currentTimeMillis());
            if (db.update("jobs", cv, "id=?", new String[]{id}) != 1) throw new IllegalStateException("job sync update failed");
            db.delete("sync_queue", "job_id=?", new String[]{id});
            ContentValues cc = new ContentValues();
            cc.put("status", "synced");
            cc.put("synced_at", System.currentTimeMillis());
            db.update("completion_commits", cc, "job_id=?", new String[]{id});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean setJobsJson(String json) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray arr = new JSONArray(json == null || json.trim().isEmpty() ? "[]" : json);
            long now = System.currentTimeMillis();
            Set<String> incomingIds = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject job = arr.optJSONObject(i);
                if (job == null) continue;
                String id = job.optString("id", "").trim();
                if (id.isEmpty()) {
                    id = UUID.randomUUID().toString();
                    job.put("id", id);
                }
                incomingIds.add(id);
                upsertJob(db, job, now + i);
            }
            try (Cursor c = db.query("jobs", new String[]{"id"}, "pending=0", null, null, null, null)) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    if (!incomingIds.contains(id)) db.delete("jobs", "id=? AND pending=0", new String[]{id});
                }
            }
            db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int clearNonPendingJobs() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int removed = db.delete("jobs", "pending=0", null);
            db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int pendingCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(DISTINCT id) FROM (SELECT job_id AS id FROM sync_queue WHERE status='pending' UNION SELECT job_id AS id FROM completion_commits WHERE status='pending' UNION SELECT id FROM jobs WHERE pending=1)", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized String pendingDetailsJson() {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT j.id,COALESCE(q.status,'pending'),COALESCE(q.attempts,0),q.last_error,COALESCE(q.next_retry_at,0),j.updated_at,j.json FROM jobs j LEFT JOIN sync_queue q ON q.job_id=j.id WHERE j.pending=1 OR q.status='pending' OR j.id IN (SELECT job_id FROM completion_commits WHERE status='pending') ORDER BY j.updated_at ASC", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject row = new JSONObject();
                    row.put("jobId", c.getString(0));
                    row.put("status", c.getString(1));
                    row.put("attempts", c.getInt(2));
                    row.put("lastError", c.isNull(3) ? JSONObject.NULL : c.getString(3));
                    row.put("nextRetryAt", c.getLong(4));
                    row.put("updatedAt", c.getLong(5));
                    if (!c.isNull(6)) row.put("job", new JSONObject(c.getString(6)));
                    out.put(row);
                } catch (Exception ignored) {}
            }
        }
        return out.toString();
    }

    public synchronized String statsJson() {
        int jobs = 0, pending = 0, kv = 0, commits = 0;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(pending),0) FROM jobs", null)) {
            if (c.moveToFirst()) { jobs = c.getInt(0); pending = c.getInt(1); }
        }
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM kv_store", null)) { if (c.moveToFirst()) kv = c.getInt(0); }
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM completion_commits", null)) { if (c.moveToFirst()) commits = c.getInt(0); }
        int retryReady = retryReadyCount();
        return "{\"jobs\":" + jobs + ",\"pending\":" + pending + ",\"retryReady\":" + retryReady + ",\"kv\":" + kv + ",\"completionCommits\":" + commits + ",\"dbVersion\":" + DB_VERSION + "}";
    }
}
