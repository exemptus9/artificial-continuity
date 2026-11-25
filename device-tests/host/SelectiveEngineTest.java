package org.totalrecall.listening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SelectiveEngineTest {
    static class Fixture implements SelectiveEngine.Listener {
        final SelectiveEngine engine = new SelectiveEngine(this, 1770000000000L);
        final List<SelectiveEngine.Clip> clips = new ArrayList<>();
        int pauses;
        public void onClip(SelectiveEngine.Clip c) { clips.add(c); }
        public void onPauseRequested() { pauses++; }
        void sound(int seconds, int sample) {
            short[] data = new short[SelectiveEngine.RATE];
            Arrays.fill(data, (short)sample);
            for (int i=0;i<seconds;i++) engine.pcm(data,data.length);
        }
        void say(String words) { sound(1,500); engine.text(words,true); }
    }

    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void ended(Fixture f, String kind) {
        f.sound(5,0);
        check(f.clips.size()==1);
        check(f.clips.get(0).category.contains(kind));
        f.engine.finish();
        check(f.clips.size()==1);
    }

    public static void main(String[] args) {
        Fixture f=new Fixture();
        f.say("I had a sandwich for lunch"); f.sound(200,0); f.engine.finish();
        check(f.clips.isEmpty());

        f=new Fixture();
        f.say("a hyper believer"); f.say("someone who believes a hyperbole literally");
        ended(f,SelectiveEngine.FORGE);
        check(f.clips.get(0).transcript.startsWith("a hyper believer"));
        check(f.clips.get(0).startedAtMillis==1770000000000L);

        f=new Fixture();
        f.say("when you reflect I respect"); f.say("when you reflect I show respect");
        f.say("put the instructions on the facing page");
        ended(f,SelectiveEngine.POETRY);
        check(f.clips.get(0).category.contains(SelectiveEngine.DECISION));

        f=new Fixture();
        f.say("it reminds me of a tuning fork"); f.say("because both react to another vibration");
        ended(f,SelectiveEngine.CONNECTION);

        f=new Fixture();
        f.engine.setFeedback(SelectiveEngine.CONNECTION,-2);
        f.say("it reminds me of a tuning fork"); f.say("because both react to another vibration");
        f.sound(4,0);
        check(f.clips.isEmpty());
        f.engine.setFeedback(SelectiveEngine.CONNECTION,2);
        f.say("the connection is real because both track new sounds"); ended(f,SelectiveEngine.CONNECTION);

        f=new Fixture();
        for(int i=0;i<110;i++) f.sound(1,i+1);
        f.engine.keep();
        check(f.clips.size()==1 && f.clips.get(0).audio.length==60*SelectiveEngine.RATE);
        check(f.clips.get(0).audio[0]==51);
        f.engine.missed();
        check(f.clips.size()==2 && f.clips.get(1).audio.length==90*SelectiveEngine.RATE);
        check(f.clips.get(1).audio[0]==21);

        f=new Fixture(); f.sound(82,0); f.say("move the subtitle to the next page"); f.sound(1,700);
        f.engine.text("Hey Lexi keep the last minute",false); f.engine.text("Hey Lexi keep the last minute",true);
        check(f.clips.size()==1 && f.clips.get(0).category.contains(SelectiveEngine.DECISION));
        check(f.clips.get(0).audio.length==60*SelectiveEngine.RATE);
        check(!f.clips.get(0).transcript.contains("keep the last minute"));

        f=new Fixture(); f.say("please change the title to autumn"); f.engine.text("Lexi pause Recall",false);
        f.sound(5,0); f.engine.finish(); check(f.pauses==1 && f.clips.isEmpty());

        f=new Fixture(); f.say("put the title on the other page"); f.sound(200,1500);
        check(f.clips.size()==1 && f.clips.get(0).audio.length<=150*SelectiveEngine.RATE);
        f=new Fixture(); f.say("violet prism reflection"); f.sound(4,0); check(f.clips.isEmpty());
        f=new Fixture();
        f.engine.setExamples(Arrays.asList(new SelectiveEngine.Example(
            "I was thinking about a violet prism reflection",SelectiveEngine.MISSED,true)));
        f.say("watch the violet prism reflection"); ended(f,"Learned interest");

        f=new Fixture(); f.engine.setExamples(Arrays.asList(new SelectiveEngine.Example(
            "put the blue subtitle on the facing page",SelectiveEngine.DECISION,false)));
        f.say("put the blue subtitle on the facing page"); f.sound(4,0); check(f.clips.isEmpty());
        f.engine.keep(); check(f.clips.size()==1);
        f=new Fixture(); f.engine.setExamples(Arrays.asList(new SelectiveEngine.Example(
            "Lexi you missed one please keep the last minute",SelectiveEngine.MISSED,true)));
        f.say("I really missed the moment last minute"); f.sound(4,0); check(f.clips.isEmpty());
        System.out.println("Selective engine passed: ephemeral ring, four cues, feedback, lookbacks, overlap, command dedup, pause, finalization, clip bound.");
    }
}
