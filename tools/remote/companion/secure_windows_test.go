//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"os/user"
	"strings"
	"testing"
)

func TestProtectPreexistingBroadDirectory(t *testing.T) {
	dir := t.TempDir()
	broad, e := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;WD)")
	if e != nil {
		t.Fatal(e)
	}
	acl, _, e := broad.DACL()
	if e != nil {
		t.Fatal(e)
	}
	if e = windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil); e != nil {
		t.Fatal(e)
	}
	if e = secureDirectory(dir); e != nil {
		t.Fatal(e)
	}
	sd, e := windows.GetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if e != nil {
		t.Fatal(e)
	}
	u, _ := user.Current()
	text := sd.String()
	if strings.Contains(text, ";;;WD)") || strings.Count(text, "(A;") != 1 || !strings.Contains(text, u.Uid) {
		t.Fatal("state directory ACL was not replaced with only current user")
	}
}
