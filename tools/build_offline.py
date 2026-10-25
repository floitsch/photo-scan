#!/usr/bin/env python3
# Copyright (C) 2026 Toit contributors.
"""Build a debug APK with the installed SDK/NDK and cached Kotlin compiler.

This alternative to Gradle requires no network, daemon, or global cache writes.
Normal development can use ./gradlew assembleDebug.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    print("+", " ".join(str(x) for x in args), flush=True)
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))))
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64", "x86", "both"], default="arm64-v8a")
    parser.add_argument("--output", type=Path, help="Optional APK destination (e.g. an emulator-only build).")
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    sdk_tools = sdk / "build-tools/36.0.0"
    android_jar = sdk / "platforms/android-36/android.jar"
    ndk = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
    work = ROOT / ".local-build/direct"
    if work.exists():
        shutil.rmtree(work)
    for name in ("classes", "generated", "dex", "native"):
        (work / name).mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    java_home = Path(env.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk"))
    if not (java_home / "bin/java").exists():
        raise SystemExit("Set JAVA_HOME to an installed JDK 17 or newer.")
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")

    def cached(group, artifact, version):
        candidates = sorted((args.gradle_cache / group / artifact / version).glob("*/*.jar"))
        if not candidates:
            raise SystemExit(f"Missing cached dependency: {group}:{artifact}:{version}. Use Gradle with network access first.")
        return candidates[0]

    stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21")
    compiler_jars = [
        cached("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        stdlib,
        cached("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        cached("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        cached("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
        cached("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        cached("org.jetbrains", "annotations", "13.0"),
    ]
    run([sdk_tools / "aapt2", "compile", "--dir", ROOT / "app/src/main/res", "-o", work / "resources.zip"], env=env)
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
    element = manifest.getroot()
    element.set("package", "org.toit.lumascan")
    android = "{http://schemas.android.com/apk/res/android}"
    element.set(android + "versionCode", "4")
    element.set(android + "versionName", "0.4")
    # Gradle normally injects SDK declarations and the debug flag.
    application = element.find("application")
    if application is None:
        raise SystemExit("Android manifest has no application element")
    # Android parses SDK-dependent application defaults as it encounters application.
    # Keep the SDK declaration ahead of it, as Gradle's manifest merger does.
    uses_sdk = element.find("uses-sdk")
    if uses_sdk is not None:
        element.remove(uses_sdk)
    else:
        uses_sdk = ET.Element("uses-sdk")
    uses_sdk.set(android + "minSdkVersion", "29")
    uses_sdk.set(android + "targetSdkVersion", "35")
    element.insert(list(element).index(application), uses_sdk)
    application.set(android + "debuggable", "true")
    manifest.write(work / "AndroidManifest.xml", encoding="utf-8", xml_declaration=True)
    run([sdk_tools / "aapt2", "link", "-o", work / "unsigned.apk", "-I", android_jar,
         "--manifest", work / "AndroidManifest.xml", "--java", work / "generated",
         "--auto-add-overlay", work / "resources.zip"], env=env)
    java_sources = sorted((work / "generated").rglob("*.java"))
    if java_sources:
        run([java_home / "bin/javac", "--release", "17", "-d", work / "classes", *java_sources], env=env)
    kotlin_sources = sorted((ROOT / "app/src/main/java").rglob("*.kt"))
    if not kotlin_sources:
        raise SystemExit("No Kotlin sources found")
    run([java_home / "bin/java", "-Xmx2g", "-cp", os.pathsep.join(map(str, compiler_jars)),
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
         "-jvm-target", "17", "-classpath", os.pathsep.join(map(str, [android_jar, stdlib, work / "classes"])),
         "-d", work / "classes", *kotlin_sources], env=env)
    run([java_home / "bin/jar", "cf", work / "app.jar", "-C", work / "classes", "."], env=env)
    run([sdk_tools / "d8", "--lib", android_jar, "--min-api", "29",
         "--output", work / "dex", work / "app.jar", stdlib], env=env)
    targets = {"arm64-v8a": "aarch64-linux-android29", "x86_64": "x86_64-linux-android29", "x86": "i686-linux-android29"}
    abis = ["arm64-v8a", "x86_64"] if args.abi == "both" else [args.abi]
    native_sources = sorted((ROOT / "app/src/main/cpp").glob("*.cpp"))
    for abi in abis:
        destination = work / "native" / abi
        destination.mkdir(parents=True)
        run([ndk / "clang++", "--target=" + targets[abi], "-std=c++17", "-O3", "-DNDEBUG",
             "-fPIC", "-shared", "-static-libstdc++", "-Wall", "-Wextra",
             "-Wl,-z,max-page-size=16384", *native_sources,
             "-landroid", "-ljnigraphics", "-llog", "-lz", "-o", destination / "liblumascan.so"], env=env)
    with zipfile.ZipFile(work / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for dex in sorted((work / "dex").glob("*.dex")):
            apk.write(dex, dex.name)
        for abi in abis:
            apk.write(work / "native" / abi / "liblumascan.so", "lib/" + abi + "/liblumascan.so")
    run([sdk_tools / "zipalign", "-P", "16", "-f", "4", work / "unsigned.apk", work / "aligned.apk"], env=env)
    keystore = ROOT / ".local-build/debug.keystore"
    if not keystore.exists():
        run([java_home / "bin/keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
             "-alias", "androiddebugkey", "-keypass", "android", "-dname", "CN=Android Debug,O=Android,C=US",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000"], env=env)
    output = (args.output or ROOT / "build/direct/luma-scan-debug.apk").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run([sdk_tools / "apksigner", "sign", "--ks", keystore, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", output, work / "aligned.apk"], env=env)
    run([sdk_tools / "apksigner", "verify", "--verbose", output], env=env)
    run([sdk_tools / "zipalign", "-c", "-P", "16", "4", output], env=env)
    print(f"\nBuilt {output} ({output.stat().st_size / 1048576:.1f} MiB)")


if __name__ == "__main__":
    main()
