# DSHA relay operator instructions

This directory is a deployment kit. It does not enable or advertise an official
service. `relay.example.invalid` is deliberately unusable. Do not put it in QR
codes or APK defaults. Only publish an endpoint after its external probes pass.

The server is a bounded go-libp2p Circuit Relay v2 plus signed rendezvous metadata,
not an HTTP/SOCKS proxy. It never registers application chat/screen/file/control
protocols, application pairing keys, or request/payload logging. libp2p performs
end-to-end peer authentication/encryption inside the relay. The relay necessarily
sees connection IPs, peer IDs, timing, and byte counts. Rendezvous records and
replay nonces are RAM-only and expire; clients must republish after a restart.

## Build and sign

From `tools/remote/relay`, using the pinned `go.mod` and committed `go.sum`:

```sh
go test -count=1 ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w -buildid=' -o /tmp/dsha-relay ./cmd/dsha-relay
```

Use `GOARCH=arm64` for an arm64 server. The server key and release-signing key
are independent. Keep both private keys out of source/artifacts. Obtain an
existing operator Ed25519 release-signing PEM key and distribute its public key
through an independently authenticated channel. Do not trust a key shipped in
the bundle itself.

```sh
python3 tools/remote/deploy/package.py --binary /tmp/dsha-relay --out /tmp/dsha-relay-bundle --signing-key /private/operator-release.pem
sh tools/remote/deploy/verify-bundle.sh /tmp/dsha-relay-bundle /trusted/operator-release-public.pem
```

The nine exact files are signed through `SHA256SUMS`/`SHA256SUMS.sig`. No service
identity, TLS private key, target registry, or credentials belong in this bundle.
Verify using the trusted repository copy of `verify-bundle.sh` before executing
any script from a received bundle. Preserve the manifest, signature, binary SHA,
relay peer ID, and external probe results in the deployment receipt.

## Independent systemd installation

First inspect the host's architecture, free RAM/FDs, existing processes/listeners,
firewall, DNS, and current website/proxy configuration. A relay virtual host must
not replace the existing website. Keep the website's configuration and ports
intact. The installer refuses an existing relay install and never edits a proxy.

```sh
sudo sh /tmp/dsha-relay-bundle/install-systemd.sh /tmp/dsha-relay-bundle /trusted/operator-release-public.pem
sudo cp /etc/dsha-relay/config.example.json /etc/dsha-relay/config.json
```

Edit `publicAddresses` to the real independent host `/dns4/REAL_HOST/tcp/443/wss`.
Keep `httpListen` and plaintext WS bound to loopback. Add the independent
`Caddyfile.example` virtual host using the host's existing configuration workflow;
validate it and reload only after review. Do not enable access logging or log
request bodies. A different TLS proxy must forward WebSocket upgrades without
intercepting application data. Preserve TLS verification on clients.
The binary accepts `DSHA_RELAY_CONFIG=/absolute/config.json`; `-config` overrides
it. The systemd unit optionally reads `/etc/dsha-relay/relay.env`, containing only
that configuration path. Put quotas, listeners, public addresses, and private-key
file paths in the JSON, never raw secrets in command arguments or environment.

```sh
sudo -u dsha-relay /opt/dsha-relay/dsha-relay -config /etc/dsha-relay/config.json -check
sudo systemctl enable --now dsha-relay
curl --fail http://127.0.0.1:8080/readyz
curl --fail https://REAL_HOST/readyz
curl --fail https://REAL_HOST/v1/info
```

The `relayPeerId` is a stable cryptographic pin; append `/p2p/PEER_ID` to the
public multiaddr. Back up `identity.key` securely. Replacing the key changes the
pin and requires explicit client reconfiguration. Clients must never accept an
untrusted pin by fetching `/v1/info` alone.

WSS on TCP 443 works when UDP is blocked and can share a host's existing TLS
listener through an independent vhost. Optional raw TCP 443 needs a separate
public IP/listener: set `listen` to `/ip4/PUBLIC_IP/tcp/443` and advertise that
address, and explicitly grant only `CAP_NET_BIND_SERVICE` (the provided unit has
no capabilities). Do not take over a website's existing TCP 443 listener. TCP
4001 may instead be exposed on a firewall-approved alternate port. The provided
default exposes neither raw TCP nor plaintext discovery publicly.

## Docker alternative

