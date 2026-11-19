#!/usr/bin/env python3
"""Shared Android native ELF and package validation."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16 * 1024
ELF64_MACHINES = {62: "x86_64", 183: "AArch64"}
ELF64_ABIS = {"arm64-v8a", "x86_64"}


class ValidationError(ValueError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_elf_16k(data: bytes, label: str) -> list[str]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValidationError(f"{label}: expected ELF file")
    if data[4] != 2 or data[5] != 1 or data[6] != 1:
        raise ValidationError(f"{label}: expected current little-endian ELF64")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    version = struct.unpack_from("<I", data, 20)[0]
    phoff = struct.unpack_from("<Q", data, 32)[0]
    ehsize, phentsize, phnum = struct.unpack_from("<HHH", data, 52)
    if elf_type != 3 or machine not in ELF64_MACHINES or version != 1 or ehsize != 64:
        raise ValidationError(f"{label}: expected Android ELF64 shared object")
    if phoff < 64 or phentsize < 56 or phnum == 0 or phoff + phentsize * phnum > len(data):
        raise ValidationError(f"{label}: invalid program header table")

    results: list[str] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if struct.unpack_from("<I", data, offset)[0] != 1:
            continue
        file_offset, vaddr = struct.unpack_from("<QQ", data, offset + 8)
        file_size, memory_size, alignment = struct.unpack_from("<QQQ", data, offset + 32)
        valid = (
            file_offset + file_size <= len(data)
            and file_size <= memory_size
            and vaddr + memory_size <= 0xFFFFFFFFFFFFFFFF
            and alignment >= PAGE_SIZE
            and alignment & (alignment - 1) == 0
            and file_offset % PAGE_SIZE == vaddr % PAGE_SIZE
            and file_offset % alignment == vaddr % alignment
        )
        verdict = "OK" if valid else "FAIL"
        results.append(
            f"{label}: PT_LOAD {index} offset=0x{file_offset:x} "
            f"vaddr=0x{vaddr:x} align=0x{alignment:x} {verdict}"
        )
        if not valid:
            raise ValidationError("\n".join(results) + f"\n{label}: 16 KiB PT_LOAD validation failed")
    if not results:
        raise ValidationError(f"{label}: ELF has no PT_LOAD segments")
    return results


def native_entries(archive: zipfile.ZipFile, prefix: str) -> dict[str, bytes]:
    names = [name for name in archive.namelist() if name.startswith(prefix) and name.endswith(".so")]
    if len(names) != len(set(names)):
        raise ValidationError(f"{archive.filename}: duplicate native library entries")
    return {name.removeprefix(prefix): archive.read(name) for name in sorted(names)}


def check_locked_aar(aar: Path) -> dict[str, str]:
    with zipfile.ZipFile(aar) as archive:
        libraries = native_entries(archive, "jni/")
    if not libraries:
        raise ValidationError(f"{aar}: AAR has no jni/**/*.so entries")
    for name, data in libraries.items():
        abi = name.split("/", 1)[0]
        if abi in ELF64_ABIS:
            for line in check_elf_16k(data, f"{aar}!jni/{name}"):
                print(line)
    return {name: sha256_bytes(data) for name, data in libraries.items()}


def zip_data_offset(handle, info: zipfile.ZipInfo) -> int:
    handle.seek(info.header_offset)
    header = handle.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValidationError(f"invalid ZIP local header for {info.filename}")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def check_apk(apk: Path, lock_path: Path) -> None:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    expected = lock.get("nativeLibraries")
    if not isinstance(expected, dict) or not expected:
        raise ValidationError(f"{lock_path}: nativeLibraries must be a non-empty object")

    with apk.open("rb") as handle, zipfile.ZipFile(handle) as archive:
        libraries = native_entries(archive, "lib/")
        if not libraries:
            raise ValidationError(f"{apk}: APK has no lib/**/*.so entries")
        infos = {info.filename.removeprefix("lib/"): info for info in archive.infolist() if info.filename.startswith("lib/") and info.filename.endswith(".so")}
        for name, data in libraries.items():
            abi = name.split("/", 1)[0]
            info = infos[name]
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValidationError(f"{apk}!lib/{name}: native library is compressed")
            data_offset = zip_data_offset(handle, info)
            if data_offset % PAGE_SIZE != 0:
                raise ValidationError(f"{apk}!lib/{name}: ZIP data offset 0x{data_offset:x} is not 16 KiB aligned")
            if abi in ELF64_ABIS:
                for line in check_elf_16k(data, f"{apk}!lib/{name}"):
                    print(line)
            print(f"{apk}!lib/{name}: ZIP offset=0x{data_offset:x} stored OK")

        for name, digest in expected.items():
            actual = libraries.get(name)
            if actual is None:
                raise ValidationError(f"{apk}: locked SDK library missing: lib/{name}")
            actual_digest = sha256_bytes(actual)
            if actual_digest != digest:
                raise ValidationError(
                    f"{apk}!lib/{name}: SDK SHA-256 mismatch: expected {digest}, got {actual_digest}"
                )
            print(f"{apk}!lib/{name}: SDK_SHA256={actual_digest} OK")


def main() -> None:
    try:
        if len(sys.argv) == 3 and sys.argv[1] == "--aar":
            check_locked_aar(Path(sys.argv[2]))
            print("ANDROID_AAR_NATIVE_ALIGNMENT=OK")
            return
        if len(sys.argv) == 4 and sys.argv[1] == "--apk":
            check_apk(Path(sys.argv[2]), Path(sys.argv[3]))
            print("ANDROID_NATIVE_PACKAGING=OK")
            return
        print("usage: android-native-packaging.py --aar <AAR> | --apk <APK> <LOCK_JSON>", file=sys.stderr)
        raise SystemExit(2)
    except (OSError, KeyError, json.JSONDecodeError, zipfile.BadZipFile, ValidationError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
