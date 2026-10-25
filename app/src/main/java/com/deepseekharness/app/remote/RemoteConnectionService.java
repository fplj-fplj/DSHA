package com.deepseekharness.app.remote;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.deepseekharness.app.R;
import com.deepseekharness.app.control.PhoneControlCoordinator;
import com.deepseekharness.app.control.RemoteControlDispatcher;
import com.deepseekharness.app.remote.transport.RemoteTransport;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** A visible-user-started remote connection; no boot receiver, watchdog or local guest dependency. */
public final class RemoteConnectionService extends Service {
  private static final String CHANNEL = "dsha_remote_connection",
      START = "remote-start",
      STOP = "remote-stop";
  private static final int NOTIFICATION =
      com.deepseekharness.app.util.NotificationIds.REMOTE_CONNECTION;
  private static final Map<String, StartLease> STARTS = new ConcurrentHashMap<>();

  private static final class StartLease {
    final RemoteHost host;
    final String sessionId, ticket;
    final boolean refresh;
    final long expectedTrustRevision;
    final AtomicBoolean endpointCommitted;
    final long expires = SystemClock.elapsedRealtime() + 30_000;

    StartLease(RemoteHost host, String sessionId, String ticket) {
      this(host, sessionId, ticket, false, 0, new AtomicBoolean());
    }

