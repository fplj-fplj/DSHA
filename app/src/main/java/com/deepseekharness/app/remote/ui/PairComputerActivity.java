package com.deepseekharness.app.remote.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.remote.RemoteConnectionService;
import com.deepseekharness.app.remote.RemoteHost;
import com.deepseekharness.app.remote.TrustedPeerStore;
import java.util.UUID;

/** One initial grant authorizes pairing; a ticket alone is never remembered trust. */
public final class PairComputerActivity extends AppCompatActivity {
  private EditText code;
  private TextView status;
  private androidx.appcompat.app.AlertDialog grant;
  private PairingTicket pendingTicket;
  private final java.util.concurrent.ExecutorService io =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private boolean active;
  private int reviewEpoch;
  private final androidx.activity.result.ActivityResultLauncher<Intent> scanner =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null)
              review(result.getData().getStringExtra(QrScannerActivity.RESULT));
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    setContentView(R.layout.build195_remote_pair);
    WorkspacePage.insets(findViewById(R.id.remote_pair_root));
    code = findViewById(R.id.remote_pair_code);
    status = findViewById(R.id.remote_pair_status);
    LinearLayout content = findViewById(R.id.remote_pair_content);
    WorkspacePage.button(
        this,
        content,
        getString(R.string.remote_scan_qr),
        true,
        v -> scanner.launch(new Intent(this, QrScannerActivity.class)));
    WorkspacePage.button(
        this,
        content,
        getString(R.string.remote_review_pair),
        false,
        v -> review(code.getText().toString()));
    WorkspacePage.button(this, content, getString(R.string.remote_back), false, v -> finish());
  }

  private void review(String input) {
    if (isFinishing() || isDestroyed()) return;
    final PairingTicket ticket;
    try {
      ticket = PairingTicket.parse(input, System.currentTimeMillis());
    } catch (IllegalArgumentException invalid) {
      status.setText(R.string.remote_code_invalid);
      return;
    }
    code.setText("");
    if (grant != null) grant.dismiss();
    int epoch = ++reviewEpoch;
    status.setText(R.string.remote_checking_trust);
    io.execute(
        () -> {
          try {
            RemoteHost trusted = TrustedPeerStore.get(this).get(ticket.peerId);
            runOnUiThread(
                () -> {
                  if (!active || epoch != reviewEpoch || isFinishing() || isDestroyed()) return;
                  if (ticket.refresh && trusted == null) {
                    status.setText(R.string.remote_refresh_unknown);
                  } else if (!ticket.refresh && trusted != null) {
                    status.setText(R.string.remote_refresh_required);
                  } else {
                    showReview(ticket, trusted);
                  }
                });
          } catch (java.io.IOException unavailable) {
            runOnUiThread(
                () -> {
                  if (active && epoch == reviewEpoch)
                    status.setText(R.string.remote_trust_unavailable);
                });
          }
        });
  }

  private void showReview(PairingTicket ticket, RemoteHost trusted) {
    status.setText("");
    grant =
        new com.deepseekharness.app.ui.DshaDialogBuilder(this)
            .setTitle(ticket.refresh ? R.string.remote_refresh_title : R.string.remote_grant_title)
            .setMessage(
                ticket.refresh
                    ? getString(
                        R.string.remote_refresh_body,
                        trusted.label,
                        ticket.peerId,
                        ticket.addresses.size())
                    : getString(R.string.remote_grant_body, ticket.label, ticket.peerId))
            .setNegativeButton(R.string.remote_cancel, null)
            .setPositiveButton(
                ticket.refresh ? R.string.remote_refresh_connect : R.string.remote_grant,
                (dialog, which) -> {
                  // The dialog owns window focus. Start only after this Activity regains it.
                  pendingTicket = ticket;
                  dialog.dismiss();
                  getWindow().getDecorView().post(this::pairWhenFocused);
                })
            .show();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) pairWhenFocused();
  }

  private void pairWhenFocused() {
    PairingTicket ticket = pendingTicket;
    if (ticket == null
        || !hasWindowFocus()
        || isFinishing()
        || isDestroyed()
        || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
      return;
    pendingTicket = null;
    if (ticket.expiresAt <= System.currentTimeMillis()) {
      status.setText(R.string.remote_code_invalid);
      return;
    }
    try {
      RemoteHost host =
          new RemoteHost(
              ticket.peerId,
              ticket.label,
              ticket.hostInstanceId,
              ticket.addresses,
              ticket.relayAddresses,
              ticket.discoveryURL,
              ticket.discoveryToken);
      if (ticket.refresh)
        RemoteConnectionService.refreshFromVisibleActivity(
            this, host, UUID.randomUUID().toString());
      else
        RemoteConnectionService.pairFromVisibleActivity(
            this, host, ticket.pairingKey, UUID.randomUUID().toString());
      status.setText(
          ticket.refresh ? R.string.remote_refresh_started : R.string.remote_pair_started);
      WorkspaceActivity.open(this);
      finish();
    } catch (java.io.IOException | RuntimeException unavailable) {
      status.setText(R.string.remote_error);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
  }

  @Override
  protected void onStop() {
    active = false;
    reviewEpoch++;
    if (code != null) code.setText("");
    pendingTicket = null;
    if (grant != null) {
      grant.dismiss();
      grant = null;
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    io.shutdownNow();
    super.onDestroy();
  }
}
