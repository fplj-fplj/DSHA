package relay

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/dsha/connect-core/discovery"
	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/connmgr"
	"github.com/libp2p/go-libp2p/core/control"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	rcmgr "github.com/libp2p/go-libp2p/p2p/host/resource-manager"
	circuit "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	"github.com/libp2p/go-libp2p/p2p/transport/tcp"
	"github.com/libp2p/go-libp2p/p2p/transport/websocket"
	ma "github.com/multiformats/go-multiaddr"
)

type Config struct {
	IdentityFile           string   `json:"identityFile"`
	HTTPListen             string   `json:"httpListen"`
	Listen                 []string `json:"listen"`
	PublicAddresses        []string `json:"publicAddresses"`
	TLSCertFile            string   `json:"tlsCertFile,omitempty"`
	TLSKeyFile             string   `json:"tlsKeyFile,omitempty"`
	MaxRecords             int      `json:"maxRecords"`
	MaxLeaseSeconds        int      `json:"maxLeaseSeconds"`
	MaxReservations        int      `json:"maxReservations"`
	MaxReservationsPerIP   int      `json:"maxReservationsPerIP"`
	MaxCircuitsPerPeer     int      `json:"maxCircuitsPerPeer"`
	CircuitDurationSeconds int      `json:"circuitDurationSeconds"`
	CircuitBytes           int64    `json:"circuitBytes"`
}

func DefaultConfig() Config {
	return Config{IdentityFile: "/var/lib/dsha-relay/identity.key", HTTPListen: "127.0.0.1:8080", Listen: []string{"/ip4/127.0.0.1/tcp/4001", "/ip4/127.0.0.1/tcp/4002/ws"}, MaxRecords: 512, MaxLeaseSeconds: 86400, MaxReservations: 128, MaxReservationsPerIP: 8, MaxCircuitsPerPeer: 4, CircuitDurationSeconds: 3600, CircuitBytes: 16 * 1024 * 1024 * 1024}
}

func (c Config) Validate() error {
	if c.MaxRecords < 1 || c.MaxRecords > 4096 || c.MaxLeaseSeconds < 30 || c.MaxLeaseSeconds > 86400 || c.MaxReservations < 1 || c.MaxReservations > 1024 || c.MaxReservationsPerIP < 1 || c.MaxReservationsPerIP > 128 || c.MaxCircuitsPerPeer < 1 || c.MaxCircuitsPerPeer > 16 || c.CircuitDurationSeconds < 1 || c.CircuitDurationSeconds > 86400 || c.CircuitBytes < 1024 || c.CircuitBytes > 64*1024*1024*1024 {
		return errors.New("relay quotas outside allowed bounds")
	}
	if len(c.Listen) == 0 || len(c.Listen) > 8 || len(c.PublicAddresses) > 8 {
		return errors.New("invalid listener count")
	}
	for _, s := range append(append([]string{}, c.Listen...), c.PublicAddresses...) {
		a, err := ma.NewMultiaddr(s)
		if err != nil {
			return errors.New("invalid relay multiaddr")
		}
		if _, err := a.ValueForProtocol(ma.P_P2P); err == nil {
			return errors.New("server public addresses omit peer suffix; service appends pinned identity")
		}
		if _, err := a.ValueForProtocol(ma.P_TCP); err != nil {
			return errors.New("relay listener must use TCP or WSS")
		}
	}
	if (c.TLSCertFile == "") != (c.TLSKeyFile == "") {
		return errors.New("both TLS certificate and key are required")
	}
	host, _, err := net.SplitHostPort(c.HTTPListen)
	if err != nil {
		return errors.New("invalid HTTP listen address")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("HTTP API must bind loopback behind a TLS reverse proxy")
	}
	return nil
}

type admissionGater struct{ Registry *Registry }

var _ connmgr.ConnectionGater = admissionGater{}

