package com.merrythieves.inkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import com.inksdk.ink.InkController
import com.inksdk.ink.InkControllerFactory
import com.inksdk.ink.InkDefaults
import com.inksdk.ink.StrokeCallback

/**
 * Ink surface with vertical scroll, multi-canvas swipe, pen via daemon, finger
 * for nav/scroll. Each canvas is a "document" bitmap whose width equals the
 * view width and whose height is `documentHeightFactor × view height`. Only
 * the current scroll window is committed to the SurfaceView and mirrored into
 * the daemon's overlay.
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    fun interface PageNavListener { fun onSwipe(direction: Int) }

    private val ink: InkController = InkControllerFactory.create()

    /** Document = vertically scrollable canvas. Width = view width. */
    private var docBitmap: Bitmap? = null
    /** Per-window snapshot we hand to the SurfaceView and the daemon. */
    private var windowBitmap: Bitmap? = null

    /** Vertical scroll offset (0..docHeight - viewHeight). */
    private var scrollY: Int = 0
    private var surfaceReady = false

    /** Document height as a multiple of view height. */
    var documentHeightFactor: Int = 2

    /** Active stroke style (also passed to the SDK). */
    private var strokeWidth: Float = InkDefaults.DEFAULT_STROKE_WIDTH_PX
    private var strokeColor: Int = Color.BLACK
    /** Eraser uses a wide white stroke. Tracked separately so a width tweak
     *  on the active pen doesn't lock in an eraser size. */
    private var eraserWidth: Float = 30f
    var isEraser: Boolean = false
        private set

    /** When false, all finger input is disabled (no drawing, no scrolling, no swiping). */
    var touchEnabled: Boolean = false

    /** Background pattern type. 0=none, 1=ruled, 2=dots, 3=grid */
    private var backgroundType: Int = 0

    /** Paint for drawing background patterns */
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#7090B8") // Same blue as dots
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint().apply {
        color = Color.parseColor("#7090B8") // Darker blue for dots
        style = Paint.Style.FILL
    }

    private var navListener: PageNavListener? = null
    fun setPageNavListener(l: PageNavListener?) { navListener = l }
    private var scrollListener: ((Int, Int) -> Unit)? = null
    fun setScrollListener(l: ((scrollY: Int, maxScrollY: Int) -> Unit)?) { scrollListener = l }
    private var dirtyListener: (() -> Unit)? = null
    fun setDirtyListener(l: (() -> Unit)?) { dirtyListener = l }
    private var surfaceReadyListener: (() -> Unit)? = null
    fun setSurfaceReadyListener(l: (() -> Unit)?) { surfaceReadyListener = l }

    private val strokePaint = Paint().apply {
        color = strokeColor
        strokeWidth = this@InkSurfaceView.strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
    }

    /** Paint for eraser strokes — uses PorterDuff.Mode.CLEAR to remove ink, not cover it. */
    private val eraserPaint = Paint().apply {
        color = Color.BLACK // Required but ignored for clear mode
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Daemon stroke buffer (view-local coords from the InputListener).
    private val strokeBuffer = mutableListOf<PointF>()

    // Finger-gesture state.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = (touchSlop * 8).coerceAtLeast(120)
    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private var fingerLastX = 0f
    private var fingerLastY = 0f
    private var fingerActive = false
    private enum class FingerMode { UNDECIDED, SCROLL, SWIPE, DRAW }
    private var fingerMode = FingerMode.UNDECIDED

    // Fallback pen state when no daemon (e.g., on a non-Bigme device).
    private var fallbackPenLastX = 0f
    private var fallbackPenLastY = 0f
    private var fallbackPenDown = false

    init { holder.addCallback(this) }

    private val strokeCallback = object : StrokeCallback {
        override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            Log.i(TAG, "cb.begin ($x,$y)")
            strokeBuffer.clear()
            strokeBuffer.add(PointF(x, y))
        }
        override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            strokeBuffer.add(PointF(x, y))
        }
        override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) {
            strokeBuffer.add(PointF(x, y))
            val docDims = docBitmap?.let { "${it.width}x${it.height}" } ?: "null"
            Log.i(TAG, "cb.end buf=${strokeBuffer.size} doc=$docDims scrollY=$scrollY")
            replayStrokeIntoDocument()
            commitWindowToSurface()
            // Re-prime the daemon overlay so its ION buffer matches the new
            // document state for the visible window. Soft sync (no force).
            windowBitmap?.let { ink.syncOverlay(it, force = false) }
            strokeBuffer.clear()
            dirtyListener?.invoke()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        ensureBitmaps()
        commitWindowToSurface()

        if (width > 0 && height > 0) {
            val limit = Rect(0, 0, width, height)
            if (ink.attach(this, limit, strokeCallback)) {
                Log.i(TAG, "${ink.javaClass.simpleName} attached")
                applyStrokeStyle()
                windowBitmap?.let { ink.syncOverlay(it, force = false) }
            } else {
                Log.i(TAG, "Ink controller did not attach — using fallback")
            }
        }
        surfaceReadyListener?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        ensureBitmaps()
        commitWindowToSurface()
        surfaceReadyListener?.invoke()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        ink.detach()
        // docBitmap / windowBitmap are intentionally retained: they are off-
        // screen buffers, independent of the surface, and recycling them on
        // every surface destroy would discard the user's note when the surface
        // is destroyed-and-recreated (e.g. brief backgrounding) without a
        // matching reload from disk.
    }

    /** Switch the underlying document to [bmp]. Caller owns lifecycle of any
     *  previous bitmap returned by [takeDocBitmap]. */
    fun setDocBitmap(bmp: Bitmap, scrollYReset: Int = 0) {
        docBitmap?.recycle()
        docBitmap = bmp
        scrollY = scrollYReset.coerceIn(0, maxScrollY())
        ensureWindowBitmap()
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = true) }
        scrollListener?.invoke(scrollY, maxScrollY())
    }

    /** Set document with saved content (ink-only, transparent background) and background type. */
    fun setDocBitmapWithContent(content: Bitmap, bgType: Int, scrollYReset: Int = 0) {
        docBitmap?.recycle()
        // docBitmap holds ink strokes on a TRANSPARENT background.
        // Background patterns are composited in rebuildWindowFromDoc, not baked here.
        val fresh = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(fresh).drawBitmap(content, 0f, 0f, null)
        content.recycle()
        backgroundType = bgType
        docBitmap = fresh
        scrollY = scrollYReset.coerceIn(0, maxScrollY())
        ensureWindowBitmap()
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = true) }
        scrollListener?.invoke(scrollY, maxScrollY())
    }

    /** Set the background pattern type.
     *  0 = none, 1 = ruled (horizontal lines), 2 = dot grid, 3 = grid */
    fun setBackground(type: Int) {
        if (type == backgroundType) return
        backgroundType = type
        // Background is rendered in rebuildWindowFromDoc, not baked into docBitmap
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = true) }
    }

    /** Detach the doc bitmap from the view (caller takes ownership). */
    fun takeDocBitmap(): Bitmap? {
        val b = docBitmap
        docBitmap = null
        return b
    }

    /** Read-only handle to the document bitmap. Caller must not recycle. */
    fun peekDocBitmap(): Bitmap? = docBitmap

    fun docWidth(): Int = docBitmap?.width ?: width
    fun docHeight(): Int = docBitmap?.height ?: (height * documentHeightFactor)

    fun setStrokeStyle(widthPx: Float, color: Int) {
        strokeWidth = widthPx
        strokeColor = color
        isEraser = false
        applyStrokeStyle()
    }

    fun setEraser(enabled: Boolean) {
        isEraser = enabled
        applyStrokeStyle()
    }

    private fun applyStrokeStyle() {
        if (isEraser) {
            eraserPaint.strokeWidth = eraserWidth
            strokePaint.color = strokeColor // keep strokePaint valid for mode checks
            ink.setStrokeStyle(eraserWidth, Color.WHITE) // daemon may still expect white
        } else {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidth
            ink.setStrokeStyle(strokeWidth, strokeColor)
        }
    }

    fun clearCurrent() {
        val doc = docBitmap ?: return
        // Clear to transparent so background pattern shows through
        Canvas(doc).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = true) }
        ink.resetDiagnostics()
        dirtyListener?.invoke()
    }

    fun maxScrollY(): Int = (docHeight() - height).coerceAtLeast(0)
    fun getScrollOffsetY(): Int = scrollY

    private fun setScroll(newY: Int) {
        val clamped = newY.coerceIn(0, maxScrollY())
        if (clamped == scrollY) return
        scrollY = clamped
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = true) }
        scrollListener?.invoke(scrollY, maxScrollY())
    }

    private fun isPenInput(event: MotionEvent): Boolean {
        val t = event.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pen events: daemon handles them (or fallback path below).
        if (isPenInput(event)) {
            if (ink.consumesMotionEvents) return false
            return handleFallbackPen(event)
        }
        // Finger / mouse path: navigation, scroll, optionally drawing.
        return handleFingerEvent(event)
    }

    private fun handleFingerEvent(event: MotionEvent): Boolean {
        // When touch is disabled, reject all finger input (no drawing, no scrolling)
        if (!touchEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerActive = true
                fingerDownX = event.x; fingerDownY = event.y
                fingerLastX = event.x; fingerLastY = event.y
                fingerMode = FingerMode.UNDECIDED
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fingerActive) return false
                val dx = event.x - fingerDownX
                val dy = event.y - fingerDownY
                if (fingerMode == FingerMode.UNDECIDED) {
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        fingerMode = when {
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f -> FingerMode.SWIPE
                            kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f -> FingerMode.SCROLL
                            else -> FingerMode.DRAW
                        }
                    } else return true
                }
                when (fingerMode) {
                    FingerMode.SCROLL -> {
                        val delta = (fingerLastY - event.y).toInt()
                        if (delta != 0) setScroll(scrollY + delta)
                    }
                    FingerMode.SWIPE -> Unit
                    FingerMode.DRAW -> {
                        drawFingerSegment(event)
                    }
                    FingerMode.UNDECIDED -> Unit
                }
                fingerLastX = event.x; fingerLastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!fingerActive) return false
                val dx = event.x - fingerDownX
                val dy = event.y - fingerDownY
                if (fingerMode == FingerMode.SWIPE && kotlin.math.abs(dx) > swipeThreshold &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    navListener?.onSwipe(if (dx < 0) 1 else -1)
                }
                if (fingerMode == FingerMode.DRAW) dirtyListener?.invoke()
                fingerActive = false
                fingerMode = FingerMode.UNDECIDED
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                fingerActive = false
                fingerMode = FingerMode.UNDECIDED
                return true
            }
        }
        return false
    }

    private fun drawFingerSegment(event: MotionEvent) {
        val doc = docBitmap ?: return
        val canvas = Canvas(doc)
        val ax = fingerLastX
        val ay = fingerLastY + scrollY
        val bx = event.x
        val by = event.y + scrollY
        val paint = if (isEraser) eraserPaint else strokePaint
        canvas.drawLine(ax, ay, bx, by, paint)
        rebuildWindowFromDoc()
        commitWindowToSurface()
        windowBitmap?.let { ink.syncOverlay(it, force = false) }
    }

    private fun handleFallbackPen(event: MotionEvent): Boolean {
        val doc = docBitmap ?: return false
        val paint = if (isEraser) eraserPaint else strokePaint
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                fallbackPenDown = true
                fallbackPenLastX = event.x
                fallbackPenLastY = event.y + scrollY
                // Tap / short-flick visibility: drawLine alone never fires for a
                // DOWN-then-UP, so a tap would leave nothing. Paint a dot now.
                Canvas(doc).drawPoint(fallbackPenLastX, fallbackPenLastY, paint)
                rebuildWindowFromDoc()
                commitWindowToSurface()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fallbackPenDown) return false
                val canvas = Canvas(doc)
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i) + scrollY
                    canvas.drawLine(fallbackPenLastX, fallbackPenLastY, hx, hy, paint)
                    fallbackPenLastX = hx; fallbackPenLastY = hy
                }
                val ex = event.x; val ey = event.y + scrollY
                canvas.drawLine(fallbackPenLastX, fallbackPenLastY, ex, ey, paint)
                fallbackPenLastX = ex; fallbackPenLastY = ey
                rebuildWindowFromDoc()
                commitWindowToSurface()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fallbackPenDown = false
                dirtyListener?.invoke()
            }
        }
        return true
    }

    /** Replay the daemon's stroke buffer into the document bitmap so the
     *  view's persistent state matches what the user just saw on the EPD.
     *  Coords arrive view-local; translate by [scrollY] to document-local. */
    private fun replayStrokeIntoDocument() {
        val doc = docBitmap ?: return
        if (strokeBuffer.isEmpty()) return
        val canvas = Canvas(doc)
        val paint = if (isEraser) eraserPaint else strokePaint
        // Always paint a dot at the down point: a tap produces a buffer of two
        // near-identical points, and drawLine on a degenerate segment is not
        // guaranteed to render. The daemon paints a matching dot on its EPD
        // overlay; mirror that into the persisted document so the bullet
        // survives a save/load round-trip.
        val first = strokeBuffer[0]
        canvas.drawPoint(first.x, first.y + scrollY, paint)
        for (i in 1 until strokeBuffer.size) {
            val a = strokeBuffer[i - 1]
            val b = strokeBuffer[i]
            canvas.drawLine(a.x, a.y + scrollY, b.x, b.y + scrollY, paint)
        }
        rebuildWindowFromDoc()
    }

    private fun ensureBitmaps() {
        ensureDocBitmap()
        ensureWindowBitmap()
        rebuildWindowFromDoc()
    }

    private fun ensureDocBitmap() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val target = h * documentHeightFactor
        val current = docBitmap
        if (current != null && current.width == w && current.height == target) return
        // Transparent background — ink strokes only; background rendered in rebuildWindowFromDoc
        val fresh = Bitmap.createBitmap(w, target, Bitmap.Config.ARGB_8888)
        if (current != null) {
            Canvas(fresh).drawBitmap(current, 0f, 0f, null)
            current.recycle()
        }
        docBitmap = fresh
    }

    private fun ensureWindowBitmap() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val current = windowBitmap
        if (current != null && current.width == w && current.height == h) return
        current?.recycle()
        windowBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }
    }

    private fun rebuildWindowFromDoc() {
        val window = windowBitmap ?: return
        val doc = docBitmap ?: return
        val canvas = Canvas(window)
        // 1. White base
        canvas.drawColor(Color.WHITE)
        // 2. Background pattern on top of white
        if (backgroundType > 0) drawBackgroundPatternOnCanvas(canvas)
        // 3. Ink strokes (transparent-background docBitmap) composited over pattern
        canvas.drawBitmap(doc, 0f, -scrollY.toFloat(), null)
    }

    private fun drawBackgroundPatternOnCanvas(canvas: Canvas) {
        val lineSpacing = 90f
        val dotSpacing = 90f
        val ruledMarginTop = 150f
        when (backgroundType) {
            1 -> drawRuledLinesOnCanvas(canvas, lineSpacing, ruledMarginTop)
            2 -> drawDotGridOnCanvas(canvas, dotSpacing)
            3 -> drawGridOnCanvas(canvas, dotSpacing)
        }
    }

    private fun drawRuledLinesOnCanvas(canvas: Canvas, lineSpacing: Float, marginTop: Float) {
        val docHeight = docBitmap?.height ?: return
        val offsetY = -scrollY.toFloat()

        // Draw margin line (red-ish line on the left) — spans the full visible window
        val marginPaint = Paint().apply {
            color = Color.parseColor("#C86060") // Red for margin
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(60f, 0f, 60f, height.toFloat(), marginPaint)

        // Draw horizontal ruled lines in document space, translated by scroll
        var y = marginTop
        while (y < docHeight) {
            val drawY = y + offsetY
            if (drawY >= 0f && drawY <= height.toFloat()) {
                canvas.drawLine(0f, drawY, width.toFloat(), drawY, backgroundPaint)
            }
            y += lineSpacing
        }
    }

    private fun drawDotGridOnCanvas(canvas: Canvas, spacing: Float) {
        val docHeight = docBitmap?.height ?: return
        val dotRadius = 3f
        val offsetY = -scrollY.toFloat()

        var x = spacing / 4
        while (x <= width) {
            var y = spacing
            while (y < docHeight) {
                val drawY = y + offsetY
                if (drawY >= -dotRadius && drawY <= height.toFloat() + dotRadius) {
                    canvas.drawCircle(x, drawY, dotRadius, dotPaint)
                }
                y += spacing
            }
            x += spacing
        }
    }

    private fun drawGridOnCanvas(canvas: Canvas, spacing: Float) {
        val docHeight = docBitmap?.height ?: return
        val offsetY = -scrollY.toFloat()

        // Horizontal lines — start at `spacing` so the first row matches the rest
        var y = spacing
        while (y < docHeight) {
            val drawY = y + offsetY
            if (drawY >= 0f && drawY <= height.toFloat()) {
                canvas.drawLine(0f, drawY, width.toFloat(), drawY, backgroundPaint)
            }
            y += spacing
        }

        // Vertical lines — full visible window height (don't scroll horizontally)
        var x = spacing / 4
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), backgroundPaint)
            x += spacing
        }
    }

    private fun commitWindowToSurface() {
        if (!surfaceReady) return
        val window = windowBitmap ?: return
        val canvas = holder.lockCanvas() ?: return
        try { canvas.drawBitmap(window, 0f, 0f, null) }
        finally { holder.unlockCanvasAndPost(canvas) }
    }

    fun isOverlayActive(): Boolean = ink.isActive
    fun setOverlayEnabled(enabled: Boolean) = ink.setEnabled(enabled)

    companion object { private const val TAG = "InkSurfaceView" }
}
