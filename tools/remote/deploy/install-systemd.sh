#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then echo "usage: install-systemd.sh BUNDLE TRUSTED_RELEASE_PUBLIC_KEY_PEM" >&2; exit 2; fi
test "$(id -u)" -eq 0
bundle=$(realpath "$1")
trust=$(realpath "$2")
"$bundle/verify-bundle.sh" "$bundle" "$trust"
# This installer only installs a new independent service. An existing install
# requires a separately reviewed upgrade, and no website/proxy config is touched.
test ! -e /opt/dsha-relay
test ! -L /opt/dsha-relay
test ! -e /etc/systemd/system/dsha-relay.service
test ! -L /etc/systemd/system/dsha-relay.service
test ! -e /etc/dsha-relay
test ! -L /etc/dsha-relay
test ! -e /var/lib/dsha-relay
test ! -L /var/lib/dsha-relay
getent passwd dsha-relay >/dev/null || useradd --system --home /var/lib/dsha-relay --shell /usr/sbin/nologin dsha-relay
test "$(id -u dsha-relay)" -ne 0
install -d -m 0755 /opt/dsha-relay
install -d -m 0750 -o root -g dsha-relay /etc/dsha-relay
install -d -m 0700 -o dsha-relay -g dsha-relay /var/lib/dsha-relay
install -m 0555 "$bundle/dsha-relay" /opt/dsha-relay/dsha-relay
install -m 0644 "$bundle/dsha-relay.service" /etc/systemd/system/dsha-relay.service
install -m 0640 -o root -g dsha-relay "$bundle/config.example.json" /etc/dsha-relay/config.example.json
runuser -u dsha-relay -- /opt/dsha-relay/dsha-relay init-identity -out /var/lib/dsha-relay/identity.key
systemctl daemon-reload
echo 'Installed, not started. Set real DNS/publicAddresses in /etc/dsha-relay/config.json, verify -check, then configure independent TLS virtual host and start service.'
