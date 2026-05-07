package com.inksdk.ink

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Onyx Boox raw-drawing controller. Uses [TouchHelper] to paint strokes
 * directly at EPD refresh rate, with [com.onyx.android.sdk.api.device.epd.EpdController]
 * configuring the full-view ink region to avoid hardware dead zones.
 *
 * [attach] is the detection gate: on non-Onyx devices the SDK classes load
 * but the vendor runtime is missing, so the call throws. We catch and return
 * false, leaving [isActive] clear so the host falls back to the Canvas path.
 */
class OnyxInkController : InkController {

    override var isActive: Boolean = false
        private set

    override val consumesMotionEvents: Boolean get() = isActive

    // TouchHelper runs raw drawing directly on the host SurfaceView and
    // holds its surface lock — host-side `holder.lockCanvas()` while
    // active will block indefinitely.
    override val ownsSurface: Boolean get() = isActive

    private var touchHelper: TouchHelper? = null
    private var pendingWidth: Float = InkDefaults.DEFAULT_STROKE_WIDTH_PX
    private var pendingColor: Int = InkDefaults.DEFAULT_STROKE_COLOR

    private val strokeIndex = AtomicInteger(0)

    override fun resetDiagnostics() {
        strokeIndex.set(0)
    }

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        if (isActive) return true
        return try {
            touchHelper = TouchHelper.create(view, makeRawInputCallback(callback)).apply {
                setStrokeWidth(pendingWidth)
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setStrokeColor(pendingColor)
                setLimitRect(limit, emptyList())
                openRawDrawing()
                setRawDrawingEnabled(true)
            }
            try {
                com.onyx.android.sdk.api.device.epd.EpdController
                    .setScreenHandWritingRegionLimit(view)
            } catch (e: Exception) {
                Log.w(TAG, "EpdController.setScreenHandWritingRegionLimit failed: ${e.message}")
            }
            isActive = true
            Log.i(TAG, "Onyx ink controller attached: limitRect=$limit")
            true
        } catch (t: Throwable) {
            // Catch Throwable, not Exception — non-Onyx devices fail with
            // NoClassDefFoundError / UnsatisfiedLinkError when the vendor
            // runtime is missing, and those are Errors, not Exceptions.
            Log.w(TAG, "Onyx ink attach failed, falling back: ${t.message}")
            touchHelper = null
            isActive = false
            false
        }
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        pendingWidth = widthPx
        pendingColor = color
        if (!isActive) return
        try {
            touchHelper?.setStrokeWidth(widthPx)
            touchHelper?.setStrokeColor(color)
        } catch (e: Exception) {
            Log.w(TAG, "setStrokeStyle failed: ${e.message}")
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (!isActive) return
        try { touchHelper?.setRawDrawingEnabled(enabled) }
        catch (e: Exception) { Log.w(TAG, "setEnabled($enabled) failed: ${e.message}") }
    }

