#!/usr/bin/env python3
"""Patch a compiled AndroidManifest.xml without recompiling resources.

Standard library only. Original string indexes, resource IDs, class names and
unselected chunks/attributes remain intact. New strings are appended to the
pool. Input may be raw AXML or an APK containing AndroidManifest.xml.

Usage: patch_manifest.py original.apk patched.xml --metadata metadata.json
       patch_manifest.py patched.xml --dump
"""
import argparse
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from xml.sax.saxutils import quoteattr

NO_INDEX = 0xFFFFFFFF
ANDROID = "http://schemas.android.com/apk/res/android"
NEW_PACKAGE = "com.sony.walkman.highresmediaplayer.sleepstat"
NEW_LABEL = "音乐播放器·睡眠统计"
NEW_VERSION = "3.0.4-sleepstat1"
NATIVE_LIBRARIES = (
    "libspusb_walkman.so", "libwmmediaplayer_walkman.so",
    "libspmedia_walkman.so", "libcnpysort.so", "libpinyin_convert.so",
)


def u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def chunk_header(kind, header_size, size):
    return struct.pack("<HHI", kind, header_size, size)


def length8(value):
    if value > 0x7FFF:
        raise ValueError("String too long for UTF-8 Android string pool")
    return bytes((0x80 | (value >> 8), value & 0xFF)) if value > 127 else bytes((value,))


def length16(value):
    return (struct.pack("<HH", 0x8000 | (value >> 16), value & 0xFFFF)
            if value > 0x7FFF else struct.pack("<H", value))


class StringPool:
    def __init__(self, chunk):
        self.original = bytes(chunk)
        self.header_size = u16(chunk, 2)
        count, self.style_count, self.flags, start, style_start = struct.unpack_from("<5I", chunk, 8)
        self.offsets = list(struct.unpack_from("<%dI" % count, chunk, self.header_size))
        self.style_offsets = chunk[self.header_size + count * 4:
                                   self.header_size + (count + self.style_count) * 4]
        self.payload = chunk[start:style_start or len(chunk)]
        self.styles = chunk[style_start:] if style_start else b""
        self.original_count = count
        self.strings = [self._decode(offset) for offset in self.offsets]
        self.lookup = {}
        for i, value in enumerate(self.strings):
            self.lookup.setdefault(value, i)

    def _decode(self, offset):
        data = self.payload
        if self.flags & 0x100:
            def read_length(pos):
                a = data[pos]
                return (((a & 127) << 8) | data[pos + 1], pos + 2) if a & 128 else (a, pos + 1)
            _, offset = read_length(offset)
            size, offset = read_length(offset)
            return data[offset:offset + size].decode("utf-8")
        size = u16(data, offset)
        offset += 2
        if size & 0x8000:
            size = ((size & 0x7FFF) << 16) | u16(data, offset)
            offset += 2
        return data[offset:offset + size * 2].decode("utf-16le")

    def get(self, index):
        return None if index == NO_INDEX else self.strings[index]

    def add(self, value):
        if value in self.lookup:
            return self.lookup[value]
        index = len(self.strings)
        self.lookup[value] = index
        self.strings.append(value)
        self.offsets.append(len(self.payload))
        units = len(value.encode("utf-16le")) // 2
        if self.flags & 0x100:
            encoded = value.encode("utf-8")
            self.payload += length8(units) + length8(len(encoded)) + encoded + b"\0"
        else:
            self.payload += length16(units) + value.encode("utf-16le") + b"\0\0"
        return index

    def build(self):
        if len(self.strings) == self.original_count:
            return self.original
        count = len(self.strings)
        start = self.header_size + 4 * count + len(self.style_offsets)
        payload = self.payload + b"\0" * (-len(self.payload) % 4)
        style_start = start + len(payload) if self.styles else 0
        size = start + len(payload) + len(self.styles)
        # Appended strings invalidate SORTED_FLAG, but UTF8_FLAG is preserved.
        header = chunk_header(1, self.header_size, size)
        header += struct.pack("<5I", count, self.style_count, self.flags & ~1, start, style_start)
        header += self.original[28:self.header_size]
        return header + struct.pack("<%dI" % count, *self.offsets) + self.style_offsets + payload + self.styles


