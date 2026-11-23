"""Check real APK assets after Gradle packaging, before signing/publication.

This is not a device/VPN/TLS acceptance test or an APK signature verifier.
"""
import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME_SHA = '19c0c8ff7fd8e59cdcd29d9f338f504dc67275809496619c2915205894dd49b5'


def verify(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        if any('__pycache__' in name or name.endswith('.pyc') for name in names):
            raise ValueError('Generated Python cache in APK')
        if any(name.endswith(('.pk8', '.key', '.jks', '.keystore')) for name in names):
            raise ValueError('Private key filename in APK')
        runtime = apk.read('assets/embedded/runtime.bin')
        if hashlib.sha256(runtime).hexdigest() != RUNTIME_SHA:
            raise ValueError('Packaged runtime does not match pinned gzip bytes')
        if 'assets/embedded/runtime.tar' in names or 'assets/embedded/runtime.tar.gz' in names:
            raise ValueError('Unexpected Gradle-expanded gzip asset')
        if apk.read('assets/embedded/engine.py') != (ROOT / 'capture/src/main/assets/embedded/engine.py').read_bytes():
            raise ValueError('Packaged engine differs from source')
        if 'lib/arm64-v8a/libwg-go.so' not in names:
            raise ValueError('Missing ARM64 WireGuard backend')
    print('Verified capture APK assets: ' + str(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='*', type=Path)
    args = parser.parse_args()
    apks = args.apks or sorted((ROOT / 'app/build/outputs/apk').glob('*/*.apk'))
    if not apks:
        raise SystemExit('No APKs found for asset verification')
    for path in apks:
        verify(path)


if __name__ == '__main__':
    main()
