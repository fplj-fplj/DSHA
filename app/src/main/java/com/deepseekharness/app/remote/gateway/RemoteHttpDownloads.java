package com.deepseekharness.app.remote.gateway;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Durable remote HTTP downloads. A resume never uses an old gateway URL, cookie or connection. */
public final class RemoteHttpDownloads {
  public interface Source extends AutoCloseable {
    int status();

    String header(String name);

    InputStream input() throws IOException;

    void close() throws IOException;
  }

  public interface Sources {
    Source open(String peerId, String hostInstance, String path, Map<String, String> headers)
        throws IOException;
  }

  public enum State {
    NEW,
    PAUSED,
    COMPLETE,
    SOURCE_CHANGED,
    UNKNOWN_PARTIAL,
    NO_RESUME
  }

  public static final class Job {
    public final String id, peerId, hostInstance, path, name, mime, destination;
    public String etag = "", sha256 = "";
    public long bytes, total = -1;
    public State state = State.NEW;
    private final Path directory;

    private Job(Path directory, Properties p) throws IOException {
      this.directory = directory;
      id = required(p, "id");
      peerId = required(p, "peer");
      hostInstance = required(p, "host");
      path = required(p, "path");
      name = required(p, "name");
      mime = required(p, "mime");
      destination = p.getProperty("destination", "");
      if (!id.matches("[0-9a-f]{32}")
          || !validPath(path)
          || !directory.getFileName().toString().equals(id))
        throw new IOException("REMOTE_DOWNLOAD_RECORD");
      try {
        bytes = Long.parseLong(p.getProperty("bytes", "0"));
        total = Long.parseLong(p.getProperty("total", "-1"));
        state = State.valueOf(p.getProperty("state", "NEW"));
      } catch (RuntimeException invalid) {
        throw new IOException("REMOTE_DOWNLOAD_RECORD");
      }
      etag = p.getProperty("etag", "");
      sha256 = p.getProperty("sha256", "");
      if (bytes < 0
          || bytes > RemoteFileBytes.MAX_BYTES
          || total < -1
          || total > RemoteFileBytes.MAX_BYTES) throw new IOException("REMOTE_DOWNLOAD_RECORD");
    }

    public boolean canResume() {
      return state != State.COMPLETE
          && state != State.SOURCE_CHANGED
          && state != State.UNKNOWN_PARTIAL
          && strongEtag(etag);
    }
  }

  private final Path root;

  public RemoteHttpDownloads(Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    if (Files.isSymbolicLink(this.root)) throw new IOException("REMOTE_DOWNLOAD_ROOT");
  }

  public Job create(
      String peerId, String hostInstance, String path, String name, String mime, String destination)
      throws IOException {
    if (peerId == null
        || hostInstance == null
        || peerId.isBlank()
        || hostInstance.isBlank()
        || !validPath(path)
        || name == null
        || name.isBlank()
        || name.length() > 240
        || mime == null
        || mime.length() > 160
        || destination != null && !destination.isEmpty() && !destination.startsWith("content://"))
      throw new IOException("REMOTE_DOWNLOAD_SOURCE");
    Path peer = peerDirectory(peerId);
    String id = UUID.randomUUID().toString().replace("-", "");
    Path directory = Files.createDirectory(peer.resolve(id));
    Properties p = new Properties();
    p.setProperty("id", id);
    p.setProperty("peer", peerId);
    p.setProperty("host", hostInstance);
    p.setProperty("path", path);
    p.setProperty("name", name);
    p.setProperty("mime", mime);
    p.setProperty("destination", destination == null ? "" : destination);
    Job job = new Job(directory, p);
    save(job);
    return job;
  }

  public Job restart(Job original, String currentHostInstance) throws IOException {
    // Original record and bytes remain available, even when the source or checkpoint changed.
    return create(
        original.peerId,
        currentHostInstance,
        original.path,
        original.name,
        original.mime,
        original.destination);
  }

