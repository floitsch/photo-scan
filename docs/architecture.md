# Architecture and research direction

## Product constraints

- Pixel 10 first; query actual camera capabilities rather than infer them from
  the model name.
- Offline processing, with desktop tools optional for development.
- Mostly flat subjects, especially printed photos.
- Free movement with advisory feedback, not a sequence of prescribed positions.
- Faithful observations only: no generated faces, texture, text, or missing content.
- Pursue usable capture, maximum recoverable detail, and algorithm comparisons.

## Pipeline

```text
Camera2 preview ───────────────────────────────→ live view
  │
  ├─ individually scheduled, timestamp-matched YUV + capture metadata
  │    ↓ bounded admission and independent plane-buffer copies
  │    ↓ native RGB conversion / grayscale pyramid
  │    ↓ reference feature tracking / RANSAC homography
  │    ↓ blur, overlap, and duplicate checks
  │    ├─ accepted RGB → disk-backed raster + bounded thumbnail
  │    ├─ accepted source PNG + metadata → replayable session
  │    ├─ smaller registered consensus → progressive preview
  │    └─ linear-light samples → tiled 2× drizzle + robust consensus
  │                                ↓
  │                streamed sensor-oriented result.png + raster
  │                                ↓
  │              thumbnail editing → tiled perspective + neutral-point WB
  │                                ↓
  │                         gallery PNG / session ZIP
  │
  └─ optional RAW_SENSOR + matched capture result → DNG archive
```

The UI serializes native operations on one worker. Camera callbacks use a separate
handler. There is a hard observation limit, explicit rejection status, and no
unbounded image queue. Saved transforms map **reference pixels to source pixels**.
Source PNGs and the saved `result.png` retain sensor orientation. The manifest
records `resultOrientation: "sensor"`, `rotationDegrees`, sensor-oriented width and
height, and the support/clipping/rejection metrics. Review rotates only its bounded
thumbnail and any requested detail tiles. Older manifests with a display-oriented result retain their legacy
interpretation. Export maps display corners back into normalized source coordinates,
in desired output TL/TR/BR/BL order; that ordering also expresses quarter turns.
`edited.png` contains the final orientation and correction, while `edit.json`
records the source corners, proportions, rotation, and white-balance gains.

Detail requests supported YUV sources up to 12.6MP and 8192px per side, including
advertised high-resolution stream sizes; Quick requests up to 2.1MP. The preview
stream stays bounded independently but must share the source aspect ratio.
The viewfinder fits the entire rotated stream inside a black background. Rear
logical and physical lens choices come from advertised characteristics; an explicit
choice cannot silently fall back to another lens. Preview/capture crop, zoom,
physical lens, and stabilization metadata are compared and retained for diagnosis.
Preview-only repeating requests keep the live
view active while separate still requests acquire observations. Rejected Detail
stream combinations fall back to Quick dimensions; optional RAW archival is
negotiated separately. Camera metadata records requested/actual modes, dimensions,
stream timings, and fallback reasons. Both modes cap accepted observations at 24.

