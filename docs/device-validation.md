# Pixel 10 validation

Host numerical tests and APK compilation cannot validate the camera HAL, real
optics, or UI lifecycle on a phone. Record results here or alongside an exported
session; do not label unperformed steps as passing.

## First launch and capture

- Install the debug APK, grant camera permission, and confirm a correctly oriented,
  undistorted portrait preview.
- Deny permission once, and permanently if appropriate, to check retry/Settings behavior.
- Let autofocus and exposure settle on a matte print. Capture a gentle freehand
  movement. Confirm the accepted frame count rises and the combined thumbnail updates.
- Move too fast, briefly obscure the view, and return. Expect advisory rejection
  rather than corrupt observations or a crash.
- Finish early and reach the 24-observation limit in a separate session.
- Inspect session metadata: exact lens, YUV dimensions, exposure, ISO, WB gains,
  lock support, matched timestamps, RAW availability and any stream fallback.

## Reconstruction comparisons

For a matte photo, a glossy photo, a fine-text target, and a nonrectangular flat
subject, export the complete session. Compare the combined output with the sharpest
source at identical display scale.

Test moving glare with several views exposing each region. Separately test
persistent glare and clipped content; the app must not claim those have been
recovered. Look for darkening, ghost edges, haloing and false texture.

Drag the four corners, choose a known aspect ratio, and verify the gallery output
against the original shape. Pick a known neutral patch and verify white balance;
reset must restore the original color. Intentionally warm/sepia prints should stay
that way unless explicitly adjusted.

## Persistence and lifecycle

- Save a PNG; confirm `Pictures/Luma Scan` has a viewable, correctly oriented image.
- Export ZIP, reopen a session, and replay it. Compare replay with the initial
  reconstruction. Source PNGs must match the captured observations.
- Archive a DNG when supported. Verify it opens and has the expected sensor size
  and capture metadata; it is not an input to the current fusion path.
- Background/foreground the app while scanning, during final processing, and while
  saving. No camera-in-use leaks, native image lifetime errors, or corrupted saved output.
- Lock/unlock the phone, interrupt with another camera application, and reopen.
- Repeat scans for several minutes. Measure peak memory, final-processing latency,
  preview smoothness, battery use, and thermal throttling.

## Status

Version 0.2 was installed on the Pixel 10. With permission, the user's current
2026-10-20 card scan, review screenshot, gallery PNG, and diagnostics were inspected.
Only 1 of 27 attempted frames was accepted: 10 failed coarse registration and
16 failed full-resolution refinement. The 8000×6000 result therefore had no
multi-view resolution gain or glare recovery. The boundary proposal selected the
bright tabletop instead of the dark card. Source archival alone took about
5.0 seconds for the first 4000×3000 observation. Preview code also cropped the
camera stream to fill the available view.

Version 0.3 reached camera-ready with a 4000×3000 Detail stream.
Its selector exposes the Pixel's advertised ultra-wide (~14mm equivalent), wide
(~25mm), and telephoto (~105mm) physical lenses, plus the logical camera. Opening
each alternative physical stream combination still needs testing.

Version 0.3 addresses these findings with camera selection, full-area preview,
scale-aware fine alignment, both-polarity rectangle detection, native source PNG
encoding, and zoomable review. Reprocessing saved data and emulator tests cannot
establish the new live Pixel camera quality: another physical capture is needed.
Rejected full-resolution source images were not retained, so their exact sensor
alignment cannot be reconstructed from diagnostic JPEGs.

The older deleted scans were not used. No framed-photo session was available in
the current evidence. Android emulator checks are recorded separately in
[the validation results](validation.md#android-emulator-validation).

## Version 0.3 card follow-up

The next user scan, `20261020-173902-72ffb446`, accepted 24 of 31 attempts.
Six attempts failed coarse registration and one failed fine registration.
The 2797×3900 gallery export has the correct card boundary, but the user reports
soft borders and tracking that still loses position too readily. The full
8000×6000 reconstruction reported nearly 100% multi-view support; this coverage
metric does not measure recovered optical resolution.

All captured attempts reported AF state 4 and the same focus-distance value,
7.277652. Version 0.3 did not record distance calibration, so this value must not
be converted into a measured subject distance. Exposure was 12.718ms at ISO 375. The camera code explicitly locked
continuous autofocus at scan start, preventing it from following changes in
distance. This is a plausible source of softness, not proof that every soft edge
is caused by focus. Accepted feature-fit RMS was approximately 0.5–0.8 source
pixels; correspondence scatter is not the same as true pose error.

Accepted source archival took approximately 663ms, down from 5004ms in the
previous recorded scan. Useful observations were typically about 1.3 seconds
apart. The 24-frame 48MP final render took 103.7 seconds on the Pixel.

The user explicitly approved local inspection of all 24 original source frames
for alignment/focus/fusion comparisons. The reported 5× preview focus failure
has no retained autofocus telemetry in this session or available app logcat;
it must not be attributed to a specific optical minimum distance without further
capability and focus-result evidence.

Version 0.4 is now installed (code 4), preserving these sessions. Continuous
autofocus, a Refocus control, improved acquisition, and bounded capture recovery
address the follow-up findings. Host/emulator evidence is recorded in
[validation](validation.md#version-04-regression-validation). The phone was locked
during post-install inspection; its updated live preview and telephoto capability
hint remain to be checked after unlock.
