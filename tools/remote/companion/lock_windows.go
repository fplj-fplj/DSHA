//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"path/filepath"
)

func lockDirectory(dir string) (func(), error) {
	path, e := windows.UTF16PtrFromString(filepath.Join(dir, "instance.lock"))
	if e != nil {
		return nil, e
	}
	handle, e := windows.CreateFile(path, windows.GENERIC_READ|windows.GENERIC_WRITE, 0, nil, windows.OPEN_ALWAYS, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if e != nil {
		return nil, e
	}
	return func() { windows.CloseHandle(handle) }, nil
}
