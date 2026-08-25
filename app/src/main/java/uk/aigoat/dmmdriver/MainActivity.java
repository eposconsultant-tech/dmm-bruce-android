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
    private static final int PICK_PHOTO_REQUEST = 4201;
    private static final int CAMERA_REQUEST = 4202;
    private static final int CAMERA_PERMISSION_REQUEST = 4203;
    private WebView webView;
    private OfflineDatabase offlineDatabase;
    private Button pendingButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private final Runnable pendingRefresh = new Runnable(){@Override public void run(){refreshPendingButton();uiHandler.postDelayed(this,1500);}};

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);offlineDatabase=new OfflineDatabase(this);
        webView=new WebView(this);webView.setBackgroundColor(Color.WHITE);webView.setFocusable(true);webView.setFocusableInTouchMode(true);webView.addJavascriptInterface(new OfflineBridge(offlineDatabase),"DMMNative");
        FrameLayout frame=new FrameLayout(this);frame.addView(webView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        pendingButton=new Button(this);pendingButton.setAllCaps(false);pendingButton.setTextSize(10f);pendingButton.setTextColor(Color.WHITE);pendingButton.setBackgroundColor(Color.rgb(17,24,39));pendingButton.setPadding(dp(10),dp(3),dp(10),dp(3));pendingButton.setOnClickListener(v->showPendingDetails());
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,dp(40));bp.gravity=Gravity.TOP|Gravity.END;bp.topMargin=dp(6);bp.rightMargin=dp(8);frame.addView(pendingButton,bp);setContentView(frame);
        WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setLoadsImagesAutomatically(true);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowFileAccess(true);s.setAllowContentAccess(true);s.setCacheMode(isOnline()?WebSettings.LOAD_DEFAULT:WebSettings.LOAD_CACHE_ELSE_NETWORK);s.setUserAgentString(s.getUserAgentString()+" DMM-Android-Driver/3.23 ExternalIntents/1 FastSync/1 StickyPending/2 NativeCamera/2");
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);installOfflineAndFastSync(view);refreshPendingButton();}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return handleExternalUrl(request.getUrl()==null?null:request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return handleExternalUrl(url);}
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams params){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=cb;new AlertDialog.Builder(MainActivity.this).setTitle("Delivery Photograph").setItems(new String[]{"Take Photo","Select Photo"},(d,which)->{if(which==0)startCameraFlow();else startGalleryFlow();}).setOnCancelListener(d->clearFileCallback()).show();return true;}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){String m=message==null?"":message;String lower=m.toLowerCase();if((lower.contains("saved on this device")&&lower.contains("cloud upload failed"))||(lower.contains("updated on this device")&&lower.contains("cloud upload failed"))||"failed to fetch".equals(lower.trim())){result.confirm();refreshPendingButton();Toast.makeText(MainActivity.this,"Saved offline · Ready to send",Toast.LENGTH_SHORT).show();return true;}return super.onJsAlert(view,url,message,result);}
        });
        uiHandler.post(pendingRefresh);if(savedInstanceState==null)webView.loadUrl(DMM_URL);else webView.restoreState(savedInstanceState);
    }

    private boolean handleExternalUrl(String url){
        if(url==null||url.isEmpty())return false;
        try{
            Uri uri=Uri.parse(url);String scheme=uri.getScheme();if(scheme==null)return false;scheme=scheme.toLowerCase();
            if("http".equals(scheme)||"https".equals(scheme))return false;
            if("tel".equals(scheme)){startActivity(new Intent(Intent.ACTION_DIAL,uri));return true;}
            if("geo".equals(scheme)){startActivity(new Intent(Intent.ACTION_VIEW,uri));return true;}
            if("intent".equals(scheme)){
                try{Intent intent=Intent.parseUri(url,Intent.URI_INTENT_SCHEME);if(intent.resolveActivity(getPackageManager())!=null){startActivity(intent);return true;}String fallback=intent.getStringExtra("browser_fallback_url");if(fallback!=null&&!fallback.isEmpty()){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(fallback)));return true;}Intent selector=intent.getSelector();if(selector!=null&&selector.getData()!=null){startActivity(new Intent(Intent.ACTION_VIEW,selector.getData()));return true;}}catch(Exception ignored){}
                String q=null;int qi=url.indexOf("query=");if(qi>=0){q=url.substring(qi+6);int amp=q.indexOf('&');if(amp>=0)q=q.substring(0,amp);}Uri maps=q==null?Uri.parse("https://www.google.com/maps"):Uri.parse("https://www.google.com/maps/search/?api=1&query="+q);startActivity(new Intent(Intent.ACTION_VIEW,maps));return true;
            }
            Intent external=new Intent(Intent.ACTION_VIEW,uri);if(external.resolveActivity(getPackageManager())!=null){startActivity(external);return true;}
        }catch(Exception ex){Toast.makeText(this,"Unable to open this link",Toast.LENGTH_SHORT).show();return true;}
        return false;
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean isOnline(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();if(n==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(n);return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}catch(Exception ex){return false;}}

    private void installOfflineAndFastSync(WebView view){
        String js="(function(){try{"+
            "if(window.__DMM323)return;window.__DMM323=true;if(!window.DMMNative)return;"+
            "var KEY='dmmJobsV3',os=Storage.prototype.setItem,og=Storage.prototype.getItem,or=Storage.prototype.removeItem;"+
            "function arr(v){try{var a=JSON.parse(v||'[]');return Array.isArray(a)?a:[];}catch(e){return[];}}"+
            "function idOf(j){return j&&j.id!=null?String(j.id):'';}"+
            "Storage.prototype.setItem=function(k,v){if(this===localStorage&&k===KEY){var incoming=arr(String(v)),existing=arr(DMMNative.getJobsJson()),old={};for(var x=0;x<existing.length;x++){var ei=existing[x],eid=idOf(ei);if(eid)old[eid]=String(ei.status||'').toLowerCase();}for(var i=0;i<incoming.length;i++){var j=incoming[i],jid=idOf(j),ns=String((j&&j.status)||'').toLowerCase(),was=jid?old[jid]:'';var becameDelivered=(ns==='delivered'&&was!=='delivered');var mustQueue=(j&&j._pendingCloudUpload===true)||(!navigator.onLine&&becameDelivered);if(mustQueue){var r=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!r.ok)throw new Error(r.error||'SQLite completion verification failed');j._pendingCloudUpload=true;}}if(!DMMNative.setJobsJson(JSON.stringify(incoming)))throw new Error('SQLite jobs write failed');return;}return os.call(this,k,v);};"+
            "Storage.prototype.getItem=function(k){if(this===localStorage&&k===KEY){try{var n=DMMNative.getJobsJson();if(n!=null)return n;}catch(e){}}return og.call(this,k);};"+
            "Storage.prototype.removeItem=function(k){if(this===localStorage&&k===KEY){try{DMMNative.removeItem(KEY);}catch(e){}return;}return or.call(this,k);};"+
            "try{var seed=og.call(localStorage,KEY);if(seed)DMMNative.setJobsJson(seed);}catch(e){}"+
            "var nativeFetch=window.fetch;window.fetch=function(input,init){var body='';try{body=init&&typeof init.body==='string'?init.body:'';}catch(e){}return nativeFetch.apply(this,arguments).then(function(resp){try{if(resp&&resp.ok&&body){var p=JSON.parse(DMMNative.pendingDetails()||'[]');for(var i=0;i<p.length;i++){var id=String(p[i].jobId||'');if(id&&body.indexOf(id)>=0){DMMNative.markJobSynced(id);}}}}catch(e){}return resp;});};"+
            "function clickSend(){try{if(!navigator.onLine||DMMNative.pendingCount()<=0)return;var els=document.querySelectorAll('button,a,[role=button]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();if(t.indexOf('upload pending')>=0||t.indexOf('send now')>=0){els[i].click();return;}}}catch(e){}}"+
            "window.addEventListener('online',function(){setTimeout(clickSend,400);});if(navigator.onLine&&DMMNative.pendingCount()>0)setTimeout(clickSend,900);"+
        "}catch(e){console.error('DMM v3.23 install failed',e);}})();";view.evaluateJavascript(js,null);
    }

    private void refreshPendingButton(){if(pendingButton==null||offlineDatabase==null)return;runOnUiThread(()->{int n=0;try{n=offlineDatabase.pendingCount();}catch(Exception ignored){}boolean online=isOnline();if(online)pendingButton.setText(n>0?"ONLINE · "+n+" READY TO SEND":"ONLINE · ALL SYNCED ✓");else pendingButton.setText(n>0?"OFFLINE · "+n+" READY TO SEND":"OFFLINE · 0 PENDING");});}
    private void showPendingDetails(){StringBuilder text=new StringBuilder();text.append(isOnline()?"Network: ONLINE\n\n":"Network: OFFLINE\n\n");try{JSONArray rows=new JSONArray(offlineDatabase.pendingDetailsJson());if(rows.length()==0)text.append("No local SQLite jobs are waiting to send.");else for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;JSONObject job=row.optJSONObject("job");String number=job==null?row.optString("jobId","?"):job.optString("jobNumber",row.optString("jobId","?"));String customer=job==null?"":job.optString("customerName",job.optString("businessName",""));String status=job==null?row.optString("status","pending"):job.optString("status",row.optString("status","pending"));text.append("Job #").append(number).append(" · ").append(status);if(!customer.isEmpty())text.append("\n").append(customer);if(i<rows.length()-1)text.append("\n\n");}}catch(Exception ex){text.append("Unable to read local pending data.\n").append(ex.getMessage());}new AlertDialog.Builder(this).setTitle("Local SQLite · Ready to Send").setMessage(text.toString()).setPositiveButton("OK",null).show();}
    private void startCameraFlow(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);return;}try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"dmm_delivery_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");pendingCameraUri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(pendingCameraUri==null)throw new IllegalStateException("Unable to create camera image URI");Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);camera.putExtra(MediaStore.EXTRA_OUTPUT,pendingCameraUri);camera.setClipData(ClipData.newRawUri("DMM delivery photo",pendingCameraUri));camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(camera,CAMERA_REQUEST);}catch(Exception ex){Toast.makeText(this,"Camera could not be opened · Select Photo instead",Toast.LENGTH_LONG).show();startGalleryFlow();}}
    private void startGalleryFlow(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,PICK_PHOTO_REQUEST);}catch(Exception ex){Toast.makeText(this,"Unable to open photos",Toast.LENGTH_LONG).show();clearFileCallback();}}
    private void clearFileCallback(){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=null;pendingCameraUri=null;}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){if(requestCode==CAMERA_REQUEST){Uri result=(resultCode==RESULT_OK)?pendingCameraUri:null;if(filePathCallback!=null)filePathCallback.onReceiveValue(result==null?null:new Uri[]{result});if(result==null&&pendingCameraUri!=null){try{getContentResolver().delete(pendingCameraUri,null,null);}catch(Exception ignored){}}filePathCallback=null;pendingCameraUri=null;return;}if(requestCode==PICK_PHOTO_REQUEST){Uri uri=resultCode==RESULT_OK&&data!=null?data.getData():null;if(filePathCallback!=null)filePathCallback.onReceiveValue(uri==null?null:new Uri[]{uri});filePathCallback=null;return;}super.onActivityResult(requestCode,resultCode,data);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==CAMERA_PERMISSION_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startCameraFlow();else{Toast.makeText(this,"Camera permission denied · Select Photo instead",Toast.LENGTH_LONG).show();startGalleryFlow();}}}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle outState){if(webView!=null)webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onDestroy(){uiHandler.removeCallbacks(pendingRefresh);if(webView!=null){webView.removeJavascriptInterface("DMMNative");webView.destroy();webView=null;}if(offlineDatabase!=null){offlineDatabase.close();offlineDatabase=null;}super.onDestroy();}
}
