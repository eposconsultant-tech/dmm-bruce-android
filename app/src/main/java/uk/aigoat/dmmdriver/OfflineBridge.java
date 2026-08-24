package uk.aigoat.dmmdriver;

import android.webkit.JavascriptInterface;

public class OfflineBridge {
    private final OfflineDatabase db;

    public OfflineBridge(OfflineDatabase db) {
        this.db = db;
    }

    @JavascriptInterface
    public String getItem(String key) {
        try { return db.getItem(key); } catch (Exception ex) { return null; }
    }

    @JavascriptInterface
    public boolean setItem(String key, String value) {
        try { return db.setItem(key, value); } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public void removeItem(String key) {
        try { db.removeItem(key); } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getJobsJson() {
        try { return db.getJobsJson(); } catch (Exception ex) { return "[]"; }
    }

    @JavascriptInterface
    public boolean setJobsJson(String json) {
        try { return db.setJobsJson(json); } catch (Exception ex) { return false; }
    }

    @JavascriptInterface
    public int pendingCount() {
        try { return db.pendingCount(); } catch (Exception ex) { return 0; }
    }

    @JavascriptInterface
    public String stats() {
        try { return db.statsJson(); } catch (Exception ex) { return "{}"; }
    }

    @JavascriptInterface
    public String engine() {
        return "sqlite-v1";
    }
}
