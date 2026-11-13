package com.deepseekharness.app;

import com.deepseekharness.app.util.DeviceAppPolicy;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** 用户授权的设备 shell；通用入口不再套用历史命令、路径和应用白名单。 */
public final class DeviceShellExecutor {
  private DeviceShellExecutor() {}

  interface Runner {
    String run(List<String> argv);
  }

  static String execute(String command, Runner runner) {
    return execute(command, runner, null);
  }

  static String execute(
      String command, Runner runner, com.deepseekharness.app.util.SelfPackageIdentity identity) {
    try {
      return runner.run(com.deepseekharness.app.util.AuthorizedDeviceShell.argv(command));
    } catch (IllegalArgumentException error) {
      return "[INVALID_ARGUMENT] " + SensitiveData.redact(error.getMessage()) + "\n[EXIT=2]";
    }
  }

  /** Privileged typed entry used only by VirtualScreenManager, never by generic shell text. */
  static String executeVirtualScreen(String command, Runner runner) {
    return executeVirtualScreen(command, runner, null);
  }

  static String executeVirtualScreen(
      String command, Runner runner, com.deepseekharness.app.util.SelfPackageIdentity identity) {
    int uid = android.os.Process.myUid();
    if (uid != 0 && uid != 2000)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 虚拟屏启动仅允许特权设备通道\n[EXIT=126]");
    try {
      if (identity == null) throw new IOException("SELF_PACKAGE_UNVERIFIED");
      DeviceShellPolicy.Plan plan =
          DeviceShellPolicy.inspectManagedVirtualScreen(command, identity.source, identity.name);
      if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
      return runner.run(plan.argv);
    } catch (IOException | IllegalArgumentException error) {
      return "[POLICY_BLOCKED] " + SensitiveData.redact(error.getMessage()) + "\n[EXIT=126]";
    }
  }

  /** Recheck SMS-provider file reads at the privileged execution boundary. */
  static void validateRootReadPaths(DeviceShellPolicy.Plan plan) throws IOException {
    boolean recursive = DeviceShellPolicy.rootReadMayDescend(plan);
    for (String path : DeviceShellPolicy.rootReadPaths(plan)) {
      if (isSmsReadPath(path, recursive)) throw new IOException("短信数据库仅允许经原生授权的当前用户 content query");
      final String canonical;
      try {
        canonical = new File(path).getCanonicalPath();
      } catch (IOException unreadable) {
        throw new IOException("无法核验 Root 读取路径", unreadable);
      }
      if (isSmsReadPath(canonical, recursive))
        throw new IOException("短信数据库仅允许经原生授权的当前用户 content query");
    }
  }

  private static boolean isSmsReadPath(String path, boolean recursive) {
    return DeviceShellPolicy.smsProviderPath(path)
        || recursive && DeviceShellPolicy.smsProviderDescendant(path);
  }

  private static String checked(String output) throws IOException {
    if (output == null || !output.endsWith("[EXIT=0]") || output.contains("[OUTPUT_TRUNCATED]"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法取得完整设备信息，未执行写入或停止操作"));
    return output.substring(0, output.length() - "[EXIT=0]".length()).trim();
  }
}