class AXML:
    def __init__(self, data):
        if len(data) < 8 or u16(data, 0) != 3 or u32(data, 4) != len(data):
            raise ValueError("Input is not a complete compiled Android XML document")
        self.header = data[:u16(data, 2)]
        self.chunks = []
        offset = len(self.header)
        while offset < len(data):
            kind, header_size, size = struct.unpack_from("<HHI", data, offset)
            if size < header_size or header_size < 8 or offset + size > len(data):
                raise ValueError("Malformed AXML chunk at %d" % offset)
            self.chunks.append(bytearray(data[offset:offset + size]))
            offset += size
        pools = [i for i, c in enumerate(self.chunks) if u16(c, 0) == 1]
        if len(pools) != 1:
            raise ValueError("Expected one string pool")
        self.pool_index = pools[0]
        self.pool = StringPool(self.chunks[self.pool_index])
        self.resource_map = {}
        for c in self.chunks:
            if u16(c, 0) == 0x180:
                for i in range((len(c) - u16(c, 2)) // 4):
                    self.resource_map[i] = u32(c, u16(c, 2) + i * 4)

    def elements(self):
        for i, chunk in enumerate(self.chunks):
            if u16(chunk, 0) == 0x102:
                node_size = u16(chunk, 2)
                yield i, self.pool.get(u32(chunk, node_size + 4)), self.attributes(chunk)

    def attributes(self, chunk):
        ext = u16(chunk, 2)
        start, size, count = struct.unpack_from("<3H", chunk, ext + 8)
        attrs = []
        for i in range(count):
            offset = ext + start + i * size
            ns, name, raw = struct.unpack_from("<3I", chunk, offset)
            kind, value = chunk[offset + 15], u32(chunk, offset + 16)
            attrs.append({"offset": offset, "ns": self.pool.get(ns),
                          "name": self.pool.get(name), "name_index": name,
                          "raw_index": raw, "type": kind, "data": value,
                          "value": self.pool.get(value) if kind == 3 else value})
        return attrs

    def replace_string(self, chunk, attr, value):
        index = self.pool.add(value)
        offset = attr["offset"]
        struct.pack_into("<I", chunk, offset + 8, index)
        struct.pack_into("<HBBI", chunk, offset + 12, 8, 0, 3, index)

    def build(self):
        self.chunks[self.pool_index] = bytearray(self.pool.build())
        result = bytearray(self.header + b"".join(self.chunks))
        struct.pack_into("<I", result, 4, len(result))
        return bytes(result)

    def dump(self):
        """Readable XML; resource references remain numeric and types explicit."""
        lines, depth = ['<?xml version="1.0" encoding="utf-8"?>'], 0
        for chunk in self.chunks:
            kind = u16(chunk, 0)
            if kind == 0x102:
                name = self.pool.get(u32(chunk, u16(chunk, 2) + 4))
                attrs = []
                if depth == 0:
                    attrs.append('xmlns:android=' + quoteattr(ANDROID))
                for attr in self.attributes(chunk):
                    key = ("android:" if attr["ns"] == ANDROID else "") + attr["name"]
                    value, type_id = attr["value"], attr["type"]
                    if type_id == 1:
                        value = "@0x%08x" % value
                    elif type_id == 2:
                        value = "?0x%08x" % value
                    elif type_id == 0x12:
                        value = "true" if value else "false"
                    elif type_id != 3:
                        value = str(value)
                    attrs.append(key + "=" + quoteattr(str(value)))
                lines.append("  " * depth + "<" + name + (" " + " ".join(attrs) if attrs else "") + ">")
                depth += 1
            elif kind == 0x103:
                depth -= 1
                if depth < 0:
                    raise ValueError("Unbalanced end element")
                name = self.pool.get(u32(chunk, u16(chunk, 2) + 4))
                lines.append("  " * depth + "</" + name + ">")
        if depth:
            raise ValueError("Unclosed element")
        text = "\n".join(lines) + "\n"
        ET.fromstring(text)  # Validate element balance/name and escaped text.
        return text


def get_attr(attrs, name, namespace=ANDROID):
    matches = [a for a in attrs if a["name"] == name and a["ns"] == namespace]
    if len(matches) != 1:
        raise ValueError("Expected one attribute %s, got %d" % (name, len(matches)))
    return matches[0]


def native_nodes(doc, library, line):
    pool = doc.pool
    ns, tag = pool.add(ANDROID), pool.add("uses-native-library")
    name, required = pool.lookup["name"], pool.lookup["required"]
    # Android framework attr IDs are corroborated from original resource map.
    expected = {name: 0x01010003, required: 0x0101028E}
    for index, resource_id in expected.items():
        if doc.resource_map.get(index) != resource_id:
            raise ValueError("Original resource map lacks native-library attribute %s" % pool.get(index))
    lib = pool.add(library)
    attrs = []
    for index, raw, type_id, value in [(name, lib, 3, lib), (required, NO_INDEX, 0x12, 0)]:
        attrs.append((doc.resource_map[index], struct.pack("<3IHBBI", ns, index, raw, 8, 0, type_id, value)))
    attrs.sort(key=lambda item: item[0])
    start = chunk_header(0x102, 16, 36 + 20 * len(attrs)) + struct.pack("<2I", line, NO_INDEX)
    start += struct.pack("<2I6H", NO_INDEX, tag, 20, 20, len(attrs), 0, 0, 0)
    start += b"".join(a[1] for a in attrs)
    end = chunk_header(0x103, 16, 24) + struct.pack("<4I", line, NO_INDEX, NO_INDEX, tag)
    return [bytearray(start), bytearray(end)]


def patch_manifest(data, add_native=True):
    doc = AXML(data)
    original_strings = tuple(doc.pool.strings)
    original_elements = list(doc.elements())
    root = next((x for x in original_elements if x[1] == "manifest"), None)
    app = next((x for x in original_elements if x[1] == "application"), None)
    if root is None or app is None:
        raise ValueError("Manifest/application missing")
    old_package = get_attr(root[2], "package", None)["value"]
    if old_package != "com.sony.walkman.highresmediaplayer":
        raise ValueError("Unexpected source package: %r" % old_package)
    permission_map = {}
    for _, tag, attrs in original_elements:
        if tag == "permission":
            old = get_attr(attrs, "name")["value"]
            if old in permission_map:
                raise ValueError("Duplicate declared permission")
            permission_map[old] = NEW_PACKAGE + ".permission.P%d" % len(permission_map)
    changes, authorities, actions = [], {}, {}
    for index, tag, attrs in original_elements:
        for attr in attrs:
            old, new = attr["value"], None
            if index == root[0] and attr["name"] == "package" and attr["ns"] is None:
                new = NEW_PACKAGE
            elif index == root[0] and attr["name"] == "versionName" and attr["ns"] == ANDROID:
                new = NEW_VERSION
            elif index == app[0] and attr["name"] == "label" and attr["ns"] == ANDROID:
                new = NEW_LABEL
            elif tag == "provider" and attr["name"] == "authorities" and attr["ns"] == ANDROID:
                parts = old.split(";")
                new = ";".join(NEW_PACKAGE + p[len(old_package):]
                               if p == old_package or p.startswith(old_package + ".") else p for p in parts)
                if new != old:
                    authorities[old] = new
            elif (attr["ns"] == ANDROID and attr["name"] in
                  ("name", "permission", "readPermission", "writePermission") and
                  isinstance(old, str) and old in permission_map):
                new = permission_map[old]
            elif tag == "action" and attr["name"] == "name" and attr["ns"] == ANDROID:
                for old_prefix, new_prefix in (
                    ("com.sony.songpal.localplayer.mediadb.", NEW_PACKAGE + ".mediadb."),
                    ("com.sony.songpal.localplayer.playbackservice.", NEW_PACKAGE + ".playbackservice."),
                    ("com.sony.songpal.app.action.COMMAND", NEW_PACKAGE + ".action.COMMAND"),
                ):
                    if old.startswith(old_prefix):
                        new = new_prefix + old[len(old_prefix):]
                        actions[old] = new
                        break
            if new is not None and new != old:
                doc.replace_string(doc.chunks[index], attr, new)
                changes.append({"element": tag, "attribute": attr["name"], "old": old, "new": new})
    native_added = []
    if add_native:
        existing = {get_attr(attrs, "name")["value"] for _, tag, attrs in original_elements
                    if tag == "uses-native-library"}
        insertion = None
        for index, chunk in enumerate(doc.chunks):
            if u16(chunk, 0) == 0x103 and doc.pool.get(u32(chunk, u16(chunk, 2) + 4)) == "application":
                insertion = index
                break
        if insertion is None:
            raise ValueError("Application closing tag missing")
        chunks = []
        for library in NATIVE_LIBRARIES:
            if library not in existing:
                chunks.extend(native_nodes(doc, library, u32(doc.chunks[app[0]], 8)))
                native_added.append(library)
        doc.chunks[insertion:insertion] = chunks
    result = doc.build()
    verified = AXML(result)
    if tuple(verified.pool.strings[:len(original_strings)]) != original_strings:
        raise AssertionError("Original string indexes changed")
    ET.fromstring(verified.dump())
    nodes = list(verified.elements())
    new_root = next(n for n in nodes if n[1] == "manifest")
    new_app = next(n for n in nodes if n[1] == "application")
    assert get_attr(new_root[2], "package", None)["value"] == NEW_PACKAGE
    assert get_attr(new_root[2], "versionName")["value"] == NEW_VERSION
    assert get_attr(new_app[2], "label")["value"] == NEW_LABEL
    assert {get_attr(a, "name")["value"] for _, t, a in nodes if t == "permission"} == set(permission_map.values())
    for _, tag, attrs in nodes:
        for attr in attrs:
            if attr["ns"] == ANDROID and attr["name"] in ("permission", "readPermission", "writePermission", "name"):
                assert not isinstance(attr["value"], str) or attr["value"] not in permission_map
        if tag == "provider":
            assert get_attr(attrs, "authorities")["value"] not in authorities
        if tag == "action":
            assert get_attr(attrs, "name")["value"] not in actions
        if tag == "uses-native-library" and get_attr(attrs, "name")["value"] in native_added:
            assert get_attr(attrs, "required")["type"] == 0x12
            assert get_attr(attrs, "required")["value"] == 0
    # Classes, SDK and all unselected attributes are checked by position/value.
    old_nodes = [n for n in original_elements if n[1] != "uses-native-library"]
    new_nodes = [n for n in nodes if n[1] != "uses-native-library"]
    assert len(old_nodes) == len(new_nodes)
    for (index, tag, attrs), (_, new_tag, new_attrs) in zip(old_nodes, new_nodes):
        assert tag == new_tag and len(attrs) == len(new_attrs)
        selected = {(c["element"], c["attribute"], c["old"]) for c in changes}
        for before, after in zip(attrs, new_attrs):
            assert (before["ns"], before["name_index"]) == (after["ns"], after["name_index"])
            if (tag, before["name"], before["value"]) not in selected:
                assert (before["raw_index"], before["type"], before["data"]) == (after["raw_index"], after["type"], after["data"])
    metadata = {
        "source_sha256": hashlib.sha256(data).hexdigest(),
        "output_sha256": hashlib.sha256(result).hexdigest(),
        "old_package": old_package, "new_package": NEW_PACKAGE,
        "label": NEW_LABEL, "version_name": NEW_VERSION,
        "permission_map": permission_map, "authority_map": authorities, "action_map": actions,
        "action_prefix_map": {
            "com.sony.songpal.localplayer.mediadb.": NEW_PACKAGE + ".mediadb.",
            "com.sony.songpal.localplayer.playbackservice.": NEW_PACKAGE + ".playbackservice.",
            "com.sony.songpal.app.action.COMMAND": NEW_PACKAGE + ".action.COMMAND",
        },
        "native_libraries_added": native_added, "changes": changes,
        "original_string_count": len(original_strings), "output_string_count": len(verified.pool.strings),
        "self_checks": "passed: original strings/indexes, XML structure, identity, permissions, providers, native-library optional flags, unchanged attributes",
    }
    return result, metadata


def read_input(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return archive.read("AndroidManifest.xml")
    return Path(path).read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, nargs="?")
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--dump", action="store_true", help="Dump input as readable XML; do not patch")
    parser.add_argument("--dump-output", type=Path)
    parser.add_argument("--no-native-libraries", action="store_true")
    args = parser.parse_args()
    data = read_input(args.input)
    if args.dump:
        print(AXML(data).dump(), end="")
        return
    if args.output is None:
        parser.error("output is required when patching")
    result, metadata = patch_manifest(data, not args.no_native_libraries)
    args.output.write_bytes(result)
    metadata_path = args.metadata or args.output.with_suffix(args.output.suffix + ".json")
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.dump_output:
        args.dump_output.write_text(AXML(result).dump(), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "metadata": str(metadata_path), "sha256": metadata["output_sha256"], "self_checks": metadata["self_checks"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