Exposure and white balance can lock during a scan, while continuous autofocus
keeps following the subject distance. An AF trigger would lock continuous-picture
focus until cancellation ([Android AF semantics](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#CONTROL_AF_MODE_CONTINUOUS_PICTURE)),
so scan start cancels stale focus locks instead. Observations require focused,
stationary lens results and a short settling interval; AUTO/MACRO-only devices
use bounded refocus requests. Physical controls are overridden only when
advertised by the logical camera. Uncalibrated focus-distance values are never
converted into centimeters. A Refocus control retries the selected lens without
switching it.

Accepted source RGB is stored in scanner-owned raster spills, with at most 640px
thumbnails retained for previews. Registration keeps the reference grayscale data
and processes one incoming source, rather than retaining all full-size RGB frames.
Source archival exports the latest accepted native RGB frame directly through the
native lossless PNG writer, avoiding a full Android bitmap and its PNG encoder.
Final fusion works in eight-row tiles, with bounded
source row caches, and streams RGB rows to both PNG and an editable raster. A 2×
12.6MP source grid is at most 50.4MP; no full-resolution result bitmap is allocated.

Initial review decoding stays within 1600px per side and 2MP. Zoomed review decodes
only the visible region plus a margin, bounded to 2MP per tile. Source comparison,
corners, neutral picking, and rotated detail tiles share normalized image coordinates;
viewport changes do not modify export geometry. Reopening a result imports
its full-resolution PNG into an editable raster in 128-row decoded strips. Export
uses 64-row output bands and bounded source regions, including quarter-turn
rotation, perspective, and approximate linear-light white balance. The original
`result.png` remains unchanged. Native reset removes only tracked source spills;
the app also prunes its dedicated disposable reconstruction cache when preparing
a new workspace. Saved session files live separately and remain available for
replay and ZIP export.

At the maximum source size, 24 uncompressed RGB spills alone occupy about 907MB
(decimal), before source PNG archives, result/raster files, and temporary output.
Disk-backed processing bounds resident image memory; it does not make storage or
processing time independent of source resolution.

Registration remains tied to the first view, avoiding sequential accumulation of
alignment errors. Reconstruction remains inside that view's bounds. A future
close-up mosaic requires broader correspondence search, global pose optimization,
and a per-region resolution map.

After acquisition, coarse tracking warps patches using the last accepted
reference-to-source homography as its prediction. Each feature starts at the
deepest pyramid level that actually contains its reference and projected patches;
features near an image border can start at a finer level instead of being discarded.
If the predicted pose fails coarse RANSAC, a second independent identity-seeded
acquisition runs through the same acceptance gates. The two hypotheses and
start-level reductions are retained in diagnostics. Fine registration uses the coarse homography's inlier set. It refines full-pyramid
patches warped by that homography, then requires reverse consistency within one
source pixel before another RANSAC fit. This accommodates local scale and
perspective changes without reducing the acceptance thresholds. Automatic corner
proposals examine both dark and bright connected components, reject components
touching the image border, simplify convex hulls to quadrilaterals, and validate
all four edges. A failed proposal leaves a manual crop fallback.

## Meaning of super-resolution

The current core projects input samples onto a finer grid and combines observations
with different subpixel offsets. This is a measurement-based reconstruction;
the output grid size is not evidence of a corresponding optical resolution gain.

Validation must compare against:

1. The sharpest individual capture at the same output size.
2. A single-frame bilinear enlargement.
3. Aligned multi-frame denoising without increasing the grid.
4. The candidate super-resolution method.

Synthetic known-pose tests isolate reconstruction from tracking. Estimated-pose
tests expose registration errors. Real print tests must evaluate fine text,
line pairs, false detail, ringing, ghosting, print grain, and glare. A result
that looks sharper because it added halos is not an improvement.

## Reflection and color limits

Temporal disagreement is evidence of corruption, not a complete reflection model.
Majority glare can defeat a median-like estimator. A stationary reflected scene
can resemble print content, and all-view clipping contains no recoverable detail.
Confidence reporting must distinguish sample support from verified absence of glare.

The first implementation locks camera white balance when possible and offers an
explicit neutral-point correction. White paper is not assumed: old prints may be
yellow, intentionally toned, or borderless. Neutral-point gains adjust an output;
they cannot reproduce a calibrated color target or compensate arbitrary mixed lighting.

RAW archival preserves a path to sensor-linear fusion, joint demosaicing and
super-resolution. A proper RAW path needs black/white levels, CFA arrangement,
noise model, color transforms, lens shading, exposure normalization, and geometric
calibration. Do not process Bayer planes as ordinary RGB.

## Research candidates

These references informed the architecture; their implementations or model weights
are not bundled in this app.

- [Handheld Multi-Frame Super-Resolution, 2019](https://research.google/pubs/handheld-multi-frame-super-resolution/):
  direct RAW burst fusion using natural hand tremor; a useful engineering baseline
  for genuine additional samples, joint demosaicing and denoising.
- [Implementing Handheld Burst Super-Resolution, IPOL 2023](https://www.ipol.im/pub/art/2023/460/):
  reproducible implementation study for the above approach.
- [RealBSR, ICCV 2023](https://arxiv.org/abs/2309.04803):
  real-world RAW and RGB burst data for evaluating generalization beyond synthetic
  downsampling.
- [QMambaBSR, CVPR 2025](https://openaccess.thecvf.com/content/CVPR2025/html/Di_QMambaBSR_Burst_Image_Super-Resolution_with_Query_State_Space_Model_CVPR_2025_paper.html):
  learned burst fusion and adaptive upsampling. Benchmark quality, Android operator
  support, memory and latency before considering deployment.
- [Removing Reflections from RAW Photos, CVPR 2025](https://openaccess.thecvf.com/content/CVPR2025/html/Kee_Removing_Reflections_from_RAW_Photos_CVPR_2025_paper.html):
  reflection separation using linear RAW and optional contextual imagery.
  Its performance on consumer reflections does not establish success on glossy
  photo prints, nor does its reported iPhone runtime establish Android performance.
- [PhotoScan technical description, 2017](https://www.research.google/blog/photoscan-taking-glare-free-pictures-of-pictures/):
  multi-view alignment and outlier rejection for reflective printed photographs.

Android references: [camera capabilities](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata),
[capture controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest),
and [supported stream combinations](https://developer.android.com/reference/android/hardware/camera2/CameraDevice).

Perspective export uses a noise-gated, locally range-clamped cubic correction
to bilinear interpolation. The gate retains bilinear sampling for corrections
below estimated local noise or quantization. It preserves existing sharp detail
without a learned restoration or sharpening pass; it does not add optical
resolution. See the held-out export checks in [validation](validation.md).

## Quality roadmap

1. Verify device capture, color, orientation, thermal behavior and frame admission
   on a Pixel 10; retain representative input sessions.
2. Compare the current reconstruction with its best input and PhotoScan on the
   same prints. Measure detail rather than infer it from file dimensions.
3. Validate the implemented full-resolution YUV capture scheduling and disk-backed
   fusion on-device, then develop tiled RAW fusion with calibrated sensor data.
4. Evaluate recent learned burst methods against the faithful baseline. Reject
   models that alter source text, faces or fine structure.
5. Add close-up mosaics with global alignment, per-region sampling density,
   and a reliable indication of areas that still need observations.
