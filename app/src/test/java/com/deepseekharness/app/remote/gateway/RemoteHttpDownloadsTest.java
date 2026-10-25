package com.deepseekharness.app.remote.gateway;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Actual HTTP byte readback after fresh store/client instances, not a mocked range response. */
public final class RemoteHttpDownloadsTest {
  static final byte[] CONTENT = new byte[900_123];

  static {
    new Random(195).nextBytes(CONTENT);
  }

  static final class Host implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    final ExecutorService workers = Executors.newCachedThreadPool();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> rangeSeen = new AtomicReference<>(),
        ifRangeSeen = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    volatile String etag = "\"source-v1\"";
    volatile boolean wrongRange;

    Host() throws IOException {
      Thread accept =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try {
                    Socket socket = server.accept();
                    workers.execute(() -> serve(socket));
                  } catch (IOException stopped) {
                    if (!server.isClosed()) failure.compareAndSet(null, stopped);
                  } catch (RejectedExecutionException stopped) {
                    if (!server.isClosed()) failure.compareAndSet(null, stopped);
                  }
                }
              },
              "remote-download-http-test");
      accept.setDaemon(true);
      accept.start();
    }

    private void serve(Socket socket) {
      try (Socket client = socket) {
        client.setSoTimeout(5000);
        BufferedReader input =
            new BufferedReader(
                new InputStreamReader(
                    client.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals("GET /file?version=stable HTTP/1.1", input.readLine());
        Map<String, String> headers = new HashMap<>();
        int bytes = 0;
        for (String line; (line = input.readLine()) != null && !line.isEmpty(); ) {
          bytes += line.length() + 2;
          if (bytes > 32768) throw new IOException("TEST_HTTP_HEAD_LIMIT");
          int colon = line.indexOf(':');
          assertTrue(colon > 0);
          headers.put(
              line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int call = calls.incrementAndGet();
        String range = headers.get("range");
        rangeSeen.set(range);
        ifRangeSeen.set(headers.get("if-range"));
        String version = etag;
        long start = range == null ? 0 : Long.parseLong(range.substring(6, range.length() - 1));
        boolean matches = range != null && version.equals(headers.get("if-range"));
        StringBuilder response =
            new StringBuilder(matches ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        response.append("ETag: ").append(version).append("\r\n");
        if (matches) {
          response
              .append("Content-Range: bytes ")
              .append(wrongRange ? 0 : start)
              .append('-')
              .append(CONTENT.length - 1)
              .append('/')
              .append(CONTENT.length)
              .append("\r\n");
        } else start = 0;
        response
            .append("Content-Length: ")
            .append(CONTENT.length - start)
            .append("\r\nConnection: close\r\n\r\n");
        OutputStream output = client.getOutputStream();
        output.write(response.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        // The first actual TCP connection closes before its declared body finishes.
        // Later requests either carry the validated remaining range or a new full copy.
        int length = call == 1 ? 170_013 : CONTENT.length - (int) start;
        output.write(CONTENT, (int) start, length);
        output.flush();
      } catch (IOException clientDisconnected) {
        /* Expected on truncation and rejected-source responses. */
      } catch (Throwable invalid) {
        failure.compareAndSet(null, invalid);
      }
    }

    RemoteHttpDownloads.Source open(
        String peer, String host, String path, Map<String, String> headers) throws IOException {
      assertEquals("peer-195", peer);
      assertEquals("host-195", host);
      assertEquals("/file?version=stable", path);
      HttpURLConnection connection =
          (HttpURLConnection)
              new URL("http://127.0.0.1:" + server.getLocalPort() + path).openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setReadTimeout(5000);
      connection.setConnectTimeout(5000);
      for (var entry : headers.entrySet())
        connection.setRequestProperty(entry.getKey(), entry.getValue());
      int status = connection.getResponseCode();
      return new RemoteHttpDownloads.Source() {
        public int status() {
          return status;
        }

        public String header(String name) {
          return connection.getHeaderField(name);
        }

        public InputStream input() throws IOException {
          return connection.getInputStream();
        }

        public void close() {
          connection.disconnect();
        }
      };
    }

    public void close() throws IOException {
      server.close();
      workers.shutdownNow();
      Throwable invalid = failure.get();
      if (invalid != null) throw new AssertionError("Test HTTP server failed", invalid);
    }
  }

  static RemoteHttpDownloads.Job create(RemoteHttpDownloads store) throws IOException {
    return store.create(
        "peer-195",
        "host-195",
        "/file?version=stable",
        "report.bin",
        "application/octet-stream",
        "content://chosen/document/195");
  }

  static void interrupt(RemoteHttpDownloads store, RemoteHttpDownloads.Job job, Host host) {
    assertThrows(
        IOException.class, () -> store.run(job, "peer-195", "host-195", host::open, () -> false));
    assertEquals(170_013, job.bytes);
    assertFalse(job.state == RemoteHttpDownloads.State.COMPLETE);
  }

  static Path partial(Path root, String id) throws IOException {
    try (var entries = Files.walk(root)) {
      return entries
          .filter(
              p ->
                  p.getFileName().toString().equals("body.part")
                      && p.getParent().getFileName().toString().equals(id))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  public void reconnectResumesExactBytesUsingStrongEtagAndRangeWithoutOldPortOrCookie()
      throws Exception {
    Path root = Files.createTempDirectory("remote-http-resume-");
    try (Host host = new Host()) {
      RemoteHttpDownloads initial = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(initial);
      interrupt(initial, job, host);
      assertEquals(RemoteHttpDownloads.State.PAUSED, job.state);
      assertTrue(job.canResume());
      RemoteHttpDownloads afterClose = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job reopened = afterClose.list("peer-195").get(0);
      afterClose.run(reopened, "peer-195", "host-195", host::open, () -> false);
      assertEquals("bytes=170013-", host.rangeSeen.get());
      assertEquals("\"source-v1\"", host.ifRangeSeen.get());
      assertEquals(RemoteHttpDownloads.State.COMPLETE, reopened.state);
      ByteArrayOutputStream destination = new ByteArrayOutputStream();
      afterClose.publish(reopened, destination);
      assertArrayEquals(CONTENT, destination.toByteArray());
      String metadata =
          Files.readString(partial(root, job.id).getParent().resolve("download.properties"));
      assertFalse(metadata.contains("127.0.0.1"));
      assertFalse(metadata.contains("Cookie"));
      assertFalse(metadata.contains("__dsha_remote"));
    }
  }

  @Test
  public void changedEtagPreservesOriginalPartialAndRequiresExplicitNewJob() throws Exception {
    Path root = Files.createTempDirectory("remote-http-changed-");
    try (Host host = new Host()) {
      RemoteHttpDownloads store = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(store);
      interrupt(store, job, host);
      byte[] original = Files.readAllBytes(partial(root, job.id));
      String originalHash = job.sha256;
      host.etag = "\"source-v2\"";
      assertThrows(
          IOException.class, () -> store.run(job, "peer-195", "host-195", host::open, () -> false));
      assertEquals(RemoteHttpDownloads.State.SOURCE_CHANGED, job.state);
      assertEquals(originalHash, job.sha256);
      assertArrayEquals(original, Files.readAllBytes(partial(root, job.id)));
      RemoteHttpDownloads.Job restart = store.restart(job, "host-195");
      assertNotEquals(job.id, restart.id);
      store.run(restart, "peer-195", "host-195", host::open, () -> false);
      assertNull(host.rangeSeen.get());
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      store.publish(restart, output);
      assertArrayEquals(CONTENT, output.toByteArray());
      assertArrayEquals(original, Files.readAllBytes(partial(root, job.id)));
    }
  }

  @Test
  public void explicitBrowserCloseCheckpointCanResumeFromANewConnection() throws Exception {
    Path root = Files.createTempDirectory("remote-http-close-");
    try (Host host = new Host()) {
      RemoteHttpDownloads store = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(store);
      AtomicBoolean closing = new AtomicBoolean();
      assertThrows(
          IOException.class,
          () ->
              store.run(
                  job,
                  "peer-195",
                  "host-195",
                  (peer, instance, path, headers) -> {
                    RemoteHttpDownloads.Source source = host.open(peer, instance, path, headers);
                    return new RemoteHttpDownloads.Source() {
                      public int status() {
                        return source.status();
                      }

                      public String header(String name) {
                        return source.header(name);
                      }

                      public InputStream input() throws IOException {
                        return new FilterInputStream(source.input()) {
                          long read;

                          public int read(byte[] buffer, int offset, int count) throws IOException {
                            int n = super.read(buffer, offset, count);
                            if (n > 0 && ((read += n) > 100_000)) closing.set(true);
                            return n;
                          }
                        };
                      }

                      public void close() throws IOException {
                        source.close();
                      }
                    };
                  },
                  closing::get));
      assertTrue(job.bytes > 100_000 && job.bytes < CONTENT.length);
      long checkpoint = job.bytes;
      RemoteHttpDownloads reopened = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job record = reopened.list("peer-195").get(0);
      reopened.run(record, "peer-195", "host-195", host::open, () -> false);
      assertEquals("bytes=" + checkpoint + "-", host.rangeSeen.get());
      ByteArrayOutputStream destination = new ByteArrayOutputStream();
      reopened.publish(record, destination);
      assertArrayEquals(CONTENT, destination.toByteArray());
    }
  }

  @Test
  public void incorrectRangeCannotConcatenateEvenWhenEtagMatches() throws Exception {
    Path root = Files.createTempDirectory("remote-http-range-");
    try (Host host = new Host()) {
      RemoteHttpDownloads store = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(store);
      interrupt(store, job, host);
      byte[] original = Files.readAllBytes(partial(root, job.id));
      host.wrongRange = true;
      assertThrows(
          IOException.class, () -> store.run(job, "peer-195", "host-195", host::open, () -> false));
      assertEquals(RemoteHttpDownloads.State.SOURCE_CHANGED, job.state);
      assertArrayEquals(original, Files.readAllBytes(partial(root, job.id)));
    }
  }

  @Test
  public void weakEtagAndDifferentHostInstanceNeverResume() throws Exception {
    Path root = Files.createTempDirectory("remote-http-validator-");
    try (Host host = new Host()) {
      host.etag = "W/\"weak\"";
      RemoteHttpDownloads store = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(store);
      interrupt(store, job, host);
      assertEquals(RemoteHttpDownloads.State.NO_RESUME, job.state);
      assertFalse(job.canResume());
      int calls = host.calls.get();
      assertThrows(
          IOException.class, () -> store.run(job, "peer-195", "host-195", host::open, () -> false));
      assertEquals(calls, host.calls.get());
      assertThrows(
          IOException.class,
          () -> store.run(job, "peer-195", "new-host-instance", host::open, () -> false));
      assertEquals(calls, host.calls.get());
    }
  }

  @Test
  public void mutatedPrivatePartialAndUnknownCrashSuffixCannotBeResumedOrPublished()
      throws Exception {
    Path root = Files.createTempDirectory("remote-http-checkpoint-");
    try (Host host = new Host()) {
      RemoteHttpDownloads store = new RemoteHttpDownloads(root);
      RemoteHttpDownloads.Job job = create(store);
      interrupt(store, job, host);
      Path part = partial(root, job.id);
      byte[] bytes = Files.readAllBytes(part);
      bytes[0] ^= 1;
      Files.write(part, bytes);
      int calls = host.calls.get();
      assertThrows(
          IOException.class, () -> store.run(job, "peer-195", "host-195", host::open, () -> false));
      assertEquals(RemoteHttpDownloads.State.UNKNOWN_PARTIAL, job.state);
      assertEquals(calls, host.calls.get());
      assertThrows(IOException.class, () -> store.publish(job, new ByteArrayOutputStream()));
      assertArrayEquals(bytes, Files.readAllBytes(part));
    }
  }
}
