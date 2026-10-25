package com.deepseekharness.app.remote.ui;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The QR and manual entry use the same bounded ticket; errors never echo its secret. */
public final class PairingTicket {
  public final String peerId, label, hostInstanceId, pairingKey;
  public final String discoveryURL, discoveryToken;
  public final List<String> addresses, relayAddresses;
  public final long expiresAt;
  public final boolean refresh;

  private PairingTicket(
      String peer,
      String label,
      String instance,
      String key,
      List<String> addresses,
      List<String> relays,
      long expiry,
      String discoveryURL,
      String discoveryToken,
      boolean refresh) {
    this.peerId = peer;
    this.label = label.isEmpty() ? "PC · " + peer.substring(Math.max(0, peer.length() - 8)) : label;
    hostInstanceId = instance;
    pairingKey = key;
    this.addresses = List.copyOf(addresses);
    relayAddresses = List.copyOf(relays);
    expiresAt = expiry;
    this.discoveryURL = discoveryURL;
    this.discoveryToken = discoveryToken;
    this.refresh = refresh;
  }

  public static PairingTicket parse(String input, long now) {
    try {
      if (input == null || input.length() > 24000) throw new IllegalArgumentException();
      String encoded = input.trim();
      String route = "";
      if (encoded.contains(":")) {
        URI uri = new URI(encoded);
        if (!"dsha-connect".equals(uri.getScheme())
            || !("pair".equals(uri.getHost()) || "refresh".equals(uri.getHost()))
            || uri.getPort() != -1
            || uri.getRawUserInfo() != null
            || uri.getFragment() != null
            || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
            || uri.getRawQuery() == null
            || !uri.getRawQuery().startsWith("ticket=")) throw new IllegalArgumentException();
        encoded = uri.getRawQuery().substring(7);
        route = uri.getHost();
      }
      if (encoded.isEmpty() || encoded.length() > 22000 || !encoded.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      byte[] raw = Base64.getUrlDecoder().decode(encoded);
      if (raw.length > 16000) throw new IllegalArgumentException();
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
      String peer = "", label = "", role = "", instance = "", key = "", mode = "";
      String discoveryURL = "", discoveryToken = "";
      List<String> addresses = List.of(), relays = List.of();
      long version = -1, expires = -1;
      Set<String> seen = new HashSet<>();
      try (JsonReader reader = new JsonReader(new StringReader(json))) {
        reader.setStrictness(Strictness.STRICT);
        reader.beginObject();
        while (reader.hasNext()) {
          String name = reader.nextName();
          if (!seen.add(name)) throw new IllegalArgumentException();
          switch (name) {
            case "v":
              version = number(reader);
              break;
            case "role":
              role = string(reader);
              break;
            case "mode":
              mode = string(reader);
              break;
            case "peerId":
              peer = string(reader);
              break;
            case "label":
              label = string(reader);
              break;
            case "hostInstanceId":
              instance = string(reader);
              break;
            case "pairingKey":
              key = string(reader);
              break;
            case "expiresAt":
              expires = number(reader);
              break;
            case "addresses":
              addresses = strings(reader, 16);
              break;
            case "relayAddresses":
              relays = strings(reader, 4);
              break;
            case "discoveryURL":
              discoveryURL = string(reader);
              break;
            case "discoveryToken":
              discoveryToken = string(reader);
              break;
            default:
              throw new IllegalArgumentException();
          }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
      }
      boolean refresh = "refresh".equals(mode);
      if ((!mode.isEmpty() && !refresh)
          || (refresh && seen.contains("pairingKey"))
          || (!refresh && !key.matches("[a-fA-F0-9]{64}"))
          || (!route.isEmpty() && !route.equals(refresh ? "refresh" : "pair")))
        throw new IllegalArgumentException();
      if (version != 1
          || !"pc".equals(role)
          || !peer.matches("[A-Za-z0-9]{16,128}")
          || !instance.matches("[a-fA-F0-9]{32}")
          || label.length() > 80
          || label.matches("(?s).*\\p{Cntrl}.*")
          || expires <= now
          || expires - now > 600000
          || addresses.isEmpty()) throw new IllegalArgumentException();
      for (String address : addresses)
        if (!address.endsWith("/p2p/" + peer)) throw new IllegalArgumentException();
      if (!discoveryURL.isEmpty() || !discoveryToken.isEmpty()) {
        URI discovery = new URI(discoveryURL);
        if (!"https".equals(discovery.getScheme())
            || discovery.getHost() == null
            || discovery.getRawUserInfo() != null
            || discovery.getFragment() != null
            || discovery.getRawQuery() != null
            || discoveryURL.length() > 2048
            || !discoveryToken.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException();
      }
      return new PairingTicket(
          peer,
          label,
          instance,
          key,
          addresses,
          relays,
          expires,
          discoveryURL,
          discoveryToken,
          refresh);
    } catch (Exception invalid) {
      throw new IllegalArgumentException("REMOTE_PAIRING_TICKET_INVALID");
    }
  }

  private static String string(JsonReader reader) throws java.io.IOException {
    if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
    return reader.nextString();
  }

  private static long number(JsonReader reader) throws java.io.IOException {
    if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException();
    String value = reader.nextString();
    if (!value.matches("[0-9]{1,16}")) throw new IllegalArgumentException();
    return Long.parseLong(value);
  }

  private static List<String> strings(JsonReader reader, int limit) throws java.io.IOException {
    List<String> out = new ArrayList<>();
    reader.beginArray();
    while (reader.hasNext()) {
      String value = string(reader);
      if (out.size() >= limit
          || value.length() > 2048
          || !value.startsWith("/")
          || value.matches("(?s).*[\\p{Cntrl}\\s?#].*")
          || out.contains(value)) throw new IllegalArgumentException();
      out.add(value);
    }
    reader.endArray();
    return out;
  }

  @Override
  public String toString() {
    return "PairingTicket[redacted]";
  }
}
