package org.totalrecall.listening;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Offline playback and non-destructive excerpt editing for a single captured PCM clip. */
final class ClipStudio {
    private final RecallListenActivity owner;
    private final Store.Moment moment;
    private final ExecutorService worker;
    private final Runnable onSaved;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private MediaPlayer player;
    private PlaybackLevel playbackLevel;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private boolean focused;
    private Wave wave;
    private SeekBar seek;
    private TextView clock,selection,notice;
    private Button transport,save;
    private CheckBox loop;
    private boolean closed,ready,playing,scrubbing,saving,seekPending;
    private int samples,cursor,a,b;
    private float speed=1;

    ClipStudio(RecallListenActivity owner,Store.Moment moment,ExecutorService worker,Runnable onSaved){
        this.owner=owner;this.moment=moment;this.worker=worker;this.onSaved=onSaved;
        audio=(AudioManager)owner.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes()).setOnAudioFocusChangeListener(change->{
                if(change<0&&playing&&!closed){pause();note("Playback paused for another audio app.");}
            },main).build();
    }

    private static AudioAttributes attributes(){
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }
    void show(){
        LinearLayout sheet=owner.column();
        ScrollView scroll=new ScrollView(owner);scroll.addView(sheet);owner.pad(sheet,18,10);
        TextView title=owner.text(moment.title,21,0xff192c28,true);title.setTypeface(Typeface.SERIF,Typeface.BOLD);sheet.addView(title);
        clock=owner.text("Opening waveform…",16,0xff435955,false);sheet.addView(clock);
        wave=new Wave(owner);wave.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.addView(wave,new LinearLayout.LayoutParams(-1,owner.dp(75)));
        seek=new SeekBar(owner);seek.setEnabled(false);seek.setMinHeight(owner.dp(48));seek.setContentDescription("Clip playhead");
        sheet.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar v){scrubbing=true;}
            public void onProgressChanged(SeekBar v,int value,boolean fromUser){
                if(fromUser){cursor=value;paint();if(!scrubbing)seekAudio();}
            }
            public void onStopTrackingTouch(SeekBar v){scrubbing=false;seekAudio();}
        });
        transport=owner.button("Play",true);transport.setOnClickListener(v->togglePlayback());
        Button speeds=owner.button("Speed · 1×",false);speeds.setOnClickListener(v->{
            float[] values={0.5f,0.75f,1f,1.25f,1.5f,2f};
            String[] labels={"0.5×","0.75×","1×","1.25×","1.5×","2×"};int selected=0;
            for(int i=0;i<values.length;i++)if(values[i]==speed)selected=i;
            new AlertDialog.Builder(owner).setTitle("Playback speed").setSingleChoiceItems(labels,selected,(d,w)->{
                d.dismiss();speed=values[w];speeds.setText("Speed · "+labels[w]);if(playing)applySpeed();
            }).setNegativeButton("Cancel",null).show();
        });
        LinearLayout transportRow=owner.row();owner.split(transportRow,transport,speeds);sheet.addView(transportRow);
        Button level=owner.link("");playbackLevel=new PlaybackLevel(level);sheet.addView(level);
        LinearLayout jumps=owner.row();
        for(int seconds:new int[]{-15,-5,5,15}){
            Button button=owner.link((seconds<0?"−":"+")+Math.abs(seconds)+" s");
            button.setContentDescription((seconds<0?"Back ":"Forward ")+Math.abs(seconds)+" seconds");
            button.setOnClickListener(v->move(cursor+(long)seconds*PcmExcerpt.RATE));
            jumps.addView(button,new LinearLayout.LayoutParams(0,-2,1));
        }
        sheet.addView(jumps);
        selection=owner.text("",15,0xff192c28,true);selection.setPadding(0,owner.dp(10),0,0);sheet.addView(selection);
        LinearLayout markers=owner.row();Button markA=owner.button("Set A here",false),markB=owner.button("Set B here",false);
        markA.setOnClickListener(v->{if(ready&&cursor<b){a=cursor;paint();}else note("Place A before B.");});
        markB.setOnClickListener(v->{if(ready&&cursor>a){b=cursor;paint();}else note("Place B after A.");});
        owner.split(markers,markA,markB);sheet.addView(markers);
        LinearLayout rangeTools=owner.row();loop=new CheckBox(owner);loop.setText("Loop A–B");
        loop.setMinHeight(owner.dp(48));loop.setOnCheckedChangeListener((v,enabled)->{if(ready&&enabled&&(cursor<a||cursor>=b))move(a);});
        Button exact=owner.link("Exact samples");exact.setOnClickListener(v->{if(ready)exactRange();});
        owner.split(rangeTools,loop,exact);sheet.addView(rangeTools);
        save=owner.button("Save excerpt…",true);save.setEnabled(false);save.setOnClickListener(v->nameExcerpt());sheet.addView(save);
        notice=owner.text("",14,0xff435955,false);notice.setPadding(0,owner.dp(8),0,0);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);sheet.addView(notice);
        if(RecallService.isListening())note("Listening is active. You can trim silently. Pause listening to play audio.");
        dialog=new AlertDialog.Builder(owner).setTitle("Playback & trim").setView(scroll).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->release());dialog.show();
        transport.setEnabled(false);
        worker.execute(()->{
            try{
                int count=PcmExcerpt.count(moment.audio);int[] peaks=PcmExcerpt.peaks(moment.audio,280);
                main.post(()->{if(closed)return;samples=count;a=0;b=count;ready=true;
                    wave.peaks=peaks;seek.setMax(count);seek.setEnabled(true);save.setEnabled(true);transport.setEnabled(true);paint();});
            }catch(Exception e){main.post(()->{if(!closed)note("Couldn’t read clip audio. "+e.getMessage());});}
        });
    }

    private void exactRange(){
        LinearLayout form=owner.column();owner.pad(form,20,8);
        form.addView(owner.text("16,000 samples per second. B is the first sample outside the excerpt.",15,0xff435955,false));
        EditText from=sampleField(form,"Start sample (A)",a),to=sampleField(form,"End sample (B)",b);
        AlertDialog editor=new AlertDialog.Builder(owner).setTitle("Set exact boundaries").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                int x=Integer.parseInt(from.getText().toString()),y=Integer.parseInt(to.getText().toString());
                if(x<0||x>=y||y>samples)throw new NumberFormatException();
                a=x;b=y;editor.dismiss();move(a);
            }catch(NumberFormatException e){to.setError("Choose 0 ≤ A < B ≤ "+samples);}
        });
    }
    private EditText sampleField(LinearLayout form,String label,int number){
        form.addView(owner.text(label,14,0xff192c28,true));EditText input=new EditText(owner);
        input.setSingleLine();input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setContentDescription(label);
        input.setText(Integer.toString(number));form.addView(input);return input;
    }
    private void nameExcerpt(){
        if(!ready||saving)return;
        LinearLayout form=owner.column();owner.pad(form,20,8);
        EditText name=new EditText(owner);name.setSingleLine();name.setContentDescription("Excerpt title");
        name.setText(moment.title+" · excerpt");name.selectAll();form.addView(name);
        final int from=a,to=b;
        form.addView(owner.text(time(from)+" – "+time(to)+" · "+(to-from)+" samples",14,0xff435955,false));
        AlertDialog editor=new AlertDialog.Builder(owner).setTitle("Name this excerpt").setView(form)
            .setPositiveButton("Save copy",null).setNegativeButton("Cancel",null).create();editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String title=name.getText().toString().trim();
            if(title.isEmpty()||title.length()>240){name.setError("Enter a title up to 240 characters");return;}
            editor.dismiss();saving=true;save.setEnabled(false);note("Saving excerpt…");
            worker.execute(()->{
                try(Store store=new Store(owner.getApplicationContext())){
                    store.saveExcerpt(moment.id,from,to,title);
                    main.post(()->{if(!owner.isFinishing()&&!owner.isDestroyed()){
                        onSaved.run();Toast.makeText(owner,"Excerpt saved · "+title,Toast.LENGTH_SHORT).show();
                    }if(!closed){saving=false;save.setEnabled(true);note("Saved “"+title+"” to your moments.");}});
                }catch(Exception e){main.post(()->{if(!closed){saving=false;save.setEnabled(true);note("Couldn’t save excerpt. "+e.getMessage());}});}
            });
        });
    }
    private void togglePlayback(){
        if(!ready)return;
        if(playing){pause();return;}
        if(RecallService.isListening()){
            new AlertDialog.Builder(owner).setTitle("Pause listening to play aloud?")
                .setMessage("This ends the live microphone session. Start listening again from the home screen when you’re ready.")
                .setNegativeButton("Keep listening",null).setPositiveButton("Pause & play",(d,w)->{
                    try{
                        RecallService.command(owner,RecallService.PAUSE);transport.setEnabled(false);
                        awaitMicRelease(android.os.SystemClock.elapsedRealtime()+5000);
                    }catch(RuntimeException e){note("Couldn’t pause listening.");}
                }).show();return;
        }
        if(cursor>=samples||(loop.isChecked()&&(cursor<a||cursor>=b)))cursor=loop.isChecked()?a:0;
        if(player!=null){startAudio();return;}
        try{
            MediaPlayer next=new MediaPlayer();player=next;
            next.setAudioAttributes(attributes());
            next.setDataSource(moment.audio.getAbsolutePath());transport.setEnabled(false);note("Opening audio…");
            next.setOnPreparedListener(p->{if(p!=player||closed)return;transport.setEnabled(true);playbackLevel.attach(p);
                if(RecallService.isListening()){note("Listening is active. Playback is paused.");return;}startAudio();});
            next.setOnSeekCompleteListener(p->{if(p==player)seekPending=false;});
            next.setOnCompletionListener(p->{if(closed||p!=player)return;
                if(playing&&loop.isChecked()&&!RecallService.isListening()){cursor=a;startAudio();}
                else{playing=false;dropFocus();cursor=samples;transport.setText("Play");paint();}});
            next.setOnErrorListener((p,what,extra)->{if(p==player)audioError();return true;});
            next.prepareAsync();
        }catch(Exception e){audioError();}
    }
    private void awaitMicRelease(long deadline){
        if(closed)return;
        if(!RecallService.isListening()){transport.setEnabled(true);togglePlayback();return;}
        if(android.os.SystemClock.elapsedRealtime()>=deadline){
            transport.setEnabled(true);note("Playback is waiting for the microphone to pause. Try Play again.");return;
        }
        main.postDelayed(()->awaitMicRelease(deadline),125);
    }
    private void applySpeed(){
        try{player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));}
        catch(RuntimeException e){audioError();}
    }
    private void startAudio(){
        if(closed||player==null)return;
        try{
            if(!focused){
                focused=audio!=null&&audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if(!focused){note("Audio output is busy. Try Play again.");return;}
            }
            seekAudio();if(player==null)return;
            player.start();playing=true;applySpeed();if(player==null)return;
            transport.setText("Pause playback");note("");main.removeCallbacks(progress);main.postDelayed(progress,50);
        }catch(RuntimeException e){audioError();}
    }
    private void pause(){
        if(player!=null&&playing)try{player.pause();}catch(RuntimeException e){audioError();}
        playing=false;dropFocus();transport.setText("Play");main.removeCallbacks(progress);
    }
    private void move(long sample){
        if(!ready)return;cursor=(int)Math.max(0,Math.min(samples,sample));seekAudio();paint();
    }
    private void seekAudio(){
        if(player==null||!transport.isEnabled())return;
        try{seekPending=true;player.seekTo((long)cursor*1000/PcmExcerpt.RATE,MediaPlayer.SEEK_CLOSEST);}
        catch(RuntimeException e){audioError();}
    }
    private final Runnable progress=new Runnable(){
        public void run(){
            if(closed||!playing||player==null)return;
            if(RecallService.isListening()){pause();note("Playback paused because listening started.");return;}
            try{
                if(!scrubbing&&!seekPending){
                    cursor=(int)Math.min(samples,(long)player.getCurrentPosition()*PcmExcerpt.RATE/1000);
                    if(loop.isChecked()&&(cursor>=b||cursor<a))move(a);else paint();
                }
                main.postDelayed(this,45);
            }catch(RuntimeException e){audioError();}
        }
    };
    private void audioError(){
        releaseAudio();transport.setEnabled(ready);transport.setText("Play");note("Couldn’t play this audio on the current output.");
    }
    private void dropFocus(){
        if(focused&&audio!=null)audio.abandonAudioFocusRequest(focus);
        focused=false;
    }
    private void releaseAudio(){
        main.removeCallbacks(progress);playing=false;seekPending=false;dropFocus();
        if(playbackLevel!=null)playbackLevel.detach();
        if(player!=null){player.release();player=null;}
    }
    private void paint(){
        if(!ready)return;
        clock.setText(time(cursor)+" / "+time(samples));
        selection.setText("A  "+time(a)+"      B  "+time(b));
        if(!scrubbing)seek.setProgress(cursor);wave.invalidate();
    }
    private static String time(int sample){
        long ms=1000L*sample/PcmExcerpt.RATE;
        return String.format(Locale.ROOT,"%d:%02d.%03d",ms/60000,ms/1000%60,ms%1000);
    }
    private void note(String message){notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
    void close(){if(dialog!=null&&dialog.isShowing())dialog.dismiss();else release();}
    private void release(){closed=true;releaseAudio();}

    private final class Wave extends View{
        int[] peaks;
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        Wave(Context context){super(context);}
        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            c.drawColor(0xffe5ede6);
            if(samples<=0||peaks==null)return;
            ink.setColor(0xffc4ddcc);c.drawRect((float)a*w/samples,0,(float)b*w/samples,h,ink);
            ink.setStrokeWidth(Math.max(1,w/(float)peaks.length*0.68f));ink.setColor(0xff286052);
            for(int i=0;i<peaks.length;i++){
                float x=(i+0.5f)*w/peaks.length,amp=Math.max(0.6f,peaks[i]/32768f*h*0.47f);
                c.drawLine(x,h*0.5f-amp,x,h*0.5f+amp,ink);
            }
            ink.setColor(0xff973b2d);ink.setStrokeWidth(owner.dp(2));float x=(float)cursor*w/samples;c.drawLine(x,0,x,h,ink);
        }
    }
}
