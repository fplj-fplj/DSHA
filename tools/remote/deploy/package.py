#!/usr/bin/env python3
"""Create an operator-signed relay bundle; never creates or invents release keys."""
import argparse
import hashlib
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
FILES = ['dsha-relay.service', 'config.example.json', 'Caddyfile.example', 'Dockerfile',
         'compose.example.yaml', 'install-systemd.sh', 'verify-bundle.sh', 'DEPLOY.md']

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--binary', required=True, type=pathlib.Path)
    ap.add_argument('--out', required=True, type=pathlib.Path)
    ap.add_argument('--signing-key', required=True, type=pathlib.Path,
                    help='existing operator Ed25519 PEM release key; keep outside bundle')
    args = ap.parse_args()
    if args.binary.read_bytes()[:4] != b'\x7fELF':
        ap.error('binary must be a cross-built Linux ELF')
    args.out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(args.binary, args.out / 'dsha-relay')
    for name in FILES:
        # Portable LF shell scripts and deterministic hashes on Windows.
        (args.out / name).write_bytes((ROOT / name).read_bytes().replace(b'\r\n', b'\n'))
    files = ['dsha-relay'] + FILES
    lines = [hashlib.sha256((args.out / n).read_bytes()).hexdigest() + '  ' + n + '\n' for n in files]
    (args.out / 'SHA256SUMS').write_bytes(''.join(lines).encode('ascii'))
    subprocess.run(['openssl', 'pkeyutl', '-sign', '-inkey', str(args.signing_key.resolve()),
                    '-rawin', '-in', str(args.out / 'SHA256SUMS'),
                    '-out', str(args.out / 'SHA256SUMS.sig')], check=True)
    for name in ['dsha-relay', 'install-systemd.sh', 'verify-bundle.sh']:
        (args.out / name).chmod(0o555)
    print(args.out.resolve())

if __name__ == '__main__':
    main()
