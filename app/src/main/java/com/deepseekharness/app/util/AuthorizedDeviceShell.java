package com.deepseekharness.app.util;

import java.util.List;

/** 用户授权版设备 shell：保留命令原文，由 Android shell 按实际通道权限执行。 */
public final class AuthorizedDeviceShell {
  private AuthorizedDeviceShell() {}

  public static List<String> argv(String command) {
    if (command == null || command.trim().isEmpty() || command.indexOf('\0') >= 0)
      throw new IllegalArgumentException("INVALID_SHELL_COMMAND");
    return List.of("sh", "-c", command);
  }
}
