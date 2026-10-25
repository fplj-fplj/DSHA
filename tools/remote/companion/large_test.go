package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	core "github.com/dsha/connect-core"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

type zeroBytes struct{}

func (zeroBytes) Read(p []byte) (int, error) { clear(p); return len(p), nil }
func TestRealEncryptedHTTPFiveGB(t *testing.T) {
	if os.Getenv("DSHA_PC_LARGE") != "1" {
		t.Skip("explicit 5 GB transport acceptance")
	}
	const size int64 = 5000000000
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			hash := sha256.New()
			count, e := io.CopyBuffer(hash, r.Body, make([]byte, 65536))
			if e != nil || count != size {
				http.Error(w, "incomplete upload", 400)
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"bytes": count, "sha256": hex.EncodeToString(hash.Sum(nil))})
			return
		}
		w.Header().Set("Content-Length", fmt.Sprint(size))
		_, _ = io.CopyBuffer(w, io.LimitReader(zeroBytes{}, size), make([]byte, 65536))
	}))
	defer server.Close()
	c, e := newCompanion(ctx, t.TempDir(), server.URL, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	go c.accept()
	phone, e := core.New(core.Config{TrustedPeerID: c.node.PeerID(), PairingKeyHex: c.ticketKey, Generation: 4, ListenAddrs: []string{"/ip4/127.0.0.1/tcp/0"}})
	if e != nil {
		t.Fatal(e)
	}
	defer phone.Close()
	go func() { <-ctx.Done(); phone.Close() }()
	if e = phone.Connect(ctx, c.node.Addresses()); e != nil {
		t.Fatal(e)
	}
	s, e := phone.Open(ctx, core.Control)
	if e != nil {
		t.Fatal(e)
	}
	writeFrame(s, control{V: 1, Op: "pair", RequestID: "large-pair", Generation: 4})
	var paired struct {
		Result struct {
			Key string `json:"reconnectKey"`
		} `json:"result"`
	}
	if e = readResponseFrame(s, &paired); e != nil {
		t.Fatal(e)
	}
	s.Close()
	if e = phone.SetTrust(c.node.PeerID(), paired.Result.Key); e != nil {
		t.Fatal(e)
	}
	s, e = phone.Open(ctx, core.Control)
	if e != nil {
		t.Fatal(e)
	}
	hello, _ := json.Marshal(map[string]any{"identity": identity{PeerID: c.node.PeerID(), HostInstanceID: c.instance, SessionID: "default", ConnectionEpoch: randomHex(16), TrustRevision: 1, Generation: 4}})
	writeFrame(s, control{V: 1, Op: "hello", RequestID: "large-hello", Generation: 4, Args: hello})
	var accepted any
	if e = readResponseFrame(s, &accepted); e != nil {
		t.Fatal(e)
	}
	s.Close()
	stream, e := phone.Open(ctx, core.HTTP)
	if e != nil {
		t.Fatal(e)
	}
	writeFrame(stream, map[string]any{"v": 1, "service": "dsh-web", "generation": 4, "targetId": "default"})
	req, _ := http.NewRequest("POST", "http://127.0.0.1/transfer", io.NopCloser(io.LimitReader(zeroBytes{}, size)))
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	started := time.Now()
	if e = req.Write(stream); e != nil {
		t.Fatal(e)
	}
	res, e := http.ReadResponse(bufio.NewReader(stream), req)
	if e != nil {
		t.Fatal(e)
	}
	var receipt struct {
		Bytes int64  `json:"bytes"`
		SHA   string `json:"sha256"`
	}
	if e = json.NewDecoder(res.Body).Decode(&receipt); e != nil {
		t.Fatal(e)
	}
	res.Body.Close()
	stream.Close()
	if res.StatusCode != 200 || receipt.Bytes != size {
		t.Fatal("upload did not reach exact 5 GB")
	}
	stream, e = phone.Open(ctx, core.HTTP)
	if e != nil {
		t.Fatal(e)
	}
	writeFrame(stream, map[string]any{"v": 1, "service": "dsh-web", "generation": 4, "targetId": "default"})
	io.WriteString(stream, "GET /transfer HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
	res, e = http.ReadResponse(bufio.NewReader(stream), nil)
	if e != nil {
		t.Fatal(e)
	}
	hash := sha256.New()
	count, e := io.CopyBuffer(hash, res.Body, make([]byte, 65536))
	res.Body.Close()
	stream.Close()
	if e != nil || count != size || hex.EncodeToString(hash.Sum(nil)) != receipt.SHA {
		t.Fatalf("download mismatch bytes=%d error=%v", count, e)
	}
	t.Logf("actual Noise/libp2p authenticated HTTP proxy upload+download: each %d bytes, same SHA256 %s, %s; no phone/SAF claim", size, receipt.SHA, time.Since(started))
}
