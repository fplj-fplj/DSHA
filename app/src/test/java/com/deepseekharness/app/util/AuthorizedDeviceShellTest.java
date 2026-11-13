package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** 不执行设备命令，只核对 shell 收到的精确参数。 */
public final class AuthorizedDeviceShellTest {
  @Test
  public void shellSyntaxAndUnicodeReachTheDeviceUnchanged() {
    String command = "printf '%s' '测试' | cat > /sdcard/Pictures/result; echo $(id)";
    assertEquals(List.of("sh", "-c", command), AuthorizedDeviceShell.argv(command));
  }

  @Test
  public void previouslyBlockedOperationsHaveExecutablePlans() {
    for (String command :
        List.of(
            "settings put global demo 1",
            "pm install /sdcard/app.apk",
            "kill 42 43",
            "rm -rf /system/demo",
            "setprop debug.demo 1",
            "unknown-command",
            "content query --uri content://sms"))
      assertEquals(List.of("sh", "-c", command), AuthorizedDeviceShell.argv(command));
  }

  @Test
  public void invalidProcessArgumentsFailBeforeSending() {
    for (String command : new String[] {null, "", " \n\t", "id\0echo"}) {
      try {
        AuthorizedDeviceShell.argv(command);
        fail("Expected an invalid shell argument");
      } catch (IllegalArgumentException expected) {
        assertEquals("INVALID_SHELL_COMMAND", expected.getMessage());
      }
    }
  }
}
