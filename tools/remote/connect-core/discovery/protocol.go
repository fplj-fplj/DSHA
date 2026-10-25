// Package discovery authenticates temporary rendezvous metadata. It never handles
// the application pairing key, chat, screen, files, or HTTP forwarding.
package discovery

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"
)

const Domain = "dsha-rendezvous-v1\n"
const MaxBody = 16384

// Request is signed over Domain + JSON of all fields except Signature. Never put
// a capability in a URL, HTTP header, or log. Expires is an absolute UTC Unix time.
type Request struct {
	Action  string `json:"action"`
	PeerID  string `json:"peerId"`
	OwnerID string `json:"ownerId,omitempty"`
	// ConfirmedPeerID is an owner-signed restoration of a previously
	// authenticated, durably remembered pair; it never grants a phone lease.
	ConfirmedPeerID string   `json:"confirmedPeerId,omitempty"`
	CapabilityHash  string   `json:"capabilityHash,omitempty"`
	Capability      string   `json:"capability,omitempty"`
	Addresses       []string `json:"addresses,omitempty"`
	Expires         int64    `json:"expires"`
	Issued          int64    `json:"issued"`
	Nonce           string   `json:"nonce"`
	PublicKey       string   `json:"publicKey"`
	Signature       string   `json:"signature,omitempty"`
}

type Record struct {
	OwnerID        string   `json:"ownerId"`
	OwnerAddresses []string `json:"ownerAddresses"`
	PeerID         string   `json:"peerId,omitempty"`
	PeerAddresses  []string `json:"peerAddresses,omitempty"`
	Expires        int64    `json:"expires"`
}

func NewCapability() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func CapabilityHash(s string) (string, error) {
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 32 {
		return "", errors.New("capability must be 32 random bytes encoded as hex")
	}
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:]), nil
}

func Sign(k crypto.PrivKey, r Request) (Request, error) {
	if r.Issued == 0 {
		r.Issued = time.Now().Unix()
	}
	id, err := peer.IDFromPrivateKey(k)
	if err != nil {
		return r, err
	}
	r.PeerID = id.String()
	pk, err := crypto.MarshalPublicKey(k.GetPublic())
	if err != nil {
		return r, err
	}
	r.PublicKey = base64.StdEncoding.EncodeToString(pk)
	if r.Nonce == "" {
		b := make([]byte, 16)
		if _, err := rand.Read(b); err != nil {
			return r, err
		}
		r.Nonce = hex.EncodeToString(b)
	}
	r.Signature = ""
	b, err := json.Marshal(r)
	if err != nil {
		return r, err
	}
	sig, err := k.Sign(append([]byte(Domain), b...))
	r.Signature = base64.StdEncoding.EncodeToString(sig)
	return r, err
}

func Verify(r Request, now time.Time, maxTTL time.Duration) (peer.ID, error) {
	if r.Issued < now.Add(-2*time.Minute).Unix() || r.Issued > now.Add(30*time.Second).Unix() {
		return "", errors.New("request timestamp outside window")
	}
	if r.Expires <= now.Unix() || r.Expires > now.Add(maxTTL).Unix() {
		return "", errors.New("expired or excessive lease")
	}
	n, err := hex.DecodeString(r.Nonce)
	if err != nil || len(n) != 16 {
		return "", errors.New("invalid nonce")
	}
	b, err := base64.StdEncoding.DecodeString(r.PublicKey)
	if err != nil || len(b) > 1024 {
		return "", errors.New("invalid public key")
	}
	pk, err := crypto.UnmarshalPublicKey(b)
	if err != nil {
		return "", err
	}
	id, err := peer.IDFromPublicKey(pk)
	if err != nil || id.String() != r.PeerID {
		return "", errors.New("peer identity does not match key")
	}
	sig, err := base64.StdEncoding.DecodeString(r.Signature)
	if err != nil {
		return "", errors.New("invalid signature")
	}
	r.Signature = ""
	b, _ = json.Marshal(r)
	ok, err := pk.Verify(append([]byte(Domain), b...), sig)
	if err != nil || !ok {
		return "", errors.New("signature rejected")
	}
	return id, nil
}

func ValidateAddresses(id string, addresses []string) error {
	if len(addresses) > 16 {
		return errors.New("too many addresses")
	}
	for _, s := range addresses {
		if len(s) > 512 {
			return errors.New("address too long")
		}
		a, err := ma.NewMultiaddr(s)
		if err != nil {
			return err
		}
		info, err := peer.AddrInfoFromP2pAddr(a)
		if err != nil || info.ID.String() != id {
			return fmt.Errorf("address peer identity mismatch")
		}
	}
	return nil
}
