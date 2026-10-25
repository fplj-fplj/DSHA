// Package mobile contains the deliberately small gomobile Java binding.
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"time"

	core "github.com/dsha/connect-core"
)

type Client struct {
	node                         *core.Node
	addresses                    []string
	discoveryURL, discoveryToken string
	epoch                        int64
	leaseContext                 context.Context
	leaseCancel                  context.CancelFunc
	leaseOnce                    sync.Once
}
type Pipe struct {
	stream  *core.Stream
	readMu  sync.Mutex
	writeMu sync.Mutex
}

func NewClient(configJSON string) (*Client, error) {
	if len(configJSON) > 32768 {
		return nil, errors.New("configuration too large")
	}
	var c struct {
		core.Config
		Addresses []string `json:"addresses"`
	}
	if err := json.Unmarshal([]byte(configJSON), &c); err != nil {
		return nil, err
	}
	n, err := core.New(c.Config)
	if err != nil {
		return nil, err
	}
	leaseContext, leaseCancel := context.WithCancel(context.Background())
	return &Client{node: n, addresses: c.Addresses, discoveryURL: c.DiscoveryURL, discoveryToken: c.DiscoveryToken, leaseContext: leaseContext, leaseCancel: leaseCancel}, nil
}
func (c *Client) Connect(timeoutMillis int64) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout(timeoutMillis))
	defer cancel()
	if c.discoveryURL != "" {
		// A remembered endpoint remains usable on LAN when rendezvous is down.
		// Dialing authenticates its pinned identity; application streams still
		// independently require the current pairing key. No operation is replayed.
		var direct []string
		for _, address := range c.addresses {
			if !strings.Contains(address, "/p2p-circuit/") {
				direct = append(direct, address)
			}
		}
		if len(direct) > 0 {
			cachedCtx, cachedCancel := context.WithTimeout(ctx, 4*time.Second)
			err := c.node.Connect(cachedCtx, direct)
			cachedCancel()
			if err == nil {
				c.epoch = c.node.ConnectionEpoch()
				c.startDiscoveryRenewal()
				return nil
			}
		}
		lookupCtx, lookupCancel := context.WithTimeout(ctx, 10*time.Second)
		addresses, err := c.node.ClaimDiscovery(lookupCtx, c.discoveryURL, c.discoveryToken, c.node.TrustedPeerID())
		lookupCancel()
		if err == nil {
			c.addresses = mergeAddresses(addresses, c.addresses)
		} else if len(c.addresses) == 0 {
			return err
		}
	}
	if err := c.node.Connect(ctx, c.addresses); err != nil {
		return err
	}
	c.epoch = c.node.ConnectionEpoch()
	c.startDiscoveryRenewal()
	return nil
}
func (c *Client) ClaimDiscovery(url, token, ownerID string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	addresses, err := c.node.ClaimDiscovery(ctx, url, token, ownerID)
	if err != nil {
		return err
	}
	c.addresses = mergeAddresses(addresses, c.addresses)
	return nil
}
func mergeAddresses(preferred, cached []string) []string {
	out := make([]string, 0, 16)
	seen := make(map[string]bool)
	for _, list := range [][]string{preferred, cached} {
		for _, address := range list {
			if !seen[address] && len(out) < 16 {
				seen[address] = true
				out = append(out, address)
			}
		}
	}
	return out
}
func (c *Client) LocalPeerID() string    { return c.node.PeerID() }
func (c *Client) RemotePeerID() string   { return c.node.TrustedPeerID() }
func (c *Client) IdentityBase64() string { return c.node.IdentityBase64() }
func (c *Client) Generation() int64      { return c.node.Generation() }
func (c *Client) Route() string {
	if !c.IsCurrent() {
		return "disconnected"
	}
	return c.node.Route()
}
func (c *Client) IsCurrent() bool { return c.epoch == c.node.ConnectionEpoch() && c.node.IsConnected() }
func (c *Client) RoundTripMillis() (int64, error) {
	if !c.IsCurrent() {
		return -1, errors.New("remote connection epoch retired")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.node.RoundTripMillis(ctx)
}
func (c *Client) Open(kind string, timeoutMillis int64) (*Pipe, error) {
	if !c.IsCurrent() {
		return nil, errors.New("remote connection epoch retired")
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout(timeoutMillis))
	defer cancel()
	s, err := c.node.Open(ctx, kind)
	if err != nil {
		return nil, err
	}
	if !c.IsCurrent() {
		_ = s.Reset()
		return nil, errors.New("remote connection epoch retired")
	}
	return &Pipe{stream: s}, nil
}
func (c *Client) Accept(timeoutMillis int64) (*Pipe, error) {
	if c.epoch != 0 && !c.IsCurrent() {
		return nil, errors.New("remote connection epoch retired")
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout(timeoutMillis))
	defer cancel()
	s, err := c.node.Accept(ctx)
	if errors.Is(err, context.DeadlineExceeded) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if c.epoch != 0 && !c.IsCurrent() {
		_ = s.Reset()
		return nil, errors.New("remote connection epoch retired")
	}
	return &Pipe{stream: s}, nil
}
func (c *Client) Close() error { c.leaseCancel(); return c.node.Close() }

// Rendezvous permissions have a finite one-hour lease even for a confirmed
// device. Renew with the phone's own signature; this never extends a QR's
// initial claim window or sends application operations.
func (c *Client) startDiscoveryRenewal() {
	if c.discoveryURL == "" {
		return
	}
	c.leaseOnce.Do(func() {
		go func() {
			delay := time.Duration(0)
			for {
				timer := time.NewTimer(delay)
				select {
				case <-c.leaseContext.Done():
					timer.Stop()
					return
				case <-timer.C:
				}
				renewalCtx, cancel := context.WithTimeout(c.leaseContext, 10*time.Second)
				_, err := c.node.ClaimDiscovery(renewalCtx, c.discoveryURL, c.discoveryToken, c.node.TrustedPeerID())
				cancel()
				if err == nil {
					delay = 20 * time.Minute
				} else {
					delay = time.Minute
				}
			}
		}()
	})
}
func (p *Pipe) Kind() string { return p.stream.Kind }
func (p *Pipe) Read(maxBytes int, timeoutMillis int64) ([]byte, error) {
	if maxBytes < 1 || maxBytes > core.MaxChunk {
		return nil, errors.New("read size outside 1..65536")
	}
	p.readMu.Lock()
	defer p.readMu.Unlock()
	_ = p.stream.SetReadDeadline(time.Now().Add(timeout(timeoutMillis)))
	buf := make([]byte, maxBytes)
	n, err := p.stream.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	if err == io.EOF {
		return []byte{}, nil
	}
	return nil, err
}
func (p *Pipe) Write(data []byte, timeoutMillis int64) (int, error) {
	if len(data) > core.MaxChunk {
		return 0, errors.New("write chunk exceeds 65536")
	}
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_ = p.stream.SetWriteDeadline(time.Now().Add(timeout(timeoutMillis)))
	return p.stream.Write(data)
}
func (p *Pipe) CloseWrite() error { return p.stream.CloseWrite() }
func (p *Pipe) Close() error      { return p.stream.Close() }
func (p *Pipe) Cancel() error     { return p.stream.Reset() }
func timeout(ms int64) time.Duration {
	if ms < 1 {
		ms = 30000
	}
	if ms > 600000 {
		ms = 600000
	}
	return time.Duration(ms) * time.Millisecond
}
