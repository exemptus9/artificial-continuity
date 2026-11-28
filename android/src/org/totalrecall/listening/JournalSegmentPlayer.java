package org.totalrecall.listening;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Previews an existing, verified archive segment without leaving the journal passage. */
final class JournalSegmentPlayer {
    private final Activity activity;
    private final ExecutorService io;
    private final ArchiveStore.Segment segment;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioFocusRequest focus;
    private final AlertDialog dialog;
    private final TextView status;
    private Button toggle;
    private MediaPlayer player;
    private long position;
    private boolean playing,focused;
    private int generation;

    JournalSegmentPlayer(Activity activity,ExecutorService io,ArchiveStore.Segment segment,long offset){
        this.activity=activity;this.io=io;this.segment=segment;position=offset;
        audio=activity.getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change->{if(change<0)stop("Paused for another audio app");},main).build();
        status=new TextView(activity);int pad=Math.round(20*activity.getResources().getDisplayMetrics().density);
        status.setPadding(pad,pad/2,pad,pad/2);status.setTextSize(17);
        dialog=new AlertDialog.Builder(activity).setTitle("Saved full audio").setView(status)
            .setNegativeButton("Back to passage",null).setPositiveButton("Play",null).create();
        dialog.setOnDismissListener(d->release());
    }
    void show(){
        dialog.show();toggle=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        toggle.setOnClickListener(v->{if(playing)stop("Paused");else play();});
        display("Paused");
    }
    private static String time(long ms){
        return String.format(Locale.ROOT,"%d:%02d.%03d",ms/60000,ms/1000%60,ms%1000);
    }
    private void display(String message){status.setText(message+" · "+time(position)+" / "+time(segment.durationMs));}
    void close(){release();if(dialog.isShowing())dialog.dismiss();}
    private void release(){
        generation++;playing=false;main.removeCallbacks(update);
        if(player!=null){player.release();player=null;}
        if(focused && audio!=null)audio.abandonAudioFocusRequest(focus);
        focused=false;
    }
    private void stop(String message){
        if(playing && player!=null)try{position=Math.min(segment.durationMs,player.getCurrentPosition());}catch(IllegalStateException ignored){}
        release();
        if(dialog.isShowing()){toggle.setEnabled(true);toggle.setText("Play");display(message);}
    }
    private final Runnable update=new Runnable(){public void run(){
        if(!playing || player==null)return;
        if(RecallService.isListening()){stop("Paused while listening");return;}
        if(!ArchiveStore.storageUnlocked(activity)){stop("Unlock this phone to play saved audio");return;}
        try{position=Math.min(segment.durationMs,player.getCurrentPosition());display("Playing");main.postDelayed(this,250);}
        catch(IllegalStateException e){stop("Playback interrupted");}
    }};
    private void play(){
        if(RecallService.isListening()){display("Pause listening before playing this recording");return;}
        if(!ArchiveStore.storageUnlocked(activity)){display("Unlock this phone to play saved audio");return;}
        if(position>=segment.durationMs)position=0;
        toggle.setEnabled(false);display("Opening recording");
        final int request=++generation;
        io.execute(()->{
            MediaPlayer prepared=null;
            try(ArchiveStore db=new ArchiveStore(activity);FileInputStream source=db.openForPlayback(segment.id)){
                prepared=new MediaPlayer();prepared.setAudioAttributes(attributes);prepared.setDataSource(source.getFD());prepared.prepare();
                MediaPlayer loaded=prepared;main.post(()->ready(request,loaded));prepared=null;
            }catch(IOException|RuntimeException error){
                main.post(()->{if(request==generation)stop("Could not play the saved phone copy. It may have been removed or damaged");});
            }finally{if(prepared!=null)prepared.release();}
        });
    }
    private void ready(int request,MediaPlayer loaded){
        if(request!=generation || !dialog.isShowing()){loaded.release();return;}
        player=loaded;
        loaded.setOnErrorListener((p,what,extra)->{if(p==player)stop("Could not play this recording");return true;});
        loaded.setOnCompletionListener(p->{if(p==player){playing=false;position=segment.durationMs;stop("Finished");toggle.setText("Replay");}});
        loaded.setOnSeekCompleteListener(p->{if(p==player && !playing)start(p);});
        try{if(position==0)start(loaded);else loaded.seekTo(position,MediaPlayer.SEEK_CLOSEST);}
        catch(IllegalStateException e){stop("Could not seek to this point");}
    }
    private void start(MediaPlayer loaded){
        if(RecallService.isListening()){stop("Paused while listening");return;}
        if(!ArchiveStore.storageUnlocked(activity)){stop("Unlock this phone to play saved audio");return;}
        try{
            focused=audio!=null && audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if(!focused){stop("Audio output is busy");return;}
            loaded.start();playing=true;toggle.setEnabled(true);toggle.setText("Pause");main.post(update);
        }catch(IllegalStateException e){stop("Playback interrupted");}
    }
}
