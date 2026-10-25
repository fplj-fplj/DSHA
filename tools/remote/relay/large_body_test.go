package relay

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/dsha/connect-core/discovery"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	client "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	ma "github.com/multiformats/go-multiaddr"
)

type patternReader struct{}

func (patternReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = byte(i % 251)
	}
	return len(p), nil
}

func TestForcedRelayLargeBody(t *testing.T) {
	setting := os.Getenv("DSHA_RELAY_LARGE_BODY")
	if setting == "" {
		t.Skip("explicit DSHA_RELAY_LARGE_BODY byte count required")
	}
	count, err := strconv.ParseInt(setting, 10, 64)
	if err != nil || count < 1024 || count > 5_000_000_000 {
		t.Fatal("large body count must be 1024..5000000000")
	}
	c := DefaultConfig()
	c.Listen = []string{"/ip4/127.0.0.1/tcp/0"}
	s, err := New(c, key(t))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	aKey, bKey := key(t), key(t)
	a, b := testNode(t, aKey, "/ip4/127.0.0.1/tcp/0"), testNode(t, bKey, "/ip4/127.0.0.1/tcp/0")
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	if _, err := s.Registry.Apply(signed(t, aKey, discovery.Request{Action: "advertise", CapabilityHash: hash})); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Registry.Apply(signed(t, bKey, discovery.Request{Action: "claim", OwnerID: a.ID().String(), Capability: cap})); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	if _, err := client.Reserve(ctx, a, peer.AddrInfo{ID: s.Host.ID(), Addrs: s.Host.Addrs()}); err != nil {
		t.Fatal(err)
	}
	proto := protocol.ID("/dsha/relay-large-fixture/1")
	type receipt struct {
		Bytes  int64  `json:"bytes"`
		SHA256 string `json:"sha256"`
	}
	a.SetStreamHandler(proto, func(st network.Stream) {
		defer st.Close()
		st.SetDeadline(time.Now().Add(5 * time.Minute))
		h := sha256.New()
		n, err := io.CopyN(h, st, count)
		if err != nil {
			st.Reset()
			return
		}
		json.NewEncoder(st).Encode(receipt{n, hex.EncodeToString(h.Sum(nil))})
	})
	route, _ := ma.NewMultiaddr(s.Host.Addrs()[0].String() + "/p2p/" + s.Host.ID().String() + "/p2p-circuit/p2p/" + a.ID().String())
	info, _ := peer.AddrInfoFromP2pAddr(route)
	if err := b.Connect(ctx, *info); err != nil {
		t.Fatal(err)
	}
	st, err := b.NewStream(network.WithAllowLimitedConn(ctx, "large-payload-fixture"), a.ID(), proto)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if !st.Conn().Stat().Limited {
		t.Fatal("large body bypassed relay")
	}
	st.SetDeadline(time.Now().Add(5 * time.Minute))
	h := sha256.New()
	started := time.Now()
	n, err := io.CopyN(io.MultiWriter(st, h), patternReader{}, count)
	if err != nil {
		t.Fatal("large body write", n, err)
	}
	st.CloseWrite()
	var got receipt
	if err := json.NewDecoder(io.LimitReader(st, 1024)).Decode(&got); err != nil {
		t.Fatal("large body receipt", err)
	}
	if got.Bytes != count || got.SHA256 != hex.EncodeToString(h.Sum(nil)) {
		t.Fatal("large body mismatch", got.Bytes)
	}
	t.Logf("forcedRelay=true udpListeners=0 bodyBytes=%d relayBudgetPerDirection=%d sha256=%s elapsedSeconds=%.3f", n, c.CircuitBytes, got.SHA256, time.Since(started).Seconds())
}
