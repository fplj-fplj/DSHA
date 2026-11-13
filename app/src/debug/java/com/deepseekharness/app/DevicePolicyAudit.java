package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 调试专用：隔离配置和 token，宿主通过 USB 转发测试真实原生计划与设备文件预检。 */
public final class DevicePolicyAudit extends Instrumentation {
  private int checks;
  private Bundle arguments;

  private void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    checks++;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args == null ? new Bundle() : args;
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    HttpShellService bridge = null;
    Fixture fixture = null;
    try {
      check(BuildConfig.DEBUG, "仅限调试包");
      List<List<String>> sent = new ArrayList<>();
      DeviceShellExecutor.Runner spy =
          argv -> {
            sent.add(new ArrayList<>(argv));
            return "[EXIT=0]";
          };
      for (String command :
          new String[] {"rm -rf /", "settings put global x 1", "kill 42 43", "sh -c id"}) {
        sent.clear();
        check(DeviceShellExecutor.execute(command, spy).endsWith("[EXIT=0]"), "授权命令计划：" + command);
        check(sent.equals(List.of(List.of("sh", "-c", command))), "原文仅交给测试替身一次");
      }
      check(!HttpShellService.isReady() && !HttpShellService.isStarting(), "不能占用现有设备桥");
      fixture =
          new Fixture(
              getTargetContext(), arguments.getString("fixtureId", UUID.randomUUID().toString()));
      fixture
          .getSharedPreferences(Constants.PREFS, 0)
          .edit()
          .putBoolean(Constants.KEY_CONFIRM_SHELL, false)
          .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true)
          .commit();
      File token = new File(fixture.base, "token");
      Compat.write(token, "audit_" + UUID.randomUUID().toString().replace("-", ""));
      bridge = new HttpShellService(fixture, token);
      bridge.start();
      long end = SystemClock.elapsedRealtime() + 5000;
      while (!HttpShellService.isReady() && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
      check(HttpShellService.isReady(), "测试桥就绪");
      Compat.write(new File(fixture.base, "ready"), "ready");
      Bundle ready = new Bundle();
      ready.putString("state", "READY");
      ready.putString("fixture", fixture.base.getName());
      sendStatus(0, ready);
      end = SystemClock.elapsedRealtime() + 240000;
      while (!new File(fixture.base, "done").exists() && SystemClock.elapsedRealtime() < end)
        Thread.sleep(100);
      check(new File(fixture.base, "done").exists(), "宿主完成测试");
      result.putString("result", "PASS");
    } catch (Throwable error) {
      result.putString("result", "FAIL");
      result.putString("failure", String.valueOf(error));
    } finally {
      if (bridge != null) bridge.stop();
      if (fixture != null) fixture.getSharedPreferences(Constants.PREFS, 0).edit().clear().commit();
      if (fixture != null && !"1".equals(arguments.getString("keepFixture")))
        try {
          File target = fixture.base.getCanonicalFile(),
              parent = getTargetContext().getCacheDir().getCanonicalFile();
          if (!target.getParentFile().equals(parent)
              || !target.getName().startsWith("device-policy-audit-"))
            throw new java.io.IOException("夹具清理目标越界");
          OwnedAuditFiles.remove(parent, target, "device-policy-audit-");
        } catch (Exception error) {
          result.putString("result", "FAIL");
          result.putString("failure", String.valueOf(error));
        }
    }
    result.putInt("assertions", checks);
    finish(
        "PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
        result);
  }

  private static final class Fixture extends ContextWrapper {
    final File base;

    Fixture(Context context, String id) throws Exception {
      super(context.getApplicationContext());
      if (!id.matches(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        throw new java.io.IOException("invalid fixture UUID");
      base = new File(context.getCacheDir(), "device-policy-audit-" + id);
      if (!base.mkdirs() || !getFilesDir().mkdirs()) throw new java.io.IOException("测试目录已存在或不可写");
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public File getFilesDir() {
      return new File(base, "files");
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      return super.getSharedPreferences(base.getName() + "_" + name, mode);
    }
  }
}
