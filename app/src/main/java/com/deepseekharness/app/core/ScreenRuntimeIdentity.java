package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.DshaApp;

/** Adds an unrepeatable process identity to the existing controller's monotonic Web generation. */
public final class ScreenRuntimeIdentity {
  private static final String PROCESS = java.util.UUID.randomUUID().toString();

  private ScreenRuntimeIdentity() {}

  public static RuntimeSnapshot.Epoch epoch(Context context) {
    long generation = HarnessController.get(context).getWebGeneration();
    return new RuntimeSnapshot.Epoch(
        DshaApp.from(context).installationId(),
        generation,
        generation > 0 ? PROCESS + ":" + generation : "");
  }

  /** A native authenticated device connection has its own generation; never manufacture Web state. */
  public static RuntimeSnapshot.Epoch remoteDevice(
      Context context, long generation, String connectionEpoch) {
    if (generation <= 0 || connectionEpoch == null || connectionEpoch.isBlank())
      throw new IllegalArgumentException("REMOTE_SCREEN_IDENTITY_INVALID");
    return new RuntimeSnapshot.Epoch(
        DshaApp.from(context).installationId(),
        generation,
        PROCESS + ":REMOTE_DEVICE:" + connectionEpoch);
  }
}
