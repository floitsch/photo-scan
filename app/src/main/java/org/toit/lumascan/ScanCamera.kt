// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.TextureView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.math.abs
import java.util.Locale

/** Camera callbacks and frame processing run off the UI thread. The consumer owns each YUV Image. */
class ScanCamera(
    private val context: Context,
    private val texture: TextureView,
    private val onFrame: (Image, JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onReady: (JSONObject) -> Unit,
    private val detailMode: Boolean = true,
    private val onDiagnostic: (JSONObject) -> Unit = {},
    private val selectedCamera: String? = null
) {
    data class CameraChoice(val id: String, val label: String, val cameraId: String,
        val physicalCameraId: String?, val equivalentFocalMm: Double?, val focusHint: String = "")

    companion object {
        /** Advertised compatible rear outputs; session combinations are verified when opened. */
        fun availableCameras(context: Context): List<CameraChoice> {
            val manager = context.getSystemService(CameraManager::class.java)
            val choices = mutableListOf<CameraChoice>()
            fun add(logicalId: String, physicalId: String?, c: CameraCharacteristics) {
                try {
                    val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return
                    val yuv = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList() +
                        map.getHighResolutionOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList()
                    CameraGeometry.streams(yuv.map { CameraGeometry.Dimensions(it.width, it.height) },
                        map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { CameraGeometry.Dimensions(it.width, it.height) }, 12_600_000L)
                    val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.toDouble()
                    val sensorWidth = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.width?.toDouble()
                    val equivalent = if (focal != null && sensorWidth != null && sensorWidth > 0) 36 * focal / sensorWidth else null
                    val lens = CameraGeometry.lensType(equivalent)
                    val focalLabel = equivalent?.let { String.format(Locale.US, " · ~%.0f mm equiv", it) } ?: ""
                    val id = if (physicalId == null) "logical:$logicalId" else "physical:$logicalId:$physicalId"
                    val logicalMulti = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                    val label = if (physicalId != null) "$lens$focalLabel · fixed lens $physicalId via $logicalId"
                        else if (logicalMulti) "$lens$focalLabel · camera $logicalId (logical, auto lens)"
                        else "$lens$focalLabel · camera $logicalId"
                    val minimum = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]
                    val nearest = CameraFocusPolicy.closestFocusCm(minimum, c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION])
                    val focusHint = if (minimum == 0f) "Fixed-focus lens"
                        else nearest?.let { String.format(Locale.US, "Closest focus approximately %.0f cm", it) } ?: "Closest focus distance not calibrated"
                    choices.add(CameraChoice(id, label, logicalId, physicalId, equivalent, focusHint))
                } catch (_: Exception) { /* Unsupported maps do not become selectable candidates. */ }
            }
            manager.cameraIdList.forEach { id ->
                try {
                    val c = manager.getCameraCharacteristics(id)
                    if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) {
                        add(id, null, c)
                        c.physicalCameraIds.sorted().forEach { physical ->
                            try { add(id, physical, manager.getCameraCharacteristics(physical)) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            return choices.sortedWith(compareBy<CameraChoice> { it.equivalentFocalMm ?: Double.MAX_VALUE }.thenBy { it.id })
        }
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(context.mainLooper)
    @Volatile private var current: Run? = null
    @Volatile private var closing: Run? = null
    private var reopenAfterClose = false
    @Volatile private var wantedOpen = false
    @Volatile var info: JSONObject = JSONObject()
        private set
    @Volatile var isReady = false
        private set

    private inner class Run {
        val thread = HandlerThread("LumaCamera").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var yuv: ImageReader? = null
        var raw: ImageReader? = null
        var preview: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var builder: CaptureRequest.Builder? = null
        lateinit var characteristics: CameraCharacteristics
        lateinit var logicalCharacteristics: CameraCharacteristics
        var cameraId = ""
        var choiceId = ""
        var choiceLabel = ""
        @Volatile var viewport: JSONObject? = null
        var previewCrop: Rect? = null
        var previewCoordinateSpace: String? = null
        var latestCoordinateSpace: String? = null
        var previewZoom: Float? = null
        var previewPhysicalId: String? = null
        var previewVideoStabilization: Int? = null
        var previewOpticalStabilization: Int? = null
        var physicalId: String? = null
        var yuvSize = Size(0, 0)
        var previewSize = Size(0, 0)
        var rawSize: Size? = null
        var rawEnabled = false
        var balancedFallback = false
        var fallbackReason = ""
        var scanLocked = false
        var locksApplied = false
        var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        var logicalAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
        var fixedFocus = false
        var physicalRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()
        var focusReadySince = 0L
        var lastFocusActionAt = 0L
        var lastFocusHintAt = 0L
        var latestResult: CaptureResult? = null
        var observation: Observation? = null
        var consecutiveTimeouts = 0
        var draining = false
        var drainEpoch = 0
        var observationTick: Runnable? = null
        var lastObservationAt = 0L
        var scanEpoch = 0
        var lastTelemetryAt = 0L
        val diagnosticCounts = mutableMapOf<String,Int>()
        var rawRequest: RawRequest? = null
        var closed = false
        var opening = false
        var deviceClosed = false
        var releaseFinished = false
        val closeCallbacks = mutableListOf<() -> Unit>()
        fun active() = current === this && !closed
    }

    private class Observation(val epoch: Int) {
        val requestedAt = SystemClock.elapsedRealtime()
        var startedAt: Long? = null
        var timestamp: Long? = null
        val matcher = CameraFrameMatcher<Image, CaptureResult>({ it.timestamp }, { it[CaptureResult.SENSOR_TIMESTAMP] })
        var physicalId: String? = null
        var coordinateSpace: String? = null
    }

    private class RawRequest(val file: File, val complete: (String?) -> Unit) {
        var image: Image? = null
        var result: CaptureResult? = null
        @Volatile var writing = false
        @Volatile var completed = false
    }

    fun open() {
        wantedOpen = true
        main.post {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { beginOpen() }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { current?.let { transform(it) } }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stopRun()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
            if (texture.isAvailable) beginOpen()
        }
    }

    fun close(onClosed: (() -> Unit)? = null) {
        wantedOpen = false
        stopRun(onClosed)
    }

    private fun stopRun(onClosed: (() -> Unit)? = null) {
        isReady = false
        val run = synchronized(this) { (current ?: closing).also { current = null; closing = it } }
        if (run == null) { if (onClosed != null) main.post(onClosed); return }
        if (!run.handler.post {
            if (onClosed != null) {
                if (run.releaseFinished) main.post(onClosed) else run.closeCallbacks.add(onClosed)
            }
            release(run)
        } && onClosed != null) main.post(onClosed)
    }

    private fun beginOpen() {
        if (!wantedOpen || current != null || !texture.isAvailable) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Camera permission is required")
            return
        }
        val run = synchronized(this) {
            if (current != null || !wantedOpen) return
            if (closing != null) { reopenAfterClose = true; return }
            Run().also { current = it }
        }
        val surfaceTexture = texture.surfaceTexture ?: run {
            stopRun()
            return
        }
        run.surfaceTexture = surfaceTexture
        run.handler.post {
            try {
                chooseCamera(run)
                if (!run.active()) return@post
                surfaceTexture.setDefaultBufferSize(run.previewSize.width, run.previewSize.height)
                run.preview = Surface(surfaceTexture)
                run.yuv = ImageReader.newInstance(run.yuvSize.width, run.yuvSize.height, ImageFormat.YUV_420_888, 2).also { reader ->
                    reader.setOnImageAvailableListener({ receiveYuv(run, it) }, run.handler)
                }
                main.post { if (run.active()) transform(run) }
                run.opening = true
                manager.openCamera(run.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        run.opening = false
                        run.device = camera
                        if (!run.active()) { camera.close(); return }
                        configure(run, run.rawSize != null)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        run.opening = false; run.device = camera
                        if (!run.active()) camera.close() else fail(run, "Camera disconnected")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        run.opening = false; run.device = camera
                        if (!run.active()) camera.close() else fail(run, "Camera error $error; reopen the camera")
                    }
                    override fun onClosed(camera: CameraDevice) { run.deviceClosed = true; finishRelease(run) }
                }, run.handler)
            } catch (error: Exception) { run.opening = false; fail(run, "Cannot open camera: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun chooseCamera(run: Run) {
        val choices = availableCameras(context)
        val chosen = if (selectedCamera != null) choices.firstOrNull { it.id == selectedCamera }
            ?: error("Selected camera $selectedCamera is unavailable. Choose another camera.")
        else (choices.filter { it.physicalCameraId != null }.ifEmpty { choices }).minByOrNull {
            abs((it.equivalentFocalMm ?: 26.0) - 26.0)
        } ?: error("No rear camera supports matching preview and YUV streams")
        run.choiceId = chosen.id
        run.choiceLabel = chosen.label
        run.cameraId = chosen.cameraId
        run.physicalId = chosen.physicalCameraId
        run.logicalCharacteristics = manager.getCameraCharacteristics(run.cameraId)
        run.characteristics = run.physicalId?.let { manager.getCameraCharacteristics(it) } ?: run.logicalCharacteristics
        selectStreams(run)
    }

    private fun selectStreams(run: Run) {
        val map = run.characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: error("Missing stream configurations")
        val yuvSizes = (map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty() +
            map.getHighResolutionOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()).distinct()
        val (source, preview) = CameraGeometry.streams(yuvSizes.map { CameraGeometry.Dimensions(it.width, it.height) },
            map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { CameraGeometry.Dimensions(it.width, it.height) },
            if (detailMode && !run.balancedFallback) 12_600_000L else 2_100_000L)
        run.yuvSize = Size(source.width, source.height)
        run.previewSize = Size(preview.width, preview.height)
        val capabilities = run.characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        val rawAvailable = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty()
        run.rawSize = if (rawAvailable) rawSizes.maxByOrNull { it.width.toLong() * it.height } else null
        run.physicalRequestKeys = run.logicalCharacteristics.availablePhysicalCameraRequestKeys?.toSet().orEmpty()
        run.fixedFocus = run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] == 0f
        val physicalModes = run.characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val logicalModes = run.logicalCharacteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val overrideMode = run.physicalId != null && run.physicalRequestKeys.contains(CaptureRequest.CONTROL_AF_MODE)
        val effectiveModes = if (overrideMode) physicalModes else logicalModes.filter { physicalModes.contains(it) }.toIntArray()
        run.afMode = CameraFocusPolicy.mode(effectiveModes, run.fixedFocus)
        run.logicalAfMode = if (overrideMode) CameraFocusPolicy.mode(logicalModes,
            run.logicalCharacteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] == 0f) else run.afMode
        val sensorOrientation = run.characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val actualMode = if (run.yuvSize.width.toLong() * run.yuvSize.height > 2_100_000L) "detail" else "balanced"
        val minDuration = try { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, run.yuvSize) } catch (_: Exception) { 0L }
        val stallDuration = try { map.getOutputStallDuration(ImageFormat.YUV_420_888, run.yuvSize) } catch (_: Exception) { 0L }
        val details = JSONObject().put("cameraId", run.cameraId).put("physicalCameraId", run.physicalId ?: JSONObject.NULL)
            .put("physicalLensPinned", run.physicalId != null)
            .put("requestedCameraChoice", selectedCamera ?: "auto")
            .put("actualCameraChoice", run.choiceId).put("cameraLabel", run.choiceLabel)
            .put("logicalLensSwitchingAllowed", run.physicalId == null &&
                run.logicalCharacteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true)
            .put("previewScaleType", "fit-center").put("previewCaptureAspectMatched", true)
            .put("requestedLogicalCropRegion", rectJson(run.logicalCharacteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]))
            .put("selectedSensorActiveArray", rectJson(run.characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]))
            .put("sensorOrientation", sensorOrientation)
            .put("width", run.yuvSize.width).put("height", run.yuvSize.height)
            .put("yuvWidth", run.yuvSize.width).put("yuvHeight", run.yuvSize.height)
            .put("sourceWidth", run.yuvSize.width).put("sourceHeight", run.yuvSize.height)
            .put("requestedCaptureMode", if (detailMode) "detail" else "balanced")
            .put("actualCaptureMode", actualMode)
            .put("fallbackReason", run.fallbackReason.ifEmpty {
                if (detailMode && actualMode == "balanced") "No larger supported YUV size within the capture limits" else ""
            })
            .put("captureIntervalMs", 650).put("captureStrategy", "preview-only repeating; individual YUV still observations")
            .put("yuvMinFrameDurationNs", minDuration).put("yuvStallDurationNs", stallDuration)
            .put("hardwareLevel", run.characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL] ?: -1)
            .put("previewWidth", run.previewSize.width).put("previewHeight", run.previewSize.height)
            .put("rawAvailable", rawAvailable).put("rawEnabled", false)
            .put("rawWidth", run.rawSize?.width ?: 0).put("rawHeight", run.rawSize?.height ?: 0)
            .put("yuvSizes", sizesJson(yuvSizes)).put("rawSizes", sizesJson(rawSizes))
            .put("outputFormats", JSONArray(map.outputFormats.toList()))
            .put("sourceMode", "YUV_420_888")
            .put("rawUsage", "Optional supplemental DNG archive; not used for fusion")
            .put("aeLockSupported", run.characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true)
            .put("awbLockSupported", run.characteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true)
            .put("stabilization", "video and optical stabilization disabled where supported")
            .put("focusPolicy", if (CameraFocusPolicy.continuous(run.afMode)) "continuous; focused stationary observations only" else "triggered autofocus; bounded refocus")
            .put("focusMode", run.afMode).put("logicalFocusMode", run.logicalAfMode)
            .put("fixedFocus", run.fixedFocus)
            .put("minimumFocusDistance", run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: JSONObject.NULL)
            .put("focusDistanceCalibration", run.characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION] ?: JSONObject.NULL)
            .put("focusDistanceUnits", if (run.characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION] in listOf(
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED)) "diopters" else "uncalibrated")
            .put("closestFocusCmApproximate", CameraFocusPolicy.closestFocusCm(
                run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE],
                run.characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]) ?: JSONObject.NULL)
            .put("hyperfocalDistance", run.characteristics[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE] ?: JSONObject.NULL)
            .put("focusModes", JSONArray(physicalModes.toList())).put("logicalFocusModes", JSONArray(logicalModes.toList()))
            .put("maxAfRegions", run.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0)
            .put("logicalMaxAfRegions", run.logicalCharacteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0)
            .put("physicalRequestKeys", JSONArray(run.physicalRequestKeys.map { it.name }.sorted()))
            .put("physicalAfModeOverride", overrideMode)
            .put("physicalAfTriggerOverride", run.physicalId != null && run.physicalRequestKeys.contains(CaptureRequest.CONTROL_AF_TRIGGER))
            .put("physicalAfRegionsOverride", run.physicalId != null && run.physicalRequestKeys.contains(CaptureRequest.CONTROL_AF_REGIONS))
            .put("afRegionPolicy", "center half of selected sensor; matching logical region when physical override unavailable")
        publishInfo(run, details)
        diagnostic(run,"streams_selected",JSONObject(details.toString()))
    }

    private fun sizesJson(sizes: List<Size>) = JSONArray().also { out ->
        sizes.forEach { out.put(JSONObject().put("width", it.width).put("height", it.height)) }
    }

    private fun configure(run: Run, withRaw: Boolean) {
        if (!run.active()) return
        try {
            run.rawEnabled = withRaw
            run.raw?.close()
            run.raw = if (withRaw) run.rawSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).also {
                    it.setOnImageAvailableListener({ reader -> receiveRaw(run, reader) }, run.handler)
                }
            } else null
            val surfaces = listOfNotNull(run.preview, run.yuv?.surface, run.raw?.surface)
            val outputs = surfaces.map { surface -> OutputConfiguration(surface).also { output ->
                run.physicalId?.let { output.setPhysicalCameraId(it) }
            } }
            val executor = Executor { command -> run.handler.post(command) }
            run.device!!.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!run.active()) { session.close(); return }
                        run.session = session
                        try {
                            run.builder = requestBuilder(run, CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(run.preview!!)
                                settings(run, this)
                            }
                            repeat(run)
                            if (!run.active()) return
                            isReady = true
                            publishInfo(run, JSONObject(info.toString()).put("rawEnabled", run.rawEnabled))
                            diagnostic(run, "camera_ready", JSONObject(info.toString()))
                            main.post { if (run.active()) onReady(JSONObject(info.toString())) }
                            status(run, if (run.rawEnabled) "Camera ready · optional RAW archive available" else "Camera ready · YUV capture")
                        } catch (error: Exception) { fail(run, "Cannot start preview: ${error.message}") }
                    }
                    override fun onReady(session: CameraCaptureSession) {
                        if (!run.active() || run.session !== session || !run.draining) return
                        run.draining = false
                        diagnostic(run, "observation_drain_complete")
                        repeat(run)
                        scheduleObservation(run)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        configurationFailed(run, withRaw)
                    }
                }))
        } catch (error: Exception) {
            diagnostic(run,"stream_configuration_exception",exceptionDetail(error))
            configurationFailed(run, withRaw)
        }
    }

    private fun configurationFailed(run: Run, withRaw: Boolean) {
        if (!run.active()) return
        diagnostic(run,"stream_configuration_failed",JSONObject().put("withRaw",withRaw).put("physicalCameraId",run.physicalId ?: JSONObject.NULL).put("balancedFallback",run.balancedFallback))
        try {
            if (withRaw) {
                run.fallbackReason = "Camera rejected preview + YUV + RAW; RAW disabled"
                publishInfo(run, JSONObject(info.toString()).put("fallbackReason", run.fallbackReason)
                    .put("rawDisabledReason", run.fallbackReason))
                diagnostic(run,"stream_fallback",JSONObject().put("reason",run.fallbackReason))
                configure(run, false)
                return
            }
            if (detailMode && !run.balancedFallback && run.yuvSize.width.toLong() * run.yuvSize.height > 2_100_000L) {
                run.balancedFallback = true
                run.fallbackReason = "Selected camera rejected detail streams; keeping this lens with Quick capture up to 2.1 MP"
            } else if (selectedCamera == null && run.physicalId != null) {
                run.physicalId = null
                run.characteristics = run.logicalCharacteristics
                run.choiceId = "logical:${run.cameraId}"
                run.choiceLabel = "Logical camera ${run.cameraId} at fixed 1×"
                run.balancedFallback = false
                run.fallbackReason = "Auto lens streams rejected; using logical camera ${run.cameraId}. Choose a lens to pin it."
            } else {
                fail(run, "Selected ${run.choiceLabel} cannot provide matching preview and capture streams. Choose another camera.")
                return
            }
            diagnostic(run,"stream_fallback",JSONObject().put("reason",run.fallbackReason))
            run.yuv?.close()
            selectStreams(run)
            run.yuv = ImageReader.newInstance(run.yuvSize.width, run.yuvSize.height, ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ reader -> receiveYuv(run, reader) }, run.handler)
            }
            run.surfaceTexture?.setDefaultBufferSize(run.previewSize.width, run.previewSize.height)
            main.post { if (run.active()) transform(run) }
            configure(run, false)
        } catch (error: Exception) {
            fail(run, "Cannot configure capture fallback: ${error.message}")
        }
    }

    private fun requestBuilder(run: Run, template: Int): CaptureRequest.Builder {
        val physical = run.physicalId
        // Physical overrides require the builder to be initialized for those physical IDs.
        return if (physical != null && run.physicalRequestKeys.isNotEmpty())
            run.device!!.createCaptureRequest(template, setOf(physical))
        else run.device!!.createCaptureRequest(template)
    }

    private fun settings(run: Run, builder: CaptureRequest.Builder) {
        val c = run.logicalCharacteristics
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        builder.set(CaptureRequest.CONTROL_AF_MODE, run.logicalAfMode)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_AF_MODE, run.afMode)
        setAfTrigger(run, builder, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0)
            centerAfRegion(c)?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        if ((run.characteristics[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0)
            centerAfRegion(run.characteristics)?.let { setPhysicalKey(run, builder, CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        if (c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if (c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.contains(CaptureRequest.EDGE_MODE_OFF) == true)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        if (c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        if (Build.VERSION.SDK_INT >= 30) {
            val zoom = c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            if (zoom != null && zoom.contains(1f)) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            }
        }
        c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
        if (Build.VERSION.SDK_INT >= 31 && c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        applyPhysicalSettings(run, builder)
        applyLocks(run, builder)
    }

    private fun applyPhysicalSettings(run: Run, builder: CaptureRequest.Builder) {
        if (run.physicalId == null) return
        val c = run.characteristics
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        setPhysicalKey(run, builder, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_ENABLE_ZSL, false)
        c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.let {
            setPhysicalKey(run, builder, CaptureRequest.LENS_FOCAL_LENGTH, it)
        }
        if (c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true)
            setPhysicalKey(run, builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true)
            setPhysicalKey(run, builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if (c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.contains(CaptureRequest.EDGE_MODE_OFF) == true)
            setPhysicalKey(run, builder, CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        if (c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true)
            setPhysicalKey(run, builder, CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { setPhysicalKey(run, builder, CaptureRequest.SCALER_CROP_REGION, it) }
        if (Build.VERSION.SDK_INT >= 30 && c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.contains(1f) == true)
            setPhysicalKey(run, builder, CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        if (Build.VERSION.SDK_INT >= 31 && c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
            setPhysicalKey(run, builder, CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
    }

    private fun <T> setPhysicalKey(run: Run, builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        val physical = run.physicalId ?: return
        if (run.physicalRequestKeys.contains(key)) builder.setPhysicalCameraKey(key, value, physical)
    }

    private fun setAfTrigger(run: Run, builder: CaptureRequest.Builder, trigger: Int) {
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        setPhysicalKey(run, builder, CaptureRequest.CONTROL_AF_TRIGGER, trigger)
    }

    private fun centerAfRegion(c: CameraCharacteristics): MeteringRectangle? {
        val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
        return MeteringRectangle(Rect(active.left + active.width() / 4, active.top + active.height() / 4,
            active.right - active.width() / 4, active.bottom - active.height() / 4), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    fun refocus() {
        val run = current ?: return
        run.handler.post { if (run.active()) requestRefocus(run, "user") }
    }

    private fun requestRefocus(run: Run, reason: String) {
        if (!run.active() || run.fixedFocus || run.draining) return
        val builder = run.builder ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - run.lastFocusActionAt < 700) return
        run.lastFocusActionAt = now
        run.focusReadySince = 0
        try {
            // CANCEL releases a continuous AF lock; START is only appropriate for AUTO/MACRO.
            setAfTrigger(run, builder, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            run.session?.capture(builder.build(), callback(run), run.handler)
            if (CameraFocusPolicy.triggered(run.afMode)) {
                setAfTrigger(run, builder, CaptureRequest.CONTROL_AF_TRIGGER_START)
                run.session?.capture(builder.build(), callback(run), run.handler)
            }
            setAfTrigger(run, builder, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            repeat(run)
            diagnostic(run, "focus_refocus", JSONObject().put("reason", reason).put("mode", run.afMode)
                .put("physicalModeOverride", run.physicalId != null && run.physicalRequestKeys.contains(CaptureRequest.CONTROL_AF_MODE))
                .put("physicalTriggerOverride", run.physicalId != null && run.physicalRequestKeys.contains(CaptureRequest.CONTROL_AF_TRIGGER)))
        } catch (error: Exception) {
            setAfTrigger(run, builder, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            diagnostic(run, "focus_refocus_exception", exceptionDetail(error))
        }
    }

    private fun updateFocus(run: Run, result: CaptureResult) {
        val now = SystemClock.elapsedRealtime()
        val mode = result[CaptureResult.CONTROL_AF_MODE] ?: run.afMode
        val af = result[CaptureResult.CONTROL_AF_STATE]
        val settled = CameraFocusPolicy.settled(mode, af, result[CaptureResult.LENS_STATE], run.fixedFocus)
        if (settled) { if (run.focusReadySince == 0L) run.focusReadySince = now } else run.focusReadySince = 0
        val idle = run.observation == null && run.rawRequest == null
        if (idle && !run.fixedFocus && now - run.lastFocusActionAt >= 3000 &&
            (CameraFocusPolicy.triggered(run.afMode) && (run.scanLocked || !settled) ||
                CameraFocusPolicy.continuous(run.afMode) && af in listOf(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)))
            requestRefocus(run, "resume_autofocus")
        if (!settled && now - run.lastFocusActionAt > 1500 && now - run.lastFocusHintAt > 4000 &&
            af in listOf(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED)) {
            run.lastFocusHintAt = now
            status(run, if (CameraFocusPolicy.nearLimit(result[CaptureResult.LENS_FOCUS_DISTANCE],
                run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]))
                "Near this lens's closest focus setting. Move farther away or choose another lens; Refocus retries this lens."
                else "Focus not found. Hold still briefly, add light, or tap Refocus.")
        }
    }

    private fun applyLocks(run: Run, builder: CaptureRequest.Builder) {
        if (run.logicalCharacteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, run.scanLocked && run.locksApplied)
        if (run.logicalCharacteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, run.scanLocked && run.locksApplied)
        if (run.characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true)
            setPhysicalKey(run, builder, CaptureRequest.CONTROL_AE_LOCK, run.scanLocked && run.locksApplied)
        if (run.characteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true)
            setPhysicalKey(run, builder, CaptureRequest.CONTROL_AWB_LOCK, run.scanLocked && run.locksApplied)
    }

    fun lockForScan(locked: Boolean) {
        val run = current ?: return
        run.handler.post {
            if (!run.active()) return@post
            run.scanEpoch++
            diagnostic(run,"scan_lock_requested",JSONObject().put("locked",locked))
            run.scanLocked = locked
            run.locksApplied = false
            run.builder?.let { applyLocks(run, it) }
            // AE/AWB stay locked during the scan; autofocus must follow natural distance changes.
            requestRefocus(run, "scan_${if (locked) "start" else "stop"}")
            if (locked) {
                maybeLock(run, run.latestResult)
                scheduleObservation(run)
            }
            repeat(run)
        }
    }

    private fun maybeLock(run: Run, result: CaptureResult?) {
        if (!run.scanLocked || run.locksApplied) return
        if (result == null) return
        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
        val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
        val aeReady = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        val awbReady = awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        if (!(aeReady && awbReady)) { telemetry(run,"waiting_for_ae_awb",result); return }
        run.locksApplied = true
        diagnostic(run,"scan_locks_applied",JSONObject().put("aeState",ae ?: JSONObject.NULL).put("awbState",awb ?: JSONObject.NULL))
        val builder = run.builder ?: return
        applyLocks(run, builder)
        repeat(run)
    }

    private fun repeat(run: Run) {
        if (run.draining) return
        try { run.session?.setRepeatingRequest(run.builder!!.build(), callback(run), run.handler) }
        catch (error: Exception) { if (run.active()) fail(run, "Preview stopped: ${error.message}") }
    }

    private fun scheduleObservation(run: Run) {
        if (!run.active() || !run.scanLocked || run.draining || run.observation != null || run.observationTick != null) return
        val tick = Runnable {
            run.observationTick = null
            if (!run.active() || !run.scanLocked || run.draining || run.observation != null) return@Runnable
            val previewResult = run.latestResult
            val previewReady = previewResult != null && metadata(run,
                previewResult[CaptureResult.SENSOR_TIMESTAMP] ?: 0L, previewResult, true).optBoolean("captureReady")
            if (!previewReady) {
                // Leave preview AF uninterrupted; a still request cannot repair an unsettled lens.
                run.lastObservationAt = SystemClock.elapsedRealtime()
                telemetry(run, "observation_deferred_until_settled", previewResult)
                scheduleObservation(run)
                return@Runnable
            }
            val pending = Observation(run.scanEpoch)
            run.observation = pending
            run.lastObservationAt = SystemClock.elapsedRealtime()
            try {
                val request = requestBuilder(run, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(run.yuv!!.surface)
                    settings(run, this)
                    setTag(pending)
                }
                run.session!!.capture(request.build(), callback(run), run.handler)
                diagnostic(run,"observation_requested",JSONObject().put("requestedAtMs",pending.requestedAt))
                run.handler.postDelayed({
                    if (run.active() && run.observation === pending) {
                        observationTimeout(run, pending)
                    }
                }, 4000)
            } catch (error: Exception) {
                fail(run, "Cannot capture observation: ${error.message}")
            }
        }
        run.observationTick = tick
        val delay = (650L - (SystemClock.elapsedRealtime() - run.lastObservationAt)).coerceAtLeast(0L)
        run.handler.postDelayed(tick, delay)
    }

    private fun callback(run: Run) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            (request.tag as? Observation)?.takeIf { run.observation === it }?.let {
                it.timestamp=timestamp; it.startedAt=SystemClock.elapsedRealtime()
            }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!run.active()) return
            val physicalResult = run.physicalId?.let { result.physicalCameraResults[it] }
            val actual: CaptureResult = physicalResult ?: result
            val coordinateSpace = if (physicalResult != null) "physical:${run.physicalId}" else "logical:${run.cameraId}"
            run.latestResult = actual
            run.latestCoordinateSpace = coordinateSpace
            if (request.tag !is Observation && request.tag !is RawRequest) {
                run.previewCrop = actual[CaptureResult.SCALER_CROP_REGION]?.let { Rect(it) }
                run.previewCoordinateSpace = coordinateSpace
                if (Build.VERSION.SDK_INT >= 30) run.previewZoom = actual[CaptureResult.CONTROL_ZOOM_RATIO]
                run.previewPhysicalId = run.physicalId ?: result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
                run.previewVideoStabilization = actual[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]
                run.previewOpticalStabilization = actual[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]
            }
            updateFocus(run, actual)
            telemetry(run, if (run.scanLocked) "capture_result" else "preview_focus", actual)
            (request.tag as? Observation)?.takeIf { run.observation === it }?.let {
                it.matcher.receiveResult(actual)
                it.physicalId = run.physicalId ?: result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
                it.coordinateSpace = coordinateSpace
                deliverObservation(run, it)
            }
            (request.tag as? RawRequest)?.takeIf { run.rawRequest === it }?.let {
                it.result = actual
                writeRawWhenReady(run, it)
            }
            maybeLock(run, actual)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            diagnostic(run,"capture_failed",JSONObject().put("reason",failure.reason).put("wasImageCaptured",failure.wasImageCaptured()).put("observation",request.tag is Observation).put("frameNumber",failure.frameNumber))
            (request.tag as? RawRequest)?.let { completeRaw(run, it, "RAW capture failed (${failure.reason})") }
            (request.tag as? Observation)?.takeIf { run.observation === it }?.let {
                finishObservation(run, it)
                status(run, "Observation skipped; camera capture failed (${failure.reason})")
            }
        }
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            diagnostic(run,"capture_buffer_lost",JSONObject().put("frameNumber",frameNumber).put("observation",request.tag is Observation))
            (request.tag as? Observation)?.takeIf { run.observation === it }?.let { finishObservation(run, it) }
            (request.tag as? RawRequest)?.let { completeRaw(run, it, "RAW image buffer lost") }
        }
    }

    private fun receiveYuv(run: Run, reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (error: IllegalStateException) {
            telemetry(run,"image_reader_exception",run.latestResult); null
        } ?: return
        val pending = run.observation
        if (!run.active() || pending == null) {
            telemetry(run,"image_without_request",run.latestResult)
            image.close(); return
        }
        if (pending.timestamp == null) diagnostic(run,"image_arrived_before_started",JSONObject()
            .put("imageTimestampNs",image.timestamp).put("requestedAtMs",pending.requestedAt))
        // ImageReader and capture callbacks have independent arrival order. The tagged
        // result's SENSOR_TIMESTAMP is authoritative; onCaptureStarted is diagnostic only.
        pending.matcher.receiveImage(image)
        deliverObservation(run, pending)
    }

    private fun deliverObservation(run: Run, pending: Observation) {
        val candidate = pending.matcher.candidateTimestamp
        if (candidate != null && pending.matcher.hasResult && candidate != pending.matcher.expectedTimestamp)
            diagnostic(run,"image_timestamp_discarded",JSONObject().put("imageTimestampNs",candidate)
                .put("resultTimestampNs",pending.matcher.expectedTimestamp ?: JSONObject.NULL)
                .put("captureStartedTimestampNs",pending.timestamp ?: JSONObject.NULL)
                .put("requestedAtMs",pending.requestedAt))
        val (image, result) = pending.matcher.takeMatched() ?: return
        run.consecutiveTimeouts = 0
        if (run.active() && run.scanLocked && pending.epoch == run.scanEpoch) {
            val captureMetadata=metadata(run,image.timestamp,result,true,pending.physicalId,pending.coordinateSpace)
                .put("observationRequestedAtMs",pending.requestedAt)
                .put("observationStartedAtMs",pending.startedAt ?: JSONObject.NULL)
                .put("captureStartedTimestampNs",pending.timestamp ?: JSONObject.NULL)
                .put("deliveryLatencyMs",SystemClock.elapsedRealtime()-pending.requestedAt)
            diagnostic(run,"observation_delivered",JSONObject().put("metadata",captureMetadata))
            try { onFrame(image,captureMetadata) }
            catch (error: Exception) {
                diagnostic(run,"frame_consumer_exception",exceptionDetail(error))
                image.close()
            }
        } else {
            telemetry(run,"observation_inactive",result)
            image.close()
        }
        finishObservation(run, pending)
    }

    private fun finishObservation(run: Run, pending: Observation) {
        pending.matcher.close()
        if (run.observation === pending) run.observation = null
        scheduleObservation(run)
    }

    private fun observationTimeout(run: Run, pending: Observation) {
        run.consecutiveTimeouts++
        val action = CameraTimeoutPolicy.action(run.consecutiveTimeouts, pending.matcher.hasResult)
        diagnostic(run,"observation_timeout",JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-pending.requestedAt)
            .put("consecutiveTimeouts",run.consecutiveTimeouts).put("action",action.name.lowercase())
            .put("resultCompleted",pending.matcher.hasResult)
            .put("imageTimestampNs",pending.matcher.candidateTimestamp ?: JSONObject.NULL)
            .put("resultTimestampNs",pending.matcher.expectedTimestamp ?: JSONObject.NULL)
            .put("captureStartedTimestampNs",pending.timestamp ?: JSONObject.NULL))
        pending.matcher.close()
        run.observation = null
        if (action == CameraTimeoutPolicy.Action.FAIL) {
            fail(run,"Camera observations failed three times; reopen the camera")
            return
        }
        run.lastObservationAt = SystemClock.elapsedRealtime() // Bounded recovery backoff.
        status(run,"Observation skipped; camera is recovering. Keep the print in view.")
        if (action == CameraTimeoutPolicy.Action.RETRY) {
            scheduleObservation(run)
            return
        }
        // Without a completed result the old HAL request may still be queued.
        // Do not submit another observation until onReady confirms an empty pipeline.
        run.draining = true
        val drain = ++run.drainEpoch
        try {
            run.session?.stopRepeating()
            run.session?.abortCaptures()
            diagnostic(run,"observation_drain_requested")
            run.handler.postDelayed({
                if (run.active() && run.draining && run.drainEpoch == drain)
                    fail(run,"Camera did not recover its capture pipeline; reopen the camera")
            },2000)
        } catch(error:Exception) {
            diagnostic(run,"observation_drain_exception",exceptionDetail(error))
            fail(run,"Camera recovery failed; reopen the camera")
        }
    }

    private fun metadata(run: Run, timestamp: Long, result: CaptureResult?, matched: Boolean, actualPhysicalId: String? = run.physicalId, coordinateSpace: String? = run.latestCoordinateSpace): JSONObject {
        val orientation = run.characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val displayDegrees = (texture.display?.rotation ?: Surface.ROTATION_0) * 90
        val meta = JSONObject().put("timestampNs", timestamp).put("sensorTimestampNs", timestamp)
            .put("metadataMatched", matched).put("cameraId", run.cameraId)
            .put("physicalCameraId", run.physicalId ?: JSONObject.NULL)
            .put("sensorOrientation", orientation).put("rotationDegrees", (orientation - displayDegrees + 360) % 360)
            .put("width", run.yuvSize.width).put("height", run.yuvSize.height)
            .put("actualCaptureMode", info.optString("actualCaptureMode")).put("fallbackReason", info.optString("fallbackReason"))
            .put("format", "YUV_420_888").put("scanLocked", run.scanLocked).put("locksApplied", run.locksApplied)
            .put("requestedCameraChoice", selectedCamera ?: "auto").put("actualCameraChoice", run.choiceId)
            .put("cameraLabel", run.choiceLabel).put("previewCaptureAspectMatched", true)
            .put("previewViewport", run.viewport?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("sensorCropRegion", rectJson(result?.get(CaptureResult.SCALER_CROP_REGION)))
            .put("previewSensorCropRegion", rectJson(run.previewCrop))
            .put("requestedLogicalCropRegion", rectJson(run.logicalCharacteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]))
        // A logical fallback result and a physical result use different sensor coordinates.
        // Missing physical telemetry leaves this check unknown; it must not reject a valid frame.
        val comparable = coordinateSpace != null && coordinateSpace == run.previewCoordinateSpace
        val cropMatches = if (comparable) result?.get(CaptureResult.SCALER_CROP_REGION)?.let { crop -> run.previewCrop?.let { it == crop } } else null
        val zoom = if (Build.VERSION.SDK_INT >= 30) result?.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        val zoomMatches = if (comparable) zoom?.let { captureZoom -> run.previewZoom?.let { abs(it - captureZoom) < .0001f } } else null
        val physicalMatches = actualPhysicalId?.let { id -> run.previewPhysicalId?.let { it == id } }
        val video = result?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        val optical = result?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        val stabilizationMatches = (video == null || run.previewVideoStabilization == null || video == run.previewVideoStabilization) &&
            (optical == null || run.previewOpticalStabilization == null || optical == run.previewOpticalStabilization)
        val fieldOfViewMatches = cropMatches != false && zoomMatches != false && physicalMatches != false && stabilizationMatches
        meta.put("sensorCropMatchesPreview", cropMatches ?: JSONObject.NULL)
            .put("sensorCropCoordinateSpace", coordinateSpace ?: JSONObject.NULL)
            .put("previewCropCoordinateSpace", run.previewCoordinateSpace ?: JSONObject.NULL)
            .put("sensorCropComparable", comparable)
            .put("fieldOfViewVerified", cropMatches == true && (zoomMatches == true || Build.VERSION.SDK_INT < 30) && physicalMatches != false && stabilizationMatches)
            .put("zoomRatio", zoom ?: JSONObject.NULL).put("previewZoomRatio", run.previewZoom ?: JSONObject.NULL)
            .put("zoomMatchesPreview", zoomMatches ?: JSONObject.NULL)
            .put("actualPhysicalCameraId", actualPhysicalId ?: JSONObject.NULL)
            .put("previewPhysicalCameraId", run.previewPhysicalId ?: JSONObject.NULL)
            .put("physicalLensMatchesPreview", physicalMatches ?: JSONObject.NULL)
            .put("previewVideoStabilizationMode", run.previewVideoStabilization ?: JSONObject.NULL)
            .put("previewOpticalStabilizationMode", run.previewOpticalStabilization ?: JSONObject.NULL)
            .put("stabilizationMatchesPreview", stabilizationMatches)
            .put("fieldOfViewReason", if (fieldOfViewMatches) "" else "Preview and capture field of view differ; choose a physical lens")
        val ae = result?.get(CaptureResult.CONTROL_AE_STATE)
        val awb = result?.get(CaptureResult.CONTROL_AWB_STATE)
        val af = result?.get(CaptureResult.CONTROL_AF_STATE)
        val aeLockSupported = run.characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE] == true
        val awbLockSupported = run.characteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true
        val aeLocked = result?.get(CaptureResult.CONTROL_AE_LOCK)
        val awbLocked = result?.get(CaptureResult.CONTROL_AWB_LOCK)
        val aeSettled = if (aeLockSupported) aeLocked == true || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            else ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
        val awbSettled = if (awbLockSupported) awbLocked == true || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
            else awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        val lensState = result?.get(CaptureResult.LENS_STATE)
        val actualAfMode = result?.get(CaptureResult.CONTROL_AF_MODE) ?: run.afMode
        val settledState = CameraFocusPolicy.settled(actualAfMode, af, lensState, run.fixedFocus)
        val now = SystemClock.elapsedRealtime()
        val focusSettled = actualAfMode == run.afMode && settledState && run.focusReadySince != 0L && now - run.focusReadySince >= 150 && now - run.lastFocusActionAt >= 200
        meta.put("captureReady", matched && run.scanLocked && run.locksApplied && aeSettled && awbSettled && focusSettled && fieldOfViewMatches)
            .put("focusSettled", focusSettled).put("focusFallback", false)
            .put("controlTelemetryFallback", false)
            .put("focusMode", actualAfMode).put("requestedFocusMode", run.afMode)
            .put("focusModeMatchesRequest", actualAfMode == run.afMode)
            .put("focusPolicy", if (CameraFocusPolicy.continuous(run.afMode)) "continuous" else "triggered")
            .put("focusReadyForMs", if (run.focusReadySince == 0L) 0 else now - run.focusReadySince)
            .put("lensState", lensState ?: JSONObject.NULL)
            .put("minimumFocusDistance", run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: JSONObject.NULL)
            .put("focusDistanceCalibration", run.characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION] ?: JSONObject.NULL)
            .put("focusDistanceUnits", if (run.characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION] in listOf(
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED)) "diopters" else "uncalibrated")
            .put("nearClosestFocusSetting", CameraFocusPolicy.nearLimit(result?.get(CaptureResult.LENS_FOCUS_DISTANCE),
                run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]))
            .put("focusHint", if (focusSettled) "" else if (settledState) "Waiting for focus to settle. Hold still briefly."
                else if (CameraFocusPolicy.nearLimit(result?.get(CaptureResult.LENS_FOCUS_DISTANCE),
                run.characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]))
                "Near this lens's closest focus setting. Move farther from the print or choose another lens."
                else "Waiting for sharp focus. Hold still briefly or tap Refocus.")
        result?.let {
            meta.put("resultTimestampNs", it[CaptureResult.SENSOR_TIMESTAMP] ?: JSONObject.NULL)
            meta.put("exposureTimeNs", it[CaptureResult.SENSOR_EXPOSURE_TIME] ?: JSONObject.NULL)
            meta.put("iso", it[CaptureResult.SENSOR_SENSITIVITY] ?: JSONObject.NULL)
            meta.put("frameDurationNs", it[CaptureResult.SENSOR_FRAME_DURATION] ?: JSONObject.NULL)
            meta.put("focusDistanceDiopters", it[CaptureResult.LENS_FOCUS_DISTANCE] ?: JSONObject.NULL)
            meta.put("aeState", it[CaptureResult.CONTROL_AE_STATE] ?: JSONObject.NULL)
            meta.put("awbState", it[CaptureResult.CONTROL_AWB_STATE] ?: JSONObject.NULL)
            meta.put("afState", it[CaptureResult.CONTROL_AF_STATE] ?: JSONObject.NULL)
            meta.put("videoStabilizationMode", it[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE] ?: JSONObject.NULL)
            meta.put("opticalStabilizationMode", it[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE] ?: JSONObject.NULL)
            meta.put("lensFocalLengthMm", it[CaptureResult.LENS_FOCAL_LENGTH] ?: JSONObject.NULL)
            meta.put("afRegions", JSONArray().also { regions -> it[CaptureResult.CONTROL_AF_REGIONS]?.take(5)?.forEach { region ->
                regions.put(JSONObject().put("rect", rectJson(region.rect)).put("weight", region.meteringWeight))
            } })
            meta.put("focusRange", it[CaptureResult.LENS_FOCUS_RANGE]?.let { range -> JSONArray(listOf(range.first, range.second)) } ?: JSONObject.NULL)
            meta.put("aeLocked", it[CaptureResult.CONTROL_AE_LOCK] ?: false)
            meta.put("awbLocked", it[CaptureResult.CONTROL_AWB_LOCK] ?: false)
            it[CaptureResult.COLOR_CORRECTION_GAINS]?.let { gains ->
                meta.put("whiteBalanceGains", JSONArray(listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)))
            }
            it[CaptureResult.COLOR_CORRECTION_TRANSFORM]?.let { matrix ->
                val entries = JSONArray()
                for (row in 0..2) for (column in 0..2) entries.put(matrix.getElement(column, row).toDouble())
                meta.put("colorCorrectionTransform", entries)
            }
        }
        return meta
    }

    fun captureRaw(destination: File, onComplete: (String?) -> Unit) {
        val run = current
        if (run == null) { main.post { onComplete("Camera is not open") }; return }
        run.handler.post {
            if (!run.active() || run.draining || !run.rawEnabled || run.raw == null) { main.post { onComplete("RAW capture is unavailable on this camera session") }; return@post }
            if (run.rawRequest != null) { main.post { onComplete("A RAW capture is already in progress") }; return@post }
            val pending = RawRequest(destination, onComplete)
            run.rawRequest = pending
            try {
                val request = requestBuilder(run, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(run.raw!!.surface)
                    settings(run, this)
                    setTag(pending)
                }
                run.session!!.capture(request.build(), callback(run), run.handler)
                run.handler.postDelayed({
                    if (run.rawRequest === pending && !pending.writing) completeRaw(run, pending, "RAW capture timed out")
                }, 8000)
            } catch (error: Exception) { completeRaw(run, pending, "RAW capture failed: ${error.message}") }
        }
    }

    private fun receiveRaw(run: Run, reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        val pending = run.rawRequest
        if (!run.active() || pending == null || pending.writing) { image.close(); return }
        pending.image?.let { older ->
            // A capture that timed out may deliver a late buffer during a subsequent request.
            if (older.timestamp >= image.timestamp) { image.close(); return }
            older.close()
        }
        pending.image = image
        writeRawWhenReady(run, pending)
    }

    private fun writeRawWhenReady(run: Run, pending: RawRequest) {
        val image = pending.image ?: return
        val result = pending.result ?: return
        if (pending.writing) return
        if (image.timestamp != result[CaptureResult.SENSOR_TIMESTAMP]) {
            pending.image = null
            image.close()
            return
        }
        pending.writing = true
        // DNG encoding and filesystem work must never block the camera or UI callbacks.
        Thread({
            var error: String? = null
            val temporary = File(pending.file.parentFile, ".${pending.file.name}.${image.timestamp}.tmp")
            try {
                check(pending.file.parentFile?.isDirectory == true || pending.file.parentFile?.mkdirs() == true) { "Cannot create RAW directory" }
                DngCreator(run.characteristics, result).use { creator ->
                    val sensor = run.characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
                    val degrees = (sensor - (texture.display?.rotation ?: 0) * 90 + 360) % 360
                    creator.setOrientation(when (degrees) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                    creator.setDescription("Luma Scan supplemental sensor archive; RAW is not used by fusion")
                    FileOutputStream(temporary).use { stream ->
                        creator.writeImage(stream, image)
                        stream.fd.sync()
                    }
                }
                check(temporary.renameTo(pending.file)) { "Cannot save RAW file" }
            } catch (failure: Exception) { error = "Cannot save RAW: ${failure.message}" }
            finally { image.close(); temporary.delete() }
            val failure = error
            // The camera handler can already have stopped during activity teardown.
            if (!run.handler.post { completeRaw(run, pending, failure) }) main.post {
                if (!pending.completed) { pending.completed = true; pending.complete(failure) }
            }
        }, "LumaDng").start()
    }

    private fun completeRaw(run: Run, pending: RawRequest, error: String?) {
        if (pending.completed) return
        pending.completed = true
        diagnostic(run,"raw_complete",JSONObject().put("file",pending.file.name).put("error",error ?: JSONObject.NULL))
        if (!pending.writing) pending.image?.close()
        pending.image = null
        if (run.rawRequest === pending) run.rawRequest = null
        main.post { pending.complete(error) }
        if (run.closed) finishRelease(run)
    }

    private fun transform(run: Run) {
        if (!run.active() || texture.width == 0 || run.previewSize.width == 0) return
        val view = RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
        val sensor = run.characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val displayDegrees = (texture.display?.rotation ?: Surface.ROTATION_0) * 90
        val geometry = CameraGeometry.fitCenter(texture.width, texture.height,
            CameraGeometry.Dimensions(run.previewSize.width, run.previewSize.height), sensor, displayDegrees)
        val matrix = Matrix()
        matrix.setScale(geometry.naturalWidth / view.width(), geometry.naturalHeight / view.height(), view.centerX(), view.centerY())
        matrix.postRotate(-displayDegrees.toFloat(), view.centerX(), view.centerY())
        matrix.postScale(geometry.scale, geometry.scale, view.centerX(), view.centerY())
        run.viewport = JSONObject().put("scaleType", "fit-center").put("fullCapturedAreaVisible", true)
            .put("viewWidth", texture.width).put("viewHeight", texture.height)
            .put("sensorOrientation", sensor).put("displayRotationDegrees", displayDegrees)
            .put("contentLeft", geometry.left).put("contentTop", geometry.top)
            .put("contentRight", geometry.right).put("contentBottom", geometry.bottom)
            .put("previewWidth", run.previewSize.width).put("previewHeight", run.previewSize.height)
            .put("captureWidth", run.yuvSize.width).put("captureHeight", run.yuvSize.height)
        val details = JSONObject(info.toString()).put("previewViewport", JSONObject(run.viewport.toString()))
        publishInfo(run, details)
        diagnostic(run, "preview_viewport", JSONObject(run.viewport.toString()))
        texture.setTransform(matrix)
    }

    private fun rectJson(rect: Rect?): Any = rect?.let {
        JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)
    } ?: JSONObject.NULL

    private fun exceptionDetail(error:Exception)=JSONObject()
        .put("exception",error.javaClass.name).put("message",error.message ?: JSONObject.NULL)
        .put("stack",error.stackTraceToString().take(6000))

    /** The camera handler aggregates repeating preview telemetry; consumers must queue any disk I/O. */
    private fun telemetry(run:Run,reason:String,result:CaptureResult?,force:Boolean=false) {
        try {
            run.diagnosticCounts[reason]=(run.diagnosticCounts[reason] ?: 0)+1
            val now=SystemClock.elapsedRealtime()
            if(!force && now-run.lastTelemetryAt<2000)return
            run.lastTelemetryAt=now
            val state=JSONObject().put("counts",JSONObject(run.diagnosticCounts.toMap()))
                .put("telemetryOnly",true).put("captureOrigin","camera result; no corresponding source image")
                .put("observationPending",run.observation!=null)
                .put("metadata",metadata(run,result?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,result,true))
            run.diagnosticCounts.clear()
            diagnostic(run,"camera_state",state)
        } catch(error:Exception) { android.util.Log.w("LumaDiagnostics","Camera telemetry unavailable",error) }
    }


    private fun diagnostic(run:Run,event:String,detail:JSONObject=JSONObject()) {
        try {
            onDiagnostic(JSONObject().put("event",event).put("detail",detail)
                .put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
                .put("cameraId",run.cameraId).put("physicalCameraId",run.physicalId ?: JSONObject.NULL)
                .put("scanEpoch",run.scanEpoch).put("scanLocked",run.scanLocked).put("locksApplied",run.locksApplied))
        } catch(error:Exception) { android.util.Log.w("LumaDiagnostics","Camera diagnostic callback failed",error) }
    }

    private fun status(run: Run, message: String) { main.post { if (run.active()) onStatus(message) } }

    private fun publishInfo(run: Run, details: JSONObject) {
        synchronized(this) { if (current === run && !run.closed) info = details }
    }

    private fun fail(run: Run, message: String) {
        if (!run.active()) return
        telemetry(run,"camera_failure",run.latestResult,true)
        diagnostic(run,"camera_exception",JSONObject().put("message",message))
        synchronized(this) { if (current === run) { current = null; closing = run } }
        main.post { onStatus(message) }
        release(run)
    }

    private fun release(run: Run) {
        if (run.closed) return
        telemetry(run,"camera_closing",run.latestResult,true)
        run.closed = true
        isReady = false
        run.observationTick?.let { run.handler.removeCallbacks(it) }
        run.observationTick = null
        run.observation?.matcher?.close()
        run.observation = null
        try { run.session?.stopRepeating() } catch (_: Exception) {}
        run.session?.close()
        run.rawRequest?.let {
            if (!it.writing) completeRaw(run, it, "Camera closed before RAW capture completed")
        }
        run.device?.close()
        if (run.device == null && !run.opening) { run.deviceClosed = true; finishRelease(run) }
    }

    private fun finishRelease(run: Run) {
        if (!run.closed || !run.deviceClosed || run.releaseFinished) return
        if (run.rawRequest?.let { it.writing && !it.completed } == true) return
        run.releaseFinished = true
        diagnostic(run,"camera_closed")
        run.yuv?.close()
        run.raw?.close()
        run.preview?.release()
        val reopen = synchronized(this) {
            if (closing === run) closing = null
            (reopenAfterClose && wantedOpen).also { reopenAfterClose = false }
        }
        run.closeCallbacks.forEach { main.post(it) }
        run.closeCallbacks.clear()
        // Release callbacks run before any surface-driven reopen creates another device.
        if (reopen) main.post { beginOpen() }
        run.thread.quitSafely()
    }
}
