package org.continuity.recall;

/** Main-thread recognition generation fence. Cancelled callbacks cannot save notes. */
public final class VoiceSession {
  public enum Phase { IDLE, STARTING, LISTENING, PROCESSING }
  private long generation;
  private Phase phase = Phase.IDLE;

  public long begin() {
    if (active()) throw new IllegalStateException("A voice request is already active");
    generation++;
    phase = Phase.STARTING;
    return generation;
  }

  public boolean accepts(long token) { return active() && generation == token; }
  public boolean active() { return phase != Phase.IDLE; }
  public Phase phase() { return phase; }
  public boolean listening(long token) {
    if (!accepts(token)) return false;
    phase = Phase.LISTENING;
    return true;
  }
  public boolean processing(long token) {
    if (!accepts(token)) return false;
    phase = Phase.PROCESSING;
    return true;
  }
  public boolean finish(long token) {
    if (!accepts(token)) return false;
    phase = Phase.IDLE;
    return true;
  }
  public void cancel() {
    generation++;
    phase = Phase.IDLE;
  }
}
