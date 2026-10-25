package com.deepseekharness.app.remote.gateway;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.Toast;
import com.deepseekharness.app.remote.RemoteSessionIdentity;
import com.deepseekharness.app.remote.RemoteConnectionClient;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launches an already authenticated remote session. Does not require Ubuntu or a local DSH. */
public final class RemoteWebLauncher {
  private static final ExecutorService QUEUE =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "remote-web-launch");
            t.setDaemon(true);
            return t;
          });
  private static final AtomicBoolean OPENING = new AtomicBoolean();

  private RemoteWebLauncher() {}

  public static void open(Activity caller) {
    if (Build.VERSION.SDK_INT < 28) {
      Toast.makeText(caller, "Remote browser requires Android 9 or newer", Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (!OPENING.compareAndSet(false, true)) return;
    QUEUE.execute(
        () -> {
          try (RemoteConnectionClient client =
              new RemoteConnectionClient(caller.getApplicationContext())) {
            client.connect();
            RemoteSessionIdentity session = client.current();
            if (session == null) throw new IOException("REMOTE_SESSION_NOT_CONNECTED");
            RemoteWebProfile.select(caller, session.peerId);
            selectProcess(caller, session);
          } catch (Exception failure) {
            caller.runOnUiThread(
                () -> {
                  OPENING.set(false);
                  Toast.makeText(
                          caller,
                          "Remote session unavailable. Connect the PC first.",
                          Toast.LENGTH_LONG)
                      .show();
                });
          }
        });
  }

  private static void selectProcess(Activity caller, RemoteSessionIdentity session)
      throws Exception {
    Context context = caller.getApplicationContext();
    CompletableFuture<IBinder> connected = new CompletableFuture<>();
    ServiceConnection connection =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder binder) {
            connected.complete(binder);
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {}

          @Override
          public void onNullBinding(ComponentName name) {
            connected.completeExceptionally(new IOException("REMOTE_PROFILE_UNAVAILABLE"));
          }
        };
    boolean bound =
        context.bindService(
            new Intent(context, RemoteWebProcessService.class),
            connection,
            Context.BIND_AUTO_CREATE);
    if (!bound) throw new IOException("REMOTE_PROFILE_BIND_FAILED");
    try {
      IBinder binder = connected.get(8, TimeUnit.SECONDS);
      Parcel request = Parcel.obtain(), reply = Parcel.obtain();
      String profile;
      try {
        request.writeInterfaceToken(RemoteWebProcessService.DESCRIPTOR);
        binder.transact(RemoteWebProcessService.PROFILE, request, reply, 0);
        reply.readException();
        profile = reply.readString();
      } finally {
        request.recycle();
        reply.recycle();
      }
      if (!RemoteWebProfile.forPeer(session.peerId).equals(profile)) {
        CountDownLatch retired = new CountDownLatch(1);
        IBinder.DeathRecipient death = retired::countDown;
        binder.linkToDeath(death, 0);
        // Unbind before process death to prevent Android immediately restarting its old binding.
        context.unbindService(connection);
        bound = false;
        request = Parcel.obtain();
        reply = Parcel.obtain();
        try {
          request.writeInterfaceToken(RemoteWebProcessService.DESCRIPTOR);
          binder.transact(RemoteWebProcessService.RETIRE, request, reply, 0);
          reply.readException();
        } finally {
          request.recycle();
          reply.recycle();
        }
        if (!retired.await(8, TimeUnit.SECONDS))
          throw new IOException("REMOTE_PROFILE_RETIRE_TIMEOUT");
      }
      caller.runOnUiThread(
          () -> {
            OPENING.set(false);
            if (!caller.isFinishing() && !caller.isDestroyed())
              caller.startActivity(
                  new Intent(caller, RemoteWebActivity.class)
                      .putExtra(RemoteWebActivity.SESSION, session.toJsonString()));
          });
    } finally {
      if (bound) context.unbindService(connection);
    }
  }
}
