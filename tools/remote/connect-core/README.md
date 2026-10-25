# DSHA connection core

The Go core and gomobile Android library carry independent `control`, `http`, and
`file` duplex streams. TCP/QUIC direct connections are preferred; a Circuit Relay
v2 address is an explicit fallback. Libp2p Noise authenticates endpoint PeerIDs
for TCP and circuit streams; QUIC uses its identity-bound TLS 1.3 transport.
Traffic remains encrypted end to end through relays. A 256-bit QR pairing key also
authenticates each stream with a domain-separated challenge/response; a relay
never receives that key. The endpoint pins the first successful pairing peer
only during the bounded pairing window, then rejects other identities.

The Java API is `remote.transport.RemoteTransport`. A new `Connection` belongs
to one local generation, carries no automatic request replay, and closes every
active stream when retired. Control, HTTP, and file have separate concurrency
budgets (8/24/4), bounded queues, 64 KiB I/O chunks, and a decimal 5 GB plus
16 MiB framing allowance per-direction stream limit, ten-second authentication
deadlines, and cancellable admission. The HTTP/file owner separately enforces
the decimal 5 GB file payload limit.
Application authorization and request/observation identities remain the native
service's responsibility. Network reconnection alone never authorizes an action.

For Go consumers use module `github.com/dsha/connect-core` with a local replace
to this directory. `New` creates an endpoint; `SetTrust`, `Connect`, `Open`, and
`Accept` establish the application link. `ReserveRelay` returns the endpoint's
circuit address; the companion must renew its reservation and advertise that
address. `ClaimDiscovery` signs rendezvous requests using the endpoint identity.
Discovery addresses are untrusted hints and must match the pinned PeerID.
Remembered direct addresses receive a bounded attempt before discovery, so a
discovery outage cannot prevent an authenticated LAN connection. A connected
phone renews its finite discovery lease with its own signature every twenty
minutes; failed renewals retry once per minute, and closing the connection
cancels pending renewal. This does not renew an unclaimed QR pairing window.

Run `go test ./...` for host integration. `build-android.ps1` pins gomobile and
builds only arm64/API30 into an external output directory using an explicit Go,
Android SDK/NDK, and Java installation. It does not build an APK or install on a
device. The resulting AAR contains Java bindings and `libgojni.so`; include it
as an app dependency. Android runtime behavior requires separate device checks.
