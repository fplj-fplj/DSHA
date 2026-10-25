package com.deepseekharness.app.remote.gateway;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Remote-only, exact-document byte sink. Never invokes the LOCAL WebFileActions policy/wrapper. */
final class RemoteBlobSink implements AutoCloseable {
  static final int PICKER = 5103;
  private final Activity activity;
  private final WebView view;
  private final RemoteHttpGateway gateway;
  private final BooleanSupplier currentSession;
  private final long generation;
  private final ExecutorService writer = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean queued = new AtomicBoolean();
  private ScriptHandler documentScript;
  private volatile String nonce = UUID.randomUUID().toString();
  private volatile boolean closed;
  private Transfer active;
  private Transfer pendingPicker;
  private boolean attached;

  private static final class Transfer {
    String id, name, mime, destination, document;
    long expected, last = SystemClock.elapsedRealtime();
    OutputStream output;
    RemoteFileBytes receiver;
    File shareFile;
    Uri pendingGallery;
    String startId;
    JavaScriptReplyProxy startReply;
  }

  RemoteBlobSink(
      Activity activity,
      WebView view,
      RemoteHttpGateway gateway,
      long generation,
      BooleanSupplier currentSession) {
    this.activity = activity;
    this.view = view;
    this.gateway = gateway;
    this.generation = generation;
    this.currentSession = currentSession;
  }

  boolean attach() {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false;
    WebViewCompat.addWebMessageListener(
        view,
        "DshaRemoteFiles",
        Collections.singleton(gateway.origin()),
        (source, message, origin, mainFrame, reply) -> {
          if (closed
              || !mainFrame
              || !gateway.origin().equals(origin.toString())
              || !gateway.permits(source.getUrl())) return;
          String json = message.getData();
          if (json == null || json.length() > 40_000) return;
          String document = nonce;
          if (!queued.compareAndSet(false, true)) {
            answer(reply, "", false, "REMOTE_FILE_BUSY");
            return;
          }
          enqueue(
              () -> {
                String id = "";
                try {
                  JSONObject request = new JSONObject(json);
                  id = request.getString("id");
                  if (!id.matches("[0-9a-f]{32}")) throw new IOException("REMOTE_FILE_ID");
                  JSONObject binding = request.getJSONObject("binding");
                  if (closed
                      || !document.equals(nonce)
                      || !document.equals(binding.getString("nonce"))
                      || binding.getLong("generation") != generation
                      || !currentSession.getAsBoolean())
                    throw new IOException("REMOTE_FILE_SESSION_EXPIRED");
                  accept(request, id, document, reply);
                } catch (Exception failure) {
                  failActive();
                  answer(reply, id, false, "REMOTE_FILE_REJECTED");
                } finally {
                  queued.set(false);
                }
              });
        });
    attached = true;
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
      documentScript =
          WebViewCompat.addDocumentStartJavaScript(
              view, RemoteFileScript.SCRIPT, Collections.singleton(gateway.origin()));
    return true;
  }

  void navigation() {
    nonce = UUID.randomUUID().toString();
    enqueue(this::failActive);
  }

  void bindDocument() {
    if (closed || !gateway.permits(view.getUrl())) return;
    try {
      JSONObject binding = new JSONObject().put("nonce", nonce).put("generation", generation);
      view.evaluateJavascript(
          RemoteFileScript.SCRIPT + "\nwindow.__dshaRemoteFilesBind?.(" + binding + ");", null);
    } catch (Exception ignored) {
    }
  }

