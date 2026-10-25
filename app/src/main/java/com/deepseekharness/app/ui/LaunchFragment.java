package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.LanAccessPresentation;
import com.deepseekharness.app.util.LanAddressPolicy;
import com.deepseekharness.app.util.UiText;

/**
 * 启动页：启动 / 进入 / 停止 dsh Web，显示运行状态、鉴权链接与局域网访问地址。
 */
public class LaunchFragment extends Fragment {

  /** 通知点击进入时带上的参数：Web 就绪后自动打开会话。 */
  public static final String ARG_OPEN_WEB = "open_web";

  /** Main can offer computer pairing before the user installs the phone runtime. */
  public static final String ARG_PREPARE_LOCAL = "prepare_local";

  private HarnessController controller;
  private TextView lanAddrText;
  private TextView launchLog;
  private androidx.appcompat.app.AlertDialog accessDialog;

  /** 启动按钮当前是否处于「进入」态（鉴权链接已就绪）。 */
  private boolean webReady;

  /** 主线程单飞；成功打开后保持占用，直到页面重新可见。 */
  private boolean enteringWeb;

  private boolean webEntryFailure;

  private boolean preparingStart;
  private final java.util.concurrent.atomic.AtomicLong startRequest =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.Executor START_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  /** 本次启动开始时刻（显示耗时用）。 */
  private long startAtMs;

  private long logRevision = -1;
  private String logUrl = "";
  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

  /** 通知点击进来、待自动进入 Web 的意图；由既有每秒刷新循环消费，就绪即进。 */
  private boolean pendingAutoEnter;

