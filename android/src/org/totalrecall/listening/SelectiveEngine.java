package org.totalrecall.listening;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** In-memory selective audio capture. One instance represents one uninterrupted microphone session. */
public final class SelectiveEngine {
    public static final int RATE = 16000;
    public static final int BUFFER_SECONDS = 90;
    public static final String FORGE = "Forge word";
    public static final String POETRY = "Poem line";
    public static final String DECISION = "Project decision";
    public static final String CONNECTION = "Connection";
    public static final String MANUAL = "Manual keep";
    public static final String MISSED = "Missed moment";

    private static final int WINDOW = RATE * BUFFER_SECONDS;
    private static final int MAX_CLIP = RATE * 150;
    private static final int END_SILENCE = RATE * 3;
    private static final int MIN_TAIL = RATE * 4;
    private static final int CONTEXT = RATE * 30;
    private static final int MAX_TEXT = 3000;
    private static final int MAX_SEGMENTS = 180;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}']+");
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
        "about", "after", "again", "because", "being", "could", "going", "knowing", "really",
        "something", "thing", "think", "those", "want", "what", "when", "where", "which",
        "with", "would", "yeah", "your", "that", "this", "there", "them", "have", "here",
        "like", "just", "they", "some", "then", "well", "were", "will", "also", "right"
    ));

    public interface Listener {
        void onClip(Clip clip);
        void onPauseRequested();
        default void onVoice(String message,boolean wake) {}
        default void onSession(int action) {}
        default void onFlag(String reason) {}
    }

    static final String[] WAKES={"hey recall","total recall","lexi","hey lexi"};
    static final String[][] COMMANDS={
        {"Save recent audio","save that","save this","keep that","remember that","save the last minute","keep the last minute","save this idea","save that line","save that poem"},
        {"Recover a missed moment","you missed one","you missed that"},
        {"Start a passage","start a passage","start a poem","start a story","start a draft"},
        {"Finish and save the passage","finish passage","finish the poem","finish the story","finish the draft"},
        {"Pause the microphone","pause recall","pause recording","stop listening","stop recording"},
        {"Start a long session","start a long session","start a special session"},
        {"Finish a long session","finish the session","finish this session"},
        {"Mark a point in a long session","mark this point","mark this line"},
        {"Save a Forge word","keep that word","save that word"},
        {"Save a poem line","keep that line","keep that rhyme"},
        {"Save a project decision","keep that decision","save that decision"}
    };
    private static final int KEEP_WORD=8, KEEP_LINE=9, KEEP_DECISION=10;
    static final Pattern WAKE=Pattern.compile("^(?:(?:hey )?(?:recall|lexi|lexie|lexy|alexi|lexy daimon)|total recall)(?: |$)");
    private static final Pattern TRAILING_REQUEST=Pattern.compile(" (hey recall|total recall|hey lexi|lexi) (.+)$");
    static int actionFor(String words){
        String command=words.replaceFirst("^(?:(?:could|can|would) you |i want (?:you )?to )?(?:please )?","").replaceFirst(" (?:please|for me)$","");
        for(int i=0;i<COMMANDS.length;i++)
            for(int j=1;j<COMMANDS[i].length;j++)if(command.equals(COMMANDS[i][j]))return i;
        if(command.matches("(?:keep|save) (?:the |that )?last (?:minute|moment)"))return 0;
        if(command.matches("(?:stop|pause) (?:total recall|recall|recording|listening)"))return 4;
        return -1;
    }
    static boolean grammarMatch(String words){
        if(words==null||words.contains("[unk]"))return false;
        java.util.regex.Matcher m=WAKE.matcher(words);
        String rest=m.find()?words.substring(m.end()):words;
        return rest.isEmpty()||actionFor(rest)>=0;
    }
    static String guide(){
        StringBuilder guide=new StringBuilder("Say Hey Recall, Total Recall, or Lexi. The panel acknowledges the wake word. Chime and spoken feedback follow your sound settings. Speak the command immediately or within eight seconds.\n");
        for(String[] row:COMMANDS)guide.append("\n").append(row[0]).append(" · ").append(String.join(", ",Arrays.copyOfRange(row,1,row.length)));
        return guide.append("\n\nWord, line and decision saves keep up to the last minute, even mid-passage. In a long session they also add a marker with an eight-second lead-in. Save and missed-moment commands also work alone. Resume from the panel or notification while the microphone is off.").toString();
    }

    public static final class Clip {
        public final short[] audio;
        public final String transcript, category, reason;
        public final long startedAtMillis;
        final boolean finishedByVoice, labeledRewind;

        Clip(short[] audio, String transcript, String category, String reason, long startedAtMillis) {
            this(audio,transcript,category,reason,startedAtMillis,false,false);
        }
        private Clip(short[] audio,String transcript,String category,String reason,long startedAtMillis,boolean byVoice,boolean labeledRewind){
            this.labeledRewind=labeledRewind;
            finishedByVoice=byVoice;
            this.audio = audio;
            this.transcript = transcript;
            this.category = category;
            this.reason = reason;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private static final class Segment {
        final String text, normalized;
        final long at;
        Segment(String text, long at) {
            this.text = text;
            this.normalized = normalize(text);
            this.at = at;
        }
    }

    private static final class Pronunciation {
        final int syllables;
        final String rhyme;
        Pronunciation(int syllables, String rhyme) {
            this.syllables = syllables;
            this.rhyme = rhyme;
        }
    }

    private static volatile Map<String, Pronunciation> dictionary = java.util.Collections.emptyMap();

    /** Loads the pinned stress/syllable/rhyme projection from a packaged asset, once per process. */
    public static synchronized void loadPronunciations(java.io.InputStream gzip) throws java.io.IOException {
        if (!dictionary.isEmpty()) return;
        Map<String, Pronunciation> index = new HashMap<>(180000);
        Map<String, Pronunciation> shared = new HashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.util.zip.GZIPInputStream(gzip), java.nio.charset.StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int first = line.indexOf('\t'), second = line.lastIndexOf('\t');
                if (first < 1 || second <= first || second == line.length() - 1)
                    throw new java.io.IOException("Damaged pronunciation index");
                String key = line.substring(first + 1);
                Pronunciation value = shared.get(key);
                if (value == null) {
                    int count;
                    try { count = Integer.parseInt(line.substring(first+1,second)); }
                    catch (NumberFormatException ex) { throw new java.io.IOException("Invalid syllable count",ex); }
                    value = new Pronunciation(count,line.substring(second+1));
                    shared.put(key,value);
                }
                index.put(line.substring(0,first),value);
            }
        }
        if (index.size() != 124903) throw new java.io.IOException("Incomplete pronunciation index");
        dictionary = index;
    }

    public static int pronunciationCount() { return dictionary.size(); }
    private int sensitivity;
    /** -1 stricter, 0 balanced, +1 exploratory. Explicit commands always retain their meaning. */
    public synchronized void setSensitivity(int value) { sensitivity = Math.max(-1, Math.min(1, value)); }

    private static final Pattern LETTER_RUN = Pattern.compile("(?:^| )(?:[a-z] ){2,}[a-z](?: |$)");
    private static final Set<String> ROUTINE = new HashSet<>(Arrays.asList(
        "buy","buying","bought","shopping","groceries","grocery","receipt","ingredients",
        "errands","appointment","appointments","laundry","items","checklist","orders","order",
        "checkout","percent","dollars","invoice","discount","quantity","restock","pickup"));
    private static boolean routineLine(String s) {
        if (s.matches(".*\\d.*")) return true;
        for (String word : s.split(" ")) if (ROUTINE.contains(word)) return true;
        return has(s,"pick up","i need to get","i need the","on the list","we need some","do we have","i also need");
    }
    private static String endRhyme(String word) {
        if (word == null || word.length() < 2 || BOILERPLATE.contains(word)) return "";
        Pronunciation p = dictionary.get(word);
        return p == null ? "" : p.rhyme;
    }
    private static int syllables(String word) {
        Pronunciation p = dictionary.get(word);
        return p == null ? Math.max(1,word.replaceAll("[^aeiouy]+"," ").trim().split(" +").length) : p.syllables;
    }
    private static final class Line {
        final String[] words;
        final int rhythm;
        final String end;
        Line(String line) {
            words=line.split(" ");
            int total=0;
            for (String word:words) total+=syllables(word);
            rhythm=total; end=words[words.length-1];
        }
    }
    private static boolean similarLength(int a,int b) { return Math.abs(a-b)<= Math.max(2,Math.min(a,b)/3); }
    private static boolean rhymes(String a,String b) {
        if (a.equals(b) || a.endsWith(b) && a.length()-b.length()>2 && b.length()>4 || b.endsWith(a) && b.length()-a.length()>2 && a.length()>4) return false;
        String rhyme=endRhyme(a);
        return !rhyme.isEmpty() && rhyme.equals(endRhyme(b));
    }
    private static boolean alliterates(Line line) {
        Set<String> unique = new HashSet<>();
        int[] initials = new int[26];
        for (String word:line.words) {
            if (word.length()<3 || COMMON.contains(word) || BOILERPLATE.contains(word) || !unique.add(word)) continue;
            char initial=word.charAt(0);
            if (initial>='a' && initial<='z' && ++initials[initial-'a']>=3) return true;
        }
        return false;
    }
    private static boolean repeatedOpening(Line a,Line b) {
        return a.words.length>=4 && b.words.length>=4 && a.words[0].equals(b.words[0]) && a.words[1].equals(b.words[1])
            && !a.end.equals(b.end) && !COMMON.contains(a.words[0]) && !COMMON.contains(a.words[1]);
    }
    /** ASR can emit a single long breath instead of line breaks. Detect three different rhyming
        words at similar syllabic intervals, including the final word of the recognized phrase. */
    private static boolean internalVerse(Line line) {
        String[] words=line.words;
        if (words.length<10 || words.length>90) return false;
        int end=words.length-1;
        int[] beats=new int[words.length+1];
        for(int i=0;i<words.length;i++) beats[i+1]=beats[i]+syllables(words[i]);
        for(int mid=Math.max(2,end-13);mid<=end-3;mid++){
            if(!rhymes(words[mid],words[end]))continue;
            for(int first=Math.max(2,mid-13);first<=mid-3;first++){
                if(rhymes(words[first],words[end]) && rhymes(words[first],words[mid])
                    && similarLength(beats[mid+1]-beats[first+1],beats[end+1]-beats[mid+1]))return true;
            }
        }
        return false;
    }

    private Hit verse(Segment current) {
        if(routineLine(current.normalized)) return null;
        Line now=new Line(current.normalized);
        if(internalVerse(now))return new Hit(POETRY,"Three different words rhyme at similar syllabic intervals inside one utterance.",5);
        if(now.words.length<3||now.words.length>20)return null;
        List<Line> lines=new ArrayList<>();
        lines.add(now);
        java.util.Iterator<Segment> reverse=recent.descendingIterator();
        long newer=at;
        while(reverse.hasNext() && lines.size()<4){
            Segment past=reverse.next();
            if(newer-past.at>14L*RATE || at-past.at>36L*RATE || routineLine(past.normalized))break;
            Line candidate=new Line(past.normalized);
            if(candidate.words.length<3 || candidate.words.length>20)break;
            lines.add(candidate);
            newer=past.at;
        }
        if(lines.size()>=2){
            Line prior=lines.get(1);
            if(now.words.length>=5 && prior.words.length>=5 && similarLength(now.rhythm,prior.rhythm)
                && rhymes(now.end,prior.end) && (alliterates(now)||alliterates(prior)))
                return new Hit(POETRY,"Two similarly paced lines rhyme and one also clusters initial sounds.",5);
        }
        if(lines.size()<3)return null;
        Line prev=lines.get(1), oldest=lines.get(2);
        if(!similarLength(now.rhythm,prev.rhythm)||!similarLength(prev.rhythm,oldest.rhythm))return null;
        if(rhymes(now.end,prev.end)&&rhymes(prev.end,oldest.end)&&rhymes(now.end,oldest.end))
            return new Hit(POETRY,"Three successive phrases have similar lengths and distinct rhyming endings.",5);
        if(lines.size()==4 && rhymes(now.end,oldest.end) && rhymes(prev.end,lines.get(3).end)
                && similarLength(oldest.rhythm,lines.get(3).rhythm))
            return new Hit(POETRY,"Four similarly paced phrases form an alternating end-rhyme pattern.",5);
        if(repeatedOpening(now,prev) && repeatedOpening(prev,oldest) && now.words.length>=5)
            return new Hit(POETRY,"Three similarly paced lines repeat an opening with different endings.",5);
        int soundPatterns=(alliterates(now)?1:0)+(alliterates(prev)?1:0)+(alliterates(oldest)?1:0);
        if(soundPatterns>=2 && (rhymes(now.end,prev.end)||rhymes(now.end,oldest.end)
                || repeatedOpening(now,prev) || repeatedOpening(prev,oldest) || soundPatterns==3))
            return new Hit(POETRY,"Repeated line lengths, clustered initial sounds and parallel phrasing.",4);
        return null;
    }
    private static boolean unknownCandidate(String word) {
        return !dictionary.isEmpty() && word.length()>=5 && word.length()<=35 && word.matches("[a-z]+")
            && !dictionary.containsKey(word);
    }
    private static Hit coinage(String now,String previous) {
        if(has(now,"a word i made up","word that i made up","word i just made up","a word i invented",
                "a word i coined","just coined","just invented a word","just made up a word",
                "i made up the word","i made up a word","my own word","this is my neologism","i coined the term",
                "i made that word up","i made this word up","word i just invented","word i invented","i call this word"))
            return new Hit(FORGE,"You explicitly described a word as one you invented.",7);
        boolean correction = has(now,"no i mean","no wait","no it's","i mean","actually it's","it's pronounced","i pronounce it");
        if (correction && !previous.isEmpty() && previous.split(" ").length<=6 && now.split(" ").length<=10) {
            // ASR often splits a coined word into familiar words: compare compact spoken forms.
            String before=previous.replaceFirst("^(?:a |the |it's |a word )","").replace(" ","");
            String after=now.replaceFirst("^(?:no i mean |no wait |no it's |i mean |actually it's |it's pronounced |i pronounce it )","").replace(" ","");
            int commonPrefix=0;
            while(commonPrefix<Math.min(before.length(),after.length()) && before.charAt(commonPrefix)==after.charAt(commonPrefix))commonPrefix++;
            if(Math.min(before.length(),after.length())>=7 && commonPrefix>=5 &&
                    (unknownCandidate(before)||unknownCandidate(after)))return new Hit(FORGE,"A short name or term was repeated with a pronunciation correction.",4);
        }
        if(LETTER_RUN.matcher(now).find() && (has(now+" "+previous,"word","spell","spelled","spelling","i call it","letters")
                    || previous.split(" ").length<=5 && !previous.isEmpty() && !routineLine(previous)))
            return new Hit(FORGE,"A short candidate term was followed by spoken spelling.",5);
        String[] words=now.split(" ");
        if(words.length>18)return null;
        for(String w:words) {
            if(!unknownCandidate(w))continue;
            if(has(previous,w) || java.util.Collections.frequency(Arrays.asList(words),w)>1)
                return new Hit(FORGE,"A candidate outside the local pronunciation dictionary was repeated.",4);
            for(String candidate:previous.split(" ")){
                if(!unknownCandidate(candidate) || candidate.length()<6 || w.length()<6)continue;
                if(smallEdit(candidate,w) && previous.split(" ").length<=7 && words.length<=7)
                    return new Hit(FORGE,"Two short phrases try similar spellings of a candidate term.",4);
            }
        }
        return null;
    }
    private static boolean smallEdit(String a,String b) {
        if(Math.abs(a.length()-b.length())>1)return false;
        int i=0,j=0,edits=0;
        while(i<a.length()&&j<b.length()){
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++edits>1)return false;
            if(a.length()>=b.length())i++;
            if(a.length()<=b.length())j++;
        }
        return edits+(a.length()-i)+(b.length()-j)==1;
    }
    private static final class Hit {
        final String category, reason;
        final int score;
        Hit(String category, String reason, int score) {
            this.category = category;
            this.reason = reason;
            this.score = score;
        }
    }

    /** An explicitly saved/reviewed example. Only a bounded lexical fingerprint survives in memory. */
    public static final class Example {
        final Set<String> terms;
        final String category;
        final boolean positive;
        public Example(String transcript, String category, boolean positive) {
            String source = transcript == null ? "" : transcript;
            // The end of a retrospective clip is closest to the user's manual marker.
            if (source.length() > 1500) source = source.substring(source.length() - 1500);
            this.terms = learningTerms(source);
            this.category = category == null ? "" : category;
            this.positive = positive;
        }
    }

    private static final Set<String> BOILERPLATE = new HashSet<>(Arrays.asList(
        "lexi", "lexie", "lexy", "recall", "missed", "minute", "moment", "last", "please",
        "keep", "save", "wanted", "mean", "means", "decided", "decision", "using", "should",
        "somebody", "someone", "maybe", "other", "very", "over", "again", "somewhere", "only",
        "kind", "back", "actually", "always", "saying", "said", "sounds", "word", "words",
        "into", "much", "actually", "first", "just", "every", "this", "does", "doing", "then",
        "need", "make", "really", "like", "will", "we're", "we'll", "let's", "know", "want"
    ));

    private static Set<String> learningTerms(String raw) {
        String[] words = normalize(raw).split(" ");
        Set<String> result = new HashSet<>();
        for (int i = words.length - 1; i >= 0 && result.size() < 48; i--) {
            String word = words[i];
            if (word.length() >= 4 && !COMMON.contains(word) && !BOILERPLATE.contains(word)) result.add(word);
        }
        return result;
    }

    private static double similarity(Set<String> query, Set<String> example) {
        if (query.size() < 2 || example.size() < 2) return 0;
        int common = 0;
        for (String word : query) if (example.contains(word)) common++;
        // Require at least two content words and meaningful coverage in the new phrase.
        // The stored example may include lead-in and hence many unrelated words.
        if (common < 2 || 2 * common < query.size()) return 0;
        return common / Math.sqrt((double) query.size() * Math.min(example.size(), query.size() * 2));
    }

    private final ArrayList<Example> examples = new ArrayList<>();
    /** Most recent examples first; repeating the same review never multiplies its influence. */
    public synchronized void setExamples(List<Example> values) {
        examples.clear();
        if (values == null) return;
        for (Example value : values) {
            if (value != null && !value.terms.isEmpty()) examples.add(value);
            if (examples.size() == 32) break;
        }
    }

    private final Listener listener;
    private final long origin;
    private final short[] ring = new short[WINDOW];
    private final ArrayDeque<Segment> recent = new ArrayDeque<>();
    private final Map<String, Integer> feedback = new HashMap<>();
    private final LinkedHashSet<String> tags = new LinkedHashSet<>();
    private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
    private final List<String> keptWords = new ArrayList<>();
    private long at, lastLoud, triggerAt, start, previousEnd;
    private long lastVoiceCommand = -10L * RATE;
    private boolean active, stopped, heldVoiceCommand,passage,wakeAnnounced;
    private int lastAction=-1;
    private long awakeUntil=-1;
    private String interim = "";
    private long interimAt = -1, scoredPartialAt = -3L * RATE;
    private int lastPartialLength;
    private String partialSuffix = "";
    private short[] selected;
    private int length;

    public SelectiveEngine(Listener listener) {
        this(listener, System.currentTimeMillis());
    }

    SelectiveEngine(Listener listener, long origin) {
        if (listener == null) throw new IllegalArgumentException("Missing clip listener");
        this.listener = listener;
        this.origin = origin;
    }

    public synchronized boolean isSelecting() { return active; }

    /** Net positive/negative feedback for this category, repopulated from the local catalog at startup. */
    public synchronized void setFeedback(String category, int score) {
        feedback.put(category, Math.max(-4, Math.min(4, score)));
    }

    public synchronized void pcm(short[] samples, int count) {
        if (count < 0 || count > samples.length) throw new IllegalArgumentException("Invalid PCM length");
        if (stopped || count == 0) return;
        // Check silence in short blocks regardless of the audio adapter's batch size.
        for (int offset = 0; offset < count; ) {
            int n = Math.min(320, count - offset);
            if (active) n = Math.min(n, MAX_CLIP - length);
            long energy = 0;
            for (int i = offset; i < offset + n; i++) {
                int sample = samples[i];
                energy += (long) sample * sample;
            }
            int pos = (int)(at % WINDOW);
            int first = Math.min(n, WINDOW - pos);
            System.arraycopy(samples, offset, ring, pos, first);
            if (first < n) System.arraycopy(samples, offset + first, ring, 0, n - first);
            if (active) {
                int room = Math.min(n, MAX_CLIP - length);
                System.arraycopy(samples, offset, selected, length, room);
                length += room;
            }
            at += n;
            offset += n;
            if (energy > (long) n * 270 * 270) lastLoud = at;
            if (active && (length == MAX_CLIP || (passage && length>=RATE*30) || (!passage &&
                (at - triggerAt >= MIN_TAIL && at - lastLoud >= END_SILENCE)))) {
                boolean continueVerse = length == MAX_CLIP && tags.contains(POETRY)
                    && at - triggerAt <= 30L * RATE && at - lastLoud < END_SILENCE;
                commit();
                if(passage && !stopped){select(POETRY,"Ongoing requested passage.",0);keptWords.clear();}
                else if (continueVerse && !stopped) {
                    select(POETRY, "Continuation of the selected passage after the clip length limit.", 0);
                    keptWords.clear(); // the next final utterance is indexed in the new chunk
                }
            }
        }
        discardExpired();
    }

    /** Feed one recognizer hypothesis. Partials replace each other; finals append exactly once. */
    public synchronized void text(String words, boolean isFinal) {text(words,isFinal,true);}
    synchronized void text(String words, boolean isFinal,boolean allowAutomatic) {
        if (stopped || words == null) return;
        String body = words.trim();
        if (body.length() > MAX_TEXT) body = body.substring(body.length() - MAX_TEXT);
        if (!isFinal && !body.equals(interim)) interimAt = at;
        if (!isFinal) interim = body;
        String n = normalize(body);
        if (n.isEmpty()) {
            if (isFinal) clearInterim();
            return;
        }
        if(isFinal){
            java.util.regex.Matcher tail=TRAILING_REQUEST.matcher(n);
            if(tail.find() && actionFor(tail.group(2))>=0){
                String leading=n.substring(0,tail.start());
                text(leading,true,allowAutomatic);
                n=n.substring(tail.start()+1);
            }
        }
        java.util.regex.Matcher prefix=WAKE.matcher(n);
        boolean addressed=prefix.find();
        String request=addressed?n.substring(prefix.end()):n;
        int action=actionFor(request);
        if(addressed && !wakeAnnounced && !(action>=0 && action==lastAction && at-lastVoiceCommand<RATE*2)){
            listener.onVoice("Wake heard · ready for a command",true);
            awakeUntil=at+RATE*8;wakeAnnounced=true;
        }
        if(addressed && request.isEmpty()){
            if(isFinal)wakeAnnounced=false;
            return;
        }
        if(action>=0 && (addressed || at<=awakeUntil || action<=1 || (action==3 && passage))){
            // Wait for an endpoint on short conversational phrases so "save that for later"
            // cannot become a save while its sentence is still being decoded.
            if(!isFinal && (!addressed || (action!=4 && !request.endsWith("last minute"))))return;
            if(action!=lastAction || at-lastVoiceCommand>=RATE*2){
                lastAction=action;lastVoiceCommand=at;
                clearInterim();
                if(action==0||action==1){
                    listener.onVoice(at==0?"No audio buffered yet":"Command heard · saving recent audio",false);
                    if(action==0)keep();else missed();
                }else if(action==2){
                    if(!passage){passage=true;select(POETRY,"You started a passage by voice.",RATE*4);}
                    listener.onVoice("Capturing passage · say finish passage to save",false);
                }else if(action==3){
                    if(passage){passage=false;commit(true);listener.onVoice("Passage ended",false);}
                    else listener.onVoice("No passage is open",false);
                }else if(action==KEEP_WORD || action==KEEP_LINE || action==KEEP_DECISION){
                    keepAs(action==KEEP_WORD?FORGE:action==KEEP_LINE?POETRY:DECISION);
                }else if(action>=5){listener.onSession(action);}
                else{
                    if(passage){passage=false;commit();}
                    listener.onVoice("Pause command heard",false);pause();listener.onPauseRequested();
                }
            }
            if(isFinal){wakeAnnounced=false;awakeUntil=-1;}
            heldVoiceCommand=!isFinal;return;
        }
        if(isFinal && (addressed || at<=awakeUntil)){
            listener.onVoice("Command unclear · try save that or start a passage",false);
            awakeUntil=-1;
        }
        if(isFinal)wakeAnnounced=false;
        // A fast uninterrupted passage may not endpoint for many seconds. Score a growing
        // hypothesis sparingly, and require two independent signals (or a clear coining cue).
        if (!isFinal) {
            if(!allowAutomatic)return;
            String suffix = body.substring(Math.max(0, body.length()-60));
            boolean growing = body.length() >= lastPartialLength + 15 || body.length() == MAX_TEXT && !suffix.equals(partialSuffix);
            if (at - scoredPartialAt >= 2L * RATE && growing) {
                partialSuffix = suffix;
                scoredPartialAt = at;
                lastPartialLength = body.length();
                String[] tokens = n.split(" ");
                String candidate = n;
                if (tokens.length > 70)
                    candidate = String.join(" ",Arrays.asList(tokens).subList(tokens.length - 70,tokens.length));
                Hit hint = detect(new Segment(candidate, at));
                if (hint != null && hint.score >= 5 && (hint.category.equals(POETRY) || hint.category.equals(FORGE)))
                    select(hint.category, hint.reason, RATE * 45);
            }
            return;
        }
        heldVoiceCommand = false;
        clearInterim();
        discardExpired();
        Segment current = new Segment(body, at);
        if (active) keptWords.add(body);
        Hit hit = allowAutomatic?detect(current):null;
        recent.addLast(current);
        while (recent.size() > MAX_SEGMENTS) recent.removeFirst();
        if (hit != null) select(hit.category, hit.reason, hit.category.equals(POETRY) ? RATE * 45 : CONTEXT);
    }

    int bufferedSeconds(){return (int)(Math.min(at,WINDOW)/RATE);}
    boolean passageOpen(){return passage;}
    public synchronized void keep() {
        if (stopped || at == 0) return;
        select(MANUAL, "You asked to keep the preceding minute.", RATE * 60);
        commit();
        if(passage)select(POETRY,"Ongoing requested passage.",0);
    }

    /** A labeled rewind makes its own clip. A passage or automatic selection keeps its boundaries. */
    synchronized void keepAs(String category) {
        if (!FORGE.equals(category) && !POETRY.equals(category) && !DECISION.equals(category))
            throw new IllegalArgumentException("Unknown rewind category");
        if (stopped || at == 0) {
            listener.onVoice("No audio buffered yet", false);
            return;
        }
        int count = (int)Math.min(at, 60L * RATE);
        long first = at - count;
        short[] audio = new short[count];
        copyRing(first, audio, 0, count);
        ArrayList<String> lines = new ArrayList<>();
        for (Segment segment : recent) if (segment.at >= first) lines.add(segment.text);
        String words = String.join(" ", lines);
        if (!interim.isEmpty() && interimAt >= first && !heldVoiceCommand && !words.endsWith(interim))
            words = (words + " " + interim).trim();
        listener.onClip(new Clip(audio, words, category, "You chose to keep this as " + category + ".",
            origin + first * 1000 / RATE, false, true));
    }

    public synchronized void missed() {
        if (stopped || at == 0) return;
        select(MISSED, "You marked a missed moment; the preceding buffer was retained.", WINDOW);
        commit();
        if(passage)select(POETRY,"Ongoing requested passage.",0);
    }

    /** Stop normally, preserving a selected clip. Never materialize the ordinary ring buffer. */
    public synchronized void finish() {
        try {
            if (active) commit();
        } finally {
            pause();
        }
    }

    /** Immediately abandon transient data; create a new engine when resuming. */
    public synchronized void pause() {
        stopped = true;
        active = false;
        Arrays.fill(ring, (short) 0);
        if (selected != null) Arrays.fill(selected, (short) 0);
        selected = null;
        recent.clear();
        keptWords.clear();
        clearInterim();
        tags.clear();
        reasons.clear();
        length = 0;
    }

    private void clearInterim() {
        interim = "";
        interimAt = -1;
        lastPartialLength = 0;
        partialSuffix = "";
        scoredPartialAt = at - 3L * RATE;
    }

    private void discardExpired() {
        while (!recent.isEmpty() && recent.peekFirst().at < at - WINDOW) recent.removeFirst();
        if (interimAt >= 0 && interimAt < at - WINDOW) clearInterim();
    }

    private void select(String category, String reason, int preRoll) {
        boolean manual = category.equals(MANUAL) || category.equals(MISSED);
        long earliest = Math.max(0, at - WINDOW);
        // Automatic clips never repeat the tail of the last committed clip.
        long desiredStart = Math.max(earliest, Math.max(manual ? 0 : previousEnd, at - preRoll));
        if (!active) {
            start = desiredStart;
            length = (int)(at - start);
            selected = new short[MAX_CLIP];
            copyRing(start, selected, 0, length);
            for (Segment segment : recent) if (segment.at >= start) keptWords.add(segment.text);
            active = true;
        } else if (manual && desiredStart < start) {
            int extra = (int)Math.min(start - desiredStart, MAX_CLIP - length);
            System.arraycopy(selected, 0, selected, extra, length);
            copyRing(start - extra, selected, 0, extra);
            List<String> earlier = new ArrayList<>();
            for (Segment segment : recent) if (segment.at >= start - extra && segment.at < start)
                earlier.add(segment.text);
            keptWords.addAll(0, earlier);
            start -= extra;
            length += extra;
        }
        if(!tags.contains(category) && !manual)listener.onFlag(category);
        tags.add(category);
        reasons.add(reason);
        triggerAt = at;
    }

    private void copyRing(long from, short[] to, int atTo, int count) {
        int pos = (int)(from % WINDOW);
        int first = Math.min(count, WINDOW - pos);
        System.arraycopy(ring, pos, to, atTo, first);
        if (first < count) System.arraycopy(ring, 0, to, atTo + first, count - first);
    }

    private void commit(){commit(false);}
    private void commit(boolean byVoice) {
        if (!active) return;
        // A partial can be useful if a button captured the phrase before the ASR endpoint.
        String text = String.join(" ", keptWords);
        if (!interim.isEmpty() && interimAt >= start && !heldVoiceCommand && !text.endsWith(interim))
            text = (text + " " + interim).trim();
        Clip clip = new Clip(Arrays.copyOf(selected, length), text, String.join(" + ", tags),
            String.join(" ", reasons), origin + start * 1000 / RATE,byVoice,false);
        previousEnd = start + length;
        active = false;
        selected = null;
        length = 0;
        tags.clear();
        reasons.clear();
        keptWords.clear();
        if (clip.audio.length > 0) listener.onClip(clip);
    }

    private static String normalize(String s) {
        return NON_WORD.matcher(s.toLowerCase(Locale.ROOT).replace('’', '\'')).replaceAll(" ").trim();
    }

    private static boolean has(String text, String... phrases) {
        String s = " " + text + " ";
        for (String phrase : phrases) if (s.contains(" " + phrase + " ")) return true;
        return false;
    }

    private Hit detect(Segment current) {
        String s = current.normalized;
        Segment previous = recent.peekLast();
        String earlier = previous != null && at - previous.at <= RATE * 24 ? previous.normalized : "";
        boolean meaning = has(s, "someone who", "somebody who", "it means", "that means", "the word for",
                "a person who", "what it means", "meaning", "means", "i define", "i'd define", "my definition");
        boolean coin = has(s + " " + earlier, "new word", "made up", "i call it", "word i coined",
                "i coined", "how do you spell", "spell it", "i'd call", "i'm calling it", "what if we called");
        boolean shared = sharesDistinctiveWord(s, earlier);
        boolean repeated = repeatedDistinctiveWord(s);
        boolean definitionAfterName = meaning && !earlier.isEmpty() && earlier.split(" ").length <= 6;
        boolean poetry = has(s, "poem line", "line of poetry", "a poem", "this line", "rhymes with");
        String both = earlier.isEmpty() ? s : earlier + " " + s;
        boolean analogy = has(both, "reminds me of", "connected to", "just like", "the same as",
            "the connection", "the link between", "ties into", "connects to", "they're connected", "a parallel");
        boolean explains = has(s, "because", "both", "that's why", "they share", "in the same way");
        boolean choice = has(s, "let's use", "let's go with", "i decided", "i've decided", "the decision is",
            "we'll use", "we will use", "i'm going to use", "the final version", "i want to change");
        boolean task = has(s, "project", "book", "page", "poem", "title", "subtitle", "layout", "website",
                "build", "app", "chapter", "draft", "prototype", "feature", "instructions");
        boolean edit = s.matches("(?:let's |please |we should |i'll )?(?:put|move|change|rename|keep|remove|replace|add) .+");
        List<Hit> options = new ArrayList<>();
        if(has(s,"once upon a time","here is a story","here is a poem","the opening line","a line for my poem","here is the first line","the story begins","let me tell you a story","a line just came to me","here is a stanza"))
            options.add(new Hit(POETRY,"A spoken introduction to a poem, story or draft.",7));
        if(!earlier.isEmpty() && has(earlier,"by the time","that winter","that summer","years later","as a child","when i was little","i still remember")
                && has(s,"i could hear","i could smell","i could feel","it sounded like","it smelled like","the sound of","the smell of"))
            options.add(new Hit(POETRY,"A scene-setting memory is followed by a sensory detail.",5));
        if(has(s,"let me rephrase","a better ending","change that line","another way to say","rewrite that line")
                && has(earlier,"here is a poem","a line","a stanza","my poem","the opening","working title"))
            options.add(new Hit(POETRY,"A draft cue was followed by an explicit revision.",6));
        Hit invented = coinage(s, earlier);
        if (invented != null) options.add(invented);
        Hit verse = verse(current);
        if (verse != null) options.add(verse);
        if (coin || meaning) options.add(new Hit(FORGE, "Language for coining or defining a term.",
            (coin ? 3 : 0) + (meaning ? 2 : 0) + (shared || repeated || definitionAfterName ? 2 : 0)));
        int nearRepeat = !routineLine(s) && !routineLine(earlier) && nearRepeatedLine(s, earlier) ? 4 : 0;
        if (poetry || nearRepeat > 0) options.add(new Hit(POETRY,
            nearRepeat > 0 ? "A short line was repeated or revised in succession." : "An explicit poetry cue.",
            nearRepeat + (poetry ? 4 : 0)));
        if (choice || (task && edit)) options.add(new Hit(DECISION, "A choice or concrete project edit.",
            (choice ? 4 : 0) + (task && edit ? 4 : 0) + (task ? 1 : 0)));
        if (analogy) options.add(new Hit(CONNECTION, "A comparison followed by an explanation.",
            2 + (explains ? 2 : 0)));
        Set<String> words = learningTerms(current.text);
        double bestKeep = 0, bestSkip = 0;
        for (Example example : examples) {
            double similarity = similarity(words, example.terms);
            if(!earlier.isEmpty()){
                Set<String> present=new HashSet<>(words);present.retainAll(example.terms);
                if(present.size()>=2)similarity=Math.max(similarity,.88*similarity(learningTerms(earlier+" "+current.text),example.terms));
            }
            if (example.positive) bestKeep = Math.max(bestKeep, similarity);
            else bestSkip = Math.max(bestSkip, similarity);
        }
        // A tie leaves stock rules alone. Feedback changes sensitivity without suppressing manual saves.
        int reviewAdjustment = 0;
        if (bestKeep >= 0.45 && bestKeep > bestSkip + 0.08) {
            options.add(new Hit("Learned interest", "Shared several content words with a moment you kept or marked missed.", 3));
            reviewAdjustment = 1;
        } else if (bestSkip >= 0.45 && bestSkip > bestKeep + 0.08) {
            reviewAdjustment = -2;
        }
        boolean broadcast=has(s,"thanks for watching","like and subscribe","after this commercial break","this episode is sponsored by");
        if(broadcast && bestKeep<.60)return null;
        Hit best = null;
        for (Hit h : options) {
            int net = feedback.containsKey(h.category) ? feedback.get(h.category) : 0;
            int adjusted = h.score + reviewAdjustment + (net >= 2 ? 1 : net <= -2 ? -1 : 0);
            if (adjusted >= 4 - sensitivity && (best == null || adjusted > best.score))
                best = new Hit(h.category, h.reason, adjusted);
        }
        return best;
    }

    private static Set<String> distinctive(String text) {
        Set<String> set = new HashSet<>();
        for (String w : text.split(" ")) if (w.length() >= 4 && !COMMON.contains(w)) set.add(w);
        return set;
    }

    private static boolean sharesDistinctiveWord(String a, String b) {
        Set<String> words = distinctive(a);
        words.retainAll(distinctive(b));
        return !words.isEmpty();
    }

    private static boolean repeatedDistinctiveWord(String a) {
        Set<String> seen = new HashSet<>();
        for (String w : a.split(" ")) if (w.length() >= 5 && !COMMON.contains(w) && !seen.add(w)) return true;
        return false;
    }

    private static boolean nearRepeatedLine(String current, String previous) {
        String[] a = current.split(" "), b = previous.split(" ");
        if (a.length < 3 || a.length > 18 || b.length < 3 || b.length > 18 ||
            Math.abs(a.length - b.length) > 3) return false;
        Set<String> left = distinctive(current), right = distinctive(previous);
        int total = Math.max(left.size(), right.size());
        left.retainAll(right);
        return left.size() >= 2 && left.size() * 3 >= total * 2;
    }
}
