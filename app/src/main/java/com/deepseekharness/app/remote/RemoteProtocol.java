package com.deepseekharness.app.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** One bounded JSON RPC frame. A transport loss is an unknown result, never a retry instruction. */
public final class RemoteProtocol {
  public static final int MAX_REQUEST = 64 * 1024, MAX_RESPONSE = 4 * 1024 * 1024;

  private RemoteProtocol() {}

  public static void close(AutoCloseable resource) {
    if (resource != null)
      try {
        resource.close();
      } catch (Exception ignored) {
      }
  }

  public static void write(OutputStream output, JSONObject object, int limit) throws IOException {
    byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length > limit) throw new IOException("REMOTE_FRAME_LIMIT");
    DataOutputStream framed = new DataOutputStream(output);
    framed.writeInt(bytes.length);
    framed.write(bytes);
    framed.flush();
  }

  public static JSONObject read(InputStream input, int limit) throws IOException {
    DataInputStream framed = new DataInputStream(input);
    int length = framed.readInt();
    if (length < 2 || length > limit) throw new IOException("REMOTE_FRAME_LIMIT");
    byte[] bytes = new byte[length];
    framed.readFully(bytes);
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return new JSONObject(text);
    } catch (java.nio.charset.CharacterCodingException | JSONException invalid) {
      throw new IOException("REMOTE_FRAME_INVALID");
    }
  }
}