func (g admissionGater) InterceptPeerDial(peer.ID) bool               { return false }
func (g admissionGater) InterceptAddrDial(peer.ID, ma.Multiaddr) bool { return false }
func (g admissionGater) InterceptAccept(network.ConnMultiaddrs) bool  { return true }
func (g admissionGater) InterceptSecured(_ network.Direction, p peer.ID, a network.ConnMultiaddrs) bool {
	return g.Registry.AllowReserve(p, a.RemoteMultiaddr())
}
func (g admissionGater) InterceptUpgraded(network.Conn) (bool, control.DisconnectReason) {
	return true, 0
}

type Server struct {
	Host      host.Host
	Registry  *Registry
	Config    Config
	relay     *circuit.Relay
	http      *http.Server
	listener  net.Listener
	closed    chan struct{}
	closeOnce sync.Once
}

func New(c Config, key crypto.PrivKey) (*Server, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	r := NewRegistry(c.MaxRecords, time.Duration(c.MaxLeaseSeconds)*time.Second)
	base := rcmgr.DefaultLimits
	libp2p.SetDefaultServiceLimits(&base)
	limits := rcmgr.PartialLimitConfig{
		System:      rcmgr.ResourceLimits{Conns: 512, ConnsInbound: 512, ConnsOutbound: 16, Streams: 2048, FD: 1024, Memory: 256 << 20},
		Transient:   rcmgr.ResourceLimits{Conns: 64, Streams: 128, FD: 128, Memory: 32 << 20},
		PeerDefault: rcmgr.ResourceLimits{Conns: 4, Streams: 32, Memory: 8 << 20},
	}.Build(base.AutoScale())
	rm, err := rcmgr.NewResourceManager(rcmgr.NewFixedLimiter(limits))
	if err != nil {
		return nil, err
	}
	wsOpts := []any{websocket.WithHandshakeTimeout(10 * time.Second)}
	if c.TLSCertFile != "" {
		cert, err := tls.LoadX509KeyPair(c.TLSCertFile, c.TLSKeyFile)
		if err != nil {
			rm.Close()
			return nil, errors.New("TLS certificate could not be loaded")
		}
		wsOpts = append(wsOpts, websocket.WithTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}}))
	}
	opts := []libp2p.Option{libp2p.Identity(key), libp2p.ListenAddrStrings(c.Listen...), libp2p.NoTransports, libp2p.Transport(tcp.NewTCPTransport), libp2p.Transport(websocket.New, wsOpts...), libp2p.ResourceManager(rm), libp2p.ConnectionGater(admissionGater{r}), libp2p.DisableRelay(), libp2p.DisableMetrics()}
	if len(c.PublicAddresses) > 0 {
		addrs := make([]ma.Multiaddr, 0, len(c.PublicAddresses))
		for _, v := range c.PublicAddresses {
			a, _ := ma.NewMultiaddr(v)
			addrs = append(addrs, a)
		}
		opts = append(opts, libp2p.AddrsFactory(func([]ma.Multiaddr) []ma.Multiaddr { return addrs }))
	}
	h, err := libp2p.New(opts...)
	if err != nil {
		rm.Close()
		return nil, err
	}
	resources := circuit.DefaultResources()
	resources.ReservationTTL = time.Hour
	resources.MaxReservations = c.MaxReservations
	resources.MaxReservationsPerIP = c.MaxReservationsPerIP
	resources.MaxReservationsPerASN = c.MaxReservations
	resources.MaxCircuits = c.MaxCircuitsPerPeer
	resources.BufferSize = 4096
	resources.Limit = &circuit.RelayLimit{Duration: time.Duration(c.CircuitDurationSeconds) * time.Second, Data: c.CircuitBytes}
	relay, err := circuit.New(h, circuit.WithACL(r), circuit.WithResources(resources))
	if err != nil {
		h.Close()
		return nil, err
	}
	s := &Server{Host: h, Registry: r, Config: c, relay: relay, closed: make(chan struct{})}
	s.http = &http.Server{Handler: s.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	// Expiry/revoke also retires already-established relay connections. ACLs
	// protect each new circuit; this sweep bounds an existing connection lease.
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-s.closed:
				return
			case <-ticker.C:
				for _, p := range r.DrainRetired() {
					h.Network().ClosePeer(p)
				}
				for _, p := range h.Network().Peers() {
					if !r.AllowReserve(p, nil) {
						h.Network().ClosePeer(p)
					}
				}
			}
		}
	}()
	return s, nil
}

