// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.*
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.pow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val ink = Color.rgb(16,20,16)
    private val panel = Color.rgb(28,35,28)
    private val lime = Color.rgb(197,247,123)
    private val paper = Color.rgb(242,239,226)
    private val muted = Color.rgb(167,179,160)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val submissionLock = Any()
    @Volatile private var recording = false
    @Volatile private var finishing = false
    @Volatile private var active = false
    @Volatile private var session: File? = null
    private lateinit var store: SessionStore
    private lateinit var root: LinearLayout
    private var texture: TextureView? = null
    private var camera: ScanCamera? = null
    @Volatile private var cameraEpoch = 0
    private var cameraReady = false
    private var cameraClosing = false
    private var detailMode = true
    private var cameraChoiceId: String? = null
    private var cameraChoiceButton: TextView? = null
    private var refocusButton: TextView? = null
    private class CameraTrace {
        var directory: File? = null
        val recent = ArrayDeque<JSONObject>() // Worker-owned: max32 metadata packets, max60 seconds.
    }
    private var cameraTrace: CameraTrace? = null
    private var cameraDescription = "Opening camera…"
    private var detailButton: TextView? = null
    private var mode = "live"
    private var generation = 0
    private var lastFrame = 0L
    private var lastSettlingHint = 0L
    private var rawBusy = false
    @Volatile private var captureFailure: String? = null
    private var accepted = 0
    private var rotation = 0
    private var bestSharp = -1.0
    private var bestFile: File? = null
    private var firstFile: File? = null
    private var image: Bitmap? = null
    private var reviewRaster: File? = null
    private var scanWorkspace: File? = null
    private var reviewComparisonRequest = 0
    private var reviewSourceCount = 0
    private var reviewCornerFallback = false
    private var resultWidth = 0
    private var resultHeight = 0
    private var resultDisplayRotation = 0
    private var renderMetrics = doubleArrayOf()
    private var crop: CropView? = null
    private var ratio: Float? = null
    private var status: TextView? = null
    private var statistics: TextView? = null
    private var captureButton: TextView? = null
    private var preview: ImageView? = null
    private var progress: ProgressBar? = null
    private var exportSession: File? = null
    @Volatile private var diagnosticRunId = ""
    @Volatile private var cameraDiagnosticSession: File? = null
    private var attemptId = 0
    private var rejectedPreviews = 0
    private var lastRejectedPreviewAttempt = -5
    private val rejectedReasons = mutableSetOf<String>()
    private val attemptCounts = mutableMapOf<String,Int>()
    private val diagnosticPending = AtomicInteger(0)
    private val diagnosticDropped = AtomicInteger(0)
    private val frameDrops = mutableMapOf<String,Int>()
    private var lastDropLogAt = 0L
    @Volatile private var stagedSession: File? = null
    private var colorGains = floatArrayOf(1f,1f,1f)
    private val pendingUi = ArrayDeque<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        store = SessionStore(this)
        detailMode = getPreferences(MODE_PRIVATE).getBoolean("detailMode",true)
        cameraChoiceId = getPreferences(MODE_PRIVATE).getString("cameraChoice",null)
        showLive()
    }

    override fun onResume() {
        super.onResume()
        active = true
        val pending=pendingUi.toList()
        pendingUi.clear()
        pending.forEach { it.invoke() }
        if (mode == "live") ensureCamera()
    }

    override fun onPause() {
        active = false
        queueDiagnostic(session,"activity_pause",JSONObject().put("recording",recording).put("finishing",finishing).put("runId",diagnosticRunId))
        if (recording) finishCapture()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        recording = false
        closeCamera()
        worker.shutdown()
        super.onDestroy()
    }

    private fun ui(action: () -> Unit) = runOnUiThread {
        if (!isDestroyed && !isFinishing) {
            if(active) action() else pendingUi.addLast(action)
        }
    }
    private fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float = 14f, color: Int = paper): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): TextView = label(text,14f,if(primary)ink else paper).apply {
        gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        background = rounded(if(primary)lime else panel,28)
        setPadding(dp(16),dp(15),dp(16),dp(15))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
    private fun base(subtitle: String): LinearLayout {
        generation++
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(ink)
            setPadding(dp(20),dp(12),dp(20),dp(12))
            fitsSystemWindows = true
        }
        root.setOnApplyWindowInsetsListener { view,insets ->
            view.setPadding(dp(20)+insets.systemWindowInsetLeft,dp(12)+insets.systemWindowInsetTop,
                dp(20)+insets.systemWindowInsetRight,dp(12)+insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("LUMA / SCAN",17f).apply { letterSpacing = .13f; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL) },LinearLayout.LayoutParams(0,dp(42),1f))
        header.addView(label("●  OFFLINE",10f,lime).apply { letterSpacing = .08f })
        root.addView(header)
        root.addView(label(subtitle,12f,muted).apply { setPadding(0,0,0,dp(12)) })
        return root
    }
    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, v -> addView(v,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { if(index>0)leftMargin=dp(8) }) }
    }
    private fun gap(height: Int=12) { root.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }

    private fun showLive() {
        mode = "live"
        releaseReview()
        base("Photographs, faithfully digitized.")
        root.addView(label("FULL CAPTURE AREA · FREE MOVEMENT",9f,muted).apply {
            letterSpacing=.08f; setPadding(0,0,0,dp(6))
        })
        val area = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = TextureView(this).apply { isOpaque=false }
        texture = surface
        area.addView(surface,FrameLayout.LayoutParams(-1,-1))
        val thumb = ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; background=rounded(ink,12); visibility=View.GONE }
        preview=thumb
        root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        gap(10)
        root.addView(label("Move gently over the print",18f))
        gap(8)
        val observationRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        statistics=label(cameraDescription,12f,muted).also {
            observationRow.addView(it,LinearLayout.LayoutParams(0,-2,1f))
        }
        observationRow.addView(thumb,LinearLayout.LayoutParams(dp(64),dp(64)).apply { leftMargin=dp(8) })
        root.addView(observationRow,LinearLayout.LayoutParams(-1,-2))
        gap(8)
        cameraChoiceButton=button("Camera · choose lens ▾") { chooseCameraLens() }
            .also { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        gap(8)
        detailButton=button(if(detailMode) "Detail capture ▾" else "Quick capture ▾") {
            if(recording || finishing || rawBusy) { toast("Finish the current capture first."); return@button }
            AlertDialog.Builder(this).setTitle("Capture resolution").setItems(arrayOf("Detail · full resolution (allow ~1 GB per scan)", "Quick · smaller observations")) { _,which ->
                val selected=which==0
                if(detailMode!=selected) {
                    detailMode=selected
                    stagedSession=null
                    getPreferences(MODE_PRIVATE).edit().putBoolean("detailMode",selected).apply()
                    detailButton?.text=if(selected) "Detail capture ▾" else "Quick capture ▾"
                    cameraDescription="Opening camera…"
                    statistics?.text=cameraDescription
                    closeCamera()
                    ensureCamera()
                }
            }.show()
        }.also { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        gap(8)
        progress=ProgressBar(this).apply { visibility=View.GONE; isIndeterminate=true }
        root.addView(progress,LinearLayout.LayoutParams(dp(26),dp(26)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        status=label("Keep the entire print in view. Move gently in any direction.",13f,muted).also { root.addView(it) }
        gap(16)
        captureButton=button("Start scanning",true) { if(recording)finishCapture() else startCapture() }.also { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        gap(10)
        refocusButton=button("Refocus") {
            camera?.refocus()
            status?.text="Refocusing this lens… Hold still briefly; move farther away if focus is not found."
        }
        root.addView(row(button("Your scans") { showSessions() },refocusButton!!,button("Archive RAW") { captureRaw() }))
        updateLiveCameraControls()
        if(active)ensureCamera()
    }

    private fun updateLiveCameraControls() {
        if(mode!="live")return
        val enabled=!recording && !finishing && !rawBusy && !cameraClosing
        cameraChoiceButton?.apply { isEnabled=enabled; alpha=if(enabled)1f else .45f }
        detailButton?.apply { isEnabled=enabled; alpha=if(enabled)1f else .45f }
        refocusButton?.apply {
            isEnabled=!finishing && !rawBusy && !cameraClosing && cameraReady && camera?.isReady==true && camera?.info?.optBoolean("fixedFocus")!=true
            alpha=if(isEnabled)1f else .45f
        }
    }

    private fun chooseCameraLens() {
        if(recording || finishing || rawBusy || cameraClosing)return
        val token=generation
        cameraChoiceButton?.isEnabled=false
        worker.execute {
            try {
                val choices=ScanCamera.availableCameras(this)
                ui {
                    updateLiveCameraControls()
                    if(mode!="live" || generation!=token || recording || finishing || rawBusy)return@ui
                    if(choices.isEmpty()) { toast("No compatible rear camera is advertised."); return@ui }
                    val labels=arrayOf("Auto · standard wide lens")+choices.map { "${it.label}\n${it.focusHint}" }.toTypedArray()
                    val checked=if(cameraChoiceId==null)0 else choices.indexOfFirst { it.id==cameraChoiceId }.let { if(it<0)-1 else it+1 }
                    AlertDialog.Builder(this).setTitle("Camera / lens")
                        .setSingleChoiceItems(labels,checked) { dialog,which ->
                            dialog.dismiss()
                            if(recording || finishing || rawBusy || cameraClosing)return@setSingleChoiceItems
                            val selected=if(which==0)null else choices[which-1].id
                            if(selected!=cameraChoiceId || camera?.isReady!=true) {
                                cameraChoiceId=selected
                                getPreferences(MODE_PRIVATE).edit().apply {
                                    if(selected==null)remove("cameraChoice") else putString("cameraChoice",selected)
                                }.apply()
                                stagedSession=null
                                cameraChoiceButton?.text="Camera · ${labels[which]} ▾"
                                cameraDescription="Opening selected camera…"
                                statistics?.text=cameraDescription
                                closeCamera()
                                ensureCamera()
                            }
                        }.setNegativeButton("Cancel",null).show()
                }
            } catch(error:Exception) { ui { updateLiveCameraControls(); toast("Cannot list cameras: ${error.message}") } }
        }
    }

    private fun ensureCamera() {
        if (camera != null || cameraClosing || !active || mode != "live") return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status?.text="Camera access is needed to scan. Tap Start scanning to grant access."
            val prefs=getPreferences(MODE_PRIVATE)
            if(!prefs.getBoolean("cameraRequested",false))requestCameraAccess()
            return
        }
        val surface=texture ?: return
        val token=generation
        val epoch=++cameraEpoch
        val trace=CameraTrace().also { cameraTrace=it }
        cameraDiagnosticSession=null
        cameraReady=false
        camera=ScanCamera(this,surface,{ frame,metadata ->
            synchronized(submissionLock) {
            val now=SystemClock.elapsedRealtime()
            val frameSession=if(recording)session else null
            val runId=diagnosticRunId
            if(cameraEpoch!=epoch) { frame.close() }
            else if(recording && !finishing && !metadata.optBoolean("captureReady",false)) {
                frame.close()
                noteFrameDrop(frameSession,"capture_not_ready",metadata)
                if(now-lastSettlingHint>800) {
                    lastSettlingHint=now
                    ui { if(mode=="live" && generation==token && cameraEpoch==epoch && recording)status?.text=
                        metadata.optString("fieldOfViewReason","").ifBlank {
                            metadata.optString("focusHint","").ifBlank { "Settling exposure and color… Keep the print in view." }
                        } }
                }
            } else if (!recording || finishing || now-lastFrame<600 || !busy.compareAndSet(false,true)) {
                frame.close()
                if(frameSession!=null)noteFrameDrop(frameSession,when {
                    finishing -> "finishing"
                    now-lastFrame<600 -> "app_interval_throttle"
                    else -> "processing_busy"
                },metadata)
            } else {
                lastFrame=now
                val current=frameSession ?: run { frame.close(); busy.set(false); return@synchronized }
                val captureMetadata=JSONObject(metadata.toString())
                try {
                    // Own the bytes before leaving the camera callback. Surface destruction may
                    // close its ImageReader independently of Activity lifecycle callbacks.
                    val planes=frame.planes
                    val width=frame.width; val height=frame.height
                    val strides=planes.map { it.rowStride }
                    val pixels=planes.map { it.pixelStride }
                    val buffers=try {
                        planes.map { plane ->
                            val source=plane.buffer.duplicate()
                            ByteBuffer.allocateDirect(source.remaining()).apply { put(source); flip() }
                        }
                    } finally { frame.close() }
                    val copiedAt=SystemClock.elapsedRealtime()
                    worker.execute {
                        val id=++attemptId
                        val started=SystemClock.elapsedRealtime()
                        var attemptLogged=false
                        try {
                            val report=NativeBridge.addYuv(buffers[0],buffers[1],buffers[2],
                                width,height,strides[0],strides[1],strides[2],pixels[1],pixels[2])
                            val nativeMs=SystemClock.elapsedRealtime()-started
                            val nativeDetail=nativeJson { NativeBridge.lastAttemptDiagnostics() }
                            var archiveMs=0L
                            var previewMs=0L
                            try {
                            if(report.size>5) {
                                if(report[0].toInt()==0) {
                                    val archiveStarted=SystemClock.elapsedRealtime()
                                    accepted=report[1].toInt()
                                    val saved=File(current,"frame-%05d.png".format(Locale.US,accepted))
                                    try {
                                        NativeBridge.exportLastSource(saved.path)
                                        store.saveFrameFile(current,accepted,saved,report,captureMetadata)
                                    } finally { archiveMs=SystemClock.elapsedRealtime()-archiveStarted }
                                    if(firstFile==null)firstFile=saved
                                    if(report[4]>bestSharp) { bestSharp=report[4]; bestFile=saved }
                                    if(accepted==1 || accepted%3==0) {
                                        val previewStarted=SystemClock.elapsedRealtime()
                                        val result=rotate(NativeBridge.render(1,640),rotation)
                                        previewMs=SystemClock.elapsedRealtime()-previewStarted
                                        ui { if(mode=="live" && generation==token && cameraEpoch==epoch) {
                                            val old=(preview?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                            preview?.setImageBitmap(result); preview?.visibility=View.VISIBLE
                                            if(old!==result)old?.recycle()
                                        } else result.recycle() }
                                    }
                                }
                                ui { if(mode=="live" && generation==token && cameraEpoch==epoch) {
                                    statistics?.text="${report[1].toInt()} accepted · ${width} × ${height} source px · ${report[3].toInt()}/16 phases"
                                    status?.text=when(report[0].toInt()) {
                                        1 -> "This view is soft. Hold briefly while focus settles."
                                        2 -> "Reacquiring the print… Keep it visible and move gently."
                                        3 -> "Very similar view. A small natural movement will help."
                                        4 -> "Capture complete. Preparing your scan…"
                                        else -> "${report[2].toInt()} matched details · Move gently, keeping the print visible."
                                    }
                                    if(report[1]>=24 || report[0].toInt()==4)finishCapture()
                                } }
                            }
                            } finally {
                                recordAttempt(current,id,runId,"capture",captureMetadata,report,nativeDetail,
                                    JSONObject().put("copyYuvMs",copiedAt-now).put("queueWaitMs",started-copiedAt)
                                        .put("nativeMs",nativeMs).put("sourceArchiveMs",archiveMs).put("previewRenderMs",previewMs)
                                        .put("totalWorkerMs",SystemClock.elapsedRealtime()-started))
                                attemptLogged=true
                            }
                        } catch(e:Exception) {
                            if(!attemptLogged)logDiagnostic(current,"frame_attempt",exceptionDetail(e)
                                .put("attemptId",id).put("runId",runId).put("origin","capture").put("status","exception").put("reason","exception")
                                .put("native",nativeJson { NativeBridge.lastAttemptDiagnostics() })
                                .put("metadata",captureMetadata).put("elapsedMs",SystemClock.elapsedRealtime()-started))
                            logDiagnostic(current,"capture_exception",exceptionDetail(e).put("attemptId",id).put("runId",runId))
                            captureFailure=e.message ?: "Capture processing failed"
                            ui {
                                if(mode=="live" && generation==token && cameraEpoch==epoch) {
                                    if(!finishing) {
                                        recording=false
                                        camera?.lockForScan(false)
                                        captureButton?.isEnabled=true; captureButton?.text="Start scanning"
                                        updateLiveCameraControls()
                                    }
                                    status?.text="Capture stopped: ${e.message ?: "please try again"}. Saved observations remain in Your scans and can be replayed."
                                }
                            }
                        }
                        finally { busy.set(false) }
                    }
                } catch(e:Exception) {
                    queueDiagnostic(current,"frame_copy_exception",exceptionDetail(e).put("metadata",captureMetadata).put("runId",runId))
                    try { frame.close() } catch(_:Exception) { }; busy.set(false)
                }
            }
            }
        },{ message -> ui { if(mode=="live" && generation==token && cameraEpoch==epoch)status?.text=message } },{ info ->
            ui { if(mode=="live" && generation==token && cameraEpoch==epoch) {
                rotation=info.optInt("rotationDegrees",info.optInt("sensorOrientation",0))
                cameraReady=true
                cameraDescription=cameraSummary(info)
                cameraChoiceButton?.text="Camera · ${info.optString("cameraLabel","rear camera")} ▾"
                updateLiveCameraControls()
                if(!recording)statistics?.text=cameraDescription
                if(!recording)status?.text=info.optString("fallbackReason","").ifBlank { "Full capture area shown. Keep the entire print inside the preview." }
            } }
        },detailMode=detailMode,selectedCamera=cameraChoiceId,onDiagnostic={ detail ->
            queueCameraDiagnostic(trace,epoch,JSONObject(detail.toString()),JSONObject(camera?.info?.toString() ?: "{}"))
        }).also { it.open() }
    }

    private fun queueCameraDiagnostic(trace:CameraTrace,epoch:Int,packet:JSONObject,cameraInfo:JSONObject) {
        if(diagnosticPending.incrementAndGet()>32) {
            diagnosticPending.decrementAndGet(); diagnosticDropped.incrementAndGet(); return
        }
        try {
            worker.execute {
                try {
                    val current=if(cameraEpoch==epoch && (recording || finishing))cameraDiagnosticSession else null
                    val directory=current ?: trace.directory ?: run {
                        val info=if(packet.optString("event")=="streams_selected")packet.optJSONObject("detail") ?: cameraInfo else cameraInfo
                        val next=if(cameraEpoch==epoch)stagedSession ?: store.create(info) else store.create(info)
                        if(cameraEpoch==epoch) { stagedSession=next; cameraDiagnosticSession=next }
                        next
                    }
                    trace.directory=directory
                    if(packet.optString("event")=="camera_ready")
                        packet.optJSONObject("detail")?.let { store.updateCameraInfo(directory,it) }
                    val now=SystemClock.elapsedRealtime()
                    val event=packet.optString("event","unknown")
                    if(event in setOf("camera_state","streams_selected","camera_ready","preview_viewport","stream_fallback","camera_exception","focus_refocus","focus_refocus_exception","camera_closed")) {
                        val encoded=packet.toString().toByteArray(Charsets.UTF_8)
                        val bounded=if(encoded.size<=20*1024)packet else JSONObject().put("event",event)
                            .put("omittedOversizeBytes",encoded.size).put("cameraId",packet.opt("cameraId"))
                            .put("physicalCameraId",packet.opt("physicalCameraId")).put("elapsedRealtimeMs",packet.optLong("elapsedRealtimeMs",now))
                        trace.recent.addLast(bounded)
                        while(trace.recent.size>32 || trace.recent.firstOrNull()?.optLong("elapsedRealtimeMs",now)?.let { now-it>60_000 }==true)
                            trace.recent.removeFirst()
                        val data=JSONObject().put("schemaVersion",1).put("kind","camera preview metadata; no images")
                            .put("maxPackets",32).put("maxAgeMs",60_000).put("maxPacketBytes",20*1024)
                            .put("packets",JSONArray(trace.recent.toList())).toString().toByteArray(Charsets.UTF_8)
                        check(data.size<=1024*1024) { "Camera preview diagnostics exceeded their bound" }
                        val target=File(directory,"camera-preview.json")
                        val temporary=File(directory,".camera-preview.json.tmp")
                        try {
                            java.io.FileOutputStream(temporary).use { stream -> stream.write(data); stream.fd.sync() }
                            check(temporary.renameTo(target)) { "Cannot commit camera preview diagnostics" }
                        } finally { temporary.delete() }
                    }
                    logDiagnostic(directory,"camera",JSONObject(packet.toString()).put("runId",diagnosticRunId))
                } catch(error:Exception) {
                    logDiagnostic(trace.directory,"camera_trace_exception",exceptionDetail(error))
                    android.util.Log.w("LumaDiagnostics","Camera diagnostic storage unavailable",error)
                } finally { diagnosticPending.decrementAndGet() }
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) { diagnosticPending.decrementAndGet() }
    }

    private fun closeCamera() {
        val closing=camera ?: return
        camera=null; cameraReady=false; cameraEpoch++
        // Reopening waits for the previous camera session and readers to close.
        cameraClosing=true
        updateLiveCameraControls()
        worker.execute {
            closing.close { ui { cameraClosing=false; updateLiveCameraControls(); ensureCamera() } }
        }
    }

    private fun requestCameraAccess() {
        val prefs=getPreferences(MODE_PRIVATE)
        if(prefs.getBoolean("cameraRequested",false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("cameraRequested",true).apply()
            requestPermissions(arrayOf(Manifest.permission.CAMERA),10)
        }
    }

    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==10) {
            if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)ensureCamera()
            else status?.text="Camera access was declined. Tap Start scanning to grant access in Settings."
        }
    }

    private fun startCapture() {
        if(finishing)return
        if(rawBusy) { status?.text="Finishing the RAW archive. Scanning will be available in a moment."; return }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { requestCameraAccess(); return }
        val device=camera ?: run { ensureCamera(); return }
        if(!cameraReady || !device.isReady) { cameraReady=false; device.open(); status?.text="Opening camera… Try again when the preview is ready."; return }
        finishing=true
        updateLiveCameraControls()
        cameraDiagnosticSession=null
        captureButton?.isEnabled=false
        status?.text="Preparing camera…"
        val info=JSONObject(device.info.toString())
        worker.execute {
            var current:File?=null
            try {
                val directory=stagedSession ?: store.create(info)
                current=directory
                session=directory; stagedSession=null
                beginDiagnosticRun(directory,"capture")
                try { store.updateCameraInfo(directory,info) }
                catch(error:Exception) { logDiagnostic(directory,"camera_metadata_exception",exceptionDetail(error)) }
                val livePreview=File(directory,"camera-preview.json")
                if(livePreview.isFile) {
                    val temporary=File(directory,".camera-preflight.json.tmp")
                    try {
                        livePreview.inputStream().use { source -> java.io.FileOutputStream(temporary).use { target -> source.copyTo(target); target.fd.sync() } }
                        check(temporary.renameTo(File(directory,"camera-preflight.json"))) { "Cannot preserve camera preflight diagnostics" }
                    } catch(error:Exception) { logDiagnostic(directory,"camera_preflight_exception",exceptionDetail(error)) }
                    finally { temporary.delete() }
                }
                cameraDiagnosticSession=directory
                logDiagnostic(directory,"session_start",JSONObject().put("runId",diagnosticRunId).put("camera",info))
                logDiagnostic(directory,"camera_preflight",JSONObject().put("file","camera-preflight.json")
                    .put("retainedPacketCount",cameraTrace?.recent?.size ?: 0).put("maxAgeMs",60_000))
                prepareWorkspace()
                NativeBridge.reset()
                captureFailure=null
                accepted=0; bestSharp=-1.0; bestFile=null; firstFile=null; lastFrame=0
                ui {
                    finishing=false
                    updateLiveCameraControls()
                    if(active && mode=="live" && camera===device) {
                        device.lockForScan(true)
                        recording=true
                        updateLiveCameraControls()
                        captureButton?.isEnabled=true
                        captureButton?.text="Finish scan"
                        status?.text="Scanning. Move slowly across the print, keeping every edge visible."
                    } else captureButton?.isEnabled=true
                }
            } catch(e:Exception) {
                logDiagnostic(current,"session_start_exception",exceptionDetail(e))
                ui { finishing=false; updateLiveCameraControls(); captureButton?.isEnabled=true; status?.text="Could not start: ${e.message}" }
            }
        }
    }

    private fun finishCapture() {
        synchronized(submissionLock) {
        if(!recording || finishing)return
        recording=false; finishing=true
        updateLiveCameraControls()
        val current=session
        flushFrameDrops(current)
        val runId=diagnosticRunId
        camera?.lockForScan(false)
        captureButton?.isEnabled=false
        status?.text="Reconstructing accepted observations…"
        progress?.visibility=View.VISIBLE
        worker.execute {
            try {
                logDiagnostic(current,"session_finish_requested",JSONObject().put("accepted",accepted).put("runId",runId))
                logDiagnostic(current,"attempt_summary",JSONObject().put("runId",runId).put("counts",JSONObject(attemptCounts.toMap())))
                if(accepted==0) { logDiagnostic(current,"scan_stopped",JSONObject().put("reason","no-accepted-observations").put("runId",runId)); ui { finishing=false; progress?.visibility=View.GONE; captureButton?.isEnabled=true; captureButton?.text="Start scanning"; updateLiveCameraControls(); status?.text="No sharp aligned observations yet. Please try again." }; return@execute }
                captureFailure?.let { error("$it. Saved observations can be replayed from Your scans.") }
                val directory=current ?: error("No active session")
                val rendered=renderFinal(directory,rotation)
                ui { finishing=false; showReview(rendered.first,rendered.second) }
            } catch(e:Exception) {
                logDiagnostic(current,"session_finish_exception",exceptionDetail(e).put("runId",runId))
                ui { finishing=false; progress?.visibility=View.GONE; captureButton?.isEnabled=true; captureButton?.text="Start scanning"; updateLiveCameraControls(); status?.text="Reconstruction failed: ${e.message}" } }
        }
        }
    }

    private fun rotate(bitmap:Bitmap,degrees:Int):Bitmap {
        if(degrees%360==0)return bitmap
        val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(degrees.toFloat()) },true)
        if(rotated!==bitmap)bitmap.recycle()
        return rotated
    }

    private fun showReview(bitmap:Bitmap,proposal:FloatArray?) {
        mode="review"
        closeCamera()
        crop?.dispose()
        if(image!==bitmap)image?.recycle()
        image=bitmap; ratio=null; colorGains=floatArrayOf(1f,1f,1f)
        base("REVIEW / PERSPECTIVE")
        val editor=CropView(this).apply {
            setImage(bitmap,proposal); background=rounded(panel,14)
            session?.let { setDetailSource(store.result(it),resultDisplayRotation,worker) }
        }
        crop=editor
        root.addView(editor,LinearLayout.LayoutParams(-1,0,1f))
        gap(6)
        root.addView(label("Pinch or double-tap to zoom. Drag corners; drag the image to pan.",11f,muted))
        gap(6)
        root.addView(row(reviewButton("Scan") { showScan(editor) },reviewButton("Best source") { compareSource(bestFile) },reviewButton("First source") { compareSource(firstFile) }))
        gap(6)
        val ratioButton=reviewButton("Shape ▾") {
            val options=arrayOf("From corners (adjustable)","3:2 landscape","4:3 landscape","1:1 square","2:3 portrait","3:4 portrait")
            AlertDialog.Builder(this).setTitle("Output proportions").setItems(options) { _,which ->
                ratio=when(which) { 1->1.5f; 2->4f/3; 3->1f; 4->2f/3; 5->3f/4; else->null }
                showScan(editor)
                (root.findViewWithTag<TextView>("ratio"))?.text=arrayOf("Shape ▾","3:2 ▾","4:3 ▾","1:1 ▾","2:3 ▾","3:4 ▾")[which]
            }.show()
        }.apply { tag="ratio" }
        root.addView(row(ratioButton,reviewButton("Reset corners") { reviewComparisonRequest++; editor.reset() },reviewButton("Fit view") { editor.fitView() }))
        gap(6)
        root.addView(row(reviewButton("Neutral point…") {
            showScan(editor)
            AlertDialog.Builder(this).setTitle("Set white balance").setMessage("Choose a region that was neutral gray in the original photograph. Avoid paper borders unless they really were neutral. The original color is preserved until you choose a point.")
                .setPositiveButton("Choose point") { _,_ ->
                    status?.text="Tap a neutral gray region in the scan."
                    editor.onNeutralPick={x,y -> selectNeutral(x,y)}
                }.setNegativeButton("Cancel",null).show()
        },reviewButton("Reset color") {
            colorGains=floatArrayOf(1f,1f,1f); editor.setColorGains(colorGains)
            editor.onNeutralPick=null; status?.text="Original color restored."
        }))
        gap(6)
        val support=renderMetrics.getOrNull(0)?.let { " · ${"%.0f".format(it*100)}% multi-view support" } ?: ""
        val dimensions="${if(resultDisplayRotation%180==0)resultWidth else resultHeight} × ${if(resultDisplayRotation%180==0)resultHeight else resultWidth} px"
        val summary=if(reviewSourceCount==1) "Single frame · no multi-frame gain" else "$reviewSourceCount frames · $dimensions$support"
        statistics=label(summary,11f,muted).also { root.addView(it) }
        status=label(if(reviewCornerFallback) "Set the print corners. Original color preserved."
            else "Check the proposed corners. Original color preserved.",11f,muted).also { root.addView(it) }
        gap(6)
        root.addView(row(reviewButton("Save PNG",true) { saveToGallery() },reviewButton("Export session") {
            AlertDialog.Builder(this).setTitle("Export session").setItems(arrayOf("Export diagnostics","Export full session ZIP")) { _,which -> export(which==0) }.show()
        }))
        gap(6)
        root.addView(row(reviewButton("New scan") { if(!finishing)showLive() },reviewButton("Your scans") { showSessions() }))
    }

    private fun reviewButton(text:String,primary:Boolean=false,action:()->Unit):TextView = button(text,primary,action).apply {
        textSize=12f; setPadding(dp(8),dp(10),dp(8),dp(10))
    }

    private fun showScan(editor:CropView) {
        reviewComparisonRequest++
        editor.compare(null)
    }

    private fun compareSource(file:File?) {
        if(file==null) { toast("No source observation available."); return }
        val token=generation
        val request=++reviewComparisonRequest
        val sourceRotation=rotation
        worker.execute {
            try {
                val shown=decodeReview(file,sourceRotation)
                ui { if(generation==token && request==reviewComparisonRequest)crop?.compare(shown,file,sourceRotation) else shown.recycle() }
            } catch(e:Exception) { ui { if(generation==token)toast("Could not open source: ${e.message}") } }
        }
    }

    private fun saveToGallery() {
        val raster=reviewRaster ?: return
        val displayPoints=crop?.points() ?: return
        val points=sourceCorners(displayPoints,resultDisplayRotation)
        val proportions=ratio
        val gains=colorGains.copyOf()
        if(finishing)return
        finishing=true; status?.text="Correcting perspective and saving full resolution PNG…"
        val current=session ?: run { finishing=false; return }
        worker.execute {
            val editStarted=SystemClock.elapsedRealtime()
            logDiagnostic(current,"edit_start",JSONObject().put("cornersNormalized",JSONArray(points.toList()))
                .put("displayCornersNormalized",JSONArray(displayPoints.toList())).put("aspectRatio",proportions ?: JSONObject.NULL)
                .put("linearRgbGains",JSONArray(gains.toList())).put("rotationDegrees",resultDisplayRotation))
            var uri:Uri?=null
            val pending=File(current,".edited-${System.nanoTime()}.tmp")
            try {
                check(raster.isFile) { "The temporary reconstruction is unavailable. Reopen this scan and try again." }
                val dimensions=NativeBridge.exportRaster(raster.path,pending.path,points,proportions ?: 0f,gains)
                check(dimensions.size>=2) { "The perspective export returned no dimensions" }
                val edited=File(current,"edited.png")
                check(pending.renameTo(edited)) { "Could not save edited PNG; check available device storage" }
                File(current,"edit.json").writeText(JSONObject()
                    .put("source","result.png").put("output","edited.png")
                    .put("cornersNormalized",JSONArray(points.toList()))
                    .put("cornersCoordinateSpace","result.png; desired output TL, TR, BR, BL")
                    .put("displayCornersNormalized",JSONArray(displayPoints.toList()))
                    .put("displayRotationDegrees",resultDisplayRotation)
                    .put("outputWidth",dimensions[0]).put("outputHeight",dimensions[1])
                    .put("aspectRatio",proportions ?: JSONObject.NULL)
                    .put("linearRgbGains",JSONArray(gains.toList()))
                    .put("colorAdjustment","user-selected neutral point; no automatic paper whitening")
                    .toString(2))
                val values=ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,"Luma_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())}.png")
                    put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Luma Scan")
                    put(MediaStore.Images.Media.IS_PENDING,1)
                }
                uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Gallery is unavailable")
                contentResolver.openOutputStream(uri)?.use { output -> edited.inputStream().use { it.copyTo(output) } } ?: error("Could not open gallery output")
                contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
                logDiagnostic(current,"edit_finish",JSONObject().put("width",dimensions[0]).put("height",dimensions[1])
                    .put("elapsedMs",SystemClock.elapsedRealtime()-editStarted).put("output","edited.png"))
                ui { finishing=false; status?.text="Saved PNG to Pictures / Luma Scan · ${dimensions[0]} × ${dimensions[1]} px"; toast("PNG saved to gallery") }
            } catch(e:Exception) {
                logDiagnostic(current,"edit_exception",exceptionDetail(e))
                if(uri!=null)contentResolver.delete(uri,null,null)
                ui { finishing=false; status?.text="Save failed: ${e.message}" }
            } finally { pending.delete() }
        }
    }

    private fun export(diagnostics:Boolean=false,directory:File?=session) {
        val current=directory ?: return
        if(finishing) { toast("Please wait for saving to finish."); return }
        if(rawBusy) { toast("Please wait for the RAW archive to finish before exporting."); return }
        exportSession=current
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/zip"
            putExtra(Intent.EXTRA_TITLE,if(diagnostics) "${current.name}-diagnostics.zip" else "${current.name}.zip")
        },if(diagnostics)21 else 20)
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode in 20..21 && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            val current=exportSession ?: return
            if(rawBusy || recording || finishing) { toast("Finish the current capture or save before exporting."); return }
            val diagnostics=requestCode==21
            finishing=true
            status?.text=if(diagnostics) "Exporting compact diagnostics…" else "Exporting source PNGs and session metadata…"
            worker.execute {
                try {
                    logDiagnostic(current,"export_start",JSONObject().put("kind",if(diagnostics) "diagnostics" else "full"))
                    contentResolver.openOutputStream(uri)?.use {
                        if(diagnostics)store.exportDiagnosticsZip(current,it) else store.exportZip(current,it)
                    } ?: error("Cannot open destination")
                    logDiagnostic(current,"export_finish",JSONObject().put("kind",if(diagnostics) "diagnostics" else "full"))
                    ui { finishing=false; status?.text="Session ZIP exported."; toast("Session exported") }
                } catch(e:Exception) {
                    logDiagnostic(current,"export_exception",exceptionDetail(e).put("kind",if(diagnostics) "diagnostics" else "full"))
                    ui { finishing=false; status?.text="Export failed: ${e.message}" }
                }
            }
        }
    }

    private fun showSessions() {
        if(recording || finishing) { toast("Finish the current scan first."); return }
        mode="sessions"; closeCamera()
        releaseReview()
        base("YOUR SCANS / ON THIS DEVICE")
        root.addView(label("A small archive\nof real photographs.",29f).apply { typeface=Typeface.create("serif",Typeface.NORMAL) })
        gap(16)
        val scroll=ScrollView(this)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val sessions=store.sessions()
        if(sessions.isEmpty())list.addView(label("Your scans will appear here. Source observations stay on this device.",15f,muted))
        sessions.forEach { directory ->
            val title=button(directory.name.replace('_',' ')) {
                AlertDialog.Builder(this).setTitle("Open session").setItems(arrayOf("Open result","Replay observations","Export diagnostics","Export full session ZIP")) { _,which ->
                    when(which) {
                        0 -> openSession(directory)
                        1 -> replay(directory)
                        2 -> export(true,directory)
                        3 -> export(false,directory)
                    }
                }.show()
            }
            list.addView(title,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        }
        gap(12)
        root.addView(button("New scan",true) { showLive() },LinearLayout.LayoutParams(-1,-2))
    }

    private fun sessionRotation(directory:File):Int {
        // Source observations always retain the camera orientation, including legacy sessions.
        val candidates=listOf(File(directory,"manifest.json"),File(directory,"session.json"),File(directory,"metadata.json"))
        for(file in candidates)if(file.isFile)try {
            val json=JSONObject(file.readText())
            val cameraInfo=json.optJSONObject("camera") ?: json.optJSONObject("cameraInfo") ?: json
            return cameraInfo.optInt("rotationDegrees",cameraInfo.optInt("sensorOrientation",0))
        }catch(_:Exception) { }
        return 0
    }

    private fun openSession(directory:File) {
        if(finishing)return
        finishing=true
        status?.text="Opening full resolution scan…"
        val token=generation
        worker.execute {
            var thumbnail:Bitmap?=null
            try {
                val resultFile=store.result(directory)
                check(resultFile.isFile) { "No completed result. Replay the observations to reconstruct it." }
                prepareWorkspace()
                val bounds=imageBounds(resultFile)
                resultWidth=bounds.first; resultHeight=bounds.second
                resultDisplayRotation=resultRotation(directory)
                thumbnail=decodeReview(resultFile,resultDisplayRotation)
                val proposal=NativeBridge.corners(thumbnail)
                logCorners(directory,thumbnail,proposal,"open_session")
                val raster=File(scanWorkspace!!,"review.raster")
                importRaster(resultFile,raster,resultWidth,resultHeight)
                reviewRaster=raster
                val frames=store.frames(directory)
                val manifest=try { JSONObject(File(directory,"manifest.json").readText()) } catch(_:Exception) { JSONObject() }
                reviewSourceCount=manifest.optInt("acceptedSourceCount",frames.size)
                val storedMetrics=manifest.optJSONArray("metrics")
                val recoveredMetrics=if(storedMetrics!=null)DoubleArray(storedMetrics.length()) { storedMetrics.optDouble(it,0.0) } else doubleArrayOf()
                val shown=thumbnail ?: error("Cannot decode result thumbnail")
                ui {
                    finishing=false
                    if(generation==token) {
                        session=directory; rotation=sessionRotation(directory)
                        firstFile=frames.firstOrNull(); bestFile=bestSource(directory,frames)
                        renderMetrics=recoveredMetrics; showReview(shown,proposal)
                    } else shown.recycle()
                }
            }catch(e:Exception) {
                thumbnail?.recycle()
                logDiagnostic(directory,"open_session_exception",exceptionDetail(e))
                ui { finishing=false; toast(e.message ?: "Could not open session") }
            }
        }
    }

    private fun bestSource(directory:File,frames:List<File>):File? {
        // Replayed sessions select their best observation directly from native sharpness reports.
        return frames.maxByOrNull { file ->
            try {
                val metadata=JSONObject(File(directory,file.nameWithoutExtension+".json").readText())
                metadata.optJSONArray("report")?.optDouble(4,0.0) ?: metadata.optDouble("sharpness",0.0)
            }catch(_:Exception) { 0.0 }
        }
    }

    private fun replay(directory:File) {
        if(finishing)return
        val frames=store.frames(directory)
        if(frames.isEmpty()) { toast("This session has no source frames."); return }
        finishing=true; session=directory; rotation=sessionRotation(directory)
        mode="replay"
        base("SESSION / REPLAY")
        root.addView(label("Rebuilding from\nyour observations.",30f).apply { typeface=Typeface.create("serif",Typeface.NORMAL) })
        gap(24)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=frames.size }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(10)))
        gap(16)
        status=label("Aligning source PNGs locally…",15f,muted).also { root.addView(it) }
        worker.execute {
            try {
                prepareWorkspace()
                NativeBridge.reset(); beginDiagnosticRun(directory,"replay")
                logDiagnostic(directory,"replay_start",JSONObject().put("runId",diagnosticRunId).put("sourceCount",frames.size))
                bestSharp=-1.0; firstFile=frames.first(); bestFile=firstFile
                frames.forEachIndexed { index,file ->
                    val decodeStarted=SystemClock.elapsedRealtime()
                    val bitmap=BitmapFactory.decodeFile(file.path) ?: error("Unreadable observation ${file.name}")
                    val decodeMs=SystemClock.elapsedRealtime()-decodeStarted
                    val id=++attemptId
                    val started=SystemClock.elapsedRealtime()
                    val metadata=try { JSONObject(File(directory,file.nameWithoutExtension+".json").readText())
                        .optJSONObject("capture") ?: JSONObject() } catch(_:Exception) { JSONObject() }
                    val report=try { NativeBridge.addBitmap(bitmap) }
                    catch(error:Exception) {
                        logDiagnostic(directory,"frame_attempt",exceptionDetail(error)
                            .put("attemptId",id).put("runId",diagnosticRunId).put("origin","replay")
                            .put("status","exception").put("reason","exception").put("inputSource",file.name)
                            .put("metadata",metadata).put("native",nativeJson { NativeBridge.lastAttemptDiagnostics() }))
                        throw error
                    } finally { bitmap.recycle() }
                    val nativeMs=SystemClock.elapsedRealtime()-started
                    recordAttempt(directory,id,diagnosticRunId,"replay",metadata,report,
                        nativeJson { NativeBridge.lastAttemptDiagnostics() },JSONObject().put("nativeMs",nativeMs).put("sourceDecodeMs",decodeMs),file.name)
                    if(report.size>4 && report[0].toInt()==0 && report[4]>bestSharp) { bestSharp=report[4]; bestFile=file }
                    ui { progress?.progress=index+1; status?.text="${index+1} / ${frames.size} source observations processed" }
                }
                ui { status?.text="Reconstructing aligned observations…"; progress?.isIndeterminate=true }
                logDiagnostic(directory,"attempt_summary",JSONObject().put("runId",diagnosticRunId).put("counts",JSONObject(attemptCounts.toMap())))
                val rendered=renderFinal(directory,rotation)
                logDiagnostic(directory,"replay_finish",JSONObject().put("runId",diagnosticRunId))
                ui { finishing=false; showReview(rendered.first,rendered.second) }
            }catch(e:Exception) {
                logDiagnostic(directory,"replay_exception",exceptionDetail(e).put("runId",diagnosticRunId))
                ui { finishing=false; showSessions(); toast("Replay failed: ${e.message}") }
            }
        }
    }

    private fun captureRaw() {
        val device=camera ?: return
        if(!device.info.optBoolean("rawEnabled",false)) { toast("RAW is unavailable with this camera's current capture streams."); return }
        if(finishing) { toast("Please wait for processing to finish."); return }
        if(!cameraReady) { toast("Wait for the camera to be ready."); return }
        if(rawBusy) { toast("A RAW archive is already in progress."); return }
        rawBusy=true
        updateLiveCameraControls()
        val token=generation
        val current=if(recording)session else stagedSession
        val info=JSONObject(device.info.toString())
        val duringScan=recording
        status?.text="Archiving an unprocessed RAW exposure…"
        worker.execute {
            try {
                val directory=current ?: store.create(info).also { stagedSession=it }
                ui { if(camera===device && active && generation==token) {
                    device.captureRaw(File(directory,"archive_${System.currentTimeMillis()}.dng")) { error -> ui {
                        rawBusy=false
                        updateLiveCameraControls()
                        if(mode=="live" && generation==token)status?.text=if(error==null) {
                            if(duringScan)"RAW archived with this scan. YUV reconstruction continues."
                            else "RAW archived for your next scan. Start scanning to add YUV observations."
                        } else "RAW capture failed: $error"
                    } }
                } else { rawBusy=false; updateLiveCameraControls() } }
            }catch(e:Exception) { ui { rawBusy=false; updateLiveCameraControls(); if(generation==token)status?.text="RAW archive failed: ${e.message}" } }
        }
    }


    private fun selectNeutral(x:Float,y:Float) {
        val source=image ?: return
        val cx=(x*source.width).toInt().coerceIn(0,source.width-1)
        val cy=(y*source.height).toInt().coerceIn(0,source.height-1)
        val values=DoubleArray(3)
        var count=0
        for(py in (cy-4).coerceAtLeast(0)..(cy+4).coerceAtMost(source.height-1))
            for(px in (cx-4).coerceAtLeast(0)..(cx+4).coerceAtMost(source.width-1)) {
                val pixel=source.getPixel(px,py)
                values[0]+=linear(Color.red(pixel)/255.0)
                values[1]+=linear(Color.green(pixel)/255.0)
                values[2]+=linear(Color.blue(pixel)/255.0)
                count++
            }
        for(i in values.indices)values[i]/=count.toDouble()
        if(values.minOrNull()!!<.01 || values.maxOrNull()!!>.94) {
            status?.text="Choose a midtone neutral region, away from deep shadows and clipped highlights."
            return
        }
        val target=values.average()
        colorGains=FloatArray(3) { (target/values[it]).coerceIn(.6,1.65).toFloat() }
        crop?.setColorGains(colorGains)
        status?.text="Neutral-point color adjustment applied. Reset color restores the original."
    }

    private fun linear(s:Double):Double = if(s<=.04045)s/12.92 else ((s+.055)/1.055).pow(2.4)
    private fun exceptionDetail(error:Exception)=JSONObject()
        .put("exception",error.javaClass.name).put("message",error.message ?: JSONObject.NULL)
        .put("stack",error.stackTraceToString().take(6000))

    private fun numbers(values:DoubleArray)=JSONArray().also { out ->
        values.forEach { out.put(if(it.isFinite())it else JSONObject.NULL) }
    }

    private fun nativeJson(read:()->String):JSONObject = try { JSONObject(read()) }
        catch(error:Exception) { JSONObject().put("diagnosticError",error.message ?: error.javaClass.name) }

    /** Diagnostic failures must never change capture, rendering, or export behavior. */
    private fun logDiagnostic(directory:File?,event:String,detail:JSONObject=JSONObject()) {
        if(directory==null)return
        try { store.logEvent(directory,event,detail) }
        catch(error:Exception) { android.util.Log.w("LumaDiagnostics","Cannot append $event",error) }
    }

    private fun queueDiagnostic(directory:File?,event:String,detail:JSONObject) {
        if(directory==null)return
        if(diagnosticPending.incrementAndGet()>32) {
            diagnosticPending.decrementAndGet(); diagnosticDropped.incrementAndGet(); return
        }
        try {
            worker.execute {
                try {
                    val dropped=diagnosticDropped.getAndSet(0)
                    if(dropped>0)logDiagnostic(directory,"diagnostic_queue_dropped",JSONObject().put("count",dropped))
                    logDiagnostic(directory,event,detail)
                } finally { diagnosticPending.decrementAndGet() }
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) { diagnosticPending.decrementAndGet() }
    }

    // Call under submissionLock so a finish request can flush the final partial interval.
    private fun noteFrameDrop(directory:File?,reason:String,metadata:JSONObject) {
        if(directory==null)return
        frameDrops[reason]=(frameDrops[reason] ?: 0)+1
        if(SystemClock.elapsedRealtime()-lastDropLogAt>=2000)flushFrameDrops(directory,metadata)
    }

    private fun flushFrameDrops(directory:File?,metadata:JSONObject?=null) {
        if(frameDrops.isEmpty())return
        val detail=JSONObject().put("counts",JSONObject(frameDrops.toMap())).put("runId",diagnosticRunId)
        if(metadata!=null)detail.put("latestMetadata",JSONObject(metadata.toString()))
        frameDrops.clear(); lastDropLogAt=SystemClock.elapsedRealtime()
        queueDiagnostic(directory,"app_frame_drops",detail)
    }

    private fun beginDiagnosticRun(directory:File,origin:String) {
        diagnosticRunId=java.util.UUID.randomUUID().toString()
        attemptId=0
        rejectedPreviews=File(directory,"diagnostic-previews").listFiles()?.count { it.isFile && it.name.startsWith("attempt-") && it.extension=="jpg" } ?: 0
        lastRejectedPreviewAttempt=-5
        rejectedReasons.clear(); attemptCounts.clear()
        synchronized(submissionLock) { frameDrops.clear(); lastDropLogAt=0 }
        logDiagnostic(directory,"run_start",JSONObject().put("runId",diagnosticRunId).put("origin",origin))
    }

    private fun recordAttempt(directory:File,id:Int,runId:String,origin:String,metadata:JSONObject,
        report:DoubleArray,native:JSONObject,timings:JSONObject,inputSource:String?=null) {
        try {
            val status=report.getOrNull(0)?.toInt() ?: -1
            val reason=native.optString("reason","status-$status")
            attemptCounts[reason]=(attemptCounts[reason] ?: 0)+1
            val detail=JSONObject().put("attemptId",id).put("runId",runId).put("origin",origin)
                .put("status",status).put("reason",reason).put("report",numbers(report))
                .put("metadata",metadata).put("native",native).put("timingsMs",timings)
            if(inputSource!=null)detail.put("inputSource",inputSource)
            if(status==0) {
                val source=inputSource ?: "frame-%05d.png".format(Locale.US,report.getOrNull(1)?.toInt() ?: 0)
                detail.put("acceptedSource",source).put("acceptedSourceExists",File(directory,source).isFile)
                if(report.getOrNull(1)?.toInt()==1) {
                    val thumbnail=NativeBridge.diagnosticThumbnail()
                    try {
                        store.saveDiagnosticPreview(directory,"reference-$runId",thumbnail)?.let {
                            detail.put("preview",it).put("previewKind","reference")
                        }
                    } finally { thumbnail.recycle() }
                }
            } else if(status in 1..3) {
                val firstReason=rejectedReasons.add(reason)
                if(rejectedPreviews<8 && (firstReason || id-lastRejectedPreviewAttempt>=5)) {
                    lastRejectedPreviewAttempt=id; rejectedPreviews++
                    val thumbnail=NativeBridge.diagnosticThumbnail()
                    try {
                        store.saveRejectedPreview(directory,id,thumbnail,detail)?.let {
                            detail.put("preview",it).put("previewKind","rejected")
                        }
                    } finally { thumbnail.recycle() }
                }
            }
            logDiagnostic(directory,"frame_attempt",detail)
        } catch(error:Exception) {
            logDiagnostic(directory,"frame_attempt",JSONObject().put("attemptId",id).put("runId",runId)
                .put("origin",origin).put("status",report.getOrNull(0)?.toInt() ?: -1)
                .put("reason",native.optString("reason","unknown")).put("report",numbers(report))
                .put("native",native).put("metadata",metadata).put("diagnosticError",error.message ?: error.javaClass.name))
        }
    }

    private fun logCorners(directory:File,bitmap:Bitmap,proposal:FloatArray,origin:String) {
        try {
            val native=nativeJson { NativeBridge.lastCornerDiagnostics() }
            reviewCornerFallback=native.optBoolean("fallback",false)
            val detail=JSONObject().put("runId",diagnosticRunId).put("origin",origin)
                .put("inputSource","result.png").put("inputWidth",bitmap.width).put("inputHeight",bitmap.height)
                .put("displayRotationDegrees",resultDisplayRotation).put("cornersNormalized",JSONArray(proposal.toList()))
                .put("inputOrientation","display-oriented downsampled result; normalized corner coordinates refer to this preview")
                .put("native",native)
            store.saveDiagnosticPreview(directory,"corner-result-${System.nanoTime()}",bitmap)?.let { detail.put("preview",it) }
            logDiagnostic(directory,"corners",detail)
        } catch(error:Exception) { logDiagnostic(directory,"corner_diagnostic_exception",exceptionDetail(error)) }
    }

    private fun cameraSummary(info:JSONObject):String {
        val width=info.optInt("yuvWidth",info.optInt("sourceWidth",0))
        val height=info.optInt("yuvHeight",info.optInt("sourceHeight",0))
        val fallback=info.optString("fallbackReason","")
        val actual=if(info.optString("actualCaptureMode")=="detail") "Detail" else "Quick"
        return "$actual · $width × $height source pixels · full area preview" +
            if(fallback.isNotBlank()) " · $fallback" else " · lossless source archive"
    }

    /** Remove only this app's disposable reconstruction cache, never session source files. */
    private fun prepareWorkspace() {
        val cache=File(cacheDir,"reconstruction")
        check(cache.isDirectory || cache.mkdirs()) { "Cannot create reconstruction cache" }
        reviewRaster=null
        val next=File(cache,java.util.UUID.randomUUID().toString())
        check(next.mkdir()) { "Cannot create reconstruction workspace" }
        scanWorkspace=next
        check(next.usableSpace>256L*1024*1024) {
            "Less than 256 MB is free. Free device storage for full resolution reconstruction and lossless source PNGs."
        }
        NativeBridge.configureStorage(next.path)
        cache.listFiles()?.filter { it!=next }?.forEach { it.deleteRecursively() }
    }

    private fun renderFinal(directory:File,degrees:Int):Pair<Bitmap,FloatArray> {
        val workspace=scanWorkspace ?: error("Missing reconstruction workspace")
        val png=File(workspace,"result.png")
        val raster=File(workspace,"review.raster")
        val started=SystemClock.elapsedRealtime()
        val report=NativeBridge.renderToFiles(png.path,raster.path,2,0)
        check(report.size>=5) { "Reconstruction returned no output dimensions" }
        resultWidth=report[0].toInt(); resultHeight=report[1].toInt()
        resultDisplayRotation=degrees
        renderMetrics=report.copyOfRange(2,5)
        reviewSourceCount=attemptCounts.filterKeys { it=="accepted" || it=="accepted-reference" }.values.sum()
            .takeIf { it>0 } ?: store.frames(directory).size
        store.finishFile(directory,png,resultWidth,resultHeight,renderMetrics,degrees,reviewSourceCount)
        logDiagnostic(directory,"render_complete",JSONObject().put("runId",diagnosticRunId)
            .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("width",resultWidth).put("height",resultHeight)
            .put("multiViewSupportFraction",report[2]).put("clippedFraction",report[3])
            .put("temporalRejectionFraction",report[4]).put("rejectionMeaning","temporal color residual rejection; not a measured glare fraction"))
        reviewRaster=raster
        val thumbnail=decodeReview(store.result(directory),degrees)
        return try {
            val proposal=NativeBridge.corners(thumbnail)
            logCorners(directory,thumbnail,proposal,"render")
            NativeBridge.reset() // Record diagnostics before releasing fusion spills.
            png.delete()
            logDiagnostic(directory,"session_finish",JSONObject().put("runId",diagnosticRunId).put("result","result.png"))
            Pair(thumbnail,proposal)
        } catch(e:Exception) { thumbnail.recycle(); throw e }
    }

    private fun imageBounds(file:File):Pair<Int,Int> {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,options)
        check(options.outWidth>0 && options.outHeight>0) { "Cannot read ${file.name}" }
        return Pair(options.outWidth,options.outHeight)
    }

    private fun decodeReview(file:File,degrees:Int):Bitmap {
        val (width,height)=imageBounds(file)
        var sample=1
        while(width.toLong()*height/(sample.toLong()*sample)>2_000_000 ||
            maxOf(width,height)/sample>1600)sample*=2
        val options=BitmapFactory.Options().apply {
            inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888
        }
        val decoded=BitmapFactory.decodeFile(file.path,options) ?: error("Cannot decode ${file.name}")
        return rotate(decoded,degrees)
    }

    @Suppress("DEPRECATION")
    private fun importRaster(file:File,raster:File,width:Int,height:Int) {
        val required=width.toLong()*height*3+64L*1024*1024
        check(raster.parentFile!!.usableSpace>required) {
            "Not enough device storage to edit this scan. Free at least ${(required/1024/1024)+1} MB and reopen it."
        }
        val decoder=BitmapRegionDecoder.newInstance(file.path,false)
            ?: error("Cannot read scan in bounded strips")
        try {
            val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 }
            var top=0
            while(top<height) {
                val bottom=minOf(top+128,height)
                val strip=decoder.decodeRegion(Rect(0,top,width,bottom),options)
                    ?: error("Cannot read scan rows $top–$bottom")
                try { NativeBridge.writeRasterRows(raster.path,strip,top,height) }
                finally { strip.recycle() }
                top=bottom
            }
        } catch(e:Exception) { raster.delete(); throw e }
        finally { decoder.recycle() }
    }

    private fun resultRotation(directory:File):Int = try {
        val manifest=JSONObject(File(directory,"manifest.json").readText())
        if(manifest.optString("resultOrientation")=="sensor")
            manifest.optInt("rotationDegrees",sessionRotation(directory)) else 0
    } catch(_:Exception) { 0 }

    /** Preserve desired display TL/TR/BR/BL ordering while returning sensor coordinates. */
    private fun sourceCorners(display:FloatArray,degrees:Int):FloatArray = FloatArray(8).also { result ->
        for(i in 0..3) {
            val x=display[2*i]; val y=display[2*i+1]
            val source=CropCoordinates.sourcePoint(x,y,degrees)
            result[2*i]=source.first; result[2*i+1]=source.second
        }
    }

    private fun releaseReview() {
        reviewComparisonRequest++
        crop?.dispose()
        crop=null
        image?.recycle(); image=null
        val thumbnail=(preview?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        preview?.setImageDrawable(null)
        thumbnail?.recycle(); preview=null
    }

    private fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }

    override fun onBackPressed() {
        if(recording)finishCapture()
        else if(finishing)toast("Processing your scan. Please wait.")
        else if(mode!="live")showLive()
        else super.onBackPressed()
    }
}
