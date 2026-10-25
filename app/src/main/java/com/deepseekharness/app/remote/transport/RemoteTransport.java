package com.deepseekharness.app.remote.transport;

import com.deepseekharness.connect.mobile.Client;
import com.deepseekharness.connect.mobile.Mobile;
import com.deepseekharness.connect.mobile.Pipe;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native encrypted transport. Call blocking methods on a worker thread. */
public final class RemoteTransport {
  private RemoteTransport() {}

  public enum Kind {
    CONTROL,
    HTTP,
    FILE
  }

  public static final class Config {
    public final String identityBase64, trustedPeerId, pairingKeyHex;
    public final String discoveryURL, discoveryToken;
    public final List<String> addresses, relayAddresses;
    public final long generation;

    public Config(
        String identityBase64,
        String trustedPeerId,
        String pairingKeyHex,
        List<String> addresses,
        List<String> relayAddresses,
        long generation) {
      this(
          identityBase64,
          trustedPeerId,
          pairingKeyHex,
          addresses,
          relayAddresses,
          generation,
          "",
          "");
    }

    public Config(
        String identityBase64,
        String trustedPeerId,
        String pairingKeyHex,
        List<String> addresses,
        List<String> relayAddresses,
        long generation,
        String discoveryURL,
        String discoveryToken) {
      this.identityBase64 = identityBase64 == null ? "" : identityBase64;
      this.trustedPeerId = trustedPeerId;
      this.pairingKeyHex = pairingKeyHex;
      this.addresses = immutable(addresses);
      this.relayAddresses = immutable(relayAddresses);
      this.generation = generation;
      this.discoveryURL = discoveryURL == null ? "" : discoveryURL;
      this.discoveryToken = discoveryToken == null ? "" : discoveryToken;
    }

    private static List<String> immutable(List<String> source) {
      return source == null
          ? Collections.emptyList()
          : Collections.unmodifiableList(new ArrayList<>(source));
    }

    String json() throws IOException {
      try {
        return new JSONObject()
            .put("identityBase64", identityBase64)
            .put("trustedPeerId", trustedPeerId)
            .put("pairingKeyHex", pairingKeyHex)
            .put("addresses", new JSONArray(addresses))
            .put("relayAddrs", new JSONArray(relayAddresses))
            .put("generation", generation)
            .put("discoveryURL", discoveryURL)
            .put("discoveryToken", discoveryToken)
            .toString();
      } catch (JSONException e) {
        throw new IOException("Transport configuration", e);
      }
    }
  }

  public static Connection connect(Config config) throws IOException {
    Client client = null;
    try {
      client = Mobile.newClient(config.json());
      client.connect(30000);
      return new Connection(client);
    } catch (Exception e) {
      if (client != null)
        try {
          client.close();
        } catch (Exception ignored) {
        }
      throw failure(e);
    }
  }

  public static final class Connection implements Closeable {
    private final Client client;
    private final AtomicBoolean closed = new AtomicBoolean();

    Connection(Client client) {
      this.client = client;
    }

    public String localPeerId() {
      return client.localPeerID();
    }

    public String remotePeerId() {
      return client.remotePeerID();
    }

    public String identityBase64() {
      return client.identityBase64();
    }

    public long generation() {
      return client.generation();
    }

    public String route() {
      return client.route();
    }

    public long roundTripMillis() throws IOException {
      try {
        return client.roundTripMillis();
      } catch (Exception e) {
        throw failure(e);
      }
    }

    public boolean isClosed() {
      return closed.get();
    }

    public boolean isCurrent() {
      return !closed.get() && client.isCurrent();
    }

    public Channel openStream(Kind kind) throws IOException {
      ensureOpen();
      try {
        return new Channel(client.open(kind.name().toLowerCase(java.util.Locale.ROOT), 30000));
      } catch (Exception e) {
        throw failure(e);
      }
    }

    public Channel acceptStream(int timeoutMs) throws IOException {
      ensureOpen();
      try {
        Pipe pipe = client.accept(timeoutMs);
        return pipe == null ? null : new Channel(pipe);
      } catch (Exception e) {
        throw failure(e);
      }
    }

    private void ensureOpen() throws IOException {
      if (!isCurrent()) throw new IOException("Remote connection epoch retired");
    }

    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true))
        try {
          client.close();
        } catch (Exception e) {
          throw failure(e);
        }
    }
  }

  public static final class Channel implements Closeable {
    private final Pipe pipe;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final InputStream input =
        new InputStream() {
          @Override
          public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
          }

          @Override
          public int read(byte[] b, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, b.length);
            if (length == 0) return 0;
            ensureOpen();
            try {
              byte[] chunk = pipe.read(Math.min(length, 65536), 600000);
              if (chunk == null || chunk.length == 0) return -1;
              System.arraycopy(chunk, 0, b, offset, chunk.length);
              return chunk.length;
            } catch (Exception e) {
              throw failure(e);
            }
          }

          @Override
          public void close() throws IOException {
            Channel.this.close();
          }
        };
    private final OutputStream output =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
          }

          @Override
          public void write(byte[] b, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, b.length);
            ensureOpen();
            while (length > 0) {
              int size = Math.min(length, 65536);
              byte[] chunk = java.util.Arrays.copyOfRange(b, offset, offset + size);
              try {
                if (pipe.write(chunk, 120000) != size)
                  throw new IOException("Incomplete remote write");
              } catch (Exception e) {
                throw failure(e);
              }
              offset += size;
              length -= size;
            }
          }

          @Override
          public void close() throws IOException {
            closeOutput();
          }
        };

    Channel(Pipe pipe) {
      this.pipe = pipe;
    }

    public Kind kind() {
      return Kind.valueOf(pipe.kind().toUpperCase(java.util.Locale.ROOT));
    }

    public InputStream input() {
      return input;
    }

    public OutputStream output() {
      return output;
    }

    public void closeOutput() throws IOException {
      try {
        pipe.closeWrite();
      } catch (Exception e) {
        throw failure(e);
      }
    }

    public void cancel() throws IOException {
      if (closed.compareAndSet(false, true))
        try {
          pipe.cancel();
        } catch (Exception e) {
          throw failure(e);
        }
    }

    private void ensureOpen() throws IOException {
      if (closed.get()) throw new IOException("Remote stream closed");
    }

    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true))
        try {
          pipe.close();
        } catch (Exception e) {
          throw failure(e);
        }
    }
  }

  private static IOException failure(Exception error) {
    return error instanceof IOException
        ? (IOException) error
        : new IOException(error.getMessage(), error);
  }
}
