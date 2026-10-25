package com.deepseekharness.app.remote.gateway;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real host sockets exercise framing and duplex behavior; no Android, Ubuntu or fake HTTP adapter. */
public final class RemoteHttpGatewayTest {
  interface Handler {
    void handle(InputStream input, OutputStream output) throws Exception;
  }

  static final class Pc implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Future<?> result;

    Pc(Handler handler) throws IOException {
      result =
          worker.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.setSoTimeout(60_000);
                  DataInputStream input = new DataInputStream(socket.getInputStream());
                  int length = input.readInt();
                  assertTrue(length > 0 && length <= 16_384);
                  String metadata = new String(input.readNBytes(length), StandardCharsets.UTF_8);
                  assertEquals(
                      "{\"v\":1,\"service\":\"dsh-web\",\"generation\":42,\"targetId\":\"default\"}",
                      metadata);
                  handler.handle(input, socket.getOutputStream());
                } catch (Exception failure) {
                  throw new RuntimeException(failure);
                }
              });
    }

    RemoteHttpGateway.Channel open() throws IOException {
      Socket socket = new Socket("127.0.0.1", server.getLocalPort());
      return new RemoteHttpGateway.Channel() {
        public InputStream input() throws IOException {
          return socket.getInputStream();
        }

        public OutputStream output() throws IOException {
          return socket.getOutputStream();
        }

        public void close() throws IOException {
          socket.close();
        }
      };
    }

    void done() throws Exception {
      result.get(90, TimeUnit.SECONDS);
    }

    public void close() throws IOException {
      server.close();
      worker.shutdownNow();
    }
  }

  static void send(OutputStream output, String text) throws IOException {
    output.write(text.getBytes(StandardCharsets.ISO_8859_1));
    output.flush();
  }

  static String head(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int state = 0;
    while (buffer.size() < 65_536) {
      int next = input.read();
      if (next < 0) throw new EOFException();
      buffer.write(next);
      state =
          (state == 0 || state == 2) ? (next == 13 ? state + 1 : 0) : (next == 10 ? state + 1 : 0);
      if (state == 4) return buffer.toString("ISO-8859-1");
    }
    throw new IOException("HEAD_LIMIT");
  }

  static Socket browser(RemoteHttpGateway gateway) throws IOException {
    Socket socket = new Socket("127.0.0.1", new URIUnchecked(gateway.origin()).port);
    socket.setSoTimeout(60_000);
    return socket;
  }

  static final class URIUnchecked {
    final int port;

    URIUnchecked(String uri) {
      port = URI.create(uri).getPort();
    }
  }

  static String authority(RemoteHttpGateway gateway) {
    return URI.create(gateway.origin()).getRawAuthority();
  }

  static String authenticate(RemoteHttpGateway gateway) throws IOException {
    try (Socket socket = browser(gateway)) {
      send(
          socket.getOutputStream(),
          "GET "
              + URI.create(gateway.entryUrl()).getRawPath()
              + " HTTP/1.1\r\nHost: "
              + authority(gateway)
              + "\r\n\r\n");
      String response = head(socket.getInputStream());
      assertTrue(response.startsWith("HTTP/1.1 303"));
      for (String line : response.split("\r\n"))
        if (line.startsWith("Set-Cookie: ")) return line.substring(12).split(";", 2)[0];
      throw new AssertionError("No private gateway cookie");
    }
  }

  static String request(RemoteHttpGateway gateway, String cookie, String extra) {
    return "GET /api/session HTTP/1.1\r\nHost: "
        + authority(gateway)
        + "\r\nCookie: "
        + cookie
        + "\r\nOrigin: "
        + gateway.origin()
        + "\r\n"
        + extra
        + "\r\n";
  }

  @Test
  public void remoteRequestHasOnlyLogicalOriginAndNoNativeCredentials() throws Exception {
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  String request = head(input);
                  assertTrue(request.startsWith("GET /api/session HTTP/1.1"));
                  assertTrue(request.contains("Host: 127.0.0.1\r\n"));
                  assertTrue(request.contains("Origin: http://127.0.0.1\r\n"));
                  assertFalse(request.contains("DSHA_REMOTE_GATEWAY"));
                  assertFalse(request.toLowerCase(Locale.ROOT).contains("authorization:"));
                  send(output, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      String cookie = authenticate(gateway);
      try (Socket socket = browser(gateway)) {
        send(
            socket.getOutputStream(),
            request(gateway, cookie, "Authorization: must-never-reach-PC\r\n"));
        String response = head(socket.getInputStream());
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.contains("Content-Security-Policy:"));
        assertTrue(response.contains("frame-src 'none'"));
        assertEquals(
            "ok", new String(socket.getInputStream().readNBytes(2), StandardCharsets.US_ASCII));
      }
      pc.done();
    }
  }

  @Test
  public void rejectsUnauthenticatedWrongOriginAbsoluteAndSmuggledRequestsBeforeOpeningPc()
      throws Exception {
    AtomicInteger opened = new AtomicInteger();
    try (RemoteHttpGateway gateway =
        new RemoteHttpGateway(
            () -> {
              opened.incrementAndGet();
              throw new IOException();
            },
            42)) {
      assertEquals(RemoteWebOrigin.REMOTE, gateway.classification());
      assertFalse(gateway.classification().allowsLocalNativeBridge());
      assertFalse(gateway.classification().requiresLocalUbuntu());
      String cookie = authenticate(gateway);
      List<String> invalid =
          List.of(
              "GET / HTTP/1.1\r\nHost: " + authority(gateway) + "\r\n\r\n",
              request(gateway, cookie, "").replace(gateway.origin(), "http://evil.example"),
              request(gateway, cookie, "")
                  .replace("GET /api/session", "GET http://127.0.0.1:3080/"),
              request(gateway, cookie, "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n"),
              request(gateway, cookie, "Content-Length: 1\r\nContent-Length: 1\r\n"),
              request(gateway, cookie, "")
                  .replace("Host: " + authority(gateway), "Host: localhost"),
              request(gateway, cookie, "").replace("GET /api/session", "CONNECT 127.0.0.1:22"));
      for (String bad : invalid)
        try (Socket socket = browser(gateway)) {
          send(socket.getOutputStream(), bad);
          assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 4"));
        }
      assertEquals(0, opened.get());
      assertFalse(gateway.permits("http://127.0.0.1:3080/"));
      assertFalse(gateway.permits("file:///data/data/dsh.android.client/"));
      assertFalse(gateway.permits("http://evil.example/"));
      assertTrue(gateway.permits(gateway.origin() + "/api"));
    }
  }

  @Test
  public void rejectsCrossTargetRedirectBeforeExposingItToTheBrowser() throws Exception {
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  head(input);
                  send(
                      output,
                      "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:3080/local\r\nContent-Length: 0\r\n\r\n");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(socket.getOutputStream(), request(gateway, authenticate(gateway), ""));
        String response = head(socket.getInputStream());
        assertTrue(response.startsWith("HTTP/1.1 502"));
        assertFalse(response.contains("Location:"));
      }
      pc.done();
    }
  }

  @Test
  public void streamsChunkedSseWithoutWaitingForPcToClose() throws Exception {
    CountDownLatch observed = new CountDownLatch(1);
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  head(input);
                  send(
                      output,
                      "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n5\r\ndata:\r\n");
                  assertTrue(observed.await(5, TimeUnit.SECONDS));
                  send(output, "0\r\nX-Trace: done\r\n\r\n");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(socket.getOutputStream(), request(gateway, authenticate(gateway), ""));
        String response = head(socket.getInputStream());
        assertTrue(response.contains("transfer-encoding: chunked"));
        assertEquals(
            "5\r\ndata:\r\n",
            new String(socket.getInputStream().readNBytes(10), StandardCharsets.US_ASCII));
        observed.countDown();
        assertEquals(
            "0\r\nX-Trace: done\r\n\r\n",
            new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
      }
      pc.done();
    }
  }

  @Test
  public void websocketHandshakesThenCarriesRawFramesInBothDirections() throws Exception {
    String key = "dGhlIHNhbXBsZSBub25jZQ==";
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  String req = head(input);
                  assertTrue(req.contains("Upgrade: websocket"));
                  String accept =
                      Base64.getEncoder()
                          .encodeToString(
                              MessageDigest.getInstance("SHA-1")
                                  .digest(
                                      (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                          .getBytes(StandardCharsets.US_ASCII)));
                  send(
                      output,
                      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: "
                          + accept
                          + "\r\n\r\n");
                  byte[] frame = {
                    (byte) 0x81, (byte) 0x82, 1, 2, 3, 4, (byte) ('h' ^ 1), (byte) ('i' ^ 2)
                  };
                  assertArrayEquals(frame, input.readNBytes(frame.length));
                  output.write(new byte[] {(byte) 0x81, 2, 'o', 'k'});
                  output.flush();
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(
            socket.getOutputStream(),
            request(
                gateway,
                authenticate(gateway),
                "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                    + key
                    + "\r\n"));
        assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 101"));
        socket
            .getOutputStream()
            .write(
                new byte[] {
                  (byte) 0x81, (byte) 0x82, 1, 2, 3, 4, (byte) ('h' ^ 1), (byte) ('i' ^ 2)
                });
        assertArrayEquals(
            new byte[] {(byte) 0x81, 2, 'o', 'k'}, socket.getInputStream().readNBytes(4));
      }
      pc.done();
    }
  }

  @Test
  public void falseWebsocketUpgradeIsRejected() throws Exception {
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  head(input);
                  send(
                      output,
                      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: invalid\r\n\r\n");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(socket.getOutputStream(), request(gateway, authenticate(gateway), ""));
        assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 502"));
      }
      pc.done();
    }
  }

  @Test
  public void chunkedUploadsKeepTheirFramingAndCheckedTrailers() throws Exception {
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  String req = head(input);
                  assertTrue(req.contains("transfer-encoding: chunked"));
                  String expectedBody = "3\r\nabc\r\n0\r\nX-Upload: done\r\n\r\n";
                  assertEquals(
                      expectedBody,
                      new String(
                          input.readNBytes(expectedBody.length()), StandardCharsets.US_ASCII));
                  send(output, "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(
            socket.getOutputStream(),
            request(gateway, authenticate(gateway), "Transfer-Encoding: chunked\r\n")
                    .replace("GET /api/session", "POST /api/upload")
                + "3\r\nabc\r\n0\r\nX-Upload: done\r\n\r\n");
        assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 201"));
      }
      pc.done();
    }
  }

  @Test
  public void gatewayCloseClosesLivePcAndBrowserStreams() throws Exception {
    CountDownLatch active = new CountDownLatch(1);
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  head(input);
                  send(output, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n");
                  active.countDown();
                  assertEquals(-1, input.read());
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(socket.getOutputStream(), request(gateway, authenticate(gateway), ""));
        assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 200"));
        assertTrue(active.await(5, TimeUnit.SECONDS));
        gateway.close();
        assertEquals(-1, socket.getInputStream().read());
      }
      pc.done();
    }
  }

  @Test
  public void invalidResponseTrailerIsNeverForwardedOrFollowedByASecondStatus() throws Exception {
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  head(input);
                  send(
                      output,
                      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nContent-Length: 10\r\n\r\n");
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        send(socket.getOutputStream(), request(gateway, authenticate(gateway), ""));
        assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 200"));
        String remaining =
            new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        assertFalse(remaining.contains("Content-Length"));
        assertFalse(remaining.contains("HTTP/1.1"));
        assertEquals("1\r\na\r\n0\r\n", remaining);
      }
      pc.done();
    }
  }

  @Test(timeout = 180_000)
  public void fiveBillionBytesTravelBothWaysWithBoundedBuffersAndLongLengths() throws Exception {
    final long size = 5_000_000_000L;
    try (Pc pc =
            new Pc(
                (input, output) -> {
                  String req = head(input);
                  assertTrue(req.contains("content-length: " + size));
                  assertEquals(size, consumePattern(input, size));
                  send(output, "HTTP/1.1 200 OK\r\nContent-Length: " + size + "\r\n\r\n");
                  producePattern(output, size);
                });
        RemoteHttpGateway gateway = new RemoteHttpGateway(pc::open, 42)) {
      try (Socket socket = browser(gateway)) {
        String cookie = authenticate(gateway);
        send(
            socket.getOutputStream(),
            request(gateway, cookie, "Content-Length: " + size + "\r\n")
                .replace("GET /api/session", "POST /api/upload"));
        producePattern(socket.getOutputStream(), size);
        assertTrue(head(socket.getInputStream()).contains("content-length: " + size));
        assertEquals(size, consumePattern(socket.getInputStream(), size));
        assertEquals(-1, socket.getInputStream().read());
      }
      pc.done();
    }
  }

  static void producePattern(OutputStream out, long size) throws IOException {
    byte[] buffer = new byte[65_536];
    Arrays.fill(buffer, (byte) 0x5a);
    long remaining = size;
    while (remaining > 0) {
      int count = (int) Math.min(buffer.length, remaining);
      out.write(buffer, 0, count);
      remaining -= count;
    }
    out.flush();
  }

  static long consumePattern(InputStream in, long size) throws IOException {
    byte[] buffer = new byte[65_536], expected = new byte[65_536];
    Arrays.fill(expected, (byte) 0x5a);
    long total = 0;
    while (total < size) {
      int count = in.read(buffer, 0, (int) Math.min(buffer.length, size - total));
      if (count < 0) throw new EOFException("5GB body truncated at " + total);
      assertEquals(-1, Arrays.mismatch(buffer, 0, count, expected, 0, count));
      total += count;
    }
    return total;
  }
}
