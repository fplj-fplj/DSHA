"""Verify the pinned native connection library and its actual source inputs."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / 'tools/remote/connect-core/android-runtime.lock.json'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def check():
    pin = json.loads(LOCK.read_text(encoding='utf-8'))
    if pin.get('schema') != 1 or pin.get('aarPath') != 'app/libs/dsha-connect.aar':
        raise ValueError('REMOTE_NATIVE_LOCK_SCHEMA')
    path = ROOT / pin['aarPath']
    if path.is_symlink() or not path.is_file() or path.stat().st_size != pin['aarBytes'] or sha(path) != pin['aarSha256']:
        raise ValueError('REMOTE_NATIVE_ARCHIVE_MISMATCH')
    inputs = pin.get('sources', {})
    if not inputs or 'tools/remote/connect-core/go.mod' not in inputs or 'tools/remote/connect-core/go.sum' not in inputs:
        raise ValueError('REMOTE_NATIVE_SOURCE_PINS_MISSING')
    for name, expected in inputs.items():
        source = ROOT / name
        if '\\' in name or not name.startswith('tools/remote/connect-core/') or '..' in Path(name).parts or not source.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError('REMOTE_NATIVE_SOURCE_PATH')
        if source.is_symlink() or not source.is_file() or sha(source) != expected:
            raise ValueError('REMOTE_NATIVE_SOURCE_CHANGED:' + name)
    sys.path.insert(0, str(ROOT / 'tools'))
    spec = importlib.util.spec_from_file_location('native_elf_audit', ROOT / 'tools/audit-standard-apk.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('REMOTE_NATIVE_DUPLICATE_MEMBER')
        libraries = [name for name in names if name.startswith('jni/') and name.endswith('.so')]
        if libraries != ['jni/arm64-v8a/libgojni.so']:
            raise ValueError('REMOTE_NATIVE_ABI')
        elf = audit.inspect_elf(io.BytesIO(archive.read(libraries[0])))
        if not elf or elf['machine'] != 183 or not elf['aligned_16k'] or not elf['relro_mapped']:
            raise ValueError('REMOTE_NATIVE_ELF_NOT_ARM64_16K')
    return {'schema': 1, 'status': 'PASS_PINNED_ARM64_NATIVE_AND_SOURCES', 'bytes': path.stat().st_size,
            'sha256': pin['aarSha256'], 'sourceFiles': len(inputs), 'elf': elf}


if __name__ == '__main__':
    print(json.dumps(check(), sort_keys=True))
