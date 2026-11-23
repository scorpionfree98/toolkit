"""Derive a distribution archive without Tornado's public test-only private key.

Input must be the original pinned upstream runtime. No device files are read.
The source project is not modified; only the explicitly supplied output is written.
"""
import argparse
import gzip
import hashlib
from pathlib import Path
import tarfile

UPSTREAM_SHA256 = '6f05361208a0e402ced5665d87ec10b115dd52b4275db7e1fc5763cc37521be3'
REMOVED = {'shell-runtime-dns/site/tornado/test/test.key'}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if digest(args.source) != UPSTREAM_SHA256:
        raise SystemExit('Unexpected upstream runtime; refusing repack')
    if args.source.resolve() == args.output.resolve() or args.output.exists():
        raise SystemExit('Output must be a new file distinct from the upstream archive')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    removed = set()
    with tarfile.open(args.source, 'r:gz') as original, args.output.open('xb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as target:
                for member in original:
                    if member.name in REMOVED:
                        removed.add(member.name)
                        continue
                    target.addfile(member, original.extractfile(member) if member.isfile() else None)
    if removed != REMOVED:
        raise SystemExit('Expected fixture not found; do not publish this output')
    print('Removed only public test fixture: ' + ', '.join(sorted(removed)))
    print('SHA256 ' + digest(args.output))


if __name__ == '__main__':
    main()
