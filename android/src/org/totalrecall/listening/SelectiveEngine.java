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
    }

    public static final class Clip {
        public final short[] audio;
        public final String transcript, category, reason;
        public final long startedAtMillis;

        Clip(short[] audio, String transcript, String category, String reason, long startedAtMillis) {
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
    private boolean active, stopped, heldVoiceCommand;
    private String interim = "";
    private long interimAt = -1;
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
            if (active && (length == MAX_CLIP ||
                (at - triggerAt >= MIN_TAIL && at - lastLoud >= END_SILENCE))) {
                commit();
            }
        }
        discardExpired();
    }

    /** Feed one recognizer hypothesis. Partials replace each other; finals append exactly once. */
    public synchronized void text(String words, boolean isFinal) {
        if (stopped || words == null) return;
        String body = words.trim();
        if (body.length() > MAX_TEXT) body = body.substring(0, MAX_TEXT);
        if (!isFinal && !body.equals(interim)) interimAt = at;
        if (!isFinal) interim = body;
        String n = normalize(body);
        if (n.isEmpty()) {
            if (isFinal) clearInterim();
            return;
        }
        boolean pause = n.matches("(?:hey )?(?:lexi|lexie|lexy|alexi|lexy daimon) (?:pause|stop) (?:total recall|recall|recording|listening)");
        boolean keep = n.matches("(?:(?:hey )?(?:lexi|lexie|lexy|alexi) )?(?:keep|save) (?:the |that )?last (?:minute|moment)");
        boolean missed = n.matches("(?:(?:hey )?(?:lexi|lexie|lexy|alexi) )?you missed (?:one|that)");
        if (pause) {
            pause();
            listener.onPauseRequested();
            return;
        }
        if (keep || missed) {
            clearInterim();
            if (!heldVoiceCommand && at - lastVoiceCommand >= RATE * 2) {
                if (keep) keep(); else missed();
                lastVoiceCommand = at;
            }
            heldVoiceCommand = !isFinal;
            return;
        }
        if (!isFinal) return;
        heldVoiceCommand = false;
        clearInterim();
        discardExpired();
        Segment current = new Segment(body, at);
        if (active) keptWords.add(body);
        Hit hit = detect(current);
        recent.addLast(current);
        while (recent.size() > MAX_SEGMENTS) recent.removeFirst();
        if (hit != null) select(hit.category, hit.reason, CONTEXT);
    }

    public synchronized void keep() {
        if (stopped || at == 0) return;
        select(MANUAL, "You asked to keep the preceding minute.", RATE * 60);
        commit();
    }

    public synchronized void missed() {
        if (stopped || at == 0) return;
        select(MISSED, "You marked a missed moment; the preceding buffer was retained.", WINDOW);
        commit();
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

    private void commit() {
        if (!active) return;
        // A partial can be useful if a button captured the phrase before the ASR endpoint.
        String text = String.join(" ", keptWords);
        if (!interim.isEmpty() && interimAt >= start && !heldVoiceCommand && !text.endsWith(interim))
            text = (text + " " + interim).trim();
        Clip clip = new Clip(Arrays.copyOf(selected, length), text, String.join(" + ", tags),
            String.join(" ", reasons), origin + start * 1000 / RATE);
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
        if (coin || meaning) options.add(new Hit(FORGE, "Language for coining or defining a term.",
            (coin ? 3 : 0) + (meaning ? 2 : 0) + (shared || repeated || definitionAfterName ? 2 : 0)));
        int nearRepeat = nearRepeatedLine(s, earlier) ? 4 : 0;
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
        Hit best = null;
        for (Hit h : options) {
            int net = feedback.containsKey(h.category) ? feedback.get(h.category) : 0;
            int adjusted = h.score + reviewAdjustment + (net >= 2 ? 1 : net <= -2 ? -1 : 0);
            if (adjusted >= 4 && (best == null || adjusted > best.score))
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
