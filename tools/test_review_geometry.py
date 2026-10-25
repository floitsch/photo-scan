#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Run the production review geometry on the JVM; no emulator or device is needed."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"


def cached(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))


def main():
    artifacts = [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
    ]
    compiler = os.pathsep.join(str(cached(*entry)) for entry in artifacts)
    stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    android = sdk / "platforms/android-36/android.jar"
    java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk"))
    java = java_home / "bin/java"
    with tempfile.TemporaryDirectory(prefix="lumascan-review-") as directory:
        classpath = os.pathsep.join(map(str, [stdlib, android]))
        subprocess.run([str(java), "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                        "-d", directory, str(ROOT / "app/src/main/java/org/toit/lumascan/CropView.kt"),
                        str(ROOT / "tests/review_geometry_test.kt")], check=True)
        subprocess.run([str(java), "-cp", os.pathsep.join([directory, str(stdlib)]),
                        "org.toit.lumascan.Review_geometry_testKt"], check=True)


if __name__ == "__main__":
    main()
