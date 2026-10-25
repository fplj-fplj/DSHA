package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"github.com/dsha/connect-core/discovery"
	"github.com/libp2p/go-libp2p/core/crypto"
	"os"
	"time"
)

func (c *companion) manageDiscovery(ctx context.Context, endpoint string, relays []string) error {
	e := c.configureDiscovery(ctx, endpoint, relays)
	if e == nil {
		return nil
	}
	c.mu.Lock()
	safeDirect := c.state.PeerID != "" && !c.state.DiscoveryRevokePending
	c.mu.Unlock()
	if !safeDirect {
		return e
	}
	fmt.Fprintln(os.Stderr, "发现服务暂不可用；已保留已信任手机的局域网直连，后台会重试发现服务。")
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				c.mu.Lock()
				revoked := c.revoked
				c.mu.Unlock()
				if revoked {
					return
				}
				if c.configureDiscovery(ctx, endpoint, relays) == nil {
					return
				}
			}
		}
	}()
	return nil
}
func (c *companion) configureDiscovery(ctx context.Context, endpoint string, relays []string) (result error) {
	defer func() {
		c.mu.Lock()
		c.discoveryReady = result == nil
		if result != nil {
			c.discoveryError = result.Error()
		} else {
			c.discoveryError = ""
		}
		c.mu.Unlock()
	}()
	if endpoint == "" {
		endpoint = c.state.DiscoveryURL
	}
	if endpoint == "" {
		if len(relays) > 0 {
			return errors.New("relay requires --discovery so the exact peer pair can be authorized")
		}
		return nil
	}
	c.mu.Lock()
	c.state.DiscoveryURL = endpoint
	if c.state.DiscoveryToken == "" {
		c.state.DiscoveryToken = randomHex(32)
	}
	e := c.saveLocked()
	c.mu.Unlock()
	if e != nil {
		return e
	}
	raw, e := base64.StdEncoding.DecodeString(c.node.IdentityBase64())
	if e != nil {
		return e
	}
	key, e := crypto.UnmarshalPrivateKey(raw)
	if e != nil {
		return e
	}
	client := discovery.Client{URL: endpoint}
	if c.state.DiscoveryRevokePending {
		fresh := randomHex(32)
		hash, _ := discovery.CapabilityHash(fresh)
		request := discovery.Request{Action: "advertise", CapabilityHash: hash, Addresses: c.node.Addresses(), Expires: time.Now().Add(time.Hour).Unix()}
		if _, e = client.Do(ctx, key, request); e != nil {
			if _, e = client.Do(ctx, key, discovery.Request{Action: "revoke", OwnerID: c.node.PeerID(), Expires: time.Now().Add(time.Minute).Unix()}); e != nil {
				return errors.New("local trust revoked; discovery revocation must complete before a new pairing")
			}
			if _, e = client.Do(ctx, key, request); e != nil {
				return e
			}
		}
		c.mu.Lock()
		c.state.DiscoveryToken = fresh
		c.state.DiscoveryRevokePending = false
		e = c.saveLocked()
		c.mu.Unlock()
		if e != nil {
			return e
		}
	}
	hash, _ := discovery.CapabilityHash(c.state.DiscoveryToken)
	advertise := func() error {
		c.mu.Lock()
		revoked := c.revoked
		confirmedPeer := c.state.PeerID
		c.mu.Unlock()
		if revoked {
			return errors.New("trust revoked")
		}
		_, e := client.Do(ctx, key, discovery.Request{Action: "advertise", CapabilityHash: hash, ConfirmedPeerID: confirmedPeer, Addresses: append(c.node.Addresses(), c.relayAddresses...), Expires: time.Now().Add(time.Hour).Unix()})
		return e
	}
	if e = advertise(); e != nil {
		return e
	}
	for _, relay := range relays {
		attempt, cancel := context.WithTimeout(ctx, 15*time.Second)
		addr, e := c.node.ReserveRelay(attempt, relay)
		cancel()
		if e != nil {
			return e
		}
		c.relayAddresses = append(c.relayAddresses, addr)
	}
	if len(relays) > 0 {
		if e = advertise(); e != nil {
			return e
		}
	}
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := advertise(); err != nil {
					c.mu.Lock()
					c.discoveryReady = false
					c.discoveryError = err.Error()
					c.mu.Unlock()
					continue
				}
				c.mu.Lock()
				c.discoveryReady = true
				c.discoveryError = ""
				c.mu.Unlock()
				for _, relay := range relays {
					attempt, cancel := context.WithTimeout(ctx, 15*time.Second)
					_, renewError := c.node.ReserveRelay(attempt, relay)
					cancel()
					if renewError != nil {
						c.mu.Lock()
						c.discoveryReady = false
						c.discoveryError = renewError.Error()
						c.mu.Unlock()
					}
				}
			}
		}
	}()
	return nil
}
