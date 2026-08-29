package uk.aigoat.dmmdriver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DMM_URL = "https://dmm.aigoat.uk/";
    private static final int PICK_PHOTO_REQUEST = 4201;
    private static final int CAMERA_REQUEST = 4202;
    private static final int CAMERA_PERMISSION_REQUEST = 4203;
    private static final int WEB_CAMERA_PERMISSION_REQUEST = 4204;
    private WebView webView;
    private OfflineDatabase offlineDatabase;
    private Button pendingButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private PermissionRequest pendingWebCameraRequest;
    private final Runnable pendingRefresh = new Runnable(){@Override public void run(){refreshPendingButton();uiHandler.postDelayed(this,5000);}};

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);offlineDatabase=new OfflineDatabase(this);
        webView=new WebView(this);webView.setBackgroundColor(Color.WHITE);webView.setFocusable(true);webView.setFocusableInTouchMode(true);webView.setVerticalScrollBarEnabled(false);webView.setHorizontalScrollBarEnabled(false);webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);webView.addJavascriptInterface(new OfflineBridge(offlineDatabase),"DMMNative");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),dp(4),dp(8),dp(4));
        GradientDrawable headerBackground=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.rgb(3,7,18),Color.rgb(15,23,42),Color.rgb(30,27,75)});header.setBackground(headerBackground);
        ImageView brandIcon=new ImageView(this);brandIcon.setImageResource(R.drawable.aigoat_driver_icon);brandIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);header.addView(brandIcon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brandCopy=new LinearLayout(this);brandCopy.setOrientation(LinearLayout.VERTICAL);brandCopy.setPadding(dp(9),0,dp(8),0);
        TextView brandTitle=new TextView(this);brandTitle.setText(R.string.brand_title);brandTitle.setTextColor(Color.WHITE);brandTitle.setTextSize(13f);brandTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);brandTitle.setSingleLine(true);brandCopy.addView(brandTitle);
        TextView brandSubTitle=new TextView(this);brandSubTitle.setText(R.string.brand_subtitle);brandSubTitle.setTextColor(Color.rgb(96,210,255));brandSubTitle.setTextSize(9.5f);brandSubTitle.setSingleLine(true);brandCopy.addView(brandSubTitle);header.addView(brandCopy,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT));
        View headerSpacer=new View(this);header.addView(headerSpacer,new LinearLayout.LayoutParams(0,1,1f));
        pendingButton=new Button(this);pendingButton.setAllCaps(false);pendingButton.setTextSize(9.5f);pendingButton.setTextColor(Color.WHITE);pendingButton.setPadding(dp(10),0,dp(10),0);GradientDrawable pendingBackground=new GradientDrawable();pendingBackground.setColor(Color.rgb(17,24,39));pendingBackground.setCornerRadius(dp(9));pendingBackground.setStroke(dp(1),Color.rgb(37,99,235));pendingButton.setBackground(pendingBackground);pendingButton.setOnClickListener(v->showPendingDetails());header.addView(pendingButton,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(38)));
        root.addView(header,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(50)));root.addView(webView,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);
        WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setLoadsImagesAutomatically(true);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);s.setCacheMode(isOnline()?WebSettings.LOAD_DEFAULT:WebSettings.LOAD_CACHE_ELSE_NETWORK);s.setUserAgentString(s.getUserAgentString()+" DMM-Android-Driver/3.28 ExternalIntents/2 FastSync/2 StickyPending/3 NativeCamera/3 WebCameraPermission/2 CameraPreviewAutoplay/1 NaturalTouchScroll/1 AiGOATBranding/1");
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);if(isOnline())view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);installOfflineAndFastSync(view);refreshPendingButton();}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return handleExternalUrl(request.getUrl()==null?null:request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return handleExternalUrl(url);}
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams params){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=cb;new AlertDialog.Builder(MainActivity.this).setTitle("Delivery Photograph").setItems(new String[]{"Take Photo","Select Photo"},(d,which)->{if(which==0)startCameraFlow();else startGalleryFlow();}).setOnCancelListener(d->clearFileCallback()).show();return true;}
            @Override public void onPermissionRequest(PermissionRequest request){runOnUiThread(()->handleWebPermissionRequest(request));}
            @Override public void onPermissionRequestCanceled(PermissionRequest request){runOnUiThread(()->{if(pendingWebCameraRequest==request)pendingWebCameraRequest=null;});}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){String m=message==null?"":message;String lower=m.toLowerCase(Locale.ROOT);if((lower.contains("saved on this device")&&lower.contains("cloud upload failed"))||(lower.contains("updated on this device")&&lower.contains("cloud upload failed"))||"failed to fetch".equals(lower.trim())){result.confirm();refreshPendingButton();Toast.makeText(MainActivity.this,"Saved offline · Ready to send",Toast.LENGTH_SHORT).show();return true;}return super.onJsAlert(view,url,message,result);}
        });
        uiHandler.post(pendingRefresh);if(savedInstanceState==null)webView.loadUrl(DMM_URL);else webView.restoreState(savedInstanceState);
    }

    private void handleWebPermissionRequest(PermissionRequest request){
        if(request==null)return;
        boolean wantsVideo=false;
        for(String resource:request.getResources()){if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)){wantsVideo=true;break;}}
        Uri origin=request.getOrigin();
        boolean trustedOrigin=origin!=null&&"https".equalsIgnoreCase(origin.getScheme())&&"dmm.aigoat.uk".equalsIgnoreCase(origin.getHost())&&(origin.getPort()==-1||origin.getPort()==443);
        if(!wantsVideo||!trustedOrigin){request.deny();if(wantsVideo)Toast.makeText(this,"Camera request blocked for an untrusted page",Toast.LENGTH_LONG).show();return;}
        if(pendingWebCameraRequest!=null&&pendingWebCameraRequest!=request){try{pendingWebCameraRequest.deny();}catch(Exception ignored){}}
        pendingWebCameraRequest=request;
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){grantPendingWebCameraPermission();}
        else{requestPermissions(new String[]{Manifest.permission.CAMERA},WEB_CAMERA_PERMISSION_REQUEST);}
    }

    private void grantPendingWebCameraPermission(){
        PermissionRequest request=pendingWebCameraRequest;pendingWebCameraRequest=null;if(request==null)return;
        Uri origin=request.getOrigin();
        boolean stillTrusted=origin!=null&&"https".equalsIgnoreCase(origin.getScheme())&&"dmm.aigoat.uk".equalsIgnoreCase(origin.getHost())&&(origin.getPort()==-1||origin.getPort()==443);
        if(isFinishing()||isDestroyed()||webView==null||!stillTrusted){try{request.deny();}catch(Exception ignored){}return;}
        try{request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});}
        catch(Exception ex){try{request.deny();}catch(Exception ignored){}Toast.makeText(this,"Camera permission could not be granted to the driver screen",Toast.LENGTH_LONG).show();}
    }

    private void denyPendingWebCameraPermission(){PermissionRequest request=pendingWebCameraRequest;pendingWebCameraRequest=null;if(request!=null){try{request.deny();}catch(Exception ignored){}}}

    private boolean handleExternalUrl(String url){
        if(url==null||url.isEmpty())return false;
        try{
            Uri uri=Uri.parse(url);String scheme=uri.getScheme();if(scheme==null)return false;scheme=scheme.toLowerCase(Locale.ROOT);
            if("http".equals(scheme)||"https".equals(scheme)){
                boolean trusted="https".equals(scheme)&&"dmm.aigoat.uk".equalsIgnoreCase(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443);
                if(trusted)return false;
                Intent browser=new Intent(Intent.ACTION_VIEW,uri);if(browser.resolveActivity(getPackageManager())!=null){startActivity(browser);return true;}
                Toast.makeText(this,"No browser is available for this link",Toast.LENGTH_SHORT).show();return true;
            }
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
            "if(window.__DMM328)return;window.__DMM328=true;if(!window.DMMNative)return;"+
            "var KEY='dmmJobsV3',MIGRATION='native-jobs-seed-v328';"+
            "function arr(v){try{var a=JSON.parse(v||'[]');return Array.isArray(a)?a:[];}catch(e){return[];}}"+
            "function pendingOnly(list){var out=[];for(var p=0;p<list.length;p++)if(list[p]&&list[p]._pendingCloudUpload===true)out.push(list[p]);return out;}"+
            "function persistBrowserPending(list){Storage.prototype.setItem.call(localStorage,KEY,JSON.stringify(pendingOnly(Array.isArray(list)?list:[])));}"+
            "var raw=Storage.prototype.getItem.call(localStorage,KEY)||'[]',legacy=arr(raw);if(!DMMNative.migrateLegacyJobsOnce(raw,MIGRATION))throw new Error('Legacy cache migration failed');persistBrowserPending(legacy);"+
            "var initial=arr(navigator.onLine?DMMNative.getPendingJobsJson():DMMNative.getJobsJson());try{if(typeof jobs!=='undefined')jobs=initial;if(typeof renderDriverPortal==='function')renderDriverPortal();}catch(e){}"+
            "var required=['dmmDriverCloudCommit','dmmCloudFetchRows','dmmCloudUpsertRows','dmmPersistJobsCache','dmmPullJobsAuthoritative'],missing=[];for(var q=0;q<required.length;q++)if(typeof window[required[q]]!=='function')missing.push(required[q]);if(missing.length){console.error('DMM native bridge hooks unavailable',missing);return;}"+
            "window.__DMM328_SYNC_BRIDGE=true;"+
            "var cloudUpsert=window.dmmCloudUpsertRows;window.dmmCloudUpsertRows=async function(type,items){var payload=items;if(type==='jobs'&&Array.isArray(items)){payload=[];for(var i=0;i<items.length;i++){var source=items[i]||{},clean={};for(var k in source)if(Object.prototype.hasOwnProperty.call(source,k)&&k!=='_pendingCloudUpload')clean[k]=source[k];payload.push(clean);}}return cloudUpsert.call(this,type,payload);};"+
            "var serverPullDepth=0,browserPersist=window.dmmPersistJobsCache;window.dmmPersistJobsCache=function(value){var list=Array.isArray(value)?value:[],ok=false;if(serverPullDepth){ok=DMMNative.mergeServerJobsJson(JSON.stringify(list));}else{for(var i=0;i<list.length;i++){var j=list[i];if(j&&j._pendingCloudUpload===true&&String(j.status||'').toLowerCase()==='delivered'){var completed=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!completed.ok)throw new Error(completed.error||'SQLite completion verification failed');}}ok=DMMNative.persistLocalJobsJson(JSON.stringify(list));}if(!ok)throw new Error('SQLite jobs write failed');var result=browserPersist.call(this,list);persistBrowserPending(list);return result;};"+
            "var cloudPull=window.dmmPullJobsAuthoritative;window.dmmPullJobsAuthoritative=async function(){serverPullDepth++;try{return await cloudPull.apply(this,arguments);}catch(error){try{var fallback=arr(DMMNative.getJobsJson());if(typeof jobs!=='undefined')jobs=fallback;if(typeof renderDriverPortal==='function')renderDriverPortal();}catch(ignored){}throw error;}finally{serverPullDepth--;}};"+
            "function restorePendingFromNative(){try{var pending=arr(DMMNative.getPendingJobsJson()),map={};for(var r=0;r<pending.length;r++)if(pending[r]&&pending[r].id!=null)map[String(pending[r].id)]=pending[r];if(typeof jobs!=='undefined'&&Array.isArray(jobs)){var merged=[],seen={};for(var m=0;m<jobs.length;m++){var current=jobs[m],id=current&&current.id!=null?String(current.id):'';if(id&&map[id]){merged.push(map[id]);seen[id]=true;}else merged.push(current);}for(var id in map)if(Object.prototype.hasOwnProperty.call(map,id)&&!seen[id])merged.push(map[id]);jobs=merged;persistBrowserPending(merged);if(typeof renderDriverPortal==='function')renderDriverPortal();}else persistBrowserPending(pending);}catch(ignored){}}"+
            "var cloudCommit=window.dmmDriverCloudCommit;window.dmmDriverCloudCommit=async function(){var nativeRows=arr(DMMNative.pendingDetails()||'[]'),jsPending={};try{if(typeof jobs!=='undefined'&&Array.isArray(jobs)){for(var i=0;i<jobs.length;i++){var j=jobs[i];if(j&&j._pendingCloudUpload===true&&j.id!=null)jsPending[String(j.id)]=true;}}}catch(e){}var attempted=[];for(var n=0;n<nativeRows.length;n++){var id=String(nativeRows[n].jobId||'');if(id&&jsPending[id])attempted.push(id);}var result=await cloudCommit.apply(this,arguments);if(!attempted.length)return result;try{var remoteRows=await window.dmmCloudFetchRows('jobs'),verify=JSON.parse(DMMNative.confirmJobsSynced(JSON.stringify(attempted),JSON.stringify(remoteRows))||'{\"ok\":false}');if(!verify.ok)throw new Error('Cloud upload returned, but the saved job could not be verified. It remains ready to send.');}catch(error){restorePendingFromNative();throw error;}return result;};"+
            "function clickSend(){try{if(!navigator.onLine||DMMNative.pendingCount()<=0)return;var els=document.querySelectorAll('button,a,[role=button]');for(var i=0;i<els.length;i++){var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();if(t.indexOf('upload pending')>=0||t.indexOf('send now')>=0){els[i].click();return;}}}catch(e){}}"+
            "window.addEventListener('online',function(){setTimeout(clickSend,400);});if(navigator.onLine&&DMMNative.pendingCount()>0)setTimeout(clickSend,900);"+
        "}catch(e){console.error('DMM v3.28 install failed',e);}})();";view.evaluateJavascript(js,null);
    }

    private void refreshPendingButton(){if(pendingButton==null||offlineDatabase==null)return;runOnUiThread(()->{int n=0;try{n=offlineDatabase.pendingCount();}catch(Exception ignored){}boolean online=isOnline();if(online)pendingButton.setText(n>0?"ONLINE · "+n+" READY TO SEND":"ONLINE · ALL SYNCED ✓");else pendingButton.setText(n>0?"OFFLINE · "+n+" READY TO SEND":"OFFLINE · 0 PENDING");});}
    private void showPendingDetails(){StringBuilder text=new StringBuilder();text.append(isOnline()?"Network: ONLINE\n\n":"Network: OFFLINE\n\n");try{JSONArray rows=new JSONArray(offlineDatabase.pendingDetailsJson());if(rows.length()==0)text.append("No local SQLite jobs are waiting to send.");else for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;JSONObject job=row.optJSONObject("job");String number=job==null?row.optString("jobId","?"):job.optString("jobNumber",row.optString("jobId","?"));String customer=job==null?"":job.optString("customerName",job.optString("businessName",""));String status=job==null?row.optString("status","pending"):job.optString("status",row.optString("status","pending"));text.append("Job #").append(number).append(" · ").append(status);if(!customer.isEmpty())text.append("\n").append(customer);if(i<rows.length()-1)text.append("\n\n");}}catch(Exception ex){text.append("Unable to read local pending data.\n").append(ex.getMessage());}new AlertDialog.Builder(this).setTitle("Local SQLite · Ready to Send").setMessage(text.toString()).setPositiveButton("OK",null).show();}
    private void startCameraFlow(){if(filePathCallback==null)return;if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);return;}try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"dmm_delivery_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");pendingCameraUri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(pendingCameraUri==null)throw new IllegalStateException("Unable to create camera image URI");Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);camera.putExtra(MediaStore.EXTRA_OUTPUT,pendingCameraUri);camera.setClipData(ClipData.newRawUri("DMM delivery photo",pendingCameraUri));camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);if(camera.resolveActivity(getPackageManager())==null)throw new IllegalStateException("No camera application is available");startActivityForResult(camera,CAMERA_REQUEST);}catch(Exception ex){deletePendingCameraImage();Toast.makeText(this,"Camera could not be opened · Select Photo instead",Toast.LENGTH_LONG).show();startGalleryFlow();}}
    private void startGalleryFlow(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,PICK_PHOTO_REQUEST);}catch(Exception ex){Toast.makeText(this,"Unable to open photos",Toast.LENGTH_LONG).show();clearFileCallback();}}
    private void deletePendingCameraImage(){if(pendingCameraUri!=null){try{getContentResolver().delete(pendingCameraUri,null,null);}catch(Exception ignored){}pendingCameraUri=null;}}
    private void clearFileCallback(){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=null;deletePendingCameraImage();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){if(requestCode==CAMERA_REQUEST){Uri result=(resultCode==RESULT_OK)?pendingCameraUri:null;if(filePathCallback!=null)filePathCallback.onReceiveValue(result==null?null:new Uri[]{result});if(result==null&&pendingCameraUri!=null){try{getContentResolver().delete(pendingCameraUri,null,null);}catch(Exception ignored){}}filePathCallback=null;pendingCameraUri=null;return;}if(requestCode==PICK_PHOTO_REQUEST){Uri uri=resultCode==RESULT_OK&&data!=null?data.getData():null;if(filePathCallback!=null)filePathCallback.onReceiveValue(uri==null?null:new Uri[]{uri});filePathCallback=null;return;}super.onActivityResult(requestCode,resultCode,data);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==CAMERA_PERMISSION_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startCameraFlow();else{Toast.makeText(this,"Camera permission denied · Select Photo instead",Toast.LENGTH_LONG).show();startGalleryFlow();}return;}if(requestCode==WEB_CAMERA_PERMISSION_REQUEST){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)grantPendingWebCameraPermission();else{denyPendingWebCameraPermission();Toast.makeText(this,"Camera permission is required for Take Photo",Toast.LENGTH_LONG).show();}}}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle outState){if(webView!=null)webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onDestroy(){denyPendingWebCameraPermission();uiHandler.removeCallbacks(pendingRefresh);if(webView!=null){webView.removeJavascriptInterface("DMMNative");webView.destroy();webView=null;}if(offlineDatabase!=null){offlineDatabase.close();offlineDatabase=null;}super.onDestroy();}
}
