// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Internal, lossless source archive. Call disk operations from a worker thread. */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "scans")
    private val diagnostics = DiagnosticsStore()
    private val environment = JSONObject().apply {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            put("appVersion", packageInfo.versionName ?: "unknown")
            put("appVersionCode", packageInfo.longVersionCode)
        } catch (_: Exception) { put("appVersion", "unknown") }
        put("androidVersion", Build.VERSION.RELEASE).put("androidSdk", Build.VERSION.SDK_INT)
        put("brand", Build.BRAND).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        put("device", Build.DEVICE).put("buildId", Build.ID).put("buildFingerprint", Build.FINGERPRINT)
    }

    @Synchronized
    fun create(cameraInfo: JSONObject): File {
        check(root.isDirectory || root.mkdirs()) { "Cannot create scan storage" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val session = File(root, "$stamp-${UUID.randomUUID().toString().take(8)}")
        check(session.mkdir()) { "Cannot create scan session" }
        writeJson(File(session, "manifest.json"), JSONObject()
            .put("version", 1)
            .put("environment", JSONObject(environment.toString()))
            .put("diagnostics", DiagnosticsStore.schema())
            .put("createdAt", System.currentTimeMillis())
            .put("camera", JSONObject(cameraInfo.toString()))
            .put("sourceMode", "YUV_420_888")
            .put("sourceStorage", "lossless PNG converted from YUV; native sensor orientation")
            .put("raw", "Optional supplemental DNG files retain sensor data; RAW is not used by fusion.")
            .put("transformConvention", "reference_to_source; row-major 3x3 homography; native sensor pixel coordinates")
            .put("reportLayout", JSONArray(listOf("status", "acceptedFrames", "inliers", "phaseBinsOf16", "sharpness", "error", "h00", "h01", "h02", "h10", "h11", "h12", "h20", "h21", "h22")))
            .put("frames", JSONArray()))
        diagnostics.logEvent(session, "session_created", JSONObject().put("environment", JSONObject(environment.toString())))
        return session
    }

    @Synchronized
    fun updateCameraInfo(session: File, info: JSONObject) {
        val manifest = readManifest(session)
            .put("camera", JSONObject(info.toString()))
            .put("updatedAt", System.currentTimeMillis())
        writeJson(File(session, "manifest.json"), manifest)
    }

    fun saveFrame(session: File, index: Int, bitmap: Bitmap, report: DoubleArray, metadata: JSONObject) {
        require(index >= 0)
        val name = "frame-%05d".format(Locale.US, index)
        val frame = File(session, "$name.png")
        writeBitmap(frame, bitmap)
        recordFrame(session, index, report, metadata)
    }

    /** Native source export avoids a full Android bitmap and slow Bitmap.compress encoding. */
    fun saveFrameFile(session: File, index: Int, pngFile: File, report: DoubleArray, metadata: JSONObject) {
        require(index >= 0)
        val width = metadata.optInt("width", metadata.optInt("sourceWidth", 0))
        val height = metadata.optInt("height", metadata.optInt("sourceHeight", 0))
        require(width > 0 && height > 0 && width <= 8192 && height <= 8192 && width.toLong() * height <= 12_600_000L) {
            "Invalid source archive dimensions"
        }
        check(pngFile.isFile && pngFile.length() > 0) { "Missing accepted source PNG" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pngFile.absolutePath, bounds)
        check(bounds.outWidth == width && bounds.outHeight == height && bounds.outMimeType == "image/png") {
            "Source PNG dimensions do not match camera metadata"
        }
        val destination = File(session, "frame-%05d.png".format(Locale.US, index))
        if (pngFile.canonicalFile != destination.canonicalFile) {
            atomicWrite(destination) { stream -> pngFile.inputStream().use { it.copyTo(stream) } }
        }
        recordFrame(session, index, report, metadata)
    }

    private fun recordFrame(session: File, index: Int, report: DoubleArray, metadata: JSONObject) {
        val name = "frame-%05d".format(Locale.US, index)
        val frame = File(session, "$name.png")
        val detail = JSONObject()
            .put("index", index).put("image", frame.name)
            .put("capture", JSONObject(metadata.toString()))
            .put("report", numbers(report))
        if (report.size >= 15) detail.put("referenceToSourceHomography", numbers(report.copyOfRange(6, 15)))
        writeJson(File(session, "$name.json"), detail)
        synchronized(this) {
            val manifest = readManifest(session)
            val old = manifest.optJSONArray("frames") ?: JSONArray()
            val updated = JSONArray()
            for (i in 0 until old.length()) {
                val entry = old.optJSONObject(i) ?: continue
                if (entry.optInt("index", -1) != index) updated.put(entry)
            }
            updated.put(JSONObject().put("index", index).put("image", frame.name).put("metadata", "$name.json"))
            manifest.put("frames", updated).put("updatedAt", System.currentTimeMillis())
            writeJson(File(session, "manifest.json"), manifest)
        }
    }

    fun finish(session: File, bitmap: Bitmap, metrics: DoubleArray) {
        writeBitmap(result(session), bitmap)
        recordResult(session, bitmap.width, bitmap.height, metrics, "display", 0)
    }

    /** Installs a native-orientation PNG without decoding its full-resolution pixels. */
    fun finishFile(session: File, pngFile: File, width: Int, height: Int, metrics: DoubleArray, rotationDegrees: Int, acceptedSourceCount: Int? = null) {
        require(width > 0 && height > 0) { "Invalid result dimensions" }
        require(rotationDegrees in listOf(0, 90, 180, 270)) { "Invalid result rotation" }
        check(pngFile.isFile && pngFile.length() > 0) { "Missing rendered result" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pngFile.absolutePath, bounds)
        check(bounds.outWidth == width && bounds.outHeight == height && bounds.outMimeType == "image/png") {
            "Rendered PNG dimensions do not match the result"
        }
        val destination = result(session)
        // The native writer may already have atomically installed result.png.
        if (pngFile.canonicalFile != destination.canonicalFile) {
            atomicWrite(destination) { stream -> pngFile.inputStream().use { it.copyTo(stream) } }
        }
        recordResult(session, width, height, metrics, "sensor", rotationDegrees, acceptedSourceCount)
    }

    @Synchronized
    private fun recordResult(session: File, width: Int, height: Int, metrics: DoubleArray, orientation: String, rotationDegrees: Int, acceptedSourceCount: Int? = null) {
        val manifest = readManifest(session)
            .put("finishedAt", System.currentTimeMillis())
            .put("result", "result.png")
            .put("resultOrientation", orientation)
            .put("rotationDegrees", rotationDegrees)
            .put("width", width).put("height", height)
            .put("metrics", numbers(metrics))
            .put("metricsLayout", JSONArray(listOf("supportFraction", "clippedFraction", "rejectedFraction")))
        if (acceptedSourceCount != null) manifest.put("acceptedSourceCount", acceptedSourceCount)
        writeJson(File(session, "manifest.json"), manifest)
    }

    @Synchronized
    fun sessions(): List<File> = root.listFiles()?.filter {
        it.isDirectory && File(it, "manifest.json").isFile
    }?.sortedByDescending { it.name } ?: emptyList()

    fun result(session: File): File = File(session, "result.png")

    @Synchronized
    fun frames(session: File): List<File> = session.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("frame-[0-9]+\\.png"))
    }?.sortedBy { it.name } ?: emptyList()

    /** Best-effort diagnostics: failures never interrupt capture. Call from a worker. */
    fun logEvent(session: File, event: String, detail: JSONObject) = diagnostics.logEvent(session, event, detail)

    /** Caller retains bitmap ownership; returns a relative JPEG path or null if capped/unavailable. */
    fun saveRejectedPreview(session: File, attemptId: Int, bitmap: Bitmap, detail: JSONObject): String? {
        val runId = detail.optString("runId", "").take(36).replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifEmpty { UUID.randomUUID().toString() }
        val path = diagnostics.savePreview(session, "attempt-$runId-%06d".format(Locale.US, attemptId), bitmap)
        if (path != null) diagnostics.logEvent(session, "rejected_preview_saved", JSONObject().put("attemptId", attemptId).put("path", path).put("capture", detail))
        return path
    }

    fun saveDiagnosticPreview(session: File, name: String, bitmap: Bitmap): String? = diagnostics.savePreview(session, name, bitmap)

    /** Compact troubleshooting bundle, including small photographic thumbnails, never source PNG/DNG. */
    fun exportDiagnosticsZip(session: File, out: OutputStream) {
        diagnostics.flush(session)
        ZipOutputStream(out.buffered()).use { zip ->
            session.walkTopDown().filter { file ->
                if (!file.isFile || file.name.startsWith(".") || file.name.endsWith(".tmp")) false
                else {
                    val relative = file.relativeTo(session).invariantSeparatorsPath
                    relative in listOf("diagnostics.jsonl", "diagnostic-summary.json", "camera-preview.json", "camera-preflight.json", "manifest.json", "edit.json") ||
                        relative.matches(Regex("frame-[0-9]+\\.json")) ||
                        (relative.startsWith("diagnostic-previews/") && relative.endsWith(".jpg"))
                }
            }.sortedBy { it.relativeTo(session).path }.forEach { file ->
                val entry = ZipEntry(file.relativeTo(session).invariantSeparatorsPath)
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Closes the archive and the supplied SAF output stream after export. */
    fun exportZip(session: File, out: OutputStream) {
        diagnostics.flush(session)
        ZipOutputStream(out.buffered()).use { zip ->
            session.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
                .sortedBy { it.relativeTo(session).path }.forEach { file ->
                    val entry = ZipEntry(file.relativeTo(session).invariantSeparatorsPath)
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun numbers(values: DoubleArray): JSONArray = JSONArray().also { array ->
        values.forEach { array.put(if (it.isFinite()) it else JSONObject.NULL) }
    }

    private fun readManifest(session: File) = JSONObject(File(session, "manifest.json").readText())

    private fun writeJson(file: File, value: JSONObject) = atomicWrite(file) { stream ->
        stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun writeBitmap(file: File, bitmap: Bitmap) = atomicWrite(file) { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not save image" }
    }

    private fun atomicWrite(file: File, write: (FileOutputStream) -> Unit) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                write(stream)
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not commit ${file.name}" }
        } finally {
            temporary.delete()
        }
    }
}
