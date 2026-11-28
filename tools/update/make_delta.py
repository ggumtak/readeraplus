"""Emit a patch for the exact signed APK, reusing unchanged compressed ZIP payloads."""
import argparse
import io
import struct
from pathlib import Path
from zipfile import ZipFile


def payloads(apk):
    with ZipFile(io.BytesIO(apk)) as archive:
        for entry in sorted(archive.infolist(), key=lambda item: item.header_offset):
            if entry.compress_size < 256:
                continue
            header = entry.header_offset
            name_len, extra_len = struct.unpack_from("<HH", apk, header + 26)
            start = header + 30 + name_len + extra_len
            yield entry.filename, start, entry.compress_size


def make_patch(old, new):
    # Headers, alignment padding, signing block and directory are copied literally from the target.
    sources = {name: (start, size) for name, start, size in payloads(old)}
    patch = io.BytesIO()
    patch.write(b"RPD1")
    cursor = 0

    def literal(end):
        if end > cursor:
            patch.write(struct.pack(">i", end - cursor))
            patch.write(memoryview(new)[cursor:end])

    for name, start, size in payloads(new):
        source = sources.get(name)
        if source is None or source[1] != size:
            continue
        offset = source[0]
        if memoryview(old)[offset:offset + size] != memoryview(new)[start:start + size]:
            continue
        literal(start)
        patch.write(struct.pack(">ii", -size, offset))
        cursor = start + size
    literal(len(new))
    return patch.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("previous", "target", "output"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    new = args.target.read_bytes()
    delta = make_patch(args.previous.read_bytes(), new)
    # If most bytes changed, offer the regular update instead.
    if len(delta) < len(new) * 0.8:
        args.output.write_bytes(delta)
