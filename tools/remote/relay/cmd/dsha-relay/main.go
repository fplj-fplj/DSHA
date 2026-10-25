package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/dsha/relay"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
)

func loadIdentity(path string) (crypto.PrivKey, error) {
	st, err := os.Lstat(path)
	if err != nil {
		return nil, errors.New("identity is missing; run init-identity first")
	}
	if !st.Mode().IsRegular() || st.Mode().Perm()&0077 != 0 {
		return nil, errors.New("identity must be a regular private 0600 file")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, errors.New("identity could not be read")
	}
	raw, err := base64.StdEncoding.DecodeString(string(b))
	if err != nil {
		return nil, errors.New("identity encoding invalid")
	}
	return crypto.UnmarshalPrivateKey(raw)
}

func run() error {
	if len(os.Args) > 1 && os.Args[1] == "init-identity" {
		f := flag.NewFlagSet("init-identity", flag.ContinueOnError)
		path := f.String("out", "identity.key", "new private identity file; never overwrite")
		if err := f.Parse(os.Args[2:]); err != nil {
			return err
		}
		key, _, err := crypto.GenerateEd25519Key(rand.Reader)
		if err != nil {
			return err
		}
		raw, err := crypto.MarshalPrivateKey(key)
		if err != nil {
			return err
		}
		fd, err := os.OpenFile(*path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			return errors.New("identity target must be a new writable file")
		}
		if _, err = fd.WriteString(base64.StdEncoding.EncodeToString(raw)); err != nil {
			fd.Close()
			return err
		}
		if err = fd.Sync(); err != nil {
			fd.Close()
			return err
		}
		if err = fd.Close(); err != nil {
			return err
		}
		id, _ := peer.IDFromPrivateKey(key)
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"peerId": id.String(), "identityFile": filepath.Base(*path)})
	}
	f := flag.NewFlagSet("dsha-relay", flag.ContinueOnError)
	configDefault := os.Getenv("DSHA_RELAY_CONFIG")
	if configDefault == "" {
		configDefault = "/etc/dsha-relay/config.json"
	}
	path := f.String("config", configDefault, "JSON configuration file (or DSHA_RELAY_CONFIG)")
	check := f.Bool("check", false, "validate configuration and print identity without listening")
	if err := f.Parse(os.Args[1:]); err != nil {
		return err
	}
	fd, err := os.Open(*path)
	if err != nil {
		return errors.New("configuration could not be opened")
	}
	defer fd.Close()
	c := relay.DefaultConfig()
	dec := json.NewDecoder(io.LimitReader(fd, 32769))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return errors.New("configuration JSON invalid")
	}
	if err := dec.Decode(new(any)); err != io.EOF {
		return errors.New("configuration trailing data")
	}
	if err := c.Validate(); err != nil {
		return err
	}
	k, err := loadIdentity(c.IdentityFile)
	if err != nil {
		return err
	}
	if *check {
		id, _ := peer.IDFromPrivateKey(k)
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"valid": true, "peerId": id.String()})
	}
	s, err := relay.New(c, k)
	if err != nil {
		return fmt.Errorf("relay could not start: %w", err)
	}
	defer s.Close()
	if err := s.StartHTTP(); err != nil {
		return errors.New("HTTP listener could not start")
	}
	json.NewEncoder(os.Stdout).Encode(map[string]any{"ready": true, "relayPeerId": s.Host.ID().String(), "addresses": s.Addresses(), "recordsPersisted": false, "payloadLogging": false})
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}
