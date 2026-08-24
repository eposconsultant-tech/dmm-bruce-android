package uk.aigoat.dmmdriver;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String DMM_URL = "https://dmm.aigoat.uk/";
    private WebView webView;
    private OfflineDatabase offlineDatabase;

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
        setContentView(frame);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " DMM-Android-Driver/3.11 SQLiteJobPersistence/1");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installMinimalJobPersistence(view);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        if (savedInstanceState == null) webView.loadUrl(DMM_URL); else webView.restoreState(savedInstanceState);
    }

    private void installMinimalJobPersistence(WebView view) {
        String js = "(function(){try{" +
            "if(window.__DMM311)return;window.__DMM311=true;" +
            "if(!window.DMMNative)return;" +
            "var KEY='dmmJobsV3';" +
            "var nativeSet=window.DMMNative.setJobsJson?window.DMMNative.setJobsJson.bind(window.DMMNative):null;" +
            "var nativeGet=window.DMMNative.getJobsJson?window.DMMNative.getJobsJson.bind(window.DMMNative):null;" +
            "if(!nativeSet||!nativeGet)return;" +
            "var originalSet=Storage.prototype.setItem;" +
            "var originalGet=Storage.prototype.getItem;" +
            "Storage.prototype.setItem=function(k,v){" +
                "if(this===localStorage&&k===KEY){try{nativeSet(String(v));}catch(e){} return originalSet.call(this,k,v);}" +
                "return originalSet.call(this,k,v);" +
            "};" +
            "Storage.prototype.getItem=function(k){" +
                "if(this===localStorage&&k===KEY){try{var n=nativeGet();if(n&&n!=='[]')return n;}catch(e){}}" +
                "return originalGet.call(this,k);" +
            "};" +
            "try{var existing=originalGet.call(localStorage,KEY);if(existing)nativeSet(existing);}catch(e){}" +
        "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("DMMNative");
            webView.destroy(); webView = null;
        }
        if (offlineDatabase != null) { offlineDatabase.close(); offlineDatabase = null; }
        super.onDestroy();
    }
}
