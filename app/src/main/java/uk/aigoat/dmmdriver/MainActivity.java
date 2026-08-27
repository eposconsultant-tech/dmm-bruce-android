package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
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
    private static final String DMM_HOST = "dmm.aigoat.uk";
    private static final int PICK_PHOTO_REQUEST = 4201;
    private static final int CAMERA_REQUEST = 4202;
    private static final int CAMERA_PERMISSION_REQUEST = 4203;

    private WebView webView;
    private OfflineDatabase offlineDatabase;
    private Button pendingButton;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        offlineDatabase = new OfflineDatabase(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.addJavascriptInterface(new OfflineBridge(offlineDatabase, this::refreshPendingButton), "DMMNative");

        FrameLayout frame = new FrameLayout(this);
        frame.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pendingButton = new Button(this);
        pendingButton.setAllCaps(false);
        pendingButton.setTextSize(10f);
        pendingButton.setTextColor(Color.WHITE);
        pendingButton.setBackgroundColor(Color.rgb(17, 24, 39));
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
        // The app only needs content:// access for Android's document/photo picker. Direct file:// access is not required.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(s.getUserAgentString() + " DMM-Android-Driver/3.24 ExternalIntents/2 RetryBackoff/1 StickyPending/3 NativeCamera/3");

        CookieManager.getInstance().setAcceptCookie(true);
        // Retained for current portal authentication compatibility; external main-frame navigation is now blocked from the native bridge WebView.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installOfflineAndFastSync(view);
                refreshPendingButton();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalUrl(request.getUrl() == null ? null : request.getUrl().toString());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalUrl(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                cancelFileSelection(true);
                filePathCallback = cb;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delivery Photograph")
                        .setItems(new String[]{"Take Photo", "Select Photo"}, (d, which) -> {
                            if (which == 0) startCameraFlow(); else startGalleryFlow();
                        })
                        .setOnCancelListener(d -> cancelFileSelection(true))
                        .show();
                return true;
            }

            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                String m = message == null ? "" : message;
                String lower = m.toLowerCase();
                if ((lower.contains("saved on this device") && lower.contains("cloud upload failed"))
                        || (lower.contains("updated on this device") && lower.contains("cloud upload failed"))
                        || "failed to fetch".equals(lower.trim())) {
                    result.confirm();
                    refreshPendingButton();
                    Toast.makeText(MainActivity.this, "Saved offline · Ready to send", Toast.LENGTH_SHORT).show();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        registerConnectivityObserver();
        refreshPendingButton();
        if (savedInstanceState == null) webView.loadUrl(DMM_URL); else webView.restoreState(savedInstanceState);
    }

    private boolean handleExternalUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase();

            if ("http".equals(scheme) || "https".equals(scheme)) {
                String host = uri.getHost();
                if (host != null && DMM_HOST.equalsIgnoreCase(host)) return false;
                Intent browser = new Intent(Intent.ACTION_VIEW, uri);
                if (browser.resolveActivity(getPackageManager()) != null) {
                    startActivity(browser);
                } else {
                    Toast.makeText(this, "Unable to open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            if ("tel".equals(scheme)) {
                Intent dial = new Intent(Intent.ACTION_DIAL, uri);
                if (dial.resolveActivity(getPackageManager()) != null) startActivity(dial);
                else Toast.makeText(this, "No phone app available", Toast.LENGTH_SHORT).show();
                return true;
            }

            if ("geo".equals(scheme)) {
                Intent geo = new Intent(Intent.ACTION_VIEW, uri);
                if (geo.resolveActivity(getPackageManager()) != null) startActivity(geo);
                else Toast.makeText(this, "No maps app available", Toast.LENGTH_SHORT).show();
                return true;
            }

            if ("intent".equals(scheme)) {
                try {
                    Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                        return true;
                    }
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                        return true;
                    }
                    Intent selector = intent.getSelector();
                    if (selector != null && selector.getData() != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, selector.getData()));
                        return true;
                    }
                } catch (Exception ignored) {}

                String q = null;
                int qi = url.indexOf("query=");
                if (qi >= 0) {
                    q = url.substring(qi + 6);
                    int amp = q.indexOf('&');
                    if (amp >= 0) q = q.substring(0, amp);
                }
                Uri maps = q == null ? Uri.parse("https://www.google.com/maps") : Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q);
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, maps);
                if (mapIntent.resolveActivity(getPackageManager()) != null) startActivity(mapIntent);
                else Toast.makeText(this, "Unable to open maps", Toast.LENGTH_SHORT).show();
                return true;
            }

            Intent external = new Intent(Intent.ACTION_VIEW, uri);
            if (external.resolveActivity(getPackageManager()) != null) {
                startActivity(external);
                return true;
            }
        } catch (Exception ex) {
            Toast.makeText(this, "Unable to open this link", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ex) {
            return false;
        }
    }

    private void registerConnectivityObserver() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { onNetworkChanged(true); }
                @Override public void onLost(Network network) { onNetworkChanged(false); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    onNetworkChanged(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                }
            };
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {}
    }

    private void onNetworkChanged(boolean online) {
        refreshPendingButton();
        if (online && webView != null) {
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript("window.dispatchEvent(new Event('online'));", null);
            });
        }
    }

    private void installOfflineAndFastSync(WebView view) {
        String js = "(function(){try{" +
                "if(window.__DMM324)return;window.__DMM324=true;if(!window.DMMNative)return;" +
                "var KEY='dmmJobsV3',os=Storage.prototype.setItem,og=Storage.prototype.getItem,or=Storage.prototype.removeItem,retryTimer=null;" +
                "function arr(v){try{var a=JSON.parse(v||'[]');return Array.isArray(a)?a:[];}catch(e){return[];}}" +
                "function idOf(j){return j&&j.id!=null?String(j.id):'';}" +
                "function pendingForBody(body){var hits=[];try{if(!body)return hits;var p=JSON.parse(DMMNative.pendingDetails()||'[]');for(var i=0;i<p.length;i++){var id=String(p[i].jobId||'');if(id&&body.indexOf(id)>=0)hits.push(id);}}catch(e){}return hits;}" +
                "function markFailed(ids,reason){try{for(var i=0;i<ids.length;i++)DMMNative.markSyncFailed(ids[i],String(reason||'Sync failed'));}catch(e){}scheduleRetry();}" +
                "function scheduleRetry(){try{if(retryTimer){clearTimeout(retryTimer);retryTimer=null;}if(DMMNative.pendingCount()<=0)return;retryTimer=setTimeout(function(){retryTimer=null;try{if(navigator.onLine&&DMMNative.retryReadyCount()>0)clickSend();}catch(e){}scheduleRetry();},5000);}catch(e){}}" +
                "Storage.prototype.setItem=function(k,v){if(this===localStorage&&k===KEY){var incoming=arr(String(v)),existing=arr(DMMNative.getJobsJson()),old={};for(var x=0;x<existing.length;x++){var ei=existing[x],eid=idOf(ei);if(eid)old[eid]=String(ei.status||'').toLowerCase();}for(var i=0;i<incoming.length;i++){var j=incoming[i],jid=idOf(j),ns=String((j&&j.status)||'').toLowerCase(),was=jid?old[jid]:'';var becameDelivered=(ns==='delivered'&&was!=='delivered');var mustQueue=(j&&j._pendingCloudUpload===true)||(!navigator.onLine&&becameDelivered);if(mustQueue){var r=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!r.ok)throw new Error(r.error||'SQLite completion verification failed');j._pendingCloudUpload=true;}}if(!DMMNative.setJobsJson(JSON.stringify(incoming)))throw new Error('SQLite jobs write failed');scheduleRetry();return;}return os.call(this,k,v);};" +
                "Storage.prototype.getItem=function(k){if(this===localStorage&&k===KEY){try{var n=DMMNative.getJobsJson();if(n!=null)return n;}catch(e){}}return og.call(this,k);};" +
                "Storage.prototype.removeItem=function(k){if(this===localStorage&&k===KEY){try{DMMNative.removeItem(KEY);}catch(e){}scheduleRetry();return;}return or.call(this,k);};" +
                "try{var seed=og.call(localStorage,KEY);if(seed)DMMNative.setJobsJson(seed);}catch(e){}" +
                "var nativeFetch=window.fetch;window.fetch=function(input,init){var body='';try{body=init&&typeof init.body==='string'?init.body:'';}catch(e){}var ids=pendingForBody(body);return nativeFetch.apply(this,arguments).then(function(resp){try{if(ids.length){if(resp&&resp.ok){for(var i=0;i<ids.length;i++)DMMNative.markJobSynced(ids[i]);}else{markFailed(ids,'HTTP '+(resp?resp.status:'unknown'));}}}catch(e){}scheduleRetry();return resp;}).catch(function(err){markFailed(ids,err&&err.message?err.message:'Network request failed');throw err;});};" +
                "function clickSend(){try{if(!navigator.onLine||DMMNative.pendingCount()<=0||DMMNative.retryReadyCount()<=0)return;var els=document.querySelectorAll('button,a,[role=button]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();if(t.indexOf('upload pending')>=0||t.indexOf('send now')>=0){els[i].click();return;}}}catch(e){}}" +
                "window.addEventListener('online',function(){setTimeout(clickSend,400);scheduleRetry();});" +
                "window.addEventListener('offline',function(){scheduleRetry();});" +
                "if(navigator.onLine&&DMMNative.pendingCount()>0)setTimeout(clickSend,900);scheduleRetry();" +
                "}catch(e){console.error('DMM v3.24 install failed',e);}})();";
        view.evaluateJavascript(js, null);
    }

    private void refreshPendingButton() {
        if (pendingButton == null || offlineDatabase == null) return;
        runOnUiThread(() -> {
            int n = 0;
            int ready = 0;
            try {
                n = offlineDatabase.pendingCount();
                ready = offlineDatabase.retryReadyCount();
            } catch (Exception ignored) {}
            boolean online = isOnline();
            if (online) {
                if (n <= 0) pendingButton.setText("ONLINE · ALL SYNCED ✓");
                else if (ready > 0) pendingButton.setText("ONLINE · " + n + " READY TO SEND");
                else pendingButton.setText("ONLINE · " + n + " RETRY WAIT");
            } else {
                pendingButton.setText(n > 0 ? "OFFLINE · " + n + " READY TO SEND" : "OFFLINE · 0 PENDING");
            }
        });
    }

    private void showPendingDetails() {
        StringBuilder text = new StringBuilder();
        text.append(isOnline() ? "Network: ONLINE\n\n" : "Network: OFFLINE\n\n");
        try {
            JSONArray rows = new JSONArray(offlineDatabase.pendingDetailsJson());
            if (rows.length() == 0) {
                text.append("No local SQLite jobs are waiting to send.");
            } else {
                long now = System.currentTimeMillis();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject job = row.optJSONObject("job");
                    String number = job == null ? row.optString("jobId", "?") : job.optString("jobNumber", row.optString("jobId", "?"));
                    String customer = job == null ? "" : job.optString("customerName", job.optString("businessName", ""));
                    String status = job == null ? row.optString("status", "pending") : job.optString("status", row.optString("status", "pending"));
                    int attempts = row.optInt("attempts", 0);
                    long nextRetryAt = row.optLong("nextRetryAt", 0);
                    text.append("Job #").append(number).append(" · ").append(status);
                    if (!customer.isEmpty()) text.append("\n").append(customer);
                    if (attempts > 0) text.append("\nRetry attempts: ").append(attempts);
                    if (nextRetryAt > now) text.append(" · next retry in ").append(Math.max(1, (nextRetryAt - now + 999) / 1000)).append("s");
                    if (!row.isNull("lastError")) text.append("\nLast error: ").append(row.optString("lastError", ""));
                    if (i < rows.length() - 1) text.append("\n\n");
                }
            }
        } catch (Exception ex) {
            text.append("Unable to read local pending data.\n").append(ex.getMessage());
        }
        new AlertDialog.Builder(this)
                .setTitle("Local SQLite · Ready to Send")
                .setMessage(text.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void startCameraFlow() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        try {
            releasePendingCameraUri();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "dmm_delivery_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingCameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (pendingCameraUri == null) throw new IllegalStateException("Unable to create camera image URI");
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.setClipData(ClipData.newRawUri("DMM delivery photo", pendingCameraUri));
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (camera.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("No camera application available");
            startActivityForResult(camera, CAMERA_REQUEST);
        } catch (Exception ex) {
            releasePendingCameraUri();
            Toast.makeText(this, "Camera could not be opened · Select Photo instead", Toast.LENGTH_LONG).show();
            startGalleryFlow();
        }
    }

    private void startGalleryFlow() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            if (i.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("No photo picker available");
            startActivityForResult(i, PICK_PHOTO_REQUEST);
        } catch (Exception ex) {
            Toast.makeText(this, "Unable to open photos", Toast.LENGTH_LONG).show();
            cancelFileSelection(true);
        }
    }

    private void releasePendingCameraUri() {
        if (pendingCameraUri != null) {
            try { getContentResolver().delete(pendingCameraUri, null, null); } catch (Exception ignored) {}
            pendingCameraUri = null;
        }
    }

    private void cancelFileSelection(boolean deletePendingCamera) {
        if (filePathCallback != null) {
            try { filePathCallback.onReceiveValue(null); } catch (Exception ignored) {}
        }
        filePathCallback = null;
        if (deletePendingCamera) releasePendingCameraUri();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CAMERA_REQUEST) {
            Uri result = (resultCode == RESULT_OK) ? pendingCameraUri : null;
            if (filePathCallback != null) filePathCallback.onReceiveValue(result == null ? null : new Uri[]{result});
            if (result == null) releasePendingCameraUri(); else pendingCameraUri = null;
            filePathCallback = null;
            return;
        }
        if (requestCode == PICK_PHOTO_REQUEST) {
            Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (filePathCallback != null) filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraFlow();
            } else {
                Toast.makeText(this, "Camera permission denied · Select Photo instead", Toast.LENGTH_LONG).show();
                startGalleryFlow();
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        networkCallback = null;
        connectivityManager = null;
        cancelFileSelection(true);
        if (webView != null) {
            webView.removeJavascriptInterface("DMMNative");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (offlineDatabase != null) {
            offlineDatabase.close();
            offlineDatabase = null;
        }
        super.onDestroy();
    }
}
