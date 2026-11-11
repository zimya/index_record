"""Reproducible wire-preserving projection of the first LSPosed captures.

Run from the repository root:
  python3 app/src/test/resources/tieba/project_captures.py
Only test resources are written. Public post IDs, body, mentions, media URLs and
selected display names are unchanged. Authentication, user profile, request,
advertising and telemetry fields are excluded by a recursive allowlist.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
DEST = Path(__file__).resolve().parent
SOURCE = ROOT / "research/tieba/lsposed"


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def fields(data):
    pos = 0

    def read_varint():
        nonlocal pos
        value = 0
        for index in range(10):
            if pos >= len(data):
                raise ValueError("truncated varint")
            byte = data[pos]
            pos += 1
            if index == 9 and byte > 1:
                raise ValueError("varint overflow")
            value |= (byte & 127) << (7 * index)
            if byte < 128:
                return value
        raise ValueError("varint overflow")

    while pos < len(data):
        start = pos
        key = read_varint()
        tag, wire = key >> 3, key & 7
        if tag == 0:
            raise ValueError("invalid tag")
        if wire == 0:
            value = read_varint()
        elif wire in (1, 2, 5):
            size = read_varint() if wire == 2 else (8 if wire == 1 else 4)
            if size > len(data) - pos:
                raise ValueError("truncated field")
            value = data[pos:pos + size]
            pos += size
        else:
            raise ValueError("unsupported wire type in captures")
        yield tag, wire, value, data[start:pos]


def project(data, schema):
    out = bytearray()
    for tag, wire, value, raw in fields(data):
        if tag not in schema:
            continue
        child = schema[tag]
        if child is None:
            out.extend(raw)
        else:
            assert wire == 2
            nested = project(value, child)
            out.extend(varint((tag << 3) | 2) + varint(len(nested)) + nested)
    return bytes(out)


def scalars(*tags):
    return dict.fromkeys(tags)


USER = scalars(2, 4)
CONTENT = scalars(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 25, 27, 31, 32, 34, 35, 36)
VIDEO = scalars(2, 3, 6, 11)
SUB = {**scalars(1, 3, 4, 5, 6), 2: CONTENT, 7: USER, 9: scalars(1)}
POST = {**scalars(1, 2, 3, 4, 6, 13, 19, 40, 46), 5: CONTENT,
        15: {1: None, 2: SUB}, 23: USER, 29: VIDEO, 37: scalars(1)}
THREAD = {**scalars(1, 2, 3, 4, 5, 20, 27, 28, 40, 45, 56, 190),
          18: USER, 79: VIDEO, 142: CONTENT, 155: scalars(1, 2)}
PAGE = scalars(1, 2, 3, 4, 5, 6, 7)
LIST_DATA = {1: THREAD, 2: scalars(1, 2), 5: USER, 6: POST, 7: POST, 8: PAGE}
PAGE_DATA = {2: scalars(1, 2), 3: PAGE, 6: POST, 8: THREAD, 13: USER, 38: POST, 97: POST}
# Keep only the observed avatar URL path, never user navigation/profile payloads.
FLOOR_USER = {**USER, 5: None, 155: {1: {1: scalars(1)}}}
FLOOR_SUB = {**SUB, 7: FLOOR_USER}
FLOOR_POST = {**POST, 23: FLOOR_USER, 15: {1: None, 2: FLOOR_SUB}}
FLOOR_DATA = {1: PAGE, 3: FLOOR_POST, 4: FLOOR_SUB,
              5: {**THREAD, 18: FLOOR_USER}, 6: scalars(1, 2), 13: None}
CAPTURES = [
    ("1794417060889_19771_127037029400026", "page-empty.bin", PAGE_DATA),
    ("1794417075762_19771_127051900145646", "list-149242.bin", LIST_DATA),
    ("1794417075778_19771_127051915726844", "list-156592.bin", LIST_DATA),
    ("1794417546508_19771_127522652130362", "floor-41963.bin", FLOOR_DATA),
]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    manifest = []
    for stem, name, schema in CAPTURES:
        original = (SOURCE / (stem + ".bin")).read_bytes()
        projected = project(original, {1: scalars(1, 2, 3), 2: schema})
        (DEST / name).write_bytes(projected)
        manifest.append({"source": stem + ".bin", "fixture": name,
                         "url": (SOURCE / (stem + ".meta")).read_text().splitlines()[0],
                         "original_bytes": len(original), "original_sha256": digest(original),
                         "fixture_bytes": len(projected), "fixture_sha256": digest(projected)})
    (DEST / "binary-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    for row in manifest:
        print(row)


if __name__ == "__main__":
    main()