    override fun syncOverlay(bitmap: android.graphics.Bitmap, region: Rect?, force: Boolean) {
        // Onyx owns its raw-drawing buffer via TouchHelper — no need to blit
        // the host bitmap. When [force] is set, cycle the raw-drawing layer
        // so the EPD picks up the freshly-composed SurfaceView and the
        // overlay's cached ink (pre-mutation) is flushed.
        if (!force || !isActive) return
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawDrawingEnabled(true)
        } catch (e: Exception) { Log.w(TAG, "syncOverlay failed: ${e.message}") }
    }

    override fun detach() {
        if (!isActive) return
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.setRawInputReaderEnable(false)
            touchHelper?.closeRawDrawing()
        } catch (e: Exception) { Log.w(TAG, "Onyx ink detach error: ${e.message}") }
        touchHelper = null
        isActive = false
        Log.i(TAG, "Onyx ink controller detached")
    }

    private fun makeRawInputCallback(sink: StrokeCallback) = object : RawInputCallback() {

        private var downJvmNs = 0L
        private var firstMoveOfStroke = false
        private var dispatchEpoch: DispatchEpoch = DispatchEpoch.UNKNOWN
        private var dispatchProbeCount = 0

        // Cross-clock dispatch latency. TouchPoint.timestamp epoch varies
        // between Onyx firmwares — some populate uptimeMillis, some
        // currentTimeMillis, some leave it at 0. Auto-detect on the first
        // event and lock the epoch.
        private fun recordDispatch(tp: TouchPoint, metric: PerfMetric) {
            val tsMs = tp.timestamp
            if (tsMs <= 0L) {
                if (dispatchEpoch == DispatchEpoch.UNKNOWN && dispatchProbeCount < 3) {
                    dispatchProbeCount++
                    if (dispatchProbeCount == 3) {
                        dispatchEpoch = DispatchEpoch.UNAVAILABLE
                        Log.w(TAG, "dispatch metrics disabled — TouchPoint.timestamp not populated")
                    }
                }
                return
            }
            if (dispatchEpoch == DispatchEpoch.UNKNOWN) {
                val uptimeNow = SystemClock.uptimeMillis()
                val wallNow = System.currentTimeMillis()
                val uptimeDelta = uptimeNow - tsMs
                val wallDelta = wallNow - tsMs
                dispatchEpoch = when {
                    uptimeDelta in 0L..60_000L -> DispatchEpoch.UPTIME
                    wallDelta in 0L..60_000L -> DispatchEpoch.WALL
                    else -> DispatchEpoch.UNAVAILABLE
                }
                Log.i(TAG, "dispatch epoch locked: $dispatchEpoch")
                if (dispatchEpoch == DispatchEpoch.UNAVAILABLE) return
            }
            val deltaMs = when (dispatchEpoch) {
                DispatchEpoch.UPTIME -> SystemClock.uptimeMillis() - tsMs
                DispatchEpoch.WALL -> System.currentTimeMillis() - tsMs
                else -> return
            }
            if (deltaMs < 0L) return
            PerfCounters.recordDirect(metric, deltaMs * 1_000_000L)
        }

        override fun onBeginRawDrawing(b: Boolean, tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            recordDispatch(tp, PerfMetric.PEN_KERNEL_TO_JVM)
            downJvmNs = handlerStart
            firstMoveOfStroke = true
            val sIdx = strokeIndex.incrementAndGet()
            val k2jSnap = PerfCounters.get(PerfMetric.PEN_KERNEL_TO_JVM)
            val k2jMs = if (k2jSnap.count > 0L) k2jSnap.lastMs else -1L
            if (sIdx <= 10) {
                Log.i(TAG, "FIRST_STROKE #$sIdx: kernel_to_jvm=${k2jMs}ms")
            }
            if (k2jMs >= SLOW_STROKE_MS) {
                Log.i(TAG, "SLOW_STROKE #$sIdx @${wallClockHms()}: kernel_to_jvm=${k2jMs}ms")
            }
            try { sink.onStrokeBegin(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            if (firstMoveOfStroke) {
                firstMoveOfStroke = false
                if (downJvmNs != 0L) {
                    PerfCounters.recordDirect(
                        PerfMetric.PEN_JVM_TO_FIRST_MOVE,
                        handlerStart - downJvmNs,
                    )
                }
            }
            try { sink.onStrokeMove(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
            }
        }

        override fun onRawDrawingTouchPointListReceived(tpl: TouchPointList) {}

        override fun onEndRawDrawing(b: Boolean, tp: TouchPoint) {
            val handlerStart = System.nanoTime()
            recordDispatch(tp, PerfMetric.EVENT_KERNEL_TO_JVM)
            try { sink.onStrokeEnd(tp.x, tp.y, tp.pressure, tp.timestamp) }
            finally {
                PerfCounters.recordDirect(PerfMetric.EVENT_HANDLER, System.nanoTime() - handlerStart)
                downJvmNs = 0L
            }
        }

        override fun onBeginRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, tp: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(tp: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(tpl: TouchPointList) {}
    }

    private enum class DispatchEpoch { UNKNOWN, UPTIME, WALL, UNAVAILABLE }

    companion object {
        private const val TAG = "OnyxInkController"

        private const val SLOW_STROKE_MS = 30L

        private val wallClockFormatter = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        private fun wallClockHms(): String = wallClockFormatter.format(System.currentTimeMillis())
    }
}
