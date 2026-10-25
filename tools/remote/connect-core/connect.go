// Package connectcore carries independent authenticated byte streams over libp2p.
// Noise authenticates the pinned endpoint keys even when a circuit relay forwards
// traffic. A separate high entropy pairing key authorizes application streams.
package connectcore

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dsha/connect-core/discovery"

	libp2p "github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/muxer/yamux"
	relayclient "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	"github.com/libp2p/go-libp2p/p2p/protocol/ping"
	"github.com/libp2p/go-libp2p/p2p/security/noise"
	quic "github.com/libp2p/go-libp2p/p2p/transport/quic"
	"github.com/libp2p/go-libp2p/p2p/transport/tcp"
	ma "github.com/multiformats/go-multiaddr"
)

const (
	Control            = "control"
	HTTP               = "http"
	File               = "file"
	MaxChunk           = 65536
	MaxFileBytes int64 = 5000000000
	// The payload owner enforces MaxFileBytes; stream framing/HTTP headers need
	// their own bounded allowance so a legal 5 GB body is not truncated.
	MaxStreamBytes int64 = MaxFileBytes + 16*1024*1024
)

var protocols = map[string]protocol.ID{Control: "/dsha-connect/control/1.0.0", HTTP: "/dsha-connect/dsh-http/1.0.0", File: "/dsha-connect/file/1.0.0"}

type Config struct {
	IdentityBase64     string   `json:"identityBase64"`
	TrustedPeerID      string   `json:"trustedPeerId"`
	PairingKeyHex      string   `json:"pairingKeyHex"`
	ListenAddrs        []string `json:"listenAddrs"`
	RelayAddrs         []string `json:"relayAddrs"`
	Generation         int64    `json:"generation"`
	PinFirstPeer       bool     `json:"pinFirstPeer"`
	PairingExpiresUnix int64    `json:"pairingExpiresUnix"`
	DiscoveryURL       string   `json:"discoveryURL,omitempty"`
	DiscoveryToken     string   `json:"discoveryToken,omitempty"`
}

type Node struct {
	h                host.Host
	ctx              context.Context
	cancel           context.CancelFunc
	mu               sync.Mutex
	trusted          peer.ID
	key              []byte
	stagedKey        []byte
	stagedExpires    int64
	pinFirst         bool
	expires          int64
	generation       int64
	identity         string
	closed           bool
	incoming         chan *Stream
	streams          map[*Stream]bool
	limits           map[string]chan struct{}
	relays           []string
	connectionEpoch  int64
	boundConnections map[string]bool
	broken           bool
}

// Stream is admitted only after endpoint and pairing-key authentication. No
// application request is retried by this package, including on reconnection.
type Stream struct {
	network.Stream
	Kind             string
	Generation       int64
	RemoteGeneration int64
	node             *Node
	slot             chan struct{}
	once             sync.Once
	readBytes        atomic.Int64
	writtenBytes     atomic.Int64
	writeMu          sync.Mutex
}