    StartLease(
        RemoteHost host,
        String sessionId,
        String ticket,
        boolean refresh,
        long revision,
        AtomicBoolean committed) {
      this.host = host;
      this.sessionId = sessionId;
      this.ticket = ticket;
      this.refresh = refresh;
      this.expectedTrustRevision = revision;
      this.endpointCommitted = committed;
    }
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService connector =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "remote-connect"));
  private final ExecutorService channels =
      Executors.newFixedThreadPool(4, r -> new Thread(r, "remote-control"));
  private final ExecutorService pumps =
      Executors.newFixedThreadPool(24, r -> new Thread(r, "remote-http"));
  private final java.util.concurrent.ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "remote-control-deadlines"));
  private final Semaphore incoming = new Semaphore(4), http = new Semaphore(12);
  private final java.util.Set<HttpPipe> pipes = ConcurrentHashMap.newKeySet();
  private RemoteSessionManager manager;
  private PendingIntent stopAction;
  private volatile boolean destroyed;
  private volatile RemoteTransport.Connection connectingTransport;
  private final RemoteSessionManager.Listener listener =
      state -> main.post(() -> stateChanged(state));
  private final Consumer<RemoteSessionIdentity> invalidation =
      identity -> {
        PhoneControlCoordinator.get(this).disconnectRemote(identity);
        for (HttpPipe pipe : pipes) if (identity.equals(pipe.identity)) pipe.close();
      };

  /** Called directly from the current Activity's connect button; secrets never enter an Intent. */
  public static void startFromVisibleActivity(Activity activity, RemoteHost host, String sessionId)
      throws IOException {
    start(activity, host, sessionId, null, false, 0);
  }

  /** First pair uses a short-lived QR ticket; only authenticated reconnect credentials are persisted. */
  public static void pairFromVisibleActivity(
      Activity activity, RemoteHost host, String pairingKeyHex, String sessionId)
      throws IOException {
    if (activity == null || host == null) throw new IOException("REMOTE_HOST_REQUIRED");
    if (TrustedPeerStore.get(activity).revision(host.peerId) > 0)
      throw new IOException("REMOTE_ALREADY_TRUSTED_USE_REFRESH");
    start(activity, host, sessionId, TrustedPeerStore.key(pairingKeyHex), false, 0);
  }

  /** Refresh QR carries no reconnect key and cannot create or expand a trust grant. */
  public static void refreshFromVisibleActivity(
      Activity activity, RemoteHost hints, String sessionId) throws IOException {
    if (activity == null || hints == null) throw new IOException("REMOTE_HOST_REQUIRED");
    TrustedPeerStore peers = TrustedPeerStore.get(activity);
    RemoteHost candidate;
    long revision;
    synchronized (peers) {
      candidate = peers.endpointCandidate(hints);
      revision = peers.revision(hints.peerId);
      if (revision < 1) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
    }
    start(activity, candidate, sessionId, null, true, revision);
  }

  private static void start(
      Activity activity,
      RemoteHost host,
      String sessionId,
      String ticket,
      boolean refresh,
      long revision)
      throws IOException {
    if (activity == null
        || activity.isFinishing()
        || activity.isDestroyed()
        || !activity.hasWindowFocus()) throw new IOException("REMOTE_VISIBLE_ACTION_REQUIRED");
    if (host == null) throw new IOException("REMOTE_HOST_REQUIRED");
    RemoteHost.id(sessionId);
    STARTS.entrySet().removeIf(entry -> entry.getValue().expires < SystemClock.elapsedRealtime());
    if (STARTS.size() >= 4) throw new IOException("REMOTE_START_BUSY");
    String nonce = RemoteSessionManager.randomId();
    STARTS.put(
        nonce, new StartLease(host, sessionId, ticket, refresh, revision, new AtomicBoolean()));
    Intent intent =
        new Intent(activity, RemoteConnectionService.class)
            .setAction(START)
            .putExtra("lease", nonce);
    try {
      if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent);
      else activity.startService(intent);
    } catch (RuntimeException failure) {
      STARTS.remove(nonce);
      throw new IOException("REMOTE_FOREGROUND_UNAVAILABLE");
    }
  }

  public static void stop(Context context) {
    RemoteSessionManager.get(context).disconnect("REMOTE_STOPPED");
    context.stopService(new Intent(context, RemoteConnectionService.class));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    manager = RemoteSessionManager.get(this);
    manager.addListener(listener);
    manager.addInvalidationListener(invalidation);
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel(
                  CHANNEL,
                  UiText.choose("电脑连接", "Computer connection"),
                  NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (STOP.equals(intent.getAction())) {
      RemoteSessionIdentity current = manager.current();
      if (current != null && current.connectionEpoch.equals(intent.getStringExtra("epoch"))) {
        manager.disconnect("REMOTE_STOPPED");
        stopSelf();
      } else if (current == null
          && intent.getLongExtra("attempt", -1) == manager.attemptGeneration()) {
        manager.disconnect("REMOTE_STOPPED");
        stopConnecting();
        stopSelf();
      }
      return START_NOT_STICKY;
    }
    StartLease lease =
        START.equals(intent.getAction()) ? STARTS.remove(intent.getStringExtra("lease")) : null;
    if (lease == null || lease.expires < SystemClock.elapsedRealtime()) {
      if (manager.current() == null) stopSelf(startId);
      return START_NOT_STICKY;
    }
    stopConnecting();
    long generation = manager.connecting(lease.host);
    activeRun = generation;
    try {
      publish(manager.snapshot(), generation);
    } catch (RuntimeException unavailable) {
      manager.failed(generation);
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    connector.execute(() -> connect(lease, generation));
    return START_NOT_STICKY;
  }

  private volatile long activeRun;

  private void connect(StartLease lease, long generation) {
    RemoteReconnectPlan retry = new RemoteReconnectPlan();
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>
        retryDeadline = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Object> retryBudget =
        new java.util.concurrent.atomic.AtomicReference<>();
    long currentGeneration = generation;
    StartLease currentLease = lease;
    try {
      while (!destroyed && manager.belongs(currentGeneration)) {
        try {
          connectOnce(
              currentLease,
              currentGeneration,
              () -> {
                retry.connected();
                retryBudget.set(null);
                var old = retryDeadline.getAndSet(null);
                if (old != null) old.cancel(false);
              });
          return;
        } catch (Exception failure) {
          // Retry transport authentication only. No RPC, message, screen action or old hello is
          // replayed.
          if (!manager.belongs(currentGeneration) || destroyed) return;
          long revision;
          try {
            revision = manager.trustedPeers().revision(lease.host.peerId);
          } catch (IOException unavailable) {
            manager.disconnect("REMOTE_DISCONNECTED");
            return;
          }
          if (revision < 1) {
            manager.failed(currentGeneration);
            return;
          }
          long delay = retry.nextDelay(SystemClock.elapsedRealtime());
          if (delay < 0) {
            manager.disconnect("REMOTE_DISCONNECTED");
            return;
          }
          long next = manager.reconnecting(currentGeneration);
          if (next < 0) return;
          currentGeneration = next;
          if (retryDeadline.get() == null) {
            Object budget = new Object();
            retryBudget.set(budget);
            retryDeadline.set(
                deadlines.schedule(
                    () -> {
                      if (!destroyed
                          && activeRun == generation
                          && retryBudget.get() == budget
                          && manager.snapshot().state == RemoteSessionManager.State.RECONNECTING)
                        manager.disconnect("REMOTE_DISCONNECTED");
                    },
                    120,
                    java.util.concurrent.TimeUnit.SECONDS));
          }
          if (!manager.waitForRetry(next, delay) || destroyed) return;
          currentLease =
              new StartLease(
                  lease.host,
                  lease.sessionId,
                  null,
                  lease.refresh && !lease.endpointCommitted.get(),
                  lease.expectedTrustRevision,
                  lease.endpointCommitted);
        }
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      var old = retryDeadline.getAndSet(null);
      if (old != null) old.cancel(false);
      retryBudget.set(null);
      main.post(
          () -> {
            var state = manager.snapshot();
            if (state.state != RemoteSessionManager.State.CONNECTED
                && state.state != RemoteSessionManager.State.CONNECTING
                && state.state != RemoteSessionManager.State.RECONNECTING) {
              stopForeground(STOP_FOREGROUND_REMOVE);
              stopSelf();
            }
          });
    }
  }

  private void connectOnce(StartLease lease, long generation, Runnable authenticated)
      throws Exception {
    RemoteTransport.Connection transport = null;
    try {
      if (!manager.isAttempt(generation) || destroyed) return;
      TrustedPeerStore peers = manager.trustedPeers();
      if (lease.ticket != null && peers.revision(lease.host.peerId) > 0)
        throw new IOException("REMOTE_ALREADY_TRUSTED_USE_REFRESH");
      if (lease.refresh && peers.revision(lease.host.peerId) != lease.expectedTrustRevision)
        throw new IOException("REMOTE_REFRESH_TRUST_CHANGED");
      String key = lease.ticket == null ? peers.pairingKey(lease.host.peerId) : lease.ticket;
      transport = dial(lease.host, key, generation);
      connectingTransport = transport;
      if (!manager.isAttempt(generation) || destroyed) return;
      if (!lease.host.peerId.equals(transport.remotePeerId()))
        throw new IOException("REMOTE_PEER_MISMATCH");
      peers.saveIdentityBase64(transport.identityBase64());
      JSONObject status;
      try (RemoteTransport.Channel channel = transport.openStream(RemoteTransport.Kind.CONTROL)) {
        status = manager.rpc(channel, "status", generation, null, null, 15);
      }
      String hostInstanceId = status.getString("hostInstanceId");
      RemoteHost.id(hostInstanceId);
      if (lease.refresh && !lease.host.hostInstanceId.equals(hostInstanceId))
        throw new IOException("REMOTE_REFRESH_HOST_CHANGED");
      if (lease.ticket != null) {
        if (!lease.host.hostInstanceId.equals(hostInstanceId))
          throw new IOException("REMOTE_PAIR_HOST_CHANGED");
        try (RemoteTransport.Channel channel = transport.openStream(RemoteTransport.Kind.CONTROL)) {
          String requestId = RemoteSessionManager.randomId();
          var deadline =
              deadlines.schedule(
                  () -> RemoteProtocol.close(channel), 20, java.util.concurrent.TimeUnit.SECONDS);
          try {
            RemoteProtocol.write(
                channel.output(),
                new JSONObject()
                    .put("v", 1)
                    .put("op", "pair")
                    .put("requestId", requestId)
                    .put("generation", generation)
                    .put("targetId", "default"),
                RemoteProtocol.MAX_REQUEST);
            JSONObject response = RemoteProtocol.read(channel.input(), RemoteProtocol.MAX_REQUEST);
            if (response.getInt("v") != 1
                || !requestId.equals(response.getString("requestId"))
                || !response.getBoolean("ok")) throw new IOException("REMOTE_PAIR_REJECTED");
            JSONObject paired = response.getJSONObject("result");
            if (!hostInstanceId.equals(paired.getString("hostInstanceId"))
                || paired.getLong("generation") != generation)
              throw new IOException("REMOTE_PAIR_IDENTITY");
            if (!manager.isAttempt(generation) || destroyed) return;
            key = TrustedPeerStore.key(paired.getString("reconnectKey"));
            manager.commitPair(generation, lease.host, key);
            RemoteProtocol.write(
                channel.output(),
                new JSONObject().put("v", 1).put("op", "pair-ack").put("requestId", requestId),
                RemoteProtocol.MAX_REQUEST);
          } finally {
            deadline.cancel(false);
          }
        }
        RemoteProtocol.close(transport);
        connectingTransport = null;
        if (!manager.isAttempt(generation) || destroyed) return;
        transport = dial(lease.host, key, generation);
        connectingTransport = transport;
        try (RemoteTransport.Channel channel = transport.openStream(RemoteTransport.Kind.CONTROL)) {
          status = manager.rpc(channel, "status", generation, null, null, 15);
        }
        if (!hostInstanceId.equals(status.getString("hostInstanceId")))
          throw new IOException("REMOTE_PAIR_HOST_CHANGED");
      }
      RemoteSessionIdentity identity =
          manager.identity(lease.host, hostInstanceId, lease.sessionId, generation);
      try (RemoteTransport.Channel channel = transport.openStream(RemoteTransport.Kind.CONTROL)) {
        manager.rpc(
            channel,
            "hello",
            generation,
            null,
            new JSONObject().put("identity", new JSONObject(identity.toJsonString())),
            15);
      }
      long measured = transport.roundTripMillis();
      if (lease.refresh) {
        manager.commitEndpointRefresh(generation, lease.host, lease.expectedTrustRevision);
        lease.endpointCommitted.set(true);
      }
      if (!manager.connected(generation, lease.host, identity, transport)) {
        if (manager.belongs(generation)) throw new IOException("REMOTE_AUTHORIZATION_CHANGED");
        return;
      }
      connectingTransport = null;
      manager.measured(identity, transport.route(), measured);
      authenticated.run();
      long nextPing = SystemClock.elapsedRealtime() + 5000;
      while (!destroyed && manager.isCurrent(identity)) {
        if (!transport.isCurrent()) throw new IOException("REMOTE_DISCONNECTED");
        if ("disconnected".equals(transport.route())) throw new IOException("REMOTE_DISCONNECTED");
        if (SystemClock.elapsedRealtime() >= nextPing) {
          long roundTrip = transport.roundTripMillis();
          manager.measured(identity, transport.route(), roundTrip);
          nextPing = SystemClock.elapsedRealtime() + 5000;
        }
        RemoteTransport.Channel channel = transport.acceptStream(1000);
        if (channel == null) continue;
        if (channel.kind() != RemoteTransport.Kind.CONTROL || !incoming.tryAcquire()) {
          channel.close();
          continue;
        }
        channels.execute(
            () -> {
              var deadline =
                  deadlines.schedule(
                      () -> RemoteProtocol.close(channel),
                      75,
                      java.util.concurrent.TimeUnit.SECONDS);
              try (RemoteTransport.Channel accepted = channel) {
                if (manager.isCurrent(identity))
                  RemoteControlDispatcher.handle(
                      this, identity, accepted.input(), accepted.output());
              } catch (IOException | RuntimeException ignored) {
              } finally {
                deadline.cancel(false);
                incoming.release();
              }
            });
      }
      if (!destroyed && manager.belongs(generation)) throw new IOException("REMOTE_DISCONNECTED");
    } finally {
      RemoteProtocol.close(transport);
      if (connectingTransport == transport) connectingTransport = null;
    }
  }

  private RemoteTransport.Connection dial(RemoteHost host, String key, long generation)
      throws IOException {
    TrustedPeerStore peers = manager.trustedPeers();
    RemoteTransport.Config config =
        new RemoteTransport.Config(
            peers.identityBase64(),
            host.peerId,
            key,
            host.addresses,
            host.relayAddresses,
            generation,
            host.discoveryURL,
            host.discoveryToken);
    RemoteTransport.Connection result = RemoteTransport.connect(config);
    if (!host.peerId.equals(result.remotePeerId())) {
      RemoteProtocol.close(result);
      throw new IOException("REMOTE_PEER_MISMATCH");
    }
    return result;
  }

  private void stopConnecting() {
    RemoteTransport.Connection pending = connectingTransport;
    connectingTransport = null;
    RemoteProtocol.close(pending);
  }

  private void stateChanged(RemoteSessionManager.Snapshot state) {
    if (destroyed) return;
    if (state != manager.snapshot()) return;
    if (state.state == RemoteSessionManager.State.CONNECTED
        || state.state == RemoteSessionManager.State.CONNECTING
        || state.state == RemoteSessionManager.State.RECONNECTING) {
      try {
        publish(state, manager.attemptGeneration());
      } catch (RuntimeException failure) {
        manager.disconnect("REMOTE_SERVICE_STOPPED");
        stopSelf();
      }
    } else {
      stopConnecting();
      for (HttpPipe pipe : pipes) pipe.close();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }
  }

  private void publish(RemoteSessionManager.Snapshot state, long generation) {
    Intent open =
        new Intent()
            .setClassName(this, "com.deepseekharness.app.remote.ui.WorkspaceActivity")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            this,
            NOTIFICATION,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    if (stopAction != null) stopAction.cancel();
    String epoch =
        state.identity == null ? "connecting-" + generation : state.identity.connectionEpoch;
    Intent stop =
        new Intent(this, RemoteConnectionService.class)
            .setAction(STOP)
            .setData(Uri.parse("dsha://remote-stop/" + epoch))
            .putExtra("epoch", epoch)
            .putExtra("attempt", generation);
    stopAction = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
    builder
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(UiText.choose("DSHA 电脑连接", "DSHA computer connection"))
        .setContentText(
            state.state == RemoteSessionManager.State.CONNECTED
                ? UiText.choose("已连接电脑，可在通知中停止", "Computer connected. Stop here at any time.")
                : state.state == RemoteSessionManager.State.RECONNECTING
                    ? UiText.choose(
                        "正在恢复电脑连接，操作已暂停", "Restoring the connection. Actions are paused.")
                    : UiText.choose("正在连接电脑…", "Connecting to computer…"))
        .setContentIntent(content)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .addAction(
            new Notification.Action.Builder(null, UiText.choose("断开连接", "Disconnect"), stopAction)
                .build());
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(NOTIFICATION, builder.build());
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  private final Binder binder =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws android.os.RemoteException {
          if (code == INTERFACE_TRANSACTION) {
            reply.writeString(RemoteConnectionClient.DESCRIPTOR);
            return true;
          }
          if (Binder.getCallingUid() != android.os.Process.myUid())
            throw new SecurityException("REMOTE_BIND_UID");
          if (code == RemoteConnectionClient.TRANSACTION_UI_LANGUAGE) {
            data.enforceInterface(RemoteConnectionClient.DESCRIPTOR);
            if (data.dataAvail() != 0) throw new SecurityException("REMOTE_BIND_TRAILING_DATA");
            reply.writeNoException();
            reply.writeString(
                new com.deepseekharness.app.core.ConfigStore(RemoteConnectionService.this)
                    .getUiLanguage());
            return true;
          }
          if (code == RemoteConnectionClient.TRANSACTION_CURRENT) {
            data.enforceInterface(RemoteConnectionClient.DESCRIPTOR);
            if (data.dataAvail() != 0) throw new SecurityException("REMOTE_BIND_TRAILING_DATA");
            RemoteSessionIdentity current = manager.current();
            reply.writeNoException();
            reply.writeString(current == null ? null : current.toJsonString());
            return true;
          }
          if (code == RemoteConnectionClient.TRANSACTION_OPEN_HTTP) {
            data.enforceInterface(RemoteConnectionClient.DESCRIPTOR);
            try {
              RemoteSessionIdentity expected =
                  RemoteSessionIdentity.fromJsonString(data.readString());
              if (data.dataAvail() != 0) throw new SecurityException("REMOTE_BIND_TRAILING_DATA");
              try (ParcelFileDescriptor descriptor = openHttp(expected)) {
                reply.writeNoException();
                reply.writeTypedObject(
                    descriptor, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
              }
            } catch (IOException | IllegalArgumentException failure) {
              reply.writeException(new IllegalStateException("REMOTE_STREAM_UNAVAILABLE"));
            }
            return true;
          }
          return super.onTransact(code, data, reply, flags);
        }
      };

  private ParcelFileDescriptor openHttp(RemoteSessionIdentity expected) throws IOException {
    if (!http.tryAcquire()) throw new IOException("REMOTE_STREAM_LIMIT");
    RemoteTransport.Channel channel = null;
    ParcelFileDescriptor[] pair = null;
    HttpPipe pipe = null;
    try {
      channel = manager.open(expected, RemoteTransport.Kind.HTTP);
      pair = ParcelFileDescriptor.createSocketPair();
      pipe = new HttpPipe(pair[1], channel, expected);
      pipes.add(pipe);
      if (!manager.isCurrent(expected)) {
        pipe.close();
        throw new IOException("REMOTE_SESSION_STALE");
      }
      pipe.start();
      return pair[0];
    } catch (IOException | RuntimeException failure) {
      if (pair == null) {
        RemoteProtocol.close(channel);
        http.release();
      } else {
        if (pipe != null) pipe.close();
        try {
          pair[0].close();
        } catch (IOException ignored) {
        }
      }
      throw failure;
    }
  }

  private final class HttpPipe {
    final ParcelFileDescriptor descriptor;
    final RemoteTransport.Channel channel;
    final RemoteSessionIdentity identity;
    final AtomicBoolean closed = new AtomicBoolean();

    HttpPipe(
        ParcelFileDescriptor descriptor,
        RemoteTransport.Channel channel,
        RemoteSessionIdentity identity) {
      this.descriptor = descriptor;
      this.channel = channel;
      this.identity = identity;
    }

    void start() {
      pumps.execute(
          () -> {
            try {
              InputStream source = new java.io.FileInputStream(descriptor.getFileDescriptor());
              copy(source, channel.output());
              channel.closeOutput();
            } catch (IOException failure) {
              close();
            }
          });
      pumps.execute(
          () -> {
            try {
              OutputStream target = new java.io.FileOutputStream(descriptor.getFileDescriptor());
              copy(channel.input(), target);
            } catch (IOException ignored) {
            } finally {
              close();
            }
          });
    }

    void close() {
      if (!closed.compareAndSet(false, true)) return;
      RemoteProtocol.close(channel);
      try {
        descriptor.close();
      } catch (IOException ignored) {
      }
      pipes.remove(this);
      http.release();
    }
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[32 * 1024];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
      output.flush();
    }
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    manager.removeListener(listener);
    manager.disconnect("REMOTE_SERVICE_STOPPED");
    manager.removeInvalidationListener(invalidation);
    stopConnecting();
    for (HttpPipe pipe : pipes) pipe.close();
    connector.shutdownNow();
    channels.shutdownNow();
    pumps.shutdownNow();
    deadlines.shutdownNow();
    if (stopAction != null) stopAction.cancel();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
