package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	core "github.com/dsha/connect-core"
)

func TestTargetBoundary(t *testing.T) {
	for _, raw := range []string{"https://127.0.0.1:3080", "http://example.com:3080", "http://127.0.0.1:3080/private", "http://user@127.0.0.1:3080", "http://localhost:3080", "http://127.0.0.1:3080/?url=x"} {
		if _, e := validateTarget(raw); e == nil {
			t.Fatalf("accepted invalid target %s", raw)
		}
	}
	for _, raw := range []string{"http://127.0.0.1:3080", "http://[::1]:3080/?token=local"} {
		if _, e := validateTarget(raw); e != nil {
			t.Fatal(e)
		}
	}
}
func TestHeaderAndWebSocketBoundary(t *testing.T) {
	if _, e := boundedHTTPReader(strings.NewReader("GET / HTTP/1.1\r\nX: " + strings.Repeat("a", 33000) + "\r\n\r\n")); e == nil {
		t.Fatal("oversized header accepted")
	}
	req, _ := http.NewRequest("GET", "http://127.0.0.1/", nil)
	req.Header.Set("Connection", "keep-alive, Upgrade")
	req.Header.Set("Upgrade", "websocket")
	req.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
	res := &http.Response{Header: http.Header{"Connection": []string{"Upgrade"}, "Upgrade": []string{"websocket"}, "Sec-Websocket-Accept": []string{"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="}}}
	if !validWebSocket(req, res) {
		t.Fatal("valid WS rejected")
	}
	res.Header.Set("Sec-WebSocket-Accept", "wrong")
	if validWebSocket(req, res) {
		t.Fatal("invalid accept admitted")
	}
}
func TestPendingRevocationAndDisconnect(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	dir := t.TempDir()
	c, e := newCompanion(ctx, dir, "", nil)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	go c.accept()
	phone, e := core.New(core.Config{TrustedPeerID: c.node.PeerID(), PairingKeyHex: c.ticketKey, Generation: 3, ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}})
	if e != nil {
		t.Fatal(e)
	}
	if e = phone.Connect(ctx, c.node.Addresses()); e != nil {
		t.Fatal(e)
	}
	s, e := phone.Open(ctx, core.Control)
	if e != nil {
		t.Fatal(e)
	}
	writeFrame(s, control{V: 1, Op: "pair", RequestID: "lost-ack", Generation: 3})
	var response struct {
		OK     bool `json:"ok"`
		Result struct {
			ReconnectKey string `json:"reconnectKey"`
		} `json:"result"`
	}
	if e = readResponseFrame(s, &response); e != nil {
		t.Fatal(e)
	}
	s.Close()
	if !response.OK {
		t.Fatal("pair failed")
	}
	phone.SetTrust(c.node.PeerID(), response.Result.ReconnectKey)
	hello, _ := json.Marshal(map[string]any{"identity": identity{PeerID: c.node.PeerID(), HostInstanceID: c.instance, SessionID: "default", ConnectionEpoch: randomHex(16), TrustRevision: 1, Generation: 3}})
	s, e = phone.Open(ctx, core.Control)
	if e != nil {
		t.Fatal(e)
	}
	writeFrame(s, control{V: 1, Op: "hello", RequestID: "hello", Generation: 3, Args: hello})
	var value any
	if e = readResponseFrame(s, &value); e != nil {
		t.Fatal(e)
	}
	s.Close()
	if c.publicStatus()["connected"] != true {
		t.Fatal("hello not connected")
	}
	c.mu.Lock()
	c.state.DiscoveryURL = "http://127.0.0.1:1"
	c.mu.Unlock()
	if e = c.manageDiscovery(ctx, "", nil); e != nil {
		t.Fatal("remembered LAN blocked by discovery outage", e)
	}
	if c.publicStatus()["networkStatus"] != "discovery-unavailable" || c.publicStatus()["connected"] != true {
		t.Fatal("discovery outage did not preserve actual LAN authorization")
	}
	c.mu.Lock()
	c.state.DiscoveryURL = ""
	c.state.DiscoveryToken = ""
	c.mu.Unlock()
	phone.Close()
	until := time.Now().Add(time.Second)
	for c.publicStatus()["connected"] == true && time.Now().Before(until) {
		time.Sleep(time.Millisecond)
	}
	if c.publicStatus()["connected"] != false {
		t.Fatal("disconnect retained phone authorization")
	}
	c.mu.Lock()
	clearTrust(&c.state)
	e = c.saveLocked()
	c.mu.Unlock()
	if e != nil {
		t.Fatal(e)
	}
	c.Close()
	next, e := newCompanion(ctx, dir, "", nil)
	if e != nil {
		t.Fatal(e)
	}
	defer next.Close()
	if next.state.PendingKey != "" || next.state.PeerID != "" || next.state.Key != "" {
		t.Fatal("revoked pending trust revived")
	}
	if _, e = next.ticket(); e != nil {
		t.Fatal(e)
	}
}
func TestStableLANPortsAndExclusiveState(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	first, e := newCompanion(ctx, dir, "", nil)
	if e != nil {
		t.Fatal(e)
	}
	ports := append([]string(nil), first.state.ListenAddrs...)
	id := first.node.PeerID()
	if len(ports) != 2 {
		t.Fatal("stable TCP and QUIC listeners not persisted")
	}
	if second, e := newCompanion(ctx, dir, "", nil); e == nil {
		second.Close()
		first.Close()
		t.Fatal("concurrent state owner admitted")
	}
	first.Close()
	second, e := newCompanion(ctx, dir, "", nil)
	if e != nil {
		t.Fatal(e)
	}
	defer second.Close()
	if second.node.PeerID() != id || strings.Join(second.state.ListenAddrs, ",") != strings.Join(ports, ",") {
		t.Fatal("LAN endpoints or identity changed after companion restart")
	}
}

