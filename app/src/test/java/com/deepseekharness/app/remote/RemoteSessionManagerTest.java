package com.deepseekharness.app.remote;

import static org.junit.Assert.*;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = PosixAtomicFileShadow.class)
public final class RemoteSessionManagerTest {
  static final class Link implements RemoteSessionManager.Link {
    int opens, closes;
    boolean live = true;

    public com.deepseekharness.app.remote.transport.RemoteTransport.Channel openStream(
        com.deepseekharness.app.remote.transport.RemoteTransport.Kind kind)
        throws java.io.IOException {
      opens++;
      throw new java.io.IOException("UNKNOWN_RESULT");
    }

    public String route() {
      return "direct";
    }

    public boolean isCurrent() {
      return live;
    }

    public void close() {
      closes++;
    }
  }

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private TrustedPeerStore store() {
    return new TrustedPeerStore(
        new File(temp.getRoot(), "pairings"),
        new TrustedPeerStore.CipherStore() {
          public String seal(String plain) {
            return plain;
          }

          public String open(String sealed) {
            return sealed;
          }
        });
  }

  private RemoteHost host(String peer) {
    return new RemoteHost(peer, "Computer", "host", List.of(), List.of());
  }

  @Test
  public void oldAttemptCannotOverwriteNewSelectionOrRestoreAfterStop() {
    RemoteSessionManager manager = new RemoteSessionManager(store());
    long first = manager.connecting(host("pc-one"));
    long second = manager.connecting(host("pc-two"));
    assertFalse(manager.isAttempt(first));
    assertTrue(manager.isAttempt(second));
    manager.failed(first);
    assertEquals(RemoteSessionManager.State.CONNECTING, manager.snapshot().state);
    assertEquals("pc-two", manager.snapshot().host.peerId);
    manager.disconnect("REMOTE_STOPPED");
    assertFalse(manager.isAttempt(second));
    manager.failed(second);
    assertEquals(RemoteSessionManager.State.STOPPED, manager.snapshot().state);
    assertNull(manager.current());
  }

  @Test
  public void reconnectUsesFreshEpochAndTrustDoesNotCreateAnActiveSession() throws Exception {
    TrustedPeerStore store = store();
    RemoteHost host = host("pc-one");
    store.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(store);
    long attempt = manager.connecting(host);
    RemoteSessionIdentity first = manager.identity(host, "instance", "session", attempt);
    RemoteSessionIdentity next = manager.identity(host, "instance", "session", attempt);
    assertNotEquals(first.connectionEpoch, next.connectionEpoch);
    assertFalse(manager.isCurrent(first));
    assertNull(manager.current());
    store.revoke(host.peerId);
    assertEquals(0, store.revision(host.peerId));
    assertFalse(manager.isAttempt(attempt));
    assertThrows(
        java.io.IOException.class, () -> manager.identity(host, "instance", "session", attempt));
  }

  @Test
  public void listenersCanBeRemovedAndPeerControlledErrorsNeverBecomeStatusText() {
    RemoteSessionManager manager = new RemoteSessionManager(store());
    AtomicInteger calls = new AtomicInteger();
    RemoteSessionManager.Listener listener = snapshot -> calls.incrementAndGet();
    manager.addListener(listener);
    manager.connecting(host("pc-one"));
    assertTrue(calls.get() >= 1);
    manager.removeListener(listener);
    int before = calls.get();
    manager.disconnect("secret from remote");
    assertEquals(before, calls.get());
    assertEquals("REMOTE_DISCONNECTED", manager.snapshot().detail);
  }

