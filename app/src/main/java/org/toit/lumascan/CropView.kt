// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.*

/** Image coordinates remain normalized while the viewport moves and scales. */
internal class CropViewport {
    var viewWidth = 0f; private set
    var viewHeight = 0f; private set
    var fitWidth = 0f; private set
    var fitHeight = 0f; private set
    var zoom = 1f; private set
    var left = 0f; private set
    var top = 0f; private set
    val width get() = fitWidth * zoom
    val height get() = fitHeight * zoom

    fun configure(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int) {
        val cx = if(width > 0) normalizedX(this.viewWidth / 2) else .5f
        val cy = if(height > 0) normalizedY(this.viewHeight / 2) else .5f
        this.viewWidth = viewWidth; this.viewHeight = viewHeight
        val scale = min(viewWidth / imageWidth.coerceAtLeast(1), viewHeight / imageHeight.coerceAtLeast(1))
        fitWidth = imageWidth * scale; fitHeight = imageHeight * scale
        left = viewWidth / 2 - cx * width; top = viewHeight / 2 - cy * height
        clamp()
    }
    fun fit() { zoom = 1f; clamp() }
    fun normalizedX(x: Float) = (x - left) / width
    fun normalizedY(y: Float) = (y - top) / height
    fun screenX(x: Float) = left + x * width
    fun screenY(y: Float) = top + y * height
    fun pan(dx: Float, dy: Float) { left += dx; top += dy; clamp() }
    fun transform(oldX: Float, oldY: Float, newX: Float, newY: Float, factor: Float) {
        if(width <= 0 || height <= 0 || !factor.isFinite()) return
        val x = normalizedX(oldX); val y = normalizedY(oldY)
        zoom = (zoom * factor).coerceIn(1f, 8f)
        left = newX - x * width; top = newY - y * height
        clamp()
    }
    private fun clamp() {
        left = if(width <= viewWidth) (viewWidth - width) / 2 else left.coerceIn(viewWidth - width, 0f)
        top = if(height <= viewHeight) (viewHeight - height) / 2 else top.coerceIn(viewHeight - height, 0f)
    }
}

/** A surviving pointer starts a fresh pan baseline after a pinch. */
internal class CropPanAnchor {
    var pointerId = -1; private set
    private var x = 0f
    private var y = 0f
    fun rebase(pointerId: Int,x: Float,y: Float) { this.pointerId = pointerId; update(x,y) }
    fun deltaX(x: Float) = x-this.x
    fun deltaY(y: Float) = y-this.y
    fun update(x: Float,y: Float) { this.x = x; this.y = y }
    fun clear() { pointerId = -1 }
}

/** Shared inverse display rotation for exported handles and decoded detail regions. */
internal object CropCoordinates {
    fun sourcePoint(x: Float,y: Float,rotation: Int): Pair<Float,Float> = when((rotation%360+360)%360) {
        90 -> Pair(y,1f-x)
        180 -> Pair(1f-x,1f-y)
        270 -> Pair(1f-y,x)
        else -> Pair(x,y)
    }
    fun sourceRegion(left: Float,top: Float,right: Float,bottom: Float,rotation: Int): FloatArray {
        val a = sourcePoint(left,top,rotation); val b = sourcePoint(right,bottom,rotation)
        return floatArrayOf(min(a.first,b.first),min(a.second,b.second),max(a.first,b.first),max(a.second,b.second))
    }
}

