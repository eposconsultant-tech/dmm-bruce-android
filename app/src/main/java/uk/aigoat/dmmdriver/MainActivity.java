package uk.aigoat.dmmdriver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DMM_URL = "https://dmm.aigoat.uk/";
    private static final int FILE_CHOOSER_REQUEST = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private OfflineDbHelper offlineDb;
    private TextView syncStatus;
    private Button downloadButton;
    private Button offlineButton;
    private Button uploadButton;
    private boolean uploadWhenReady = false;
    private boolean showingOffline = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        immersive();
        offlineDb = new OfflineDbHelper(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(10, 8, 10, 8);
        bar.setBackgroundColor(Color.rgb(20, 26, 35));

        downloadButton = makeBarButton("↓ Download Day");
        offlineButton = makeBarButton("Offline Jobs");
        uploadButton = makeBarButton("↑ Upload Pending");
        syncStatus = new TextView(this);
        syncStatus.setTextColor(Color.WHITE);
        syncStatus.setTextSize(12f);
        syncStatus.setPadding(16, 10, 8, 0);

        bar.addView(downloadButton);
        bar.addView(offlineButton);
        bar.addView(uploadButton);
        bar.addView(syncStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new OfflineBridge(), "AndroidOffline");

        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        configureWebView();
        downloadButton.setOnClickListener(v -> downloadSelectedDay());
        offlineButton.setOnClickListener(v -> showOfflineJobs());
        uploadButton.setOnClickListener(v -> uploadPending());
        updateStatus();

        if (savedInstanceState == null) webView.loadUrl(DMM_URL);
        else webView.restoreState(savedInstanceState);
    }

    private Button makeBarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setMinHeight(42);
        return b;
    }

    private void configureWebView() {
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
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUserAgentString(settings.getUserAgentString() + " DMM-Android-Driver/2.1-OfflineSQLite");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("https://dmm.aigoat.uk")) {
                    showingOffline = false;
                    updateStatus();
                    if (uploadWhenReady) {
                        uploadWhenReady = false;
                        view.postDelayed(() -> doUploadPending(), 1000);
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && offlineDb.getSnapshotCount() > 0) {
                    showOfflineJobs();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
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
        });
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    private String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(new Date());
    }

    private void updateStatus() {
        runOnUiThread(() -> {
            int pending = offlineDb.getPendingCount();
            String mode = isOnline() ? "ONLINE" : "OFFLINE";
            syncStatus.setText(mode + "  •  " + pending + " pending  •  SQLite " + offlineDb.getSnapshotCount() + " day(s)");
            uploadButton.setText(pending > 0 ? "↑ Upload Pending (" + pending + ")" : "↑ Upload Pending");
        });
    }

    private void downloadSelectedDay() {
        if (!isOnline()) {
            Toast.makeText(this, "No internet. Existing SQLite jobs are still available offline.", Toast.LENGTH_LONG).show();
            return;
        }
        showingOffline = false;
        downloadButton.setEnabled(false);
        syncStatus.setText("Downloading selected day's jobs…");

        String js = "(function(){try{" +
                "if(typeof jobs==='undefined'||typeof activeDriverId==='undefined'||!activeDriverId) return JSON.stringify({error:'Log in as a driver first'});" +
                "var d=(typeof driverSelectedDate!=='undefined'&&driverSelectedDate)?driverSelectedDate:new Date();" +
                "var ds=(typeof isoDate==='function')?isoDate(d):d.toISOString().slice(0,10);" +
                "var driver=(typeof driverById==='function')?driverById(activeDriverId):null;" +
                "var dayJobs=(typeof jobsForDriverOnDate==='function')?jobsForDriverOnDate(driver,ds):jobs.filter(function(j){return String(j.driverId)===String(activeDriverId)&&String(j.date)===String(ds);});" +
                "var customerIds={};dayJobs.forEach(function(j){customerIds[String(j.customerId||'')]=true;});" +
                "var cs=(typeof customers!=='undefined'?customers:[]).filter(function(c){return customerIds[String(c.id||'')];});" +
                "return JSON.stringify({format:'DMM_DRIVER_OFFLINE_V1',downloadedAt:new Date().toISOString(),driverId:String(activeDriverId),driverName:driver?driver.name:'',jobDate:ds,jobs:dayJobs,customers:cs,products:(typeof products!=='undefined'?products:[]),requiredData:(typeof requiredData!=='undefined'?requiredData:[]),siteSettings:(typeof siteSettings!=='undefined'?siteSettings:{}),trucks:(typeof trucks!=='undefined'?trucks:[]),drivers:(typeof drivers!=='undefined'?drivers:[])});" +
                "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})();";

        webView.evaluateJavascript(js, value -> {
            downloadButton.setEnabled(true);
            try {
                String json = new JSONArray("[" + value + "]").getString(0);
                JSONObject data = new JSONObject(json);
                if (data.has("error")) throw new Exception(data.getString("error"));
                offlineDb.saveSnapshot(data.getString("driverId"), data.getString("jobDate"), json, data.optString("downloadedAt", nowIso()));
                int count = data.optJSONArray("jobs") == null ? 0 : data.optJSONArray("jobs").length();
                Toast.makeText(this, "Downloaded " + count + " job(s) for " + data.optString("jobDate") + " to SQLite.", Toast.LENGTH_LONG).show();
                updateStatus();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                updateStatus();
            }
        });
    }

    private void showOfflineJobs() {
        String snapshot = offlineDb.getLatestSnapshot();
        if (snapshot == null || snapshot.trim().isEmpty()) {
            Toast.makeText(this, "No downloaded jobs yet. Go online and press Download Day first.", Toast.LENGTH_LONG).show();
            return;
        }
        showingOffline = true;
        String encoded = Base64.encodeToString(snapshot.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        webView.loadDataWithBaseURL("https://dmm-offline.local/", buildOfflineHtml(encoded), "text/html", "UTF-8", null);
        updateStatus();
    }

    private String buildOfflineHtml(String encodedSnapshot) {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>body{font-family:Arial,sans-serif;margin:0;background:#eef2f6;color:#172033}header{background:#111827;color:#fff;padding:12px 16px;position:sticky;top:0;z-index:5}header b{font-size:18px}header span{display:block;font-size:12px;opacity:.8;margin-top:3px}.wrap{padding:12px}.job{background:#fff;border:1px solid #d8e0e8;border-radius:12px;margin-bottom:12px;overflow:hidden}.head{display:flex;justify-content:space-between;gap:8px;background:#f8fafc;padding:10px 12px;border-bottom:1px solid #e5e7eb}.head strong{font-size:17px}.body{padding:12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.field{margin:9px 0}.field label{display:block;font-weight:bold;font-size:12px;margin-bottom:4px}.field input,.field textarea{width:100%;box-sizing:border-box;padding:9px;border:1px solid #cbd5e1;border-radius:8px}.actions{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.actions button,.save{padding:10px 13px;border:0;border-radius:8px;font-weight:bold}.start{background:#2563eb;color:white}.end{background:#dc2626;color:white}.complete{background:#16a34a;color:white}.save{background:#111827;color:white}.proof{display:grid;grid-template-columns:1fr 1fr;gap:12px}.sig{border:1px solid #cbd5e1;background:#fff;width:100%;height:140px;touch-action:none}.photo img{max-width:100%;max-height:150px;border-radius:8px}.req{padding:8px;border-left:4px solid #2563eb;background:#eff6ff;margin:7px 0;border-radius:6px}.audit{font-size:11px;color:#64748b;background:#f8fafc;padding:8px;border-radius:7px}.badge{font-size:11px;font-weight:bold;padding:4px 8px;border-radius:999px;background:#e2e8f0}.delivered{background:#dcfce7;color:#166534}@media(max-width:700px){.grid,.proof{grid-template-columns:1fr}}</style></head><body>" +
                "<header><b>DMM Driver — OFFLINE SQLITE</b><span id='sub'>Downloaded jobs stored on this tablet</span></header><div class='wrap' id='root'></div>" +
                "<script>const SNAP=JSON.parse(decodeURIComponent(escape(atob('" + encodedSnapshot + "'))));" +
                "const C=Object.fromEntries((SNAP.customers||[]).map(x=>[String(x.id),x]));const P=Object.fromEntries((SNAP.products||[]).map(x=>[String(x.id),x]));const R=Object.fromEntries((SNAP.requiredData||[]).map(x=>[String(x.id),x]));" +
                "document.getElementById('sub').textContent=(SNAP.driverName||'Driver')+' • '+SNAP.jobDate+' • downloaded '+new Date(SNAP.downloadedAt).toLocaleString();" +
                "function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[m]));}" +
                "function customerName(j){let c=C[String(j.customerId)]||{};return c.businessName||c.tradingName||[c.firstName,c.surname].filter(Boolean).join(' ')||'Customer';}" +
                "function reqs(j){let out=[];(j.items||[]).forEach(line=>{let p=P[String(line.productId)]||{};(p.requiredDataIds||[]).forEach(rid=>{let r=R[String(rid)];if(r&&r.active!==false&&r.driverCompulsory)(r.items||[]).forEach(it=>out.push({lineId:String(line.id||''),req:r,item:it,product:p}));});});return out;}" +
                "function answer(j,e){let a=(j.requiredDataAnswers||[]).find(x=>String(x.lineId)===e.lineId&&String(x.requirementId)===String(e.req.id)&&String(x.itemId)===String(e.item.id));return a?String(a.value||''):'';}" +
                "function render(){let root=document.getElementById('root');root.innerHTML='';(SNAP.jobs||[]).forEach((j,idx)=>{let c=C[String(j.customerId)]||{};let rs=reqs(j);let el=document.createElement('section');el.className='job';el.innerHTML=`<div class='head'><div><strong>${esc(j.time||'—')} · ${esc(customerName(j))}</strong><div>${esc(c.postcode||'')} · Job ${esc(j.jobNumber||j.id)}</div></div><span class='badge ${String(j.status)==='Delivered'?'delivered':''}'>${esc(j.status||'Booked')}</span></div><div class='body'><div class='grid'><div><b>Products</b><div>${(j.items||[]).map(x=>esc((P[String(x.productId)]||{}).name||x.description||'Product')).join('<br>')}</div></div><div><b>Address</b><div>${esc([c.address1,c.address2,c.address3,c.postcode].filter(Boolean).join(', '))}</div></div></div><div class='actions'><button class='start' onclick='stamp(${idx},\"driverStartedAt\")'>START</button><button class='end' onclick='stamp(${idx},\"driverEndedAt\")'>END</button></div><div class='audit' id='audit-${idx}'></div><h4>Required Data</h4><div>${rs.length?rs.map((e,n)=>`<div class='req'><div>${esc(e.req.header||e.req.name||'Required')} — ${esc(e.item.name||e.item.label||'Value')}</div><input data-req='${idx}' data-n='${n}' value='${esc(answer(j,e))}'></div>`).join(''):'No compulsory required data for this job.'}</div><div class='field'><label>Driver finalise notes</label><textarea id='notes-${idx}'>${esc(j.driverFinaliseNotes||'')}</textarea></div><div class='proof'><div class='photo'><b>Delivery Photo</b><input id='photo-${idx}' type='file' accept='image/*' capture='environment' onchange='photoPick(${idx},this)'><div id='photoPreview-${idx}'>${j.deliveryPhoto?`<img src='${j.deliveryPhoto}'>`:'No photo stored'}</div></div><div><b>Customer Signature</b><canvas class='sig' id='sig-${idx}' width='600' height='220'></canvas><button onclick='clearSig(${idx})'>Clear</button></div></div><div class='actions'><button class='save' onclick='saveJob(${idx},false)'>Save Offline Progress</button><button class='complete' onclick='saveJob(${idx},true)'>✓ COMPLETE OFFLINE</button></div></div>`;root.appendChild(el);setupSig(idx,j.signatureData);audit(idx);});}" +
                "function audit(i){let j=SNAP.jobs[i];document.getElementById('audit-'+i).textContent='Start: '+(j.driverStartedAt||'—')+' | End: '+(j.driverEndedAt||'—')+' | Completed: '+(j.signedOffAt||'—')+' | Modified: '+(j.modifiedAt||'—');}" +
                "function stamp(i,k){let j=SNAP.jobs[i],t=new Date().toISOString();j[k]=t;j.modifiedAt=t;if(k==='driverStartedAt')j.status='Driver on route';saveJob(i,false);audit(i);}" +
                "function photoPick(i,input){let f=input.files&&input.files[0];if(!f)return;let r=new FileReader();r.onload=()=>{SNAP.jobs[i].deliveryPhoto=r.result;document.getElementById('photoPreview-'+i).innerHTML=`<img src='${r.result}'>`;};r.readAsDataURL(f);}" +
                "function setupSig(i,data){let c=document.getElementById('sig-'+i),x=c.getContext('2d'),down=false,last=null;if(data){let im=new Image();im.onload=()=>x.drawImage(im,0,0,c.width,c.height);im.src=data;}function pt(e){let r=c.getBoundingClientRect(),t=e.touches?e.touches[0]:e;return{x:(t.clientX-r.left)*c.width/r.width,y:(t.clientY-r.top)*c.height/r.height};}function s(e){e.preventDefault();down=true;last=pt(e)}function m(e){if(!down)return;e.preventDefault();let p=pt(e);x.beginPath();x.moveTo(last.x,last.y);x.lineTo(p.x,p.y);x.lineWidth=3;x.lineCap='round';x.stroke();last=p}function u(){down=false}c.onmousedown=s;c.onmousemove=m;c.onmouseup=u;c.onmouseleave=u;c.ontouchstart=s;c.ontouchmove=m;c.ontouchend=u;}" +
                "function clearSig(i){let c=document.getElementById('sig-'+i);c.getContext('2d').clearRect(0,0,c.width,c.height);}" +
                "function ink(c){let d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;for(let i=3;i<d.length;i+=4)if(d[i])return true;return false;}" +
                "function saveJob(i,complete){let j=SNAP.jobs[i],rs=reqs(j);document.querySelectorAll(`[data-req='${i}']`).forEach(inp=>{let e=rs[Number(inp.dataset.n)],v=String(inp.value||'').trim();j.requiredDataAnswers=j.requiredDataAnswers||[];let a=j.requiredDataAnswers.find(x=>String(x.lineId)===e.lineId&&String(x.requirementId)===String(e.req.id)&&String(x.itemId)===String(e.item.id));if(a)a.value=v;else j.requiredDataAnswers.push({lineId:e.lineId,requirementId:String(e.req.id),itemId:String(e.item.id),value:v,recordedBy:SNAP.driverName||'Driver',recordedAt:new Date().toISOString()});});j.driverFinaliseNotes=document.getElementById('notes-'+i).value||'';let sig=document.getElementById('sig-'+i);if(ink(sig))j.signatureData=sig.toDataURL('image/png');j.modifiedAt=new Date().toISOString();if(complete){let missing=rs.filter(e=>!answer(j,e).trim());if(missing.length)return alert('Complete all compulsory Required Data first.');if(!j.deliveryPhoto)return alert('Add the delivery photo first.');if(!j.signatureData)return alert('Capture the customer signature first.');if(!j.driverStartedAt)j.driverStartedAt=j.modifiedAt;if(!j.driverEndedAt)j.driverEndedAt=j.modifiedAt;j.status='Delivered';j.statusUpdatedAt=j.modifiedAt;j.signedOffAt=j.modifiedAt;j.deliveryRecordedAt=j.modifiedAt;j.lastModifiedBy=SNAP.driverName||'Driver';}AndroidOffline.queueJob(JSON.stringify(j));audit(i);render();}" +
                "render();</script></body></html>";
    }

    private void uploadPending() {
        int count = offlineDb.getPendingCount();
        if (count == 0) {
            Toast.makeText(this, "Nothing is waiting to upload.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isOnline()) {
            Toast.makeText(this, "Still offline. " + count + " change(s) remain safely queued in SQLite.", Toast.LENGTH_LONG).show();
            return;
        }
        String url = webView.getUrl();
        if (showingOffline || url == null || !url.startsWith("https://dmm.aigoat.uk")) {
            uploadWhenReady = true;
            syncStatus.setText("Opening live DMM to upload queued work…");
            webView.loadUrl(DMM_URL);
            return;
        }
        doUploadPending();
    }

    private void doUploadPending() {
        JSONArray pending = offlineDb.getPendingJobs();
        if (pending.length() == 0) { updateStatus(); return; }
        uploadButton.setEnabled(false);
        syncStatus.setText("Uploading " + pending.length() + " queued job(s)…");
        String payload = pending.toString();
        String js = "(async function(){try{" +
                "if(typeof jobs==='undefined'||typeof dmmDriverCloudCommit!=='function')throw new Error('Driver cloud session is not ready. Log in first.');" +
                "var pending=" + payload + ";" +
                "pending.forEach(function(p){var i=jobs.findIndex(function(j){return String(j.id)===String(p.id)});if(i>=0)jobs[i]=Object.assign({},jobs[i],p);else jobs.push(p);});" +
                "if(typeof saveAll==='function')saveAll();await dmmDriverCloudCommit();AndroidOffline.onUploadResult('OK');" +
                "}catch(e){AndroidOffline.onUploadResult('ERROR:'+String(e&&e.message||e));}})();";
        webView.evaluateJavascript(js, ignored -> { });
    }

    public class OfflineBridge {
        @JavascriptInterface
        public void queueJob(String json) {
            try {
                JSONObject job = new JSONObject(json);
                String id = job.optString("id", "");
                if (id.isEmpty()) throw new Exception("Job ID missing");
                offlineDb.queueJob(id, json, nowIso());
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Saved offline. Upload Pending when signal returns.", Toast.LENGTH_SHORT).show();
                    updateStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Offline save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onUploadResult(String result) {
            runOnUiThread(() -> {
                uploadButton.setEnabled(true);
                if ("OK".equals(result)) {
                    offlineDb.clearPending();
                    Toast.makeText(MainActivity.this, "Offline changes uploaded to DMM cloud successfully.", Toast.LENGTH_LONG).show();
                    updateStatus();
                    webView.reload();
                } else {
                    Toast.makeText(MainActivity.this, "Upload failed. SQLite queue kept safe.\n" + result, Toast.LENGTH_LONG).show();
                    updateStatus();
                }
            });
        }
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

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onBackPressed() {
        if (showingOffline) {
            showingOffline = false;
            webView.loadUrl(DMM_URL);
        } else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
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
}
