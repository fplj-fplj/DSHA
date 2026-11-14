package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.util.UiText;

/** Root 提醒独立于全局免确认；后台拦截只记待提示状态，回到应用后再展示。 */
public final class RootShellDialogs {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static java.lang.ref.WeakReference<AlertDialog> visible =
      new java.lang.ref.WeakReference<>(null);

  private RootShellDialogs() {}

  public static String guide() {
    return UiText.choose(
        "Root 拥有系统最高权限。DSHA 仅接受可识别的单条命令，限制始终生效；阅读此说明不会解除限制。\n\n常用查询\nid / whoami：查看身份；id 显示 uid=0 才表示 Android Root。\nls / cat / head / tail：列出目录、读取文件或首尾内容；Root 可能读到其他应用的数据。\ngetprop / getenforce：读取系统属性、SELinux 状态。\nps / df / du：查看进程、磁盘空间、目录占用。\nsettings get / list、pm list packages：读取设置、列出应用。\n\n受限操作\ncp / mv / rm：复制、移动、删除文件；仅限受控共享目录和 /data/local/tmp 子目录，拒绝系统目录、照片目录和软链接越界。删除不能自动撤销。\nkill / am force-stop：停止应用；仅接受核验过的用户应用，保护系统应用和 DSHA。\ncontent query：短信查询需要单独开启短信授权，只允许当前 Android 用户。\n\n禁止操作\ndd / mkfs / flash：写分区、格式化、刷机，可能清空数据或导致无法开机。\nmount / setenforce / setprop、settings put：修改挂载、安全状态或系统设置。\npm install / uninstall / clear：安装、卸载、清除应用数据。\nsh / su / app_process、管道、重定向和未知命令：无法按单条命令核验，通用 Root 入口拒绝执行。\n\n这些限制只覆盖 DSHA 的受管 Root 入口，不能约束其他应用或自行运行的代码。",
        "Root has the highest system privileges. DSHA accepts only recognized single commands. Reading this guide does not remove the restrictions.\n\nCommon queries\nid / whoami: show the execution identity; uid=0 means Android Root.\nls / cat / head / tail: list directories or read files; Root may access other apps' data.\ngetprop / getenforce: read system properties and SELinux status.\nps / df / du: inspect processes, disk space and directory usage.\nsettings get / list, pm list packages: read settings and list apps.\n\nRestricted operations\ncp / mv / rm: copy, move or delete files within controlled shared directories and children of /data/local/tmp. System and photo directories and escaping symlinks are refused. Deletion cannot be automatically undone.\nkill / am force-stop: stop verified user apps; system apps and DSHA are protected.\ncontent query: SMS requires separate authorization and is limited to the current Android user.\n\nBlocked operations\ndd / mkfs / flash: partition writes, formatting or flashing may erase data or prevent booting.\nmount / setenforce / setprop, settings put: change mounts, security state or system settings.\npm install / uninstall / clear: install, uninstall or erase app data.\nsh / su / app_process, pipes, redirects and unknown commands: cannot be verified as a single command and are refused by the generic Root entry.\n\nThese restrictions cover DSHA's managed Root entries, not other apps or independently executed code.");
  }

  public static AlertDialog explain(Context context, Runnable enable) {
    // 不使用 safetyConfirmation=true：该标志在用户授权版会自动点击肯定按钮。
    DshaDialogBuilder builder = new DshaDialogBuilder(context);
    builder.setTitle(UiText.choose("Root 命令限制与说明", "Root command restrictions and guide"));
    builder.setMessage(guide());
    builder.setPositiveButton(
        enable == null ? UiText.text("知道了") : UiText.choose("了解限制并开启", "Enable with restrictions"),
        (dialog, which) -> {
          if (enable != null) enable.run();
        });
    if (enable != null) builder.setNegativeButton(UiText.text("取消"), null);
    return builder.show();
  }

  public static void blocked(Context context) {
    // 不记录原始命令、输出或参数，合并重复提醒，避免后台弹窗和敏感内容落盘。
    Context app = context.getApplicationContext();
    app.getSharedPreferences("root_command_notice", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("pending", true)
        .apply();
    MAIN.post(() -> showPending(app));
  }

  public static void showPending(Context context) {
    FragmentActivity host = ForegroundActivity.current();
    AlertDialog current = visible.get();
    if (!ForegroundActivity.isResumed(host) || current != null && current.isShowing()) return;
    var prefs = context.getSharedPreferences("root_command_notice", Context.MODE_PRIVATE);
    if (!prefs.getBoolean("pending", false)) return;
    AlertDialog dialog =
        new DshaDialogBuilder(host)
            .setTitle(UiText.choose("Root 命令已拦截", "Root command blocked"))
            .setMessage(
                String.format(
                    "%s\n\n%s",
                    UiText.choose(
                        "命令未通过 Root 检查，已停止执行。请查看命令返回的具体原因；关闭此提示不会执行被拦截的命令。",
                        "The command failed the Root checks and was stopped. Its response contains the reason. Dismissing this notice does not execute it."),
                    guide()))
            .setPositiveButton(
                UiText.text("知道了"), (d, which) -> prefs.edit().putBoolean("pending", false).apply())
            .create();
    dialog.setOnCancelListener(d -> prefs.edit().putBoolean("pending", false).apply());
    visible = new java.lang.ref.WeakReference<>(dialog);
    dialog.show();
  }
}
