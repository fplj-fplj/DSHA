package com.deepseekharness.app.remote;

import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Remote principal; independent of local Ubuntu, Web generation and device bridge tokens. */
public final class RemoteSessionIdentity {
  public final String peerId, hostInstanceId, sessionId, connectionEpoch;
  public final long trustRevision, generation;

  public RemoteSessionIdentity(
      String peerId,
      String hostInstanceId,
      String sessionId,
      String connectionEpoch,
      long trustRevision,
      long generation) {
    this.peerId = RemoteHost.id(peerId);
    this.hostInstanceId = RemoteHost.id(hostInstanceId);
    this.sessionId = RemoteHost.id(sessionId);
    if (connectionEpoch == null
        || !connectionEpoch.matches("[0-9a-f]{32}")
        || trustRevision < 1
        || generation < 1) throw new IllegalArgumentException("REMOTE_EPOCH");
    this.connectionEpoch = connectionEpoch;
    this.trustRevision = trustRevision;
    this.generation = generation;
  }

  public String toJsonString() {
    try {
      return new JSONObject()
          .put("peerId", peerId)
          .put("hostInstanceId", hostInstanceId)
          .put("sessionId", sessionId)
          .put("connectionEpoch", connectionEpoch)
          .put("trustRevision", trustRevision)
          .put("generation", generation)
          .toString();
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static RemoteSessionIdentity fromJsonString(String json) {
    if (json == null || json.length() > 4096) throw new IllegalArgumentException("REMOTE_IDENTITY");
    try {
      JSONObject object = new JSONObject(json);
      return new RemoteSessionIdentity(
          object.getString("peerId"),
          object.getString("hostInstanceId"),
          object.getString("sessionId"),
          object.getString("connectionEpoch"),
          object.getLong("trustRevision"),
          object.getLong("generation"));
    } catch (JSONException invalid) {
      throw new IllegalArgumentException("REMOTE_IDENTITY");
    }
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof RemoteSessionIdentity)) return false;
    RemoteSessionIdentity that = (RemoteSessionIdentity) other;
    return peerId.equals(that.peerId)
        && hostInstanceId.equals(that.hostInstanceId)
        && sessionId.equals(that.sessionId)
        && connectionEpoch.equals(that.connectionEpoch)
        && trustRevision == that.trustRevision
        && generation == that.generation;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        peerId, hostInstanceId, sessionId, connectionEpoch, trustRevision, generation);
  }

  @Override
  public String toString() {
    return "RemoteSessionIdentity[redacted]";
  }
}
