// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded, best-effort diagnostics. Call from a worker, never from a camera callback. */
internal class DiagnosticsStore {
    companion object {
        const val MAX_EVENTS = 2000
        const val MAX_LOG_BYTES = 2 * 1024 * 1024
        const val MAX_PREVIEWS = 12
        const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
        const val MAX_PREVIEW_SIDE = 960
        private val TERMINAL_EVENTS = setOf(
            "scan_finished", "scan_stopped", "scan_error", "scan_end",
            "session_finish", "session_finish_exception", "session_start_exception", "capture_exception",
            "replay_finish", "replay_exception", "edit_finish", "edit_exception", "export_finish", "export_exception")
        fun schema() = JSONObject().put("schemaVersion", 1)
            .put("maxStoredEvents", MAX_EVENTS).put("maxLogBytes", MAX_LOG_BYTES)
            .put("maxPreviews", MAX_PREVIEWS).put("maxPreviewBytes", MAX_PREVIEW_BYTES)
            .put("maxPreviewSide", MAX_PREVIEW_SIDE).put("previewFormat", "JPEG")
    }

    private class State(val session: File) {
        var events = 0L
        var stored = 0L
        var dropped = 0L
        var failures = 0L
        var bytes = 0L
        var previewRequests = 0L
        var droppedPreviews = 0L
        var lastFlush = 0L
        var sinceFlush = 0
        val eventCounts = linkedMapOf<String, Long>()
        val reasons = linkedMapOf<String, Long>()
        val statuses = linkedMapOf<String, Long>()
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun state(session: File): State = states.computeIfAbsent(session.absolutePath) {
        State(session).also { state ->
            val summary = File(session, "diagnostic-summary.json")
            if (summary.isFile) try {
                val old = JSONObject(summary.readText())
                state.events = old.optLong("totalEvents")
                state.dropped = old.optLong("droppedEvents")
                state.failures = old.optLong("diagnosticFailures")
                state.previewRequests = old.optLong("previewRequests")
                state.droppedPreviews = old.optLong("droppedPreviews")
                for ((key, target) in listOf("eventCounts" to state.eventCounts, "attemptReasons" to state.reasons, "attemptStatuses" to state.statuses)) {
                    old.optJSONObject(key)?.let { values -> values.keys().forEach { name -> target[name] = values.optLong(name) } }
                }
            } catch (_: Exception) { state.failures++ }
            val log = File(session, "diagnostics.jsonl")
            if (log.isFile) {
                state.bytes = log.length()
                try { log.useLines { lines -> lines.forEach { line ->
                    state.stored++
                    try {
                        val record = JSONObject(line)
                        // Recover records appended since the last periodically persisted summary.
                        if (record.optLong("sequence") > state.events) {
                            count(state, record.optString("event"), record.optJSONObject("detail") ?: JSONObject())
                            state.events = record.optLong("sequence")
                        }
                    } catch (_: Exception) { state.failures++ }
                } } } catch (_: Exception) { state.failures++ }
            }
        }
    }

    fun logEvent(session: File, event: String, detail: JSONObject) {
        try {
            val state = state(session)
            synchronized(state) {
                val name = identifier(event)
                count(state, name, detail)
                val safe = sanitize(detail) as JSONObject
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
                val record = JSONObject().put("schemaVersion", 1).put("sequence", state.events)
                    .put("timestampUtc", utc).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                    .put("event", name).put("detail", safe)
                val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
                if (state.stored >= MAX_EVENTS || bytes.size > 64 * 1024 || state.bytes + bytes.size > MAX_LOG_BYTES) state.dropped++
                else try {
                    FileOutputStream(File(session, "diagnostics.jsonl"), true).use { it.write(bytes) }
                    state.stored++
                    state.bytes += bytes.size
                } catch (_: Exception) {
                    state.failures++; state.dropped++
                    state.bytes = File(session, "diagnostics.jsonl").length()
                }
                state.sinceFlush++
                if (state.sinceFlush >= 10 || SystemClock.elapsedRealtime() - state.lastFlush >= 2000 || name in TERMINAL_EVENTS) flushLocked(state)
                Log.d("LumaScan", "$name reason=${identifier(safe.optString("reason", "none"))} status=${identifier(safe.optString("status", "none"))}")
            }
        } catch (_: Exception) {
            // Broken diagnostic storage or malformed detail must never abort a scan.
            recordFailure(session)
            Log.w("LumaScan", "diagnostic_write_failed")
        }
    }