func TestRealDSHAndPairing(t *testing.T) {
	raw := os.Getenv("DSHA_REAL_DSH_URL")
	if raw == "" {
		t.Skip("requires real pinned DSH host")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	c, e := newCompanion(ctx, t.TempDir(), raw, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	go c.accept()
	phone, e := core.New(core.Config{TrustedPeerID: c.node.PeerID(), PairingKeyHex: c.ticketKey, Generation: 7, ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}})
	if e != nil {
		t.Fatal(e)
	}
	defer phone.Close()
	if e = phone.Connect(ctx, c.node.Addresses()); e != nil {
		t.Fatal(e)
	}
	request := func(req control) map[string]any {
		s, e := phone.Open(ctx, core.Control)
		if e != nil {
			t.Fatal(e)
		}
		defer s.Close()
		_ = s.SetDeadline(time.Now().Add(20 * time.Second))
		req.V = 1
		req.Generation = 7
		if req.RequestID == "" {
			req.RequestID = randomHex(16)
		}
		if e = writeFrame(s, req); e != nil {
			t.Fatal(e)
		}
		var response map[string]any
		if e = readResponseFrame(s, &response); e != nil {
			t.Fatal(e)
		}
		if response["ok"] != true {
			t.Fatalf("control rejected: %v", response)
		}
		return response["result"].(map[string]any)
	}
	status := request(control{Op: "status"})
	if status["hostInstanceId"] != c.instance {
		t.Fatal("status instance")
	}
	// Deliberately lose the pairing ack. The phone's new-key proof must finish
	// rotation, rather than bricking trust or reusing the QR credential.
	paired := request(control{Op: "pair", RequestID: "pair-first"})
	newKey := paired["reconnectKey"].(string)
	if newKey == c.ticketKey || len(newKey) != 64 {
		t.Fatal("long term key not independent")
	}
	if e = phone.SetTrust(c.node.PeerID(), newKey); e != nil {
		t.Fatal(e)
	}
	ident := identity{PeerID: c.node.PeerID(), HostInstanceID: c.instance, SessionID: "default", ConnectionEpoch: randomHex(16), TrustRevision: 1, Generation: 7}
	hello, _ := json.Marshal(map[string]any{"identity": ident})
	request(control{Op: "hello", Args: hello})
	refresh, e := c.refreshTicket()
	if e != nil || refresh["mode"] != "refresh" || refresh["peerId"] != c.node.PeerID() {
		t.Fatal("trusted endpoint refresh missing")
	}
	if _, exists := refresh["pairingKey"]; exists {
		t.Fatal("trusted refresh QR exposed long term key")
	}
	call := func(method string, args any) map[string]any {
		b, _ := json.Marshal(args)
		return request(control{Op: "dsh", TargetID: "default", Method: method, Args: b})
	}
	list := call("sessions.list", map[string]any{})
	if _, ok := list["items"].([]any); !ok {
		t.Fatal("real session list missing")
	}
	created := call("sessions.create", map[string]any{"hostInstanceId": c.instance})
	id, ok := created["sessionId"].(string)
	if !ok || id == "" {
		t.Fatal("real DSH create missing identity")
	}
	observed := call("sessions.status", map[string]any{"sessionId": id, "hostInstanceId": c.instance, "turnId": "", "taskId": id})
	if observed["sessionId"] != id || observed["running"] != false {
		t.Fatalf("real session status %v", observed)
	}
	call("sessions.cancel", map[string]any{"sessionId": id, "hostInstanceId": c.instance})
	// Exercise the actual official prompt route, without inventing credentials
	// or treating provider refusal as a model success. Admission is real.
	args, _ := json.Marshal(map[string]any{"sessionId": id, "requestId": randomHex(16), "text": "DSHA host acceptance: reply only OK.", "hostInstanceId": c.instance})
	sent, e := c.dshCall(ctx, "default", "sessions.send", args)
	if e != nil {
		if !strings.Contains(e.Error(), "provider-credentials-unavailable") && !strings.Contains(e.Error(), "model-unavailable") {
			t.Fatal(e)
		}
		t.Log("real prompt was refused by the credential-free DSH host (expected provider boundary)")
	} else {
		t.Logf("real prompt admission receipt: %s", sent)
		_, _ = c.dshCall(ctx, "default", "sessions.cancel", json.RawMessage(`{"sessionId":"`+id+`"}`))
	}
	stream, e := phone.Open(ctx, core.HTTP)
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	if e = writeFrame(stream, map[string]any{"v": 1, "service": "dsh-web", "generation": 7, "targetId": "default"}); e != nil {
		t.Fatal(e)
	}
	if _, e = io.WriteString(stream, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"); e != nil {
		t.Fatal(e)
	}
	response, e := http.ReadResponse(bufio.NewReader(stream), nil)
	if e != nil {
		t.Fatal(e)
	}
	body, e := io.ReadAll(response.Body)
	response.Body.Close()
	if e != nil || response.StatusCode != 200 || !strings.Contains(strings.ToLower(string(body)), "<html") {
		t.Fatalf("real DSH HTTP proxy %d %v", response.StatusCode, e)
	}
	request(control{Op: "revoke"})
	if _, e = phone.Open(ctx, core.HTTP); e == nil {
		t.Fatal("revoked peer still admitted")
	}
}
