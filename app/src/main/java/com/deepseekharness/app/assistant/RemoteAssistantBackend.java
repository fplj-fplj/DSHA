package com.deepseekharness.app.assistant;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.remote.RemoteSessionIdentity;
import com.deepseekharness.app.remote.RemoteSessionManager;
import java.io.IOException;
import org.json.JSONObject;

public final class RemoteAssistantBackend implements AssistantBackend {
  private final Context context;
  private final RemoteSessionManager manager;
  public final RemoteSessionIdentity identity;

  public RemoteAssistantBackend(Context context, RemoteSessionIdentity identity) {
    this.context = context.getApplicationContext();
    this.identity = identity;
    manager = RemoteSessionManager.get(context);
  }

  @Override
  public boolean current() {
    return manager.isCurrent(identity);
  }

  @Override
  public boolean ownsControlBinding(
      com.deepseekharness.app.control.ControlTaskAuthority.Binding value) {
    return value != null
        && value.owner() == com.deepseekharness.app.control.ControlTaskAuthority.Owner.REMOTE_DEVICE
        && identity.connectionEpoch.equals(value.connection())
        && current();
  }

  @Override
  public JSONObject call(String method, JSONObject args) throws IOException {
    if (!current()) throw new IOException("REMOTE_PEER_CHANGED");
    JSONObject value = manager.callDsh(identity, method, args);
    if (!current()) throw new IOException("REMOTE_PEER_CHANGED");
    return value;
  }

  @Override
  public void returnConversation(AssistantBinding binding) throws IOException {
    if (!current()) throw new IOException("REMOTE_PEER_CHANGED");
    // Native conversation screen stays bound to this exact peer and conversation.
    context.startActivity(
        new Intent(context, AssistantActivity.class)
            .putExtra("bound", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }
}
