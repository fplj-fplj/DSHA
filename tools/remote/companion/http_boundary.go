package main

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"io"
	"net/http"
	"strings"
)

type boundedBody struct {
	io.ReadCloser
	left int64
}

// multipart boundaries and metadata are separate from the file's 5 GB quota.
const maxHTTPBody = int64(5000000000 + 8*1024*1024)

func (b *boundedBody) Read(p []byte) (int, error) {
	if b.left == 0 {
		var extra [1]byte
		n, e := b.ReadCloser.Read(extra[:])
		if n > 0 {
			return 0, errors.New("HTTP file body exceeds 5 GB")
		}
		return 0, e
	}
	if int64(len(p)) > b.left {
		p = p[:b.left]
	}
	n, e := b.ReadCloser.Read(p)
	b.left -= int64(n)
	return n, e
}

func boundedHTTPReader(r io.Reader) (*bufio.Reader, error) {
	in := bufio.NewReaderSize(r, 32768)
	var h bytes.Buffer
	for {
		line, e := in.ReadSlice('\n')
		if e != nil {
			return nil, e
		}
		if h.Len()+len(line) > 32768 {
			return nil, errors.New("HTTP header limit")
		}
		h.Write(line)
		if bytes.Equal(line, []byte("\r\n")) {
			break
		}
	}
	return bufio.NewReaderSize(io.MultiReader(bytes.NewReader(h.Bytes()), in), 32768), nil
}
func hasToken(value, want string) bool {
	for _, v := range strings.Split(value, ",") {
		if strings.EqualFold(strings.TrimSpace(v), want) {
			return true
		}
	}
	return false
}
func validWebSocket(req *http.Request, res *http.Response) bool {
	if !strings.EqualFold(req.Header.Get("Upgrade"), "websocket") || !strings.EqualFold(res.Header.Get("Upgrade"), "websocket") || !hasToken(req.Header.Get("Connection"), "upgrade") || !hasToken(res.Header.Get("Connection"), "upgrade") {
		return false
	}
	key, e := base64.StdEncoding.DecodeString(req.Header.Get("Sec-WebSocket-Key"))
	if e != nil || len(key) != 16 {
		return false
	}
	sum := sha1.Sum([]byte(req.Header.Get("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return res.Header.Get("Sec-WebSocket-Accept") == base64.StdEncoding.EncodeToString(sum[:])
}
