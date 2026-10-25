package mobile

import (
	"context"
	"encoding/json"
	core "github.com/dsha/connect-core"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAcceptTimeoutIsPollingAndCloseCancels(t *testing.T) {
	c, err := NewClient(`{"listenAddrs":["/ip4/127.0.0.1/tcp/0"],"generation":5}`)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	start := time.Now()
	pipe, err := c.Accept(30)
	if err != nil || pipe != nil {
		t.Fatal("poll must return nil without retiring connection", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("poll blocked")
	}
	if c.Generation() != 5 || c.IdentityBase64() == "" || c.LocalPeerID() == "" {
		t.Fatal("identity missing")
	}
	_ = c.Close()
	if _, err := c.Accept(30); err == nil {
		t.Fatal("closed node must error")
	}
}
func TestCachedLANAuthenticatesWithoutDiscoveryAvailability(t *testing.T) {
	key := strings.Repeat("ab", 32)
	pc, err := core.New(core.Config{ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}, PairingKeyHex: key, PinFirstPeer: true, PairingExpiresUnix: time.Now().Add(time.Minute).Unix()})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	renewalStarted := make(chan struct{}, 1)
	renewalCanceled := make(chan struct{}, 1)
	releaseHandler := make(chan struct{})
	discovery := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		select {
		case renewalStarted <- struct{}{}:
		default:
		}
		select {
		case <-r.Context().Done():
		case <-releaseHandler:
		}
		select {
		case renewalCanceled <- struct{}{}:
		default:
		}
	}))
	defer func() { close(releaseHandler); discovery.Close() }()
	config, _ := json.Marshal(map[string]any{"trustedPeerId": pc.PeerID(), "pairingKeyHex": key, "addresses": pc.Addresses(), "listenAddrs": []string{"/ip4/127.0.0.1/tcp/0"}, "discoveryURL": discovery.URL, "discoveryToken": strings.Repeat("cd", 32)})
	c, err := NewClient(string(config))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	started := time.Now()
	if err = c.Connect(10000); err != nil {
		t.Fatal(err)
	}
	if time.Since(started) > 3*time.Second {
		t.Fatal("working cached LAN connection blocked on rendezvous")
	}
	pipe, err := c.Open(core.Control, 5000)
	if err != nil {
		t.Fatal(err)
	}
	defer pipe.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	accepted, err := pc.Accept(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer accepted.Close()
	if pc.TrustedPeerID() != c.LocalPeerID() {
		t.Fatal("cached route bypassed pair-key identity proof")
	}
	select {
	case <-renewalStarted:
	case <-time.After(time.Second):
		t.Fatal("connected peer did not start independent signed lease renewal")
	}
	_ = c.Close()
	select {
	case <-renewalCanceled:
	case <-time.After(time.Second):
		t.Fatal("close did not cancel pending discovery renewal")
	}
}
func TestDiscoveryAddressesMergeDeduplicatesWithinDialBudget(t *testing.T) {
	preferred := []string{"a", "a", "b"}
	cached := []string{"b", "c"}
	if got := strings.Join(mergeAddresses(preferred, cached), ","); got != "a,b,c" {
		t.Fatal(got)
	}
	many := make([]string, 40)
	for i := range many {
		many[i] = strings.Repeat("x", i+1)
	}
	if len(mergeAddresses(many, cached)) != 16 {
		t.Fatal("merged addresses exceed dial budget")
	}
}
func TestConfigurationBounds(t *testing.T) {
	if c, err := NewClient(`{"trustedPeerId":"bad"}`); err == nil {
		_ = c.Close()
		t.Fatal("invalid trust accepted")
	}
	if c, err := NewClient(`{"pairingKeyHex":"00"}`); err == nil {
		_ = c.Close()
		t.Fatal("short key accepted")
	}
}
