package uk.aigoat.dmmdriver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class OfflineDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "dmm_driver_offline.db";
    private static final int DB_VERSION = 2;

    public OfflineDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_pending ON jobs(pending)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (job_id TEXT PRIMARY KEY, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv_store (k TEXT PRIMARY KEY, v TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id TEXT PRIMARY KEY, job_id TEXT, kind TEXT, local_path TEXT, remote_url TEXT, status TEXT NOT NULL DEFAULT 'local', updated_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
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
                db.delete("jobs", null, null);
                db.delete("sync_queue", null, null);
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

    private void upsertJob(SQLiteDatabase db, JSONObject job, long now) {
        String id = job.optString("id", "").trim();
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            try { job.put("id", id); } catch (Exception ignored) {}
        }
        String json = job.toString();
        boolean pending = job.optBoolean("_pendingCloudUpload", false);

        boolean changed = true;
        try (Cursor c = db.query("jobs", new String[]{"json", "pending"}, "id=?", new String[]{id}, null, null, null, "1")) {
            if (c.moveToFirst()) {
                String oldJson = c.getString(0);
                int oldPending = c.getInt(1);
                changed = !json.equals(oldJson) || oldPending != (pending ? 1 : 0);
            }
        }

        if (changed) {
            ContentValues cv = new ContentValues();
            cv.put("id", id);
            cv.put("json", json);
            cv.put("pending", pending ? 1 : 0);
            cv.put("updated_at", now);
            db.insertWithOnConflict("jobs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }

        if (pending) {
            ContentValues q = new ContentValues();
            q.put("job_id", id);
            q.put("status", "pending");
            q.put("updated_at", now);
            db.insertWithOnConflict("sync_queue", null, q, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            db.delete("sync_queue", "job_id=?", new String[]{id});
        }
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
                    try { job.put("id", id); } catch (Exception ignored) {}
                }
                incomingIds.add(id);
                upsertJob(db, job, now + i);
            }

            // Remove only stale downloaded rows. Never delete unsynced pending work just because
            // an authoritative cloud download does not contain it yet.
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
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM jobs WHERE pending=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized String statsJson() {
        int jobs = 0, pending = 0, kv = 0;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(pending),0) FROM jobs", null)) {
            if (c.moveToFirst()) { jobs = c.getInt(0); pending = c.getInt(1); }
        }
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM kv_store", null)) {
            if (c.moveToFirst()) kv = c.getInt(0);
        }
        return "{\"jobs\":" + jobs + ",\"pending\":" + pending + ",\"kv\":" + kv + ",\"dbVersion\":" + DB_VERSION + "}";
    }
}
