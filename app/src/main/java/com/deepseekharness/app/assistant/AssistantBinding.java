package com.deepseekharness.app.assistant;

import com.deepseekharness.app.remote.RemoteSessionIdentity;
import java.util.Objects;

/** A conversation address, never a shortened display label or the transport session. */
public record AssistantBinding(
    Target target,
    String hostInstanceId,
    String sessionId,
    String connectionEpoch,
    long generation,
    RemoteSessionIdentity remote) {
  public enum Target {
    LOCAL,
    REMOTE
  }

  public AssistantBinding {
    Objects.requireNonNull(target);
    check(hostInstanceId);
    check(sessionId);
    check(connectionEpoch);
    if (generation < 1 || (target == Target.REMOTE) != (remote != null))
      throw new IllegalArgumentException("ASSISTANT_BINDING_INVALID");
    if (remote != null
        && (!remote.hostInstanceId.equals(hostInstanceId)
            || !remote.connectionEpoch.equals(connectionEpoch)
            || remote.generation != generation))
      throw new IllegalArgumentException("ASSISTANT_PEER_CHANGED");
  }

  private static void check(String value) {
    if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("ASSISTANT_ID_INVALID");
  }
}