func New(c Config) (*Node, error) {
	if c.Generation < 0 {
		return nil, errors.New("negative generation")
	}
	var sk crypto.PrivKey
	var err error
	if c.IdentityBase64 == "" {
		sk, _, err = crypto.GenerateEd25519Key(rand.Reader)
	} else {
		var raw []byte
		raw, err = base64.StdEncoding.DecodeString(c.IdentityBase64)
		if err == nil {
			sk, err = crypto.UnmarshalPrivateKey(raw)
		}
	}
	if err != nil {
		return nil, fmt.Errorf("identity: %w", err)
	}
	raw, err := crypto.MarshalPrivateKey(sk)
	if err != nil {
		return nil, err
	}
	var trusted peer.ID
	if c.TrustedPeerID != "" {
		trusted, err = peer.Decode(c.TrustedPeerID)
		if err != nil {
			return nil, errors.New("invalid trusted peer")
		}
	}
	var key []byte
	if c.PairingKeyHex != "" {
		key, err = hex.DecodeString(c.PairingKeyHex)
		if err != nil || len(key) != 32 {
			return nil, errors.New("pairing key must be 32 random bytes encoded as hex")
		}
	}
	if trusted != "" && len(key) != 32 {
		return nil, errors.New("trusted peer requires pairing key")
	}
	if c.PinFirstPeer && (len(key) != 32 || c.PairingExpiresUnix <= time.Now().Unix() || c.PairingExpiresUnix > time.Now().Add(10*time.Minute).Unix()) {
		return nil, errors.New("pairing window must expire within ten minutes")
	}
	listen := c.ListenAddrs
	if len(listen) == 0 {
		listen = []string{"/ip4/0.0.0.0/tcp/0", "/ip4/0.0.0.0/udp/0/quic-v1"}
	}
	if len(listen) > 8 || len(c.RelayAddrs) > 4 {
		return nil, errors.New("too many addresses")
	}
	h, err := libp2p.New(libp2p.Identity(sk), libp2p.ListenAddrStrings(listen...), libp2p.NoTransports, libp2p.Transport(tcp.NewTCPTransport), libp2p.Transport(quic.NewTransport), libp2p.Security(noise.ID, noise.New), libp2p.Muxer(yamux.ID, yamux.DefaultTransport), libp2p.EnableRelay(), libp2p.EnableHolePunching(), libp2p.DisableMetrics())
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &Node{h: h, ctx: ctx, cancel: cancel, trusted: trusted, key: key, pinFirst: c.PinFirstPeer, expires: c.PairingExpiresUnix, generation: c.Generation, identity: base64.StdEncoding.EncodeToString(raw), incoming: make(chan *Stream, 32), streams: make(map[*Stream]bool), limits: map[string]chan struct{}{Control: make(chan struct{}, 8), HTTP: make(chan struct{}, 24), File: make(chan struct{}, 4)}}
	n.connectionEpoch = 1
	n.boundConnections = make(map[string]bool)
	h.Network().Notify(&network.NotifyBundle{DisconnectedF: func(_ network.Network, c network.Conn) { n.disconnected(c) }})
	for kind, pid := range protocols {
		k := kind
		h.SetStreamHandler(pid, func(s network.Stream) { n.receive(k, s) })
	}
	for _, addr := range c.RelayAddrs {
		n.relays = append(n.relays, addr)
	}
	return n, nil
}

