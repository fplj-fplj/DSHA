#!/bin/sh
set -eu
# The public key must arrive through an independently trusted channel, never
# select the trust anchor from the bundle being verified.
if [ "$#" -ne 2 ]; then echo "usage: verify-bundle.sh BUNDLE TRUSTED_RELEASE_PUBLIC_KEY_PEM" >&2; exit 2; fi
bundle=$(realpath "$1")
trust=$(realpath "$2")
test -d "$bundle"
test -f "$trust"
case "$trust" in "$bundle"/*) echo "trust key must be outside bundle" >&2; exit 2;; esac
cd "$bundle"
test -f SHA256SUMS
test ! -L SHA256SUMS
test -f SHA256SUMS.sig
test ! -L SHA256SUMS.sig
openssl pkeyutl -verify -pubin -inkey "$trust" -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig >/dev/null
# The signed pin file has a deliberately closed set of paths. No shell expansion,
# absolute paths, parent traversal, or unchecked files can enter the installer.
for file in dsha-relay dsha-relay.service config.example.json Caddyfile.example Dockerfile compose.example.yaml install-systemd.sh verify-bundle.sh DEPLOY.md; do
  test -f "$file"
  test ! -L "$file"
  actual=$(sha256sum "$file")
  grep -Fx "$actual" SHA256SUMS >/dev/null
done
test "$(wc -l < SHA256SUMS)" -eq 9
sha256sum --check --strict SHA256SUMS
echo 'signed bundle verified'
