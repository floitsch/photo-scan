# Luma Scan

## Status: archived experiment

**This project is archived and is no longer maintained. We did not really achieve
the goals we set out for it, which is why development has stopped.**

The goal was a smooth, free-movement Android scanner that could faithfully recover
more detail from multiple views, remove reflections, and automatically flatten a
photographed print. The prototype made progress, but did not deliver that
combination reliably enough in real use.

The last version, **0.4**, was installed on a Pixel 10. Earlier device testing
improved from accepting 1 of 27 observations to 24 of 31, but tracking still lost
position, card borders remained soft, and focus and automatic boundaries needed
work. Reflection removal and a convincing real-world super-resolution advantage
were not established. Version 0.4 added further focus and tracking fixes and passed
host and emulator tests, but its live capture quality was not verified in a new
physical scan. Larger output dimensions alone are not evidence of more detail.

The source and development notes are preserved for reference and experimentation.
The implementation and build instructions below describe the final prototype;
they are not a commitment to further development or production readiness.

## Overview

An offline Android scanner for printed photographs and other mostly flat subjects.
Move naturally over the picture: the app accepts useful observations, aligns them
to the first view, and builds a combined image. There are no required capture points.

The first implementation targets a Pixel 10. It is an **experimental, faithful
reconstruction baseline**, not a claim of state-of-the-art scan quality. It has no
generative model and no network permission.

## What is implemented

- Camera2 preview and individually scheduled YUV observations, with actual stream
  dimensions and fallback reasons recorded in camera metadata.
- A rear-camera/lens selector based on advertised camera capabilities, and an
  uncropped, fit-center viewfinder with matching preview/capture aspect ratios.
- **Detail** capture selects supported sources up to 12.6MP; **Quick** selects up to
  2.1MP. Both accept at most 24 observations. Unsupported Detail stream combinations
  fall back to Quick dimensions and report the reason.
- Automatic exposure/white-balance locking when supported and converged.
- Continuous autofocus during movement, a **Refocus** control, and advertised
  lens close-focus hints; only focused, settled observations are admitted.
- Native feature tracking, RANSAC planar alignment, blur and duplicate rejection.
- Progressive combined preview and final 2× sample-grid reconstruction in linear light,
  streaming up to 50.4MP to PNG without a full-resolution output bitmap.
- Temporal consensus to reject observations inconsistent with the other views,
  including moving highlights and shadows.
- Boundary proposals for dark or bright rectangular subjects, editable four-corner
  perspective correction, and selectable output proportions. Review supports pinch
  zoom, panning, double-tap zoom, and bounded detail tiles from the saved full image.
- Optional neutral-point white balance and full-resolution tiled export, preserving
  the original reconstruction.
- Comparison against the first and sharpest accepted source observations.
- PNG export to the Android gallery; saved sessions with lossless source PNGs,
  capture metadata, transforms, replay, and ZIP export.
- Optional full-resolution DNG archival where the camera supports a compatible RAW
  stream. **RAW is supplemental; this version reconstructs from processed YUV.**

## Build and install

Requirements: JDK 17+, Android SDK 36, NDK `28.2.13676358`, CMake `3.22.1`.
Set `ANDROID_HOME`, or add `sdk.dir=…` in an untracked `local.properties`.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For this workstation, an alternative build uses only the installed SDK/NDK and
cached Kotlin artifacts. It does not need Gradle's daemon or network access:

```sh
python tools/build_offline.py --abi both
adb install -r build/direct/luma-scan-debug.apk
```

The APK is debug-signed and built for ARM64 and x86-64. The direct build stores its
APK at `build/direct/luma-scan-debug.apk` and debug key under `.local-build/`.
Use `--output` to choose another APK path. Gradle uses its conventional Android debug key,
so switching build methods can require uninstalling the previously installed APK.
Uninstalling deletes app-private sessions; export any sessions you want to keep first.

## Scan

1. Grant camera access. Choose a camera/lens and **Detail capture** or **Quick capture**. Detail needs
   substantial free storage; allow roughly 1GB per scan and more for source archives
   and output files. Fill most of the preview with the print and let focus settle.
2. Tap **Start scanning**. Move gently in any direction, keeping the print visible.
   Slight lateral movements provide different samples; viewpoint changes can move
   glare away from a region. Focus remains active as distance changes. If a lens
   cannot focus, move farther away or use **Refocus**; telephoto lenses can have a
   much longer closest-focus distance. The selector shows an approximation only
   when the camera advertises calibrated distance units.
