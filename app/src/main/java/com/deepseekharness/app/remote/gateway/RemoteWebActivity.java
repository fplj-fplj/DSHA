package com.deepseekharness.app.remote.gateway;

import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import com.deepseekharness.app.remote.RemoteConnectionClient;
import com.deepseekharness.app.remote.RemoteSessionIdentity;
import com.deepseekharness.app.util.UiText;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

/** Browser for a PC-owned frontend. Intentionally independent of every local browser/runtime class. */
public final class RemoteWebActivity extends ComponentActivity {
  public static final String SESSION = "remote_session_identity";
  private static final int CHOOSE_FILE = 5101, SAVE_DOWNLOAD = 5102;
  private final ExecutorService io = Executors.newFixedThreadPool(2);
  private volatile RemoteConnectionClient client;
  private volatile RemoteHttpGateway gateway;
  private volatile boolean destroyed;
  private WebView browser;
  private TextView status;
  private RemoteBlobSink blobSink;
  private long sessionGeneration;
  private volatile RemoteSessionIdentity sessionIdentity;
  private ValueCallback<Uri[]> fileChoice;
  private String pendingDownload;
  private String pendingName, pendingMime;
  private RemoteHttpDownloads downloads;
  private RemoteHttpDownloads.Job publicationJob;
  private final java.util.concurrent.atomic.AtomicBoolean downloadBusy =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean reloading =
      new java.util.concurrent.atomic.AtomicBoolean();

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    com.deepseekharness.app.ui.LanguageController.applyRemote(this, null);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (browser != null && browser.canGoBack()) browser.goBack();
                else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                }
              }
            });
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                      | androidx.core.view.WindowInsetsCompat.Type.ime());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Button back = new Button(this);
    back.setText(UiText.choose("返回", "Back"));
    back.setOnClickListener(v -> finish());
    bar.addView(back);
    TextView label = new TextView(this);
    label.setText(UiText.choose("远程 · 电脑工作区", "REMOTE · PC workspace"));
    label.setTextSize(18);
    bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Button reload = new Button(this);
    reload.setText(UiText.choose("重新加载", "Reload"));
    reload.setOnClickListener(v -> reloadSession(root));
    bar.addView(reload);
    Button records = new Button(this);
    records.setText(UiText.choose("下载记录", "Downloads"));
    records.setOnClickListener(v -> showDownloads());
    bar.addView(records);
    root.addView(bar);
    status = new TextView(this);
    status.setPadding(16, 8, 16, 8);
    status.setText(UiText.choose("正在连接所选电脑…", "Connecting to the selected PC…"));
    root.addView(status);
    setContentView(root);
    if (!RemoteWebProfile.isRemoteProcess()) {
      status.setText(UiText.choose("远程浏览器隔离不可用", "Remote browser isolation unavailable"));
      return;
    }
    final RemoteSessionIdentity session;
    try {
      session = RemoteSessionIdentity.fromJsonString(getIntent().getStringExtra(SESSION));
      if (!RemoteWebProfile.loaded().equals(RemoteWebProfile.forPeer(session.peerId)))
        throw new IOException("REMOTE_PROFILE_MISMATCH");
      sessionIdentity = session;
      sessionGeneration = session.generation;
    } catch (Exception invalid) {
      status.setText(
          UiText.choose("请重新选择电脑以打开其独立浏览器。", "Select the PC again to open its isolated browser."));
      return;
    }
    io.execute(
        () -> {
          try {
            RemoteConnectionClient connection = new RemoteConnectionClient(getApplicationContext());
            client = connection;
            connection.connect();
            String ownerLanguage = connection.uiLanguage();
            downloads =
                new RemoteHttpDownloads(
                    new File(getNoBackupFilesDir(), "remote-downloads").toPath());
            RemoteHttpGateway server =
                new RemoteHttpGateway(
                    () -> channel(connection.openHttp(session)), session.generation);
            if (destroyed) {
              server.close();
              connection.close();
              return;
            }
            gateway = server;
            runOnUiThread(
                () -> {
                  if (destroyed) return;
                  String previousLanguage = UiText.language();
                  String currentLanguage =
                      com.deepseekharness.app.ui.LanguageController.applyRemote(
                          this, ownerLanguage);
                  if (!previousLanguage.equals(currentLanguage)) {
                    recreate();
                    return;
                  }
                  createBrowser(root, server);
                });
          } catch (Exception unavailable) {
            runOnUiThread(
                () -> {
                  if (!destroyed)
                    status.setText(
                        UiText.choose(
                            "电脑连接不可用，请从工作区列表重新连接。",
                            "PC connection unavailable. Reconnect from the workspace list."));
                });
          }
        });
  }

  private static RemoteHttpGateway.Channel channel(ParcelFileDescriptor descriptor)
      throws IOException {
    ParcelFileDescriptor read = null, write = null;
    try {
      read = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
      write = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
      InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(read);
      OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(write);
      return new RemoteHttpGateway.Channel() {
        @Override
        public InputStream input() {
          return input;
        }

        @Override
        public OutputStream output() {
          return output;
        }

        @Override
        public void close() throws IOException {
          try {
            input.close();
          } finally {
            output.close();
          }
        }
      };
    } catch (IOException failure) {
      if (read != null) read.close();
      if (write != null) write.close();
      throw failure;
    } finally {
      descriptor.close();
    }
  }

  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private void createBrowser(LinearLayout root, RemoteHttpGateway server) {
    browser = new WebView(this);
    browser.setTag(RemoteWebOrigin.REMOTE);
    WebSettings settings = browser.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setGeolocationEnabled(false);
    settings.setSupportMultipleWindows(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setUserAgentString(settings.getUserAgentString() + " DSHA-REMOTE/1");
    CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
    browser.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !server.permits(request.getUrl().toString());
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !server.permits(url);
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (server.permits(url)) return null;
            if (!request.isForMainFrame()
                && (url.startsWith("blob:" + server.origin() + "/")
                    || url.startsWith("data:image/")
                    || url.startsWith("data:font/"))) return null;
            return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Remote origin blocked",
                java.util.Map.of(),
                new ByteArrayInputStream(new byte[0]));
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            if (blobSink != null) blobSink.bindDocument();
            status.setText(
                UiText.choose(
                    "远程 · 电脑网页 · 此处不提供本机 Android 工具",
                    "REMOTE · PC frontend · local Android tools are unavailable here"));
          }

          @Override
          public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (blobSink != null) blobSink.navigation();
          }

          @Override
          public void onReceivedError(
              WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame())
              status.setText(
                  UiText.choose(
                      "远程网页不可用，请检查电脑连接。", "Remote page unavailable. Check the PC connection."));
          }

          @Override
          public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (blobSink != null) {
              blobSink.close();
              blobSink = null;
            }
            root.removeView(view);
            view.destroy();
            browser = null;
            status.setText(
                UiText.choose(
                    "远程浏览器已关闭，请从工作区列表重新打开。",
                    "Remote browser closed. Reopen it from the workspace list."));
            return true;
          }
        });
    browser.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(PermissionRequest request) {
            request.deny();
          }

          @Override
          public void onGeolocationPermissionsShowPrompt(
              String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
          }

          @Override
          public boolean onShowFileChooser(
              WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileChoice != null) fileChoice.onReceiveValue(null);
            fileChoice = callback;
            Intent chooser =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            chooser.putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try {
              startActivityForResult(chooser, CHOOSE_FILE);
            } catch (RuntimeException unavailable) {
              fileChoice = null;
              callback.onReceiveValue(null);
            }
            return true;
          }
        });
    blobSink =
        new RemoteBlobSink(
            this,
            browser,
            server,
            sessionGeneration,
            () -> {
              try {
                return client != null && sessionIdentity.equals(client.current());
              } catch (IOException disconnected) {
                return false;
              }
            });
    if (!blobSink.attach())
      status.setText(
          UiText.choose(
              "保存远程文件需要更新 Android WebView。",
              "Remote file saving requires an updated Android WebView."));
    browser.setDownloadListener(
        (url, userAgent, disposition, mimeType, contentLength) -> {
          if (!server.permits(url)
              || pendingDownload != null
              || !downloadBusy.compareAndSet(false, true)) return;
          pendingDownload = url;
          String name =
              URLUtil.guessFileName(url, disposition, mimeType)
                  .replaceAll("[\\\\/\\p{Cntrl}]", "_");
          if (name.length() > 200) name = name.substring(name.length() - 200);
          pendingName = name;
          pendingMime = mimeType == null ? "application/octet-stream" : mimeType;
          Intent save =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType(mimeType == null ? "application/octet-stream" : mimeType)
                  .putExtra(Intent.EXTRA_TITLE, name);
          try {
            startActivityForResult(save, SAVE_DOWNLOAD);
          } catch (RuntimeException unavailable) {
            pendingDownload = null;
            downloadBusy.set(false);
            status.setText(UiText.choose("文件选择器不可用", "Document picker unavailable"));
          }
        });
    root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
    browser.loadUrl(server.entryUrl());
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == RemoteBlobSink.PICKER && blobSink != null) {
      blobSink.pickerResult(resultCode, data);
      return;
    }
    if (requestCode == CHOOSE_FILE && fileChoice != null) {
      ValueCallback<Uri[]> callback = fileChoice;
      fileChoice = null;
      if (resultCode != RESULT_OK || data == null) {
        callback.onReceiveValue(null);
        return;
      }
      java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
      if (data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          uris.add(data.getClipData().getItemAt(i).getUri());
      else if (data.getData() != null) uris.add(data.getData());
      // Only picker-granted content URIs, never file:/ or a path supplied by the remote page.
      uris.removeIf(uri -> !"content".equals(uri.getScheme()));
      callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
    }
    if (requestCode == SAVE_DOWNLOAD) {
      String url = pendingDownload, name = pendingName, mime = pendingMime;
      RemoteHttpDownloads.Job existing = publicationJob;
      publicationJob = null;
      pendingDownload = null;
      if (resultCode != RESULT_OK || data == null || data.getData() == null) {
        downloadBusy.set(false);
        return;
      }
      Uri destination = data.getData();
      if (!"content".equals(destination.getScheme())) {
        downloadBusy.set(false);
        return;
      }
      try {
        int granted = data.getFlags();
        if ((granted & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
          if ((granted & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            getContentResolver()
                .takePersistableUriPermission(
                    destination,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
          else
            getContentResolver()
                .takePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } else if ((granted & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
          getContentResolver()
              .takePersistableUriPermission(destination, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (RuntimeException notPersistent) {
        /* A completed private copy remains available for a new picker. */
      }
      if (existing != null) {
        io.execute(() -> publish(existing, destination));
        return;
      }
      if (url == null) {
        downloadBusy.set(false);
        return;
      }
      status.setText(
          UiText.choose(
              "正在下载远程文件；若中断，可在下载记录中保留已接收部分。",
              "Downloading remote file. Partial bytes are kept in Downloads if interrupted."));
      io.execute(
          () -> {
            try {
              java.net.URI uri = java.net.URI.create(url);
              String path =
                  uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
              RemoteHttpDownloads.Job job =
                  downloads.create(
                      sessionIdentity.peerId,
                      sessionIdentity.hostInstanceId,
                      path,
                      name,
                      mime,
                      destination.toString());
              download(job);
            } catch (Exception failed) {
              downloadBusy.set(false);
              runOnUiThread(
                  () -> {
                    if (!destroyed)
                      status.setText(
                          UiText.choose(
                              "无法创建远程下载，请从电脑网页重试。",
                              "Unable to create remote download. Retry from the PC page."));
                  });
            }
          });
    }
  }

  private void download(RemoteHttpDownloads.Job job) {
    RemoteHttpGateway server = gateway;
    RemoteSessionIdentity expected = sessionIdentity;
    try {
      if (destroyed || server == null || !expected.equals(client.current()))
        throw new IOException("REMOTE_DOWNLOAD_SESSION_EXPIRED");
      downloads.run(
          job,
          expected.peerId,
          expected.hostInstanceId,
          (peer, host, path, headers) -> {
            if (!expected.equals(client.current()) || server != gateway)
              throw new IOException("REMOTE_DOWNLOAD_SESSION_EXPIRED");
            HttpURLConnection connection =
                (HttpURLConnection) new URL(server.origin() + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Cookie", server.nativeCookie());
            for (java.util.Map.Entry<String, String> header : headers.entrySet())
              connection.setRequestProperty(header.getKey(), header.getValue());
            final int code;
            try {
              code = connection.getResponseCode();
            } catch (IOException failure) {
              connection.disconnect();
              throw failure;
            }
            return new RemoteHttpDownloads.Source() {
              public int status() {
                return code;
              }

              public String header(String name) {
                return connection.getHeaderField(name);
              }

              public InputStream input() throws IOException {
                return connection.getInputStream();
              }

              public void close() {
                connection.disconnect();
              }
            };
          },
          () -> destroyed || gateway != server || !expected.equals(sessionIdentity));
      if (job.destination.startsWith("content://")) publish(job, Uri.parse(job.destination));
      else
        runOnUiThread(
            () -> {
              if (!destroyed)
                status.setText(
                    UiText.choose(
                        "远程下载已完成，请从下载记录中保存。", "Remote download complete. Save it from Downloads."));
            });
    } catch (Exception failure) {
      runOnUiThread(
          () -> {
            if (!destroyed)
              status.setText(
                  UiText.choose(
                      "下载已暂停或来源已变更，请打开下载记录查看、续传或明确重新开始。",
                      "Download paused or source changed. Open Downloads to review, resume or explicitly restart."));
          });
    } finally {
      downloadBusy.set(false);
    }
  }

  private void publish(RemoteHttpDownloads.Job job, Uri destination) {
    try {
      downloads.verify(job);
      try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
        if (output == null) throw new IOException("REMOTE_DOWNLOAD_DESTINATION");
        downloads.publish(job, output);
      }
      runOnUiThread(
          () -> {
            if (!destroyed) status.setText(UiText.choose("远程下载已保存", "Remote download saved"));
          });
    } catch (Exception failure) {
      runOnUiThread(
          () -> {
            if (!destroyed)
              status.setText(
                  UiText.choose(
                      "已保留完整的应用私有副本；请在下载记录中选择保存，重新指定目标位置。",
                      "Completed private copy retained. Choose Save from Downloads to select a destination again."));
          });
    } finally {
      downloadBusy.set(false);
    }
  }

  private void showDownloads() {
    if (downloads == null) return;
    io.execute(
        () -> {
          try {
            java.util.List<RemoteHttpDownloads.Job> jobs = downloads.list(sessionIdentity.peerId);
            String[] labels =
                jobs.stream()
                    .map(
                        j ->
                            j.name
                                + " · "
                                + android.text.format.Formatter.formatFileSize(this, j.bytes)
                                + " · "
                                + downloadState(j.state))
                    .toArray(String[]::new);
            runOnUiThread(
                () -> {
                  if (!destroyed)
                    new android.app.AlertDialog.Builder(this)
                        .setTitle(
                            UiText.choose("远程 HTTP 下载 · 当前电脑", "Remote HTTP downloads · this PC"))
                        .setItems(labels, (dialog, index) -> reviewDownload(jobs.get(index)))
                        .setNegativeButton(UiText.choose("关闭", "Close"), null)
                        .show();
                });
          } catch (IOException unavailable) {
            runOnUiThread(
                () -> {
                  if (!destroyed)
                    status.setText(
                        UiText.choose("远程下载记录不可用", "Remote download records unavailable"));
                });
          }
        });
  }

  private void reviewDownload(RemoteHttpDownloads.Job job) {
    if (job.state == RemoteHttpDownloads.State.COMPLETE) {
      if (!downloadBusy.compareAndSet(false, true)) return;
      publicationJob = job;
      try {
        startActivityForResult(
            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(job.mime)
                .putExtra(Intent.EXTRA_TITLE, job.name),
            SAVE_DOWNLOAD);
      } catch (RuntimeException unavailable) {
        publicationJob = null;
        downloadBusy.set(false);
      }
      return;
    }
    android.app.AlertDialog.Builder review =
        new android.app.AlertDialog.Builder(this)
            .setTitle(job.name)
            .setMessage(
                UiText.choose(
                    "仅当电脑可核验文件未变更时才能续传。重新开始会创建新副本并保留这份部分文件。网页内部生成的文件在网页关闭后无法续传。",
                    "Resume is available only when the PC can verify the same unchanged file. Restart begins a new copy and keeps this partial. Files generated inside a webpage cannot resume after that page closes."))
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setNeutralButton(
                UiText.choose("重新创建下载", "Restart as new"),
                (dialog, which) -> {
                  if (!downloadBusy.compareAndSet(false, true)) return;
                  io.execute(
                      () -> {
                        try {
                          download(downloads.restart(job, sessionIdentity.hostInstanceId));
                        } catch (IOException failed) {
                          downloadBusy.set(false);
                        }
                      });
                });
    if (job.canResume() && job.hostInstance.equals(sessionIdentity.hostInstanceId))
      review.setPositiveButton(
          UiText.choose("继续下载", "Resume"),
          (dialog, which) -> {
            if (downloadBusy.compareAndSet(false, true)) io.execute(() -> download(job));
          });
    review.show();
  }

  private static String downloadState(RemoteHttpDownloads.State state) {
    switch (state) {
      case COMPLETE:
        return UiText.choose("已下载", "Downloaded");
      case PAUSED:
        return UiText.choose("已暂停", "Paused");
      case SOURCE_CHANGED:
        return UiText.choose("来源已变更 · 需要重新开始", "Source changed · restart needed");
      case UNKNOWN_PARTIAL:
        return UiText.choose("部分文件需要检查", "Partial needs review");
      case NO_RESUME:
        return UiText.choose("无法安全续传 · 需要重新开始", "Safe resume unavailable · restart needed");
      default:
        return UiText.choose("尚未开始", "Not started");
    }
  }

  private void reloadSession(LinearLayout root) {
    if (client == null || !reloading.compareAndSet(false, true)) return;
    io.execute(
        () -> {
          try {
            RemoteSessionIdentity fresh = client.current(), previous = sessionIdentity;
            if (fresh == null
                || !fresh.peerId.equals(previous.peerId)
                || !fresh.sessionId.equals(previous.sessionId))
              throw new IOException("REMOTE_SESSION_CHANGED");
            RemoteHttpGateway replacement =
                new RemoteHttpGateway(() -> channel(client.openHttp(fresh)), fresh.generation);
            runOnUiThread(
                () -> {
                  if (destroyed) {
                    replacement.close();
                    return;
                  }
                  if (blobSink != null) {
                    blobSink.close();
                    blobSink = null;
                  }
                  if (browser != null) {
                    root.removeView(browser);
                    browser.stopLoading();
                    browser.destroy();
                    browser = null;
                  }
                  if (gateway != null) gateway.close();
                  gateway = replacement;
                  sessionIdentity = fresh;
                  sessionGeneration = fresh.generation;
                  createBrowser(root, replacement);
                  status.setText(
                      UiText.choose(
                          "远程连接已恢复，请在下载记录中手动继续 HTTP 文件下载。",
                          "Remote connection restored. Resume HTTP files explicitly in Downloads."));
                });
          } catch (Exception unavailable) {
            runOnUiThread(
                () -> {
                  if (!destroyed)
                    status.setText(
                        UiText.choose(
                            "电脑已断开，请重新连接同一工作区后再重新加载。",
                            "PC is disconnected. Reconnect the same workspace, then Reload."));
                });
          } finally {
            reloading.set(false);
          }
        });
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    if (blobSink != null) {
      blobSink.close();
      blobSink = null;
    }
    if (fileChoice != null) {
      fileChoice.onReceiveValue(null);
      fileChoice = null;
    }
    if (browser != null) {
      browser.stopLoading();
      browser.destroy();
      browser = null;
    }
    RemoteHttpGateway server = gateway;
    if (server != null) server.close();
    RemoteConnectionClient connection = client;
    if (connection != null) connection.close();
    // Closing the gateway unblocks network reads. Let the worker fsync its exact partial/hash;
    // interrupting a FileChannel here would destroy the final resumable checkpoint.
    io.shutdown();
    super.onDestroy();
  }
}
