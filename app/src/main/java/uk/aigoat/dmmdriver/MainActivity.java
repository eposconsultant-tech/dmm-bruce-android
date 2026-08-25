package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
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
    private static final int FILE_CHOOSER_REQUEST = 2201;
    private static final int CAMERA_PERMISSION_REQUEST = 2202;
    private static final int CAMERA_PERMISSION_ON_INSTALL_REQUEST = 2203;

    private WebView webView;
    private OfflineDatabase offlineDatabase;
    private Button pendingButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    private WebChromeClient.FileChooserParams pendingChooserParams;

    // Background status check only. Never reloads, navigates, or changes the WebView screen.
    private final Runnable pendingRefresh = new Runnable() {
        @Override public void run() {
            try {
                if (webView != null && pendingButton != null) {
                    webView.evaluateJavascript("(!!document.getElementById('driverPortal')).toString()", value -> {
                        boolean driverVisible = "\"true\"".equals(value) || "true".equals(value);
                        pendingButton.setVisibility(driverVisible ? android.view.View.VISIBLE : android.view.View.GONE);
                        if (driverVisible) refreshPendingButton();
                    });
                }
            } catch (Exception ignored) {}
            uiHandler.postDelayed(this, 5000);
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
        pendingButton.setTextSize(11f);
        pendingButton.setTextColor(Color.WHITE);
        pendingButton.setBackgroundColor(Color.rgb(17,24,39));
        pendingButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        pendingButton.setVisibility(android.view.View.GONE);
        pendingButton.setOnClickListener(v -> showPendingDetails());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(42));
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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " DMM-Android-Driver/3.13 SQLiteOffline/3 ScreenStable/1 CameraOnLaunch/1");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installMinimalJobPersistence(view);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                pendingChooserParams = params;
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                    return true;
                }
                launchFileChooser();
                return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                String m = message == null ? "" : message;
                if (m.contains("Job was saved on this device") && m.contains("Cloud upload failed")) {
                    result.confirm();
                    refreshPendingButton();
                    Toast.makeText(MainActivity.this, "Saved offline · Ready to send", Toast.LENGTH_LONG).show();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_ON_INSTALL_REQUEST);
        }
        uiHandler.post(pendingRefresh);
        if (savedInstanceState == null) webView.loadUrl(DMM_URL); else webView.restoreState(savedInstanceState);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void installMinimalJobPersistence(WebView view) {
        String js = "(function(){try{" +
            "if(window.__DMM313)return;window.__DMM313=true;if(!window.DMMNative)return;" +
            "var KEY='dmmJobsV3';var originalSet=Storage.prototype.setItem,originalGet=Storage.prototype.getItem,originalRemove=Storage.prototype.removeItem;" +
            "Storage.prototype.setItem=function(k,v){if(this===localStorage&&k===KEY){try{var a=JSON.parse(String(v)||'[]');if(Array.isArray(a)){for(var i=0;i<a.length;i++){var j=a[i];if(j&&j._pendingCloudUpload===true&&String(j.status||'').toLowerCase()==='delivered'){var r=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!r.ok)throw new Error(r.error||'SQLite completion verification failed');}}}if(!DMMNative.setJobsJson(String(v)))throw new Error('SQLite jobs write failed');return;}catch(e){throw e;}}return originalSet.call(this,k,v);};" +
            "Storage.prototype.getItem=function(k){if(this===localStorage&&k===KEY){try{var n=DMMNative.getJobsJson();if(n!=null)return n;}catch(e){}}return originalGet.call(this,k);};" +
            "Storage.prototype.removeItem=function(k){if(this===localStorage&&k===KEY){try{DMMNative.removeItem(KEY);}catch(e){}return;}return originalRemove.call(this,k);};" +
            "try{var existing=originalGet.call(localStorage,KEY);if(existing)DMMNative.setJobsJson(existing);}catch(e){}" +
        "}catch(e){console.error('DMM v3.13 persistence install failed',e);}})();";
        view.evaluateJavascript(js, null);
    }

    private void refreshPendingButton() {
        if (pendingButton == null || offlineDatabase == null) return;
        runOnUiThread(() -> {
            int n = 0; try { n = offlineDatabase.pendingCount(); } catch (Exception ignored) {}
            pendingButton.setText(n > 0 ? "LOCAL READY TO SEND (" + n + ")" : "ALL SYNCED ✓");
        });
    }

    private void showPendingDetails() {
        StringBuilder text = new StringBuilder();
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

    private void launchFileChooser() {
        try {
            Intent i=pendingChooserParams==null?new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"):pendingChooserParams.createIntent();
            i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,FILE_CHOOSER_REQUEST);
        } catch(Exception ex){ if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=null;pendingChooserParams=null;Toast.makeText(this,"Unable to open camera/photo chooser",Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        if(requestCode==FILE_CHOOSER_REQUEST){if(filePathCallback!=null){filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data));filePathCallback=null;}pendingChooserParams=null;return;}super.onActivityResult(requestCode,resultCode,data);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==CAMERA_PERMISSION_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)launchFileChooser();else{if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=null;pendingChooserParams=null;Toast.makeText(this,"Camera permission is required for delivery photos",Toast.LENGTH_LONG).show();}}
    }

    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle outState){if(webView!=null)webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onDestroy(){uiHandler.removeCallbacks(pendingRefresh);if(webView!=null){webView.removeJavascriptInterface("DMMNative");webView.destroy();webView=null;}if(offlineDatabase!=null){offlineDatabase.close();offlineDatabase=null;}super.onDestroy();}
}
