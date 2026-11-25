package org.totalrecall.listening;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Verifies backlog accounting and durable approval with synthetic manifests; real payload transfer is tested separately. */
final class BacklogProbe {
    private static final int ITEMS = 3200;
    private static final long ITEM_BYTES = 262144, TOTAL_BYTES = ITEMS * ITEM_BYTES;
    private static final String HASH = "98e932b1a99d24cfcaadf69141fd89a9059d7afd5c3bb5233e7798c047033d31";

    static void verify(Context context, String pairing) throws Exception {
        ArchiveSync.reset(context); ArchiveStore.reset(context);
        SharedPreferences prefs=ArchiveSync.prefs(context);
        context.getSharedPreferences("recall-mint-link-v1",Context.MODE_PRIVATE).edit().putString("pair",pairing).commit();
        prefs.edit().putBoolean("segment",true).putBoolean("clip",false).putBoolean("journal",false)
                .putBoolean("wifi",false).putBoolean("charging",true).commit();
        Set<String> ids = new HashSet<>();
        try(ArchiveStore store=new ArchiveStore(context)) {
            SQLiteDatabase database=store.getWritableDatabase();
            long start=System.currentTimeMillis()-86400000;
            database.beginTransaction();
            try(SQLiteStatement insert=database.compileStatement("INSERT INTO segments(id,start,end,duration,size,bitrate,sha,receipt,state,until_ms) VALUES(?,?,?,120000,262144,32000,?,'','pending',?)")){
                for(int i=0;i<ITEMS;i++) {
                    String id=UUID.randomUUID().toString(); ids.add(id);
                    insert.bindString(1,id);insert.bindLong(2,start+i);insert.bindLong(3,start+i+120000);
                    insert.bindString(4,HASH);insert.bindLong(5,ArchiveStore.retainUntil(start+i+120000));
                    insert.executeInsert();
                }
                database.setTransactionSuccessful();
            } finally {database.endTransaction();}
            ArchiveSync.Plan routine=ArchiveSync.preview(context);
            check(routine.totalCount==ITEMS && routine.totalBytes==TOTAL_BYTES,"Automatic preview total is incorrect");
            check(routine.batch.size()<ITEMS && routine.batchBytes<TOTAL_BYTES,"Routine budget was ignored");
            ArchiveSync.Plan backlog=ArchiveSync.preview(context,true);
            check(backlog.totalCount==ITEMS && backlog.batch.size()==ITEMS && backlog.batchBytes==TOTAL_BYTES,"800 MiB was silently reduced");
            for(org.json.JSONObject entry:backlog.batch)check(ids.remove(entry.getString("id")),"Backlog contained a missing or duplicate manifest");
            check(ids.isEmpty(),"Backlog omitted manifests");
            java.lang.reflect.Method checkSpace=ArchiveSync.Plan.class.getDeclaredMethod("checkSpace",long.class);
            checkSpace.setAccessible(true);
            boolean lowSpaceRejected=false;
            try{checkSpace.invoke(backlog,TOTAL_BYTES-1);ArchiveSync.approve(context,backlog);}
            catch(Exception expected){lowSpaceRejected=true;}
            check(lowSpaceRejected,"Backlog approved with one byte too little reported capacity");
            check((Boolean)checkSpace.invoke(backlog,TOTAL_BYTES),"800 MiB preflight rejected sufficient exact capacity");
            ArchiveSync.approve(context,backlog);
            boolean chargingJob=false;
            for(JobInfo job:context.getSystemService(JobScheduler.class).getAllPendingJobs())
                if(job.getService().getClassName().equals(ArchiveSync.class.getName()) && job.isRequireCharging())chargingJob=true;
            check(chargingJob,"Approved backlog failed to queue under the charging constraint");
            String reference=prefs.getString("manual",null);
            check(reference!=null && reference.length()<256,"Missing durable queue reference");
            Class<?> queueType=Class.forName("org.totalrecall.listening.ArchiveBatchQueue");
            java.lang.reflect.Constructor<?> open=queueType.getDeclaredConstructor(Context.class);
            open.setAccessible(true);
            try(android.database.sqlite.SQLiteOpenHelper reopened=(android.database.sqlite.SQLiteOpenHelper)open.newInstance(context)) {
                java.lang.reflect.Method summary=queueType.getDeclaredMethod("summary",String.class);
                summary.setAccessible(true);
                Object data=summary.invoke(reopened,reference);
                java.lang.reflect.Field count=data.getClass().getDeclaredField("count"),bytes=data.getClass().getDeclaredField("bytes");
                count.setAccessible(true);bytes.setAccessible(true);
                check(count.getInt(data)==ITEMS && bytes.getLong(data)==TOTAL_BYTES,"Durable manifest lost byte count or entries on reopen");
            }
            int preferenceChars=0;
            for(Object v:prefs.getAll().values())preferenceChars+=String.valueOf(v).length();
            check(preferenceChars<16384,"Approval stored thousands of descriptors in preferences");
            check(!prefs.getBoolean("auto",false),"One-time approval enabled ongoing automatic transfer");
        } finally {ArchiveSync.stop(context);ArchiveStore.reset(context);}
    }

    private static void check(boolean ok,String detail){if(!ok)throw new AssertionError(detail);}
}
