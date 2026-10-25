package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ControlTaskAuthorityTest {
  @Test
  public void asynchronousPauseRequiresTheExactCurrentLeaseWithoutPausingItsSuccessor() {
    var authority = new ControlTaskAuthority();
    var first = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "same-session", "17");
    var old = authority.begin(first);
    authority.completeLocal(first);
    var next =
        authority.begin(binding(first.owner(), first.connection(), first.taskSessionId(), "18"));
    assertNull(authority.pauseCurrent(old, "OLD_STATUS_FAILURE"));
    assertFalse(authority.snapshot().paused());
    assertTrue(authority.current(next));
    assertEquals(next.binding(), authority.pauseCurrent(next, "CURRENT_STATUS_FAILURE"));
    assertTrue(authority.snapshot().paused());
    assertFalse(authority.current(next));
    authority.resume();
    var resumed = authority.lease(next.binding());
    assertNull(authority.pauseCurrent(next, "STALE_REVISION"));
    assertTrue(authority.current(resumed));
  }

  @Test
  public void remoteFinishFencesExactTaskWithoutPausingAnotherOrRevivingTheSameTurn() {
    var authority = new ControlTaskAuthority();
    var task =
        binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "connection", "full-session", "turn-7");
    var old = authority.begin(task);
    assertFalse(
        authority.finish(binding(task.owner(), task.connection(), "another-session", "turn-7")));
    assertTrue(authority.current(old));
    assertTrue(authority.finish(task));
    assertFalse(authority.current(old));
    assertNull(authority.snapshot().binding());
    assertFalse(authority.snapshot().paused());
    assertThrows(IllegalStateException.class, () -> authority.begin(task));
    assertTrue(
        authority.current(
            authority.begin(
                binding(task.owner(), task.connection(), task.taskSessionId(), "turn-8"))));
  }

  @Test
  public void removingOwnedUiRequiresNewObservationWithoutGrantingResume() {
    var authority = new ControlTaskAuthority();
    var task = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "full-session", "7");
    var old = authority.begin(task);
    assertTrue(authority.observed(old));
    authority.requireObservation(task);
    assertFalse(authority.current(old));
    var current = authority.lease(task);
    assertFalse(authority.actionAllowed(current));
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.requireObservation(
                binding(task.owner(), task.connection(), "another-session", "7")));
    assertTrue(authority.current(current));
    authority.pause("USER_PAUSED");
    assertThrows(IllegalStateException.class, () -> authority.requireObservation(task));
    assertTrue(authority.snapshot().paused());
  }

  private static ControlTaskAuthority.Binding binding(
      ControlTaskAuthority.Owner owner, String connection, String task, String turn) {
    return new ControlTaskAuthority.Binding(owner, connection, task, turn);
  }

  @Test
  public void onePhysicalScreenAcrossIndependentLocalAndRemoteOwners() {
    var authority = new ControlTaskAuthority();
    var local =
        authority.begin(
            binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:17", "local-session", "1"));
    assertFalse(authority.actionAllowed(local));
    assertTrue(authority.observed(local));
    assertTrue(authority.actionAllowed(local));
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(
                    ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "pc-session", "turn-1")));
    authority.stop("LOCAL_DONE");
    assertFalse(authority.current(local));
    var remote =
        authority.begin(
            binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "pc-session", "turn-1"));
    assertTrue(authority.current(remote));
    assertFalse(authority.actionAllowed(remote));
  }

  @Test
  public void pauseFencesImmediatelyAndResumeCannotReuseObservation() {
    var authority = new ControlTaskAuthority();
    var binding =
        binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session-full", "turn-full");
    var old = authority.begin(binding);
    authority.observed(old);
    assertEquals(binding, authority.pause("LOCAL_USER_PAUSE"));
    assertFalse(authority.current(old));
    assertFalse(authority.actionAllowed(old));
    assertThrows(IllegalStateException.class, () -> authority.begin(binding));
    authority.resume();
    assertFalse(authority.current(old));
    var resumed = authority.lease(binding);
    assertFalse(authority.actionAllowed(resumed));
    assertTrue(authority.observed(resumed));
    assertTrue(authority.actionAllowed(resumed));
  }

  @Test
  public void taskStopAndNewConnectionCannotClearLocalPause() {
    var authority = new ControlTaskAuthority();
    authority.begin(
        binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session", "turn"));
    authority.pause("LOCAL_USER_PAUSE");
    authority.stop("REMOTE_STOP");
    assertTrue(authority.snapshot().paused());
    assertEquals("LOCAL_USER_PAUSE", authority.snapshot().reason());
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:2", "session", "turn")));
    assertNull(authority.snapshot().binding());
  }

  @Test
  public void fullConnectionTaskTurnAndOwnerAreDistinctEvenWithSameSuffix() {
    var authority = new ControlTaskAuthority();
    var full =
        binding(
            ControlTaskAuthority.Owner.REMOTE_DEVICE,
            "connection-A",
            "session-abc0001",
            "turn-abc0001");
    var active = authority.begin(full);
    for (var wrong :
        new ControlTaskAuthority.Binding[] {
          binding(
              ControlTaskAuthority.Owner.LOCAL_DSH,
              "connection-A",
              "session-abc0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-B",
              "session-abc0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-A",
              "session-xyz0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-A",
              "session-abc0001",
              "turn-xyz0001")
        }) {
      assertThrows(IllegalStateException.class, () -> authority.lease(wrong));
      assertNotEquals(full.identity(), wrong.identity());
    }
    assertTrue(authority.current(active));
  }

  @Test
  public void oldTurnCannotBeRevivedBySameIdentifiersAfterCompletion() {
    var authority = new ControlTaskAuthority();
    var binding = binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session", "turn");
    var first = authority.begin(binding);
    authority.observed(first);
    assertEquals(binding, authority.stop("DONE"));
    var next = authority.begin(binding);
    assertFalse(authority.current(first));
    assertFalse(authority.actionAllowed(next));
  }

  @Test
  public void consecutiveActualLocalTurnsRetireExactlyAndRequireNewObservation() {
    var authority = new ControlTaskAuthority();
    var a1 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-A-same1234", "1");
    var b1 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-B-same1234", "1");
    var a2 = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "prefix-A-same1234", "2");
    var first = authority.begin(a1);
    authority.observed(first);
    assertThrows(IllegalStateException.class, () -> authority.begin(b1));
    assertFalse(authority.completeLocal(b1));
    assertTrue(authority.current(first));
    assertTrue(authority.completeLocal(a1));
    assertFalse(authority.current(first));
    assertThrows(IllegalStateException.class, () -> authority.begin(a1));
    var second = authority.begin(a2);
    assertFalse(authority.actionAllowed(second));
    assertFalse(authority.completeLocal(a1));
    assertTrue(authority.current(second));
    assertTrue(authority.observed(second));
    assertTrue(authority.actionAllowed(second));
  }

  @Test
  public void completedLocalTurnCannotClearPauseOrRetireRemoteOwner() {
    var authority = new ControlTaskAuthority();
    var local = binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge", "session", "1");
    authority.begin(local);
    authority.pause("USER_PAUSE");
    assertTrue(authority.completeLocal(local));
    assertTrue(authority.snapshot().paused());
    assertEquals("USER_PAUSE", authority.snapshot().reason());
    authority.resume();
    var remote =
        authority.begin(binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "peer", "session", "1"));
    assertFalse(authority.completeLocal(local));
    assertTrue(authority.current(remote));
  }
}
