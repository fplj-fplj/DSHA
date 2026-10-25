package com.deepseekharness.app.control;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One-use request IDs for the authenticated connection. Capacity refuses new IDs, never evicts. */
public final class ControlRequestJournal {
  public enum State {
    NEW,
    IN_FLIGHT,
    FINISHED,
    CONFLICT,
    FULL,
    STALE
  }

  public static final class Reservation {
    public final State state;
    public final String receipt;
    private final Entry entry;

    private Reservation(State state, String receipt, Entry entry) {
      this.state = state;
      this.receipt = receipt;
      this.entry = entry;
    }
  }

  private static final class Entry {
    final String digest;
    String receipt;

    Entry(String digest) {
      this.digest = digest;
    }
  }

  private final int limit;
  private String connection = "";
  private final Map<String, Entry> entries = new HashMap<>();

  public ControlRequestJournal(int limit) {
    if (limit < 1) throw new IllegalArgumentException();
    this.limit = limit;
  }

  public synchronized Reservation reserve(
      String identity, String requestId, String digest, BooleanSupplier current) {
    if (!current.getAsBoolean()) return new Reservation(State.STALE, null, null);
    if (!connection.equals(identity)) {
      connection = identity;
      entries.clear();
    }
    Entry existing = entries.get(requestId);
    if (existing != null) {
      if (!existing.digest.equals(digest)) return new Reservation(State.CONFLICT, null, null);
      return new Reservation(
          existing.receipt == null ? State.IN_FLIGHT : State.FINISHED, existing.receipt, null);
    }
    if (entries.size() >= limit) return new Reservation(State.FULL, null, null);
    Entry added = new Entry(digest);
    entries.put(requestId, added);
    return new Reservation(State.NEW, null, added);
  }

  public synchronized void finish(Reservation reservation, String boundedReceipt) {
    if (reservation == null
        || reservation.state != State.NEW
        || reservation.entry == null
        || boundedReceipt == null
        || boundedReceipt.length() > 65536) throw new IllegalArgumentException();
    reservation.entry.receipt = boundedReceipt;
  }
}