  @Test
  public void authenticatedOwnerRevocationFencesActionsBeforeClosingItsTransport()
      throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(host);
    RemoteSessionIdentity identity = manager.identity(host, "instance", "session", generation);
    Link link = new Link();
    assertTrue(manager.connected(generation, host, identity, link));
    assertTrue(manager.isCurrent(identity));
    AtomicInteger invalidated = new AtomicInteger();
    manager.addInvalidationListener(
        old -> {
          assertEquals(identity, old);
          assertFalse(manager.isCurrent(old));
          assertEquals(0, link.closes);
          invalidated.incrementAndGet();
        });
    peers.revoke(host.peerId);
    assertEquals(1, invalidated.get());
    assertEquals(1, link.closes);
    assertNull(manager.current());
    assertThrows(
        java.io.IOException.class,
        () -> manager.callDsh(identity, "sessions.send", new org.json.JSONObject()));
    assertEquals(0, link.opens);
  }

  @Test
  public void lostTransportResultIsNeverAutomaticallyReplayed() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(host);
    RemoteSessionIdentity identity = manager.identity(host, "instance", "session", generation);
    Link link = new Link();
    assertTrue(manager.connected(generation, host, identity, link));
    assertThrows(
        java.io.IOException.class,
        () -> manager.callDsh(identity, "sessions.send", new org.json.JSONObject()));
    assertEquals(1, link.opens);
    manager.disconnect("REMOTE_STOPPED");
  }

  @Test
  public void revocationBetweenAuthenticationAndPublicationCannotInstallOwner() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(host);
    RemoteSessionIdentity identity = manager.identity(host, "instance", "session", generation);
    peers.revoke(host.peerId);
    assertFalse(manager.connected(generation, host, identity, new Link()));
    assertNull(manager.current());
  }

  @Test
  public void reconnectInvalidatesOldOwnerAndRequiresNewEpochWithoutBusinessReplay()
      throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long first = manager.connecting(host);
    RemoteSessionIdentity old = manager.identity(host, "instance", "session", first);
    Link link = new Link();
    assertTrue(manager.connected(first, host, old, link));
    AtomicInteger fenced = new AtomicInteger();
    manager.addInvalidationListener(
        owner -> {
          assertEquals(old, owner);
          fenced.incrementAndGet();
        });
    long next = manager.reconnecting(first);
    assertTrue(next > first);
    assertEquals(1, fenced.get());
    assertEquals(1, link.closes);
    assertEquals(RemoteSessionManager.State.RECONNECTING, manager.snapshot().state);
    assertNull(manager.current());
    assertFalse(manager.isCurrent(old));
    assertEquals(0, link.opens);
    RemoteSessionIdentity fresh = manager.identity(host, "instance", "session", next);
    assertNotEquals(old.connectionEpoch, fresh.connectionEpoch);
    assertNotEquals(old.generation, fresh.generation);
    assertFalse(manager.connected(first, host, old, new Link()));
    assertTrue(manager.connected(next, host, fresh, new Link()));
    assertTrue(manager.isCurrent(fresh));
  }

  @Test
  public void stopOrTrustRevocationCancelsPendingRetryBeforeAuthentication() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long first = manager.connecting(host);
    long retry = manager.reconnecting(first);
    assertTrue(manager.isAttempt(retry));
    manager.disconnect("REMOTE_STOPPED");
    assertFalse(manager.isAttempt(retry));
    assertEquals(-1, manager.reconnecting(retry));
    long other = manager.connecting(host);
    long secondRetry = manager.reconnecting(other);
    peers.revoke(host.peerId);
    assertFalse(manager.isAttempt(secondRetry));
    assertEquals(-1, manager.reconnecting(secondRetry));
    assertNull(manager.current());
  }

  @Test
  public void underlyingConnectionReplacementImmediatelyFencesOldActionsBeforeHeartbeat()
      throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost host = host("pc-one");
    peers.trust(host, "ab".repeat(32));
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(host);
    RemoteSessionIdentity identity = manager.identity(host, "instance", "session", generation);
    Link link = new Link();
    assertTrue(manager.connected(generation, host, identity, link));
    assertTrue(manager.isCurrent(identity));
    link.live = false;
    assertFalse(manager.isCurrent(identity));
    assertNull(manager.current());
    assertThrows(
        java.io.IOException.class,
        () -> manager.callDsh(identity, "sessions.send", new org.json.JSONObject()));
    assertEquals(0, link.opens);
    assertTrue(manager.reconnecting(generation) > generation);
  }

  @Test
  public void stoppedRefreshCannotCommitLateAuthenticatedEndpointHints() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost original = host("pc-one");
    peers.trust(original, "ab".repeat(32));
    long revision = peers.revision(original.peerId);
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(original);
    RemoteHost hint =
        new RemoteHost(
            original.peerId,
            "QR",
            "b".repeat(32),
            List.of("/ip4/192.168.1.10/tcp/1234"),
            List.of());
    manager.disconnect("REMOTE_STOPPED");
    assertThrows(
        java.io.IOException.class, () -> manager.commitEndpointRefresh(generation, hint, revision));
    assertEquals(original.addresses, peers.get(original.peerId).addresses);
    assertEquals(revision, peers.revision(original.peerId));
  }

  @Test
  public void refreshedEndpointCanCommitForExactAttemptWithoutChangingGrant() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost original = host("pc-one");
    peers.trust(original, "ab".repeat(32));
    long revision = peers.revision(original.peerId);
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    RemoteHost hint =
        new RemoteHost(
            original.peerId,
            "QR",
            "b".repeat(32),
            List.of("/ip4/192.168.1.10/tcp/1234"),
            List.of());
    long generation = manager.connecting(hint);
    manager.commitEndpointRefresh(generation, hint, revision);
    assertEquals(hint.addresses, peers.get(original.peerId).addresses);
    assertEquals(revision, peers.revision(original.peerId));
    assertEquals("ab".repeat(32), peers.pairingKey(original.peerId));
    peers.revoke(original.peerId);
    assertThrows(
        java.io.IOException.class, () -> manager.commitEndpointRefresh(generation, hint, revision));
  }

  @Test
  public void initialPairCannotReplaceAnExistingTrustedPrincipal() throws Exception {
    TrustedPeerStore peers = store();
    RemoteHost original = host("pc-one");
    peers.trust(original, "ab".repeat(32));
    long revision = peers.revision(original.peerId);
    RemoteSessionManager manager = new RemoteSessionManager(peers);
    long generation = manager.connecting(original);
    assertThrows(
        java.io.IOException.class, () -> manager.commitPair(generation, original, "cd".repeat(32)));
    assertEquals(revision, peers.revision(original.peerId));
    assertEquals("ab".repeat(32), peers.pairingKey(original.peerId));
  }
}
