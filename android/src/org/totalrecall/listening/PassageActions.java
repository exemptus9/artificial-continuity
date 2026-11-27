package org.totalrecall.listening;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executor;

final class PassageActions {
    static void copy(Activity a,String text){
        a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Total Recall passage",text));
        Toast.makeText(a,"Passage copied",Toast.LENGTH_SHORT).show();
    }
    static void share(Activity a,String text){
        a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share passage"));
    }
    static void audio(Activity a,Store.Moment clip,Executor worker){
        worker.execute(()->{
            try{
                File cache=a.getCacheDir();
                File[] old=cache.listFiles((dir,name)->name.matches("clip-[a-f0-9-]+\\.wav"));
                if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>600000)f.delete();
                File dest=new File(cache,"clip-"+UUID.randomUUID()+".wav");
                Files.copy(clip.audio.toPath(),dest.toPath());
                Uri uri=new Uri.Builder().scheme("content").authority(a.getPackageName()+".share").appendPath(dest.getName()).build();
                a.runOnUiThread(()->{
                    if(a.isFinishing()||a.isDestroyed())return;
                    Intent send=new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM,uri);
                    send.setClipData(ClipData.newRawUri("Original recording",uri));send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(send,"Share original audio"));
                });
            }catch(Exception e){a.runOnUiThread(()->Toast.makeText(a,"Could not prepare this audio file for sharing.",Toast.LENGTH_LONG).show());}
        });
    }
}
