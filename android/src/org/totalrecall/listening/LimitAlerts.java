package org.totalrecall.listening;

import android.app.*;
import android.app.job.*;
import android.content.*;

/** Retain an actionable alert across capture stops, connection loss and reboots. */
final class LimitAlerts {
    static final int JOB=0x54524c41, ID=14016;
    private static final String CHANNEL="archive-capacity", LOCAL="phone", REMOTE="server";
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("archive-limit-alerts",0);}
    static String message(Context c){
        SharedPreferences p=prefs(c);
        String local=p.getBoolean(LOCAL,false)?"Phone storage limit reached. Review the archive budget and free-space reserve.":"";
        String remote=p.getBoolean(REMOTE,false)?"Upload limit reached. Review server storage and transfer allowances.":"";
        return local.isEmpty()?remote:remote.isEmpty()?local:local+" "+remote;
    }
    static void local(Context c,boolean active){update(c,LOCAL,active);}
    static void remote(Context c,boolean active){update(c,REMOTE,active);}
    private static synchronized void update(Context c,String key,boolean active){
        if(prefs(c).getBoolean(key,false)==active)return;
        prefs(c).edit().putBoolean(key,active).apply();
        refresh(c,true);
    }
    static void remind(Context c){
        if(prefs(c).getBoolean(LOCAL,false))try(ArchiveStore store=new ArchiveStore(c)){
            if(!ArchiveStore.enabled(c)||store.maintain())local(c,false);
        }
        refresh(c,false);
    }
    private static synchronized void refresh(Context c,boolean changed){
        String text=message(c);
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(text.isEmpty()){
            if(nm!=null)nm.cancel(ID);
            if(jobs!=null)jobs.cancel(JOB);
            return;
        }
        if(jobs!=null && jobs.getPendingJob(JOB)==null)
            jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,ArchiveSync.class)).setPeriodic(60*60000L).setPersisted(true).build());
        if(nm==null || !nm.areNotificationsEnabled())return;
        long now=System.currentTimeMillis();
        if(!changed && now-prefs(c).getLong("shown",0)<55*60000L)return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Archive limits",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(c,ID,new Intent(c,ArchiveSyncActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Total Recall needs more room").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_ERROR).build();
        nm.notify(ID,note);prefs(c).edit().putLong("shown",now).apply();
    }
}
