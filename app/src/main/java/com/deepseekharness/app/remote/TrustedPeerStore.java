package com.deepseekharness.app.remote;

import android.content.Context;
import android.util.AtomicFile;
import com.deepseekharness.app.data.KeyVault;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local-only encrypted pairing state. Never backed up and never replaced on read failure. */
public final class TrustedPeerStore {
  interface CipherStore {
    String seal(String plain) throws IOException;

    String open(String sealed) throws IOException;
  }

  private static TrustedPeerStore instance;
  private final AtomicFile file;
  private final CipherStore cipher;
  private final java.util.Set<String> revoked = new java.util.HashSet<>();
  private final CopyOnWriteArrayList<java.util.function.Consumer<String>> revocations =
      new CopyOnWriteArrayList<>();

  public static synchronized TrustedPeerStore get(Context context) {
    if (instance == null) {
      Context app = context.getApplicationContext();
      KeyVault vault = new KeyVault(app, "dsha_remote_pairings_v1");
      instance =
          new TrustedPeerStore(
              new File(app.getNoBackupFilesDir(), "remote-pairings-v1.enc"),
              new CipherStore() {
                public String seal(String plain) throws IOException {
                  String sealed = vault.encrypt(plain);
                  if (sealed.isEmpty()) throw new IOException("REMOTE_KEYSTORE_UNAVAILABLE");
                  return sealed;
                }

                public String open(String sealed) throws IOException {
                  return vault.decryptChecked(sealed);
                }
              });
    }
    return instance;
  }

  TrustedPeerStore(File target, CipherStore cipher) {
    this.file = new AtomicFile(target);
    this.cipher = cipher;
  }

