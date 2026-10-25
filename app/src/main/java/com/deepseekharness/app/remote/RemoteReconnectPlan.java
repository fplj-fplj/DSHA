package com.deepseekharness.app.remote;

/** Transport-only retry budget within an existing user-started service; no business requests. */
final class RemoteReconnectPlan {
  private static final long[] DELAYS = {1000, 2000, 4000, 8000, 15000, 30000};
  private long startedAt = -1;
  private int retries;

  long nextDelay(long nowMillis) {
    if (startedAt < 0) startedAt = nowMillis;
    long delay = DELAYS[Math.min(retries, DELAYS.length - 1)];
    if (nowMillis < startedAt || nowMillis - startedAt + delay >= 120000) return -1;
    retries++;
    return delay;
  }

  void connected() {
    startedAt = -1;
    retries = 0;
  }
}
