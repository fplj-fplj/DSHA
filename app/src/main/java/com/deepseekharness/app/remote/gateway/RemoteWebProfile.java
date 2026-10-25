package com.deepseekharness.app.remote.gateway;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Must run from Application.attachBaseContext, before any provider/WebView initialization. */
public final class RemoteWebProfile {
  private static volatile String loadedProfile;

  private RemoteWebProfile() {}

  public static boolean isRemoteProcess() {
    String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : null;
    return process != null && process.endsWith(":remote_web");
  }

  public static boolean initializeProcess(Context context) {
    if (!isRemoteProcess()) return false;
    if (loadedProfile != null) return true;
    synchronized (RemoteWebProfile.class) {
      if (loadedProfile != null) return true;
      String profile = "unselected";
      try (InputStream input = new FileInputStream(selection(context))) {
        byte[] bytes = new byte[65];
        int count = input.read(bytes);
        if (count == 64 && input.read() == -1) {
          String value = new String(bytes, 0, count, StandardCharsets.US_ASCII);
          if (value.matches("[0-9a-f]{64}")) profile = value;
        }
      } catch (IOException ignored) {
        /* Fail closed: activity rejects unselected. */
      }
      WebView.setDataDirectorySuffix("remote_pc_" + profile);
      loadedProfile = profile;
    }
    return true;
  }

  static String loaded() {
    if (loadedProfile == null)
      throw new IllegalStateException("REMOTE_WEB_PROFILE_NOT_BOOTSTRAPPED");
    return loadedProfile;
  }

  public static String forPeer(String peerId) {
    if (peerId == null || peerId.isBlank() || peerId.length() > 512)
      throw new IllegalArgumentException("peerId");
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(peerId.getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder();
      for (byte b : hash) value.append(String.format(Locale.ROOT, "%02x", b & 255));
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static synchronized void select(Context context, String peerId) throws IOException {
    String profile = forPeer(peerId);
    android.util.AtomicFile file = new android.util.AtomicFile(selection(context));
    FileOutputStream output = file.startWrite();
    try {
      output.write(profile.getBytes(StandardCharsets.US_ASCII));
      file.finishWrite(output);
    } catch (IOException failure) {
      file.failWrite(output);
      throw failure;
    }
  }

  private static File selection(Context context) {
    return new File(context.getNoBackupFilesDir(), "remote-web-profile");
  }
}
