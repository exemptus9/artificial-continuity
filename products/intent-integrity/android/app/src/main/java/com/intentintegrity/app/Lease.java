package com.intentintegrity.app;
/** In-memory only. Caller supplies elapsedRealtime, never wall-clock time. */
public final class Lease {
    private String key = "";
    private long start = -1, end = -1;
    public void grant(String scope, long now, long duration) {
        if (scope == null || scope.isEmpty() || now < 0 || duration <= 0 || duration > 300000 || now > Long.MAX_VALUE-duration) throw new IllegalArgumentException("Invalid timed pass");
        key=scope; start=now; end=now+duration;
    }
    public boolean valid(String scope, long now) { return key.equals(scope) && now >= start && now < end; }
    public void clear() { key=""; start=-1; end=-1; }
}
