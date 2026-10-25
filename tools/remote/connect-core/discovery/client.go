package discovery

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"time"

	"github.com/libp2p/go-libp2p/core/crypto"
)

// Client uses HTTPS certificate validation and a separately pinned relay peer
// identity in the transport. HTTP loopback is allowed for local integration only.
type Client struct {
	URL  string
	HTTP *http.Client
}

func (c Client) Do(ctx context.Context, key crypto.PrivKey, req Request) (Record, error) {
	u, err := url.Parse(c.URL)
	if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "https" && !(u.Scheme == "http" && (u.Hostname() == "127.0.0.1" || u.Hostname() == "::1" || u.Hostname() == "localhost"))) {
		return Record{}, errors.New("discovery requires HTTPS without credentials or query")
	}
	r, err := Sign(key, req)
	if err != nil {
		return Record{}, err
	}
	b, err := json.Marshal(r)
	if err != nil {
		return Record{}, err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.URL+"/v1/record", bytes.NewReader(b))
	if err != nil {
		return Record{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 10 * time.Second}
	if c.HTTP != nil {
		copy := *c.HTTP
		client = &copy
		if client.Timeout == 0 || client.Timeout > 10*time.Second {
			client.Timeout = 10 * time.Second
		}
	}
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return errors.New("discovery redirects disabled") }
	resp, err := client.Do(httpReq)
	if err != nil {
		return Record{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 512))
		return Record{}, errors.New("discovery request rejected")
	}
	b, err = io.ReadAll(io.LimitReader(resp.Body, MaxBody+1))
	if err != nil || len(b) > MaxBody {
		return Record{}, errors.New("discovery response exceeds bound")
	}
	var out Record
	if err := json.Unmarshal(b, &out); err != nil {
		return Record{}, err
	}
	return out, nil
}