Verify the signed bundle first, then `docker build` its scratch Dockerfile.
`compose.example.yaml` uses Linux host networking; TLS stays with the independently
configured host proxy. Config and identity are read-only mounts. Create the
identity with `dsha-relay init-identity` first and make it readable only by UID
65532 with mode 0600. Do not run Docker and systemd instances on the same ports.
No image is pulled by the scratch Dockerfile. Record the locally built image ID.

## Pairing/discovery contract

The PC generates an independent random 32-byte discovery capability and puts
`discoveryURL` (HTTPS origin) and `discoveryToken` (64 hex chars) in the short-lived
QR alongside its peer ID, application pairing key, and pinned relay multiaddrs.
The capability is not the application pairing key.

`github.com/dsha/connect-core/discovery` signs each request with the node identity.
`Client.Do` POSTs `/v1/record` with a 10-second timeout, 16 KiB bound and no redirects.
The signature binds action, caller peer ID/public key, owner ID, capability/hash,
all addresses, absolute Unix-second expiry, issue time, and a fresh 128-bit nonce.
Signed requests expire after two minutes independently of the advertised lease.

1. PC `advertise`: its capability SHA-256, addresses, and lease expiry; then reserve
   the pinned relay and republish with the circuit address. The first claim is
   limited to five minutes from the first advertisement.
2. Phone `claim`: owner PC ID, capability, phone addresses, expiry; only that exact
   phone identity can claim the invitation. Then reserve/connect the relay.
3. Both sides `lookup` with the capability to obtain the exact paired addresses.
   PC periodically renews the same `advertise`; the connected phone independently
   renews the same `claim` every 20 minutes, retrying renewal failures after one
   minute. The phone lease is one hour; server leases are at most 24 hours.
   Presence/lease renewal does not replay a business action.
   After authentication and durable local trust storage, PC includes
   `confirmedPeerId` in its signed `advertise`. This restores only that exact
   remembered phone binding after relay restart or metadata expiry, even when
   the phone returns after five minutes. The phone still signs its own `claim`
   with the capability to obtain an independent lease; the owner's presence
   alone grants no phone relay access. An unpaired advertisement cannot extend
   its first-claim window through renewal. A claimed phone cannot be silently
   replaced by a different confirmed peer; revoke and a fresh QR are required.
4. Either paired peer may sign `revoke` with the owner ID, closing relay transport
   connections. A different phone requires revoke and a fresh QR capability.

Unknown signatures, reused nonces, mismatched address peer IDs, expired invites,
second claimants, public plaintext discovery, and unknown relay pairs are denied.
No capability may be placed in URLs, headers, access logs, analytics, or receipts.
Limits bound metadata, HTTP concurrency/body/rate, total libp2p connections,
reservations per service/IP, circuits per peer, circuit duration, and circuit
bytes in each direction. Limits intentionally disconnect long/large sessions;
clients show a reconnect action and must not replay an uncertain business command.
Behind a loopback TLS proxy, all WSS reservations have the proxy's underlying
source IP. The default per-IP reservation limit of eight therefore also caps
that proxy at eight reservations. An operator may deliberately raise it to at
most 128 while retaining the global reservation and per-peer/circuit limits.
The service does not trust spoofable forwarded-IP headers to bypass this quota.
The default circuit budget is 16 GiB per direction (configurable up to 64 GiB),
separate from the application single-file body maximum of 5,000,000,000 bytes.
Noise/multiplexing/framing and other streams also consume this circuit budget.
Circuit quotas are not file sizes and do not guarantee an arbitrarily reused
circuit can finish another maximum-size file. A broken transfer must verify the
same target and resume offset; an uncertain control action must not be replayed.

Run the explicit large-payload host check with
`DSHA_RELAY_LARGE_BODY=5000000000 go test -count=1 -run TestForcedRelayLargeBody -v`.
This streams and hashes the actual byte count through a forced encrypted TCP
circuit without a 5 GB disk fixture. It is transport evidence only, not phone
file-picker/storage or internet-deployment evidence.

## External acceptance before advertising service

From a different network, verify valid TLS hostname/certificate, exact pinned
relay peer ID, health/readiness, a signed PC advertisement and phone claim,
actual WSS circuit transfer with UDP blocked, and denial for an unrelated peer.
Then revoke and verify the circuit stops. Check reservation/data limits and
website health before/after. Local integration tests are not external deployment
or phone evidence. Until these probes pass the endpoint remains unconfigured in
production UI/QR defaults.
