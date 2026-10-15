"""Reject packaged Android signature stubs; inspect DEX definitions, not references."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def definitions(dex):
    def integer(at):
        return struct.unpack_from("<I", dex, at)[0]

    strings_size, strings_at = integer(56), integer(60)
    types_at = integer(68)
    classes_size, classes_at = integer(96), integer(100)
    strings = []
    for i in range(strings_size):
        start = integer(strings_at + i * 4)
        while dex[start] & 0x80:
            start += 1
        start += 1  # skip ULEB128 UTF-16 length
        strings.append(dex[start:dex.index(b"\0", start)].decode("utf-8", errors="replace"))
    for i in range(classes_size):
        type_index = integer(classes_at + i * 32)
        yield strings[integer(types_at + type_index * 4)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        classes = {name for file in apk.namelist() if file.endswith(".dex") for name in definitions(apk.read(file))}
    forbidden = sorted(name for name in classes if name.startswith("Landroid/window/WindowInfosListener") or
                       name == "Landroid/view/InputWindowHandle;")
    required = {"Lcom/lateral/privileged/ProtectedDisplayOutput;", "Lcom/lateral/beast/ProtectedTileSurface;",
                "Lcom/lateral/privileged/ProtectedHostOverlay;", "Lcom/lateral/beast/ProtectedHostSurface;"}
    report = {"apk": str(args.apk.resolve()), "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "definedClasses": len(classes), "packagedPlatformStubs": forbidden,
              "missingRendererClasses": sorted(required-classes)}
    report["passed"] = not forbidden and not report["missingRendererClasses"]
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
