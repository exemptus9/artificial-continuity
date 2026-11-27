package org.totalrecall.listening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SelectiveEngineTest {
    static class Fixture implements SelectiveEngine.Listener {
        final SelectiveEngine engine = new SelectiveEngine(this, 1770000000000L);
        final List<SelectiveEngine.Clip> clips = new ArrayList<>();
        final List<String> spoken=new ArrayList<>();
        int pauses;
        public void onSpokenRequest(String request){spoken.add(request);}
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
    private static void labeledRewinds() {
        String[][] examples={{"keep that word", SelectiveEngine.FORGE},
            {"keep that line", SelectiveEngine.POETRY}, {"keep that decision", SelectiveEngine.DECISION}};
        for(String[] example:examples){
            Fixture f=new Fixture();
            f.engine.text("hey recall "+example[0],true); // No mic samples yet.
            check(f.clips.isEmpty());
            for(int i=1;i<=70;i++)f.sound(1,i);
            f.engine.text("purple window",true);
            f.engine.text("hey recall "+example[0],false);
            check(f.clips.isEmpty());
            f.engine.text("hey recall "+example[0],true);
            f.engine.text("hey recall "+example[0],true); // Second decoder confirms the same request.
            check(f.clips.size()==1);
            SelectiveEngine.Clip clip=f.clips.get(0);
            check(clip.category.equals(example[1]) && clip.labeledRewind && clip.transcript.equals("purple window"));
            check(clip.audio.length==60*SelectiveEngine.RATE && clip.audio[0]==11 && clip.audio[clip.audio.length-1]==70);
        }
        Fixture f=new Fixture();
        f.say("keep that word"); f.say("word captured"); f.say("project decision saved");
        check(f.clips.isEmpty());
        f.say("hey recall"); f.sound(9,300); f.say("keep that line");
        check(f.clips.isEmpty());
        f=new Fixture();
        f.say("hey recall start a passage"); f.sound(4,700); f.engine.text("blue sky",true);
        f.engine.keepAs(SelectiveEngine.POETRY);
        check(f.clips.size()==1 && f.clips.get(0).audio.length==5*SelectiveEngine.RATE && f.engine.passageOpen());
        f.say("white sand"); f.say("finish passage");
        check(f.clips.size()==2 && f.clips.get(1).transcript.contains("blue sky") && f.clips.get(1).transcript.contains("white sand"));
        System.out.println("Labeled rewinds passed: wake requirement, decoder dedup, exact samples, warmup and independent passage.");
    }

    private static void learningFromLabeledSaves() {
        Fixture f=new Fixture();
        f.say("bananas and groceries"); f.sound(18,0); f.say("amber lantern");
        f.engine.keepAs(SelectiveEngine.POETRY);
        SelectiveEngine.Clip saved=f.clips.get(0);
        check(saved.transcript.equals("bananas and groceries amber lantern") && saved.cueText.equals("amber lantern"));
        f.sound(13,0);f.engine.keepAs(SelectiveEngine.FORGE);
        check(f.clips.get(1).cueText.isEmpty());
        f.engine.text("a nearer phrase",false);f.engine.keepAs(SelectiveEngine.POETRY);
        check(f.clips.get(2).cueText.equals("a nearer phrase"));

        SelectiveEngine.Example kept=new SelectiveEngine.Example(saved.cueText, saved.category,true,true);
        f=new Fixture(); f.engine.setExamples(Arrays.asList(kept)); f.say("my amber lantern");
        ended(f,SelectiveEngine.POETRY);
        check(f.clips.get(0).reason.contains("amber, lantern") && f.clips.get(0).cueText==null);
        f=new Fixture();f.engine.setExamples(Arrays.asList(kept));f.say("amber honey");f.say("bananas and groceries");
        f.engine.finish();check(f.clips.isEmpty());
        f=new Fixture();f.engine.setExamples(Arrays.asList(kept,new SelectiveEngine.Example("amber lantern",SelectiveEngine.DECISION,true,true)));
        f.say("my amber lantern");ended(f,"Learned interest");
        f=new Fixture();f.engine.setExamples(Arrays.asList(kept,new SelectiveEngine.Example("amber lantern",SelectiveEngine.POETRY,false,true)));
        f.say("my amber lantern");f.engine.finish();check(f.clips.isEmpty());

        f=new Fixture();f.sound(1,500);
        f.engine.text("ember looming",true,true,"Emberlumen");
        f.engine.keepAs(SelectiveEngine.FORGE);
        check(f.clips.get(0).cueText.equals("Emberlumen") && f.clips.get(0).transcript.equals("ember looming"));
        f=new Fixture();f.sound(1,500);f.engine.text("a silver lantern",true,true,"Hey Recall pause");
        check(f.pauses==0 && f.clips.isEmpty()); // A display preference cannot become a command.
        SelectiveEngine.Example coined=new SelectiveEngine.Example("emberlumen",SelectiveEngine.FORGE,true,true);
        f=new Fixture();f.engine.setExamples(Arrays.asList(coined));f.sound(1,500);
        f.engine.text("keeping ember looming",true,true,"keeping Emberlumen");
        ended(f,SelectiveEngine.FORGE);check(f.clips.get(0).transcript.equals("keeping ember looming"));
        f=new Fixture();f.engine.setExamples(Arrays.asList(coined));f.say("I remember emberlumen");ended(f,SelectiveEngine.FORGE);
        f=new Fixture();f.engine.setExamples(Arrays.asList(coined));f.say("emberlumens");f.say("thanks for watching emberlumen");f.engine.finish();
        check(f.clips.isEmpty());
        f=new Fixture();f.engine.setExamples(Arrays.asList(new SelectiveEngine.Example("coffee",SelectiveEngine.FORGE,true,true)));
        f.say("coffee tomorrow");f.engine.finish();check(f.clips.isEmpty());
    }

    private static void conversationalSaves(){
        Fixture f=new Fixture();
        f.say("hey recall");
        f.say("can recall");    // Later final from the general decoder for the same wake.
        f.say("deep that line");
        f.engine.text("keep that line",true);
        check(f.clips.size()==1 && f.clips.get(0).labeledRewind && f.clips.get(0).category.equals(SelectiveEngine.POETRY));
        for(String synonym:new String[]{"save that line","save that poem"}){
            f=new Fixture();f.say("hey recall "+synonym);
            check(f.clips.size()==1 && f.clips.get(0).labeledRewind && f.clips.get(0).category.equals(SelectiveEngine.POETRY));
            f=new Fixture();f.say(synonym);
            check(f.clips.size()==1 && !f.clips.get(0).labeledRewind);
        }
        String restricted="[unk] recall keep that word",original="the paper lantern they recall keep that word";
        SelectiveEngine.CommandEndpoints pair=new SelectiveEngine.CommandEndpoints();
        check(pair.observe(restricted,null,null,32000)==null);
        check("recall keep that word".equals(pair.observe(null,original,null,38400)));
        check(pair.observe(null,original,null,39000)==null);
        check("recall keep that word".equals(pair.observe(restricted,null,null,40000)));
        check(pair.observe(restricted,null,null,45000)==null);
        check(pair.observe(null,original,null,45000+2*SelectiveEngine.RATE)==null);
        pair.clear();
        check(pair.observe("hey recall keep that word",null,"the teacher said a recall keep that word",50000)==null);
        check(pair.observe("total recall","we watched the movie total recall together",null,51000)==null);
        check(pair.observe("hey recall keep that word",null,"the paper lantern",53000).equals("hey recall keep that word"));
        check(pair.observe(restricted,null,null,90000)==null);
        pair.clear();check(pair.observe(null,original,null,91000)==null);
        for(String wrong:new String[]{"the teacher said hey recall keep that word","never say hey recall keep that word",
            "the paper lantern they recall keep that decision","the paper lantern keep that word",
            "the paper lantern recall keep that word for later","the paper lantern lexi keep that word"}){
            check(SelectiveEngine.corroboratedSave(restricted,wrong)==null);
        }
        check(SelectiveEngine.corroboratedSave("[unk] keep that word",original)==null);
        check(SelectiveEngine.corroboratedSave("[unk] recall [unk] keep that word",original)==null);
        check(SelectiveEngine.corroboratedSave("[unk] recall pause recording","they recall pause recording")==null);
        f=new Fixture();f.say("the teacher said hey recall keep that word");check(f.clips.isEmpty());
        System.out.println("Conversational saves passed: split wake, category synonyms, both decoder orders, stale and quoted anchors.");
    }

    private static void handsFreeChoices(){
        Fixture f=new Fixture();
        f.engine.text("hey recall save as",true);
        check(!f.engine.choicePending() && f.spoken.isEmpty());
        for(int i=1;i<=65;i++)f.sound(1,i);
        f.engine.text("lantern in the rain",true,true,"Lantern in the Rain");
        f.engine.text("hey recall save as",false);
        check(!f.engine.choicePending());
        f.engine.text("hey murray call save as",true); // Slightly earlier final from the independent dictation decoder.
        f.engine.text("hey recall save as",true);
        check(f.engine.choicePending() && f.spoken.equals(Arrays.asList("choose")) && f.clips.isEmpty());
        f.sound(4,25000);
        f.engine.text("line",false);f.engine.text("do not choose line",true);
        check(f.clips.isEmpty() && f.engine.choicePending());
        f.engine.text("line",true);f.engine.text("line",true);
        check(f.clips.size()==1 && !f.engine.choicePending());
        SelectiveEngine.Clip saved=f.clips.get(0);
        check(saved.fromSpokenChoice && saved.labeledRewind && saved.category.equals(SelectiveEngine.POETRY));
        check(saved.audio.length==60*SelectiveEngine.RATE && saved.audio[0]==6 && saved.audio[saved.audio.length-1]==65);
        check(saved.startedAtMillis==1770000005000L && saved.transcript.equals("lantern in the rain") && saved.cueText.equals("Lantern in the Rain"));
        check(f.spoken.equals(Arrays.asList("choose","choiceClosed")));
        for(String end:new String[]{"cancel","timeout","gap","pause","finish"}){
            f=new Fixture();f.say("hey recall save this as");
            if(end.equals("cancel"))f.engine.text("cancel",true);
            if(end.equals("gap"))f.engine.closeChoice(null);
            if(end.equals("pause"))f.engine.pause();
            if(end.equals("finish"))f.engine.finish();
            if(end.equals("timeout")){
                for(int i=0;i<12;i++){f.engine.cueAudio(SelectiveEngine.RATE);f.sound(1,0);}
            }
            f.engine.text("decision",true);
            check(f.clips.isEmpty() && !f.engine.choicePending() && f.spoken.equals(Arrays.asList("choose","choiceClosed")));
        }
        f=new Fixture();f.say("hey recall save as");
        f.engine.cueAudio(2*SelectiveEngine.RATE);f.sound(9,0);f.engine.text("word",true);
        check(f.clips.size()==1 && f.clips.get(0).category.equals(SelectiveEngine.FORGE));
        f=new Fixture();f.say("help");f.say("status");f.say("word");f.say("repeat");
        check(f.spoken.isEmpty() && f.clips.isEmpty());
        f.engine.text("hey recall",false);check(f.spoken.isEmpty());
        f.say("hey recall what can i say");f.say("lexi status");f.say("hey recall repeat");
        f.say("hey recall");f.engine.text("hey recall",true);f.say("help");
        check(f.spoken.equals(Arrays.asList("help","status","repeat","ready","help")) && f.clips.isEmpty());
        SelectiveEngine.CommandEndpoints pair=new SelectiveEngine.CommandEndpoints();
        check(pair.observe(null,null,"the instructor said",16000)==null);
        check(pair.observe("[unk]",null,"the instructor",17000)==null);
        check(pair.observe("hey recall keep that word",null,"the instructor read a strange word",26000)==null);
        check(pair.observe("hey recall keep that word","hey recall keep that word",null,100000)!=null);
        pair.clear();
        check(pair.observe("[unk] hey recall save as",null,null,16000)==null);
        check("hey recall save as".equals(pair.observe(null,"hey murray call save as",null,23000)));
        check(pair.observe(null,"the instructor said hey recall save as",null,32000)==null);
        check(pair.observe("[unk] hey recall save as",null,"the instructor said hey recall save as",33000)==null);
        pair.clear();
        check(pair.observe("[unk] recall save as","let us save as",null,16000)==null);
        check(pair.observe("[unk] hey recall save as","we may save that",null,17000)==null);
        for(String refusal:new String[]{"stop","wait","don't","wrong","no","discard","nope","not","pause","cancel","don't save"}){
            check(!SelectiveEngine.shortChoiceReading("line",refusal));
        }
        check(SelectiveEngine.shortChoiceReading("line","line") && SelectiveEngine.shortChoiceReading("cancel","can sell"));
        System.out.println("Hands-free choices passed: frozen audio, cue, origin, deduplication, final labels, cancellation, bounded prompt time, pause, status, help and repeat.");
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
        f=new Fixture();f.say("Hey Recall");f.say("please save that for me");check(f.clips.size()==1);
        f.say("please do not save that");check(f.clips.size()==1);
        f=new Fixture();f.say("total recall start a passage");f.say("when the windows turned to mirrors");
        f.sound(9,0);check(f.engine.isSelecting());f.sound(25,1600);f.say("finish passage");
        check(!f.engine.isSelecting() && f.clips.size()==2);
        long total=0;for(SelectiveEngine.Clip c:f.clips)total+=c.audio.length;check(total==37L*SelectiveEngine.RATE);
        f=new Fixture();f.say("hey recall");f.sound(9,0);f.say("pause recording");check(f.pauses==0);
        retain(SelectiveEngine.POETRY,"that summer i went back to the farm","i could smell the earth through the window");
        f=new Fixture();f.say("silver dawn walked through my door hey recall save that");
        check(f.clips.size()==1 && f.clips.get(0).transcript.contains("silver dawn"));
        ignore("we went shopping by the road","i had a sandwich for lunch");
        labeledRewinds();
        conversationalSaves();
        handsFreeChoices();
        learningFromLabeledSaves();
        System.out.println("Natural save requests, wake expiry, poetry and prose cues, and uninterrupted passage capture verified.");
        System.out.println("Selective engine passed: ephemeral ring, four cues, feedback, lookbacks, overlap, command dedup, pause, finalization, clip bound.");
    }
}
