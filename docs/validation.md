# Validation results — 2026-10-20

## Passed in this workspace

- Standard Gradle `assembleDebug`, plus the direct offline build with Kotlin and
  native NDK compilation for `arm64-v8a` and `x86_64`.
- Standard Gradle `lintDebug`: zero errors. Remaining warnings concern English-only
  localization, portrait orientation, SDK/dependency update suggestions, and storage.
- APK resource linking, DEX generation, signing verification, and ZIP alignment.
  Native libraries use 16KB-compatible segment alignment.
- Native deterministic tests: homography fitting, translation, perspective,
  coarse-to-fine subpixel tracking, duplicate/blur/unrelated-frame rejection,
  moving bright corruption, output sizing, and invalid input/transform rejection.
- Exact resident-versus-disk reconstruction and preview equality; PNG chunk CRCs,
  lossless decoded pixel equality, raster round trips, incomplete/out-of-order strip
  rejection, and identity/perspective/90° rotation/white-balance exports. Reset
  removes scanner spills while preserving user results and unrelated files.
- AddressSanitizer and UndefinedBehaviorSanitizer on the native tests.
  LeakSanitizer cannot operate under this environment's tracing restrictions;
  it was disabled with `ASAN_OPTIONS=detect_leaks=0`. No leak-check result is claimed.
- End-to-end host replay of a nine-frame synthetic session ZIP: all nine source
  observations accepted; 384×288 inputs produced a 768×576 reconstruction,
  reference PNG, bilinear baseline, and JSON report. The recorded run reported
  full multi-frame support and 0.392 seconds of processing; this is a host fixture
  measurement, not an Android latency estimate.

## Android emulator validation

The following runtime checks passed on an Android 11 / API 30 x86 AVD. This is
an emulator result, not a physical Pixel 10 result or a real-print quality test.

- Camera permission and an upright TextureView preview after the orientation fix.
  Detail selected 1856×1392 YUV observations with locked 3A and matched capture
  timestamps; reconstruction saved a sensor-oriented 3712×2784 PNG and displayed
  it as 2784×3712. Duplicate observations were rejected.
- Android JNI replay accepted the nine-frame synthetic fixture, saving a 768×576
  sensor-oriented result with 90° display rotation to 576×768. Independently decoded
  RGB pixels exactly matched the host reconstruction (per-channel mean absolute
  difference: zero).
- Gallery Save PNG produced a 2617×3489 image with the reset corner margins of 3%.
  Independent Pillow decoding succeeded, and gallery bytes exactly matched
  `edited.png`. SAF ZIP export passed CRC and expected-file checks.
- Saved scans reopened after APK replacement through the bounded region-import
  path. Custom perspective corners and a 1:1 ratio produced 2315×2315 output.
  Neutral-point color selection rejected a clipped region, accepted a midtone,
  and reset successfully. Saved `edit.json` recorded aspect ratio 1 and nonidentity
  RGB gains `[0.6247111, 1.2458957, 1.65]`.
- Background/foreground during save completed successfully. Switching Detail to
  Quick reopened the camera with 640×480 observations. No AndroidRuntime or native
  fatal logs appeared during these checks.
- Independently parsed the archived emulator DNG: version 1.4, 1856×1392,
  uncompressed 16-bit data, and 2×2 RGGB CFA `(0, 1, 1, 2)`. Strip offsets and
  lengths covered the payload, and every pixel decoded, spanning values 64–4179.
  This verifies the emulator archive structure and payload, without establishing
  real-camera color or demosaicing quality.

These checks cover the tested emulator flows. Camera HAL behavior, physical lens
selection, image quality, latency, thermal behavior, and process memory on the
Pixel 10 remain unverified; see [the physical-device procedure](device-validation.md).

## Deterministic reconstruction checks

| Check | Result |
| --- | ---: |
| Estimated center error for a 0.45, 0.65 pixel shift at 1024×768 | 0.0451 input pixels |
| Same shift at 4200×3000, estimated registration | 0.0233 input pixels |
| Ideal detail target, single-frame bilinear MSE | 0.00454862 |
| Same target, 16 known subpixel phases, fused MSE | 0.000151358 |
| Moving bright-band target, fused MSE | 0.00000483823 |
| Bright-band observations rejected | 19.2% |

Here MSE is measured against the known analytic grayscale target on normalized
encoded pixel values, excluding boundary pixels. Fusion itself operates in
approximate linear light. These ideal point samples do not model a camera PSF,
Bayer processing, lens distortion, exposure drift, or realistic sensor noise.

## Optical sampling and noise benchmark

A separate image-formation benchmark uses RGB channels, a Gaussian optical PSF
with sigma 0.20 input pixels, a one-pixel box aperture, signal-dependent shot/read
noise, and 16 known quarter-pixel phases. It includes target frequencies above a
single frame's Nyquist limit and a moving bright contamination case. All outputs
are compared at 512×384, with boundary pixels excluded. The 1× reconstruction is
enlarged in linear light for comparison at the same display size.