  private final Runnable refreshState =
      new Runnable() {
        @Override
        public void run() {
          refreshRunState();
          refreshLanAddr();
          // 通知点击进来的自动进入：复用这个每秒循环等鉴权链接，不另开定时器。
          // 判据只认「鉴权链接已出现」——它就是进入 Web 的前提，也是按钮变「进入」的
          // 同一条件。不能拿 getWebAuthFailure() 判成败：那是最近一次鉴权结果消息，
          // 初始值与成功值都非 null（成功时是"鉴权成功"），用它当成功判据会永不进入。
          if (pendingAutoEnter && !webEntryUrl().isEmpty()) {
            pendingAutoEnter = false;
            enterWeb();
          }
          ui.postDelayed(this, 1000);
        }
      };

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_launch, container, false);

    ((TextView) v.findViewById(R.id.launch_workspaces))
        .setText(com.deepseekharness.app.util.UiText.choose("电脑工作区", "Computer workspaces"));
    ((TextView) v.findViewById(R.id.launch_workspaces_detail))
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "配对电脑，使用电脑上的 DSH；无需安装手机环境。",
                "Pair a computer and use its DSH. Phone runtime setup is optional."));
    v.findViewById(R.id.launch_workspaces)
        .setOnClickListener(
            x -> com.deepseekharness.app.remote.ui.WorkspaceActivity.open(requireContext()));
    if (getArguments() != null && getArguments().getBoolean(ARG_PREPARE_LOCAL, false)) {
      ((TextView) v.findViewById(R.id.launch_run_state))
          .setText(
              com.deepseekharness.app.util.UiText.choose("手机环境尚未准备", "Phone runtime not set up"));
      ((TextView) v.findViewById(R.id.launch_subtitle))
          .setText(
              com.deepseekharness.app.util.UiText.choose(
                  "在手机运行 DSH 时再准备环境，也可直接连接电脑。",
                  "Set up DSH to run on this phone, or connect a computer below."));
      ((Button) v.findViewById(R.id.launch_start))
          .setText(com.deepseekharness.app.util.UiText.choose("准备手机环境", "Set up phone runtime"));
      v.findViewById(R.id.launch_start)
          .setOnClickListener(
              x ->
                  startActivity(
                      com.deepseekharness.app.remote.ui.WorkspaceEntry.local(requireContext())));
      for (int id :
          new int[] {
            R.id.launch_status,
            R.id.launch_controls,
            R.id.launch_metrics,
            R.id.launch_shortcuts_title,
            R.id.launch_shortcuts,
            R.id.launch_log_panel
          }) v.findViewById(id).setVisibility(View.GONE);
      return v;
    }

    controller = HarnessController.get(requireContext());
    final Activity activity = requireActivity();
    TextView status = v.findViewById(R.id.launch_status);
    Button start = v.findViewById(R.id.launch_start);
    Button restart = v.findViewById(R.id.launch_open);
    Button stop = v.findViewById(R.id.launch_stop);
    lanAddrText = v.findViewById(R.id.lan_addr);
    launchLog = v.findViewById(R.id.launch_log);
    logRevision = -1;
    logUrl = "";
    v.findViewById(R.id.launch_download_logs)
        .setOnClickListener(x -> startActivity(DiagnosticActivity.downloadLogs(requireContext())));

    v.findViewById(R.id.launch_address).setOnClickListener(x -> showAccessAddresses());
    restart.setText(R.string.ui2_restart);
    v.findViewById(R.id.launch_recovery).setOnClickListener(x -> showRecovery());
    v.findViewById(R.id.launch_safe)
        .setOnClickListener(
            x ->
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
                    .setTitle(com.deepseekharness.app.util.UiText.text("安全启动 Web？"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "停止当前启动，只加载官方基础界面。原插件开关、会话、模型和配置保留；普通重启后回到原配置。安全界面不加载移动插件等扩展。"))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setPositiveButton(
                        com.deepseekharness.app.util.UiText.text("安全启动"),
                        (dialog, which) -> doStart(activity, status, start, true))
                    .show());

    // 启动按钮：未就绪时是「启动」；鉴权链接就绪后自动变为「进入」，点击进 WebUI。
    start.setOnClickListener(
        x -> {
          if (webReady || !webEntryUrl().isEmpty()) {
            enterWeb();
            return;
          }
          doStart(activity, status, start);
        });

    restart.setOnClickListener(
        x -> {
          // startWeb 本身串行执行「清旧进程 → 启动」，无需拆成两次请求。
          doStart(activity, status, start);
        });

    // 通知点击进来：置待进入标记，由每秒刷新循环在鉴权链接就绪后自动进入。
    if (getArguments() != null && getArguments().getBoolean(ARG_OPEN_WEB, false)) {
      getArguments().remove(ARG_OPEN_WEB);
      pendingAutoEnter = true;
    }

    stop.setOnClickListener(
        x -> {
          startRequest.incrementAndGet();
          preparingStart = false;
          FirstRunWebStart.cancel(requireContext());
          invalidateWebEntry();
          controller.stopWeb(
              msg -> {
                long generation = controller.getWebGeneration();
                activity.runOnUiThread(
                    () -> {
                      if (getView() != v || generation != controller.getWebGeneration()) return;
                      status.setText(com.deepseekharness.app.util.UiStateText.render(msg));
                      refreshRunState();
                      refreshLanAddr();
                      var stopped = controller.lastStopResult();
                      if (stopped != null && !stopped.stopped()
                          || !controller.lastStopError().isEmpty())
                        offerForceStop(activity, v, status, generation);
                    });
              });
          webReady = false;
          start.setText(com.deepseekharness.app.util.UiText.text("启动"));
          status.setText(com.deepseekharness.app.util.UiText.text("停止中…"));
          refreshLanAddr();
          refreshRunState();
        });

    return v;
  }

  /** 启动 dsh：记录启动时刻，鉴权链接就绪后把「启动」变「进入」并输出 URL 到日志。 */
  private void doStart(Activity activity, TextView status, Button start) {
    doStart(activity, status, start, false);
  }

  private void doStart(Activity activity, TextView status, Button start, boolean safeMode) {
    if (preparingStart || !safeMode && (controller.isStarting() || controller.isStopping())) return;
    final View root = getView();
    if (root == null || !isResumed() || activity.isFinishing() || activity.isDestroyed()) return;
    FirstRunWebStart.cancel(requireContext());
    preparingStart = true;
    long request = startRequest.incrementAndGet();
    invalidateWebEntry();
    startAtMs = System.currentTimeMillis();
    String time =
        new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(new java.util.Date());
    status.setText(com.deepseekharness.app.util.UiText.format("启动中…（%s）", time));
    start.setText(com.deepseekharness.app.util.UiText.text("启动"));
    webReady = false;
    java.util.function.Consumer<String> startStatus =
        msg -> {
          long generation = controller.getWebGeneration();
          activity.runOnUiThread(
              () -> {
                if (getView() != root || generation != controller.getWebGeneration()) return;
                status.setText(com.deepseekharness.app.util.UiStateText.render(msg));
                refreshRunState();
                refreshLanAddr();
              });
        };
    refreshRunState();
    boolean queued =
        FirstRunWebStart.enqueueVisible(
            requireActivity(),
            START_IO,
            safeMode,
            safeMode,
            startStatus,
            () -> {
              if (startRequest.get() != request || getView() != root) return;
              preparingStart = false;
              refreshRunState();
              refreshLanAddr();
            });
    if (!queued) {
      preparingStart = false;
      refreshRunState();
    }
  }

  /**
   * 外部（通知点击）请求：下一次刷新周期自动进入 Web。
   *
   * <p>页面不可见时由 {@code onPause} 清掉，避免用户回来时被意外带走。
   */
  void requestAutoEnterWeb() {
    if (controller == null) return;
    pendingAutoEnter = true;
    if (getView() != null) ui.post(refreshState);
  }

  /** 打开 WebPreviewActivity 进入 dsh WebUI。 */
  private void enterWeb() {
    final View root = getView();
    final Activity activity = getActivity();
    if (enteringWeb
        || root == null
        || activity == null
        || !isResumed()
        || activity.isFinishing()
        || activity.isDestroyed()) return;
    String url = webEntryUrl();
    if (url.isEmpty()) {
      if (getView() != null) {
        ((TextView) getView().findViewById(R.id.launch_status))
            .setText(com.deepseekharness.app.util.UiText.text("先点「启动」，等鉴权链接就绪后再进入"));
      }
      return;
    }
    final long generation = webEntryGeneration();
    enteringWeb = true;
    webEntryFailure = false;
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiText.choose("正在打开 Web…", "Opening Web…"));
    refreshRunState();
    try {
      if (getView() != root || !isResumed() || activity.isFinishing() || activity.isDestroyed()) {
        invalidateWebEntry();
        return;
      }
      if (generation != webEntryGeneration() || !url.equals(webEntryUrl())) {
        finishWebEntry(root, com.deepseekharness.app.util.UiText.text("Web 状态已变化，请等待就绪后重新进入"));
        return;
      }
      // The browser owns the token -> cookie -> HTTP 200 verification. Doing it here
      // too created two serial exchanges before the first page; no unverified cookie
      // is handed off and neither navigation nor progress reports browser readiness.
      openWebEntry(activity, url, null);
      refreshLanAddr();
    } catch (RuntimeException error) {
      finishWebEntry(
          root,
          com.deepseekharness.app.util.UiText.format(
              "无法打开 Web 页面，请重试（%s）", error.getClass().getSimpleName()));
    }
  }

  // Same-package host tests can replace only the Activity destination.
  String webEntryUrl() {
    return controller.getWebAuthUrl();
  }

  long webEntryGeneration() {
    return controller.getWebGeneration();
  }

  void openWebEntry(Activity activity, String url, String cookie) {
    startActivity(WebPreviewActivity.intent(activity, url, cookie));
  }

  private void finishWebEntry(View root, String message) {
    enteringWeb = false;
    webEntryFailure = true;
    ((TextView) root.findViewById(R.id.launch_status))
        .setText(com.deepseekharness.app.util.UiStateText.render(message));
    refreshRunState();
  }

  private void invalidateWebEntry() {
    enteringWeb = false;
  }

  /** 仅在用户仍位于底部时跟随；滚动日志本身，不请求焦点或移动操作区。 */
  private void updateLog(String text) {
    View root = getView();
    if (root == null || launchLog == null) return;
    LogScrollView scroll = root.findViewById(R.id.launch_log_scroll);
    boolean follow = scroll.shouldFollowEnd();
    launchLog.setText(text);
    if (follow) scroll.followEndAfterLayout();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (controller == null) return;
    invalidateWebEntry();
    ui.removeCallbacks(refreshState);
    ui.post(refreshState);
  }

  @Override
  public void onPause() {
    // 页面不可见时撤销自动进入：用户离开了启动页，回来不该被"突袭"打开 Web。
    pendingAutoEnter = false;
    startRequest.incrementAndGet();
    preparingStart = false;
    if (enteringWeb && getView() != null) {
      ((TextView) getView().findViewById(R.id.launch_status))
          .setText(com.deepseekharness.app.util.UiText.text("返回后可重新进入 Web"));
    }
    invalidateWebEntry();
    ui.removeCallbacks(refreshState);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    startRequest.incrementAndGet();
    preparingStart = false;
    invalidateWebEntry();
    ui.removeCallbacks(refreshState);
    if (accessDialog != null) accessDialog.dismiss();
    accessDialog = null;
    lanAddrText = null;
    launchLog = null;
    super.onDestroyView();
  }

  /** 读取共享状态，不在主线程执行 proot/kill -0；重建页面也能跟随后台启停。 */
  private void refreshRunState() {
    try {
      View root = getView();
      if (root == null) return;
      TextView runState = root.findViewById(R.id.launch_run_state);
      Button start = root.findViewById(R.id.launch_start);
      if (runState == null) return;
      boolean starting = preparingStart || controller.isStarting();
      boolean stopping = controller.isStopping();
      var stopResult = controller.lastStopResult();
      boolean unresolvedStop =
          controller.isUserStopped()
              && !starting
              && (stopResult != null && !stopResult.stopped()
                  || !controller.lastStopError().isEmpty());
      boolean ready = !starting && !stopping && !webEntryUrl().isEmpty();
      boolean restoring = controller.isRestoringWebSession();
      boolean unresolved = controller.hasUnresolvedWebSession();
      com.deepseekharness.app.util.StartupTrace.Snapshot trace =
          controller.startupDiagnostics().snapshot();
      String localUrl = webEntryUrl();
      if (launchLog != null && (trace.revision != logRevision || !localUrl.equals(logUrl))) {
        // 仅原生本机视图显示当前鉴权地址；归档与导出的诊断仍统一脱敏。
        updateLog(
            (trace.log.isEmpty() ? com.deepseekharness.app.util.UiText.text("还没有日志。") : trace.log)
                + (localUrl.isEmpty()
                    ? ""
                    : com.deepseekharness.app.util.UiText.format(
                        "\n\n本机 Web 地址（可长按复制）：\n%s", localUrl)));
        logRevision = trace.revision;
        logUrl = localUrl;
      }
      runState.setText(
          enteringWeb
              ? com.deepseekharness.app.util.UiText.choose("正在打开 Web…", "Opening Web…")
              : restoring
                  ? com.deepseekharness.app.util.UiText.choose(
                      "正在核对原 Web 实例…", "Checking the existing Web instance…")
                  : unresolved
                      ? com.deepseekharness.app.util.UiText.choose(
                          "原 Web 实例状态待确认，请查看诊断或正常停止后重试",
                          "The previous Web instance is unconfirmed. Inspect diagnostics or stop it normally before retrying.")
                      : controller.isRestartBlocked()
                          ? com.deepseekharness.app.util.UiText.text("自动重启已暂停")
                          : stopping
                              ? com.deepseekharness.app.util.UiText.text("DSH 停止中…")
                              : starting
                                  ? com.deepseekharness.app.util.UiText.text("DSH 启动中…")
                                  : ready
                                      ? (trace.safe
                                              ? com.deepseekharness.app.util.UiText.text(
                                                  "基础界面已就绪 · 安全模式")
                                              : com.deepseekharness.app.util.UiText.text(
                                                  "DSH 已就绪，可进入"))
                                          + (controller.isWebCompatibilityFallback()
                                              ? com.deepseekharness.app.util.UiText.text(
                                                  " · 已兼容切换 proot")
                                              : "")
                                      : controller.isUserStopped()
                                          ? getString(R.string.ui2_stopped)
                                          : getString(R.string.ui2_stopped));
      ((TextView) root.findViewById(R.id.launch_port))
          .setText(ready ? String.valueOf(controller.getWebPort()) : "—");
      ((TextView) root.findViewById(R.id.launch_environment))
          .setText(EnvironmentUiStatus.get(requireContext()).ready ? "READY" : "—");
      ((TextView) root.findViewById(R.id.launch_subtitle))
          .setText(
              ready
                  ? (controller.isWebCompatibilityFallback()
                          ? "proot"
                          : controller.proot().runtime().id())
                      + " · dsh "
                      + com.deepseekharness.app.util.Constants.DSH_VERSION
                  : getString(R.string.ui2_launch_hint));
      root.findViewById(R.id.launch_status)
          .setVisibility(
              enteringWeb
                      || webEntryFailure
                      || starting
                      || stopping
                      || unresolvedStop
                      || !trace.issues.isEmpty()
                  ? View.VISIBLE
                  : View.GONE);
      if (starting)
        ((TextView) root.findViewById(R.id.launch_status))
            .setText(
                com.deepseekharness.app.util.UiText.format(
                    "%s · 本阶段 %s 秒 · 总计 %s 秒\n%s",
                    trace.stage,
                    trace.stageElapsedMs / 1000,
                    trace.elapsedMs / 1000,
                    trace.issues.isEmpty()
                        ? com.deepseekharness.app.util.UiText.text("下方实时显示启动输出；等待不会自动终止。")
                        : com.deepseekharness.app.util.UiText.text("检测到插件或配置异常，可查看恢复选项。")));
      root.findViewById(R.id.launch_busy)
          .setVisibility(starting || stopping ? View.VISIBLE : View.GONE);
      Button recovery = root.findViewById(R.id.launch_recovery);
      int failures = controller.config().getWebFailures();
      recovery.setVisibility(View.VISIBLE);
      recovery.setText(com.deepseekharness.app.util.UiText.text("恢复选项"));
      recovery.setContentDescription(
          !trace.issues.isEmpty()
              ? com.deepseekharness.app.util.UiText.format(
                  "检测到 %s 项异常，查看插件与恢复选项", trace.issues.size())
              : com.deepseekharness.app.util.UiText.format(
                  "失败 %s/3，%s，查看恢复选项", failures, controller.config().getWebFailureStage()));
      if (start != null) {
        webReady = ready;
        start.setText(
            enteringWeb
                ? com.deepseekharness.app.util.UiText.text("进入中…")
                : ready
                    ? com.deepseekharness.app.util.UiText.choose("▶  进入工作台", "▶  Open workspace")
                    : getString(R.string.ui2_start));
        start.setEnabled(!enteringWeb && !starting && !stopping && !restoring);
      }
      Button restart = root.findViewById(R.id.launch_open);
      if (restart != null) restart.setEnabled(!starting && !stopping);
      Button stop = root.findViewById(R.id.launch_stop);
      if (stop != null) stop.setEnabled(!stopping);
      if (EnvironmentUiStatus.get(requireContext()).recovery) {
        runState.setText(com.deepseekharness.app.util.UiText.text("环境需要恢复"));
        if (start != null) start.setEnabled(false);
        if (restart != null) restart.setEnabled(false);
        root.findViewById(R.id.launch_safe).setEnabled(false);
        recovery.setVisibility(View.VISIBLE);
        recovery.setText(com.deepseekharness.app.util.UiText.text("环境维护"));
        recovery.setContentDescription(com.deepseekharness.app.util.UiText.text("查看环境维护与恢复选项"));
        recovery.setOnClickListener(
            v ->
                startActivity(
                    new Intent(requireContext(), ExtractActivity.class)
                        .putExtra("review_only", true)));
      } else {
        root.findViewById(R.id.launch_safe).setEnabled(!starting && !stopping);
        recovery.setOnClickListener(v -> showRecovery());
      }
    } catch (RuntimeException failure) {
      if (getContext() != null)
        com.deepseekharness.app.core.DiagnosticLog.record(
            getContext(), "UI_REFRESH_FAILED", failure.getClass().getSimpleName());
      View view = getView();
      if (view != null) {
        TextView state = view.findViewById(R.id.launch_run_state);
        if (state != null)
          state.setText(
              com.deepseekharness.app.util.UiText.choose(
                  "运行状态暂时无法读取，请查看操作记录", "Run state unavailable. See operation history."));
      }
    }
  }

  private void showRecovery() {
    startActivity(new Intent(requireContext(), StartupRecoveryActivity.class));
  }

  private void offerForceStop(Activity activity, View root, TextView status, long generation) {
    final long consentRevision =
        new com.deepseekharness.app.core.DeviceGrants(activity).consentPolicy().revision();
    // 进程与文件身份核验放在工作线程；本次候选只捕获一次，由对话框局部持有至确认。
    new Thread(
            () -> {
              HarnessController.ForceStopRequest request = null;
              try {
                request = controller.prepareForceStop();
              } catch (java.io.IOException denied) {
                com.deepseekharness.app.core.DiagnosticLog.record(
                    activity.getApplicationContext(),
                    "WEB_FORCE_STOP_CANDIDATE",
                    denied.getClass().getSimpleName());
              }
              final HarnessController.ForceStopRequest candidate = request;
              ui.post(
                  () -> {
                    if (consentRevision
                            != new com.deepseekharness.app.core.DeviceGrants(activity)
                                .consentPolicy()
                                .revision()
                        || getView() != root
                        || !isResumed()
                        || activity.isFinishing()
                        || activity.isDestroyed()
                        || generation != controller.getWebGeneration()) return;
                    if (candidate == null) {
                      status.setText(
                          com.deepseekharness.app.util.UiText.text(
                              "尚未确认 Web 退出；进程身份无法核验，保留运行屏障。请查看诊断后重试停止。"));
                      return;
                    }
                    new DshaDialogBuilder(requireContext(), true)
                        .setTitle(com.deepseekharness.app.util.UiText.text("强制结束本次 Web？"))
                        .setMessage(
                            com.deepseekharness.app.util.UiText.text(
                                "普通停止尚未确认 Web 退出。本次已核验进程属于 DSHA；强制结束可能中断正在写入的对话或文件。\n\n确认后只结束这次已核验的 Web 进程；身份或状态变化会拒绝执行。取消会保留现场与运行屏障。"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            com.deepseekharness.app.util.UiText.text("强制结束"),
                            (dialog, which) -> {
                              if (consentRevision
                                      != new com.deepseekharness.app.core.DeviceGrants(activity)
                                          .consentPolicy()
                                          .revision()
                                  || getView() != root
                                  || !isResumed()
                                  || activity.isFinishing()
                                  || activity.isDestroyed()) return;
                              boolean accepted =
                                  controller.forceStop(
                                      candidate,
                                      message ->
                                          ui.post(
                                              () -> {
                                                if (getView() != root
                                                    || generation != controller.getWebGeneration())
                                                  return;
                                                status.setText(
                                                    com.deepseekharness.app.util.UiStateText.render(
                                                        message));
                                                refreshRunState();
                                                refreshLanAddr();
                                              }));
                              if (!accepted)
                                status.setText(
                                    com.deepseekharness.app.util.UiText.text(
                                        "Web 状态或进程身份已变化，本次强制结束未执行。请重新停止后确认。"));
                              else
                                status.setText(
                                    com.deepseekharness.app.util.UiText.text("正在强制结束本次已核验的 Web…"));
                            })
                        .show();
                  });
            },
            "web-force-stop-candidate")
        .start();
  }

  /** Snapshot settings, actual listener generation and current interfaces independently of the view. */
  LanAccessPresentation lanAccess() {
    boolean enabled = controller.config().isLanMode();
    boolean permission =
        com.deepseekharness.app.bridge.LocalNetworkAccess.granted(requireContext());
    long generation = controller.getWebGeneration();
    boolean authenticated = LanProxyService.hasDshAuth(generation);
    int port = LanProxyService.port(generation);
    LanAddressPolicy.Discovery discovery =
        enabled && permission && authenticated && port > 0
            ? LanAddressPolicy.currentDiscovery()
            : null;
    return LanAccessPresentation.capture(
        enabled, permission, generation, authenticated, port, discovery);
  }

  /** LAN status remains visible when disabled; opening this view never enables a listener. */
  private void refreshLanAddr() {
    if (lanAddrText == null || !isAdded() || controller == null) return;
    LanAccessPresentation access = lanAccess();
    lanAddrText.setText(lanStatus(access));
    lanAddrText.setOnClickListener(v -> showAccessAddresses());
    lanAddrText.setVisibility(View.VISIBLE);
    renderAccessAddresses(access);
  }

  void showAccessAddresses() {
    if (!isAdded() || controller == null) return;
    if (accessDialog != null) accessDialog.dismiss();
    var dialog =
        new DshaDialogBuilder(requireContext())
            .setTitle(R.string.ui2_address)
            .setMessage("")
            .setNegativeButton(UiText.choose("关闭", "Close"), null)
            .setPositiveButton(UiText.choose("复制本机地址", "Copy local URL"), null)
            .setNeutralButton(UiText.choose("局域网访问", "LAN access"), null)
            .create();
    accessDialog = dialog;
    dialog.setOnDismissListener(
        ignored -> {
          if (accessDialog == dialog) accessDialog = null;
        });
    dialog.show();
    renderAccessAddresses(lanAccess());
  }

  private void renderAccessAddresses(LanAccessPresentation access) {
    var dialog = accessDialog;
    if (dialog == null || !dialog.isShowing() || !isAdded()) return;
    String local = webEntryUrl();
    long generation = controller.getWebGeneration();
    dialog.setMessage(
        UiText.format(
            UiText.choose("本机访问\n%s\n\n%s", "On this phone\n%s\n\n%s"),
            local.isEmpty()
                ? UiText.choose("启动 DSH 并等待就绪后显示本机地址。", "Start DSH and wait for its local URL.")
                : local,
            lanStatus(access)));
    Button localButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
    localButton.setEnabled(!local.isEmpty());
    localButton.setOnClickListener(
        ignored -> {
          if (isAdded()
              && generation == controller.getWebGeneration()
              && !local.isEmpty()
              && local.equals(webEntryUrl())) copyAddr("DSH", local);
          else connectionChanged();
        });
    Button lanButton = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
    lanButton.setText(lanActionLabel(access.state));
    lanButton.setOnClickListener(ignored -> onLanAction(lanAccess()));
  }

  static String lanStatus(LanAccessPresentation access) {
    return switch (access.state) {
      case DISABLED ->
          UiText.choose(
              "局域网访问：未开启。可在此手动开启，供同一可信网络的设备连接。",
              "LAN access: off. Enable it here to connect devices on the same trusted network.");
      case PERMISSION_REQUIRED ->
          UiText.choose(
              "局域网访问已开启，但未获得系统本地网络权限；本机访问仍可使用。",
              "LAN access is enabled, but the system local network permission is missing. Local access is still available.");
      case WAITING_AUTH ->
          UiText.choose(
              "局域网访问已开启，正在等待本轮 DSH 就绪并完成认证。",
              "LAN access is enabled. Waiting for this DSH run to be ready and authenticated.");
      case LISTENER_UNAVAILABLE ->
          UiText.choose(
              "局域网访问已开启，但本轮代理尚未成功监听。可重试局域网连接；仍失败时查看启动诊断。",
              "LAN access is enabled, but its listener is not ready. Retry LAN access or check startup diagnostics if it still fails.");
      case DISCOVERY_DENIED ->
          UiText.choose(
              "局域网代理已监听，但系统拒绝读取网络地址。请检查应用的网络权限后刷新。",
              "The LAN listener is ready, but the system denied access to network addresses. Check app network permissions and refresh.");
      case DISCOVERY_FAILED ->
          UiText.choose(
              "局域网代理已监听，但暂时无法读取网络接口。请检查 Wi-Fi 或以太网连接后刷新。",
              "The LAN listener is ready, but network interfaces could not be read. Check Wi-Fi or Ethernet and refresh.");
      case IPV6_ONLY ->
          UiText.choose(
              "局域网代理已监听，检测到 IPv6，但没有可分享的私有 IPv4 地址；此代理使用 IPv4。请连接提供私有 IPv4 地址的 Wi-Fi 或以太网。",
              "The LAN listener is ready. IPv6 was detected, but no shareable private IPv4 address is available. This proxy uses IPv4. Connect to Wi-Fi or Ethernet that provides private IPv4.");
      case NO_ADDRESS ->
          UiText.choose(
              "局域网代理已监听，但没有可分享的 Wi-Fi 或以太网私有 IPv4 地址。请连接局域网后刷新；VPN 或移动数据地址不会作为局域网链接。",
              "The LAN listener is ready, but no shareable Wi-Fi or Ethernet private IPv4 address is available. Connect to a LAN and refresh. VPN and cellular addresses are excluded.");
      case READY ->
          UiText.format(
              UiText.choose(
                  "局域网访问地址：\n%s\n使用“局域网连接链接”查看或复制带凭据的链接。同网设备能否连接还取决于网络隔离、防火墙和路由；HTTP 流量未加密，仅在可信网络使用。",
                  "LAN access addresses:\n%s\nUse LAN connection links to view or copy an authenticated link. Connectivity also depends on network isolation, firewalls and routing. HTTP traffic is unencrypted; use only trusted networks."),
              String.join("\n", access.services));
    };
  }

  private static String lanActionLabel(LanAccessPresentation.State state) {
    return switch (state) {
      case DISABLED -> UiText.choose("开启局域网访问", "Enable LAN access");
      case PERMISSION_REQUIRED -> UiText.choose("允许本地网络", "Allow local network");
      case LISTENER_UNAVAILABLE -> UiText.choose("重试局域网连接", "Retry LAN access");
      case READY -> UiText.choose("局域网连接链接", "LAN connection links");
      default -> UiText.choose("刷新地址", "Refresh addresses");
    };
  }

  private void onLanAction(LanAccessPresentation access) {
    if (!isAdded()) return;
    if (access.state == LanAccessPresentation.State.DISABLED) {
      new DshaDialogBuilder(requireContext())
          .setTitle(UiText.choose("开启局域网访问？", "Enable LAN access?"))
          .setMessage(
              UiText.choose(
                  "开启后，同一网络中的设备可凭连接链接访问本轮 DSH。链接包含访问凭据，HTTP 流量未加密；请只在可信网络开启。可在“配置”中随时关闭并撤销链接。",
                  "Devices on the same network can access this DSH run using its connection link. The link contains a credential and HTTP traffic is unencrypted. Enable only on a trusted network. Turn it off in Configuration to revoke links."))
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(
              UiText.choose("开启", "Enable"),
              (dialog, which) -> {
                if (!isAdded() || getView() == null || !isResumed()) return;
                controller.config().setLanMode(true);
                requestLanAccess();
                refreshLanAddr();
              })
          .show();
    } else if (access.state == LanAccessPresentation.State.PERMISSION_REQUIRED
        || access.state == LanAccessPresentation.State.LISTENER_UNAVAILABLE) {
      requestLanAccess();
      refreshLanAddr();
    } else if (access.state == LanAccessPresentation.State.READY) {
      if (access.services.size() == 1) showLanAddress(access.services.get(0));
      else
        new DshaDialogBuilder(requireContext())
            .setTitle(UiText.choose("选择局域网地址", "Choose a LAN address"))
            .setItems(
                access.services.toArray(new String[0]),
                (dialog, which) -> showLanAddress(access.services.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    } else refreshLanAddr();
  }

  private void requestLanAccess() {
    if (!isAdded() || !controller.config().isLanMode()) return;
    try {
      com.deepseekharness.app.HarnessService.ensureLanForeground(requireContext());
      if (getActivity() instanceof MainActivity)
        ((MainActivity) getActivity()).requestLocalNetwork();
      else
        com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(requireContext());
    } catch (RuntimeException failure) {
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "无法开启局域网连接，请检查系统权限和启动诊断后重试。",
                  "LAN access could not start. Check system permissions and startup diagnostics, then retry."),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void showLanAddress(String service) {
    if (!isAdded()) return;
    final long generation = controller.getWebGeneration();
    if (!lanAccess().stillOffers(generation, service)) {
      connectionChanged();
      return;
    }
    final String token = LanProxyService.boundLanToken(generation);
    if (token.isEmpty()) {
      connectionChanged();
      return;
    }
    final String address = service + "?token=" + token;
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.choose("局域网连接链接", "LAN connection link"))
        .setMessage(
            UiText.format(
                UiText.choose(
                    "此链接包含访问凭据。局域网使用 HTTP，流量未加密，请只在可信网络使用并只分享给可信设备。DSH 重启或关闭局域网访问后，旧链接会失效，请重新复制。\n\n%s",
                    "This link contains an access credential. LAN traffic uses unencrypted HTTP. Use a trusted network and share only with trusted devices. Restarting DSH or disabling LAN access revokes this link; copy a new one afterward.\n\n%s"),
                address))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            UiText.choose("复制连接链接", "Copy connection link"),
            (dialog, which) -> {
              if (!isAdded()
                  || generation != controller.getWebGeneration()
                  || !lanAccess().stillOffers(generation, service)
                  || !token.equals(LanProxyService.boundLanToken(generation))) {
                connectionChanged();
                return;
              }
              copyAddr(UiText.choose("局域网地址", "LAN address"), address);
            })
        .show();
  }

  private void connectionChanged() {
    if (isAdded())
      Toast.makeText(
              requireContext(),
              UiText.choose(
                  "连接已变化，请重新查看链接。", "The connection changed. View its current link again."),
              Toast.LENGTH_LONG)
          .show();
  }

  private void copyAddr(String label, String addr) {
    try {
      android.content.ClipboardManager cm =
          (android.content.ClipboardManager)
              requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(com.deepseekharness.app.bridge.SensitiveClipboard.text(label, addr));
        Toast.makeText(
                requireContext(),
                com.deepseekharness.app.util.UiText.choose(
                    "已复制连接地址，请只分享给可信设备",
                    "Connection address copied. Share only with trusted devices"),
                Toast.LENGTH_SHORT)
            .show();
      }
    } catch (Throwable t) {
      Toast.makeText(
              requireContext(),
              com.deepseekharness.app.util.UiText.format("复制失败：%s", t.getMessage()),
              Toast.LENGTH_SHORT)
          .show();
    }
  }
}
