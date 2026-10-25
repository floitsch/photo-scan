#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Run the independent optical-PSF, sampling, and noise reconstruction benchmark."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
output = root / "build/quality-benchmark"
output.mkdir(parents=True, exist_ok=True)
sources = sorted(p for p in (root / "app/src/main/cpp").glob("*.cpp") if p.name != "bridge.cpp")
binary = output / "quality-benchmark"
subprocess.run(["g++", "-std=c++17", "-O2", "-Wall", "-Wextra",
                "-I", str(root / "app/src/main/cpp"), *map(str, sources),
                str(root / "tests/quality_benchmark.cpp"), "-lz", "-o", str(binary)], check=True)
result = subprocess.run([str(binary), str(output)], text=True, capture_output=True)
print(result.stdout, end="")
print(result.stderr, end="")
(output / "metrics.json").write_text(result.stdout)
result.check_returncode()