func (n *Node) PeerID() string         { return n.h.ID().String() }
func (n *Node) IdentityBase64() string { return n.identity }
func (n *Node) Generation() int64      { return n.generation }
func (n *Node) TrustedPeerID() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.trusted == "" {
		return ""
	}
	return n.trusted.String()
}
func (n *Node) Addresses() []string {
	var candidates []string
	seen := make(map[string]bool)
	for _, a := range n.h.Addrs() {
		value := a.String() + "/p2p/" + n.PeerID()
		if !seen[value] {
			seen[value] = true
			candidates = append(candidates, value)
		}
	}
	sort.Slice(candidates, func(i, j int) bool {
		a, b := addressPriority(candidates[i]), addressPriority(candidates[j])
		if a == b {
			return candidates[i] < candidates[j]
		}
		return a < b
	})
	var out, loopback []string
	for _, a := range candidates {
		if addressPriority(a) >= 90 {
			loopback = append(loopback, a)
		} else if len(out) < 10 {
			out = append(out, a)
		}
	}
	for _, a := range loopback {
		if len(out) >= 12 {
			break
		}
		out = append(out, a)
	}
	return out
}
func addressPriority(address string) int {
	m, err := ma.NewMultiaddr(address)
	if err != nil {
		return 100
	}
	raw, err := m.ValueForProtocol(ma.P_IP4)
	v4 := err == nil
	if !v4 {
		raw, _ = m.ValueForProtocol(ma.P_IP6)
	}
	ip := net.ParseIP(raw)
	if ip == nil {
		return 80
	}
	if ip.IsLoopback() {
		return 90
	}
	if ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
		return 100
	}
	priority := 0
	if !v4 {
		priority += 20
	}
	if ip.IsPrivate() {
		priority += 10
	}
	return priority
}
func (n *Node) Route() string {
	n.mu.Lock()
	p := n.trusted
	closed := n.closed || n.broken
	n.mu.Unlock()
	if closed || p == "" {
		return "disconnected"
	}
	connections := n.h.Network().ConnsToPeer(p)
	if len(connections) == 0 {
		return "disconnected"
	}
	for _, c := range connections {
		if _, err := c.RemoteMultiaddr().ValueForProtocol(ma.P_CIRCUIT); err != nil {
			return "direct"
		}
	}
	return "relay"
}
func (n *Node) RoundTripMillis(ctx context.Context) (int64, error) {
	if !n.IsConnected() {
		return -1, errors.New("remote connection retired")
	}
	ctx = network.WithNoDial(network.WithAllowLimitedConn(ctx, "encrypted relay heartbeat"), "heartbeat cannot restore an application epoch")
	n.mu.Lock()
	p := n.trusted
	n.mu.Unlock()
	if p == "" {
		return -1, errors.New("no trusted peer")
	}
	select {
	case result := <-ping.Ping(ctx, n.h, p):
		if result.Error != nil {
			return -1, result.Error
		}
		return result.RTT.Milliseconds(), nil
	case <-ctx.Done():
		return -1, ctx.Err()
	}
}
func (n *Node) ClaimDiscovery(ctx context.Context, url, token, ownerID string) ([]string, error) {
	n.mu.Lock()
	valid := n.trusted.String() == ownerID && !n.closed
	n.mu.Unlock()
	if !valid {
		return nil, errors.New("discovery owner does not match trusted peer")
	}
	record, err := (discovery.Client{URL: url}).Do(ctx, n.h.Peerstore().PrivKey(n.h.ID()), discovery.Request{Action: "claim", OwnerID: ownerID, Capability: token, Addresses: n.Addresses(), Expires: time.Now().Add(time.Hour).Unix()})
	if err != nil {
		return nil, err
	}
	if record.OwnerID != ownerID {
		return nil, errors.New("discovery response owner mismatch")
	}
	if err := discovery.ValidateAddresses(ownerID, record.OwnerAddresses); err != nil {
		return nil, err
	}
	return record.OwnerAddresses, nil
}

// SetTrust revokes all old streams before replacing a principal or pairing key.
func (n *Node) SetTrust(id, keyHex string) error {
	p, err := peer.Decode(id)
	if err != nil {
		return errors.New("invalid trusted peer")
	}
	key, err := hex.DecodeString(keyHex)
	if err != nil || len(key) != 32 {
		return errors.New("invalid pairing key")
	}
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return errors.New("closed")
	}
	changed := n.trusted != p || !hmac.Equal(n.key, key)
	n.trusted = p
	n.key = key
	n.pinFirst = false
	n.stagedKey = nil
	n.stagedExpires = 0
	var old []*Stream
	if changed {
		n.connectionEpoch++
		for s := range n.streams {
			old = append(old, s)
		}
	}
	n.mu.Unlock()
	for _, s := range old {
		_ = s.Reset()
	}
	return nil
}

