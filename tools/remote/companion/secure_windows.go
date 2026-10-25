//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"os/user"
)

// Remove inherited ACL entries and grant only the current user. The state is
// separate from binaries and never requires elevation or a machine service.
func secureDirectory(path string) error {
	u, e := user.Current()
	if e != nil {
		return e
	}
	sd, e := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;" + u.Uid + ")")
	if e != nil {
		return e
	}
	acl, _, e := sd.DACL()
	if e != nil {
		return e
	}
	return windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
}
