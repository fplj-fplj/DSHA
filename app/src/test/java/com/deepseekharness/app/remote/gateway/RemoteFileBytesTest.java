package com.deepseekharness.app.remote.gateway;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public final class RemoteFileBytesTest {
  @Test
  public void acknowledgedChunksPublishTheExactSelectedFileBytes() throws Exception {
    Path file = Files.createTempFile("remote-file-sink-", ".bin");
    byte[] content = new byte[100_003];
    new Random(42).nextBytes(content);
    try {
      try (RemoteFileBytes sink =
          new RemoteFileBytes(Files.newOutputStream(file), content.length)) {
        int sequence = 0;
        for (int offset = 0; offset < content.length; offset += 24576) {
          byte[] chunk =
              Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 24576));
          sink.chunk(sequence++, Base64.getEncoder().encodeToString(chunk));
        }
        assertFalse(sink.complete());
        sink.finish(content.length);
        assertTrue(sink.complete());
        assertEquals(content.length, sink.written());
      }
      assertArrayEquals(content, Files.readAllBytes(file));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void duplicateOrOutOfOrderChunksCannotBeReplayed() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (RemoteFileBytes sink = new RemoteFileBytes(output, 2)) {
      sink.chunk(0, "YQ==");
      assertThrows(IOException.class, () -> sink.chunk(0, "Yg=="));
      assertThrows(IOException.class, () -> sink.finish(1));
      assertFalse(sink.complete());
      assertArrayEquals(new byte[] {'a'}, output.toByteArray());
    }
  }

  @Test
  public void malformedAndOversizedChunksNeverWriteOrFinish() throws Exception {
    for (String encoded :
        List.of("!invalid!", Base64.getEncoder().encodeToString(new byte[24577]), "")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (RemoteFileBytes sink = new RemoteFileBytes(output, 24577)) {
        assertThrows(IOException.class, () -> sink.chunk(0, encoded));
        assertEquals(0, output.size());
        assertThrows(IOException.class, () -> sink.finish(0));
      }
    }
  }

  @Test
  public void fiveGbDeclarationUsesLongAndCannotMintCompletionAfterPartialData() throws Exception {
    try (RemoteFileBytes sink =
        new RemoteFileBytes(OutputStream.nullOutputStream(), 5_000_000_000L)) {
      sink.chunk(0, Base64.getEncoder().encodeToString(new byte[24576]));
      assertEquals(24576, sink.written());
      assertThrows(IOException.class, () -> sink.finish(5_000_000_000L));
      assertFalse(sink.complete());
    }
    assertThrows(
        IOException.class,
        () -> new RemoteFileBytes(OutputStream.nullOutputStream(), 5_000_000_001L));
  }

  @Test
  public void ioFailureAndPrematureCloseInvalidateCompletion() throws Exception {
    try (RemoteFileBytes sink =
        new RemoteFileBytes(
            new OutputStream() {
              public void write(int value) throws IOException {
                throw new IOException("disk full");
              }
            },
            1)) {
      assertThrows(IOException.class, () -> sink.chunk(0, "YQ=="));
      assertThrows(IOException.class, () -> sink.finish(1));
      assertFalse(sink.complete());
    }
    RemoteFileBytes closed = new RemoteFileBytes(new ByteArrayOutputStream(), 0);
    closed.close();
    assertThrows(IOException.class, () -> closed.finish(0));
    assertFalse(closed.complete());
  }
}
