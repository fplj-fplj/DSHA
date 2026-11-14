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

  @Test
  public void rootRejectsDangerousCommandsAndShellBypassesBeforeAnyExecution() {
    for (String command :
        List.of(
            "dd if=/dev/zero of=/dev/block/sda",
            "mkfs.ext4 /dev/block/sda",
            "setenforce 0",
            "setprop debug.demo 1",
            "mount -o remount,rw /system",
            "settings put global demo 1",
            "pm clear example.app",
            "rm -rf /system/demo",
            "sh -c id",
            "su -c id",
            "toybox dd if=/dev/zero of=/dev/block/sda",
            "id; reboot",
            "echo $(reboot)",
            "echo x > /system/demo",
            "cat /dev/null | sh",
            "app_process /system/bin example.Main",
            "unknown-command")) {
      String output =
          DeviceShellExecutor.executeRoot(
              command,
              argv -> {
                fail("Root command reached the runner: " + command);
                return "";
              },
              null);
      assertTrue(command, output.contains("[POLICY_BLOCKED]"));
      assertTrue(command, output.endsWith("[EXIT=126]"));
    }
  }

  @Test
  public void rootReadUsesParsedArgumentsAndReturnsFailureWithoutReplay() {
    List<List<String>> sent = new ArrayList<>();
    String output =
        DeviceShellExecutor.executeRoot(
            "/system/bin/getprop ro.product.model",
            argv -> {
              sent.add(argv);
              return "permission denied\n[EXIT=7]";
            },
            null);
    assertEquals(List.of(List.of("getprop", "ro.product.model")), sent);
    assertEquals("permission denied\n[EXIT=7]", output);
  }

  @Test
  public void rootSmsCannotUseTheUnapprovedGenericEntry() {
    for (String command :
        List.of(
            "content query --uri content://sms",
            "cat /data/user/0/com.android.providers.telephony/databases/mmssms.db")) {
      String output =
          DeviceShellExecutor.executeRoot(
              command,
              argv -> {
                fail("SMS reached the generic runner");
                return "";
              },
              null);
      assertTrue(output.contains("[POLICY_BLOCKED]"));
    }
  }

  @Test
  public void rootMixedSystemAndUserTargetsStopNeitherApp() throws Exception {
    var identity =
        com.deepseekharness.app.util.SelfPackageIdentity.launch(
            "dsh.android.client", 12346, "/data/app/dsha/base.apk", "/data/app/dsha/base.apk");
    List<List<String>> sent = new ArrayList<>();
    String output =
        DeviceShellExecutor.executeRoot(
            "kill 42 43",
            argv -> {
              sent.add(argv);
              if (argv.equals(List.of("pm", "list", "packages", "-U", "-3")))
                return "package:example.app uid:12345\npackage:dsh.android.client uid:12346\n[EXIT=0]";
              if (argv.equals(List.of("pm", "list", "packages", "-U", "-s")))
                return "package:android uid:1000\n[EXIT=0]";
              if (argv.equals(List.of("ps", "-A", "-o", "PID,UID,NAME")))
                return "PID UID NAME\n42 12345 example.app\n43 1000 system_server\n[EXIT=0]";
              fail("A mixed batch must not stop an app: " + argv);
              return "";
            },
            identity);
    assertTrue(output.contains("[POLICY_BLOCKED]"));
    assertEquals(3, sent.size());
  }
}
