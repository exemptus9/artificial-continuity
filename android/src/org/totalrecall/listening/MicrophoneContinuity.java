package org.totalrecall.listening;

/** One boundary per continuous loss of microphone access; only an armed session can resume. */
final class MicrophoneContinuity {
    private boolean armed=true;
    private long lost=-1;
    boolean waiting(){return armed&&lost>=0;}
    boolean lose(long now){if(!armed||lost>=0)return false;lost=now;return true;}
    long resume(long now){if(!armed||lost<0)return -1;long elapsed=Math.max(0,now-lost);lost=-1;return elapsed;}
    long stop(long now){long elapsed=resume(now);armed=false;return elapsed;}
}