    private fun count(state: State, event: String, detail: JSONObject) {
        state.events++
        increment(state.eventCounts, event)
        if (event == "frame_attempt") {
            increment(state.reasons, identifier(detail.optString("reason", "unspecified")))
            increment(state.statuses, identifier(detail.optString("status", "unspecified")))
        }
    }

    private fun increment(counts: MutableMap<String, Long>, value: String) {
        val key = if (counts.containsKey(value) || counts.size < 63) value else "other"
        counts[key] = (counts[key] ?: 0L) + 1L
    }

    /** Caller keeps ownership of bitmap. Images of the photographed print remain visible here. */
    fun savePreview(session: File, name: String, bitmap: Bitmap): String? {
        return try {
            val state = state(session)
            synchronized(state) {
                state.previewRequests++
                val directory = File(session, "diagnostic-previews")
                check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, identifier(name) + ".jpg")
                val old = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
                if (!file.exists() && old.size >= MAX_PREVIEWS) { state.droppedPreviews++; flushLocked(state); return@synchronized null }
                val side = max(bitmap.width, bitmap.height)
                val scaled = if (side > MAX_PREVIEW_SIDE) {
                    val ratio = MAX_PREVIEW_SIDE.toDouble() / side
                    Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * ratio).roundToInt()), max(1, (bitmap.height * ratio).roundToInt()), true)
                } else bitmap
                val bytes = try {
                    ByteArrayOutputStream().use { out -> check(scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)); out.toByteArray() }
                } finally { if (scaled !== bitmap) scaled.recycle() }
                val retainedBytes = old.sumOf { it.length() } - if (file.isFile) file.length() else 0L
                if (retainedBytes + bytes.size > MAX_PREVIEW_BYTES) { state.droppedPreviews++; flushLocked(state); return@synchronized null }
                atomicWrite(file, bytes)
                flushLocked(state)
                "diagnostic-previews/${file.name}"
            }
        } catch (_: Exception) {
            recordFailure(session)
            Log.w("LumaScan", "diagnostic_preview_failed")
            null
        }
    }

    private fun recordFailure(session: File) {
        try { val state = state(session); synchronized(state) { state.failures++; flushLocked(state) } } catch (_: Exception) {}
    }

    fun flush(session: File) {
        try { val state = state(session); synchronized(state) { flushLocked(state) } }
        catch (_: Exception) { Log.w("LumaScan", "diagnostic_summary_failed") }
    }

    private fun flushLocked(state: State) {
        val directory = File(state.session, "diagnostic-previews")
        val previews = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }.orEmpty()
        val summary = schema().put("updatedAt", System.currentTimeMillis())
            .put("totalEvents", state.events).put("storedEvents", state.stored)
            .put("droppedEvents", state.dropped).put("diagnosticFailures", state.failures)
            .put("logBytes", state.bytes).put("eventCounts", JSONObject(state.eventCounts.toMap()))
            .put("attemptReasons", JSONObject(state.reasons.toMap())).put("attemptStatuses", JSONObject(state.statuses.toMap()))
            .put("previewCount", previews.size).put("previewBytes", previews.sumOf { it.length() })
            .put("previewRequests", state.previewRequests).put("droppedPreviews", state.droppedPreviews)
        try {
            atomicWrite(File(state.session, "diagnostic-summary.json"), summary.toString(2).toByteArray(Charsets.UTF_8))
            state.lastFlush = SystemClock.elapsedRealtime()
            state.sinceFlush = 0
        } catch (_: Exception) { state.failures++; Log.w("LumaScan", "diagnostic_summary_failed") }
    }

    private fun identifier(value: String) = value.take(80).replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "unspecified" }

    private fun sanitize(value: Any?, depth: Int = 0): Any {
        if (value == null || value === JSONObject.NULL || depth > 8) return JSONObject.NULL
        return when (value) {
            is JSONObject -> JSONObject().also { clean -> value.keys().asSequence().take(128).forEach { key -> clean.put(key.take(128), sanitize(value.opt(key), depth + 1)) } }
            is JSONArray -> JSONArray().also { clean -> for (i in 0 until minOf(256, value.length())) clean.put(sanitize(value.opt(i), depth + 1)) }
            is Double -> if (value.isFinite()) value else JSONObject.NULL
            is Float -> if (value.isFinite()) value else JSONObject.NULL
            is Number, is Boolean -> value
            else -> value.toString().take(8192)
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
    }
}