// StageTrustKey lets a pairing reply survive a lost final acknowledgement. A
// proof with the staged key commits it; after the bounded overlap the old key
// expires. The caller persists both keys before sending its pairing reply.
func (n *Node) StageTrustKey(keyHex string, expiresUnix int64) error {
	key, err := hex.DecodeString(keyHex)
	if err != nil || len(key) != 32 {
		return errors.New("invalid staged key")
	}
	if expiresUnix <= time.Now().Unix() || expiresUnix > time.Now().Add(10*time.Minute).Unix() {
		return errors.New("invalid staged key expiry")
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed || n.trusted == "" {
		return errors.New("no paired principal")
	}
	n.stagedKey = key
	n.stagedExpires = expiresUnix
	return nil
}
func (n *Node) Connect(ctx context.Context, addresses []string) error {
	n.mu.Lock()
	p := n.trusted
	closed := n.closed
	n.mu.Unlock()
	if closed {
		return errors.New("closed")
	}
	if p == "" {
		return errors.New("remote peer is not trusted")
	}
	if len(addresses) == 0 || len(addresses) > 16 {
		return errors.New("invalid peer addresses")
	}
	addresses = append([]string(nil), addresses...)
	for _, relay := range n.relays {
		addresses = append(addresses, relay+"/p2p-circuit/p2p/"+p.String())
	}
	var direct, relayed []ma.Multiaddr
	for _, a := range addresses {
		m, err := ma.NewMultiaddr(a)
		if err != nil {
			return errors.New("invalid peer multiaddr")
		}
		info, err := peer.AddrInfoFromP2pAddr(m)
		if err != nil || info.ID != p {
			return errors.New("multiaddr peer does not match trusted identity")
		}
		for _, v := range info.Addrs {
			if _, err := v.ValueForProtocol(ma.P_CIRCUIT); err == nil {
				relayed = append(relayed, v)
			} else {
				direct = append(direct, v)
			}
		}
	}
	// Keep a bounded direct preference. Retrying a connection does not replay a
	// business operation: streams are opened only after this method returns.
	var last error
	for _, group := range [][]ma.Multiaddr{direct, relayed} {
		if len(group) == 0 {
			continue
		}
		limit := 8 * time.Second
		if len(relayed) == 0 {
			limit = 20 * time.Second
		}
		attempt, cancel := context.WithTimeout(ctx, limit)
		n.h.Peerstore().AddAddrs(p, group, peerstore.TempAddrTTL)
		last = n.h.Connect(attempt, peer.AddrInfo{ID: p, Addrs: group})
		cancel()
		if last == nil {
			n.mu.Lock()
			n.broken = false
			n.mu.Unlock()
			return nil
		}
	}
	if last == nil {
		last = errors.New("no usable peer addresses")
	}
	return last
}
func (n *Node) ReserveRelay(ctx context.Context, address string) (string, error) {
	m, err := ma.NewMultiaddr(address)
	if err != nil {
		return "", err
	}
	info, err := peer.AddrInfoFromP2pAddr(m)
	if err != nil {
		return "", err
	}
	if _, err = relayclient.Reserve(ctx, n.h, *info); err != nil {
		return "", err
	}
	return address + "/p2p-circuit/p2p/" + n.PeerID(), nil
}
func (n *Node) Open(ctx context.Context, kind string) (*Stream, error) {
	pid, ok := protocols[kind]
	if !ok {
		return nil, errors.New("unknown stream kind")
	}
	n.mu.Lock()
	p := n.trusted
	key := append([]byte(nil), n.key...)
	closed := n.closed || n.broken
	n.mu.Unlock()
	if closed || p == "" || len(key) != 32 {
		return nil, errors.New("no active trusted peer")
	}
	slot := n.limits[kind]
	select {
	case slot <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-n.ctx.Done():
		return nil, errors.New("closed")
	}
	s, err := n.h.NewStream(network.WithNoDial(network.WithAllowLimitedConn(ctx, "encrypted remote assistant relay"), "explicit connect and fresh hello required"), p, pid)
	if err != nil {
		<-slot
		return nil, err
	}
	stopCancel := context.AfterFunc(ctx, func() { _ = s.Reset() })
	defer stopCancel()
	remoteGen, err := n.authenticate(s, kind, key)
	if err != nil {
		_ = s.Reset()
		<-slot
		return nil, err
	}
	return n.track(s, kind, slot, remoteGen, key, false)
}
func (n *Node) Accept(ctx context.Context) (*Stream, error) {
	for {
		select {
		case s := <-n.incoming:
			n.mu.Lock()
			active := n.streams[s] && !n.closed
			n.mu.Unlock()
			if active {
				return s, nil
			}
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-n.ctx.Done():
			return nil, errors.New("closed")
		}
	}
}
func (n *Node) receive(kind string, s network.Stream) {
	slot := n.limits[kind]
	select {
	case slot <- struct{}{}:
	default:
		_ = s.Reset()
		return
	}
	n.mu.Lock()
	p := n.trusted
	key := append([]byte(nil), n.key...)
	staged := append([]byte(nil), n.stagedKey...)
	if len(staged) > 0 && time.Now().Unix() >= n.stagedExpires {
		key = staged
		staged = nil
	}
	allow := p == s.Conn().RemotePeer() || (p == "" && n.pinFirst && time.Now().Unix() < n.expires)
	closed := n.closed
	n.mu.Unlock()
	if !allow || closed || len(key) != 32 {
		_ = s.Reset()
		<-slot
		return
	}
	remoteGen, usedKey, err := n.authenticateIncoming(s, kind, key, staged)
	if err != nil {
		_ = s.Reset()
		<-slot
		return
	}
	key = usedKey
	n.mu.Lock()
	if n.trusted == "" && n.pinFirst && time.Now().Unix() < n.expires {
		n.trusted = s.Conn().RemotePeer()
		n.pinFirst = false
	}
	var retired []*Stream
	if len(n.stagedKey) > 0 && hmac.Equal(n.stagedKey, key) {
		n.connectionEpoch++
		n.key = n.stagedKey
		n.stagedKey = nil
		n.stagedExpires = 0
		for old := range n.streams {
			retired = append(retired, old)
		}
	}
	valid := n.trusted == s.Conn().RemotePeer() && hmac.Equal(n.key, key) && !n.closed
	n.mu.Unlock()
	for _, old := range retired {
		_ = old.Reset()
	}
	if !valid {
		_ = s.Reset()
		<-slot
		return
	}
	stream, err := n.track(s, kind, slot, remoteGen, key, true)
	if err != nil {
		return
	}
	select {
	case n.incoming <- stream:
	case <-n.ctx.Done():
		_ = stream.Reset()
	default:
		_ = stream.Reset()
	}
}
func (n *Node) track(s network.Stream, kind string, slot chan struct{}, remoteGen int64, key []byte, incoming bool) (*Stream, error) {
	stream := &Stream{Stream: s, Kind: kind, Generation: n.generation, RemoteGeneration: remoteGen, node: n, slot: slot}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed || s.Conn().IsClosed() || s.Conn().RemotePeer() != n.trusted || !hmac.Equal(n.key, key) || (n.broken && (!incoming || kind != Control)) {
		_ = s.Reset()
		<-slot
		return nil, errors.New("retired principal")
	}
	n.streams[stream] = true
	n.boundConnections[s.Conn().ID()] = true
	if incoming && kind == Control {
		n.broken = false
	}
	_ = s.SetDeadline(time.Time{})
	return stream, nil
}
func (n *Node) Close() error {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil
	}
	n.closed = true
	n.cancel()
	var all []*Stream
	for s := range n.streams {
		all = append(all, s)
	}
	n.mu.Unlock()
	for _, s := range all {
		_ = s.Reset()
	}
	return n.h.Close()
}
func (s *Stream) release() {
	s.once.Do(func() { s.node.mu.Lock(); delete(s.node.streams, s); s.node.mu.Unlock(); <-s.slot })
}
func (s *Stream) Close() error { err := s.Stream.Close(); s.release(); return err }
func (s *Stream) Reset() error { err := s.Stream.Reset(); s.release(); return err }
func (s *Stream) Read(p []byte) (int, error) {
	if len(p) > MaxChunk {
		p = p[:MaxChunk]
	}
	read, err := s.Stream.Read(p)
	if s.readBytes.Add(int64(read)) > MaxStreamBytes {
		_ = s.Reset()
		return 0, errors.New("stream byte limit exceeded")
	}
	return read, err
}
func (s *Stream) Write(p []byte) (int, error) {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	if int64(len(p))+s.writtenBytes.Load() > MaxStreamBytes {
		return 0, errors.New("stream byte limit exceeded")
	}
	total := 0
	for len(p) > 0 {
		count := len(p)
		if count > MaxChunk {
			count = MaxChunk
		}
		w, err := s.Stream.Write(p[:count])
		s.writtenBytes.Add(int64(w))
		total += w
		p = p[w:]
		if err != nil {
			return total, err
		}
		if w == 0 {
			return total, io.ErrShortWrite
		}
	}
	return total, nil
}

