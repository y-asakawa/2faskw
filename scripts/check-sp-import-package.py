#!/usr/bin/env python3
"""Check static release ZIP contents; this is a build helper, not an XML analyzer."""
import hashlib
import io
import json
import pathlib
import stat
import sys
import zipfile

archive, root, version = sys.argv[1:4]
required = {
    'graphicalmatrix-sp-import.sh', f'lib/2faskw-sp-import-tools-{version}.jar', 'lib/jackson-core-2.21.7.jar',
    'README.md', 'LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md',
    'package-metadata/VERSION', 'package-metadata/COMPATIBILITY.txt',
    'package-metadata/PACKAGE-CONTENTS.txt', 'package-metadata/PACKAGE-MANIFEST.sha256', 'package-metadata/RUNTIME-DEPENDENCIES.txt',
    'package-metadata/SBOM.cdx.json',
}
with zipfile.ZipFile(archive) as z:
    entries = z.infolist()
    if len(entries) > 128 or sum(info.file_size for info in entries) > 32 * 1024 * 1024:
        raise SystemExit('SP Import ZIP exceeds content limits')
    seen = set()
    for info in entries:
        path = pathlib.PurePosixPath(info.filename)
        if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != root:
            raise SystemExit('unsafe ZIP path: ' + info.filename)
        relative = str(path.relative_to(root))
        if relative not in required or relative in seen or stat.S_ISLNK(info.external_attr >> 16):
            raise SystemExit('unexpected/duplicate/symlink ZIP entry: ' + info.filename)
        seen.add(relative)
        data = z.read(info)
        if any(marker in data for marker in (b'-----BEGIN PRIVATE KEY-----', b'-----BEGIN OPENSSH PRIVATE KEY-----', b'-----BEGIN PGP PRIVATE KEY BLOCK-----')):
            raise SystemExit('private key marker in ZIP')
    if seen != required:
        raise SystemExit('missing required files: ' + ', '.join(sorted(required - seen)))
    def read(name):
        return z.read(root + '/' + name)
    if read('package-metadata/VERSION').decode().strip() != version:
        raise SystemExit('version mismatch')
    if not (z.getinfo(root + '/graphicalmatrix-sp-import.sh').external_attr >> 16) & stat.S_IXUSR:
        raise SystemExit('launcher is not executable')
    readme = read('README.md').decode('utf-8')
    for marker in ('--defer-attributes', 'DEFER ATTRIBUTES ISOLATED', 'attribute-migration-todo.txt', 'SOURCE_METADATA_EXPIRED'):
        if marker not in readme:
            raise SystemExit('missing two-stage migration guidance: ' + marker)
    listed = set(read('package-metadata/PACKAGE-CONTENTS.txt').decode().splitlines())
    if listed != required - {'package-metadata/PACKAGE-MANIFEST.sha256'}:
        raise SystemExit('content list mismatch')
    hashed = set()
    for line in read('package-metadata/PACKAGE-MANIFEST.sha256').decode().splitlines():
        digest, name = line.split('  ', 1)
        if name not in listed or name in hashed or hashlib.sha256(read(name)).hexdigest() != digest:
            raise SystemExit('invalid manifest hash/path: ' + name)
        hashed.add(name)
    if hashed != listed:
        raise SystemExit('incomplete manifest')
    with zipfile.ZipFile(io.BytesIO(read(f'lib/2faskw-sp-import-tools-{version}.jar'))) as jar:
        for name in ('DeferredRegistration', 'MetadataValidity'):
            if 'io/github/yasakawa/faskw/spimport/' + name + '.class' not in jar.namelist():
                raise SystemExit('missing two-stage migration implementation: ' + name)
        manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\r\n', '\n')
        if 'Class-Path: jackson-core-2.21.7.jar\n' not in manifest:
            raise SystemExit('runtime dependency missing from JAR classpath')
        for entry in (
            'Implementation-Title: 2FAS-KW SP Import Tools',
            f'Implementation-Version: {version}',
            'Implementation-Vendor: 2FAS-KW Project',
        ):
            if entry + '\n' not in manifest:
                raise SystemExit('missing or invalid JAR attribution/version: ' + entry)
    sbom = json.loads(read('package-metadata/SBOM.cdx.json'))
    components = sbom['components']
    if sbom['metadata']['component']['version'] != version or len(components) != 1:
        raise SystemExit('SBOM version/component mismatch')
    component = components[0]
    if component['purl'] != 'pkg:maven/com.fasterxml.jackson.core/jackson-core@2.21.7' or component['hashes'] != [
            {'alg': 'SHA-256', 'content': hashlib.sha256(read('lib/jackson-core-2.21.7.jar')).hexdigest()}]:
        raise SystemExit('SBOM dependency hash mismatch')
print('SP Import package contents and manifest: OK')
