package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationIdsTest {
  @Test
  public void everyPurposeHasADistinctStablePositiveIdentifier() {
    assertEquals(10, NotificationIds.all().size());
    assertEquals(9062, NotificationIds.FILE_DOWNLOAD);
    assertTrue(NotificationIds.all().stream().allMatch(id -> id > 0));
    assertEquals(NotificationIds.SHELL_CONFIRM, Constants.NOTIF_SHELL_CONFIRM);
  }
}
