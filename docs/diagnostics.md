# Scan diagnostics

Luma Scan 0.2 and later record new scan attempts locally, including attempts that never produce a successful result. Use **Your scans → session → Export diagnostics** to save a compact troubleshooting ZIP, including for a session without a finished result. On the result review screen, **Export session** offers **Export diagnostics** and **Export full session ZIP**. The app does not upload it. Review the ZIP before sharing: its small JPEG thumbnails show the photograph being scanned, and its manifest identifies the phone model and Android build.

A compact diagnostics ZIP contains `diagnostics.jsonl`, `diagnostic-summary.json`, `manifest.json`, accepted-frame `frame-NNNNN.json` metadata, `edit.json` when present, and `diagnostic-previews/*.jpg`. It excludes full source PNGs, the full result PNG, edited PNGs, and RAW DNGs. The full source archive also includes diagnostic files.

## Event records

`diagnostics.jsonl` uses schema version 1, with one JSON object per line:

- `sequence`: session event sequence number, including events omitted after limits are reached.
- `timestampUtc`: UTC wall time, formatted as ISO 8601 with milliseconds and `Z`.
- `elapsedRealtimeMs`: Android monotonic milliseconds since boot, useful for time intervals when the wall clock changes.
- `event`: a short identifier such as `session_created` or `frame_attempt`.
- `detail`: event-specific measurements, attempt status and reason, capture metadata, quality scores, or corner/edit measurements. A preview path is relative to the scan directory.

The event file retains the first 2,000 records up to 2 MiB. A single encoded record is limited to 64 KiB. After either limit, counters continue to count attempts, events, statuses, and reasons; the summary records omitted events. Detail nesting is limited to eight levels, arrays to 256 elements, objects to 128 fields, and strings to 8,192 characters. Non-finite floating-point values are stored as JSON null.

`diagnostic-summary.json` contains total, stored, and dropped event counts; diagnostic storage failures; counts by event; and `attemptReasons` and `attemptStatuses` for `frame_attempt` events. Counter categories retain up to 63 distinct identifiers and aggregate further categories as `other`. It also records log bytes, preview bytes/count, preview requests, and previews omitted by the storage caps. The summary refreshes after ten events or after two seconds on the next event, on terminal scan events, and before export. If the process dies, newly appended log records are recovered when that session is used again; counter-only events since the last summary write may be lost.

## Preview limits and ownership

Rejected-attempt snapshots and optional corner previews share a limit of 12 JPEG files and 4 MiB per session. The longest image side is at most 960 pixels; JPEG quality is 78. The app retains at most eight rejected-attempt previews, leaving four slots for reference and corner context within the shared twelve-image budget. The capture caller chooses which rejected attempts warrant a snapshot, for example a changed rejection reason or every fifth attempt. Rejected filenames include the run UUID and attempt number, preserving evidence across replay runs. Storage enforces the hard limits regardless of that policy. Reusing a preview name replaces that file atomically within the same budget.

Preview files and summaries are installed with an atomic rename. Diagnostic failures never stop capture; missing files or counters indicate that storage may have been unavailable. Event records continue to aggregate in memory when appending fails, and later summary writes can preserve those counts. The caller retains ownership of preview bitmaps and should recycle them after the save call. All diagnostic disk operations belong on a worker thread.

The `environment` object in the manifest records app version/code, Android version/API, brand, manufacturer, model, device, build ID and build fingerprint. Its `diagnostics` object describes the schema and storage caps. `LumaScan` logcat messages contain concise event/reason/status identifiers, with no photo pixels or base64 image data.

Version 0.3 additionally records selected/actual camera choice, preview fit bounds,
capture and preview aspect agreement, crop coordinate spaces, crop/zoom/lens/
stabilization comparisons, and whether field-of-view agreement could be verified
from metadata. Missing telemetry remains unknown, rather than proving a match.
Fine-tracking records include reverse-consistency failures and the warped-patch
method. Corner records include both-polarity component counts, rejection counts,
ranked quadrilaterals, each candidate's edge support, and optional line-fit error.
Finished manifests retain `acceptedSourceCount`; output dimensions alone are not
evidence of multi-frame resolution gain.

Version 0.4 retains bounded metadata before scanning in `camera-preview.json`
(at most 32 packets, 60 seconds, and 1 MiB; each packet at most 20 KiB).
`camera-preflight.json` freezes the available history at Start. Both files are
included in compact diagnostic exports; neither contains image pixels. Metadata
includes requested/actual focus mode, focus readiness, lens movement, minimum
focus setting and distance calibration. Uncalibrated focus values are not
converted to centimeters. Timestamp/disposal and timeout/recovery events describe
camera buffer matching. Registration records include valid pyramid start-level
reductions and at most two independent acquisition hypotheses.

Run `python3 tools/test_diagnostics.py` to exercise the real Kotlin storage policy on the host. It covers event/byte limits, retained aggregate counts, preview count/byte budgets, dimension scaling, caller bitmap ownership, concurrent events, restart recovery, and unavailable storage. Android clock, logger, and JPEG encoder are stubbed; device validation must check actual thumbnails and the compact ZIP.
