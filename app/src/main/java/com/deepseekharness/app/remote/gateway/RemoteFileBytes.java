package com.deepseekharness.app.remote.gateway;

import java.io.*;
import java.util.Base64;

/** Bounded, acknowledged remote Blob protocol. A partial/failed stream can never finish. */
final class RemoteFileBytes implements AutoCloseable {
  static final long MAX_BYTES = 5_000_000_000L;
  private final OutputStream output;
  private final long expected;
  private long written;
  private int sequence;
  private boolean failed, complete, closed;

  RemoteFileBytes(OutputStream output, long expected) throws IOException {
    if (expected < 0 || expected > MAX_BYTES) throw new IOException("REMOTE_FILE_LIMIT");
    this.output = output;
    this.expected = expected;
  }

  void chunk(int index, String encoded) throws IOException {
    if (closed || failed || complete) throw new IOException("REMOTE_FILE_CLOSED");
    try {
      if (index != sequence || encoded.length() > 32768)
        throw new IOException("REMOTE_FILE_SEQUENCE");
      byte[] bytes;
      try {
        bytes = Base64.getDecoder().decode(encoded);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("REMOTE_FILE_BASE64");
      }
      if (bytes.length == 0 || bytes.length > 24576 || written > expected - bytes.length)
        throw new IOException("REMOTE_FILE_LENGTH");
      output.write(bytes);
      written += bytes.length;
      sequence++;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  void finish(long reported) throws IOException {
    if (failed || closed || complete || reported != written || written != expected) {
      failed = true;
      throw new IOException("REMOTE_FILE_TRUNCATED");
    }
    try {
      output.flush();
      output.close();
      closed = true;
      complete = true;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  boolean complete() {
    return complete;
  }

  long written() {
    return written;
  }

  @Override
  public void close() throws IOException {
    closed = true;
    output.close();
  }
}