  public List<Job> list(String peerId) throws IOException {
    List<Job> jobs = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(peerDirectory(peerId))) {
      for (Path child : children)
        if (child.getFileName().toString().matches("[0-9a-f]{32}")
            && !Files.isSymbolicLink(child)) {
          try {
            Path record = child.resolve("download.properties");
            if (Files.isSymbolicLink(record) || Files.size(record) > 32_768) continue;
            Properties p = new Properties();
            try (InputStream input = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) {
              p.load(input);
            }
            Job job = new Job(child, p);
            if (peerId.equals(job.peerId)) jobs.add(job);
          } catch (IOException | RuntimeException invalid) {
            /* Preserve unreadable records, never delete them. */
          }
        }
    }
    jobs.sort(Comparator.comparing(j -> j.id));
    return jobs;
  }

  public void run(
      Job job,
      String authenticatedPeer,
      String authenticatedHost,
      Sources sources,
      BooleanSupplier cancelled)
      throws IOException {
    if (!job.peerId.equals(authenticatedPeer) || !job.hostInstance.equals(authenticatedHost)) {
      job.state = State.SOURCE_CHANGED;
      save(job);
      throw new IOException("REMOTE_DOWNLOAD_SOURCE_CHANGED");
    }
    Path part = job.directory.resolve("body.part");
    if (Files.isSymbolicLink(part) || Files.isSymbolicLink(job.directory))
      throw new IOException("REMOTE_DOWNLOAD_LINK");
    try (FileChannel lockFile =
            FileChannel.open(
                job.directory.resolve("job.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lock = lockFile.tryLock()) {
      if (lock == null) throw new IOException("REMOTE_DOWNLOAD_BUSY");
      long offset = Files.exists(part, LinkOption.NOFOLLOW_LINKS) ? Files.size(part) : 0;
      MessageDigest digest = sha();
      if (offset != job.bytes
          || offset > 0
              && (!job.sha256.matches("[0-9a-f]{64}") || !job.sha256.equals(hash(part, digest)))) {
        job.state = State.UNKNOWN_PARTIAL;
        save(job);
        throw new IOException("REMOTE_DOWNLOAD_CHECKPOINT_UNKNOWN");
      }
      if (offset == 0) digest.reset();
      if (job.state == State.COMPLETE) return;
      if (job.state == State.SOURCE_CHANGED || job.state == State.UNKNOWN_PARTIAL)
        throw new IOException("REMOTE_DOWNLOAD_RESTART_REQUIRED");
      if (offset > 0 && !strongEtag(job.etag)) {
        job.state = State.NO_RESUME;
        save(job);
        throw new IOException("REMOTE_DOWNLOAD_NO_VALIDATOR");
      }
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Accept-Encoding", "identity");
      if (offset > 0) {
        headers.put("Range", "bytes=" + offset + "-");
        headers.put("If-Range", job.etag);
      }
      try (Source source =
          sources.open(
              job.peerId, job.hostInstance, job.path, Collections.unmodifiableMap(headers))) {
        if (cancelled.getAsBoolean()) throw new IOException("REMOTE_DOWNLOAD_PAUSED");
        String encoding = source.header("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity"))
          throw new IOException("REMOTE_DOWNLOAD_ENCODING");
        String etag = source.header("ETag");
        long count = contentLength(source.header("Content-Length"));
        if (offset > 0) {
          if (source.status() == 416
              && offset == job.total
              && job.etag.equals(etag)
              && ("bytes */" + offset).equals(source.header("Content-Range"))) {
            job.state = State.COMPLETE;
            save(job);
            return;
          }
          Matcher range =
              Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                  .matcher(String.valueOf(source.header("Content-Range")));
          boolean valid =
              source.status() == 206
                  && job.etag.equals(etag)
                  && strongEtag(etag)
                  && range.matches();
          long start = -1, end = -1, total = -1;
          if (valid)
            try {
              start = Long.parseLong(range.group(1));
              end = Long.parseLong(range.group(2));
              total = Long.parseLong(range.group(3));
            } catch (NumberFormatException overflow) {
              valid = false;
            }
          valid =
              valid
                  && start == offset
                  && end >= start
                  && total > end
                  && total <= RemoteFileBytes.MAX_BYTES
                  && end == total - 1
                  && (job.total == -1 || job.total == total)
                  && (count == -1 || count == total - offset);
          if (!valid) {
            job.state = State.SOURCE_CHANGED;
            save(job);
            throw new IOException("REMOTE_DOWNLOAD_SOURCE_CHANGED");
          }
          job.total = total;
        } else {
          if (source.status() != 200) throw new IOException("REMOTE_DOWNLOAD_STATUS");
          job.etag = strongEtag(etag) ? etag : "";
          job.total = count;
        }
        if (job.total > RemoteFileBytes.MAX_BYTES) throw new IOException("REMOTE_DOWNLOAD_LIMIT");
        job.state = State.PAUSED;
        save(job);
        long written = offset, checkpoint = offset;
        Set<OpenOption> options =
            new HashSet<>(
                List.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS));
        options.add(offset > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        try (InputStream input = source.input();
            FileChannel outputFile = FileChannel.open(part, options);
            OutputStream output = Channels.newOutputStream(outputFile)) {
          byte[] buffer = new byte[65_536];
          try {
            for (; ; ) {
              if (cancelled.getAsBoolean()) throw new IOException("REMOTE_DOWNLOAD_PAUSED");
              int n = input.read(buffer);
              if (n < 0) break;
              if (n == 0) continue;
              if (written > RemoteFileBytes.MAX_BYTES - n
                  || job.total >= 0 && written > job.total - n)
                throw new IOException("REMOTE_DOWNLOAD_LIMIT");
              output.write(buffer, 0, n);
              digest.update(buffer, 0, n);
              written += n;
              if (written - checkpoint >= 8L * 1024 * 1024) {
                checkpoint(job, outputFile, digest, written);
                checkpoint = written;
              }
            }
            if (job.total >= 0 && written != job.total)
              throw new EOFException("REMOTE_DOWNLOAD_TRUNCATED");
            checkpoint(job, outputFile, digest, written);
            job.total = written;
            job.state = State.COMPLETE;
            save(job);
          } catch (IOException | RuntimeException interrupted) {
            if (Files.size(part) == written) checkpoint(job, outputFile, digest, written);
            else {
              job.state = State.UNKNOWN_PARTIAL;
              save(job);
            }
            if (job.state != State.UNKNOWN_PARTIAL) {
              job.state = strongEtag(job.etag) ? State.PAUSED : State.NO_RESUME;
              save(job);
            }
            throw interrupted;
          }
        }
      }
    } catch (OverlappingFileLockException busy) {
      throw new IOException("REMOTE_DOWNLOAD_BUSY");
    }
  }

  /** Revalidates private stage immediately before user-selected publication. */
  public void verify(Job job) throws IOException {
    Path part = job.directory.resolve("body.part");
    if (job.state != State.COMPLETE) throw new IOException("REMOTE_DOWNLOAD_NOT_COMPLETE");
    if (Files.isSymbolicLink(part)
        || !Files.exists(part, LinkOption.NOFOLLOW_LINKS)
        || Files.size(part) != job.bytes
        || !job.sha256.equals(hash(part, sha()))) {
      job.state = State.UNKNOWN_PARTIAL;
      save(job);
      throw new IOException("REMOTE_DOWNLOAD_NOT_COMPLETE");
    }
  }

  public void publish(Job job, OutputStream destination) throws IOException {
    verify(job);
    Path part = job.directory.resolve("body.part");
    MessageDigest copied = sha();
    long written = 0;
    try (InputStream input = Files.newInputStream(part, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[65_536];
      int count;
      while ((count = input.read(buffer)) >= 0)
        if (count > 0) {
          destination.write(buffer, 0, count);
          copied.update(buffer, 0, count);
          written += count;
        }
      if (written != job.bytes || !job.sha256.equals(hex(copied.digest()))) {
        job.state = State.UNKNOWN_PARTIAL;
        save(job);
        throw new IOException("REMOTE_DOWNLOAD_PUBLICATION_CHANGED");
      }
      destination.flush();
    }
  }

  private static void checkpoint(Job job, FileChannel output, MessageDigest digest, long written)
      throws IOException {
    output.force(true);
    job.bytes = written;
    try {
      job.sha256 = hex(((MessageDigest) digest.clone()).digest());
    } catch (CloneNotSupportedException unavailable) {
      throw new IOException("REMOTE_DOWNLOAD_DIGEST_UNAVAILABLE");
    }
    save(job);
  }

  private Path peerDirectory(String peer) throws IOException {
    Path directory =
        root.resolve(hex(sha().digest(peer.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory)) throw new IOException("REMOTE_DOWNLOAD_LINK");
    return directory;
  }

  private static boolean validPath(String path) {
    return path != null
        && path.length() <= 8192
        && path.startsWith("/")
        && !path.startsWith("//")
        && !path.startsWith("/__dsha_remote/")
        && path.indexOf('\\') < 0
        && path.chars().noneMatch(c -> c < 32 || c == 127)
        && path.indexOf('#') < 0;
  }

  private static boolean strongEtag(String value) {
    return value != null && value.matches("\"[\\x21\\x23-\\x7e]{1,512}\"");
  }

  private static long contentLength(String value) throws IOException {
    if (value == null) return -1;
    if (!value.matches("[0-9]+")) throw new IOException("REMOTE_DOWNLOAD_LENGTH");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw new IOException("REMOTE_DOWNLOAD_LENGTH");
    }
  }

  private static String required(Properties p, String key) throws IOException {
    String value = p.getProperty(key);
    if (value == null || value.length() > 8192) throw new IOException("REMOTE_DOWNLOAD_RECORD");
    return value;
  }

  private static MessageDigest sha() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String hash(Path file, MessageDigest digest) throws IOException {
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[65_536];
      int n;
      while ((n = input.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
    }
    try {
      return hex(((MessageDigest) digest.clone()).digest());
    } catch (CloneNotSupportedException e) {
      throw new IOException("REMOTE_DOWNLOAD_DIGEST_UNAVAILABLE");
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  private static void save(Job job) throws IOException {
    Properties p = new Properties();
    p.setProperty("id", job.id);
    p.setProperty("peer", job.peerId);
    p.setProperty("host", job.hostInstance);
    p.setProperty("path", job.path);
    p.setProperty("name", job.name);
    p.setProperty("mime", job.mime);
    p.setProperty("destination", job.destination);
    p.setProperty("etag", job.etag);
    p.setProperty("sha256", job.sha256);
    p.setProperty("bytes", Long.toString(job.bytes));
    p.setProperty("total", Long.toString(job.total));
    p.setProperty("state", job.state.name());
    Path target = job.directory.resolve("download.properties"),
        temporary = job.directory.resolve("download.next");
    try (OutputStream output =
        Files.newOutputStream(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)) {
      p.store(output, "Remote download; no gateway URLs or cookies");
    }
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
