package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ControlRequestJournalTest {
  @Test
  public void duplicateInflightIsUnknownAndFinishedReturnsOriginalReceiptWithoutReplay() {
    var journal = new ControlRequestJournal(2);
    var first = journal.reserve("connection", "request", "digest", () -> true);
    assertEquals(ControlRequestJournal.State.NEW, first.state);
    assertEquals(
        ControlRequestJournal.State.IN_FLIGHT,
        journal.reserve("connection", "request", "digest", () -> true).state);
    journal.finish(first, "{original receipt}");
    var duplicate = journal.reserve("connection", "request", "digest", () -> true);
    assertEquals(ControlRequestJournal.State.FINISHED, duplicate.state);
    assertEquals("{original receipt}", duplicate.receipt);
    assertEquals(
        ControlRequestJournal.State.CONFLICT,
        journal.reserve("connection", "request", "changed", () -> true).state);
  }

  @Test
  public void capacityRefusesNewIdsWithoutForgettingOldDispatchedRequest() {
    var journal = new ControlRequestJournal(1);
    var first = journal.reserve("connection", "request", "digest", () -> true);
    journal.finish(first, "done");
    assertEquals(
        ControlRequestJournal.State.FULL,
        journal.reserve("connection", "next", "next-digest", () -> true).state);
    assertEquals(
        ControlRequestJournal.State.FINISHED,
        journal.reserve("connection", "request", "digest", () -> true).state);
  }

  @Test
  public void staleConnectionCannotResetCurrentJournalAndLateFinishCannotOverwriteIt() {
    var journal = new ControlRequestJournal(2);
    var old = journal.reserve("old", "request", "digest", () -> true);
    var next = journal.reserve("new", "request", "digest-new", () -> true);
    assertEquals(
        ControlRequestJournal.State.STALE,
        journal.reserve("old", "request", "digest", () -> false).state);
    journal.finish(old, "old-result");
    assertEquals(
        ControlRequestJournal.State.IN_FLIGHT,
        journal.reserve("new", "request", "digest-new", () -> true).state);
    journal.finish(next, "new-result");
    assertEquals("new-result", journal.reserve("new", "request", "digest-new", () -> true).receipt);
  }
}
