package connectcore

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"io"
	"strings"
	"testing"
	"time"

	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
	relay "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
)

func key(t *testing.T) string {
	t.Helper()
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}
func node(t *testing.T, g int64) *Node {
	t.Helper()
	n, err := New(Config{ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}, Generation: g})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = n.Close() })
	return n
}
func pair(t *testing.T) (*Node, *Node) {
	t.Helper()
	a, b := node(t, 17), node(t, 28)
	k := key(t)
	if err := a.SetTrust(b.PeerID(), k); err != nil {
		t.Fatal(err)
	}
	if err := b.SetTrust(a.PeerID(), k); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := a.Connect(ctx, b.Addresses()); err != nil {
		t.Fatal(err)
	}
	return a, b
}
func ctx(t *testing.T) (context.Context, context.CancelFunc) {
	t.Helper()
	return context.WithTimeout(context.Background(), 10*time.Second)
}
func exchange(t *testing.T, a, b *Node, kind string) {
	t.Helper()
	c, cancel := ctx(t)
	defer cancel()
	s, err := a.Open(c, kind)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	r, err := b.Accept(c)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if r.Kind != kind || r.RemoteGeneration != a.Generation() || s.RemoteGeneration != b.Generation() {
		t.Fatal("stream identity mismatch")
	}
	payload := bytes.Repeat([]byte(kind+"\x00\xff"), 4096)
	done := make(chan error, 1)
	go func() { _, err := s.Write(payload); _ = s.CloseWrite(); done <- err }()
	received, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, received) {
		t.Fatal("byte corruption")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if _, err := r.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(s, buf); err != nil || string(buf) != "ok" {
		t.Fatal("duplex/halfclose", err)
	}
}
func TestDirectThreeIndependentStreams(t *testing.T) {
	a, b := pair(t)
	for _, k := range []string{Control, HTTP, File} {
		exchange(t, a, b, k)
	}
	if a.Route() != "direct" {
		t.Fatal(a.Route())
	}
	c, cancel := ctx(t)
	defer cancel()
	if ms, err := a.RoundTripMillis(c); err != nil || ms < 0 {
		t.Fatal(ms, err)
	}
}
func TestQUICDirectDuplex(t *testing.T) {
	a, err := New(Config{ListenAddrs: []string{"/ip4/127.0.0.1/udp/0/quic-v1"}, Generation: 11})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := New(Config{ListenAddrs: []string{"/ip4/127.0.0.1/udp/0/quic-v1"}, Generation: 12})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	k := key(t)
	_ = a.SetTrust(b.PeerID(), k)
	_ = b.SetTrust(a.PeerID(), k)
	c, cancel := ctx(t)
	defer cancel()
	if err := a.Connect(c, b.Addresses()); err != nil {
		t.Fatal(err)
	}
	exchange(t, a, b, Control)
	if a.Route() != "direct" {
		t.Fatal(a.Route())
	}
}
func TestDisconnectCannotImplicitlyRedialOldEpoch(t *testing.T) {
	a, b := pair(t)
	exchange(t, a, b, Control)
	before := a.ConnectionEpoch()
	if err := a.h.Network().ClosePeer(b.h.ID()); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for a.ConnectionEpoch() == before && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if a.ConnectionEpoch() == before || a.IsConnected() {
		t.Fatal("disconnect did not retire authenticated epoch")
	}
	c, cancel := ctx(t)
	defer cancel()
	if s, err := a.Open(c, Control); err == nil {
		_ = s.Close()
		t.Fatal("old epoch implicitly reconnected")
	}
	if err := a.Connect(c, b.Addresses()); err != nil {
		t.Fatal(err)
	}
	exchange(t, a, b, Control)
	if a.ConnectionEpoch() == before {
		t.Fatal("new transport reused old epoch")
	}
}
func TestUntrustedAddressRejected(t *testing.T) {
	a, b := pair(t)
	rogue := node(t, 1)
	c, cancel := ctx(t)
	defer cancel()
	if err := a.Connect(c, rogue.Addresses()); err == nil {
		t.Fatal("wrong identity accepted")
	}
	exchange(t, a, b, Control)
}
func TestWrongPairingKeyRejectsApplicationData(t *testing.T) {
	a, b := pair(t)
	if err := b.SetTrust(a.PeerID(), key(t)); err != nil {
		t.Fatal(err)
	}
	c, cancel := ctx(t)
	defer cancel()
	if s, err := a.Open(c, Control); err == nil {
		_ = s.Close()
		t.Fatal("wrong key accepted")
	}
}
func TestFirstPairingPinsOnlyAuthenticatedPeer(t *testing.T) {
	a := node(t, 1)
	k := key(t)
	b, err := New(Config{ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}, PairingKeyHex: k, PinFirstPeer: true, PairingExpiresUnix: time.Now().Add(time.Minute).Unix()})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err := a.SetTrust(b.PeerID(), k); err != nil {
		t.Fatal(err)
	}
	c, cancel := ctx(t)
	defer cancel()
	if err := a.Connect(c, b.Addresses()); err != nil {
		t.Fatal(err)
	}
	exchange(t, a, b, Control)
	if b.TrustedPeerID() != a.PeerID() {
		t.Fatal("first peer not pinned")
	}
	third := node(t, 3)
	if err := third.SetTrust(b.PeerID(), k); err != nil {
		t.Fatal(err)
	}
	if err := third.Connect(c, b.Addresses()); err != nil {
		t.Fatal(err)
	}
	if s, err := third.Open(c, Control); err == nil {
		_ = s.Close()
		t.Fatal("second peer accepted")
	}
}
func TestStreamBoundsAndControlPriority(t *testing.T) {
	a, b := pair(t)
	c, cancel := ctx(t)
	defer cancel()
	var streams []*Stream
	for i := 0; i < 4; i++ {
		s, err := a.Open(c, File)
		if err != nil {
			t.Fatal(err)
		}
		r, err := b.Accept(c)
		if err != nil {
			t.Fatal(err)
		}
		streams = append(streams, s, r)
	}
	defer func() {
		for _, s := range streams {
			_ = s.Close()
		}
	}()
	short, stop := context.WithTimeout(c, 80*time.Millisecond)
	defer stop()
	if s, err := a.Open(short, File); err == nil {
		_ = s.Close()
		t.Fatal("file concurrency exceeded")
	}
	exchange(t, a, b, Control)
	if _, err := a.Open(c, "arbitrary"); err == nil {
		t.Fatal("unknown protocol accepted")
	}
}
func TestRevocationAndGenerationCloseBlockedReaders(t *testing.T) {
	a, b := pair(t)
	c, cancel := ctx(t)
	defer cancel()
	s, err := a.Open(c, Control)
	if err != nil {
		t.Fatal(err)
	}
	r, err := b.Accept(c)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	done := make(chan error, 1)
	go func() { buf := make([]byte, 1); _, err := s.Read(buf); done <- err }()
	if err := a.SetTrust(b.PeerID(), key(t)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("read succeeded after revoke")
		}
	case <-time.After(time.Second):
		t.Fatal("revoked stream stayed blocked")
	}
	_ = a.Close()
	if _, err := a.Open(c, Control); err == nil {
		t.Fatal("retired node opened stream")
	}
	if a.Route() != "disconnected" {
		t.Fatal(a.Route())
	}
}
func TestIdentityPersistenceAndExpiredPairing(t *testing.T) {
	a := node(t, 1)
	copy, err := New(Config{IdentityBase64: a.IdentityBase64(), ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}})
	if err != nil {
		t.Fatal(err)
	}
	defer copy.Close()
	if copy.PeerID() != a.PeerID() {
		t.Fatal("identity changed")
	}
	if n, err := New(Config{PairingKeyHex: key(t), PinFirstPeer: true, PairingExpiresUnix: time.Now().Add(-time.Second).Unix()}); err == nil {
		_ = n.Close()
		t.Fatal("expired pairing accepted")
	}
}
func TestStagedPairingKeyRecoversLostAcknowledgement(t *testing.T) {
	a, b := pair(t)
	next := key(t)
	if err := b.StageTrustKey(next, time.Now().Add(time.Minute).Unix()); err != nil {
		t.Fatal(err)
	}
	exchange(t, a, b, Control)
	if err := a.SetTrust(b.PeerID(), next); err != nil {
		t.Fatal(err)
	}
	exchange(t, a, b, Control)
	b.mu.Lock()
	pending := len(b.stagedKey)
	committed := hex.EncodeToString(b.key)
	b.mu.Unlock()
	if pending != 0 || committed != next {
		t.Fatal("staged proof did not commit")
	}
	exchange(t, b, a, Control)
}
func TestForcedRelayPreservesAuthenticatedDuplex(t *testing.T) {
	relayHost, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer relayHost.Close()
	service, err := relay.New(relayHost)
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	a, b := node(t, 1), node(t, 2)
	k := key(t)
	_ = a.SetTrust(b.PeerID(), k)
	_ = b.SetTrust(a.PeerID(), k)
	c, cancel := ctx(t)
	defer cancel()
	relayAddr := relayHost.Addrs()[0].String() + "/p2p/" + relayHost.ID().String()
	addr, err := b.ReserveRelay(c, relayAddr)
	if err != nil {
		t.Fatal(err)
	}
	c = network.WithAllowLimitedConn(c, "relay integration test")
	if err := a.Connect(c, []string{addr}); err != nil {
		t.Fatal(err)
	}
	if a.Route() != "relay" {
		t.Fatal("did not use circuit", a.Route())
	}
	if !strings.Contains(addr, "/p2p-circuit/") {
		t.Fatal(addr)
	}
	exchange(t, a, b, HTTP)
}
