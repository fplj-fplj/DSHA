package com.deepseekharness.app;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 所有调用使用替身，不在宿主或设备执行被测命令。 */
public final class DeviceShellExecutorTest {
  @Test
  public void genericExecutionSendsOriginalCommandOnceAndPreservesResult() {
    for (String command :
        List.of(
            "kill 42 43",
            "rm -rf /system/demo",
            "echo a | cat",
            "content query --uri content://sms")) {
      List<List<String>> sent = new ArrayList<>();
      String output =
          DeviceShellExecutor.execute(
              command,
              argv -> {
                sent.add(argv);
                return "Android denied this operation\n[EXIT=7]";
              });
      assertEquals(List.of(List.of("sh", "-c", command)), sent);
      assertEquals("Android denied this operation\n[EXIT=7]", output);
    }
  }

  @Test
  public void invalidCommandDoesNotInvokeTheDeviceRunner() {
    String output =
        DeviceShellExecutor.execute(
            "id\0echo",
            argv -> {
              fail("Invalid argument reached the device");
              return "";
            });
    assertTrue(output.startsWith("[INVALID_ARGUMENT]"));
    assertTrue(output.endsWith("[EXIT=2]"));
  }
}
