package com.deepseekharness.app.remote.gateway;

/** Explicit browser classification. A loopback gateway does not grant LOCAL Android authority. */
public enum RemoteWebOrigin {
  REMOTE;

  public boolean allowsLocalNativeBridge() {
    return false;
  }

  public boolean requiresLocalUbuntu() {
    return false;
  }
}
