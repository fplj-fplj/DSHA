package discovery

import (
	"context"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/crypto"
)

func TestSignIdentityTimestampAndTamper(t *testing.T) {
	k, _, _ := crypto.GenerateEd25519Key(rand.Reader)
	r, err := Sign(k, Request{Action: "advertise", Expires: time.Now().Add(time.Hour).Unix()})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(r, time.Now(), time.Hour); err != nil {
		t.Fatal(err)
	}
	r.Action = "revoke"
	if _, err := Verify(r, time.Now(), time.Hour); err == nil {
		t.Fatal("tampered signed request accepted")
	}
	r, _ = Sign(k, Request{Action: "advertise", Expires: time.Now().Add(time.Hour).Unix(), Issued: time.Now().Add(-3 * time.Minute).Unix()})
	if _, err := Verify(r, time.Now(), time.Hour); err == nil {
		t.Fatal("old signature accepted")
	}
	if _, err := CapabilityHash("short"); err == nil {
		t.Fatal("short capability accepted")
	}
}

func TestClientNeverRedirectsCapability(t *testing.T) {
	forwarded := false
	dest := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forwarded = true }))
	defer dest.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, dest.URL, http.StatusTemporaryRedirect)
	}))
	defer source.Close()
	k, _, _ := crypto.GenerateEd25519Key(rand.Reader)
	_, err := (Client{URL: source.URL, HTTP: source.Client()}).Do(context.Background(), k, Request{Action: "claim", Capability: "never-forward", Expires: time.Now().Add(time.Hour).Unix()})
	if err == nil || forwarded {
		t.Fatal("capability redirect followed")
	}
	_, err = (Client{URL: "http://example.com"}).Do(context.Background(), k, Request{})
	if err == nil {
		t.Fatal("remote plaintext discovery accepted")
	}
}