| Reconstruction | Linear RGB MSE |
| --- | ---: |
| Single noisy observation, bilinear enlargement | 0.00143578 |
| Aligned multi-frame 1× reconstruction, enlarged | 0.00190006 |
| 2× multi-frame reconstruction | 0.0000684453 |
| 2× reconstruction with moving glare | 0.0000846956 |

The 2× reconstruction beats both baselines in this fixture. The 1× baseline has
higher error than the single-frame enlargement in this case. This benchmark uses
known transforms and simulated image formation; it does not establish the same
improvement with estimated poses, a phone camera pipeline, or real glossy prints.
Neither benchmark measures Pixel 10 optical resolution or ranks other scanning apps.

## Full-resolution storage and memory checks

The optional full-resolution native test exercises actual raster files and verifies
the full output dimensions and selected output rows without decoding the entire
50.4MP PNG into a resident test image.

| Host test case | Measured peak process RSS |
| --- | ---: |
| 24 disk-backed 4200×3000 sources, bounded preview/latest-frame reload; then a separate one-source 8400×6000 streamed output in the same process | 83.9MiB |
| Above tests plus estimated registration of two 4200×3000 textured sources | 197.0MiB |

These are cumulative high-water measurements from the latest host C++ test run;
values vary with the host allocator and environment. The 24-source
storage case uses known poses; the 50.4MP output case uses one source, so this is
not a measured 24-frame full-resolution fusion runtime. The registration case
includes full-resolution reference/current grayscale processing. These results
are **not Android process memory measurements**: camera buffers, Android bitmaps,
UI allocations, codecs, GPU resources, and device-specific overhead differ.

Full-resolution source RGB is spilled to disk. Native previews are at most 640px;
Android review images are bounded to 1600px per side and 2MP. Final reconstruction
and export stream tiles/rows to disk, and reopening uses bounded region decoding.
These implementation bounds still need verification on the physical target.

## Reproduce

```sh
python tools/test_native.py --output build/native-tests/artifacts
ASAN_OPTIONS=detect_leaks=0 python tools/test_native.py --sanitize
python tools/test_native.py --full-resolution
python tools/benchmark_quality.py
python tools/replay.py /path/to/session.zip --output build/replay
```

The full-resolution test temporarily writes roughly 1GB of source spills plus
output files. Its RSS assertions are intended for the normal host build; sanitizer
instrumentation changes memory consumption.

## Physical Pixel 10 verification still needed

The version 0.2 card scan establishes that Detail capture, persistence, and gallery
export ran on the Pixel 10, but exposed serious tracking and boundary failures.
Revised camera combinations and fallbacks, optional RAW mode, viewfinder agreement,
display/crop interaction, color fidelity, latency, thermal behavior, process memory
peaks, and gallery/SAF lifecycle remain physical-device checks. Follow
[the device procedure and observed findings](device-validation.md) before
claiming device readiness. A passing Gradle build or host benchmark does not
establish these runtime properties.

## Version 0.2 diagnostics validation

- Gradle `assembleDebug` and `lintDebug` passed. `python3 tools/test_diagnostics.py`
  passed event/byte/preview limits, aggregate counts after dropped events, concurrent
  logging, restart recovery, bitmap ownership, and unavailable-storage containment.
  Native diagnostic fixtures passed strict JSON parsing, including rejection,
  tracking, conversion-failure, and corner-fallback records. The native benchmark
  JSON was identical before and after instrumentation.
- An emulator replay recorded four attempts: `blur`, `accepted-reference`,
  `duplicate`, and `coarse-ransac` with `insufficient-inliers`. Five corresponding
  diagnostic JPEGs retained attempt/reference and corner context. Corner detection
  reported insufficient foreground: 9.55% against its 12% threshold, and used the
  documented fallback. The compact ZIP was 143,522 bytes, passed CRC checks, and
  contained no PNG or DNG files.
- A replay with zero accepted frames still exported a 3,048-byte compact ZIP with
  one rejected thumbnail and its failure exception. Both replay summaries recorded
  `diagnosticFailures: 0`.
- A live Quick-camera emulator check recorded `focus_not_ready`: AF state 5
  (`NOT_FOCUSED_LOCKED`) while AE and AWB were state 3 (`LOCKED`). The capture gate
  admitted no frames; `scan_stopped` preserved the diagnostic events. This validates
  telemetry and failure retention, and establishes no improvement in live-camera
  scan quality.

The user's failed version 0.1 scans of an MTG card and a framed photograph were
already deleted. These checks do not retrospectively diagnose those failures.
See [diagnostic schema, limits, and export instructions](diagnostics.md).

## Version 0.3 regression validation

- Gradle `assembleDebug` and `lintDebug` passed (zero lint errors). The final APK
  was installed with `adb install -r` on the Pixel 10; installed version is
  `0.3`, code `3`. SHA-256:
  `fa117dd8740df781cc7c23ea2df7c70fc8720b2238f1c9df98b42c75199be7db`.
