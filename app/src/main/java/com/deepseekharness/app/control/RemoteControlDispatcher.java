package com.deepseekharness.app.control;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.capabilities.WindowTargetEvidence;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.ScreenRuntimeIdentity;
import com.deepseekharness.app.remote.RemoteSessionIdentity;
import com.deepseekharness.app.remote.RemoteSessionManager;
import com.deepseekharness.app.remote.RemoteProtocol;
import com.deepseekharness.app.util.ScreenTarget;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Authenticated CONTROL channel only. There is no shell, ADB, file-path or runtime-start route. */
public final class RemoteControlDispatcher {
  private static final Set<String> OPERATIONS =
      Set.of("tree", "dump", "screenshot", "tap", "swipe", "node", "input", "key");
  private static final ControlRequestJournal JOURNAL = new ControlRequestJournal(1024);

  private RemoteControlDispatcher() {}

  public static void handle(
      Context context, RemoteSessionIdentity identity, InputStream input, OutputStream output)
      throws java.io.IOException {
    JSONObject reply;
    String requestId = "";
    ControlRequestJournal.Reservation reservation = null;
    try {
      DataInputStream data = new DataInputStream(input);
      int size = data.readInt();
      if (size <= 0 || size > RemoteProtocol.MAX_REQUEST)
        throw new IllegalArgumentException("CONTROL_REQUEST_LIMIT");
      byte[] bytes = new byte[size];
      data.readFully(bytes);
      String utf8 =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
      JSONObject request = new JSONObject(utf8);
      requestId = request.optString("requestId");
      if (requestId.isEmpty() || requestId.length() > 256)
        throw new IllegalArgumentException("CONTROL_REQUEST_ID_INVALID");
      if (request.optInt("v") != 1
          || !"device".equals(request.optString("op"))
          || !"physical-screen".equals(request.optString("targetId")))
        throw new IllegalArgumentException("CONTROL_ROUTE_INVALID");
      if (request.optLong("generation", -1) != identity.generation
          || !identity.equals(
              RemoteSessionIdentity.fromJsonString(request.getJSONObject("identity").toString())))
        throw new IllegalStateException("REMOTE_SESSION_CHANGED");
      StringBuilder hashed = new StringBuilder(64);
      for (byte part : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        hashed.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
      String digest = hashed.toString();
      reservation =
          JOURNAL.reserve(
              identity.toJsonString(),
              requestId,
              digest,
              () -> RemoteSessionManager.get(context).isCurrent(identity));
      if (reservation.state == ControlRequestJournal.State.FINISHED) {
        write(output, new JSONObject(reservation.receipt));
        return;
      }
      if (reservation.state == ControlRequestJournal.State.IN_FLIGHT) {
        write(
            output,
            new JSONObject()
                .put("v", 1)
                .put("requestId", requestId)
                .put("ok", true)
                .put(
                    "result",
                    com.deepseekharness.app.screen.ScreenReply.cancelled(
                        "DUPLICATE_REQUEST_IN_FLIGHT", true, true)));
        return;
      }
      if (reservation.state != ControlRequestJournal.State.NEW)
        throw new IllegalStateException(
            switch (reservation.state) {
              case CONFLICT -> "CONTROL_REQUEST_ID_REUSED";
              case FULL -> "CONTROL_REQUEST_JOURNAL_FULL";
              default -> "REMOTE_SESSION_CHANGED";
            });
      JSONObject result =
          dispatch(context, identity, request.getString("method"), request.optJSONObject("args"));
      reply =
          new JSONObject()
              .put("v", 1)
              .put("requestId", requestId)
              .put("ok", true)
              .put("result", result);
    } catch (Exception refused) {
      try {
        String message = refused.getMessage();
        String code =
            message != null && message.matches("[A-Z][A-Z0-9_]{0,80}")
                ? message
                : "CONTROL_REQUEST_FAILED";
        JSONObject error = new JSONObject().put("code", code).put("automaticRetry", false);
        if (code.startsWith("ASSISTANT_") || code.equals("PHYSICAL_SCREEN_BUSY"))
          error.put(
              "guidance", com.deepseekharness.app.assistant.AssistantService.taskGuidance(code));
        reply =
            new JSONObject()
                .put("v", 1)
                .put("requestId", requestId)
                .put("ok", false)
                .put("error", error);
      } catch (org.json.JSONException impossible) {
        throw new java.io.IOException(impossible);
      }
    }
    if (reservation != null && reservation.state == ControlRequestJournal.State.NEW) {
      String receipt = reply.toString();
      if (receipt.length() > 65536) {
        try {
          receipt =
              new JSONObject()
                  .put("v", 1)
                  .put("requestId", requestId)
                  .put("ok", true)
                  .put(
                      "result",
                      com.deepseekharness.app.screen.ScreenReply.refused(
                              "REQUEST_ALREADY_COMPLETED")
                          .put(
                              "originalState",
                              reply.optJSONObject("result") == null
                                  ? "UNKNOWN"
                                  : reply.optJSONObject("result").optString("state", "UNKNOWN"))
                          .put("automaticRetry", false))
                  .toString();
        } catch (org.json.JSONException impossible) {
          throw new java.io.IOException(impossible);
        }
      }
      JOURNAL.finish(reservation, receipt);
    }
    write(output, reply);
  }

  private static void write(OutputStream output, JSONObject reply) throws java.io.IOException {
    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
    DataOutputStream data = new DataOutputStream(output);
    data.writeInt(bytes.length);
    data.write(bytes);
    data.flush();
  }

  public static JSONObject dispatch(
      Context context, RemoteSessionIdentity identity, String method, JSONObject arguments)
      throws Exception {
    var sessions = RemoteSessionManager.get(context);
    if (identity == null || !sessions.isCurrent(identity))
      throw new IllegalStateException("REMOTE_SESSION_CHANGED");
    var coordinator = PhoneControlCoordinator.get(context);
    JSONObject args = arguments == null ? new JSONObject() : new JSONObject(arguments.toString());
    if (method.equals("status")) return status(coordinator);
    var binding =
        new ControlTaskAuthority.Binding(
            ControlTaskAuthority.Owner.REMOTE_DEVICE,
            identity.connectionEpoch,
            args.getString("taskSessionId"),
            args.getString("turnId"));
    if (method.equals("task.begin")) {
      var existing = coordinator.snapshot().binding();
      if (existing != null && !existing.equals(binding))
        throw new IllegalStateException("PHYSICAL_SCREEN_BUSY");
      if (coordinator.snapshot().paused()) throw new IllegalStateException("LOCAL_RESUME_REQUIRED");
      var taskUi =
          com.deepseekharness.app.assistant.AssistantService.ensureRemoteTask(
              context, identity, binding.taskSessionId(), binding.turnId());
      if (!taskUi.ready()) throw new IllegalStateException(taskUi.code());
      if (!sessions.isCurrent(identity)) throw new IllegalStateException("REMOTE_SESSION_CHANGED");
      coordinator.begin(binding);
      return status(coordinator);
    }
    var snapshot = coordinator.snapshot();
    if (!binding.equals(snapshot.binding())) throw new IllegalStateException("SCREEN_TASK_CHANGED");
    if (method.equals("task.pause")) {
      if (!snapshot.paused()) coordinator.pause("REMOTE_PAUSE");
      return status(coordinator);
    }
    if (method.equals("task.resume")) {
      if (snapshot.paused() && !"REMOTE_PAUSE".equals(snapshot.reason()))
        throw new IllegalStateException("LOCAL_RESUME_REQUIRED");
      coordinator.resume();
      return status(coordinator);
    }
    if (method.equals("task.stop")) {
      coordinator.stop("REMOTE_STOP");
      com.deepseekharness.app.assistant.AssistantService.finishLocalTask(context, binding);
      return status(coordinator);
    }
    if (method.equals("task.finish")) {
      coordinator.finishTask(binding);
      com.deepseekharness.app.assistant.AssistantService.finishLocalTask(context, binding);
      return status(coordinator);
    }
    if (!method.startsWith("screen.")) throw new IllegalArgumentException("CONTROL_ROUTE_INVALID");
    String operation = method.substring(7);
    if (operation.equals("press")) operation = "key";
    if (!OPERATIONS.contains(operation))
      throw new IllegalArgumentException("CONTROL_ROUTE_INVALID");
    boolean observe =
        operation.equals("tree") || operation.equals("dump") || operation.equals("screenshot");
    var screenUi =
        com.deepseekharness.app.assistant.AssistantService.prepareScreenOperation(
            context, binding, observe);
    if (!screenUi.ready()) throw new IllegalStateException(screenUi.code());
    var lease = coordinator.lease(binding);
    if (!observe && !coordinator.actionAllowed(lease))
      throw new IllegalStateException("FRESH_SCREEN_OBSERVATION_REQUIRED");
    BooleanSupplier live =
        () ->
            com.deepseekharness.app.assistant.AssistantService.stopChannelCurrent(context, binding)
                && coordinator.current(lease)
                && sessions.isCurrent(identity)
                && !OwnedScreenOverlay.expanded();
    var preflight = DshaAccessibilityService.observeTargetForRequest(live);
    if (preflight.target == null) return preflight.failure();
    ScreenTarget target = preflight.target;
    var broker = DshaApp.from(context).capabilityBroker();
    var grants = new DeviceGrants(context);
    long consentRevision = grants.consentPolicy().revision();
    var request =
        CapabilityRequest.create(
            "screen",
            operation,
            ScreenRuntimeIdentity.remoteDevice(
                context, identity.generation, identity.connectionEpoch),
            DshaApp.from(context).installationId(),
            identity.connectionEpoch,
            evidence(target),
            grants.screenPolicy().revision,
            broker.epoch("screen"),
            SystemClock.elapsedRealtime() + 65000);
    coordinator.bindRequest(request, lease);
    BooleanSupplier current =
        () ->
            live.getAsBoolean()
                && broker.current(request)
                && new DeviceGrants(context).screenPolicy().revision == request.policyRevision
                && new DeviceGrants(context).consentPolicy().revision() == consentRevision;
    if (!current.getAsBoolean()) throw new IllegalStateException("SCREEN_TASK_CHANGED");
    if (!grants.effectiveScreenPolicy().allows(target.packageName)) {
      var handler = coordinator.confirmationHandler();
      if (handler == null) throw new IllegalStateException("SCREEN_CONFIRMATION_UNAVAILABLE");
      var pending = broker.beginConfirmation(request);
      if (pending == null) throw new IllegalStateException("SCREEN_CONFIRMATION_BUSY");
      try {
        handler.confirm(
            request,
            target,
            accepted -> {
              if (current.getAsBoolean()) broker.resolve(pending, Boolean.TRUE.equals(accepted));
              else broker.cancel(request);
            });
        if (!pending.await(60, TimeUnit.SECONDS) || !current.getAsBoolean())
          throw new IllegalStateException("SCREEN_CONFIRMATION_DENIED_OR_EXPIRED");
      } finally {
        broker.finish(pending);
      }
    }
    if (!current.getAsBoolean() || !broker.authorize(request))
      throw new IllegalStateException("SCREEN_TASK_CHANGED");
    if (operation.equals("screenshot")) args.put("format", "mcp").put("maxImageBytes", 2900000);
    JSONObject result =
        new JSONObject(DshaAccessibilityService.execute(broker, request, current, operation, args));
    if (observe && "COMPLETED".equals(result.optString("state"))) coordinator.observed(lease);
    result
        .put("owner", "REMOTE_DEVICE")
        .put("taskSessionId", binding.taskSessionId())
        .put("turnId", binding.turnId())
        .put("connectionEpoch", identity.connectionEpoch)
        .put("automaticRetry", false);
    return result;
  }

  private static WindowTargetEvidence evidence(ScreenTarget target) {
    return new WindowTargetEvidence() {
      public String kind() {
        return "android-window";
      }

      public int displayId() {
        return target.displayId;
      }

      public int windowId() {
        return target.windowId;
      }

      public String packageName() {
        return target.packageName;
      }
    };
  }

  private static JSONObject status(PhoneControlCoordinator coordinator)
      throws org.json.JSONException {
    var state = coordinator.snapshot();
    JSONObject value =
        new JSONObject()
            .put("state", state.binding() == null ? "IDLE" : state.paused() ? "PAUSED" : "ACTIVE")
            .put("paused", state.paused())
            .put("needsObservation", state.needsObservation())
            .put("revision", state.revision())
            .put("reason", state.reason())
            .put("automaticRetry", false);
    if (state.binding() != null)
      value
          .put("owner", state.binding().owner().name())
          .put("taskSessionId", state.binding().taskSessionId())
          .put("turnId", state.binding().turnId());
    return value;
  }
}
