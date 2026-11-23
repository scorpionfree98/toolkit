"""Stage the validated ARM64 runtime; never download or package phone secrets."""
import hashlib
import os
from pathlib import Path
import shutil
import re
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SHA = '19c0c8ff7fd8e59cdcd29d9f338f504dc67275809496619c2915205894dd49b5'
# Gradle's asset merger transparently expands .gz assets and removes the suffix.
# A neutral extension preserves the exact gzip bytes consumed by RuntimeHost.
target = ROOT / 'src/main/assets/embedded/runtime.bin'
source = Path(os.environ.get('CAPTURE_RUNTIME_ARCHIVE', str(target)))
if not source.is_file():
    source = target
if not source.is_file():
    raise SystemExit('Missing verified runtime: set CAPTURE_RUNTIME_ARCHIVE to shell-runtime-dns.tar.gz')
digest = hashlib.sha256()
with source.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest() != SHA:
    raise SystemExit('Runtime archive SHA256 mismatch; build stopped')
# Defense in depth: do not reintroduce even public test-only private keys.
private_pem = re.compile(rb'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{64,}-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')
with tarfile.open(source, 'r:gz') as archive:
    for member in archive:
        if member.name.endswith(('.key', '.pk8', '.p12', '.pfx', '.jks', '.keystore')):
            raise SystemExit('Private key filename in runtime; build stopped')
        if member.isfile() and private_pem.search(archive.extractfile(member).read()):
            raise SystemExit('Private key PEM in runtime; build stopped')
target.parent.mkdir(parents=True, exist_ok=True)
if source.resolve() != target.resolve():
    shutil.copyfile(source, target)
print(f'Validated embedded runtime: {target.stat().st_size} bytes')
