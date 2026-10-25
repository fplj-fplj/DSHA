package relay

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/dsha/connect-core/discovery"
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	client "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	"github.com/libp2p/go-libp2p/p2p/transport/websocket"
)

func TestWSSWithUDPAbsentAndCertificatePin(t *testing.T) {
	keyTLS, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(195), Subject: pkix.Name{CommonName: "localhost"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &keyTLS.PublicKey, keyTLS)
	if err != nil {
		t.Fatal(err)
	}
	privateDER, _ := x509.MarshalECPrivateKey(keyTLS)
	dir := t.TempDir()
	certFile, keyFile := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER}), 0600)
	os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: privateDER}), 0600)
	c := DefaultConfig()
	c.Listen = []string{"/ip4/127.0.0.1/tcp/0/tls/ws"}
	c.TLSCertFile = certFile
	c.TLSKeyFile = keyFile
	s, err := New(c, key(t))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	api := httptest.NewServer(s.Handler())
	defer api.Close()
	k := key(t)
	cap, _ := discovery.NewCapability()
	hash, _ := discovery.CapabilityHash(cap)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if _, err := (discovery.Client{URL: api.URL}).Do(ctx, k, discovery.Request{Action: "advertise", CapabilityHash: hash, Expires: time.Now().Add(time.Hour).Unix()}); err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	cert, _ := x509.ParseCertificate(certDER)
	pool.AddCert(cert)
	h, err := libp2p.New(libp2p.Identity(k), libp2p.NoListenAddrs, libp2p.NoTransports, libp2p.Transport(websocket.New, websocket.WithTLSClientConfig(&tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool})), libp2p.DisableMetrics())
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	if _, err := client.Reserve(ctx, h, peer.AddrInfo{ID: s.Host.ID(), Addrs: s.Host.Addrs()}); err != nil {
		t.Fatal("WSS TCP-only reservation", err)
	}
	if h.Network().ConnsToPeer(s.Host.ID())[0].ConnState().Transport != "websocket" {
		t.Fatal("WSS path not used")
	}
	// Certificate verification is real: the same node identity without the CA
	// cannot connect, even though its peer ID has permission to reserve.
	untrustedTLS, err := libp2p.New(libp2p.Identity(k), libp2p.NoListenAddrs, libp2p.NoTransports, libp2p.Transport(websocket.New), libp2p.DisableMetrics())
	if err != nil {
		t.Fatal(err)
	}
	defer untrustedTLS.Close()
	if err := untrustedTLS.Connect(ctx, peer.AddrInfo{ID: s.Host.ID(), Addrs: s.Host.Addrs()}); err == nil {
		t.Fatal("untrusted TLS certificate accepted")
	}
	wrongPin := id(key(t))
	if err := h.Connect(ctx, peer.AddrInfo{ID: wrongPin, Addrs: s.Host.Addrs()}); err == nil {
		t.Fatal("wrong relay identity accepted")
	}
}
