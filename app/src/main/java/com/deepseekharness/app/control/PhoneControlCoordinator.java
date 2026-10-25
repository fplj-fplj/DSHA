package com.deepseekharness.app.control;

import android.content.Context;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide native authority; remote tasks do not borrow a local Web generation. */
public final class PhoneControlCoordinator {
  private static PhoneControlCoordinator instance;
  private final Context context;
  private final ControlTaskAuthority authority = new ControlTaskAuthority();
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private volatile ScreenControlConfirmation confirmation;
  private volatile java.util.function.Consumer<ControlTaskAuthority.Binding> turnCancellation;

  private PhoneControlCoordinator(Context context) {
    this.context = context.getApplicationContext();
    OwnedScreenOverlay.setExpansionListener(
        () -> {
          var state = snapshot();
          if (!state.paused() || "REMOTE_PAUSE".equals(state.reason()))
            pause("ASSISTANT_PANEL_EXPANDED");
        });
  }

  public static synchronized PhoneControlCoordinator get(Context context) {
    if (instance == null) instance = new PhoneControlCoordinator(context);
    return instance;
  }

  public ControlTaskAuthority.Snapshot snapshot() {
    return authority.snapshot();
  }

  public ControlTaskAuthority.Lease begin(ControlTaskAuthority.Binding binding) {
    var lease = authority.begin(binding);
    changed();
    return lease;
  }

  public void checkBegin(ControlTaskAuthority.Binding binding) {
    authority.checkBegin(binding);
  }

  public ControlTaskAuthority.Lease lease(ControlTaskAuthority.Binding binding) {
    return authority.lease(binding);
  }

  public boolean current(ControlTaskAuthority.Lease lease) {
    return authority.current(lease);
  }

  public boolean actionAllowed(ControlTaskAuthority.Lease lease) {
    return authority.actionAllowed(lease);
  }

  public boolean observed(ControlTaskAuthority.Lease lease) {
    boolean result = authority.observed(lease);
    if (result) changed();
    return result;
  }

  public void requireObservation(ControlTaskAuthority.Binding binding) {
    authority.requireObservation(binding);
    fence(false, null);
  }

  /** Completion fences work but must not invoke the selected assistant's cancel callback. */
  public void completeLocal(ControlTaskAuthority.Binding binding) {
    if (authority.completeLocal(binding)) fence(false, null);
  }

  public void finishTask(ControlTaskAuthority.Binding binding) {
    if (authority.finish(binding)) fence(false, null);
  }

  public void pause(String reason) {
    var previous = authority.pause(reason);
    fence(true, previous);
  }

  public boolean pauseCurrent(ControlTaskAuthority.Lease lease, String reason) {
    var previous = authority.pauseCurrent(lease, reason);
    if (previous == null) return false;
    fence(true, previous);
    return true;
  }

  public void resume() {
    authority.resume();
    fence(false, null);
  }

  public void stop(String reason) {
    var previous = authority.stop(reason);
    fence(true, previous);
  }

  public void disconnectRemote(com.deepseekharness.app.remote.RemoteSessionIdentity identity) {
    var value = snapshot().binding();
    if (value != null
        && value.owner() == ControlTaskAuthority.Owner.REMOTE_DEVICE
        && value.connection().equals(identity.connectionEpoch)) pause("REMOTE_DISCONNECTED");
  }

  public void stopLocal(String reason) {
    var binding = snapshot().binding();
    if (binding != null && binding.owner() == ControlTaskAuthority.Owner.LOCAL_DSH) stop(reason);
  }

  public void bindRequest(CapabilityRequest request, ControlTaskAuthority.Lease lease) {
    if (!current(lease)) throw new IllegalStateException("SCREEN_TASK_CHANGED");
    ScreenAuthorityBindings.bind(
        request, lease.binding().identity() + "\0" + lease.revision(), () -> current(lease));
  }

  public void setConfirmationHandler(ScreenControlConfirmation handler) {
    confirmation = handler;
  }

  public ScreenControlConfirmation confirmationHandler() {
    return confirmation;
  }

  public void setTurnCancellation(
      java.util.function.Consumer<ControlTaskAuthority.Binding> callback) {
    turnCancellation = callback;
  }

  public void addListener(Runnable listener) {
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void fence(boolean cancelTurn, ControlTaskAuthority.Binding previous) {
    DshaApp.from(context).capabilityBroker().revoke("screen");
    DshaAccessibilityService.revokeRequests();
    if (cancelTurn && previous != null) {
      var callback = turnCancellation;
      if (callback != null)
        try {
          callback.accept(previous);
        } catch (RuntimeException ignored) {
        }
    }
    changed();
  }

  private void changed() {
    for (Runnable listener : listeners)
      try {
        listener.run();
      } catch (RuntimeException ignored) {
      }
  }
}
