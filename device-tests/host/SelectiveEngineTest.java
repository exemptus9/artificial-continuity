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

    private static void retain(String category,String... lines) {
        Fixture f=new Fixture();
        for(String line:lines) f.say(line);
        ended(f,category);
    }
    private static void ignore(String... lines){
        Fixture f=new Fixture();
        for(String line:lines) f.say(line);
        f.sound(5,0); f.engine.finish(); check(f.clips.isEmpty());
    }
    private static void prosody() {
        retain(SelectiveEngine.POETRY,"I fold the corners of the night","my palms disclose a stolen light","my knuckles close around the bright");
        retain(SelectiveEngine.POETRY,"I wore a valley painted blue","the ancient glass that I fell through","my broken window knows me too");
        retain(SelectiveEngine.POETRY,"we whispered names into the stone","a seed of ash concealed the sky","I wandered softly all alone","the last few ghosts forgot to fly");
        retain(SelectiveEngine.POETRY,"quiet corners cradle coals","broken branches breathe beneath","silver sparrows steal the sky");
        retain(SelectiveEngine.POETRY,"I walk the street at night and fold my coat in light and send my ghost in flight");
        retain(SelectiveEngine.FORGE,"glimpsanity","that is a word I made up");
        retain(SelectiveEngine.FORGE,"glimpsanity","glimpsaniti");
        retain(SelectiveEngine.FORGE,"suffralchemy","suffralchemy","it means converting hurt into art");
        retain(SelectiveEngine.FORGE,"I call it a glimneral","spell it g l i m n e r a l");
        retain(SelectiveEngine.FORGE,"hyper believer","no I mean hyper boliever");
        Fixture streaming=new Fixture(); streaming.sound(4,900);
        streaming.engine.text("I walk the street at night and fold my coat in light and send my ghost in flight",false);
        check(streaming.engine.isSelecting()); ended(streaming,SelectiveEngine.POETRY);
        ignore("we need three bananas","pick up some green limes","put milk on the list");
        ignore("I need the blue socks","I need the blue jeans","I need the blue shirt");
        ignore("go buy the paint in blue","go buy a size two shoe","go buy a tube of glue");
        ignore("a little flour and eggs","one cup of sugar and spice","stir them into the batter");
        ignore("I hope you sleep at night","please switch the light","I have to leave at eight");
        ignore("I made up my mind yesterday");
        ignore("what time is the meeting","it is after my last call","I will get back to you");
        Fixture strict=new Fixture();strict.engine.setSensitivity(-1);
        strict.say("glimpsanity");strict.say("glimpsanity");strict.sound(5,0);check(strict.clips.isEmpty());
        Fixture sensitive=new Fixture();sensitive.engine.setSensitivity(1);
        sensitive.say("I have a new word");ended(sensitive,SelectiveEngine.FORGE);

        Fixture longVerse=new Fixture();
        // Use unusual block boundaries and a continuous sample counter to catch drops at exactly 150s.
        int submitted=0;
        for(int line=0;line<180;line++){
            int count=16003;
            short[] audio=new short[count];
            for(int i=0;i<count;i++) audio[i]=(short)((submitted+i)%25000+1000);
            longVerse.engine.pcm(audio,audio.length);submitted+=count;
            String[] verse={"My palms disclose a stolen light","My dreams dissolve into the night","The bruising dawn begins in flight"};
            longVerse.engine.text(verse[line%3],true);
        }
        longVerse.engine.finish();
        check(longVerse.clips.size()==2);
        int cursor=0;
        for(SelectiveEngine.Clip c:longVerse.clips){
            check(c.startedAtMillis==1770000000000L+(cursor*1000L/SelectiveEngine.RATE));
            for(short sample:c.audio){check(sample==(short)(cursor%25000+1000));cursor++;}
        }
        check(cursor==submitted);
        System.out.println("Creative fixtures passed: 6 freestyle patterns (including streaming partial), 5 coinage patterns, 7 ordinary negatives, two sensitivities and 180 s sample-exact clip continuation.");
    }
    public static void main(String[] args) throws Exception {
        String index=args.length==0?"../android/assets/listening/cmudict-prosody.tsv.gz":args[0];
        try(java.io.InputStream in=new java.io.FileInputStream(index)){SelectiveEngine.loadPronunciations(in);}
        check(SelectiveEngine.pronunciationCount()==124903);
        prosody();
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