  private JSONObject read() throws IOException {
    if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
      try {
        return new JSONObject()
            .put("schema", 1)
            .put("revision", 0)
            .put("peers", new JSONArray())
            .put("identity", "");
      } catch (JSONException impossible) {
        throw new IOException("REMOTE_STORE", impossible);
      }
    }
    try {
      byte[] data;
      try (var input = file.openRead();
          var buffer = new java.io.ByteArrayOutputStream()) {
        byte[] chunk = new byte[8192];
        int count;
        while ((count =
                input.read(chunk, 0, Math.min(chunk.length, 512 * 1024 + 1 - buffer.size())))
            > 0) {
          buffer.write(chunk, 0, count);
          if (buffer.size() > 512 * 1024) throw new IOException("REMOTE_STORE_LIMIT");
        }
        data = buffer.toByteArray();
      }
      if (data.length > 512 * 1024) throw new IOException("REMOTE_STORE_LIMIT");
      JSONObject root = new JSONObject(cipher.open(new String(data, StandardCharsets.UTF_8)));
      if (root.getInt("schema") != 1
          || root.getLong("revision") < 0
          || root.getJSONArray("peers").length() > 64)
        throw new IOException("REMOTE_STORE_INVALID");
      return root;
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_UNREADABLE");
    }
  }

  private void write(JSONObject root) throws IOException {
    byte[] sealed = cipher.seal(root.toString()).getBytes(StandardCharsets.UTF_8);
    if (sealed.length > 512 * 1024) throw new IOException("REMOTE_STORE_LIMIT");
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(sealed);
      output.flush();
      output.getFD().sync();
      file.finishWrite(output);
      // AtomicFile reports rename/fsync failures only to Log. Do not publish trust or clear
      // a revocation tombstone unless the exact encrypted candidate became the base file.
      byte[] committed;
      try (var input = new java.io.FileInputStream(file.getBaseFile());
          var bytes = new java.io.ByteArrayOutputStream()) {
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
          bytes.write(chunk, 0, count);
          if (bytes.size() > sealed.length) throw new IOException("REMOTE_STORE_COMMIT_FAILED");
        }
        committed = bytes.toByteArray();
      }
      if (!java.util.Arrays.equals(sealed, committed))
        throw new IOException("REMOTE_STORE_COMMIT_FAILED");
    } catch (IOException failure) {
      if (output != null) file.failWrite(output);
      throw failure;
    }
  }

  public synchronized List<RemoteHost> list() throws IOException {
    try {
      JSONArray peers = read().getJSONArray("peers");
      List<RemoteHost> result = new ArrayList<>();
      for (int i = 0; i < peers.length(); i++) {
        RemoteHost host = RemoteHost.fromJson(peers.getJSONObject(i).getJSONObject("host"));
        if (!revoked.contains(host.peerId)) result.add(host);
      }
      return java.util.Collections.unmodifiableList(result);
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  private JSONObject find(JSONObject root, String peerId) throws JSONException {
    JSONArray peers = root.getJSONArray("peers");
    for (int i = 0; i < peers.length(); i++) {
      JSONObject entry = peers.getJSONObject(i);
      if (peerId.equals(entry.getJSONObject("host").getString("peerId"))) return entry;
    }
    return null;
  }

  public synchronized RemoteHost get(String peerId) throws IOException {
    try {
      if (revoked.contains(peerId)) return null;
      JSONObject entry = find(read(), RemoteHost.id(peerId));
      return entry == null ? null : RemoteHost.fromJson(entry.getJSONObject("host"));
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  public synchronized String pairingKey(String peerId) throws IOException {
    try {
      if (revoked.contains(peerId)) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
      JSONObject entry = find(read(), RemoteHost.id(peerId));
      if (entry == null) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
      return key(entry.getString("key"));
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  public synchronized long revision(String peerId) throws IOException {
    try {
      if (revoked.contains(peerId)) return 0;
      JSONObject entry = find(read(), RemoteHost.id(peerId));
      return entry == null ? 0 : entry.getLong("revision");
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  /** QR fields are endpoint hints. The saved peer and key remain the authentication authority. */
  synchronized RemoteHost endpointCandidate(RemoteHost hints) throws IOException {
    RemoteHost previous = get(hints.peerId);
    if (previous == null) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
    if (!hints.hostInstanceId.matches("[0-9a-f]{32}"))
      throw new IOException("REMOTE_REFRESH_INSTANCE");
    return new RemoteHost(
        previous.peerId,
        previous.label,
        hints.hostInstanceId,
        hints.addresses,
        hints.relayAddresses.isEmpty() ? previous.relayAddresses : hints.relayAddresses,
        hints.discoveryURL.isEmpty() ? previous.discoveryURL : hints.discoveryURL,
        hints.discoveryURL.isEmpty() ? previous.discoveryToken : hints.discoveryToken);
  }

  /** Called by the session owner after pinned-peer authentication, hello and an actual ping. */
  synchronized void updateAuthenticatedEndpoints(
      RemoteHost authenticated, long expectedTrustRevision) throws IOException {
    try {
      if (revoked.contains(authenticated.peerId)) throw new IOException("REMOTE_PEER_NOT_TRUSTED");
      JSONObject root = read();
      JSONObject entry = find(root, authenticated.peerId);
      if (entry == null
          || expectedTrustRevision < 1
          || entry.getLong("revision") != expectedTrustRevision)
        throw new IOException("REMOTE_REFRESH_TRUST_CHANGED");
      RemoteHost previous = RemoteHost.fromJson(entry.getJSONObject("host"));
      RemoteHost changed =
          new RemoteHost(
              previous.peerId,
              previous.label,
              authenticated.hostInstanceId,
              authenticated.addresses,
              authenticated.relayAddresses,
              authenticated.discoveryURL,
              authenticated.discoveryToken);
      // The entry's reconnect key, grant revision and device identity are deliberately untouched.
      entry.put("host", changed.json());
      write(root);
    } catch (JSONException | IllegalArgumentException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  static String key(String value) {
    if (value == null || !value.matches("[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("REMOTE_PAIRING_KEY");
    return value.toLowerCase(java.util.Locale.ROOT);
  }

  /** Called only after native confirmation and a successful authenticated pair response. */
  public void trust(RemoteHost host, String reconnectKey) throws IOException {
    synchronized (this) {
      try {
        String safeKey = key(reconnectKey);
        JSONObject root = read();
        JSONArray peers = root.getJSONArray("peers");
        for (int i = peers.length() - 1; i >= 0; i--)
          if (host.peerId.equals(peers.getJSONObject(i).getJSONObject("host").getString("peerId")))
            peers.remove(i);
        if (peers.length() >= 64) throw new IOException("REMOTE_PEER_LIMIT");
        long next = Math.addExact(root.getLong("revision"), 1);
        root.put("revision", next);
        peers.put(
            new JSONObject().put("host", host.json()).put("key", safeKey).put("revision", next));
        write(root);
        revoked.remove(host.peerId);
      } catch (JSONException | ArithmeticException invalid) {
        throw new IOException("REMOTE_STORE_INVALID");
      }
    }
    notifyRevoked(host.peerId);
  }

  public void revoke(String peerId) throws IOException {
    RemoteHost.id(peerId);
    synchronized (this) {
      revoked.add(peerId);
    }
    // Invalidate the live owner before disk work; a failed write must never keep a current lease.
    notifyRevoked(peerId);
    synchronized (this) {
      try {
        JSONObject root = read();
        JSONArray peers = root.getJSONArray("peers");
        for (int i = peers.length() - 1; i >= 0; i--)
          if (peerId.equals(peers.getJSONObject(i).getJSONObject("host").getString("peerId")))
            peers.remove(i);
        root.put("revision", Math.addExact(root.getLong("revision"), 1));
        write(root);
      } catch (JSONException | ArithmeticException invalid) {
        throw new IOException("REMOTE_STORE_INVALID");
      }
    }
  }

  public synchronized String identityBase64() throws IOException {
    try {
      return read().getString("identity");
    } catch (JSONException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  public synchronized void saveIdentityBase64(String identity) throws IOException {
    if (identity == null
        || identity.isEmpty()
        || identity.length() > 16384
        || !identity.matches("[A-Za-z0-9+/=_-]+")) throw new IOException("REMOTE_IDENTITY_INVALID");
    try {
      JSONObject root = read();
      String old = root.getString("identity");
      if (!old.isEmpty() && !old.equals(identity)) throw new IOException("REMOTE_IDENTITY_CHANGED");
      root.put("identity", identity);
      write(root);
    } catch (JSONException invalid) {
      throw new IOException("REMOTE_STORE_INVALID");
    }
  }

  public void addRevocationListener(java.util.function.Consumer<String> listener) {
    revocations.addIfAbsent(listener);
  }

  public void removeRevocationListener(java.util.function.Consumer<String> listener) {
    revocations.remove(listener);
  }

  private void notifyRevoked(String peerId) {
    for (var listener : revocations)
      try {
        listener.accept(peerId);
      } catch (RuntimeException ignored) {
      }
  }
}
