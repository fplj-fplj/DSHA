package relay

import (
	"crypto/rand"
	"testing"
	"time"

	"github.com/dsha/connect-core/discovery"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
)

func key(t *testing.T) crypto.PrivKey {
	t.Helper()
	k, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}
func id(k crypto.PrivKey) peer.ID { p, _ := peer.IDFromPrivateKey(k); return p }
func signed(t *testing.T, k crypto.PrivKey, r discovery.Request) discovery.Request {
	t.Helper()
	if r.Expires == 0 {
		r.Expires = time.Now().Add(time.Hour).Unix()
	}
	v, err := discovery.Sign(k, r)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func TestRegistryPairAuthorizationAndReplay(t *testing.T) {
	r := NewRegistry(4, 24*time.Hour)
	a, b, c := key(t), key(t), key(t)
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	req := signed(t, a, discovery.Request{Action: "advertise", CapabilityHash: hash})
	if _, err := r.Apply(req); err != nil {
		t.Fatal(err)
	}
	if _, err := r.Apply(req); err == nil {
		t.Fatal("replay accepted")
	}
	if r.AllowConnect(id(b), nil, id(a)) || r.AllowReserve(id(b), nil) {
		t.Fatal("unclaimed peer allowed")
	}
	if _, err := r.Apply(signed(t, b, discovery.Request{Action: "claim", OwnerID: id(a).String(), Capability: cap})); err != nil {
		t.Fatal(err)
	}
	if !r.AllowConnect(id(b), nil, id(a)) || !r.AllowConnect(id(a), nil, id(b)) {
		t.Fatal("paired connection denied")
	}
	if _, err := r.Apply(signed(t, c, discovery.Request{Action: "claim", OwnerID: id(a).String(), Capability: cap})); err == nil {
		t.Fatal("invitation reassigned")
	}
	if r.AllowConnect(id(c), nil, id(a)) {
		t.Fatal("untrusted connection allowed")
	}
	tamper := signed(t, a, discovery.Request{Action: "lookup", OwnerID: id(a).String(), Capability: cap})
	tamper.Expires++
	if _, err := r.Apply(tamper); err == nil {
		t.Fatal("tampered signature accepted")
	}
	if _, err := r.Apply(signed(t, b, discovery.Request{Action: "revoke", OwnerID: id(a).String()})); err != nil {
		t.Fatal(err)
	}
	if r.AllowConnect(id(b), nil, id(a)) || r.AllowReserve(id(a), nil) {
		t.Fatal("revoked pair allowed")
	}
}

func TestRegistryBoundsAndExpiry(t *testing.T) {
	r := NewRegistry(1, time.Hour)
	a, b := key(t), key(t)
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	if _, err := r.Apply(signed(t, a, discovery.Request{Action: "advertise", CapabilityHash: hash})); err != nil {
		t.Fatal(err)
	}
	if _, err := r.Apply(signed(t, b, discovery.Request{Action: "advertise", CapabilityHash: hash})); err != ErrCapacity {
		t.Fatal("record bound not enforced", err)
	}
	now := time.Now().Add(6 * time.Minute)
	r.now = func() time.Time { return now }
	if _, err := r.Apply(signed(t, b, discovery.Request{Action: "claim", OwnerID: id(a).String(), Capability: cap})); err == nil {
		t.Fatal("expired QR claim accepted")
	}
	r.now = func() time.Time { return now.Add(time.Hour) }
	if r.Count() != 0 || r.AllowReserve(id(a), nil) {
		t.Fatal("expired advertisement retained")
	}
	config := DefaultConfig()
	config.CircuitBytes = 64*1024*1024*1024 + 1
	if config.Validate() == nil {
		t.Fatal("unbounded relay data accepted")
	}
	config = DefaultConfig()
	config.HTTPListen = "0.0.0.0:8080"
	if config.Validate() == nil {
		t.Fatal("public plaintext discovery allowed")
	}
}

func TestRememberedPairRestoresAfterRestartAndExpiry(t *testing.T) {
	owner, phone, other := key(t), key(t), key(t)
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	now := time.Now()
	signNow := func(k crypto.PrivKey, req discovery.Request) discovery.Request {
		req.Issued = now.Unix()
		req.Expires = now.Add(time.Hour).Unix()
		return signed(t, k, req)
	}
	advertise := func(confirmed string) discovery.Request {
		return signNow(owner, discovery.Request{Action: "advertise", CapabilityHash: hash, ConfirmedPeerID: confirmed})
	}
	claim := func(k crypto.PrivKey) discovery.Request {
		return signNow(k, discovery.Request{Action: "claim", OwnerID: id(owner).String(), Capability: cap})
	}
	r := NewRegistry(4, 24*time.Hour)
	r.now = func() time.Time { return now }
	if _, err := r.Apply(advertise("")); err != nil {
		t.Fatal(err)
	}
	if _, err := r.Apply(claim(phone)); err != nil {
		t.Fatal(err)
	}
	// Replacing the registry models the complete RAM-only loss on service
	// restart, including peer bindings and replay cache. Identity stays stable.
	r = NewRegistry(4, 24*time.Hour)
	r.now = func() time.Time { return now }
	if _, err := r.Apply(advertise(id(phone).String())); err != nil {
		t.Fatal("owner restore", err)
	}
	if r.AllowReserve(id(phone), nil) || r.AllowConnect(id(owner), nil, id(phone)) {
		t.Fatal("owner confirmation granted phone lease without phone signature")
	}
	now = now.Add(6 * time.Minute)
	if _, err := r.Apply(claim(phone)); err != nil {
		t.Fatal("remembered phone denied after restart and QR window", err)
	}
	if !r.AllowConnect(id(owner), nil, id(phone)) {
		t.Fatal("restored phone connection denied")
	}
	if _, err := r.Apply(claim(other)); err == nil {
		t.Fatal("wrong phone claimed remembered binding")
	}
	if _, err := r.Apply(advertise(id(other).String())); err == nil {
		t.Fatal("owner silently replaced existing remembered peer")
	}
	// Expiring the record has the same restoration behavior. Updating the
	// owner's presence cannot resurrect an expired phone lease by itself.
	now = now.Add(61 * time.Minute)
	if r.Count() != 0 {
		t.Fatal("record did not expire")
	}
	if _, err := r.Apply(advertise(id(phone).String())); err != nil {
		t.Fatal("expired record restore", err)
	}
	if r.AllowConnect(id(owner), nil, id(phone)) {
		t.Fatal("expired phone lease resurrected by owner")
	}
	now = now.Add(6 * time.Minute)
	if _, err := r.Apply(claim(phone)); err != nil {
		t.Fatal("remembered phone denied after record expiry", err)
	}
	// A different owner cannot sign a restoration for this owner's record.
	takeover := signNow(other, discovery.Request{Action: "advertise", OwnerID: id(owner).String(), CapabilityHash: hash, ConfirmedPeerID: id(other).String()})
	if _, err := r.Apply(takeover); err == nil {
		t.Fatal("foreign owner record takeover accepted")
	}
	tamper := advertise(id(phone).String())
	tamper.ConfirmedPeerID = id(other).String()
	if _, err := r.Apply(tamper); err == nil {
		t.Fatal("tampered confirmed peer signature accepted")
	}
}

func TestUnpairedRenewalDoesNotExtendFirstQRClaim(t *testing.T) {
	r := NewRegistry(4, 24*time.Hour)
	owner, phone := key(t), key(t)
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	now := time.Now()
	r.now = func() time.Time { return now }
	apply := func(k crypto.PrivKey, req discovery.Request) error {
		req.Issued = now.Unix()
		req.Expires = now.Add(time.Hour).Unix()
		_, err := r.Apply(signed(t, k, req))
		return err
	}
	for i := 0; i < 4; i++ {
		if err := apply(owner, discovery.Request{Action: "advertise", CapabilityHash: hash}); err != nil {
			t.Fatal(err)
		}
		if i < 3 {
			now = now.Add(2 * time.Minute)
		}
	}
	if err := apply(phone, discovery.Request{Action: "claim", OwnerID: id(owner).String(), Capability: cap}); err == nil {
		t.Fatal("unpaired QR claim window extended by presence renewals")
	}
}
