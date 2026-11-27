package org.totalrecall.listening;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

/** Foreground, fixed prompts. No recognized words, profile names or recordings are spoken. */
final class SpokenChoices implements AutoCloseable {
    static final String PREF="spokenChoiceGuidance";
    enum Prompt {
        MIC("Microphone off","The microphone is off. Tap Start listening and continue, or cancel."),
        PERMISSION("Microphone permission","Microphone access is needed. Open app permissions, or cancel."),
        GROUP("Voice grouping","Voice grouping is off. Enable voice grouping to compare a sample."),
        VAULT("Capture paused","Capture is paused. Open your phone vault to enable it, or cancel."),
        RETRY("Input unavailable","Voice input is unavailable. Check again, or open the recorder."),
        SAMPLE("Review a sample","Check who spoke and what you heard. Use this sample, or try another phrase."),
        OTHER("Different speaker","Another voice profile matched. Try another phrase, or cancel."),
        UNCERTAIN("Uncertain speaker","The speaker is uncertain. Use this sample only if you know who spoke, or try another phrase."),
        WORD("Review a word","Check the wording in the box. Use this sample, or try another phrase."),
        REPLACE("Conflicting spelling","This phrase has another spelling. Replace it, or go back."),
        SAVED("Continue practice","Spelling checked. Try another delivery, or tap Done."),
        END("Close a session","Close this session? End and close, or go back.");
        final String label,words;
        Prompt(String label,String words){this.label=label;this.words=words;}
        String resource(){return "choice_"+name().toLowerCase(java.util.Locale.ROOT);}
    }
    private final Context app;
    private final SharedPreferences prefs;
    private final AudioManager manager;
    private final AudioAttributes attributes=new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private long token;
    SpokenChoices(Context context){
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences("recall-speech",Context.MODE_PRIVATE);
        manager=app.getSystemService(AudioManager.class);
    }
    boolean enabled(){return prefs.getBoolean(PREF,false);}
    boolean play(Prompt prompt,boolean preview){
        close();
        if(!preview && !enabled())return false;
        int id=app.getResources().getIdentifier(prompt.resource(),"raw",app.getPackageName());
        if(id==0)return false;
        try{
            MediaPlayer next=MediaPlayer.create(app,id,attributes,AudioManager.AUDIO_SESSION_ID_GENERATE);
            if(next==null)return false;
            return start(next);
        }catch(RuntimeException e){close();return false;}
    }
    boolean start(MediaPlayer next){
        player=next;
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change<0 && player==next)close();}).build();
        if(manager==null || manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){close();return false;}
        next.setOnCompletionListener(done->{if(player==done)close();});
        next.setOnErrorListener((failed,what,extra)->{if(player==failed)close();return true;});
        int level=prefs.getInt("cueLevel",2);
        float volume=level==0?.30f:level==1?.62f:1f;
        next.setVolume(volume,volume);
        // Some devices initially report zero duration. Completion/focus loss shortens the
        // gate to its acoustic tail. All bundled prompts are shorter than six seconds.
        token=CuePlaybackGate.begin(Math.max(6500,next.getDuration()+1000));
        try{next.start();return true;}
        catch(RuntimeException e){close();return false;}
    }
    public void close(){
        MediaPlayer old=player;player=null;
        if(old!=null)old.release();
        if(token!=0){CuePlaybackGate.end(token);token=0;}
        if(focus!=null && manager!=null)manager.abandonAudioFocusRequest(focus);
        focus=null;
    }
}
