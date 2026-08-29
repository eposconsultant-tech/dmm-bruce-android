package uk.aigoat.dmmdriver;

import android.webkit.WebView;

public final class PortalAdapter {
    private PortalAdapter() {}

    public static void install(WebView v) {
        String js = "(function(){try{" +
            "window.__DMM_ANDROID_APK=true;window.__DMM_ANDROID_SQLITE=true;window.__DMM_ANDROID_APK_VERSION='3.30';" +
            "document.documentElement.classList.add('dmm-android-apk');document.body.classList.add('dmm-android-apk');" +
            "if(!window.DMMNative)return;" +
            "window.__dmmNativePersist=function(arr){try{arr=Array.isArray(arr)?arr:[];for(var i=0;i<arr.length;i++){var j=arr[i];if(j&&j._pendingCloudUpload===true&&String(j.status||'').toLowerCase()==='delivered'){var r=JSON.parse(DMMNative.completeJobAtomic(JSON.stringify(j)));if(!r.ok)throw new Error(r.error||'SQLite completion verification failed');}}if(!DMMNative.setJobsJson(JSON.stringify(arr)))throw new Error('SQLite jobs write failed');return true;}catch(e){console.error('DMM SQLite persist failed',e);return false;}};" +
            "if(typeof dmmPersistJobsCache==='function'){window.__dmmBrowserPersistJobsCache=dmmPersistJobsCache;dmmPersistJobsCache=function(arr){return window.__dmmNativePersist(arr);};}" +
            "try{var raw=DMMNative.getJobsJson();var local=JSON.parse(raw||'[]');if(Array.isArray(local)&&local.length){jobs=local;try{renderDriverPortal();}catch(e){}}}catch(e){console.warn('SQLite restore deferred',e);}" +
            "function addPending(){var p=document.getElementById('driverPortal');if(!p)return;var logout=p.querySelector('#driverLogoutBtn,button[id*=Logout],.driver-logout');var host=(logout&&logout.parentElement)||p.querySelector('.driver-top-actions,.driver-header-actions');if(!host||document.getElementById('driverLocalPendingBtn'))return;var b=document.createElement('button');b.id='driverLocalPendingBtn';b.type='button';b.className='driver-top-action';b.style.cssText='background:#111827;color:#fff;border:0;border-radius:8px;padding:9px 12px;font-weight:800;margin-left:6px';b.onclick=function(){try{var rows=JSON.parse(DMMNative.pendingDetails()||'[]');if(!rows.length){alert('ALL SYNCED ✓\\n\\nNo local SQLite data is waiting to send.');return;}var lines=rows.map(function(r){var j=r.job||{};return '#'+(j.jobNumber||r.jobId)+' · '+(j.status||'Pending')+(r.lastError?' · '+r.lastError:'');});alert('LOCAL READY TO SEND ('+rows.length+')\\n\\n'+lines.join('\\n'));}catch(e){alert('Unable to read local SQLite pending data.\\n\\n'+e);}};host.insertBefore(b,logout||null);refresh();}" +
            "function refresh(){var b=document.getElementById('driverLocalPendingBtn');if(!b)return;try{var n=DMMNative.pendingCount();b.textContent=n?'LOCAL READY TO SEND ('+n+')':'ALL SYNCED ✓';}catch(e){b.textContent='LOCAL PENDING';}}" +
            "document.addEventListener('click',function(){setTimeout(function(){addPending();refresh();},250);},true);" +
            "new MutationObserver(function(){addPending();refresh();}).observe(document.body,{childList:true,subtree:true});addPending();refresh();" +
            "}catch(e){console.error('DMM APK adapter install failed',e);}})();";
        v.evaluateJavascript(js, null);
    }
}
