package com.deepseekharness.app.remote.ui;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

public final class PairingTicketTest {
  static final long NOW = 1800000000000L;
  static final String PEER = "12D3KooWExamplePeerIdentity123456789";
  static final String KEY = "a".repeat(64);

  static String json() {
    return "{\"v\":1,\"role\":\"pc\",\"peerId\":\""
        + PEER
        + "\","
        + "\"hostInstanceId\":\""
        + "b".repeat(32)
        + "\",\"pairingKey\":\""
        + KEY
        + "\","
        + "\"expiresAt\":"
        + (NOW + 180000)
        + ",\"addresses\":[\"/ip4/192.168.1.4/tcp/52341/p2p/"
        + PEER
        + "\"]}";
  }

  static String code(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  static String url(String json) {
    return "dsha-connect://pair?ticket=" + code(json);
  }

  static String refreshJson() {
    return json()
        .replace("\"role\":\"pc\"", "\"role\":\"pc\",\"mode\":\"refresh\"")
        .replace("\"pairingKey\":\"" + KEY + "\",", "")
        .replace("192.168.1.4/tcp/52341", "192.168.2.4/tcp/52341");
  }

  static String refreshUrl(String json) {
    return "dsha-connect://refresh?ticket=" + code(json);
  }

  private static void rejects(String input) {
    try {
      PairingTicket.parse(input, NOW);
      fail("Accepted invalid pairing data");
    } catch (IllegalArgumentException expected) {
      assertEquals("REMOTE_PAIRING_TICKET_INVALID", expected.getMessage());
      assertFalse(expected.getMessage().contains(KEY));
      assertNull(expected.getCause());
    }
  }

  @Test
  public void qrAndManualCodeResolveSamePeerAndNeverEchoPairKey() {
    PairingTicket qr = PairingTicket.parse(url(json()), NOW);
    PairingTicket manual = PairingTicket.parse("  " + code(json()) + "\n", NOW);
    assertEquals(PEER, qr.peerId);
    assertEquals(qr.peerId, manual.peerId);
    assertEquals(qr.addresses, manual.addresses);
    assertEquals(KEY, qr.pairingKey);
    assertEquals("PairingTicket[redacted]", qr.toString());
  }

  @Test
  public void unrelatedSchemesAuthoritiesAndInjectedParametersRejected() {
    for (String input :
        new String[] {
          url(json()).replace("dsha-connect:", "https:"),
          url(json()).replace("//pair?", "//evil@pair?"),
          url(json()).replace("//pair?", "//pair:80?"),
          url(json()).replace("//pair?", "//pair/other?"),
          url(json()) + "&ticket=" + code(json()),
          url(json()) + "#fragment",
          "javascript:alert(1)",
          "intent://pair",
          "file:///sdcard/code"
        }) rejects(input);
  }

  @Test
  public void expiryVersionRoleAndDuplicateFieldsRejected() {
    rejects(url(json().replace("\"v\":1", "\"v\":2")));
    rejects(url(json().replace("\"v\":1", "\"v\":1,\"v\":1")));
    rejects(url(json().replace("\"role\":\"pc\"", "\"role\":\"phone\"")));
    rejects(url(json().replace(String.valueOf(NOW + 180000), String.valueOf(NOW))));
    rejects(url(json().replace(String.valueOf(NOW + 180000), String.valueOf(NOW + 600001))));
    rejects(url(json().replace("\"v\":1", "\"v\":\"1\"")));
    rejects(url(json().replace("\"v\":1", "\"v\":1,\"unknown\":true")));
  }

  @Test
  public void endpointMustBelongToTicketPeerAndNoDuplicateAddresses() {
    rejects(url(json().replace("/p2p/" + PEER, "/p2p/OtherComputer123456789")));
    rejects(
        url(
            json()
                .replace(
                    "\"addresses\":[",
                    "\"addresses\":[\"/ip4/192.168.1.4/tcp/52341/p2p/" + PEER + "\",")));
    rejects(url(json().replace("/ip4/192.168.1.4", "https://private.example")));
    rejects(url(json().replace("/tcp/52341", "/tcp/52341?secret")));
  }

  @Test
  public void relayTargetsAndIndependentDiscoveryCapabilityAccepted() {
    String relay =
        json()
            .replace(
                "/ip4/192.168.1.4/tcp/52341/p2p/",
                "/dns4/relay.example/tcp/443/p2p/RelayPeer123456789/p2p-circuit/p2p/");
    String extras =
        ",\"label\":\"My computer\",\"relayAddresses\":[\"/dns4/relay.example/tcp/443/p2p/RelayPeer123456789\"],"
            + "\"discoveryURL\":\"https://relay.example/discovery\",\"discoveryToken\":\""
            + "c".repeat(64)
            + "\"";
    String value = relay.substring(0, relay.length() - 1) + extras + "}";
    PairingTicket ticket = PairingTicket.parse(url(value), NOW);
    assertEquals("My computer", ticket.label);
    assertEquals(1, ticket.relayAddresses.size());
    assertNotEquals(ticket.pairingKey, ticket.discoveryToken);
    rejects(
        url(value.replace("https://relay.example/discovery", "http://relay.example/discovery")));
    rejects(
        url(
            value.replace(
                "https://relay.example/discovery", "https://user@relay.example/discovery")));
    rejects(
        url(
            value.replace(
                "https://relay.example/discovery", "https://relay.example/discovery?token=x")));
    rejects(url(value.replace(",\"discoveryToken\":\"" + "c".repeat(64) + "\"", "")));
  }

  @Test
  public void malformedUtf8SecretsAndOversizedCodesRejected() {
    rejects("_w");
    rejects("a".repeat(24001));
    rejects(url(json().replace(KEY, "short-secret")));
    rejects(url(json().replace("\"role\":\"pc\"", "\"role\":\"pc\",\"label\":\"bad\\nlabel\"")));
    rejects(url(json() + "{}"));
  }

  @Test
  public void trustedRefreshQrAndManualCodeCarryNewHintsWithoutConnectionSecret() {
    PairingTicket qr = PairingTicket.parse(refreshUrl(refreshJson()), NOW);
    PairingTicket manual = PairingTicket.parse(code(refreshJson()), NOW);
    assertTrue(qr.refresh);
    assertTrue(manual.refresh);
    assertFalse(PairingTicket.parse(url(json()), NOW).refresh);
    assertEquals(PEER, qr.peerId);
    assertTrue(qr.addresses.get(0).contains("192.168.2.4"));
    assertEquals("", qr.pairingKey);
    assertEquals(qr.hostInstanceId, manual.hostInstanceId);
    assertFalse(qr.toString().contains(PEER));
  }

  @Test
  public void refreshRejectsEveryPairingKeyFieldAndConfusedRouteOrMode() {
    rejects(
        refreshUrl(
            refreshJson()
                .replace(
                    "\"mode\":\"refresh\"",
                    "\"mode\":\"refresh\",\"pairingKey\":\"" + KEY + "\"")));
    rejects(
        refreshUrl(
            refreshJson()
                .replace("\"mode\":\"refresh\"", "\"mode\":\"refresh\",\"pairingKey\":\"\"")));
    rejects(url(refreshJson()));
    rejects(refreshUrl(json()));
    rejects(refreshUrl(refreshJson().replace("\"mode\":\"refresh\"", "\"mode\":\"other\"")));
    rejects(refreshUrl(refreshJson()).replace("//refresh?", "//retrust?"));
  }

  @Test
  public void refreshRejectsExpiredHostlessOrOtherPeerAddressesBeforeAnyStoreWrite() {
    rejects(refreshUrl(refreshJson().replace(String.valueOf(NOW + 180000), String.valueOf(NOW))));
    rejects(refreshUrl(refreshJson().replace("b".repeat(32), "")));
    rejects(refreshUrl(refreshJson().replace("/p2p/" + PEER, "/p2p/OtherPeer123456789")));
  }
}
