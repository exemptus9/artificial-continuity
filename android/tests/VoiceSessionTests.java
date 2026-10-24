package org.continuity.recall;

/** Synthetic ordering tests; do not prove device speech recognition or routing. */
public final class VoiceSessionTests {
  private static int checks;
  private static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    VoiceSession session = new VoiceSession();
    long first = session.begin();
    check(session.accepts(first), "new request accepted");
    check(session.listening(first), "ready callback accepted");
    check(session.processing(first), "end callback accepted");
    check(session.active(), "awaiting result still owns request");
    session.cancel();
    check(!session.finish(first), "cancelled result cannot finish or commit");
    check(!session.listening(first), "cancelled ready callback ignored");
    long second = session.begin();
    check(!session.finish(first), "old result cannot consume new request");
    check(session.accepts(second), "new request survives stale callback");
    check(session.finish(second), "one final result accepted");
    check(!session.finish(second), "duplicate result cannot double-save");
    long third = session.begin();
    boolean rejected = false;
    try { session.begin(); } catch (IllegalStateException expected) { rejected = true; }
    check(rejected, "overlapping begin rejected");
    session.cancel();
    check(!session.processing(third), "pause invalidates processing callback");
    check(MicrophoneLease.acquire("speech"), "speech lease acquired");
    check(!MicrophoneLease.acquire("background"), "recorder cannot overlap speech");
    check(!MicrophoneLease.acquire("speech"), "second speech request rejected");
    MicrophoneLease.release("background");
    check(MicrophoneLease.heldBy("speech"), "foreign release cannot free microphone");
    MicrophoneLease.release("speech");
    check(MicrophoneLease.acquire("background"), "recorder can start after speech");
    check(!MicrophoneLease.acquire("speech"), "speech cannot overlap recorder");
    MicrophoneLease.release("background");
    check(MicrophoneLease.owner().isEmpty(), "microphone released");
    check(MicrophoneLease.acquire("recovery-test"), "recovery leases idle microphone");
    check(!MicrophoneLease.acquire("background"), "recording cannot start during recovery");
    MicrophoneLease.release("speech");
    check(MicrophoneLease.heldBy("recovery-test"), "unrelated activity cannot release recovery");
    MicrophoneLease.release("recovery-test");
    System.out.println("VoiceSessionTests: " + checks + " checks passed (synthetic lifecycle only)");
  }
}
