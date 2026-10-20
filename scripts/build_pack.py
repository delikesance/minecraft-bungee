#!/usr/bin/env python3
"""Packs resource-pack/pack into a reproducible ZIP and writes its SHA-1.

    python3 scripts/build_pack.py [output-dir]    # default: resource-pack/dist

Timestamps, ordering and permissions are fixed so the same sources always
yield the same SHA-1 (clients cache packs by hash).
"""
import hashlib, pathlib, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "resource-pack" / "pack"
NAME = "vampyuyu-menus"

def build(out_dir):
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"{NAME}.zip"
    files = sorted(p for p in SOURCE.rglob("*") if p.is_file())
    if not any(p.name == "pack.mcmeta" for p in files):
        sys.exit("pack.mcmeta missing")
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(SOURCE).as_posix(), (2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, path.read_bytes())
    digest = hashlib.sha1(target.read_bytes()).hexdigest()
    (out / f"{NAME}.sha1").write_text(digest + "\n")
    return target, digest

if __name__ == "__main__":
    path, digest = build(sys.argv[1] if len(sys.argv) > 1 else ROOT / "resource-pack" / "dist")
    print(f"{path} {digest}")
