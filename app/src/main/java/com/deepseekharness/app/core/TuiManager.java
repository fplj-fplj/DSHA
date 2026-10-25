package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DSH-TUI（终端前端插件）的受管安装与状态检测。
 *
 * <p>TUI 运行在 guest 的独立 {@code dsh-tui} profile：它的 bundle patch 会禁用
 * tool-bash 等宿主行（这是它作为「终端门面」的正确形态），因此绝不能装进 Web
 * 的 {@code web} profile，否则 Web 端 agent 的基础工具会被禁用。
 *
 * <p>安装走 guest 里官方 {@code dsh plugin --profile dsh-tui add} 入口：该命令在
 * 真实 DSHA guest（Node 24 / pnpm 10.34.5 / dsh 0.2.1-rc.2+）实测可完整安装
 * @deepseek-harness-tui/dsh-tui 0.17.0（80 依赖、约 20 秒），无需宿主另造安装器。
 *
 * <p>guest 命令统一经 {@code /bin/bash -lc} 包装：登录 shell 会加载
 * /etc/profile.d/dsha-runtime-env.sh，把受管 /root/dsh-bin（dsh/pnpm/npm）挂进
 * PATH；proot 的非登录执行环境不含该路径，裸调 dsh 会找不到命令。
 *
 * <p>状态只做只读检测：读 profile 的 package.json 判断 TUI 包与版本，不解析
 * node_modules 内容、不缓存跨代次结果。
 */
public final class TuiManager {

  /** npm 包名（scoped），与上游发布名一致。 */
  public static final String PACKAGE = "@deepseek-harness-tui/dsh-tui";

  /** 当前固定版本：发布前按 npm registry 重新核对，不追 latest。 */
  public static final String VERSION = "0.17.0";

  /** guest 内的独立 profile 目录。 */
  public static final String PROFILE_DIR = "/root/.dsh/profiles/dsh-tui";

  /** 安装超时：首次下载约 30MB 依赖，给足 10 分钟。 */
  private static final long INSTALL_TIMEOUT_MS = 600_000;

  /** TUI 安装状态；unknown 表示尚未检测或检测失败，不等于未安装。 */
  public enum State {
    UNKNOWN,
    NOT_INSTALLED,
    INSTALLING,
    READY,
    FAILED
  }

  public static final class Status {
    public final State state;
    public final String version;
    public final String message;

    Status(State state, String version, String message) {
      this.state = state;
      this.version = version;
      this.message = message;
    }

    public static Status unknown() {
      return new Status(State.UNKNOWN, "", "");
    }
  }

  public interface Listener {
    void onTuiStatus(Status status);
  }

  private static final AtomicReference<Status> LAST = new AtomicReference<>(Status.unknown());

  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "dsha-tui");
            t.setDaemon(true);
            return t;
          });

  private static volatile TuiManager shared;

  private TuiManager() {}

  public static TuiManager shared() {
    TuiManager local = shared;
    if (local == null) {
      synchronized (TuiManager.class) {
        local = shared;
        if (local == null) shared = local = new TuiManager();
      }
    }
    return local;
  }

  public Status lastKnown() {
    return LAST.get();
  }

  /**
   * 只读检测 TUI 是否已安装在 dsh-tui profile。同步执行 guest 命令，调用方须在
   * 后台线程调用；结果不落盘（LAST 仅用于 UI 粗显示）。
   */
  public Status detect(ProotBootstrap proot) {
    if (!proot.isEnvironmentReady()) return Status.unknown();
    try {
      String probe =
          "/bin/bash -lc '"
              + "python3 -c \"import json;"
              + "p=json.load(open('"
              + PROFILE_DIR
              + "/package.json'));"
              + "v=p.get('dependencies',{}).get('"
              + PACKAGE
              + "');"
              + "print(v if isinstance(v,str) else 'ABSENT')\""
              + "' 2>&1";
      String out = proot.execChecked(probe).trim();
      if ("ABSENT".equals(out) || out.isEmpty()) {
        return publish(new Status(State.NOT_INSTALLED, "", ""));
      }
      return publish(new Status(State.READY, out, ""));
    } catch (IOException | RuntimeException error) {
      // profile 目录不存在或 JSON 解析失败：按未安装路径呈现，不阻塞入口。
      return publish(new Status(State.NOT_INSTALLED, "", ""));
    }
  }

  /**
   * 受管安装：在后台线程执行官方 profile 安装命令。结果经 listener 回调；
   * INSTALLING 期间不重复提交。失败保留现状（半装 profile 由 dsh plugin 事务
   * 自行维护），不做破坏性清理或自动重试。
   */
  public void install(Context context, ProotBootstrap proot, Listener listener) {
    Status current = LAST.get();
    if (current.state == State.INSTALLING) {
      listener.onTuiStatus(current);
      return;
    }
    publish(new Status(State.INSTALLING, current.version, ""));
    io.execute(
        () -> {
          try {
            if (!proot.isEnvironmentReady())
              throw new IOException(UiText.text("运行环境未就绪，先完成安装页初始化"));
            proot.requireUserRuntime();
            // 登录 shell 拿到受管 PATH 后再执行官方安装入口；超时覆盖首启下载。
            String command =
                "/bin/bash -lc 'dsh plugin --profile dsh-tui add "
                    + PACKAGE
                    + "@"
                    + VERSION
                    + " 2>&1'";
            var result = proot.runPersonalMaintenance(command, null, false, INSTALL_TIMEOUT_MS);
            if (result.timedOut) throw new IOException(UiText.text("TUI 安装超时，可稍后在插件页重试"));
            Status done = detect(proot);
            if (done.state != State.READY) throw new IOException(tail(result.output, 400));
            listener.onTuiStatus(done);
          } catch (IOException | RuntimeException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            String safe = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
            listener.onTuiStatus(publish(new Status(State.FAILED, "", tail(safe, 400))));
          }
        });
  }

  /**
   * 构造 PTY 中启动 TUI 的 guest 命令（argv 形式，无用户输入拼接）。
   * bash -l 加载 dsha-runtime-env.sh 后 exec dsh，环境与终端页普通 PTY 一致。
   */
  public static String[] launchArgv() {
    return new String[] {"/bin/bash", "-lc", "exec dsh --profile dsh-tui"};
  }

  private static String tail(String value, int max) {
    if (value == null) return "";
    String flat = value.replace("\n", " ").trim();
    return flat.length() > max ? flat.substring(flat.length() - max) : flat;
  }

  private Status publish(Status status) {
    LAST.set(status);
    return status;
  }
}
