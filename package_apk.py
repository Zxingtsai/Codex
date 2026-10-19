#!/usr/bin/env python3
"""Keep all original resources/assets while replacing selected DEX and manifest bytes."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def patch_resource_package(data, old, new):
    data = bytearray(data)
    offset = struct.unpack_from('<H', data, 2)[0]
    found = 0
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        assert size >= header and offset + size <= len(data)
        if kind == 0x200:
            name = bytes(data[offset + 12:offset + 268]).decode('utf-16le').split('\0')[0]
            if name == old:
                encoded = new.encode('utf-16le')
                assert len(encoded) < 256
                data[offset + 12:offset + 268] = encoded.ljust(256, b'\0')
                found += 1
        offset += size
    assert found == 1, found
    return bytes(data)


def package(original, rebuilt, manifest, extension, metadata, output):
    meta = json.loads(Path(metadata).read_text())
    replacements = {'AndroidManifest.xml': Path(manifest).read_bytes(), 'classes7.dex': Path(extension).read_bytes()}
    changed = []
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(rebuilt) as base:
        for name in source.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                candidate = base.read(name)
                if candidate != source.read(name):
                    replacements[name] = candidate
                    changed.append(name)
        replacements['resources.arsc'] = patch_resource_package(source.read('resources.arsc'), meta['old_package'], meta['new_package'])
        with zipfile.ZipFile(output, 'w') as dest:
            for entry in source.infolist():
                if entry.filename.upper().startswith('META-INF/') and (entry.filename.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')) or entry.filename.upper() == 'META-INF/MANIFEST.MF'):
                    continue
                dest.writestr(entry, replacements.pop(entry.filename, None) or source.read(entry.filename))
            for name, content in replacements.items():
                dest.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(original) as a, zipfile.ZipFile(output) as b:
        untouched = [x for x in a.namelist() if x.startswith(('assets/', 'res/'))]
        assert all(a.read(x) == b.read(x) for x in untouched)
    print(json.dumps({'output': str(output), 'dex_reassembled': changed, 'unchanged_resource_asset_entries': len(untouched), 'sha256': hashlib.sha256(Path(output).read_bytes()).hexdigest()}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ['original', 'rebuilt', 'manifest', 'extension', 'metadata', 'output']:
        p.add_argument(name)
    a = p.parse_args()
    package(a.original, a.rebuilt, a.manifest, a.extension, a.metadata, a.output)
