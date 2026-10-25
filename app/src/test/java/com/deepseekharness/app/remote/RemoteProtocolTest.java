package com.deepseekharness.app.remote;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class RemoteProtocolTest {
  @Test
  public void boundedFrameRoundTripsUnicodeWithoutConsumingNextFrame() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    RemoteProtocol.write(bytes, new JSONObject().put("message", "你好"), 100);
    RemoteProtocol.write(bytes, new JSONObject().put("next", true), 100);
    ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
    assertEquals("你好", RemoteProtocol.read(input, 100).getString("message"));
    assertTrue(RemoteProtocol.read(input, 100).getBoolean("next"));
    assertEquals(-1, input.read());
  }

  @Test
  public void negativeAndOversizedFrameLengthsAreRejectedBeforeReadingBody() throws Exception {
    for (int size : new int[] {-1, 0, 1, 101, Integer.MAX_VALUE}) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      new DataOutputStream(bytes).writeInt(size);
      IOException failure =
          assertThrows(
              IOException.class,
              () -> RemoteProtocol.read(new ByteArrayInputStream(bytes.toByteArray()), 100));
      assertEquals("REMOTE_FRAME_LIMIT", failure.getMessage());
    }
  }

  @Test
  public void malformedUtf8AndIncompleteFramesRemainFailures() throws Exception {
    byte[] bad = new byte[] {0, 0, 0, 3, (byte) 0xff, '{', '}'};
    assertEquals(
        "REMOTE_FRAME_INVALID",
        assertThrows(
                IOException.class, () -> RemoteProtocol.read(new ByteArrayInputStream(bad), 100))
            .getMessage());
    assertThrows(
        IOException.class,
        () ->
            RemoteProtocol.read(new ByteArrayInputStream(new byte[] {0, 0, 0, 8, '{', '}'}), 100));
  }

  @Test
  public void oversizedOutgoingRequestWritesNoPartialHeader() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () -> RemoteProtocol.write(bytes, new JSONObject().put("data", "x".repeat(200)), 100));
    assertEquals(0, bytes.size());
  }

  @Test
  public void identityRoundTripBindsEveryOwnerComponentAndRedactsToString() {
    RemoteSessionIdentity one =
        new RemoteSessionIdentity("pc", "host", "session", "a".repeat(32), 7, 11);
    assertEquals(one, RemoteSessionIdentity.fromJsonString(one.toJsonString()));
    assertNotEquals(one, new RemoteSessionIdentity("pc", "host", "session", "b".repeat(32), 7, 11));
    assertNotEquals(one, new RemoteSessionIdentity("pc", "host", "session", "a".repeat(32), 8, 11));
    assertNotEquals(
        one, new RemoteSessionIdentity("pc", "another", "session", "a".repeat(32), 7, 11));
    assertEquals("RemoteSessionIdentity[redacted]", one.toString());
    assertThrows(IllegalArgumentException.class, () -> RemoteSessionIdentity.fromJsonString("{}"));
  }
}
