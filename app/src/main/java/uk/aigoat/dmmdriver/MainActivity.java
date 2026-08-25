package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String DMM_URL = "https://dmm.aigoat.uk/";
    private static final int PICK_PHOTO_REQUEST = 3201;
    private static final int CAMERA_REQUEST = 3202;
    private static final int CAMERA_PERMISSION_REQUEST = 3203;

    private WebView webView;
    private OfflineDatabase offlineDatabase;
    private Button pendingButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;

    private final Runnable pendingRefresh = new Runnable() {
        @Override public void run() {
            refreshPendingButton();
            uiHandler.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        offlineDatabase = new OfflineDatabase(this);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.addJavascriptInterface(new OfflineBridge(offlineDatabase), "DMMNative");

        FrameLayout frame = new FrameLayout(this);
        frame.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pendingButton = new Button(this);
        pendingButton.setAllCaps(false);
        pendingButton.setTextSize(10f);
        pendingButton.setTextColor(Color.WHITE);
        pendingButton.setBackgroundColor(Color.rgb(17,24,39));
        pendingButton.setPadding(dp(10), dp(3), dp(10), dp(3));
        pendingButton.setOnClickListener(v -> showPendingDetails());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40));
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = dp(6);
        bp.rightMargin = dp(8);
        frame.addView(pendingButton, bp);
        setContentView(frame);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(s.getUserAgentString() + " DMM-Android-Driver/3.20 FullOffline/1 SQLiteOffline/3 AtomicCompletion/3 NativePendingUI/2 NativeCamera/1");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installFullOfflinePersistence(view);
                refreshPendingButton();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Delivery Photograph")
                    .setItems(new String[]{"Take Photo", "Select Photo"}, (d, which) -> {
                        if (which == 0) startCameraFlow(); else startGalleryFlow();
                    })
                    .setOnCancelListener(d -> clearFileCallback())
                    .show();
                return true;
            }

            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                String m = message == null ? "" : message;
                if ((m.contains("saved on this device") && m.toLowerCase().contains("cloud upload failed")) ||
                    (m.contains("updated on this device") && m.toLowerCase().contains("cloud upload failed")) ||
                    "Failed to fetch".equalsIgnoreCase(m.trim())) {
                    result.confirm();
                    refreshPendingButton();
                    Toast.makeText(MainActivity.this, "Saved offline · Ready to send", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        uiHandler.post(pendingRefresh);
        if (savedInstanceState == null) webView.loadUrl(DMM_URL); else webView.restoreState(savedInstanceState);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ex) { return false; }
    }

    private void installFullOfflinePersistence(WebView view) {
        String js = "(function(){try{" +
            "if(window.__DMM320)return;window.__DMM320=true;if(!window.DMMNative)return;" +
            "var KEY='dmmJobsV3';var os=Storage.prototype.setItem,og=Storage.prototype.getItem,or=Storage.prototype.removeItem;" +
            "Storage.prototype.setItem=function(k,v){if(this===localStorage&&k===KEY){" +
                "var a=JSON.parse(String(v)||'[]');if(Array.isArray(a)){for(var i=0;i<a.length;i++){var j=a[i];if(j&&j._pendingCloudUpload===true&&String(j.status||'').toLowerCase()==='delivered'){var r=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!r.ok)throw new Error(r.error||'SQLite completion verification failed');}}}" +
                "if(!DMMNative.setJobsJson(String(v)))throw new Error('SQLite jobs write failed');return;}return os.call(this,k,v);};" +
            "Storage.prototype.getItem=function(k){if(this===localStorage&&k===KEY){try{var n=DMMNative.getJobsJson();if(n!=null)return n;}catch(e){}}return og.call(this,k);};" +
            "Storage.prototype.removeItem=function(k){if(this===localStorage&&k===KEY){try{DMMNative.removeItem(KEY);}catch(e){}return;}return or.call(this,k);};" +
            "try{var e=og.call(localStorage,KEY);if(e)DMMNative.setJobsJson(e);}catch(x){}" +
        "}catch(e){console.error('DMM v3.20 offline persistence failed',e);}})();";
        view.evaluateJavascript(js, null);
    }

    private void refreshPendingButton() {
        if (pendingButton == null || offlineDatabase == null) return;
        runOnUiThread(() -> {
            int n = 0; try { n = offlineDatabase.pendingCount(); } catch (Exception ignored) {}
            boolean online = isOnline();
            if (online) pendingButton.setText(n > 0 ? "ONLINE · " + n + " READY TO SEND" : "ONLINE · ALL SYNCED ✓");
            else pendingButton.setText(n > 0 ? "OFFLINE · " + n + " READY TO SEND" : "OFFLINE · 0 PENDING");
        });
    }

    private void showPendingDetails() {
        StringBuilder text = new StringBuilder();
        text.append(isOnline() ? "Network: ONLINE\n\n" : "Network: OFFLINE\n\n");
        try {
            JSONArray rows = new JSONArray(offlineDatabase.pendingDetailsJson());
            if (rows.length() == 0) text.append("No local SQLite jobs are waiting to send.");
            else for (int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i); if(row==null) continue; JSONObject job=row.optJSONObject("job");
                String number=job==null?row.optString("jobId","?"):job.optString("jobNumber",row.optString("jobId","?"));
                String customer=job==null?"":job.optString("customerName",job.optString("businessName",""));
                String status=job==null?row.optString("status","pending"):job.optString("status",row.optString("status","pending"));
                text.append("Job #").append(number).append(" · ").append(status); if(!customer.isEmpty()) text.append("\n").append(customer);
                String err=row.optString("lastError",""); if(!err.isEmpty()&&!"null".equalsIgnoreCase(err)) text.append("\nError: ").append(err);
                if(i<rows.length()-1) text.append("\n\n");
            }
        } catch(Exception ex){ text.append("Unable to read local pending data.\n").append(ex.getMessage()); }
        new AlertDialog.Builder(this).setTitle("Local SQLite · Ready to Send").setMessage(text.toString()).setPositiveButton("OK",null).show();
    }

    private void startCameraFlow() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST); return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "dmm_delivery_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingCameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (pendingCameraUri != null) camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(camera, CAMERA_REQUEST);
        } catch(Exception ex) {
            Toast.makeText(this,"Camera could not be opened · use Select Photo",Toast.LENGTH_LONG).show(); startGalleryFlow();
        }
    }

    private void startGalleryFlow() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); startActivityForResult(i,PICK_PHOTO_REQUEST);
        } catch(Exception ex){ Toast.makeText(this,"Unable to open photos",Toast.LENGTH_LONG).show(); clearFileCallback(); }
    }

    private void clearFileCallback(){ if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=null;pendingCameraUri=null; }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        if(requestCode==CAMERA_REQUEST){if(filePathCallback!=null)filePathCallback.onReceiveValue(resultCode==RESULT_OK&&pendingCameraUri!=null?new Uri[]{pendingCameraUri}:null);filePathCallback=null;pendingCameraUri=null;return;}
        if(requestCode==PICK_PHOTO_REQUEST){Uri uri=resultCode==RESULT_OK&&data!=null?data.getData():null;if(filePathCallback!=null)filePathCallback.onReceiveValue(uri==null?null:new Uri[]{uri});filePathCallback=null;return;}
        super.onActivityResult(requestCode,resultCode,data);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==CAMERA_PERMISSION_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startCameraFlow();else{Toast.makeText(this,"Camera permission denied · use Select Photo",Toast.LENGTH_LONG).show();startGalleryFlow();}}
    }

    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle outState){if(webView!=null)webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onDestroy(){uiHandler.removeCallbacks(pendingRefresh);if(webView!=null){webView.removeJavascriptInterface("DMMNative");webView.destroy();webView=null;}if(offlineDatabase!=null){offlineDatabase.close();offlineDatabase=null;}super.onDestroy();}
}
