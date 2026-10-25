package com.deepseekharness.app.remote.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.remote.RemoteConnectionService;
import com.deepseekharness.app.remote.RemoteHost;
import com.deepseekharness.app.remote.RemoteSessionManager;
import com.deepseekharness.app.remote.TrustedPeerStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Environment-free native home. A PC session never enters the local runtime gate. */
public final class WorkspaceActivity extends AppCompatActivity {
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private RemoteSessionManager manager;
  private List<RemoteHost> hosts = List.of();
  private LinearLayout hostList, actions;
  private TextView status;
  private boolean active;
  private int loadRevision;
  private final RemoteSessionManager.Listener listener =
      snapshot ->
          runOnUiThread(
              () -> {
                if (!active) return;
                render();
                if (snapshot.state == RemoteSessionManager.State.CONNECTED) loadHosts();
              });

  public static void open(Context context) {
    context.startActivity(
        new Intent(context, WorkspaceActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.build195_remote_workspaces);
    WorkspacePage.insets(findViewById(R.id.remote_workspace_root));
    manager = RemoteSessionManager.get(this);
    status = findViewById(R.id.remote_connection_status);
    hostList = findViewById(R.id.remote_host_list);
    actions = findViewById(R.id.remote_workspace_actions);
    LinearLayout local = WorkspacePage.card(this, findViewById(R.id.remote_local_card));
    WorkspacePage.text(this, local, getString(R.string.remote_local_phone), true);
    WorkspacePage.text(this, local, getString(R.string.remote_local_detail), false);
    WorkspacePage.button(
        this,
        local,
        getString(R.string.remote_open_local),
        true,
        v -> startActivity(WorkspaceEntry.local(this)));
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
    manager.addListener(listener);
    loadHosts();
    render();
  }

  @Override
  protected void onStop() {
    active = false;
    loadRevision++;
    manager.removeListener(listener);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    io.shutdownNow();
    super.onDestroy();
  }

  private void loadHosts() {
    int revision = ++loadRevision;
    io.execute(
        () -> {
          try {
            List<RemoteHost> result = TrustedPeerStore.get(this).list();
            // A persisted peer is one computer even if its endpoint list changes.
            List<RemoteHost> unique = uniqueHosts(result);
            runOnUiThread(
                () -> {
                  if (!active || revision != loadRevision) return;
                  hosts = unique;
                  render();
                });
          } catch (java.io.IOException unavailable) {
            runOnUiThread(
                () -> {
                  if (active && revision == loadRevision)
                    status.setText(R.string.remote_trust_unavailable);
                });
          }
        });
  }

  static List<RemoteHost> uniqueHosts(List<RemoteHost> values) {
    LinkedHashMap<String, RemoteHost> unique = new LinkedHashMap<>();
    for (RemoteHost host : values) unique.put(host.peerId, host);
    return new ArrayList<>(unique.values());
  }

  private void render() {
    if (status == null || !active || isFinishing() || isDestroyed()) return;
    RemoteSessionManager.Snapshot snapshot = manager.snapshot();
    int stateText =
        switch (snapshot.state) {
          case CONNECTING -> R.string.remote_state_connecting;
          case RECONNECTING -> R.string.remote_state_reconnecting;
          case CONNECTED -> R.string.remote_state_connected;
          case DISCONNECTED -> R.string.remote_state_disconnected;
          case FAILED -> R.string.remote_state_failed;
          default -> R.string.remote_state_stopped;
        };
    StringBuilder summary = new StringBuilder(getString(stateText));
    if (snapshot.host != null) summary.append("\n").append(snapshot.host.label);
    if (snapshot.state == RemoteSessionManager.State.CONNECTED) {
      int route =
          "direct".equals(snapshot.path)
              ? R.string.remote_path_direct
              : "relay".equals(snapshot.path)
                  ? R.string.remote_path_relay
                  : R.string.remote_path_unknown;
      summary
          .append("\n")
          .append(getString(route))
          .append("\n")
          .append(
              snapshot.latencyMillis >= 0
                  ? getString(R.string.remote_latency, snapshot.latencyMillis)
                  : getString(R.string.remote_latency_unknown));
    }
    status.setText(summary);
    hostList.removeAllViews();
    if (hosts.isEmpty())
      WorkspacePage.text(this, hostList, getString(R.string.remote_no_computers), false);
    for (RemoteHost host : hosts) {
      LinearLayout card = WorkspacePage.card(this, hostList);
      WorkspacePage.text(this, card, host.label, true);
      WorkspacePage.text(this, card, "ID · " + host.peerId, false).setTextIsSelectable(true);
      WorkspacePage.text(
          this, card, getString(R.string.remote_endpoint_count, host.addresses.size()), false);
      boolean same = snapshot.host != null && host.peerId.equals(snapshot.host.peerId);
      if (same && snapshot.state == RemoteSessionManager.State.CONNECTED) {
        WorkspacePage.button(
            this,
            card,
            getString(R.string.remote_open_pc),
            true,
            v -> com.deepseekharness.app.remote.gateway.RemoteWebLauncher.open(this));
      } else {
        var connect =
            WorkspacePage.button(
                this, card, getString(R.string.remote_connect), true, v -> connect(host));
        connect.setEnabled(
            snapshot.state != RemoteSessionManager.State.CONNECTING
                && snapshot.state != RemoteSessionManager.State.RECONNECTING);
      }
      WorkspacePage.button(
          this, card, getString(R.string.remote_revoke), false, v -> reviewRevoke(host));
    }
    actions.removeAllViews();
    WorkspacePage.button(
        this,
        actions,
        getString(R.string.remote_pair_computer),
        true,
        v -> startActivity(new Intent(this, PairComputerActivity.class)));
    if (snapshot.state == RemoteSessionManager.State.CONNECTED
        || snapshot.state == RemoteSessionManager.State.CONNECTING
        || snapshot.state == RemoteSessionManager.State.RECONNECTING)
      WorkspacePage.button(
          this,
          actions,
          getString(R.string.remote_disconnect),
          false,
          v -> RemoteConnectionService.stop(this));
  }

  private void connect(RemoteHost host) {
    try {
      RemoteConnectionService.startFromVisibleActivity(this, host, UUID.randomUUID().toString());
    } catch (java.io.IOException | RuntimeException unavailable) {
      status.setText(R.string.remote_error);
    }
  }

  private void reviewRevoke(RemoteHost host) {
    new com.deepseekharness.app.ui.DshaDialogBuilder(this)
        .setTitle(R.string.remote_revocation_title)
        .setMessage(getString(R.string.remote_revocation_body, host.label))
        .setNegativeButton(R.string.remote_cancel, null)
        .setPositiveButton(
            R.string.remote_revoke,
            (dialog, which) -> {
              io.execute(
                  () -> {
                    try {
                      TrustedPeerStore.get(this).revoke(host.peerId);
                      runOnUiThread(
                          () -> {
                            if (active) loadHosts();
                          });
                    } catch (java.io.IOException unavailable) {
                      runOnUiThread(
                          () -> {
                            if (active) status.setText(R.string.remote_error);
                          });
                    }
                  });
            })
        .show();
  }
}
