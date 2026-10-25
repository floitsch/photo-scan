#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Replay a Luma Scan session ZIP with the same reconstruction core as Android.

Requires Python 3, Pillow, and a C++17 compiler. No server or GPU is used.
"""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
Image.MAX_IMAGE_PIXELS = 16000000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/replay")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    executable = output / "replay-native"
    sources = sorted(p for p in (ROOT / "app/src/main/cpp").glob("*.cpp") if p.name != "bridge.cpp")
    subprocess.run(["g++", "-std=c++17", "-O3", "-DNDEBUG",
                    "-I", str(ROOT / "app/src/main/cpp"),
                    *map(str, sources),
                    str(ROOT / "tools/replay.cpp"), "-lz", "-o", str(executable)], check=True)
    with tempfile.TemporaryDirectory(prefix="luma-replay-") as tmp:
        temporary = Path(tmp)
        with zipfile.ZipFile(args.session) as archive:
            # Never extract paths from an archive. Only decode expected, bounded frame files.
            entries = sorted((info for info in archive.infolist()
                              if re.fullmatch(r"frame-[0-9]+\.png", info.filename)),
                             key=lambda info: info.filename)
            if not entries or len(entries) > 24:
                raise ValueError("Expected 1–24 lossless frame PNGs in the archive root")
            for index, entry in enumerate(entries):
                if entry.file_size > 64000000:
                    raise ValueError("Source PNG exceeds the replay size limit")
                with Image.open(io.BytesIO(archive.read(entry))) as image:
                    if image.width * image.height > 16000000:
                        raise ValueError("Source image exceeds 16 megapixels")
                    image.convert("RGB").save(temporary / f"frame-{index:05d}.ppm")
        result = subprocess.run([str(executable), str(temporary), str(output / "scan")],
                                text=True, capture_output=True, check=True)
    print(result.stdout, end="")
    records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    (output / "report.json").write_text(json.dumps(records, indent=2) + "\n")
    path = output / "scan-reference.ppm"
    with Image.open(path) as image:
        image.save(output / "scan-reference.png")
    path.unlink()
    # Native output has independently enforced dimensions; sources above retain
    # the stricter input limit. Reading .size does not decode the large PNG.
    Image.MAX_IMAGE_PIXELS = 50400000
    with Image.open(output / "scan-reference.png") as reference, Image.open(output / "scan-fused.png") as fused:
        reference = reference.resize(fused.size, Image.Resampling.BILINEAR)
        reference.save(output / "scan-reference-bilinear.png")
    print(f"Replayed output and first-frame baseline: {output}")


if __name__ == "__main__":
    main()
