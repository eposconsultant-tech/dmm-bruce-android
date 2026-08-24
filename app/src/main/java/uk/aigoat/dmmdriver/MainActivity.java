package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String DMM_URL = "https://dmm.aigoat.uk/";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int CAMERA_PERMISSION_REQUEST = 2002;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingCameraPermissionRequest;
    private OfflineDatabase offlineDatabase;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); immersive();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        offlineDatabase=new OfflineDatabase(this);
        webView=new WebView(this); webView.setBackgroundColor(Color.WHITE); webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); webView.setVerticalScrollBarEnabled(true); webView.setHorizontalScrollBarEnabled(false); webView.setScrollbarFadingEnabled(false); webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY); webView.setFocusable(true); webView.setFocusableInTouchMode(true); webView.requestFocus(View.FOCUS_DOWN);
        webView.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN&&!v.hasFocus())v.requestFocus();return false;});
        webView.addJavascriptInterface(new OfflineBridge(offlineDatabase),"DMMNative");
        FrameLayout frame=new FrameLayout(this); frame.setBackgroundColor(Color.WHITE); frame.addView(webView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT)); setContentView(frame);
        WebSettings settings=webView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setDatabaseEnabled(true); settings.setLoadsImagesAutomatically(true); settings.setSupportZoom(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false); settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true); settings.setMediaPlaybackRequiresUserGesture(false); settings.setJavaScriptCanOpenWindowsAutomatically(true); settings.setSupportMultipleWindows(false); settings.setAllowFileAccess(true); settings.setAllowContentAccess(true); settings.setCacheMode(WebSettings.LOAD_DEFAULT); settings.setUserAgentString(settings.getUserAgentString()+" DMM-Android-Driver/3.07 SQLiteOffline/3 AtomicCompletion/2 PortalAdapter/1");
        CookieManager cookies=CookieManager.getInstance(); cookies.setAcceptCookie(true); cookies.setAcceptThirdPartyCookies(webView,true);
        webView.setWebViewClient(new WebViewClient(){
            private boolean external(String url){if(url==null||url.isEmpty())return false;try{Uri u=Uri.parse(url);String s=u.getScheme()==null?"":u.getScheme().toLowerCase(),h=u.getHost()==null?"":u.getHost().toLowerCase();if("tel".equals(s)){startActivity(new Intent(Intent.ACTION_DIAL,u));return true;}if("geo".equals(s)||"google.navigation".equals(s)){startActivity(new Intent(Intent.ACTION_VIEW,u));return true;}if("intent".equals(s)){startActivity(Intent.parseUri(url,Intent.URI_INTENT_SCHEME));return true;}if(("http".equals(s)||"https".equals(s))&&((h.contains("google.com")&&url.toLowerCase().contains("maps"))||h.contains("maps.google")||h.contains("waze.com"))){startActivity(new Intent(Intent.ACTION_VIEW,u));return true;}}catch(Exception ignored){}return false;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return external(r!=null&&r.getUrl()!=null?r.getUrl().toString():null);}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return external(u);}
            @Override public void onPageFinished(WebView v,String u){super.onPageFinished(v,u);v.requestFocus(View.FOCUS_DOWN);PortalAdapter.install(v);}
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams p){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=cb;Intent i=p.createIntent();i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,FILE_CHOOSER_REQUEST);return true;}catch(Exception e){filePathCallback=null;return false;}}
            @Override public void onPermissionRequest(final PermissionRequest r){runOnUiThread(()->{boolean cam=false;for(String x:r.getResources())if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)){cam=true;break;}if(!cam){r.deny();return;}if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});else{pendingCameraPermissionRequest=r;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);}});}
        });
        if(savedInstanceState==null)webView.loadUrl(DMM_URL);else webView.restoreState(savedInstanceState);
    }
    @Override protected void onActivityResult(int c,int r,Intent d){if(c==FILE_CHOOSER_REQUEST){if(filePathCallback!=null){filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r,d));filePathCallback=null;}return;}super.onActivityResult(c,r,d);}
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] g){super.onRequestPermissionsResult(c,p,g);if(c==CAMERA_PERMISSION_REQUEST&&pendingCameraPermissionRequest!=null){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)pendingCameraPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});else pendingCameraPermissionRequest.deny();pendingCameraPermissionRequest=null;}}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle o){if(webView!=null)webView.saveState(o);super.onSaveInstanceState(o);}
    @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)immersive();}
    @Override protected void onDestroy(){if(webView!=null){webView.removeJavascriptInterface("DMMNative");webView.destroy();webView=null;}if(offlineDatabase!=null){offlineDatabase.close();offlineDatabase=null;}super.onDestroy();}
}
