package com.deepseekharness.app.remote;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same-UID cross-process gateway client. Call connect/current/openHttp from a worker. */
public final class RemoteConnectionClient implements AutoCloseable {
  public static final String DESCRIPTOR = "com.deepseekharness.app.remote.IRemoteConnectionService";
  public static final int TRANSACTION_CURRENT = IBinder.FIRST_CALL_TRANSACTION;
  public static final int TRANSACTION_OPEN_HTTP = IBinder.FIRST_CALL_TRANSACTION + 1;
  public static final int TRANSACTION_UI_LANGUAGE = IBinder.FIRST_CALL_TRANSACTION + 2;
  private final Context context;
  private final CountDownLatch ready = new CountDownLatch(1);
  private volatile IBinder binder;
  private boolean bound;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
          binder = service;
          ready.countDown();
        }

        public void onServiceDisconnected(ComponentName name) {
          binder = null;
          ready.countDown();
        }

        public void onNullBinding(ComponentName name) {
          ready.countDown();
        }
      };

  public RemoteConnectionClient(Context context) {
    this.context = context.getApplicationContext();
  }

  public void connect() throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IOException("REMOTE_BIND_WORKER_REQUIRED");
    synchronized (this) {
      if (!bound)
        bound =
            context.bindService(
                new Intent(context, RemoteConnectionService.class),
                connection,
                Context.BIND_AUTO_CREATE);
      if (!bound) throw new IOException("REMOTE_BIND_UNAVAILABLE");
    }
    try {
      if (!ready.await(5, TimeUnit.SECONDS) || binder == null)
        throw new IOException("REMOTE_BIND_UNAVAILABLE");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("REMOTE_BIND_INTERRUPTED");
    }
  }

  public RemoteSessionIdentity current() throws IOException {
    Parcel request = Parcel.obtain(), response = Parcel.obtain();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      transact(TRANSACTION_CURRENT, request, response);
      String json = response.readString();
      return json == null ? null : RemoteSessionIdentity.fromJsonString(json);
    } catch (RuntimeException invalid) {
      throw new IOException("REMOTE_BIND_FAILED");
    } finally {
      request.recycle();
      response.recycle();
    }
  }

  /** The owner process resolves preferences; never trust another process's cached preferences. */
  public String uiLanguage() throws IOException {
    Parcel request = Parcel.obtain(), response = Parcel.obtain();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      transact(TRANSACTION_UI_LANGUAGE, request, response);
      String language = response.readString();
      if (!com.deepseekharness.app.util.UiLanguagePreference.isSupportedLanguage(language)
          || response.dataAvail() != 0) throw new IOException("REMOTE_UI_LANGUAGE_INVALID");
      return language;
    } catch (RuntimeException invalid) {
      throw new IOException("REMOTE_BIND_FAILED");
    } finally {
      request.recycle();
      response.recycle();
    }
  }

  public ParcelFileDescriptor openHttp(RemoteSessionIdentity expected) throws IOException {
    if (expected == null) throw new IOException("REMOTE_SESSION_STALE");
    Parcel request = Parcel.obtain(), response = Parcel.obtain();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      request.writeString(expected.toJsonString());
      transact(TRANSACTION_OPEN_HTTP, request, response);
      ParcelFileDescriptor result = response.readTypedObject(ParcelFileDescriptor.CREATOR);
      if (result == null) throw new IOException("REMOTE_STREAM_UNAVAILABLE");
      return result;
    } catch (RuntimeException invalid) {
      throw new IOException("REMOTE_BIND_FAILED");
    } finally {
      request.recycle();
      response.recycle();
    }
  }

  private void transact(int code, Parcel request, Parcel response) throws IOException {
    IBinder active = binder;
    if (active == null) throw new IOException("REMOTE_BIND_DISCONNECTED");
    try {
      if (!active.transact(code, request, response, 0))
        throw new IOException("REMOTE_BIND_UNSUPPORTED");
      response.readException();
    } catch (RemoteException failure) {
      throw new IOException("REMOTE_BIND_DISCONNECTED");
    }
  }

  @Override
  public synchronized void close() {
    binder = null;
    if (bound) {
      bound = false;
      context.unbindService(connection);
    }
  }
}