func proof(key []byte, label, kind string, initiator, responder peer.ID, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, key)
	for _, v := range []string{"dsha-connect/1", label, kind, initiator.String(), responder.String()} {
		_, _ = io.WriteString(h, v)
		_, _ = h.Write([]byte{0})
	}
	for _, part := range parts {
		_, _ = h.Write(part)
	}
	return h.Sum(nil)
}
func (n *Node) authenticate(s network.Stream, kind string, key []byte) (int64, error) {
	_ = s.SetDeadline(time.Now().Add(10 * time.Second))
	a, b := n.h.ID(), s.Conn().RemotePeer()
	header := make([]byte, 40)
	challenge := make([]byte, 40)
	binary.BigEndian.PutUint64(header[:8], uint64(n.generation))
	if _, err := rand.Read(header[8:]); err != nil {
		return 0, err
	}
	if err := writeAll(s, append(header, proof(key, "open", kind, a, b, header)...)); err != nil {
		return 0, err
	}
	response := make([]byte, 72)
	if _, err := io.ReadFull(s, response); err != nil {
		return 0, err
	}
	copy(challenge, response[:40])
	if !hmac.Equal(response[40:], proof(key, "reply", kind, a, b, header, challenge)) {
		return 0, errors.New("pairing authentication failed")
	}
	err := writeAll(s, proof(key, "ack", kind, a, b, header, challenge))
	return int64(binary.BigEndian.Uint64(challenge[:8])), err
}
func writeAll(w io.Writer, p []byte) error {
	for len(p) > 0 {
		n, err := w.Write(p)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		p = p[n:]
	}
	return nil
}
func (n *Node) authenticateIncoming(s network.Stream, kind string, key, alternate []byte) (int64, []byte, error) {
	_ = s.SetDeadline(time.Now().Add(10 * time.Second))
	a, b := s.Conn().RemotePeer(), n.h.ID()
	request := make([]byte, 72)
	if _, err := io.ReadFull(s, request); err != nil {
		return 0, nil, err
	}
	header := request[:40]
	if !hmac.Equal(request[40:], proof(key, "open", kind, a, b, header)) {
		if len(alternate) != 32 || !hmac.Equal(request[40:], proof(alternate, "open", kind, a, b, header)) {
			return 0, nil, errors.New("pairing authentication failed")
		}
		key = alternate
	}
	challenge := make([]byte, 40)
	binary.BigEndian.PutUint64(challenge[:8], uint64(n.generation))
	if _, err := rand.Read(challenge[8:]); err != nil {
		return 0, nil, err
	}
	if err := writeAll(s, append(challenge, proof(key, "reply", kind, a, b, header, challenge)...)); err != nil {
		return 0, nil, err
	}
	ack := make([]byte, 32)
	if _, err := io.ReadFull(s, ack); err != nil {
		return 0, nil, err
	}
	if !hmac.Equal(ack, proof(key, "ack", kind, a, b, header, challenge)) {
		return 0, nil, errors.New("pairing authentication failed")
	}
	return int64(binary.BigEndian.Uint64(header[:8])), key, nil
}
