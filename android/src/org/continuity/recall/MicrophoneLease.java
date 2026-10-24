package org.continuity.recall;

/** Same-process exclusion between the explicit speech request and foreground recorder. */
public final class MicrophoneLease {
  private static String current = "";
  private MicrophoneLease() {}
  public static synchronized boolean acquire(String owner) {
    if (owner == null || owner.isEmpty()) throw new IllegalArgumentException("Missing owner");
    if (!current.isEmpty()) return false;
    current = owner;
    return true;
  }
  public static synchronized void release(String owner) {
    if (current.equals(owner)) current = "";
  }
  public static synchronized boolean heldBy(String owner) { return current.equals(owner); }
  public static synchronized String owner() { return current; }
}
