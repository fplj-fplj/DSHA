package com.deepseekharness.app.remote.gateway;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** One-session loopback HTTP gateway. This origin is REMOTE, even though its socket is loopback.
 * No local runtime, native bridge, filesystem path or credential is exposed by this class. */
public final class RemoteHttpGateway implements AutoCloseable {
  public interface Channel extends AutoCloseable {
    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    void close() throws IOException;
  }

  public interface Channels {
    Channel open() throws IOException;
  }

  private static final int HEAD_LIMIT = 65_536;
  private static final String CAP_COOKIE = "DSHA_REMOTE_GATEWAY";
  private final Channels channels;
  private final long generation;
  private final ServerSocket listener;
  private final String capability;
  private final String authority;
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          16,
          r -> {
            Thread t = new Thread(r, "remote-http-stream");
            t.setDaemon(true);
            return t;
          });
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Set<Channel> openChannels = ConcurrentHashMap.newKeySet();
  private final Semaphore slots = new Semaphore(8);
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "remote-http-deadline");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean closed;

  public RemoteHttpGateway(Channels channels, long generation) throws IOException {
    if (generation <= 0) throw new IllegalArgumentException("generation");
    this.channels = Objects.requireNonNull(channels);
    this.generation = generation;
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    capability = hex(random);
    listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
    authority = "127.0.0.1:" + listener.getLocalPort();
    Thread accept = new Thread(this::accept, "remote-http-accept");
    accept.setDaemon(true);
    accept.start();
  }

  public String origin() {
    return "http://" + authority;
  }

  public RemoteWebOrigin classification() {
    return RemoteWebOrigin.REMOTE;
  }

  String nativeCookie() {
    return CAP_COOKIE + "=" + capability;
  }

  /** Native navigation only. Capability is never forwarded to the PC. */
  public String entryUrl() {
    return origin() + "/__dsha_remote/" + capability;
  }

  public boolean permits(String url) {
    try {
      URI uri = new URI(url);
      return "http".equals(uri.getScheme())
          && authority.equals(uri.getRawAuthority())
          && uri.getRawUserInfo() == null;
    } catch (Exception e) {
      return false;
    }
  }

  private void accept() {
    while (!closed) {
      try {
        Socket socket = listener.accept();
        if (!slots.tryAcquire()) {
          socket.close();
          continue;
        }
        sockets.add(socket);
        workers.execute(() -> serve(socket));
      } catch (IOException | RejectedExecutionException stopped) {
        if (!closed) close();
      }
    }
  }

  private void serve(Socket socket) {
    Channel channel = null;
    Future<?> upload = null;
    boolean finalHeadersSent = false;
    try {
      socket.setSoTimeout(15_000);
      InputStream input = socket.getInputStream();
      OutputStream output = socket.getOutputStream();
      Head request = Head.read(input);
      String[] start = request.first.split(" ", -1);
      if (start.length != 3 || !"HTTP/1.1".equals(start[2])) throw new Rejection(400);
      String method = start[0], target = start[1];
      if (!List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(method))
        throw new Rejection(405);
      if (!target.startsWith("/")
          || target.startsWith("//")
          || target.indexOf('#') >= 0
          || target.indexOf('\\') >= 0
          || target.indexOf('\0') >= 0) throw new Rejection(400);
      if (!authority.equals(request.one("host"))) throw new Rejection(403);
      String origin = request.one("origin");
      if (origin != null && !origin().equals(origin)) throw new Rejection(403);
      if (request.one("expect") != null) throw new Rejection(417);
      Body body = Body.of(request, false, false);
      if (target.equals("/__dsha_remote/" + capability) && "GET".equals(method)) {
        write(
            output,
            "HTTP/1.1 303 See Other\r\nLocation: /\r\nSet-Cookie: "
                + CAP_COOKIE
                + "="
                + capability
                + "; Path=/; HttpOnly; SameSite=Strict\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
      }
      if (!authenticated(request.all("cookie"))) throw new Rejection(403);
      boolean websocket = request.isWebSocketRequest();
      if (request.one("upgrade") != null && !websocket) throw new Rejection(400);
      if (websocket && !body.empty()) throw new Rejection(400);
      socket.setSoTimeout(0);
      channel = channels.open();
      openChannels.add(channel);
      if (closed) throw new IOException("REMOTE_GATEWAY_CLOSED");
      InputStream remoteInput = channel.input();
      OutputStream remoteOutput = channel.output();
      byte[] metadata =
          ("{\"v\":1,\"service\":\"dsh-web\",\"generation\":"
                  + generation
                  + ",\"targetId\":\"default\"}")
              .getBytes(StandardCharsets.UTF_8);
      new DataOutputStream(remoteOutput).writeInt(metadata.length);
      remoteOutput.write(metadata);
      request.writeRequest(remoteOutput, method + " " + target + " HTTP/1.1", websocket);
      remoteOutput.flush();
      final Channel currentChannel = channel;
      final java.util.concurrent.atomic.AtomicBoolean finalResponse =
          new java.util.concurrent.atomic.AtomicBoolean();
      Runnable responseDeadline =
          () -> {
            try {
              deadlines.schedule(
                  () -> {
                    if (!finalResponse.get())
                      try {
                        currentChannel.close();
                      } catch (IOException ignored) {
                      }
                  },
                  45,
                  TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
            }
          };
      if (!body.empty()) {
        upload =
            workers.submit(
                () -> {
                  try {
                    body.copy(input, remoteOutput);
                    remoteOutput.flush();
                    responseDeadline.run();
                  } catch (IOException failure) {
                    try {
                      currentChannel.close();
                    } catch (IOException ignored) {
                    }
                  }
                });
      } else responseDeadline.run();
      Head response;
      int code;
      int interims = 0;
      do {
        response = Head.read(remoteInput);
        code = response.status();
        if (code >= 100 && code < 200 && code != 101) {
          if (++interims > 8) throw new Rejection(502);
          response.writeResponse(output, false, origin(), null);
          output.flush();
        }
      } while (code >= 100 && code < 200 && code != 101);
      finalResponse.set(true);
      if (code == 101) {
        if (!websocket || !response.validWebSocketResponse(request.one("sec-websocket-key")))
          throw new Rejection(502);
        response.writeResponse(output, true, origin(), null);
        output.flush();
        finalHeadersSent = true;
        upload =
            workers.submit(
                () -> {
                  try {
                    copyUntilEof(input, remoteOutput);
                  } catch (IOException ignored) {
                  } finally {
                    try {
                      currentChannel.close();
                    } catch (IOException ignored) {
                    }
                  }
                });
        copyUntilEof(remoteInput, output);
      } else {
        Body responseBody =
            Body.of(response, true, "HEAD".equals(method) || code == 204 || code == 304);
        String location = response.one("location");
        if (location != null && !safeLocation(location)) throw new Rejection(502);
        response.writeResponse(output, false, origin(), contentPolicy());
        output.flush();
        finalHeadersSent = true;
        responseBody.copy(remoteInput, output);
        output.flush();
      }
    } catch (Rejection rejection) {
      try {
        if (!finalHeadersSent)
          write(
              socket.getOutputStream(),
              "HTTP/1.1 "
                  + rejection.code
                  + " Remote gateway rejected request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
      } catch (IOException ignored) {
      }
    } catch (IOException | RuntimeException failure) {
      try {
        if (!finalHeadersSent)
          write(
              socket.getOutputStream(),
              "HTTP/1.1 502 Remote session unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
      } catch (IOException ignored) {
      }
    } finally {
      if (upload != null) upload.cancel(true);
      if (channel != null) {
        openChannels.remove(channel);
        try {
          channel.close();
        } catch (IOException ignored) {
        }
      }
      sockets.remove(socket);
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      slots.release();
    }
  }

  private boolean authenticated(List<String> cookies) {
    for (String value : cookies)
      for (String cookie : value.split(";")) {
        String[] pair = cookie.trim().split("=", 2);
        if (pair.length == 2
            && CAP_COOKIE.equals(pair[0])
            && MessageDigest.isEqual(
                capability.getBytes(StandardCharsets.US_ASCII),
                pair[1].getBytes(StandardCharsets.US_ASCII))) return true;
      }
    return false;
  }

  private boolean safeLocation(String value) {
    if (value.startsWith("/") && !value.startsWith("//") && value.indexOf('\\') < 0) return true;
    try {
      URI uri = new URI(value);
      return !uri.isAbsolute() && uri.getRawAuthority() == null && value.indexOf('\\') < 0
          || permits(value);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private String contentPolicy() {
    return "default-src 'self'; connect-src 'self' ws://"
        + authority
        + "; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; "
        + "font-src 'self' data:; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'";
  }

  @Override
  public void close() {
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    for (Socket socket : sockets)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    for (Channel channel : openChannels)
      try {
        channel.close();
      } catch (IOException ignored) {
      }
    workers.shutdownNow();
    deadlines.shutdownNow();
  }

  private static void write(OutputStream out, String value) throws IOException {
    out.write(value.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  static long copyLength(InputStream in, OutputStream out, long length) throws IOException {
    byte[] buffer = new byte[65_536];
    long remaining = length;
    while (remaining > 0) {
      int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (count < 0) throw new EOFException("REMOTE_BODY_TRUNCATED");
      if (count == 0) continue;
      out.write(buffer, 0, count);
      remaining -= count;
    }
    return length;
  }

  static void copyUntilEof(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[65_536];
    int count;
    while ((count = in.read(buffer)) >= 0)
      if (count > 0) {
        out.write(buffer, 0, count);
        out.flush();
      }
  }

  private static final class Rejection extends IOException {
    final int code;

    Rejection(int code) {
      this.code = code;
    }
  }

  private static String line(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < limit; i++) {
      int b = in.read();
      if (b < 0) throw new EOFException("REMOTE_HEAD_TRUNCATED");
      if (b == '\r') {
        if (in.read() != '\n') throw new Rejection(400);
        return bytes.toString("ISO-8859-1");
      }
      if (b == '\n' || b == 0 || (b < 32 && b != '\t') || b == 127) throw new Rejection(400);
      bytes.write(b);
    }
    throw new Rejection(431);
  }

  private static final class Head {
    final String first;
    final List<String[]> headers = new ArrayList<>();

    Head(String first) {
      this.first = first;
    }

    static Head read(InputStream input) throws IOException {
      InputStream bounded =
          new FilterInputStream(input) {
            private long deadline;

            @Override
            public int read() throws IOException {
              if (deadline != 0 && System.nanoTime() > deadline) throw new Rejection(408);
              int value = super.read();
              // Waiting for a response's first byte includes the legitimate streaming upload.
              // Its header budget starts when the response actually begins, not before 5 GB
              // uploads.
              if (deadline == 0) deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
              else if (System.nanoTime() > deadline) throw new Rejection(408);
              return value;
            }
          };
      Head head = new Head(line(bounded, HEAD_LIMIT));
      int size = head.first.length() + 2;
      for (; ; ) {
        String value = line(bounded, HEAD_LIMIT - size);
        size += value.length() + 2;
        if (value.isEmpty()) break;
        int colon = value.indexOf(':');
        if (colon < 1 || !value.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
          throw new Rejection(400);
        head.headers.add(
            new String[] {
              value.substring(0, colon).toLowerCase(Locale.ROOT), value.substring(colon + 1).trim()
            });
      }
      return head;
    }

    List<String> all(String name) {
      List<String> result = new ArrayList<>();
      for (String[] header : headers) if (header[0].equals(name)) result.add(header[1]);
      return result;
    }

    String one(String name) throws IOException {
      List<String> values = all(name);
      if (values.size() > 1) throw new Rejection(400);
      return values.isEmpty() ? null : values.get(0);
    }

    boolean token(String name, String value) throws IOException {
      String actual = one(name);
      return actual != null
          && Arrays.stream(actual.split(",")).anyMatch(v -> value.equalsIgnoreCase(v.trim()));
    }

    int status() throws IOException {
      if (!first.matches("HTTP/1\\.[01] [1-5][0-9][0-9]( .*)?")) throw new Rejection(502);
      return Integer.parseInt(first.substring(9, 12));
    }

    boolean isWebSocketRequest() throws IOException {
      String key = one("sec-websocket-key");
      if (!"GET".equals(first.split(" ")[0])
          || !token("connection", "upgrade")
          || !"websocket".equalsIgnoreCase(one("upgrade"))
          || !"13".equals(one("sec-websocket-version"))
          || key == null) return false;
      try {
        return Base64.getDecoder().decode(key).length == 16;
      } catch (IllegalArgumentException bad) {
        return false;
      }
    }

    boolean validWebSocketResponse(String key) throws IOException {
      try {
        String expected =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
        return token("connection", "upgrade")
            && "websocket".equalsIgnoreCase(one("upgrade"))
            && expected.equals(one("sec-websocket-accept"));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IOException(impossible);
      }
    }

    Set<String> hopHeaders() throws IOException {
      Set<String> hop =
          new HashSet<>(
              List.of(
                  "connection",
                  "proxy-connection",
                  "keep-alive",
                  "proxy-authorization",
                  "proxy-authenticate"));
      String connection = one("connection");
      if (connection != null)
        for (String token : connection.split(",")) {
          String name = token.trim().toLowerCase(Locale.ROOT);
          // Removing framing nominated by Connection would desynchronize the raw body.
          if (List.of("content-length", "transfer-encoding", "host", "cookie", "origin")
              .contains(name)) throw new Rejection(400);
          hop.add(name);
        }
      return hop;
    }

    void writeRequest(OutputStream out, String first, boolean ws) throws IOException {
      write(out, first + "\r\n");
      Set<String> hop = hopHeaders();
      for (String[] header : headers) {
        if (hop.contains(header[0])
            || header[0].equals("host")
            || header[0].equals("origin")
            || header[0].equals("upgrade")
            || header[0].equals("authorization")
            || header[0].startsWith("proxy-")) continue;
        String value = header[1];
        if (header[0].equals("cookie")) {
          StringJoiner cookies = new StringJoiner("; ");
          for (String cookie : value.split(";"))
            if (!cookie.trim().startsWith(CAP_COOKIE + "=")) cookies.add(cookie.trim());
          value = cookies.toString();
          if (value.isEmpty()) continue;
        }
        write(out, header[0] + ": " + value + "\r\n");
      }
      // The PC endpoint replaces this logical service origin with the selected DSH target.
      write(out, "Host: 127.0.0.1\r\n");
      if (one("origin") != null) write(out, "Origin: http://127.0.0.1\r\n");
      write(
          out,
          ws ? "Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n" : "Connection: close\r\n\r\n");
    }

    void writeResponse(OutputStream out, boolean ws, String origin, String csp) throws IOException {
      write(out, first + "\r\n");
      Set<String> hop = hopHeaders();
      for (String[] header : headers) {
        if (hop.contains(header[0])
            || header[0].equals("upgrade")
            || header[0].startsWith("proxy-")) continue;
        if (header[0].equals("set-cookie") && header[1].startsWith(CAP_COOKIE + "=")) continue;
        write(out, header[0] + ": " + header[1] + "\r\n");
      }
      if (csp != null)
        write(
            out,
            "Content-Security-Policy: "
                + csp
                + "\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n");
      write(
          out,
          ws ? "Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n" : "Connection: close\r\n\r\n");
    }
  }

  private static final class Body {
    final long length;
    final boolean chunked;

    Body(long length, boolean chunked) {
      this.length = length;
      this.chunked = chunked;
    }

    static Body of(Head head, boolean response, boolean noBody) throws IOException {
      String transfer = head.one("transfer-encoding"), length = head.one("content-length");
      if (transfer != null && length != null) throw new Rejection(400);
      if (transfer != null && !"chunked".equalsIgnoreCase(transfer)) throw new Rejection(400);
      long count = response ? -1 : 0;
      if (length != null) {
        if (!length.matches("[0-9]+")) throw new Rejection(400);
        try {
          count = Long.parseLong(length);
        } catch (NumberFormatException overflow) {
          throw new Rejection(400);
        }
      }
      return noBody ? new Body(0, false) : new Body(count, transfer != null);
    }

    boolean empty() {
      return !chunked && length == 0;
    }

    void copy(InputStream input, OutputStream output) throws IOException {
      if (!chunked) {
        if (length >= 0) copyLength(input, output, length);
        else copyUntilEof(input, output);
        return;
      }
      for (; ; ) {
        String size = line(input, 4096);
        String raw = size.split(";", 2)[0];
        if (!raw.matches("[0-9a-fA-F]+")) throw new Rejection(400);
        long count;
        try {
          count = Long.parseLong(raw, 16);
        } catch (NumberFormatException overflow) {
          throw new Rejection(400);
        }
        write(output, size + "\r\n");
        if (count == 0) {
          int total = 0;
          for (; ; ) {
            String trailer = line(input, HEAD_LIMIT - total);
            total += trailer.length() + 2;
            if (trailer.isEmpty()) {
              write(output, "\r\n");
              return;
            }
            int colon = trailer.indexOf(':');
            if (colon < 1 || !trailer.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
              throw new Rejection(400);
            String name = trailer.substring(0, colon).toLowerCase(Locale.ROOT);
            if (List.of(
                    "content-length",
                    "transfer-encoding",
                    "host",
                    "cookie",
                    "set-cookie",
                    "authorization",
                    "proxy-authorization",
                    "proxy-authenticate",
                    "connection",
                    "upgrade",
                    "trailer",
                    "content-encoding",
                    "content-type",
                    "content-range",
                    "location",
                    "cache-control",
                    "vary",
                    "content-security-policy",
                    "www-authenticate")
                .contains(name)) throw new Rejection(400);
            write(output, trailer + "\r\n");
          }
        }
        copyLength(input, output, count);
        if (input.read() != '\r' || input.read() != '\n') throw new Rejection(400);
        write(output, "\r\n");
        output.flush();
      }
    }
  }
}
