package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        immersive();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(true);
        webView.setScrollbarFadingEnabled(false);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.WHITE);
        frame.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(frame);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setUserAgentString(settings.getUserAgentString() + " DMM-Android-Driver/2.18.34");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            private boolean handleExternalUrl(String url) {
                if (url == null || url.isEmpty()) return false;
                try {
                    Uri uri = Uri.parse(url);
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

                    if ("tel".equals(scheme)) {
                        startActivity(new Intent(Intent.ACTION_DIAL, uri));
                        return true;
                    }

                    if ("geo".equals(scheme) || "google.navigation".equals(scheme)) {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    }

                    if ("intent".equals(scheme)) {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        startActivity(intent);
                        return true;
                    }

                    if (("http".equals(scheme) || "https".equals(scheme)) &&
                        ((host.contains("google.com") && url.toLowerCase().contains("maps")) ||
                         host.contains("maps.google") || host.contains("waze.com"))) {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                return handleExternalUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectAndroidBridgeState();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception ex) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            wantsCamera = true;
                            break;
                        }
                    }

                    if (!wantsCamera) {
                        request.deny();
                        return;
                    }

                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCameraPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                    }
                });
            }
        });

        registerConnectivityWatcher();

        if (savedInstanceState == null) {
            webView.loadUrl(DMM_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            return info != null && info.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void registerConnectivityWatcher() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    if (webView == null) return;
                    webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                    webView.evaluateJavascript(
                        "window.__DMM_ANDROID_ONLINE=true;window.dispatchEvent(new Event('online'));",
                        null
                    );
                });
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> {
                    if (webView == null) return;
                    webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    webView.evaluateJavascript(
                        "window.__DMM_ANDROID_ONLINE=false;window.dispatchEvent(new Event('offline'));",
                        null
                    );
                });
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder().build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void injectAndroidBridgeState() {
        if (webView == null) return;
        final boolean online = isOnline();
        webView.evaluateJavascript(
            "window.__DMM_ANDROID_APK=true;" +
            "window.__DMM_ANDROID_ONLINE=" + (online ? "true" : "false") + ";" +
            "document.documentElement.classList.add('dmm-android-apk');" +
            "document.body.classList.add('dmm-android-apk');" +
            "(function(){" +
            "var s=document.getElementById('dmm-apk-edge-css');" +
            "if(!s){s=document.createElement('style');s.id='dmm-apk-edge-css';" +
            "s.textContent='html.dmm-android-apk,body.dmm-android-apk{margin:0!important;padding:0!important;background:#fff!important;overflow:auto!important;min-height:100%!important} body.dmm-android-apk .driver-shell,body.dmm-android-apk .driver-portal-shell,body.dmm-android-apk #driverPortal{max-width:none!important;width:100%!important;margin:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;padding-left:0!important;padding-right:0!important} body.dmm-android-apk .driver-tab-content,body.dmm-android-apk .driver-content,body.dmm-android-apk .finalise-job-content,body.dmm-android-apk .required-data-content,body.dmm-android-apk [class*=finalise],body.dmm-android-apk [class*=required],body.dmm-android-apk [class*=terms]{-webkit-overflow-scrolling:touch!important;overscroll-behavior:auto!important}';" +
            "document.head.appendChild(s);}" +
            "document.documentElement.style.overflow='auto';document.body.style.overflow='auto';" +
            "window.dispatchEvent(new Event(window.__DMM_ANDROID_ONLINE?'online':'offline'));" +
            "})();",
            null
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && pendingCameraPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingCameraPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingCameraPermissionRequest.deny();
            }
            pendingCameraPermissionRequest = null;
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
