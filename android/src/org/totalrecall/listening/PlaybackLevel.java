package org.totalrecall.listening;

import android.app.AlertDialog;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.widget.Button;
import android.widget.Toast;

/** Review-only gain attached to one player. Each new recording opens at original level. */
final class PlaybackLevel {
    private static final String[] LEVELS={"Original","+6 dB","+12 dB"};
    private final Button button;
    private LoudnessEnhancer effect;
    private int session,choice;

    PlaybackLevel(Button button){
        this.button=button;
        button.setAllCaps(false);
        paint();
        button.setOnClickListener(v->new AlertDialog.Builder(button.getContext())
            .setTitle("Playback level").setSingleChoiceItems(LEVELS,choice,(dialog,index)->{
                dialog.dismiss();select(index);
            }).setNegativeButton("Close",null).show());
    }
    void select(int index){
        if(index<0 || index>=LEVELS.length)throw new IllegalArgumentException("Unknown playback level");
        choice=index;apply();paint();
    }
    private void paint(){button.setText("Playback level · "+LEVELS[choice]);}
    void attach(MediaPlayer player){
        detach();
        try{session=player.getAudioSessionId();}catch(RuntimeException unavailable){session=0;}
        if(choice>0 && session<=0){unavailable();return;}
        apply();
    }
    private void apply(){
        if(choice==0){releaseEffect();return;}
        if(session<=0)return; // Selected before the player was opened.
        try{
            if(effect==null)effect=new LoudnessEnhancer(session);
            effect.setTargetGain(choice*600);
            if(effect.setEnabled(true)!=AudioEffect.SUCCESS)unavailable();
        }catch(RuntimeException e){unavailable();}
    }
    private void unavailable(){
        releaseEffect();choice=0;paint();
        Toast.makeText(button.getContext(),"Playback boost unavailable on this output. Using original level.",Toast.LENGTH_LONG).show();
    }
    private void releaseEffect(){
        if(effect!=null){try{effect.release();}catch(RuntimeException ignored){}effect=null;}
    }
    void detach(){releaseEffect();session=0;}
}
