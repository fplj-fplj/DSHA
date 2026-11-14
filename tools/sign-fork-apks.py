#!/usr/bin/env python3
"""为 fplj-fplj/DSHA 使用固定仓库证书签名，并核对实际 APK 身份和资产。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from build_flavors import CHOICES, selected_flavors

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--flavor', choices=CHOICES, default='both')
    args = parser.parse_args()
    identity = json.loads((ROOT / 'ci/fork-release-identity.json').read_text())
    lock = json.loads((ROOT / 'ci/toolchain.lock.json').read_text())
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / lock['buildTools']
    keystore = Path(os.environ['DSHA_FORK_KEYSTORE'])
    source = (ROOT / 'app/build.gradle').read_text()
    version = re.search(r'versionName "([^"]+)"', source)[1]
    code = re.search(r'versionCode (\d+)', source)[1]
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError('SIGNING_OUTPUT_MUST_BE_EMPTY')
    args.output.mkdir(parents=True, exist_ok=True)
    receipts = []
    for flavor, minimum in selected_flavors(args.flavor):
        unsigned = ROOT / f'app/build/outputs/apk/{flavor}/release/app-{flavor}-release-unsigned.apk'
        target = args.output / ('dsha-0.2.1-rc.2' + ('-low' if flavor == 'low' else '') + '.apk')
        run(tools / 'zipalign', '-P', '16', '-f', '4', unsigned, target)
        run(tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', identity['alias'],
            '--ks-pass', 'env:DSHA_FORK_STORE_PASSWORD', '--key-pass', 'env:DSHA_FORK_STORE_PASSWORD',
            '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
            '--v4-signing-enabled', 'false', target)
        # Standard 的 minSdk 30 默认略过 v1；从 API 23 核验三种实际签名，清单版本另验。
        signature = run(tools / 'apksigner', 'verify', '--min-sdk-version', '23', '--verbose', '--print-certs', target)
        if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', signature) != [identity['certificateSha256']]:
            raise ValueError('FORK_CERTIFICATE_MISMATCH')
        for scheme in identity['schemes']:
            if not re.search(r'Verified using ' + scheme + r' scheme[^\n]*: true', signature):
                raise ValueError('APK_SIGNATURE_SCHEME_MISSING:' + scheme)
        badging = run(tools / 'aapt', 'dump', 'badging', target)
        expected_version = version + ('low' if flavor == 'low' else '')
        if (f"package: name='{identity['packageName']}' versionCode='{code}' versionName='{expected_version}'" not in badging
                or f"sdkVersion:'{minimum}'" not in badging or 'application-debuggable' in badging
                or "native-code: 'arm64-v8a'" not in badging):
            raise ValueError('APK_PACKAGE_OR_BUILD_IDENTITY_MISMATCH:' + flavor)
        run(tools / 'zipalign', '-c', '-P', '16', '4', target)
        with zipfile.ZipFile(target) as apk:
            for name in ['adb-shell.py', 'device-shell-policy.py', 'runtime-descriptor.json',
                         'builtin-plugins/dsh-device-shell-guide/lib/index.js']:
                if apk.read('assets/' + name) != (ROOT / 'app/src/main/assets' / name).read_bytes():
                    raise ValueError('AUTHORIZED_APK_ASSET_MISMATCH:' + name)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        target.with_suffix('.apk.sha256').write_text(digest + '  ' + target.name + '\n')
        receipts.append(dict(flavor=flavor, package=identity['packageName'], versionCode=int(code),
                             versionName=expected_version, debuggable=False, certificateSha256=identity['certificateSha256'],
                             bytes=target.stat().st_size, sha256=digest, file=target.name))
    (args.output / 'build-receipt.json').write_text(json.dumps(dict(schema=1, commit=os.environ.get('GITHUB_SHA'),
        apks=receipts, deviceAcceptance='NOT_RUN'), ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(receipts, ensure_ascii=False))


if __name__ == '__main__':
    main()
