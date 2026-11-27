package org.totalrecall.listening;

import java.util.ArrayDeque;

/** Device-local monotonic playback intervals, including a short acoustic tail. Contains no audio. */
final class CuePlaybackGate {
    private static final long MS=1_000_000L,TAIL=200*MS,HISTORY=12_000*MS;
    private static final ArrayDeque<Span> spans=new ArrayDeque<>();
    private static long sequence,recentUntil=Long.MIN_VALUE;
    private static final class Span {
        final long token,start;
        long end;
        Span(long id,long start,long end){this.token=id;this.start=start;this.end=end;}
    }
    private CuePlaybackGate(){}
    static synchronized long begin(long durationMs){
        long now=System.nanoTime();
        while(!spans.isEmpty() && (spans.size()>=40 || spans.peekFirst().end+HISTORY<now))spans.removeFirst();
        long duration=Math.max(1,Math.min(12_000,durationMs))*MS;
        Span span=new Span(++sequence,now,now+duration+TAIL);spans.addLast(span);
        recentUntil=Math.max(recentUntil,span.end+HISTORY);
        return span.token;
    }
    static synchronized void end(long token){
        long now=System.nanoTime();
        for(Span span:spans)if(span.token==token){span.end=Math.min(span.end,now+TAIL);return;}
    }
    static synchronized void cancel(long token){spans.removeIf(span->span.token==token);}
    static synchronized boolean recent(){return System.nanoTime()<=recentUntil;}
    static boolean active(){long now=System.nanoTime();return overlaps(now,now);}
    static synchronized boolean overlaps(long first,long last){
        for(Span span:spans)if(last>=span.start && first<=span.end)return true;
        return false;
    }
}
