// DSHA Connect is a user-level companion. It never starts, installs or modifies DSH.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"time"

	core "github.com/dsha/connect-core"
	qrcode "github.com/skip2/go-qrcode"
)

const protocolVersion = 1

type target struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	URL    string `json:"url"`
	Cookie string `json:"cookie,omitempty"`
}
type diskState struct {
	Identity               string   `json:"identity"`
	PeerID                 string   `json:"peerId,omitempty"`
	Key                    string   `json:"key,omitempty"`
	PendingKey             string   `json:"pendingKey,omitempty"`
	PendingExpires         int64    `json:"pendingExpires,omitempty"`
	PairRequestID          string   `json:"pairRequestId,omitempty"`
	DiscoveryURL           string   `json:"discoveryUrl,omitempty"`
	DiscoveryToken         string   `json:"discoveryToken,omitempty"`
	DiscoveryRevokePending bool     `json:"discoveryRevokePending,omitempty"`
	ListenAddrs            []string `json:"listenAddrs,omitempty"`
	Targets                []target `json:"targets"`
}
type identity struct {
	PeerID          string `json:"peerId"`
	HostInstanceID  string `json:"hostInstanceId"`
	SessionID       string `json:"sessionId"`
	ConnectionEpoch string `json:"connectionEpoch"`
	TrustRevision   int64  `json:"trustRevision"`
	Generation      int64  `json:"generation"`
}
type control struct {
	V          int             `json:"v"`
	Op         string          `json:"op"`
	RequestID  string          `json:"requestId"`
	Generation int64           `json:"generation"`
	TargetID   string          `json:"targetId"`
	Method     string          `json:"method,omitempty"`
	Args       json.RawMessage `json:"args,omitempty"`
	Identity   *identity       `json:"identity,omitempty"`
}
type companion struct {
	mu                 sync.Mutex
	state              diskState
	dir                string
	node               *core.Node
	instance, ipcToken string
	generation         int64
	pairingExpiry      time.Time
	ticketKey          string
	phone              *identity
	phoneEpoch         int64
	cache              map[string][]byte
	cacheFingerprints  map[string][32]byte
	client             *http.Client
	ctx                context.Context
	relayAddresses     []string
	revoked            bool
	releaseLock        func()
	closeOnce          sync.Once
	discoveryReady     bool
	discoveryError     string
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func defaultDir() string {
	if d := os.Getenv("LOCALAPPDATA"); d != "" {
		return filepath.Join(d, "DSHA", "Connect")
	}
	d, _ := os.UserConfigDir()
	return filepath.Join(d, "dsha-connect")
}
func writePrivate(path string, value any) error {
	b, e := json.MarshalIndent(value, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.OpenFile(path+".new", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if e != nil {
		return e
	}
	if _, e = f.Write(b); e != nil {
		f.Close()
		return e
	}
	if e = f.Sync(); e != nil {
		f.Close()
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	return os.Rename(path+".new", path)
}
func (c *companion) saveLocked() error {
	return writePrivate(filepath.Join(c.dir, "state.json"), c.state)
}
func clearTrust(state *diskState) {
	state.PeerID = ""
	state.Key = ""
	state.PendingKey = ""
	state.PendingExpires = 0
	state.PairRequestID = ""
	state.DiscoveryRevokePending = state.DiscoveryURL != ""
}
func validateTarget(raw string) (*url.URL, error) {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "http" || u.User != nil || u.Fragment != "" || (u.Hostname() != "127.0.0.1" && u.Hostname() != "::1") || u.Port() == "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("DSH URL must be a numeric loopback HTTP origin with an explicit port and optional launch token")
	}
	for k := range u.Query() {
		if k != "token" {
			return nil, errors.New("only DSH launch token is accepted in target URL")
		}
	}
	return u, nil
}
func newCompanion(ctx context.Context, dir, dshURL string, relays []string) (*companion, error) {
	return newCompanionWithRevoke(ctx, dir, dshURL, relays, false)
}
func newCompanionWithRevoke(ctx context.Context, dir, dshURL string, relays []string, revoke bool) (*companion, error) {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, e
	}
	if e := secureDirectory(dir); e != nil {
		return nil, e
	}
	release, e := lockDirectory(dir)
	if e != nil {
		return nil, errors.New("another companion is already using this state directory")
	}
	keepLock := false
	defer func() {
		if !keepLock {
			release()
		}
	}()
	c := &companion{dir: dir, instance: randomHex(16), ipcToken: randomHex(32), generation: time.Now().UnixMilli(), cache: map[string][]byte{}, cacheFingerprints: map[string][32]byte{}, ctx: ctx}
	c.releaseLock = release
	c.client = &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }, Transport: &http.Transport{Proxy: nil, DisableCompression: true, ResponseHeaderTimeout: 20 * time.Second}}
	if b, e := os.ReadFile(filepath.Join(dir, "state.json")); e == nil {
		if e = json.Unmarshal(b, &c.state); e != nil {
			return nil, e
		}
	} else if !errors.Is(e, os.ErrNotExist) {
		return nil, e
	}
	if revoke {
		clearTrust(&c.state)
	}
	if dshURL != "" {
		t, e := c.authorizeTarget(ctx, "default", "本机 DSH", dshURL)
		if e != nil {
			return nil, e
		}
		c.state.Targets = []target{t}
	}
	for _, t := range c.state.Targets {
		if _, e := validateTarget(t.URL); e != nil {
			return nil, e
		}
	}
	cfg := core.Config{IdentityBase64: c.state.Identity, TrustedPeerID: c.state.PeerID, PairingKeyHex: c.state.Key, Generation: c.generation, RelayAddrs: relays, ListenAddrs: c.state.ListenAddrs}
	if cfg.TrustedPeerID == "" {
		c.pairingExpiry = time.Now().Add(3 * time.Minute)
		c.ticketKey = randomHex(32)
		cfg.PairingKeyHex = c.ticketKey
		cfg.PinFirstPeer = true
		cfg.PairingExpiresUnix = c.pairingExpiry.Unix()
	}
	n, e := core.New(cfg)
	if e != nil {
		return nil, e
	}
	c.node = n
	if len(c.state.ListenAddrs) == 0 {
		c.state.ListenAddrs = stableListeners(n.Addresses())
	}
	if c.state.PendingKey != "" {
		if c.state.PendingExpires <= time.Now().Unix() {
			c.state.Key = c.state.PendingKey
			c.state.PendingKey = ""
			c.state.PairRequestID = ""
			if e = n.SetTrust(c.state.PeerID, c.state.Key); e != nil {
				return nil, e
			}
		} else {
			if e = n.StageTrustKey(c.state.PendingKey, c.state.PendingExpires); e != nil {
				return nil, e
			}
			c.pairingExpiry = time.Unix(c.state.PendingExpires, 0)
		}
	}
	c.state.Identity = n.IdentityBase64()
	if e = c.saveLocked(); e != nil {
		n.Close()
		return nil, e
	}
	keepLock = true
	return c, nil
}
func (c *companion) Close() {
	c.closeOnce.Do(func() {
		if c.node != nil {
			c.node.Close()
		}
		if c.releaseLock != nil {
			c.releaseLock()
		}
	})
}
func stableListeners(addresses []string) []string {
	ports := map[string]string{}
	for _, address := range addresses {
		parts := strings.Split(address, "/")
		for i, part := range parts {
			if (part == "tcp" || part == "udp") && i+1 < len(parts) && parts[i+1] != "0" {
				ports[part] = parts[i+1]
			}
		}
	}
	var result []string
	if ports["tcp"] != "" {
		result = append(result, "/ip4/0.0.0.0/tcp/"+ports["tcp"])
	}
	if ports["udp"] != "" {
		result = append(result, "/ip4/0.0.0.0/udp/"+ports["udp"]+"/quic-v1")
	}
	return result
}
func (c *companion) authorizeTarget(ctx context.Context, id, name, raw string) (target, error) {
	u, e := validateTarget(raw)
	if e != nil {
		return target{}, e
	}
	q := u.Query()
	token := q.Get("token")
	u.RawQuery = ""
	t := target{ID: id, Name: name, URL: strings.TrimRight(u.String(), "/")}
	if token != "" {
		auth := *u
		auth.Path = "/"
		auth.RawQuery = url.Values{"token": []string{token}}.Encode()
		req, _ := http.NewRequestWithContext(ctx, "GET", auth.String(), nil)
		res, e := c.client.Do(req)
		if e != nil {
			return t, e
		}
		defer res.Body.Close()
		if res.StatusCode != 303 {
			return t, fmt.Errorf("DSH launch token exchange returned %d", res.StatusCode)
		}
		for _, cookie := range res.Cookies() {
			if strings.HasPrefix(cookie.Name, "dsh-auth-") {
				t.Cookie = cookie.Name + "=" + cookie.Value
			}
		}
		if t.Cookie == "" {
			return t, errors.New("DSH did not return its authority cookie")
		}
	}
	return t, nil
}
func (c *companion) ticket() (map[string]any, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.revoked || c.state.PeerID != "" || time.Now().After(c.pairingExpiry) {
		return nil, errors.New("配对窗口已关闭；需要在电脑上撤销旧信任并重新运行")
	}
	ticket := map[string]any{"v": 1, "role": "pc", "peerId": c.node.PeerID(), "addresses": append(c.node.Addresses(), c.relayAddresses...), "pairingKey": c.ticketKey, "expiresAt": c.pairingExpiry.UnixMilli(), "hostInstanceId": c.instance}
	if c.state.DiscoveryURL != "" {
		ticket["discoveryURL"] = c.state.DiscoveryURL
		ticket["discoveryToken"] = c.state.DiscoveryToken
	}
	return ticket, nil
}
func (c *companion) refreshTicket() (map[string]any, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state.PeerID == "" || c.revoked {
		return nil, errors.New("no remembered phone")
	}
	ticket := map[string]any{"v": 1, "role": "pc", "mode": "refresh", "peerId": c.node.PeerID(), "addresses": append(c.node.Addresses(), c.relayAddresses...), "expiresAt": time.Now().Add(3 * time.Minute).UnixMilli(), "hostInstanceId": c.instance}
	if c.state.DiscoveryURL != "" {
		ticket["discoveryURL"] = c.state.DiscoveryURL
		ticket["discoveryToken"] = c.state.DiscoveryToken
	}
	return ticket, nil
}
func (c *companion) publicStatus() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.validatePhoneLocked()
	targets := []map[string]string{}
	for _, t := range c.state.Targets {
		targets = append(targets, map[string]string{"id": t.ID, "name": t.Name})
	}
	networkStatus := "direct"
	if c.state.DiscoveryURL != "" {
		networkStatus = "discovery-unavailable"
		if c.discoveryReady {
			networkStatus = "discovery"
		}
	}
	return map[string]any{"protocol": 1, "hostInstanceId": c.instance, "peerId": c.node.PeerID(), "generation": c.generation, "trusted": c.state.PeerID != "", "connected": c.phone != nil, "revoked": c.revoked, "networkStatus": networkStatus, "targets": targets, "dshCompatibility": []string{"0.2.1-rc.2", "0.2.1-rc.3"}}
}
func (c *companion) validatePhoneLocked() bool {
	if c.phone != nil && (!c.node.IsConnected() || c.phoneEpoch != c.node.ConnectionEpoch()) {
		c.phone = nil
	}
	return c.phone != nil
}
func readFrame(r io.Reader, v any) error {
	return readFrameLimit(r, v, 65536)
}
func readFrameLimit(r io.Reader, v any, limit uint32) error {
	var size uint32
	if e := binary.Read(r, binary.BigEndian, &size); e != nil {
		return e
	}
	if size == 0 || size > limit {
		return errors.New("frame exceeds 64 KiB")
	}
	b := make([]byte, size)
	if _, e := io.ReadFull(r, b); e != nil {
		return e
	}
	return json.Unmarshal(b, v)
}
func writeFrame(w io.Writer, v any) error {
	b, e := json.Marshal(v)
	if e != nil {
		return e
	}
	if len(b) > 4*1024*1024 {
		return errors.New("response too large")
	}
	if e = binary.Write(w, binary.BigEndian, uint32(len(b))); e != nil {
		return e
	}
	_, e = w.Write(b)
	return e
}
func reply(id string, result any, e error) map[string]any {
	if e != nil {
		return map[string]any{"v": 1, "requestId": id, "ok": false, "error": map[string]string{"code": "REQUEST_FAILED", "message": e.Error()}}
	}
	return map[string]any{"v": 1, "requestId": id, "ok": true, "result": result}
}
func (c *companion) accept() {
	for {
		s, e := c.node.Accept(c.ctx)
		if e != nil {
			return
		}
		go func() {
			defer s.Close()
			if s.Kind == core.Control {
				c.handleControl(s)
			} else {
				c.proxy(s)
			}
		}()
	}
}
func (c *companion) handleControl(s *core.Stream) {
	_ = s.SetDeadline(time.Now().Add(40 * time.Second))
	var req control
	if e := readFrame(s, &req); e != nil {
		return
	}
	if req.V != 1 || req.RequestID == "" || len(req.RequestID) > 128 || req.Generation != s.RemoteGeneration {
		writeFrame(s, reply(req.RequestID, nil, errors.New("invalid protocol or generation")))
		return
	}
	c.mu.Lock()
	encoded, _ := json.Marshal(req)
	fingerprint := sha256.Sum256(encoded)
	cached, seen := c.cache[req.RequestID]
	if seen {
		if c.cacheFingerprints[req.RequestID] != fingerprint {
			c.mu.Unlock()
			writeFrame(s, reply(req.RequestID, nil, errors.New("requestId reused with different content")))
			return
		}
		c.mu.Unlock()
		if cached == nil {
			writeFrame(s, reply(req.RequestID, nil, errors.New("request outcome unknown; do not replay")))
		} else {
			var v any
			json.Unmarshal(cached, &v)
			writeFrame(s, v)
		}
		return
	}
	if len(c.cache) >= 4096 {
		c.mu.Unlock()
		writeFrame(s, reply(req.RequestID, nil, errors.New("request journal full; reconnect explicitly")))
		return
	}
	c.cache[req.RequestID] = nil
	c.cacheFingerprints[req.RequestID] = fingerprint
	c.mu.Unlock()
	if req.Op != "status" && req.Op != "targets" && req.Op != "pair" && req.Op != "hello" {
		c.mu.Lock()
		ready := c.validatePhoneLocked() && c.state.PeerID != "" && c.phone.Generation == s.RemoteGeneration
		c.mu.Unlock()
		if !ready {
			writeFrame(s, reply(req.RequestID, nil, errors.New("authenticated phone hello required")))
			return
		}
	}
	var result any
	var e error
	rotate := ""
	switch req.Op {
	case "status", "targets":
		result = c.publicStatus()
	case "pair":
		c.mu.Lock()
		if c.state.PendingKey != "" && c.state.PairRequestID == req.RequestID && time.Now().Before(c.pairingExpiry) {
			rotate = c.state.PendingKey
			result = map[string]any{"reconnectKey": rotate, "peerId": c.node.PeerID(), "hostInstanceId": c.instance, "generation": req.Generation}
		} else if c.state.PeerID != "" || time.Now().After(c.pairingExpiry) {
			e = errors.New("pairing window expired or consumed")
		} else {
			rotate = randomHex(32)
			c.state.PeerID = c.node.TrustedPeerID()
			c.state.Key = c.ticketKey
			c.state.PendingKey = rotate
			c.state.PendingExpires = c.pairingExpiry.Unix()
			c.state.PairRequestID = req.RequestID
			e = c.saveLocked()
			if e == nil {
				e = c.node.StageTrustKey(rotate, c.state.PendingExpires)
			}
			if e == nil {
				result = map[string]any{"reconnectKey": rotate, "peerId": c.node.PeerID(), "hostInstanceId": c.instance, "generation": req.Generation}
			}
		}
		c.mu.Unlock()
	case "hello":
		var a struct {
			Identity identity `json:"identity"`
		}
		e = json.Unmarshal(req.Args, &a)
		if e == nil {
			if a.Identity.HostInstanceID != c.instance || a.Identity.PeerID != c.node.PeerID() || a.Identity.Generation != s.RemoteGeneration || len(a.Identity.ConnectionEpoch) != 32 || a.Identity.TrustRevision < 1 || a.Identity.SessionID == "" {
				e = errors.New("invalid connection identity")
			} else {
				c.mu.Lock()
				if c.state.PeerID == "" {
					e = errors.New("pair first")
				} else {
					c.phone = &a.Identity
					c.phoneEpoch = c.node.ConnectionEpoch()
				}
				c.mu.Unlock()
				result = map[string]any{"accepted": e == nil}
			}
		}
	case "dsh":
		result, e = c.dshCall(c.ctx, req.TargetID, req.Method, req.Args)
	case "revoke":
		c.mu.Lock()
		c.revoked = true
		c.phone = nil
		clearTrust(&c.state)
		e = c.saveLocked()
		c.mu.Unlock()
		result = map[string]bool{"revoked": e == nil}
	default:
		e = errors.New("unsupported control operation")
	}
	response := reply(req.RequestID, result, e)
	b, _ := json.Marshal(response)
	c.mu.Lock()
	c.cache[req.RequestID] = b
	c.mu.Unlock()
	_ = writeFrame(s, response)
	if rotate != "" && e == nil {
		_ = s.SetDeadline(time.Now().Add(5 * time.Second))
		var ack control
		if readFrame(s, &ack) == nil && ack.V == 1 && ack.Op == "pair-ack" && ack.RequestID == req.RequestID {
			c.mu.Lock()
			c.state.Key = rotate
			c.state.PendingKey = ""
			c.state.PairRequestID = ""
			persisted := c.saveLocked() == nil
			c.mu.Unlock()
			if persisted {
				_ = s.Close()
				_ = c.node.SetTrust(c.state.PeerID, rotate)
			}
		}
	}
	if req.Op == "revoke" && e == nil {
		// Let the peer consume the acknowledgement and close its write half.
		// Revocation still completes if that acknowledgement is lost.
		_ = s.CloseWrite()
		_ = s.SetReadDeadline(time.Now().Add(2 * time.Second))
		var end [1]byte
		_, _ = s.Read(end[:])
		_ = c.node.Close()
	}
}
func (c *companion) findTarget(id string) (target, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, t := range c.state.Targets {
		if t.ID == id {
			return t, nil
		}
	}
	return target{}, errors.New("unknown DSH target")
}
func argumentName(endpoint string) string {
	if endpoint == "session/list" {
		return "_request"
	}
	return "request"
}
func (c *companion) rpcCall(ctx context.Context, id, endpoint string, args json.RawMessage) (json.RawMessage, error) {
	t, e := c.findTarget(id)
	if e != nil {
		return nil, e
	}
	var a any = map[string]any{}
	if len(args) > 0 {
		if e = json.Unmarshal(args, &a); e != nil {
			return nil, e
		}
	}
	rpcID := randomHex(16)
	b, _ := json.Marshal(map[string]any{"type": "client-request", "rpcId": rpcID, "method": endpoint, "payload": map[string]any{"args": map[string]any{argumentName(endpoint): a}}})
	req, _ := http.NewRequestWithContext(ctx, "POST", t.URL+"/api/"+endpoint, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Cookie", t.Cookie)
	res, e := c.client.Do(req)
	if e != nil {
		return nil, e
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("DSH HTTP %d", res.StatusCode)
	}
	var response struct {
		RPCID  string `json:"rpcId"`
		Result struct {
			OK    bool            `json:"ok"`
			Value json.RawMessage `json:"value"`
			Error json.RawMessage `json:"error"`
		} `json:"result"`
	}
	if e = json.NewDecoder(io.LimitReader(res.Body, 4*1024*1024)).Decode(&response); e != nil {
		return nil, e
	}
	if response.RPCID != rpcID {
		return nil, errors.New("DSH RPC response identity mismatch")
	}
	if !response.Result.OK {
		return nil, fmt.Errorf("DSH rejected: %s", response.Result.Error)
	}
	return response.Result.Value, nil
}
func (c *companion) proxy(s *core.Stream) {
	_ = s.SetDeadline(time.Now().Add(30 * time.Second))
	var meta struct {
		V          int    `json:"v"`
		Service    string `json:"service"`
		Generation int64  `json:"generation"`
		TargetID   string `json:"targetId"`
	}
	if readFrameLimit(s, &meta, 16384) != nil || meta.V != 1 || meta.Generation != s.RemoteGeneration || meta.Service != "dsh-web" {
		return
	}
	c.mu.Lock()
	ready := c.validatePhoneLocked() && c.state.PeerID != "" && c.phone.Generation == s.RemoteGeneration
	c.mu.Unlock()
	if !ready {
		return
	}
	t, e := c.findTarget(meta.TargetID)
	if e != nil {
		return
	}
	reader, e := boundedHTTPReader(s)
	if e != nil {
		return
	}
	req, e := http.ReadRequest(reader)
	if e != nil {
		return
	}
	defer req.Body.Close()
	if req.ContentLength > maxHTTPBody {
		io.WriteString(s, "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	req.Body = &boundedBody{ReadCloser: req.Body, left: maxHTTPBody}
	if req.URL.IsAbs() || req.Method == "CONNECT" || !strings.HasPrefix(req.RequestURI, "/") || strings.HasPrefix(req.RequestURI, "//") {
		return
	}
	u, _ := url.Parse(t.URL)
	req.URL.Scheme = u.Scheme
	req.URL.Host = u.Host
	req.Host = u.Host
	req.RequestURI = ""
	req.Close = false
	// Only the locally configured DSH cookie crosses the loopback boundary.
	req.Header.Del("Authorization")
	req.Header.Del("Cookie")
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Forwarded")
	req.Header.Del("X-Forwarded-For")
	req.Header.Del("X-Forwarded-Host")
	req.Header.Del("X-Forwarded-Proto")
	req.Header.Del("Sec-Fetch-Site")
	req.Header.Set("Cookie", t.Cookie)
	if req.Header.Get("Origin") != "" {
		req.Header.Set("Origin", t.URL)
	}
	// Headers have a deadline; a legitimate multi-GB body is streamed until the
	// owner disconnects. Applying the header deadline to the body breaks uploads.
	_ = s.SetDeadline(time.Time{})
	conn, e := net.DialTimeout("tcp", u.Host, 5*time.Second)
	if e != nil {
		return
	}
	defer conn.Close()
	if e = req.Write(conn); e != nil {
		return
	}
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	upstream, e := boundedHTTPReader(conn)
	if e != nil {
		return
	}
	res, e := http.ReadResponse(upstream, req)
	if e != nil {
		return
	}
	defer res.Body.Close()
	if res.ContentLength > maxHTTPBody {
		io.WriteString(s, "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
		return
	}
	if res.StatusCode != 101 {
		res.Body = &boundedBody{ReadCloser: res.Body, left: maxHTTPBody}
	}
	res.Header.Del("Set-Cookie")
	if location := res.Header.Get("Location"); location != "" {
		dest, e := url.Parse(location)
		if e != nil || (dest.IsAbs() && (dest.Scheme != u.Scheme || dest.Host != u.Host)) {
			res.StatusCode = 502
			res.Status = "502 Bad Gateway"
			res.Header.Del("Location")
			res.Body = io.NopCloser(strings.NewReader("upstream redirect rejected"))
			res.ContentLength = -1
		} else if dest.IsAbs() {
			res.Header.Set("Location", dest.RequestURI())
		}
	}
	if res.StatusCode == 101 {
		if !validWebSocket(req, res) {
			return
		}
		if _, e = fmt.Fprintf(s, "HTTP/1.1 101 Switching Protocols\r\n"); e != nil {
			return
		}
		if e = res.Header.Write(s); e != nil {
			return
		}
		if _, e = io.WriteString(s, "\r\n"); e != nil {
			return
		}
		_ = s.SetDeadline(time.Time{})
		_ = conn.SetDeadline(time.Time{})
		done := make(chan struct{})
		go func() { _, _ = io.Copy(conn, reader); _ = conn.Close(); close(done) }()
		_, _ = io.Copy(s, upstream)
		_ = s.Close()
		<-done
		return
	}
	_ = s.SetDeadline(time.Time{})
	_ = conn.SetDeadline(time.Time{})
	_ = res.Write(s)
}
func sameSecret(a, b string) bool {
	return len(a) == len(b) && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
func (c *companion) localAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if r.Host != r.Context().Value(listenKey{}).(string) {
		http.Error(w, "invalid host", 403)
		return
	}
	if origin := r.Header.Get("Origin"); origin != "" && origin != "http://"+r.Host {
		http.Error(w, "invalid origin", 403)
		return
	}
	if r.URL.Path == "/" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, bootstrapHTML)
		return
	}
	// Bootstrap browser uses an HttpOnly, same-origin session; plugins use IPC bearer.
	cookie, _ := r.Cookie("dsha-connect")
	browser := cookie != nil && sameSecret(cookie.Value, c.ipcToken)
	if !browser && !sameSecret(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), c.ipcToken) {
		http.Error(w, "authentication required", 401)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	switch r.URL.Path {
	case "/v1/status":
		json.NewEncoder(w).Encode(c.publicStatus())
	case "/v1/pair":
		t, e := c.ticket()
		if e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
		json.NewEncoder(w).Encode(t)
	case "/v1/refresh":
		t, e := c.refreshTicket()
		if e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
		json.NewEncoder(w).Encode(t)
	case "/v1/qr.png":
		t, e := c.ticket()
		verb := "pair"
		if e != nil {
			t, e = c.refreshTicket()
			verb = "refresh"
		}
		if e != nil {
			http.Error(w, e.Error(), 409)
			return
		}
		b, _ := json.Marshal(t)
		payload := "dsha-connect://" + verb + "?ticket=" + base64.RawURLEncoding.EncodeToString(b)
		png, e := qrcode.Encode(payload, qrcode.Medium, 440)
		if e != nil {
			http.Error(w, e.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write(png)
	case "/v1/revoke":
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		c.mu.Lock()
		c.revoked = true
		c.phone = nil
		clearTrust(&c.state)
		e := c.saveLocked()
		c.mu.Unlock()
		if e != nil {
			http.Error(w, e.Error(), 500)
			return
		}
		c.node.Close()
		json.NewEncoder(w).Encode(map[string]bool{"revoked": true})
	case "/v1/device":
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		var req control
		if e := json.NewDecoder(io.LimitReader(r.Body, 65536)).Decode(&req); e != nil {
			http.Error(w, "invalid request", 400)
			return
		}
		c.mu.Lock()
		c.validatePhoneLocked()
		phone := c.phone
		c.mu.Unlock()
		if phone == nil {
			http.Error(w, "phone disconnected; establish a new authenticated hello", 409)
			return
		}
		req.V = 1
		req.Op = "device"
		req.Generation = phone.Generation
		req.Identity = phone
		if req.RequestID == "" {
			req.RequestID = randomHex(16)
		}
		if req.TargetID == "" {
			req.TargetID = "physical-screen"
		}
		s, e := c.node.Open(r.Context(), core.Control)
		if e != nil {
			http.Error(w, e.Error(), 502)
			return
		}
		defer s.Close()
		_ = s.SetDeadline(time.Now().Add(35 * time.Second))
		if e = writeFrame(s, req); e != nil {
			http.Error(w, "request outcome unknown", 502)
			return
		}
		var response any
		if e = readResponseFrame(s, &response); e != nil {
			http.Error(w, "request outcome unknown; do not replay", 502)
			return
		}
		json.NewEncoder(w).Encode(response)
	default:
		http.NotFound(w, r)
	}
}
func readResponseFrame(r io.Reader, v any) error {
	var n uint32
	if e := binary.Read(r, binary.BigEndian, &n); e != nil {
		return e
	}
	if n == 0 || n > 4*1024*1024 {
		return errors.New("invalid response size")
	}
	b := make([]byte, n)
	if _, e := io.ReadFull(r, b); e != nil {
		return e
	}
	return json.Unmarshal(b, v)
}

type listenKey struct{}

func main() {
	dir := flag.String("state-dir", defaultDir(), "private user state directory")
	dshURL := flag.String("dsh-url", "", "DSH loopback launch URL (token exchanged locally)")
	relay := flag.String("relay", "", "optional circuit-v2 relay multiaddr")
	discoveryURL := flag.String("discovery", "", "optional HTTPS discovery service URL")
	revoke := flag.Bool("revoke", false, "remove remembered phone trust before starting")
	flag.Parse()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	var relays []string
	if *relay != "" {
		relays = []string{*relay}
	}
	c, e := newCompanionWithRevoke(ctx, *dir, *dshURL, relays, *revoke)
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	defer c.Close()
	if e = c.manageDiscovery(ctx, *discoveryURL, relays); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	listener, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	host := listener.Addr().String()
	if e = writePrivate(filepath.Join(c.dir, "ipc.json"), map[string]any{"v": 1, "url": "http://" + host, "token": c.ipcToken, "hostInstanceId": c.instance, "pid": os.Getpid()}); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	defer os.Remove(filepath.Join(c.dir, "ipc.json"))
	go c.accept()
	server := &http.Server{ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 32768, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r = r.WithContext(context.WithValue(r.Context(), listenKey{}, host))
		if r.URL.Path == "/" && r.URL.Query().Get("bootstrap") == c.ipcToken {
			http.SetCookie(w, &http.Cookie{Name: "dsha-connect", Value: c.ipcToken, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
			http.Redirect(w, r, "/", 303)
			return
		}
		c.localAPI(w, r)
	})}
	go func() { <-ctx.Done(); server.Close() }()
	fmt.Printf("DSHA Connect: http://%s/?bootstrap=%s\nPrivate state: %s\n", host, c.ipcToken, c.dir)
	if e = server.Serve(listener); e != nil && !errors.Is(e, http.ErrServerClosed) {
		fmt.Fprintln(os.Stderr, e)
	}
}

const bootstrapHTML = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>DSHA Connect</title><style>body{font:16px system-ui;max-width:680px;margin:50px auto;padding:20px;color:#20242a;background:#f7f8fa}section{padding:24px;background:white;border:1px solid #ddd;border-radius:18px}img{max-width:100%}button{font:inherit;padding:12px 22px;border-radius:8px}pre{white-space:pre-wrap}small{color:#52606d}</style><section><h1>DSHA Connect</h1><p>手机扫描此二维码，确认电脑身份后连接电脑自己的 DSH。</p><img id="qr" src="/v1/qr.png" alt="配对二维码"><p id="status">正在读取连接状态…</p><p id="network"></p><small>二维码三分钟有效。扫码密钥仅用于首次配对；长期信任使用独立密钥。停止或撤销后，未知结果不会自动重放。</small><p><button id="revoke">撤销手机信任</button></p><pre id="targets"></pre></section><script>async function refresh(){let r=await fetch('/v1/status');if(!r.ok){document.getElementById('status').textContent='请打开终端提供的本机启动链接。';return}let s=await r.json();document.getElementById('status').textContent=s.revoked?'已撤销；退出后重新运行以生成二维码':s.connected?'手机已连接':s.trusted?'已记住手机；扫码可更新电脑地址':'等待扫码（二维码三分钟有效）';document.getElementById('qr').hidden=s.revoked;document.getElementById('qr').src='/v1/qr.png?mode='+(s.trusted?'refresh':'pair')+'&minute='+Math.floor(Date.now()/60000);document.getElementById('network').textContent=s.networkStatus==='discovery-unavailable'?'发现或中继服务暂不可用；已信任手机可尝试局域网直连。':' ';document.getElementById('targets').textContent=s.targets.map(t=>t.name+' · '+t.id).join('\n')||'尚未登记 DSH。关闭 companion 后用 --dsh-url 填写本机 DSH 启动链接。'}document.getElementById('revoke').onclick=async()=>{await fetch('/v1/revoke',{method:'POST'});document.getElementById('status').textContent='已撤销。请重新运行 companion 生成新二维码。';document.getElementById('qr').hidden=true};refresh();setInterval(refresh,3000)</script></html>`