func (s *Server) StartHTTP() error {
	l, err := net.Listen("tcp", s.Config.HTTPListen)
	if err != nil {
		return err
	}
	s.listener = l
	go func() { _ = s.http.Serve(l) }()
	return nil
}

func (s *Server) Close() error {
	s.closeOnce.Do(func() {
		close(s.closed)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		s.http.Shutdown(ctx)
		s.relay.Close()
		s.Host.Close()
	})
	return nil
}

func (s *Server) Handler() http.Handler {
	slots := make(chan struct{}, 32)
	var mu sync.Mutex
	window := time.Now().Unix()
	requests := 0
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if r.URL.RawQuery != "" || len(r.URL.Path) > 128 {
			http.Error(w, `{"error":"invalid request"}`, http.StatusBadRequest)
			return
		}
		if r.Method == http.MethodGet && (r.URL.Path == "/healthz" || r.URL.Path == "/readyz") {
			io.WriteString(w, `{"ready":true}`)
			return
		}
		if r.Method == http.MethodGet && r.URL.Path == "/v1/info" {
			json.NewEncoder(w).Encode(map[string]any{"protocol": 1, "relayPeerId": s.Host.ID().String(), "addresses": s.Addresses(), "quotas": map[string]any{"reservations": s.Config.MaxReservations, "circuitsPerPeer": s.Config.MaxCircuitsPerPeer, "circuitBytes": s.Config.CircuitBytes, "circuitSeconds": s.Config.CircuitDurationSeconds}})
			return
		}
		if r.Method != http.MethodPost || r.URL.Path != "/v1/record" {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
			return
		}
		select {
		case slots <- struct{}{}:
			defer func() { <-slots }()
		default:
			http.Error(w, `{"error":"busy"}`, http.StatusTooManyRequests)
			return
		}
		mu.Lock()
		now := time.Now().Unix()
		if now != window {
			window = now
			requests = 0
		}
		requests++
		limited := requests > 100
		mu.Unlock()
		if limited {
			http.Error(w, `{"error":"rate limited"}`, http.StatusTooManyRequests)
			return
		}
		if r.Header.Get("Content-Type") != "application/json" {
			http.Error(w, `{"error":"content type"}`, http.StatusUnsupportedMediaType)
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, discovery.MaxBody))
		if err != nil {
			http.Error(w, `{"error":"request too large"}`, http.StatusRequestEntityTooLarge)
			return
		}
		var req discovery.Request
		dec := json.NewDecoder(bytes.NewReader(body))
		dec.DisallowUnknownFields()
		if dec.Decode(&req) != nil || dec.Decode(new(any)) != io.EOF {
			http.Error(w, `{"error":"invalid request"}`, http.StatusBadRequest)
			return
		}
		record, err := s.Registry.Apply(req)
		if err != nil {
			code := http.StatusForbidden
			if errors.Is(err, ErrCapacity) {
				code = http.StatusTooManyRequests
			}
			http.Error(w, `{"error":"request rejected"}`, code)
			return
		}
		if req.Action == "revoke" {
			for _, id := range []string{record.OwnerID, record.PeerID} {
				if p, err := peer.Decode(id); err == nil {
					s.Host.Network().ClosePeer(p)
				}
			}
		}
		json.NewEncoder(w).Encode(record)
	})
}

func (s *Server) Addresses() []string {
	out := []string{}
	for _, a := range s.Host.Addrs() {
		out = append(out, a.String()+"/p2p/"+s.Host.ID().String())
	}
	return out
}