3. Tap **Finish scan**, or let the 24-observation limit finish it. Review the result
   against the source images.
4. Check the corner suggestion and drag each handle to the intended edge. Pinch or
   double-tap to zoom, drag the image to pan, and use **Fit view** to see it all.
   **Reset corners** restores the automatic proposal. Choose
   a known aspect ratio if available; **From corners** is only a geometric estimate.
5. Optionally pick a region known to be neutral gray to adjust white balance.
   **Reset color** restores the original color.
6. **Save PNG** writes the perspective-corrected image to `Pictures/Luma Scan`.
   **Export session** writes a ZIP to a location you choose.

Use **Your scans** to reopen or replay saved observations. The app retains source
frames in private storage for reproducibility; sessions can be large. Accepted
full-resolution RGB frames spill to a disposable disk cache. Native previews are
limited to 640px; review starts with thumbnails of at most 1600px per side and
2MP, then decodes bounded detail tiles when zoomed. Final fusion and
perspective/color export use the full stored pixels.

Saved source PNGs and `result.png` retain sensor orientation. The manifest records
`resultOrientation: "sensor"` and `rotationDegrees`; the app applies that rotation
for review. `edited.png` and the gallery export apply the chosen orientation,
corners, proportions, and color gains. Session ZIPs retain these files and metadata.

New attempts retain bounded diagnostic events, rejection reasons, camera metadata,
and corner measurements. Use **Your scans → session → Export diagnostics**, or
choose **Export diagnostics** from the review screen's **Export session** menu,
to share a compact ZIP. It includes small photographic thumbnails rather than
full PNG/DNG files: up to eight rejected views plus reference/corner context share
a twelve-image, 4MiB limit. See [diagnostic contents and limits](docs/diagnostics.md).

## Validation and algorithm development

Diagnostic storage caps, concurrency, restart recovery, and failure containment
can be checked with the cached Kotlin compiler and JSON dependency:

```sh
python3 tools/test_diagnostics.py
```

The native test suite runs independently of Android:

```sh
python tools/test_native.py
ASAN_OPTIONS=detect_leaks=0 python tools/test_native.py --sanitize
python tools/test_native.py --full-resolution
python tools/benchmark_quality.py
```

An optional desktop replay tool uses exactly the same C++ reconstruction core.
It accepts an exported session ZIP, requires Pillow and a C++17 compiler, and
produces the combined result, first-frame bilinear baseline, and a JSON report:

```sh
python tools/replay.py /path/to/session.zip --output build/replay
```

This tool is for development and comparisons; the Android app does not require a
desktop or server. See [the architecture and research notes](docs/architecture.md),
[validation results](docs/validation.md), and
[the device validation procedure](docs/device-validation.md).

## Current limits

- Detail capture uses the supported processed YUV size up to 12.6MP, rather than
  the sensor's largest possible RAW resolution. Quick uses up to 2.1MP. Output
  dimensions are doubled; **this does not imply twice the resolved detail**. Real
  gains depend on optics, blur, alignment, and sample diversity.
- Host tests measured about 84MiB peak RSS for the disk-backed 24-source storage and
  50.4MP streaming case, and 197MiB including two-frame 12.6MP registration. These
  are host measurements from staged test cases, not Android process memory measurements.
  A real Pixel 10 scan exposed tracking and boundary failures; see
  [device findings](docs/device-validation.md#status). The revised pipeline needs
  another physical capture to establish quality and latency.
- The reconstruction covers the first frame. It does not yet stitch a large
  close-up mosaic or handle major viewpoint changes, curved pages, or 3D objects.
- Temporal rejection needs multiple agreeing observations. Persistent glare,
  glare covering a region in most views, textured reflections, and clipped content
  can remain. Bright original image content must not be mistaken for recoverable glare.
- Automatic boundaries require a supported rectangular edge. Similar colors,
  reflections, nested frames, and partly off-screen subjects can require manual
  correction; arbitrary flat objects have no inherently correct rectangular crop.
- The camera's processing already includes demosaicing, denoising and tone mapping.
  The current YUV conversion assumes conventional limited-range BT.601, followed
  by an approximate sRGB inverse transfer for fusion. It is not calibrated RAW color.
- There is no learned RAW burst model, lens calibration, rolling-shutter correction,
  or proof of performance against PhotoScan yet.

Work left unfinished includes a repeatable Pixel 10 comparison using real matte
and glossy prints, a RAW reconstruction path, and benchmarked alternatives.
