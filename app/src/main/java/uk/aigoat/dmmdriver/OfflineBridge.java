package uk.aigoat.dmmdriver;

import android.webkit.JavascriptInterface;

public class OfflineBridge {
    private final OfflineDatabase db;
    private final Runnable onChanged;

    public OfflineBridge(OfflineDatabase db) {
        this(db, null);
    }

    public OfflineBridge(OfflineDatabase db, Runnable onChanged) {
        this.db = db;
        this.onChanged = onChanged;
    }

    private void changed() {
        if (onChanged != null) {
            try { onChanged.run(); } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public String getItem(String key) {
        try { return db.getItem(key); } catch (Exception ex) { return null; }
    }

    @JavascriptInterface
    public boolean setItem(String key, String value) {
        try {
            boolean ok = db.setItem(key, value);
            if (ok) changed();
            return ok;
        } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public void removeItem(String key) {
        try { db.removeItem(key); changed(); } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getJobsJson() {
        try { return db.getJobsJson(); } catch (Exception ex) { return "[]"; }
    }

    @JavascriptInterface
    public boolean setJobsJson(String json) {
        try {
            boolean ok = db.setJobsJson(json);
            if (ok) changed();
            return ok;
        } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public boolean saveJobJson(String json) {
        try {
            boolean ok = db.saveJobJson(json);
            if (ok) changed();
            return ok;
        } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public String completeJobAtomic(String json) {
        try {
            String result = db.completeJobAtomic(json);
            changed();
            return result;
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"Native completion exception\"}";
        }
    }

    @JavascriptInterface
    public boolean markJobSynced(String id) {
        try {
            boolean ok = db.markJobSynced(id);
            if (ok) changed();
            return ok;
        } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public boolean markSyncFailed(String id, String error) {
        try {
            boolean ok = db.markSyncFailed(id, error);
            if (ok) changed();
            return ok;
        } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public int retryReadyCount() {
        try { return db.retryReadyCount(); } catch (Exception ex) { return 0; }
    }

    @JavascriptInterface
    public String getJobJson(String id) {
        try { return db.getJobJson(id); } catch (Exception ex) { return null; }
    }

    @JavascriptInterface
    public int clearNonPendingJobs() {
        try {
            int count = db.clearNonPendingJobs();
            changed();
            return count;
        } catch (Exception ex) { return 0; }
    }

    @JavascriptInterface
    public int pendingCount() {
        try { return db.pendingCount(); } catch (Exception ex) { return 0; }
    }

    @JavascriptInterface
    public String pendingDetails() {
        try { return db.pendingDetailsJson(); } catch (Exception ex) { return "[]"; }
    }

    @JavascriptInterface
    public String stats() {
        try { return db.statsJson(); } catch (Exception ex) { return "{}"; }
    }

    @JavascriptInterface
    public String engine() {
        return "sqlite-v4-retry-safe";
    }
}
