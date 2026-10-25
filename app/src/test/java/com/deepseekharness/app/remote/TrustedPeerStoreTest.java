package com.deepseekharness.app.remote;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
public final class TrustedPeerStoreTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private static final String KEY = "ab".repeat(32), IDENTITY = "ZGV2aWNlLWtleQ==";
  private static final TrustedPeerStore.CipherStore CIPHER =
      new TrustedPeerStore.CipherStore() {
        public String seal(String plain) {
          return "test:"
              + android.util.Base64.encodeToString(
                  plain.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        }

        public String open(String sealed) throws IOException {
          if (!sealed.startsWith("test:")) throw new IOException("TEST_AUTHENTICATION_FAILED");
          return new String(
              android.util.Base64.decode(sealed.substring(5), android.util.Base64.NO_WRAP),
              StandardCharsets.UTF_8);
        }
      };

  private RemoteHost host(String peer) {
    return new RemoteHost(
        peer, "Computer", "a".repeat(32), List.of("/ip4/127.0.0.1/tcp/1"), List.of());
  }

  private TrustedPeerStore store(File file) {
    return new TrustedPeerStore(file, CIPHER);
  }

  @Test
  public void persistentPairingAndIdentitySurviveNewStoreWithoutSessionAuthority()
      throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore original = store(file);
    original.saveIdentityBase64(IDENTITY);
    original.trust(host("pc-one"), KEY);
    long revision = original.revision("pc-one");
    TrustedPeerStore reopened = store(file);
    assertEquals(IDENTITY, reopened.identityBase64());
    assertEquals(KEY, reopened.pairingKey("pc-one"));
    assertEquals(revision, reopened.revision("pc-one"));
    assertFalse(Files.readString(file.toPath()).contains(KEY));
    assertNull(new RemoteSessionManager(reopened).current());
  }

  @Test
  public void revokeFencesLiveUseBeforePersistenceAndFutureTrustGetsNewRevision() throws Exception {
    TrustedPeerStore store = store(new File(temp.getRoot(), "pairings"));
    store.trust(host("pc-one"), KEY);
    long first = store.revision("pc-one");
    AtomicBoolean called = new AtomicBoolean();
    store.addRevocationListener(
        peer -> {
          try {
            assertEquals(0, store.revision(peer));
            called.set(true);
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
        });
    store.revoke("pc-one");
    assertTrue(called.get());
    assertTrue(store.list().isEmpty());
    assertThrows(IOException.class, () -> store.pairingKey("pc-one"));
    TrustedPeerStore reopened = store(new File(temp.getRoot(), "pairings"));
    reopened.trust(host("pc-one"), KEY);
    assertTrue(reopened.revision("pc-one") > first);
  }

  @Test
  public void corruptedStoreCannotBeSilentlyOverwrittenByTrustOrNewIdentity() throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    byte[] corrupt = "unreadable ciphertext".getBytes(StandardCharsets.UTF_8);
    Files.write(file.toPath(), corrupt);
    TrustedPeerStore store = store(file);
    assertThrows(IOException.class, store::list);
    assertThrows(IOException.class, () -> store.trust(host("pc-two"), KEY));
    assertThrows(IOException.class, () -> store.saveIdentityBase64(IDENTITY));
    assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
  }

  @Test
  public void failedKeystoreSealDoesNotPublishPlaintextOrLoseOriginalPairing() throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore first = store(file);
    first.trust(host("pc-one"), KEY);
    byte[] original = Files.readAllBytes(file.toPath());
    TrustedPeerStore broken =
        new TrustedPeerStore(
            file,
            new TrustedPeerStore.CipherStore() {
              public String seal(String plain) throws IOException {
                throw new IOException("LOCKED");
              }

              public String open(String sealed) throws IOException {
                return CIPHER.open(sealed);
              }
            });
    assertThrows(IOException.class, () -> broken.trust(host("pc-two"), KEY));
    assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    assertThrows(IOException.class, () -> broken.revoke("pc-one"));
    assertEquals(0, broken.revision("pc-one"));
    assertThrows(IOException.class, () -> broken.pairingKey("pc-one"));
  }

  @Test
  public void peerReplacementRevisesOnlyThatPrincipalAndIdentityCannotRotateSilently()
      throws Exception {
    TrustedPeerStore store = store(new File(temp.getRoot(), "pairings"));
    store.saveIdentityBase64(IDENTITY);
    store.trust(host("pc-one"), KEY);
    store.trust(host("pc-two"), "cd".repeat(32));
    long one = store.revision("pc-one"), two = store.revision("pc-two");
    store.trust(host("pc-one"), "ef".repeat(32));
    assertTrue(store.revision("pc-one") > one);
    assertEquals(two, store.revision("pc-two"));
    assertThrows(IOException.class, () -> store.saveIdentityBase64("YW5vdGhlcg=="));
    assertEquals(IDENTITY, store.identityBase64());
  }

  @Test
  public void discoveryCredentialAndAddressHintsPersistInsideEncryptedRecord() throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore store = store(file);
    RemoteHost host =
        new RemoteHost(
            "pc-one",
            "Desktop",
            "a".repeat(32),
            List.of(),
            List.of("/dns4/relay.example/tcp/443/wss/p2p/example"),
            "https://relay.example",
            KEY);
    store.trust(host, KEY);
    RemoteHost read = store(file).get("pc-one");
    assertEquals("https://relay.example", read.discoveryURL);
    assertEquals(KEY, read.discoveryToken);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RemoteHost(
                "pc", "Computer", "", List.of(), List.of(), "http://relay.example", KEY));
  }

  @Test
  public void endpointRefreshPreservesKeyGrantLabelAndDeviceIdentity() throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore store = store(file);
    store.saveIdentityBase64(IDENTITY);
    store.trust(host("pc-one"), KEY);
    store.trust(host("pc-two"), "cd".repeat(32));
    long revision = store.revision("pc-one"), other = store.revision("pc-two");
    RemoteHost hint =
        new RemoteHost(
            "pc-one",
            "Untrusted QR label",
            "b".repeat(32),
            List.of("/ip4/192.168.1.10/tcp/1234"),
            List.of());
    RemoteHost candidate = store.endpointCandidate(hint);
    assertEquals("Computer", candidate.label);
    assertEquals("/ip4/127.0.0.1/tcp/1", store.get("pc-one").addresses.get(0));
    store.updateAuthenticatedEndpoints(candidate, revision);
    TrustedPeerStore reopened = store(file);
    assertEquals(hint.addresses, reopened.get("pc-one").addresses);
    assertEquals("Computer", reopened.get("pc-one").label);
    assertEquals("b".repeat(32), reopened.get("pc-one").hostInstanceId);
    assertEquals(KEY, reopened.pairingKey("pc-one"));
    assertEquals(revision, reopened.revision("pc-one"));
    assertEquals(other, reopened.revision("pc-two"));
    assertEquals(IDENTITY, reopened.identityBase64());
  }

  @Test
  public void endpointRefreshRejectsUnknownRevokedOrChangedTrustWithoutWriting() throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore store = store(file);
    store.trust(host("pc-one"), KEY);
    RemoteHost hint =
        new RemoteHost(
            "pc-one", "QR", "b".repeat(32), List.of("/ip4/192.168.1.10/tcp/1234"), List.of());
    byte[] original = Files.readAllBytes(file.toPath());
    long revision = store.revision("pc-one");
    assertThrows(IOException.class, () -> store.endpointCandidate(host("unknown")));
    assertThrows(IOException.class, () -> store.updateAuthenticatedEndpoints(hint, revision + 1));
    assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    store.revoke("pc-one");
    byte[] revoked = Files.readAllBytes(file.toPath());
    assertThrows(IOException.class, () -> store.updateAuthenticatedEndpoints(hint, revision));
    assertArrayEquals(revoked, Files.readAllBytes(file.toPath()));
  }

  @Test
  public void endpointHintDoesNotWriteAndMissingDiscoveryRetainsPriorPrivateConfiguration()
      throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore store = store(file);
    RemoteHost original =
        new RemoteHost(
            "pc-one",
            "Computer",
            "a".repeat(32),
            List.of("/ip4/127.0.0.1/tcp/1"),
            List.of("/dns4/relay.example/tcp/443/wss/p2p/example"),
            "https://relay.example",
            KEY);
    store.trust(original, KEY);
    byte[] before = Files.readAllBytes(file.toPath());
    RemoteHost hint =
        new RemoteHost(
            "pc-one", "QR", "b".repeat(32), List.of("/ip4/192.168.1.10/tcp/1234"), List.of());
    RemoteHost candidate = store.endpointCandidate(hint);
    assertEquals(original.relayAddresses, candidate.relayAddresses);
    assertEquals(original.discoveryURL, candidate.discoveryURL);
    assertEquals(original.discoveryToken, candidate.discoveryToken);
    assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    TrustedPeerStore locked =
        new TrustedPeerStore(
            file,
            new TrustedPeerStore.CipherStore() {
              public String seal(String plain) throws IOException {
                throw new IOException("LOCKED");
              }

              public String open(String sealed) throws IOException {
                return CIPHER.open(sealed);
              }
            });
    assertThrows(
        IOException.class,
        () -> locked.updateAuthenticatedEndpoints(candidate, store.revision("pc-one")));
    assertArrayEquals(before, Files.readAllBytes(file.toPath()));
  }

  @Test
  public void silentAtomicCommitFailureKeepsOriginalAndNeverClearsRevocationFence()
      throws Exception {
    File file = new File(temp.getRoot(), "pairings");
    TrustedPeerStore store = store(file);
    store.trust(host("pc-one"), KEY);
    byte[] original = Files.readAllBytes(file.toPath());
    PosixAtomicFileShadow.failNextCommit();
    assertEquals(
        "REMOTE_STORE_COMMIT_FAILED",
        assertThrows(IOException.class, () -> store.trust(host("pc-two"), KEY)).getMessage());
    assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    assertNull(store.get("pc-two"));
    PosixAtomicFileShadow.failNextCommit();
    assertEquals(
        "REMOTE_STORE_COMMIT_FAILED",
        assertThrows(IOException.class, () -> store.revoke("pc-one")).getMessage());
    assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    assertEquals(0, store.revision("pc-one"));
    assertThrows(IOException.class, () -> store.pairingKey("pc-one"));
  }
}
