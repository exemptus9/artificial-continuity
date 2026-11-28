package org.totalrecall.listening;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exercises the journal's normal navigation and the platform AAC transport on the disposable emulator. */
final class JournalSegmentPlaybackProbe {
    private final Instrumentation probe;
    private JournalSegmentPlaybackProbe(Instrumentation probe){this.probe=probe;}

    static int verify(Instrumentation probe,Context context,ArchiveStore.Segment full)throws Exception{
        return new JournalSegmentPlaybackProbe(probe).run(context,full);
    }
    private int run(Context context,ArchiveStore.Segment full)throws Exception{
        final String wording="The synthetic journal passage for the local audio check";
        final int recognition=46637,expected=recognition-6000;
        final long at=full.startMs+recognition;
        boolean wasEnabled=JournalStore.enabled(context);
        JournalStore.setEnabled(context,true);
        try(JournalStore journal=new JournalStore(context)){
            long epoch=JournalStore.epoch(context),session=journal.begin(epoch);
            FullJournalActivity page=null;
            try{
                if(!journal.append(session,epoch,wording,false))throw new AssertionError("Could not seed the ordinary journal passage");
                journal.getWritableDatabase().execSQL("UPDATE lines SET at=? WHERE session=?",new Object[]{at,session});
                page=(FullJournalActivity)probe.startActivitySync(new Intent(context,FullJournalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                FullJournalActivity activity=page;
                await(()->onMain(()->{
                    TextView body=(TextView)field(activity,"transcript");
                    if(!(body.getText() instanceof Spanned))return false;
                    Spanned text=(Spanned)body.getText();
                    for(ClickableSpan span:text.getSpans(0,text.length(),ClickableSpan.class)){
                        if(text.subSequence(text.getSpanStart(span),text.getSpanEnd(span)).toString().contains(wording)){
                            span.onClick(body);return true;
                        }
                    }
                    return false;
                }),"Journal did not render its retained ordinary passage");
                tap("Find audio at this time");
                await(()->node("Paused · 0:40.637 / 2:00.000")!=null,"Saved AAC did not open paused at the expected lead-in");
                Object preview=onMain(()->field(activity,"segmentPlayer"));
                if(preview==null || onMain(()->field(preview,"player"))!=null)throw new AssertionError("Audio opened before Play was tapped");

                tap("Play");
                int[] observed={-1};
                await(()->onMain(()->{
                    MediaPlayer player=(MediaPlayer)field(preview,"player");
                    if(player==null || !player.isPlaying())return false;
                    observed[0]=player.getCurrentPosition();
                    // AAC-LC uses 64 ms frames at 16 kHz. Allow for polling a running player.
                    return observed[0]>=expected-128 && observed[0]<=expected+1200;
                }),"Android AAC playback did not start near the journal timestamp");
                tap("Back to passage");
                await(()->node("What I actually said")!=null && onMain(()->field(preview,"player"))==null,
                    "Leaving playback did not release audio and return to the same passage");
                if(node(wording)==null)throw new AssertionError("The original passage was lost after playback");
                return observed[0];
            }finally{
                if(page!=null){FullJournalActivity opened=page;probe.runOnMainSync(opened::finish);}
                journal.delete(session);
            }
        }finally{if(!wasEnabled)JournalStore.setEnabled(context,false);}
    }
    private <T> T onMain(Supplier<T> work){
        java.util.concurrent.atomic.AtomicReference<T> out=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        probe.runOnMainSync(()->{try{out.set(work.get());}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null)throw new AssertionError(error.get());
        return out.get();
    }
    private static Object field(Object owner,String name){
        try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private void await(BooleanSupplier ready,String error){
        long end=SystemClock.elapsedRealtime()+7000;
        do{if(ready.getAsBoolean())return;SystemClock.sleep(80);}while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError(error);
    }
    private AccessibilityNodeInfo node(String label){
        AccessibilityNodeInfo root=probe.getUiAutomation().getRootInActiveWindow();
        if(root==null)return null;
        for(AccessibilityNodeInfo item:root.findAccessibilityNodeInfosByText(label)){
            if(item.getText()!=null && label.equalsIgnoreCase(item.getText().toString()))return item;
        }
        return null;
    }
    private void tap(String label){
        await(()->{AccessibilityNodeInfo button=node(label);return button!=null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);},
            "Could not tap "+label);
    }
}
