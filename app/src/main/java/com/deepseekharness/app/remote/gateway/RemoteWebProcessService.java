package com.deepseekharness.app.remote.gateway;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** Same-UID process lifecycle handshake. Never returns connection/native bridge credentials. */
public final class RemoteWebProcessService extends Service {
  static final String DESCRIPTOR = "com.deepseekharness.app.remote.IRemoteWebProcess";
  static final int PROFILE = IBinder.FIRST_CALL_TRANSACTION;
  static final int RETIRE = IBinder.FIRST_CALL_TRANSACTION + 1;
  private final Binder binder =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
          if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
          }
          if (Binder.getCallingUid() != android.os.Process.myUid())
            throw new SecurityException("REMOTE_WEB_UID");
          data.enforceInterface(DESCRIPTOR);
          if (code == PROFILE) {
            reply.writeNoException();
            reply.writeString(RemoteWebProfile.loaded());
            return true;
          }
          if (code == RETIRE) {
            // This is the dedicated remote browser process, never the app/guest/transport process.
            reply.writeNoException();
            new Handler(Looper.getMainLooper())
                .postDelayed(
                    () -> {
                      if (RemoteWebProfile.isRemoteProcess())
                        android.os.Process.killProcess(android.os.Process.myPid());
                    },
                    100);
            return true;
          }
          return super.onTransact(code, data, reply, flags);
        }
      };

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }
}