/** Corners, neutral picks, comparison and detail tiles share the same normalized image space. */
class CropView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        private set
    private var comparison: Bitmap? = null
    private val corners = FloatArray(8)
    private val initialCorners = FloatArray(8)
    private val viewport = CropViewport()
    private val bounds = RectF()
    private val tileBounds = RectF()
    private val tileDestination = RectF()
    private val lensBounds = RectF()
    private val lensImage = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val polygon = Path()
    private val mask = Path()
    private val accent = Color.rgb(197, 247, 123)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragging = -1
    private val panAnchor = CropPanAnchor()
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var span = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var suppressGesture = false
    private var moved = false
    private var colorFilter: ColorMatrixColorFilter? = null
    private var sourceFile: File? = null
    private var sourceRotation = 0
    private var comparisonFile: File? = null
    private var comparisonRotation = 0
    private var detailExecutor: Executor? = null
    private var detailTile: Bitmap? = null
    private val main = Handler(context.mainLooper)
    @Volatile private var requestSerial = 0
    @Volatile private var disposed = false
    private val detailRequest = Runnable { requestDetail() }
    var onChanged: (() -> Unit)? = null
    var onNeutralPick: ((Float, Float) -> Unit)? = null

    private val taps = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            dragging = -1; suppressGesture = true
            if(viewport.zoom > 1.5f) fitView()
            else {
                viewport.transform(event.x,event.y,event.x,event.y,3f)
                viewportChanged()
            }
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val picker = onNeutralPick ?: return false
            if(comparison != null || !insideImage(event.x,event.y)) return false
            onNeutralPick = null
            picker(viewport.normalizedX(event.x).coerceIn(0f,1f), viewport.normalizedY(event.y).coerceIn(0f,1f))
            return true
        }
    })

    fun setColorGains(gains: FloatArray) {
        val r = gains[0].toDouble().pow(1.0/2.2).toFloat()
        val g = gains[1].toDouble().pow(1.0/2.2).toFloat()
        val b = gains[2].toDouble().pow(1.0/2.2).toFloat()
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            r,0f,0f,0f,0f, 0f,g,0f,0f,0f, 0f,0f,b,0f,0f, 0f,0f,0f,1f,0f)))
        invalidate()
    }

    fun setImage(image: Bitmap, proposal: FloatArray? = null) {
        disposed = false
        comparison?.takeIf { it !== bitmap && it !== image }?.recycle()
        bitmap = image; comparison = null
        val initial = proposal?.takeIf { it.size == 8 && it.all(Float::isFinite) && isConvex(it) }
            ?: floatArrayOf(.03f,.03f,.97f,.03f,.97f,.97f,.03f,.97f)
        for(i in corners.indices) corners[i] = initial[i].coerceIn(0f,1f)
        corners.copyInto(initialCorners)
        viewport.configure(width.toFloat(),height.toFloat(),image.width,image.height)
        fitView()
    }

    /** The persisted PNG stays on disk; every decoded viewport tile is bounded to two megapixels. */
    fun setDetailSource(file: File, rotation: Int, executor: Executor) {
        sourceFile = file; sourceRotation = rotation; detailExecutor = executor
        viewportChanged()
    }
    fun compare(image: Bitmap?, file: File? = null, rotation: Int = 0) {
        if(comparison !== image && comparison !== bitmap) comparison?.recycle()
        comparison = image; comparisonFile = file; comparisonRotation = rotation
        clearTile(); viewportChanged()
    }
    fun points(): FloatArray = corners.copyOf()
    fun reset() {
        initialCorners.copyInto(corners)
        compare(null); onChanged?.invoke(); invalidate()
    }
    fun fitView() { viewport.fit(); viewportChanged() }
    fun dispose() {
        disposed = true; requestSerial++
        removeCallbacks(detailRequest)
        comparison?.takeIf { it !== bitmap }?.recycle(); comparison = null
        clearTile(); onNeutralPick = null
    }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        bitmap?.let { viewport.configure(w.toFloat(),h.toFloat(),it.width,it.height) }
        viewportChanged()
    }
    private fun viewportChanged() {
        requestSerial++
        removeCallbacks(detailRequest)
        if(viewport.zoom > 1.15f && !disposed) postDelayed(detailRequest,90)
        else clearTile()
        invalidate()
    }
    private fun clearTile() { detailTile?.recycle(); detailTile = null }
    private fun insideImage(x: Float,y: Float) = viewport.width > 0 && viewport.height > 0 &&
        viewport.normalizedX(x) in 0f..1f && viewport.normalizedY(y) in 0f..1f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shown = comparison ?: bitmap ?: return
        bounds.set(viewport.left,viewport.top,viewport.left+viewport.width,viewport.top+viewport.height)
        paint.color = Color.WHITE; paint.style = Paint.Style.FILL
        paint.colorFilter = if(comparison == null) colorFilter else null
        canvas.drawBitmap(shown,null,bounds,paint)
        detailTile?.let { tile ->
            imageRect(tileBounds,tileDestination)
            canvas.drawBitmap(tile,null,tileDestination,paint)
        }
        paint.colorFilter = null
        if(comparison != null) return
        polygon.rewind()
        for(i in 0..3) {
            val x = viewport.screenX(corners[2*i]); val y = viewport.screenY(corners[2*i+1])
            if(i == 0) polygon.moveTo(x,y) else polygon.lineTo(x,y)
        }
        polygon.close()
        mask.rewind(); mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(bounds,Path.Direction.CW); mask.addPath(polygon)
        paint.color = 0x77000000; canvas.drawPath(mask,paint)
        paint.color = accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = density*1.4f
        canvas.drawPath(polygon,paint)
        paint.style = Paint.Style.FILL
        for(i in 0..3) {
            val x = viewport.screenX(corners[2*i]); val y = viewport.screenY(corners[2*i+1])
            paint.color = 0xff101410.toInt(); canvas.drawCircle(x,y,9*density,paint)
            paint.color = accent; canvas.drawCircle(x,y,if(i == dragging) 6*density else 4*density,paint)
        }
        if(dragging >= 0) drawMagnifier(canvas,shown)
    }
    private fun imageRect(normalized: RectF,target: RectF) {
        target.set(viewport.screenX(normalized.left),viewport.screenY(normalized.top),
            viewport.screenX(normalized.right),viewport.screenY(normalized.bottom))
    }
    private fun drawMagnifier(canvas: Canvas,shown: Bitmap) {
        val side = min(116*density,min(width,height)*.4f)
        val margin = 10*density
        val x = viewport.screenX(corners[dragging*2]); val y = viewport.screenY(corners[dragging*2+1])
        val left = if(x < width/2f) width-side-margin else margin
        lensBounds.set(left,margin,left+side,margin+side)
        paint.color = 0xff101410.toInt(); paint.style = Paint.Style.FILL
        canvas.drawRect(lensBounds,paint)
        val save = canvas.save(); canvas.clipRect(lensBounds)
        val scale = 2.5f
        lensImage.set(lensBounds.centerX()+(bounds.left-x)*scale,lensBounds.centerY()+(bounds.top-y)*scale,
            lensBounds.centerX()+(bounds.right-x)*scale,lensBounds.centerY()+(bounds.bottom-y)*scale)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(shown,null,lensImage,paint)
        detailTile?.let { tile ->
            imageRect(tileBounds,tileDestination)
            lensImage.set(lensBounds.centerX()+(tileDestination.left-x)*scale,lensBounds.centerY()+(tileDestination.top-y)*scale,
                lensBounds.centerX()+(tileDestination.right-x)*scale,lensBounds.centerY()+(tileDestination.bottom-y)*scale)
            canvas.drawBitmap(tile,null,lensImage,paint)
        }
        paint.colorFilter = null; paint.color = accent; paint.strokeWidth = density
        val cx = lensBounds.centerX(); val cy = lensBounds.centerY()
        canvas.drawLine(cx-12*density,cy,cx-3*density,cy,paint); canvas.drawLine(cx+3*density,cy,cx+12*density,cy,paint)
        canvas.drawLine(cx,cy-12*density,cx,cy-3*density,paint); canvas.drawLine(cx,cy+3*density,cx,cy+12*density,paint)
        canvas.restoreToCount(save)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*density
        canvas.drawRect(lensBounds,paint); paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(bitmap == null || viewport.width <= 0 || disposed) return false
        if(event.actionMasked == MotionEvent.ACTION_DOWN) suppressGesture = false
        taps.onTouchEvent(event)
        if(suppressGesture) {
            if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panAnchor.rebase(event.getPointerId(0),event.x,event.y); moved = false
                gestureStartX = event.x; gestureStartY = event.y
                dragging = -1
                if(comparison == null && onNeutralPick == null) {
                    var nearest = 30*density
                    for(i in 0..3) {
                        val distance = hypot(event.x-viewport.screenX(corners[2*i]),event.y-viewport.screenY(corners[2*i+1]))
                        if(distance < nearest) { nearest = distance; dragging = i }
                    }
                }
                if(dragging >= 0) {
                    offsetX = viewport.screenX(corners[dragging*2])-event.x
                    offsetY = viewport.screenY(corners[dragging*2+1])-event.y
                }
                parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = -1; moved = true
                resetPinch(event,-1); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount >= 2) {
                    val x = (event.getX(0)+event.getX(1))/2; val y = (event.getY(0)+event.getY(1))/2
                    val distance = hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1))
                    viewport.transform(focusX,focusY,x,y,if(span > 0) distance/span else 1f)
                    focusX = x; focusY = y; span = distance; dragging = -1
                    viewportChanged()
                } else {
                    val pointer = event.findPointerIndex(panAnchor.pointerId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(pointer); val y = event.getY(pointer)
                    val dx = panAnchor.deltaX(x); val dy = panAnchor.deltaY(y)
                    if(dragging >= 0) {
                        val candidate = corners.copyOf()
                        candidate[dragging*2] = viewport.normalizedX(x+offsetX).coerceIn(0f,1f)
                        candidate[dragging*2+1] = viewport.normalizedY(y+offsetY).coerceIn(0f,1f)
                        if(isConvex(candidate)) { candidate.copyInto(corners); onChanged?.invoke() }
                        invalidate()
                    } else if(moved || hypot(x-gestureStartX,y-gestureStartY) > touchSlop) {
                        moved = true; viewport.pan(dx,dy); viewportChanged()
                    }
                    panAnchor.update(x,y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Rebase on the surviving pointer so the next move cannot jump by the finger separation.
                resetPinch(event,event.actionIndex); dragging = -1; moved = true; invalidate(); return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                panAnchor.clear(); dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false); invalidate()
                if(event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }
    private fun resetPinch(event: MotionEvent,skip: Int) {
        val indices = (0 until event.pointerCount).filter { it != skip }
        val first = indices.firstOrNull() ?: return
        panAnchor.rebase(event.getPointerId(first),event.getX(first),event.getY(first))
        if(indices.size >= 2) {
            val second = indices[1]
            focusX = (event.getX(first)+event.getX(second))/2; focusY = (event.getY(first)+event.getY(second))/2
            span = hypot(event.getX(first)-event.getX(second),event.getY(first)-event.getY(second))
        } else span = 0f
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun requestDetail() {
        val file = if(comparison != null) comparisonFile else sourceFile
        val rotation = if(comparison != null) comparisonRotation else sourceRotation
        val executor = detailExecutor ?: return
        if(file == null || viewport.zoom <= 1.15f || disposed) return
        val serial = requestSerial
        val visible = RectF(viewport.normalizedX(0f).coerceIn(0f,1f),viewport.normalizedY(0f).coerceIn(0f,1f),
            viewport.normalizedX(width.toFloat()).coerceIn(0f,1f),viewport.normalizedY(height.toFloat()).coerceIn(0f,1f))
        val paddingX = visible.width()*.15f; val paddingY = visible.height()*.15f
        visible.inset(-paddingX,-paddingY); visible.intersect(0f,0f,1f,1f)
        try {
            executor.execute {
                if(disposed || serial != requestSerial) return@execute
                var tile: Bitmap? = null
                try {
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(file.path,false) ?: return@execute
                    val displayedBounds: RectF
                    try {
                        val native = nativeRegion(visible,rotation)
                        val region = Rect(floor(native.left*decoder.width).toInt().coerceIn(0,decoder.width-1),
                            floor(native.top*decoder.height).toInt().coerceIn(0,decoder.height-1),
                            ceil(native.right*decoder.width).toInt().coerceIn(1,decoder.width),
                            ceil(native.bottom*decoder.height).toInt().coerceIn(1,decoder.height))
                        var sample = 1
                        while(region.width().toLong()*region.height()/(sample.toLong()*sample) > 2_000_000L ||
                            max(region.width(),region.height())/sample > 2048) sample *= 2
                        tile = decoder.decodeRegion(region,BitmapFactory.Options().apply {
                            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                        }) ?: return@execute
                        displayedBounds = displayRegion(RectF(region.left.toFloat()/decoder.width,region.top.toFloat()/decoder.height,
                            region.right.toFloat()/decoder.width,region.bottom.toFloat()/decoder.height),rotation)
                    } finally { decoder.recycle() }
                    if(rotation%360 != 0) {
                        val original = tile!!
                        tile = Bitmap.createBitmap(original,0,0,original.width,original.height,
                            Matrix().apply { postRotate(rotation.toFloat()) },true)
                        if(tile !== original) original.recycle()
                    }
                    val completed = tile!!; tile = null
                    main.post {
                        if(disposed || serial != requestSerial) completed.recycle()
                        else {
                            clearTile(); detailTile = completed; tileBounds.set(displayedBounds); invalidate()
                        }
                    }
                } catch(error:Exception) { android.util.Log.d("LumaScan","Review detail tile unavailable",error) }
                finally { tile?.recycle() }
            }
        } catch(_:RejectedExecutionException) { /* Activity teardown cancels outstanding detail work. */ }
    }

    companion object {
        internal fun nativeRegion(display: RectF,rotation: Int): RectF {
            val bounds = CropCoordinates.sourceRegion(display.left,display.top,display.right,display.bottom,rotation)
            return RectF(bounds[0],bounds[1],bounds[2],bounds[3])
        }
        internal fun displayRegion(native: RectF,rotation: Int): RectF = nativeRegion(native,360-rotation)
        private fun isConvex(p: FloatArray): Boolean {
            for(i in 0..3) {
                val a = i*2; val b = ((i+1)%4)*2; val c = ((i+2)%4)*2
                if((p[b]-p[a])*(p[c+1]-p[b+1])-(p[b+1]-p[a+1])*(p[c]-p[b]) < .0005f) return false
            }
            return true
        }
    }
}
