//go:build !windows

package main

import (
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
)

func lockDirectory(dir string) (func(), error) {
	file, e := os.OpenFile(filepath.Join(dir, "instance.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB); e != nil {
		file.Close()
		return nil, e
	}
	return func() { unix.Flock(int(file.Fd()), unix.LOCK_UN); file.Close() }, nil
}
