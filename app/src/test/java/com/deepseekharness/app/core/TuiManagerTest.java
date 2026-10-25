package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * TuiManager 纯逻辑契约：包名/版本/profile 目录常量与 guest 启动 argv 形态。
 * guest 命令执行路径依赖容器环境，不在单测中执行。
 */
public class TuiManagerTest {

  @Test
  public void packageIdentityIsFixed() {
    assertEquals("@deepseek-harness-tui/dsh-tui", TuiManager.PACKAGE);
    assertFalse(TuiManager.PACKAGE.isEmpty());
    assertEquals("0.17.0", TuiManager.VERSION);
  }

  @Test
  public void profileDirectoryIsDedicated() {
    // TUI 绝不进 web profile：固定独立 dsh-tui profile 目录。
    assertTrue(TuiManager.PROFILE_DIR.endsWith("/profiles/dsh-tui"));
    assertFalse(TuiManager.PROFILE_DIR.contains("web"));
  }

  @Test
  public void launchArgvIsExecFormWithoutUserConcatenation() {
    String[] argv = TuiManager.launchArgv();
    assertEquals(3, argv.length);
    assertEquals("/bin/bash", argv[0]);
    assertEquals("-lc", argv[1]);
    // 固定字面量，不含用户输入拼接；dsh 走受管 PATH 包装。
    assertEquals("exec dsh --profile dsh-tui", argv[2]);
  }

  @Test
  public void initialStatusIsUnknownNotUninstalled() {
    // UNKNOWN 表示未检测，不得与 NOT_INSTALLED 混淆。
    assertEquals(TuiManager.State.UNKNOWN, TuiManager.Status.unknown().state);
    assertNotEquals(TuiManager.State.UNKNOWN, TuiManager.State.NOT_INSTALLED);
  }
}
