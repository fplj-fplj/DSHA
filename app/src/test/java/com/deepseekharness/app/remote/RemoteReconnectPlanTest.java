package com.deepseekharness.app.remote;

import static org.junit.Assert.*;
import org.junit.Test;

/** Deterministic retry budget: no sockets, jobs, clocks or sleeps. */
public final class RemoteReconnectPlanTest {
  @Test
  public void exponentialBackoffIsBoundedAndStopsWithinTwoMinutes() {
    RemoteReconnectPlan plan = new RemoteReconnectPlan();
    long now = 100;
    for (long expected : new long[] {1000, 2000, 4000, 8000, 15000, 30000, 30000}) {
      assertEquals(expected, plan.nextDelay(now));
      now += expected;
    }
    assertEquals(-1, plan.nextDelay(now));
  }

  @Test
  public void healthyConnectionResetsRetryBudgetButClockRollbackCannotExtendIt() {
    RemoteReconnectPlan plan = new RemoteReconnectPlan();
    assertEquals(1000, plan.nextDelay(10000));
    assertEquals(-1, plan.nextDelay(9999));
    plan.connected();
    assertEquals(1000, plan.nextDelay(500000));
    assertEquals(2000, plan.nextDelay(501000));
  }
}
