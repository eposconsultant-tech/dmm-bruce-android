package uk.aigoat.dmmdriver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class OfflineDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "dmm_driver_offline.db";
    private static final int DB_VERSION = 4;
    private static final String PENDING_MARKER = "_pendingCloudUpload";
    private static final int TEXT_CHUNK_SIZE = 256 * 1024;

    private enum WriteSource { LOCAL, SERVER }

    public OfflineDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_pending ON jobs(pending)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (job_id TEXT PRIMARY KEY, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv_store (k TEXT PRIMARY KEY, v TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id TEXT PRIMARY KEY, job_id TEXT, kind TEXT, local_path TEXT, remote_url TEXT, status TEXT NOT NULL DEFAULT 'local', updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS completion_commits (job_id TEXT PRIMARY KEY, checksum TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', committed_at INTEGER NOT NULL, synced_at INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { onCreate(db); }

    private static String sha256(String value) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        byte[] digest=md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format("%02x",b));
        return out.toString();
    }

    private static String requireId(JSONObject job) throws Exception {
        String id=job.optString("id","").trim();
        if(id.isEmpty()){id=UUID.randomUUID().toString();job.put("id",id);}
        return id;
    }

    private boolean isAlreadyPending(SQLiteDatabase db,String id){
        try(Cursor c=db.query("jobs",new String[]{"pending"},"id=?",new String[]{id},null,null,null,"1")){
            return c.moveToFirst()&&c.getInt(0)==1;
        }catch(Exception e){return false;}
    }

    private Set<String> pendingJobIds(SQLiteDatabase db){
        Set<String> ids=new HashSet<>();
        try(Cursor c=db.query("jobs",new String[]{"id"},"pending=1",null,null,null,null)){
            while(c.moveToNext())ids.add(c.getString(0));
        }
        return ids;
    }

    private String readJobJson(SQLiteDatabase db,String id){
        if(id==null||id.trim().isEmpty())return null;
        StringBuilder out=new StringBuilder();int offset=1;
        while(true){
            String chunk;
            try(Cursor c=db.rawQuery("SELECT substr(json,?,?) FROM jobs WHERE id=?",new String[]{String.valueOf(offset),String.valueOf(TEXT_CHUNK_SIZE),id})){
                if(!c.moveToFirst()||c.isNull(0))return out.length()==0?null:out.toString();
                chunk=c.getString(0);
            }
            if(chunk==null||chunk.isEmpty())break;
            out.append(chunk);
            if(chunk.length()<TEXT_CHUNK_SIZE)break;
            offset+=TEXT_CHUNK_SIZE;
        }
        return out.toString();
    }

    private String upsertJob(SQLiteDatabase db,JSONObject incoming,long now,WriteSource source,Set<String> knownPendingIds)throws Exception{
        String id=requireId(incoming);
        boolean oldPending=knownPendingIds==null?isAlreadyPending(db,id):knownPendingIds.contains(id);
        boolean explicitLocalPending=source==WriteSource.LOCAL&&incoming.optBoolean(PENDING_MARKER,false);

        if(source==WriteSource.SERVER)incoming.remove(PENDING_MARKER);

        // Never let unverified cloud/cache data replace a payload awaiting confirmation.
        if(oldPending&&!explicitLocalPending)return id;

        boolean pending=explicitLocalPending;
        if(pending)incoming.put(PENDING_MARKER,true);else incoming.remove(PENDING_MARKER);

        ContentValues cv=new ContentValues();
        cv.put("id",id);cv.put("json",incoming.toString());cv.put("pending",pending?1:0);cv.put("updated_at",now);
        if(db.insertWithOnConflict("jobs",null,cv,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("jobs upsert failed");

        if(pending){
            ContentValues create=new ContentValues();create.put("job_id",id);create.put("status","pending");create.put("updated_at",now);
            // CONFLICT_IGNORE returns -1 both for a real failure and for the expected
            // "row already exists" case. The checked update below is the reliable result.
            db.insertWithOnConflict("sync_queue",null,create,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues update=new ContentValues();update.put("status","pending");update.put("updated_at",now);
            if(db.update("sync_queue",update,"job_id=?",new String[]{id})!=1)throw new IllegalStateException("sync queue update failed");
        }
        return id;
    }

    private String upsertLocalJob(SQLiteDatabase db,JSONObject job,long now)throws Exception {
        return upsertJob(db,job,now,WriteSource.LOCAL,null);
    }

    public synchronized String getItem(String key){
        if("dmmJobsV3".equals(key))return getJobsJson();
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.query("kv_store",new String[]{"v"},"k=?",new String[]{key},null,null,null)){
            if(c.moveToFirst())return c.isNull(0)?null:c.getString(0);
        }
        return null;
    }

    public synchronized boolean setItem(String key,String value){
        if("dmmJobsV3".equals(key))return persistLocalJobsJson(value);
        SQLiteDatabase db=getWritableDatabase();ContentValues cv=new ContentValues();cv.put("k",key);cv.put("v",value);cv.put("updated_at",System.currentTimeMillis());
        return db.insertWithOnConflict("kv_store",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1;
    }

    public synchronized void removeItem(String key){
        SQLiteDatabase db=getWritableDatabase();
        if("dmmJobsV3".equals(key)){
            db.beginTransaction();
            try{db.delete("jobs","pending=0",null);db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");db.setTransactionSuccessful();}
            finally{db.endTransaction();}
            return;
        }
        db.delete("kv_store","k=?",new String[]{key});
    }

    private String jobsJson(String selection){
        JSONArray out=new JSONArray();SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.query("jobs",new String[]{"id"},selection,null,null,null,"updated_at ASC")){
            while(c.moveToNext()){try{String json=readJobJson(db,c.getString(0));if(json!=null)out.put(new JSONObject(json));}catch(Exception ignored){}}
        }
        return out.toString();
    }

    public synchronized String getJobsJson(){return jobsJson(null);}
    public synchronized String getPendingJobsJson(){return jobsJson("pending=1");}

    public synchronized String getJobJson(String id){
        return readJobJson(getReadableDatabase(),id);
    }

    public synchronized boolean saveJobJson(String json){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{JSONObject job=new JSONObject(json==null?"{}":json);upsertLocalJob(db,job,System.currentTimeMillis());db.setTransactionSuccessful();return true;}
        catch(Exception ex){return false;}finally{db.endTransaction();}
    }

    public synchronized String completeJobAtomic(String json){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONObject job=new JSONObject(json==null?"{}":json);job.put(PENDING_MARKER,true);
            if(!"delivered".equalsIgnoreCase(job.optString("status","")))throw new IllegalStateException("completion status must be Delivered");
            String id=upsertLocalJob(db,job,System.currentTimeMillis());String payload=job.toString();String checksum=sha256(canonicalJson(job,true));
            ContentValues commit=new ContentValues();commit.put("job_id",id);commit.put("checksum",checksum);commit.put("payload_json",payload);commit.put("status","pending");commit.put("committed_at",System.currentTimeMillis());
            if(db.insertWithOnConflict("completion_commits",null,commit,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("completion commit insert failed");
            String storedJson=readJobJson(db,id);int storedPending=0;
            try(Cursor c=db.query("jobs",new String[]{"pending"},"id=?",new String[]{id},null,null,null,"1")){if(!c.moveToFirst()||storedJson==null)throw new IllegalStateException("job read-back failed");storedPending=c.getInt(0);}
            int queueCount=0;try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE job_id=? AND status='pending'",new String[]{id})){if(c.moveToFirst())queueCount=c.getInt(0);}
            if(storedPending!=1||queueCount!=1)throw new IllegalStateException("pending queue verification failed");
            if(!checksum.equals(sha256(canonicalJson(new JSONObject(storedJson),true))))throw new IllegalStateException("payload checksum verification failed");
            JSONObject verified=new JSONObject(storedJson);if(!"delivered".equalsIgnoreCase(verified.optString("status","")))throw new IllegalStateException("Delivered status verification failed");
            db.setTransactionSuccessful();JSONObject out=new JSONObject();out.put("ok",true);out.put("id",id);out.put("pending",true);out.put("status",verified.optString("status",""));out.put("checksum",checksum);return out.toString();
        }catch(Exception ex){return errorJson(ex);}finally{db.endTransaction();}
    }

    private boolean writeJobsJson(String json,WriteSource source){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONArray arr=new JSONArray(json==null||json.trim().isEmpty()?"[]":json);long now=System.currentTimeMillis();
            Set<String> incomingIds=new HashSet<>();Set<String> knownPendingIds=pendingJobIds(db);
            for(int i=0;i<arr.length();i++){
                JSONObject job=arr.optJSONObject(i);if(job==null)continue;String id=requireId(job);incomingIds.add(id);
                upsertJob(db,job,now+i,source,knownPendingIds);
            }
            List<String> staleIds=new ArrayList<>();
            try(Cursor c=db.query("jobs",new String[]{"id"},"pending=0",null,null,null,null)){while(c.moveToNext()){String id=c.getString(0);if(!incomingIds.contains(id))staleIds.add(id);}}
            for(String id:staleIds)db.delete("jobs","id=? AND pending=0",new String[]{id});
            db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");
            db.setTransactionSuccessful();return true;
        }catch(Exception ex){return false;}finally{db.endTransaction();}
    }

    public synchronized boolean setJobsJson(String json){return persistLocalJobsJson(json);}
    public synchronized boolean persistLocalJobsJson(String json){return writeJobsJson(json,WriteSource.LOCAL);}
    public synchronized boolean mergeServerJobsJson(String json){return writeJobsJson(json,WriteSource.SERVER);}

    public synchronized boolean migrateLegacyJobsOnce(String json,String marker){
        if(marker==null||marker.trim().isEmpty())return false;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            try(Cursor c=db.query("kv_store",new String[]{"v"},"k=?",new String[]{marker},null,null,null,"1")){if(c.moveToFirst()){db.setTransactionSuccessful();return true;}}
            JSONArray arr=new JSONArray(json==null||json.trim().isEmpty()?"[]":json);long now=System.currentTimeMillis();
            for(int i=0;i<arr.length();i++){
                JSONObject incoming=arr.optJSONObject(i);if(incoming==null)continue;String id=requireId(incoming);boolean incomingPending=incoming.optBoolean(PENDING_MARKER,false);
                // Only unsent work is authoritative during the one-time handover.
                // Completed/non-pending browser rows can be stale and will be fetched afresh.
                if(!incomingPending)continue;
                boolean exists=false;boolean existingPending=false;
                try(Cursor c=db.query("jobs",new String[]{"pending"},"id=?",new String[]{id},null,null,null,"1")){if(c.moveToFirst()){exists=true;existingPending=c.getInt(0)==1;}}
                if(!exists||!existingPending)upsertLocalJob(db,incoming,now+i);
            }
            ContentValues done=new ContentValues();done.put("k",marker);done.put("v","1");done.put("updated_at",now);
            if(db.insertWithOnConflict("kv_store",null,done,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("migration marker write failed");
            db.setTransactionSuccessful();return true;
        }catch(Exception ex){return false;}finally{db.endTransaction();}
    }

    private static String canonicalJson(Object value,boolean topLevel)throws Exception{
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){
            JSONObject obj=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();java.util.Iterator<String> iterator=obj.keys();
            while(iterator.hasNext()){String key=iterator.next();if(!(topLevel&&PENDING_MARKER.equals(key)))keys.add(key);}
            StringBuilder out=new StringBuilder("{");boolean first=true;
            for(String key:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':').append(canonicalJson(obj.opt(key),false));}
            return out.append('}').toString();
        }
        if(value instanceof JSONArray){
            JSONArray arr=(JSONArray)value;StringBuilder out=new StringBuilder("[");
            for(int i=0;i<arr.length();i++){if(i>0)out.append(',');out.append(canonicalJson(arr.opt(i),false));}
            return out.append(']').toString();
        }
        if(value instanceof Number)return JSONObject.numberToString((Number)value);
        if(value instanceof Boolean)return value.toString();
        return JSONObject.quote(String.valueOf(value));
    }

    public synchronized String confirmJobsSynced(String idsJson,String remoteRowsJson){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONArray ids=new JSONArray(idsJson==null?"[]":idsJson);JSONArray remoteRows=new JSONArray(remoteRowsJson==null?"[]":remoteRowsJson);
            Map<String,JSONObject> remoteById=new HashMap<>();
            for(int i=0;i<remoteRows.length();i++){
                JSONObject row=remoteRows.optJSONObject(i);if(row==null)continue;Object rawData=row.opt("data");JSONObject data=null;
                if(rawData instanceof JSONObject)data=(JSONObject)rawData;else if(rawData instanceof String){try{data=new JSONObject((String)rawData);}catch(Exception ignored){}}
                if(data==null)continue;String id=row.optString("entity_id",data.optString("id","")).trim();if(!id.isEmpty())remoteById.put(id,data);
            }

            List<String> attempted=new ArrayList<>();JSONArray failed=new JSONArray();
            for(int i=0;i<ids.length();i++){
                String id=String.valueOf(ids.opt(i)).trim();if(id.isEmpty()||attempted.contains(id))continue;attempted.add(id);
                String localJson=readJobJson(db,id);int pending=0;
                try(Cursor c=db.query("jobs",new String[]{"pending"},"id=?",new String[]{id},null,null,null,"1")){if(c.moveToFirst())pending=c.getInt(0);}
                JSONObject remote=remoteById.get(id);boolean matches=localJson!=null&&pending==1&&remote!=null&&canonicalJson(new JSONObject(localJson),true).equals(canonicalJson(remote,true));
                int pendingCommit=0;try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM completion_commits WHERE job_id=? AND status='pending'",new String[]{id})){if(c.moveToFirst())pendingCommit=c.getInt(0);}
                if(matches&&pendingCommit>0&&!"delivered".equalsIgnoreCase(remote.optString("status","")))matches=false;
                if(!matches)failed.put(id);
            }
            if(failed.length()>0){JSONObject out=new JSONObject();out.put("ok",false);out.put("failed",failed);return out.toString();}

            long now=System.currentTimeMillis();
            for(String id:attempted){
                String localJson=readJobJson(db,id);if(localJson==null)throw new IllegalStateException("job disappeared during confirmation");
                JSONObject job=new JSONObject(localJson);job.remove(PENDING_MARKER);ContentValues cv=new ContentValues();cv.put("json",job.toString());cv.put("pending",0);cv.put("updated_at",now);
                if(db.update("jobs",cv,"id=? AND pending=1",new String[]{id})!=1)throw new IllegalStateException("job confirmation update failed");
                db.delete("sync_queue","job_id=?",new String[]{id});ContentValues cc=new ContentValues();cc.put("status","synced");cc.put("synced_at",now);db.update("completion_commits",cc,"job_id=?",new String[]{id});
            }
            db.setTransactionSuccessful();JSONObject out=new JSONObject();out.put("ok",true);out.put("ids",new JSONArray(attempted));return out.toString();
        }catch(Exception ex){return errorJson(ex);}finally{db.endTransaction();}
    }

    public synchronized boolean markJobSynced(String id){
        if(id==null||id.trim().isEmpty())return false;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            String json=readJobJson(db,id);int pending=0;try(Cursor c=db.query("jobs",new String[]{"pending"},"id=?",new String[]{id},null,null,null,"1")){if(c.moveToFirst())pending=c.getInt(0);}
            if(json==null||pending!=1)return false;JSONObject job=new JSONObject(json);if(!"delivered".equalsIgnoreCase(job.optString("status","")))return false;job.remove(PENDING_MARKER);
            ContentValues cv=new ContentValues();cv.put("json",job.toString());cv.put("pending",0);cv.put("updated_at",System.currentTimeMillis());if(db.update("jobs",cv,"id=?",new String[]{id})!=1)throw new IllegalStateException("job sync update failed");
            db.delete("sync_queue","job_id=?",new String[]{id});ContentValues cc=new ContentValues();cc.put("status","synced");cc.put("synced_at",System.currentTimeMillis());db.update("completion_commits",cc,"job_id=?",new String[]{id});db.setTransactionSuccessful();return true;
        }catch(Exception ex){return false;}finally{db.endTransaction();}
    }

    private static String errorJson(Exception ex){
        JSONObject out=new JSONObject();try{out.put("ok",false);out.put("error",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());}catch(Exception ignored){}return out.toString();
    }

    public synchronized int clearNonPendingJobs(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{int removed=db.delete("jobs","pending=0",null);db.execSQL("DELETE FROM sync_queue WHERE job_id NOT IN (SELECT id FROM jobs)");db.setTransactionSuccessful();return removed;}finally{db.endTransaction();}}
    public synchronized int pendingCount(){SQLiteDatabase db=getReadableDatabase();try(Cursor c=db.rawQuery("SELECT COUNT(DISTINCT id) FROM (SELECT job_id AS id FROM sync_queue WHERE status='pending' UNION SELECT job_id AS id FROM completion_commits WHERE status='pending' UNION SELECT id FROM jobs WHERE pending=1)",null)){return c.moveToFirst()?c.getInt(0):0;}}
    public synchronized String pendingJobIdsJson(){JSONArray out=new JSONArray();SQLiteDatabase db=getReadableDatabase();try(Cursor c=db.rawQuery("SELECT DISTINCT id FROM (SELECT job_id AS id FROM sync_queue WHERE status='pending' UNION SELECT job_id AS id FROM completion_commits WHERE status='pending' UNION SELECT id FROM jobs WHERE pending=1) ORDER BY id",null)){while(c.moveToNext())out.put(c.getString(0));}return out.toString();}
    public synchronized String pendingDetailsJson(){JSONArray out=new JSONArray();SQLiteDatabase db=getReadableDatabase();try(Cursor c=db.rawQuery("SELECT j.id,COALESCE(q.status,'pending'),COALESCE(q.attempts,0),q.last_error,j.updated_at FROM jobs j LEFT JOIN sync_queue q ON q.job_id=j.id WHERE j.pending=1 OR q.status='pending' OR j.id IN (SELECT job_id FROM completion_commits WHERE status='pending') ORDER BY j.updated_at ASC",null)){while(c.moveToNext()){try{String id=c.getString(0);JSONObject row=new JSONObject();row.put("jobId",id);row.put("status",c.getString(1));row.put("attempts",c.getInt(2));row.put("lastError",c.isNull(3)?JSONObject.NULL:c.getString(3));row.put("updatedAt",c.getLong(4));String json=readJobJson(db,id);if(json!=null)row.put("job",new JSONObject(json));out.put(row);}catch(Exception ignored){}}}return out.toString();}
    public synchronized String statsJson(){int jobs=0,pending=0,kv=0,commits=0;SQLiteDatabase db=getReadableDatabase();try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(pending),0) FROM jobs",null)){if(c.moveToFirst()){jobs=c.getInt(0);pending=c.getInt(1);}}try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM kv_store",null)){if(c.moveToFirst())kv=c.getInt(0);}try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM completion_commits",null)){if(c.moveToFirst())commits=c.getInt(0);}return "{\"jobs\":"+jobs+",\"pending\":"+pending+",\"kv\":"+kv+",\"completionCommits\":"+commits+",\"dbVersion\":"+DB_VERSION+"}";}
}
