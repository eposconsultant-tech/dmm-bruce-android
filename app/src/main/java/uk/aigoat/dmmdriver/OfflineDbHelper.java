package uk.aigoat.dmmdriver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

public class OfflineDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "dmm_driver_offline.db";
    private static final int DB_VERSION = 1;

    public OfflineDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, driver_id TEXT NOT NULL, job_date TEXT NOT NULL, payload TEXT NOT NULL, downloaded_at TEXT NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX ux_snapshots_driver_date ON snapshots(driver_id, job_date)");
        db.execSQL("CREATE TABLE pending_jobs (job_id TEXT PRIMARY KEY, payload TEXT NOT NULL, queued_at TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS snapshots");
        db.execSQL("DROP TABLE IF EXISTS pending_jobs");
        onCreate(db);
    }

    public void saveSnapshot(String driverId, String jobDate, String payload, String downloadedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("driver_id", driverId);
        values.put("job_date", jobDate);
        values.put("payload", payload);
        values.put("downloaded_at", downloadedAt);
        db.insertWithOnConflict("snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getLatestSnapshot() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT payload FROM snapshots ORDER BY downloaded_at DESC LIMIT 1", null)) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    public int getSnapshotCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM snapshots", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void queueJob(String jobId, String payload, String queuedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("job_id", jobId);
        values.put("payload", payload);
        values.put("queued_at", queuedAt);
        db.insertWithOnConflict("pending_jobs", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public JSONArray getPendingJobs() {
        JSONArray out = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT payload FROM pending_jobs ORDER BY queued_at ASC", null)) {
            while (c.moveToNext()) {
                try { out.put(new JSONObject(c.getString(0))); }
                catch (Exception ignored) { }
            }
        }
        return out;
    }

    public int getPendingCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM pending_jobs", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clearPending() {
        getWritableDatabase().delete("pending_jobs", null, null);
    }
}
