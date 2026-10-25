package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dsha/connect-core/discovery"
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	client "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	ma "github.com/multiformats/go-multiaddr"
)

func testNode(t *testing.T, k crypto.PrivKey, listen string) host.Host {
	t.Helper()
	h, err := libp2p.New(libp2p.Identity(k), libp2p.ListenAddrStrings(listen), libp2p.EnableRelay(), libp2p.DisableMetrics())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { h.Close() })
	return h
}

func TestLocalDirectRelayDeniedAndResourceLimits(t *testing.T) {
	c := DefaultConfig()
	c.Listen = []string{"/ip4/127.0.0.1/tcp/0", "/ip4/127.0.0.1/tcp/0/ws"}
	c.CircuitBytes = 32 * 1024
	c.CircuitDurationSeconds = 30
	c.MaxReservations = 1
	s, err := New(c, key(t))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	httpServer := httptest.NewServer(s.Handler())
	defer httpServer.Close()
	d := discovery.Client{URL: httpServer.URL}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	aKey, bKey, cKey := key(t), key(t), key(t)
	a := testNode(t, aKey, "/ip4/127.0.0.1/tcp/0")
	b := testNode(t, bKey, "/ip4/127.0.0.1/tcp/0")
	untrusted := testNode(t, cKey, "/ip4/127.0.0.1/tcp/0")
	proto := protocol.ID("/dsha/relay-test/1")
	a.SetStreamHandler(proto, func(st network.Stream) { defer st.Close(); io.Copy(st, st) })
	// TCP direct path is real, independent of the relay. No UDP listener is used.
	if err := b.Connect(ctx, peer.AddrInfo{ID: a.ID(), Addrs: a.Addrs()}); err != nil {
		t.Fatal(err)
	}
	stream, err := b.NewStream(ctx, a.ID(), proto)
	if err != nil {
		t.Fatal(err)
	}
	stream.SetDeadline(time.Now().Add(5 * time.Second))
	stream.Write([]byte("direct"))
	out := make([]byte, 6)
	if _, err := io.ReadFull(stream, out); err != nil || string(out) != "direct" {
		t.Fatal("direct echo", err)
	}
	stream.Close()
	if b.Network().ConnsToPeer(a.ID())[0].Stat().Limited {
		t.Fatal("direct unexpectedly relayed")
	}
	b.Network().ClosePeer(a.ID())
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	if _, err := d.Do(ctx, aKey, discovery.Request{Action: "advertise", CapabilityHash: hash, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Do(ctx, bKey, discovery.Request{Action: "claim", OwnerID: a.ID().String(), Capability: cap, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	relayInfo := peer.AddrInfo{ID: s.Host.ID(), Addrs: s.Host.Addrs()}
	reservation, err := client.Reserve(ctx, a, relayInfo)
	if err != nil {
		t.Fatal("reserve", err)
	}
	if reservation.LimitData != uint64(c.CircuitBytes) {
		t.Fatal("data quota not advertised", reservation.LimitData)
	}
	if _, err := client.Reserve(ctx, b, relayInfo); err == nil {
		t.Fatal("reservation capacity not enforced")
	}
	if _, err := client.Reserve(ctx, untrusted, relayInfo); err == nil {
		t.Fatal("untrusted reservation accepted")
	}
	// Force the TCP-only circuit route: the destination's direct address is
	// deliberately absent, avoiding a false pass from direct-dial optimization.
	b.Peerstore().ClearAddrs(a.ID())
	var tcpAddr ma.Multiaddr
	for _, v := range s.Host.Addrs() {
		if _, err := v.ValueForProtocol(ma.P_WS); err != nil {
			tcpAddr = v
			break
		}
	}
	circuitAddr, _ := ma.NewMultiaddr(tcpAddr.String() + "/p2p/" + s.Host.ID().String() + "/p2p-circuit/p2p/" + a.ID().String())
	info, _ := peer.AddrInfoFromP2pAddr(circuitAddr)
	if err := b.Connect(ctx, *info); err != nil {
		t.Fatal("relay connect", err)
	}
	stream, err = b.NewStream(network.WithAllowLimitedConn(ctx, "relay-test"), a.ID(), proto)
	if err != nil {
		t.Fatal("relay stream", err)
	}
	if !stream.Conn().Stat().Limited {
		t.Fatal("test did not use relay")
	}
	stream.SetDeadline(time.Now().Add(5 * time.Second))
	stream.Write([]byte("encrypted-relay"))
	out = make([]byte, 15)
	if _, err := io.ReadFull(stream, out); err != nil || string(out) != "encrypted-relay" {
		t.Fatal("relay echo", err, string(out))
	}
	stream.Close()
	// The relay's application surface consists only of library hop/stop plus
	// libp2p identity/ping. It has no chat/control protocol and no payload logger.
	if s.Host.Mux().Protocols()[0] == proto {
		t.Fatal("relay installed application protocol")
	}
	stream, err = b.NewStream(network.WithAllowLimitedConn(ctx, "resource-test"), a.ID(), proto)
	if err != nil {
		t.Fatal(err)
	}
	stream.SetDeadline(time.Now().Add(5 * time.Second))
	done := make(chan error, 1)
	go func() { _, err := stream.Write(bytes.Repeat([]byte{1}, 128*1024)); done <- err }()
	readBytes, readErr := io.Copy(io.Discard, stream)
	writeErr := <-done
	if readBytes >= 128*1024 || (readErr == nil && writeErr == nil) {
		t.Fatal("relay byte quota did not terminate circuit")
	}
	stream.Close()
	// Independently self-advertising never grants access to another pair.
	cap2, _ := discovery.NewCapability()
	hash2, _ := discovery.CapabilityHash(cap2)
	if _, err := d.Do(ctx, cKey, discovery.Request{Action: "advertise", CapabilityHash: hash2, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	if err := untrusted.Connect(ctx, *info); err == nil {
		t.Fatal("unpaired relay connect accepted")
	}
}

func TestHTTPBoundsAndNoProxy(t *testing.T) {
	c := DefaultConfig()
	c.Listen = []string{"/ip4/127.0.0.1/tcp/0"}
	s, err := New(c, key(t))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	h := s.Handler()
	for _, path := range []string{"/proxy?url=http://127.0.0.1", "/v1/record?capability=secret", "/v1/chat", "/v1/screen"} {
		r := httptest.NewRequest(http.MethodPost, path, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code == 200 {
			t.Fatal("unexpected endpoint", path)
		}
	}
	r := httptest.NewRequest(http.MethodPost, "/v1/record", bytes.NewReader(bytes.Repeat([]byte("x"), discovery.MaxBody+1)))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatal("body bound", w.Code)
	}
	r = httptest.NewRequest(http.MethodGet, "/readyz", nil)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var result map[string]any
	if json.Unmarshal(w.Body.Bytes(), &result) != nil || result["ready"] != true {
		t.Fatal("readiness failed")
	}
}

func TestServiceRestartRestoresOnlyRememberedPair(t *testing.T) {
	c := DefaultConfig()
	c.Listen = []string{"/ip4/127.0.0.1/tcp/0"}
	serviceKey, ownerKey, phoneKey := key(t), key(t), key(t)
	owner := testNode(t, ownerKey, "/ip4/127.0.0.1/tcp/0")
	phone := testNode(t, phoneKey, "/ip4/127.0.0.1/tcp/0")
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	old, err := New(c, serviceKey)
	if err != nil {
		t.Fatal(err)
	}
	oldAPI := httptest.NewServer(old.Handler())
	d := discovery.Client{URL: oldAPI.URL}
	if _, err := d.Do(ctx, ownerKey, discovery.Request{Action: "advertise", CapabilityHash: hash, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Do(ctx, phoneKey, discovery.Request{Action: "claim", OwnerID: owner.ID().String(), Capability: cap, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Reserve(ctx, owner, peer.AddrInfo{ID: old.Host.ID(), Addrs: old.Host.Addrs()}); err != nil {
		t.Fatal(err)
	}
	stablePin := old.Host.ID()
	oldAPI.Close()
	old.Close()
	restarted, err := New(c, serviceKey)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	if restarted.Host.ID() != stablePin || restarted.Registry.Count() != 0 {
		t.Fatal("service identity or empty restart state unexpected")
	}
	api := httptest.NewServer(restarted.Handler())
	defer api.Close()
	d.URL = api.URL
	now := time.Now()
	var fakeUnix atomic.Int64
	fakeUnix.Store(now.Unix())
	restarted.Registry.mu.Lock()
	restarted.Registry.now = func() time.Time { return time.Unix(fakeUnix.Load(), 0) }
	restarted.Registry.mu.Unlock()
	if _, err := d.Do(ctx, ownerKey, discovery.Request{Action: "advertise", CapabilityHash: hash, ConfirmedPeerID: phone.ID().String(), Issued: now.Unix(), Expires: now.Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	if restarted.Registry.AllowReserve(phone.ID(), nil) {
		t.Fatal("owner granted phone access without phone lease")
	}
	now = now.Add(6 * time.Minute)
	fakeUnix.Store(now.Unix())
	if _, err := d.Do(ctx, phoneKey, discovery.Request{Action: "claim", OwnerID: owner.ID().String(), Capability: cap, Issued: now.Unix(), Expires: now.Add(time.Hour).Unix()}); err != nil {
		t.Fatal("remembered reclaim after actual service restart", err)
	}
	// Force re-dial to the restarted listener with the same cryptographic pin.
	owner.Network().ClosePeer(stablePin)
	owner.Peerstore().ClearAddrs(stablePin)
	if _, err := client.Reserve(ctx, owner, peer.AddrInfo{ID: stablePin, Addrs: restarted.Host.Addrs()}); err != nil {
		t.Fatal("owner reserve after restart", err)
	}
	if _, err := client.Reserve(ctx, phone, peer.AddrInfo{ID: stablePin, Addrs: restarted.Host.Addrs()}); err != nil {
		t.Fatal("remembered phone reserve after restart", err)
	}
	if _, err := d.Do(ctx, key(t), discovery.Request{Action: "claim", OwnerID: owner.ID().String(), Capability: cap, Issued: now.Unix(), Expires: now.Add(time.Hour).Unix()}); err == nil {
		t.Fatal("untrusted phone reclaimed remembered pair")
	}
}
