package com.deepseekharness.app.remote;

import android.content.Context;
import com.deepseekharness.app.remote.transport.RemoteTransport;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns the single active PC connection. It never initializes or starts a local runtime. */
public final class RemoteSessionManager {
  interface Link extends AutoCloseable {
    RemoteTransport.Channel openStream(RemoteTransport.Kind kind) throws IOException;

    String route();

    boolean isCurrent();

    void close();
  }

  public enum State {
    STOPPED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
  }

  public interface Listener {
    void onChanged(Snapshot snapshot);
  }

  public static final class Snapshot {
    public final State state;
    public final RemoteSessionIdentity identity;
    public final RemoteHost host;
    public final String detail, path;
    public final long latencyMillis;

    Snapshot(State state, RemoteSessionIdentity identity, RemoteHost host, String detail) {
      this(state, identity, host, detail, "", -1);
    }

    Snapshot(
        State state,
        RemoteSessionIdentity identity,
        RemoteHost host,
        String detail,
        String path,
        long latencyMillis) {
      this.state = state;
      this.identity = identity;
      this.host = host;
      this.detail = detail;
      this.path = path;
      this.latencyMillis = latencyMillis;
    }
  }

  private static RemoteSessionManager instance;
  private final TrustedPeerStore peers;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<RemoteSessionIdentity>> invalidations =
      new CopyOnWriteArrayList<>();
  private final AtomicLong generations = new AtomicLong(System.currentTimeMillis());
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "remote-rpc-deadlines");
            thread.setDaemon(true);
            return thread;
          });
  private Snapshot snapshot = new Snapshot(State.STOPPED, null, null, "REMOTE_STOPPED");
  private Link connection;
  private long attempt;
  private boolean committingPair;

  public static synchronized RemoteSessionManager get(Context context) {
    if (instance == null) instance = new RemoteSessionManager(TrustedPeerStore.get(context));
    return instance;
  }

  RemoteSessionManager(TrustedPeerStore peers) {
    this.peers = peers;
    peers.addRevocationListener(
        peerId -> {
          synchronized (this) {
            if (committingPair
                || snapshot.host == null
                || !snapshot.host.peerId.equals(peerId)
                || !belongs(attempt)) return;
          }
          disconnect("REMOTE_TRUST_REVOKED");
        });
  }

  public TrustedPeerStore trustedPeers() {
    return peers;
  }

  public synchronized Snapshot snapshot() {
    return snapshot;
  }

  public synchronized RemoteSessionIdentity current() {
    return isCurrent(snapshot.identity) ? snapshot.identity : null;
  }

  public synchronized boolean isCurrent(RemoteSessionIdentity expected) {
    return expected != null
        && snapshot.state == State.CONNECTED
        && expected.equals(snapshot.identity)
        && connection != null
        && connection.isCurrent();
  }

  public void addListener(Listener listener) {
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  public void addInvalidationListener(Consumer<RemoteSessionIdentity> listener) {
    invalidations.addIfAbsent(listener);
  }

  public void removeInvalidationListener(Consumer<RemoteSessionIdentity> listener) {
    invalidations.remove(listener);
  }

  long connecting(RemoteHost host) {
    disconnect("REMOTE_SWITCHED");
    long generation;
    synchronized (this) {
      generation = generations.incrementAndGet();
      attempt = generation;
      snapshot = new Snapshot(State.CONNECTING, null, host, "REMOTE_CONNECTING");
    }
    changed();
    return generation;
  }

  synchronized boolean isAttempt(long generation) {
    return attempt == generation
        && (snapshot.state == State.CONNECTING || snapshot.state == State.RECONNECTING);
  }

  synchronized boolean belongs(long generation) {
    return attempt == generation && (isAttempt(generation) || snapshot.state == State.CONNECTED);
  }

  synchronized long attemptGeneration() {
    return attempt;
  }

  synchronized void commitPair(long generation, RemoteHost host, String key) throws IOException {
    if (!isAttempt(generation)) throw new IOException("REMOTE_SESSION_STALE");
    committingPair = true;
    try {
      synchronized (peers) {
        if (peers.revision(host.peerId) > 0)
          throw new IOException("REMOTE_ALREADY_TRUSTED_USE_REFRESH");
        peers.trust(host, key);
      }
    } finally {
      committingPair = false;
    }
  }

  synchronized void commitEndpointRefresh(
      long generation, RemoteHost authenticated, long expectedTrustRevision) throws IOException {
    if (!isAttempt(generation)) throw new IOException("REMOTE_SESSION_STALE");
    peers.updateAuthenticatedEndpoints(authenticated, expectedTrustRevision);
  }

  long reconnecting(long oldGeneration) {
    Link oldConnection;
    RemoteSessionIdentity oldIdentity;
    long next;
    synchronized (this) {
      if (!belongs(oldGeneration)) return -1;
      oldConnection = connection;
      oldIdentity = snapshot.identity;
      connection = null;
      next = generations.incrementAndGet();
      attempt = next;
      snapshot = new Snapshot(State.RECONNECTING, null, snapshot.host, "REMOTE_RECONNECTING");
      notifyAll();
    }
    invalidate(oldIdentity);
    RemoteProtocol.close(oldConnection);
    changed();
    return next;
  }

  synchronized boolean waitForRetry(long generation, long delayMillis) throws InterruptedException {
    long deadline = android.os.SystemClock.elapsedRealtime() + delayMillis;
    while (isAttempt(generation)) {
      long remaining = deadline - android.os.SystemClock.elapsedRealtime();
      if (remaining <= 0) return true;
      wait(remaining);
    }
    return false;
  }

  void measured(RemoteSessionIdentity identity, String path, long millis) {
    synchronized (this) {
      if (!isCurrent(identity)) return;
      snapshot =
          new Snapshot(
              State.CONNECTED,
              identity,
              snapshot.host,
              "REMOTE_CONNECTED",
              "relay".equals(path) ? "relay" : "direct".equals(path) ? "direct" : "",
              Math.max(-1, millis));
    }
    changed();
  }

  synchronized RemoteSessionIdentity identity(
      RemoteHost host, String hostInstanceId, String sessionId, long generation)
      throws IOException {
    if (!isAttempt(generation)
        || snapshot.host == null
        || !snapshot.host.peerId.equals(host.peerId)) throw new IOException("REMOTE_SESSION_STALE");
    long revision = peers.revision(host.peerId);
    if (revision < 1) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
    return new RemoteSessionIdentity(
        host.peerId, hostInstanceId, sessionId, randomId(), revision, generation);
  }

  boolean connected(
      long generation,
      RemoteHost host,
      RemoteSessionIdentity identity,
      RemoteTransport.Connection transport) {
    return connected(
        generation,
        host,
        identity,
        new Link() {
          public RemoteTransport.Channel openStream(RemoteTransport.Kind kind) throws IOException {
            return transport.openStream(kind);
          }

          public String route() {
            return transport.route();
          }

          public boolean isCurrent() {
            return transport.isCurrent();
          }

          public void close() {
            RemoteProtocol.close(transport);
          }
        });
  }

  boolean connected(
      long generation, RemoteHost host, RemoteSessionIdentity identity, Link transport) {
    synchronized (this) {
      if (!isAttempt(generation)
          || identity.generation != generation
          || !identity.peerId.equals(host.peerId)) return false;
      try {
        if (peers.revision(host.peerId) != identity.trustRevision) return false;
      } catch (IOException unavailable) {
        return false;
      }
      connection = transport;
      snapshot =
          new Snapshot(State.CONNECTED, identity, host, "REMOTE_CONNECTED", transport.route(), -1);
      notifyAll();
    }
    changed();
    return true;
  }

  void failed(long generation) {
    synchronized (this) {
      if (!isAttempt(generation)) return;
      snapshot = new Snapshot(State.FAILED, null, snapshot.host, "REMOTE_CONNECTION_FAILED");
      attempt = generations.incrementAndGet();
      notifyAll();
    }
    changed();
  }

  public void disconnect(String reason) {
    Link oldConnection;
    RemoteSessionIdentity oldIdentity;
    synchronized (this) {
      oldConnection = connection;
      oldIdentity = snapshot.identity;
      connection = null;
      attempt = generations.incrementAndGet();
      snapshot =
          new Snapshot(
              "REMOTE_STOPPED".equals(reason) ? State.STOPPED : State.DISCONNECTED,
              null,
              snapshot.host,
              safeReason(reason));
      notifyAll();
    }
    // Revoke screen permits before a potentially blocking native transport close.
    invalidate(oldIdentity);
    RemoteProtocol.close(oldConnection);
    changed();
  }

  private void invalidate(RemoteSessionIdentity oldIdentity) {
    if (oldIdentity != null)
      for (var listener : invalidations) {
        try {
          listener.accept(oldIdentity);
        } catch (RuntimeException ignored) {
        }
      }
  }

  private static String safeReason(String reason) {
    return java.util.Set.of(
                "REMOTE_STOPPED",
                "REMOTE_SWITCHED",
                "REMOTE_TRUST_REVOKED",
                "REMOTE_DISCONNECTED",
                "REMOTE_SERVICE_STOPPED")
            .contains(reason == null ? "" : reason)
        ? reason
        : "REMOTE_DISCONNECTED";
  }

  private void changed() {
    Snapshot state = snapshot();
    for (Listener listener : listeners) {
      try {
        listener.onChanged(state);
      } catch (RuntimeException ignored) {
      }
    }
  }

  RemoteTransport.Channel open(RemoteSessionIdentity expected, RemoteTransport.Kind kind)
      throws IOException {
    Link active;
    synchronized (this) {
      if (!isCurrent(expected) || connection == null) throw new IOException("REMOTE_SESSION_STALE");
      active = connection;
    }
    RemoteTransport.Channel channel = active.openStream(kind);
    if (!isCurrent(expected)) {
      channel.close();
      throw new IOException("REMOTE_SESSION_STALE");
    }
    return channel;
  }

  /** Caller uses a worker. Never retries a dispatched send/cancel after a lost result. */
  public JSONObject callDsh(RemoteSessionIdentity expected, String method, JSONObject args)
      throws IOException {
    if (!java.util.Set.of(
            "sessions.list",
            "sessions.create",
            "sessions.status",
            "sessions.send",
            "sessions.cancel")
        .contains(method)) throw new IOException("REMOTE_DSH_METHOD");
    try (RemoteTransport.Channel channel = open(expected, RemoteTransport.Kind.CONTROL)) {
      JSONObject result = rpc(channel, "dsh", expected.generation, method, args, 60);
      if (!isCurrent(expected)) throw new IOException("REMOTE_RESULT_STALE");
      return result;
    }
  }

  JSONObject rpc(
      RemoteTransport.Channel channel,
      String operation,
      long generation,
      String method,
      JSONObject args,
      int timeoutSeconds)
      throws IOException {
    String requestId = randomId();
    var timeout =
        deadlines.schedule(() -> RemoteProtocol.close(channel), timeoutSeconds, TimeUnit.SECONDS);
    try {
      JSONObject request =
          new JSONObject()
              .put("v", 1)
              .put("op", operation)
              .put("requestId", requestId)
              .put("generation", generation)
              .put("targetId", "default");
      if (method != null) request.put("method", method);
      if (args != null) request.put("args", args);
      RemoteProtocol.write(channel.output(), request, RemoteProtocol.MAX_REQUEST);
      JSONObject response = RemoteProtocol.read(channel.input(), RemoteProtocol.MAX_RESPONSE);
      if (response.getInt("v") != 1 || !requestId.equals(response.getString("requestId")))
        throw new IOException("REMOTE_RESPONSE_IDENTITY");
      if (!response.getBoolean("ok")) throw new IOException("REMOTE_RPC_REJECTED");
      return response.getJSONObject("result");
    } catch (JSONException invalid) {
      throw new IOException("REMOTE_RESPONSE_INVALID");
    } finally {
      timeout.cancel(false);
    }
  }

  static String randomId() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    StringBuilder result = new StringBuilder(32);
    for (byte value : bytes)
      result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }
}