- Native normal, ASan/UBSan, and full-resolution tests passed. Incremental
  1.04×–1.40× scale changes with rotation were accepted in nine synthetic cases,
  with maximum known-pose errors of 0.013–0.065 source pixels. The 12.6MP
  translation case had 0.00442px center error. The four optical/noise benchmark
  MSE values above were unchanged.
- Synthetic dark, bright, and framed rectangles passed the unchanged normalized
  corner-error bound of 0.018. The actual saved card result now selects the outer
  black card instead of the tabletop, verified visually in Android review.
  Known sequential geometric warps of the actual 12MP source gave maximum pose
  errors of 0.109px, 0.0475px, and 0.0467px at scales 1.12×, 1.30×, and 1.40×.
  These are constructed transformations, not measurements of the discarded
  full-resolution phone attempts. Abrupt large jumps can still lose acquisition.
- Native source PNG export was independently decoded and compared pixel-for-pixel
  with the 4000×3000 input: identical. Its host time was 759ms, which is not a
  prediction of Pixel latency. Kotlin storage tests covered same-path installation,
  metadata/manifest updates, dimension mismatch, and diagnostic ZIP exclusions.
- Camera geometry tests covered every quarter-turn sensor/display combination,
  portrait/landscape/square layouts, exact stream aspect matching, and size limits.
  The emulator camera selector reopened an explicit lens successfully; preview
  letterboxing and unobstructed edges were inspected.
- Real injected Android gestures exercised double-tap zoom, pinch, pan after
  pointer handoff, Fit, and source comparison. The restored fitted canvas was
  pixel-identical, with unchanged corner-handle centers. Zoomed tiles remained
  upright and revealed detail beyond the review thumbnail. Pure-coordinate tests
  also covered export invariance, rotation mapping, limits, and resizing.

The inspected phone files and derived visual fixtures remain in ignored local
build storage. Neither the framed-photo failure nor improved live reflection
handling has been verified by this regression pass.

## Version 0.4 regression validation

- Gradle `assembleDebug` and `lintDebug` passed. Version `0.4`, code `4`,
  was installed with `adb install -r` on the Pixel 10, preserving sessions.
  APK SHA-256:
  `4710de162d5cf502cfe5ebea029e0da6906437fb01e8ae5cebc4586f4300175a`.
- Normal native, full-resolution, ASan/UBSan, and optical benchmark checks passed.
  Sanitizer leak detection was disabled under tracing. Optical MSE values were
  unchanged. Host peak memory was 83.02 MiB for staged storage/50.4MP streaming,
  and 196.21 MiB including the 12.6MP registration test; these are not Android
  or full 24-frame reconstruction memory measurements.
- Known-pose tracking tests cover valid pyramid start levels, abrupt return after
  incremental motion, and unrelated-image rejection. Abrupt reacquisition's
  maximum pose error was 0.00518 source pixels. All 24 real saved source frames
  still register. Those frames were already accepted by the previous version,
  so this replay does not establish recovery of previously rejected observations.
- `tools/test_export_quality.py` passed 64 held-out C++-to-PNG cases, including
  text, RGB detail, gradients, noise, perspective, and rotation. Sharp-text MSE
  improved about 10–11%; no new local extrema appeared. Worst flat-area MSE
  increased 0.0118% (noise standard deviation 0.0059%). Host export time for the
  actual 2797×3900 card rose from 1.545 to 2.100 seconds. Its soft source data
  caused the conservative interpolation gate to retain 99.9619% of output pixels
  unchanged. This is export resampling, not additional observed optical detail.
- Camera geometry/focus tests cover continuous focus admission, calibrated
  distance hints, exact timestamp matching in both arrival orders, stale-buffer
  ownership, and bounded timeout recovery. Kotlin diagnostics/storage tests passed,
  including metadata refresh and compact-export inclusion of preview/preflight
  metadata while excluding source PNG/DNG files.
- The final Android 11 x86 emulator build delivered 82 exactly matched observations
  over 60 seconds: one reference and 81 duplicates of a static synthetic scene.
  All had passive-focused AF state 2. Manual Refocus during scanning worked.
  One image arrived before its start callback and was subsequently matched and
  delivered; no observation timed out or closed the camera. Timeout recovery
  itself is covered by policy tests, not a forced device timeout in this run.
  Finish reached perspective review, and Save PNG produced a valid 147×247
  synthetic crop in the gallery (36ms native edit/export).

Live Pixel quality still requires another physical scan. Individual card source
frames already contain much of the observed border softness; edge-transition
width includes optics, ISP processing, physical shadows, and measurement
interpolation, and does not isolate defocus. The update keeps autofocus active
as camera distance changes. It cannot restore detail absent from the saved inputs.
Installation and version verification succeeded while the Pixel was locked;
live camera startup and the physical telephoto focus-limit hint could not yet
be checked on this build.
