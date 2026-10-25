#!/usr/bin/env python3
"""Check APK ABI, ELF LOAD/ZIP alignment, and bundled attribution (stdlib only).

This is a packaging check, not a substitute for running on a 16-KiB device.
RELRO boundaries are reported separately because prebuilt dependencies may need
additional validation even when their LOAD segments have 16-KiB alignment.
"""

import argparse
from pathlib import Path
import struct
import zipfile

PAGE_SIZE = 16384
REQUIRED_NOTICES = {
    "app-LICENSE.txt", "ATTRIBUTION.md", "NeMo-Speech-LICENSE.txt",
    "NeMo-Speech-NOTICE.txt", "NeMo-Speech-THIRD-PARTY-NOTICES.md",
    "llama-cpp-LICENSE.txt", "SentencePiece-LICENSE.txt", "whisper-cpp-LICENSE.txt",
}


def check_apk(path: Path) -> None:
    with zipfile.ZipFile(path) as archive, path.open("rb") as apk:
        names = set(archive.namelist())
        missing = {"assets/licenses/" + name for name in REQUIRED_NOTICES} - names
        if missing:
            raise ValueError(f"missing notices: {sorted(missing)}")
        if any(name.endswith(".gguf") for name in names):
            raise ValueError("model weights must not be bundled in the APK")
        libraries = [item for item in archive.infolist() if item.filename.endswith(".so")]
        if not libraries:
            raise ValueError("APK contains no native libraries")
        for item in libraries:
            if not item.filename.startswith("lib/arm64-v8a/"):
                raise ValueError(f"unexpected ABI: {item.filename}")
            data = archive.read(item)
            if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
                raise ValueError(f"expected little-endian AArch64 ELF64: {item.filename}")
            phoff = struct.unpack_from("<Q", data, 32)[0]
            entry_size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                kind, _, offset, address, _, _, memory_size, alignment = struct.unpack_from(
                    "<IIQQQQQQ", data, phoff + entry_size * index
                )
                if kind == 1 and (alignment < PAGE_SIZE or (address - offset) % PAGE_SIZE):
                    raise ValueError(f"ELF LOAD segment lacks 16-KiB alignment: {item.filename}")
                if kind == 0x6474E552 and (address + memory_size) % PAGE_SIZE:
                    print(f"  NOTE: RELRO end is not 16-KiB aligned: {item.filename}; validate on a 16-KiB device")
            if item.compress_type == zipfile.ZIP_STORED:
                apk.seek(item.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", apk.read(4))
                data_offset = item.header_offset + 30 + name_size + extra_size
                if data_offset % PAGE_SIZE:
                    raise ValueError(f"uncompressed library lacks 16-KiB ZIP alignment: {item.filename}")
        print(f"{path}: {len(libraries)} ARM64 libraries; LOAD/ZIP alignment and notices passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.apks:
        check_apk(path)


if __name__ == "__main__":
    main()
