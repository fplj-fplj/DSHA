package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.net.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class LanPortPolicyTest {
  @Test
  public void currentPreferenceLeavesTheLegacyOnlyPortAvailable() {
    assertEquals(3081, LanPortPolicy.preferred("com.dsh.client"));
    assertEquals(3082, LanPortPolicy.preferred("dsh.android.client"));
  }

  @Test
  public void occupiedPreferenceFallsBackToAHeldKernelPort() throws Exception {
    try (ServerSocket owner = new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))) {
      int preferred = owner.getLocalPort();
      try (ServerSocket actual = LanPortPolicy.bind(preferred)) {
        assertNotEquals(preferred, actual.getLocalPort());
        assertTrue(actual.isBound());
        assertEquals("0.0.0.0", actual.getInetAddress().getHostAddress());
        assertThrows(
            BridgePortPolicy.Exhausted.class,
            () -> BridgePortPolicy.bind(new int[] {actual.getLocalPort()}));
        assertFalse(owner.isClosed());
      }
    }
  }

  @Test
  public void twoContendersBindSeparateListenersWithoutAProbeRace() throws Exception {
    try (ServerSocket reservation = new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))) {
      int preferred = reservation.getLocalPort();
      reservation.close();
      ExecutorService workers = Executors.newFixedThreadPool(2);
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      Callable<ServerSocket> bind =
          () -> {
            ready.countDown();
            go.await();
            return LanPortPolicy.bind(preferred);
          };
      Future<ServerSocket> first = workers.submit(bind), second = workers.submit(bind);
      assertTrue(ready.await(3, TimeUnit.SECONDS));
      go.countDown();
      try (ServerSocket a = first.get(3, TimeUnit.SECONDS);
          ServerSocket b = second.get(3, TimeUnit.SECONDS)) {
        assertNotEquals(a.getLocalPort(), b.getLocalPort());
        assertTrue(a.getLocalPort() == preferred || b.getLocalPort() == preferred);
      } finally {
        workers.shutdownNow();
      }
    }
  }
}