  private void accept(JSONObject request, String id, String document, JavaScriptReplyProxy reply)
      throws Exception {
    String action = request.getString("action"), transferId = request.getString("transferId");
    if (!transferId.matches("[0-9a-f]{32}")) throw new IOException("REMOTE_FILE_TRANSFER_ID");
    if (action.equals("start")) {
      if (active != null || pendingPicker != null) throw new IOException("REMOTE_FILE_BUSY");
      Transfer transfer = new Transfer();
      transfer.id = transferId;
      transfer.document = document;
      transfer.name = safeName(request.getString("name"));
      transfer.mime = request.getString("mime");
      if (!transfer.mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
          || transfer.mime.length() > 160) throw new IOException("REMOTE_FILE_MIME");
      transfer.expected = request.getLong("bytes");
      transfer.destination = request.getString("destination");
      if (!List.of("save", "share", "gallery").contains(transfer.destination)
          || transfer.expected < 0
          || transfer.expected
              > (transfer.destination.equals("share") ? 32L * 1024 * 1024 : 5_000_000_000L)
          || transfer.destination.equals("gallery") && !transfer.mime.startsWith("image/"))
        throw new IOException("REMOTE_FILE_LIMIT");
      transfer.startReply = reply;
      transfer.startId = id;
      active = transfer;
      if (transfer.destination.equals("share")) {
        File parent = new File(activity.getCacheDir(), "web-shares/" + UUID.randomUUID());
        if (!parent.mkdirs()) throw new IOException("REMOTE_SHARE_STAGE");
        transfer.shareFile = new File(parent, transfer.name);
        transfer.output = new FileOutputStream(transfer.shareFile);
        transfer.receiver = new RemoteFileBytes(transfer.output, transfer.expected);
        answer(reply, id, true, "ready");
        watch(transfer);
      } else if (transfer.destination.equals("gallery") && Build.VERSION.SDK_INT >= 29) {
        main.post(
            () -> {
              if (closed || !document.equals(nonce)) return;
              new android.app.AlertDialog.Builder(activity)
                  .setTitle(
                      com.deepseekharness.app.util.UiText.choose(
                          "将远程图片保存到相册？", "Save remote image to gallery?"))
                  .setMessage(
                      transfer.name
                          + " · "
                          + android.text.format.Formatter.formatFileSize(
                              activity, transfer.expected))
                  .setPositiveButton(
                      com.deepseekharness.app.util.UiText.choose("保存", "Save"),
                      (dialog, which) ->
                          enqueue(
                              () -> {
                                try {
                                  if (closed
                                      || active != transfer
                                      || !document.equals(nonce)
                                      || !currentSession.getAsBoolean())
                                    throw new IOException("REMOTE_FILE_EXPIRED");
                                  android.content.ContentValues values =
                                      new android.content.ContentValues();
                                  values.put(
                                      android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                                      transfer.name);
                                  values.put(
                                      android.provider.MediaStore.MediaColumns.MIME_TYPE,
                                      transfer.mime);
                                  values.put(
                                      android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                      "Pictures/DSHA Remote");
                                  values.put(
                                      android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                                  transfer.pendingGallery =
                                      activity
                                          .getContentResolver()
                                          .insert(
                                              android.provider.MediaStore.Images.Media
                                                  .EXTERNAL_CONTENT_URI,
                                              values);
                                  if (transfer.pendingGallery == null)
                                    throw new IOException("REMOTE_IMAGE_DESTINATION");
                                  transfer.output =
                                      activity
                                          .getContentResolver()
                                          .openOutputStream(transfer.pendingGallery, "w");
                                  if (transfer.output == null)
                                    throw new IOException("REMOTE_IMAGE_DESTINATION");
                                  transfer.receiver =
                                      new RemoteFileBytes(transfer.output, transfer.expected);
                                  answer(reply, id, true, "ready");
                                  watch(transfer);
                                } catch (Exception failure) {
                                  failActive();
                                  answer(reply, id, false, "REMOTE_IMAGE_SAVE_FAILED");
                                }
                              }))
                  .setNegativeButton(
                      com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
                      (dialog, which) ->
                          enqueue(
                              () -> {
                                failActive();
                                answer(reply, id, false, "AbortError");
                              }))
                  .setOnCancelListener(
                      dialog ->
                          enqueue(
                              () -> {
                                failActive();
                                answer(reply, id, false, "AbortError");
                              }))
                  .show();
            });
      } else {
        pendingPicker = transfer;
        main.post(
            () -> {
              if (closed || !document.equals(nonce)) return;
              try {
                activity.startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(transfer.mime)
                        .putExtra(Intent.EXTRA_TITLE, transfer.name),
                    PICKER);
              } catch (RuntimeException unavailable) {
                enqueue(
                    () -> {
                      pendingPicker = null;
                      failActive();
                      answer(reply, id, false, "REMOTE_PICKER_UNAVAILABLE");
                    });
              }
            });
      }
      return;
    }
    Transfer transfer = active;
    if (transfer == null
        || !transfer.id.equals(transferId)
        || !transfer.document.equals(document)
        || transfer.output == null) throw new IOException("REMOTE_FILE_NOT_READY");
    transfer.last = SystemClock.elapsedRealtime();
    if (action.equals("chunk")) {
      transfer.receiver.chunk(request.getInt("sequence"), request.getString("data"));
      answer(reply, id, true, "ack");
    } else if (action.equals("done")) {
      transfer.receiver.finish(request.getLong("bytes"));
      transfer.output = null;
      if (transfer.pendingGallery != null) {
        android.content.ContentValues published = new android.content.ContentValues();
        published.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
        if (activity.getContentResolver().update(transfer.pendingGallery, published, null, null)
            != 1) throw new IOException("REMOTE_IMAGE_PUBLISH_FAILED");
        transfer.pendingGallery = null;
      }
      active = null;
      if (transfer.destination.equals("share"))
        main.post(
            () -> {
              if (closed
                  || !document.equals(nonce)
                  || !currentSession.getAsBoolean()
                  || !activity.hasWindowFocus()
                  || activity.isFinishing()
                  || activity.isDestroyed()) {
                deleteOwnStage(transfer);
                answer(reply, id, false, "REMOTE_FILE_SESSION_EXPIRED");
                return;
              }
              try {
                Uri uri =
                    androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".updates",
                        transfer.shareFile,
                        transfer.name);
                activity.startActivity(
                    com.deepseekharness.app.ui.NativeFileShare.chooser(
                        uri, transfer.name, transfer.mime, transfer.name));
                answer(reply, id, true, "chooser-opened");
              } catch (RuntimeException unavailable) {
                deleteOwnStage(transfer);
                answer(reply, id, false, "REMOTE_SHARE_UNAVAILABLE");
              }
            });
      else answer(reply, id, true, "saved");
    } else throw new IOException("REMOTE_FILE_ACTION");
  }

  void pickerResult(int result, Intent data) {
    enqueue(
        () -> {
          Transfer transfer = pendingPicker;
          pendingPicker = null;
          if (transfer == null) return;
          try {
            if (active != transfer) {
              answer(transfer.startReply, transfer.startId, false, "REMOTE_FILE_EXPIRED");
              return;
            }
            if (closed || !transfer.document.equals(nonce) || !currentSession.getAsBoolean())
              throw new IOException("REMOTE_FILE_EXPIRED");
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
              failActive();
              answer(transfer.startReply, transfer.startId, false, "AbortError");
              return;
            }
            Uri uri = data.getData();
            if (!"content".equals(uri.getScheme()))
              throw new IOException("REMOTE_FILE_DESTINATION");
            transfer.output = activity.getContentResolver().openOutputStream(uri, "w");
            if (transfer.output == null) throw new IOException("REMOTE_FILE_DESTINATION");
            transfer.receiver = new RemoteFileBytes(transfer.output, transfer.expected);
            transfer.last = SystemClock.elapsedRealtime();
            answer(transfer.startReply, transfer.startId, true, "ready");
            watch(transfer);
          } catch (Exception failed) {
            failActive();
            answer(transfer.startReply, transfer.startId, false, "REMOTE_FILE_REJECTED");
          }
        });
  }

  private void watch(Transfer transfer) {
    main.postDelayed(
        () -> {
          if (closed) return;
          enqueue(
              () -> {
                if (active != transfer) return;
                if (!transfer.document.equals(nonce)
                    || !currentSession.getAsBoolean()
                    || SystemClock.elapsedRealtime() - transfer.last > 60_000) failActive();
                else watch(transfer);
              });
        },
        5000);
  }

  private void answer(JavaScriptReplyProxy reply, String id, boolean ok, String detail) {
    main.post(
        () -> {
          if (closed) return;
          try {
            reply.postMessage(
                new JSONObject()
                    .put("id", id)
                    .put("ok", ok)
                    .put(
                        ok ? "result" : "error",
                        ok ? new JSONObject().put("status", detail) : detail)
                    .toString());
          } catch (Exception ignored) {
          }
        });
  }

  private void enqueue(Runnable task) {
    if (closed) return;
    try {
      writer.execute(task);
    } catch (RejectedExecutionException ignored) {
    }
  }

  private static String safeName(String name) throws IOException {
    String result = name.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
    if (result.isEmpty() || result.equals(".") || result.equals("..") || result.length() > 200)
      throw new IOException("REMOTE_FILE_NAME");
    return result;
  }

  private void failActive() {
    Transfer transfer = active;
    active = null;
    if (transfer == null) return;
    try {
      if (transfer.output != null) transfer.output.close();
    } catch (IOException ignored) {
    }
    if (transfer.pendingGallery != null)
      try {
        activity.getContentResolver().delete(transfer.pendingGallery, null, null);
      } catch (RuntimeException ignored) {
      }
    deleteOwnStage(transfer);
  }

  private static void deleteOwnStage(Transfer transfer) {
    if (transfer.shareFile == null) return;
    // Only the fresh file/directory created above. No traversal or user destination deletion.
    transfer.shareFile.delete();
    transfer.shareFile.getParentFile().delete();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    nonce = UUID.randomUUID().toString();
    main.removeCallbacksAndMessages(null);
    if (documentScript != null) documentScript.remove();
    if (attached) WebViewCompat.removeWebMessageListener(view, "DshaRemoteFiles");
    writer.execute(this::failActive);
    writer.shutdown();
  }
}
