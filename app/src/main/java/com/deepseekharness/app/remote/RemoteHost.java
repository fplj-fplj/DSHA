package com.deepseekharness.app.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A paired PC. Addresses are transport hints, never evidence of peer identity. */
public final class RemoteHost {
  public final String peerId, label, hostInstanceId;
  public final List<String> addresses, relayAddresses;
  public final String discoveryURL, discoveryToken;

  public RemoteHost(
      String peerId,
      String label,
      String hostInstanceId,
      List<String> addresses,
      List<String> relayAddresses) {
    this(peerId, label, hostInstanceId, addresses, relayAddresses, "", "");
  }

  public RemoteHost(
      String peerId,
      String label,
      String hostInstanceId,
      List<String> addresses,
      List<String> relayAddresses,
      String discoveryURL,
      String discoveryToken) {
    this.peerId = id(peerId);
    if (label == null || label.isBlank() || label.length() > 120 || hasControl(label))
      throw new IllegalArgumentException("REMOTE_HOST_LABEL");
    this.label = label;
    this.hostInstanceId =
        hostInstanceId == null || hostInstanceId.isEmpty() ? "" : id(hostInstanceId);
    this.addresses = addressList(addresses);
    this.relayAddresses = addressList(relayAddresses);
    this.discoveryURL = discoveryURL == null ? "" : discoveryURL;
    this.discoveryToken = discoveryToken == null ? "" : discoveryToken;
    if (this.discoveryURL.isEmpty() != this.discoveryToken.isEmpty())
      throw new IllegalArgumentException("REMOTE_DISCOVERY");
    if (!this.discoveryURL.isEmpty()) {
      java.net.URI uri;
      try {
        uri = java.net.URI.create(this.discoveryURL);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("REMOTE_DISCOVERY");
      }
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || this.discoveryURL.length() > 2048
          || !this.discoveryToken.matches("[0-9a-fA-F]{64}"))
        throw new IllegalArgumentException("REMOTE_DISCOVERY");
    }
  }

  static String id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,160}"))
      throw new IllegalArgumentException("REMOTE_ID");
    return value;
  }

  private static boolean hasControl(String value) {
    for (int i = 0; i < value.length(); i++)
      if (Character.isISOControl(value.charAt(i))) return true;
    return false;
  }

  private static List<String> addressList(List<String> values) {
    if (values == null || values.size() > 32)
      throw new IllegalArgumentException("REMOTE_ADDRESSES");
    ArrayList<String> result = new ArrayList<>();
    for (String value : values) {
      if (value == null || value.isBlank() || value.length() > 2048 || hasControl(value))
        throw new IllegalArgumentException("REMOTE_ADDRESS");
      result.add(value);
    }
    return Collections.unmodifiableList(result);
  }

  JSONObject json() throws JSONException {
    return new JSONObject()
        .put("peerId", peerId)
        .put("label", label)
        .put("hostInstanceId", hostInstanceId)
        .put("addresses", new JSONArray(addresses))
        .put("relayAddresses", new JSONArray(relayAddresses))
        .put("discoveryURL", discoveryURL)
        .put("discoveryToken", discoveryToken);
  }

  static RemoteHost fromJson(JSONObject json) throws JSONException {
    return new RemoteHost(
        json.getString("peerId"),
        json.getString("label"),
        json.optString("hostInstanceId", ""),
        strings(json.getJSONArray("addresses")),
        strings(json.optJSONArray("relayAddresses")),
        json.optString("discoveryURL", ""),
        json.optString("discoveryToken", ""));
  }

  private static List<String> strings(JSONArray values) throws JSONException {
    ArrayList<String> result = new ArrayList<>();
    if (values != null) for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
    return result;
  }
}
