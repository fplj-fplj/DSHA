package relay

import (
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"sync"
	"time"

	"github.com/dsha/connect-core/discovery"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"
)

var ErrDenied = errors.New("rendezvous authorization denied")
var ErrCapacity = errors.New("rendezvous capacity reached")

type entry struct {
	record      discovery.Record
	claimBefore int64
	capHash     string
	peerExpires int64
}

// Registry contains short-lived metadata only, never application secrets or
// payloads. A QR capability claims exactly one peer; it cannot be reassigned.
type Registry struct {
	mu         sync.Mutex
	entries    map[string]*entry
	nonces     map[string]int64
	maxRecords int
	maxTTL     time.Duration
	claimTTL   time.Duration
	now        func() time.Time
	retired    map[string]bool
}

func NewRegistry(maxRecords int, maxTTL time.Duration) *Registry {
	return &Registry{entries: map[string]*entry{}, nonces: map[string]int64{}, maxRecords: maxRecords, maxTTL: maxTTL, claimTTL: 5 * time.Minute, now: time.Now, retired: map[string]bool{}}
}

func (r *Registry) sweep(now int64) {
	for k, v := range r.entries {
		if v.record.Expires <= now {
			r.retired[v.record.OwnerID] = true
			if v.record.PeerID != "" {
				r.retired[v.record.PeerID] = true
			}
			delete(r.entries, k)
		} else if v.peerExpires > 0 && v.peerExpires <= now {
			r.retired[v.record.OwnerID] = true
			r.retired[v.record.PeerID] = true
			v.peerExpires = 0
		}
	}
	for k, v := range r.nonces {
		if v <= now {
			delete(r.nonces, k)
		}
	}
}

func (r *Registry) Apply(req discovery.Request) (discovery.Record, error) {
	id, err := discovery.Verify(req, r.now(), r.maxTTL)
	if err != nil {
		return discovery.Record{}, ErrDenied
	}
	if err := discovery.ValidateAddresses(id.String(), req.Addresses); err != nil {
		return discovery.Record{}, ErrDenied
	}
	if req.ConfirmedPeerID != "" && req.Action != "advertise" {
		return discovery.Record{}, ErrDenied
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.now().Unix()
	r.sweep(now)
	nonce := id.String() + ":" + req.Nonce
	if _, ok := r.nonces[nonce]; ok {
		return discovery.Record{}, ErrDenied
	}
	// This map has a hard bound, including unaffiliated signed requests.
	if len(r.nonces) >= r.maxRecords*32 {
		return discovery.Record{}, ErrCapacity
	}
	owner := req.OwnerID
	if req.Action == "advertise" {
		owner = id.String()
	}
	e := r.entries[owner]
	switch req.Action {
	case "advertise":
		if req.OwnerID != "" && req.OwnerID != id.String() {
			return discovery.Record{}, ErrDenied
		}
		if req.ConfirmedPeerID != "" {
			confirmed, err := peer.Decode(req.ConfirmedPeerID)
			if err != nil || confirmed == id || (e != nil && e.record.PeerID != "" && e.record.PeerID != confirmed.String()) {
				return discovery.Record{}, ErrDenied
			}
		}
		h, err := hex.DecodeString(req.CapabilityHash)
		if err != nil || len(h) != 32 || req.Capability != "" {
			return discovery.Record{}, ErrDenied
		}
		if e == nil {
			if len(r.entries) >= r.maxRecords {
				return discovery.Record{}, ErrCapacity
			}
			e = &entry{record: discovery.Record{OwnerID: owner}, capHash: req.CapabilityHash, claimBefore: now + int64(r.claimTTL/time.Second)}
			r.entries[owner] = e
		} else if subtle.ConstantTimeCompare([]byte(e.capHash), []byte(req.CapabilityHash)) != 1 {
			// Revoke before creating a different QR capability. Never silently
			// replace a claimed peer with a second holder of an old invitation.
			return discovery.Record{}, ErrDenied
		}
		if req.ConfirmedPeerID != "" {
			// An owner's valid signature can restore only its own exact peer
			// binding after RAM-only registry loss. The phone must still sign
			// claim with the capability to establish its independent lease.
			e.record.PeerID = req.ConfirmedPeerID
		}
		e.record.OwnerAddresses = append([]string(nil), req.Addresses...)
		e.record.Expires = req.Expires
	case "claim", "lookup":
		if e == nil || req.CapabilityHash != "" {
			return discovery.Record{}, ErrDenied
		}
		h, err := discovery.CapabilityHash(req.Capability)
		if err != nil || subtle.ConstantTimeCompare([]byte(h), []byte(e.capHash)) != 1 {
			return discovery.Record{}, ErrDenied
		}
		if req.Action == "claim" {
			if id.String() == owner {
				return discovery.Record{}, ErrDenied
			}
			if e.record.PeerID == "" {
				if now >= e.claimBefore {
					return discovery.Record{}, ErrDenied
				}
				e.record.PeerID = id.String()
			} else if e.record.PeerID != id.String() {
				return discovery.Record{}, ErrDenied
			}
			e.record.PeerAddresses = append([]string(nil), req.Addresses...)
			e.peerExpires = req.Expires
		} else if id.String() != owner && id.String() != e.record.PeerID {
			return discovery.Record{}, ErrDenied
		}
	case "revoke":
		if e == nil || (id.String() != owner && id.String() != e.record.PeerID) {
			return discovery.Record{}, ErrDenied
		}
		delete(r.entries, owner)
	default:
		return discovery.Record{}, ErrDenied
	}
	r.nonces[nonce] = req.Issued + 121
	record := e.record
	if record.PeerID != "" && e.peerExpires > 0 && e.peerExpires < record.Expires {
		record.Expires = e.peerExpires
	}
	record.OwnerAddresses = append([]string(nil), record.OwnerAddresses...)
	record.PeerAddresses = append([]string(nil), record.PeerAddresses...)
	return record, nil
}

func (r *Registry) AllowReserve(p peer.ID, _ ma.Multiaddr) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sweep(r.now().Unix())
	pid := p.String()
	for _, e := range r.entries {
		if e.record.OwnerID == pid || (e.record.PeerID == pid && e.peerExpires > r.now().Unix()) {
			return true
		}
	}
	return false
}

func (r *Registry) AllowConnect(src peer.ID, _ ma.Multiaddr, dest peer.ID) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sweep(r.now().Unix())
	srcID, destID := src.String(), dest.String()
	for _, e := range r.entries {
		if e.peerExpires > r.now().Unix() && e.record.PeerID != "" && ((e.record.OwnerID == srcID && e.record.PeerID == destID) || (e.record.OwnerID == destID && e.record.PeerID == srcID)) {
			return true
		}
	}
	return false
}

// DrainRetired closes transport connections even if the peer also holds another
// still-valid invitation. Clients may reconnect that other pair explicitly.
func (r *Registry) DrainRetired() []peer.ID {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sweep(r.now().Unix())
	out := []peer.ID{}
	for s := range r.retired {
		if p, err := peer.Decode(s); err == nil {
			out = append(out, p)
		}
	}
	r.retired = map[string]bool{}
	return out
}

func (r *Registry) Count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sweep(r.now().Unix())
	return len(r.entries)
}
