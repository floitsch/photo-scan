#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Run deterministic image-reconstruction tests on the host."""
import argparse
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sanitize", action="store_true")
parser.add_argument("--output", type=Path, help="Write synthetic comparison images as PNG and PPM files.")
parser.add_argument("--full-resolution", action="store_true",
                    help="Also exercise 24 x 12.6MP sources, 50.4MP streaming, and full-resolution registration.")
args = parser.parse_args()
output = root / "build/native-tests"
output.mkdir(parents=True, exist_ok=True)
flags = ["-O1", "-g", "-fsanitize=address,undefined", "-fno-omit-frame-pointer"] if args.sanitize else ["-O2"]
binary = output / ("fusion-sanitized" if args.sanitize else "fusion-test")
sources = sorted(p for p in (root / "app/src/main/cpp").glob("*.cpp") if p.name != "bridge.cpp")
subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", *flags,
                "-I", str(root / "app/src/main/cpp"),
                *map(str, sources),
                str(root / "tests/fusion_test.cpp"), "-lz", "-o", str(binary)], check=True)
command = [str(binary)]
if args.output:
    command.extend(["--output", str(args.output)])
if args.full_resolution:
    command.append("--full-resolution")
subprocess.run(command, check=True)
