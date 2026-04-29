package com.inksdk.ink

import android.graphics.Rect
import android.util.Log
import android.view.SurfaceView

/**
 * Onyx Boox raw-drawing controller stub.
 * 
 * This is a placeholder - the actual Onyx SDK (com.onyx.android.sdk) is not
 * available in standard Maven repositories. For the full Onyx implementation,
 * refer to the original inksdk repository.
 * 
 * On non-Onyx devices, this controller will fail to attach and the app will
 * fall back to standard MotionEvent + Canvas rendering.
 */
class OnyxInkController : InkController {

    override var isActive: Boolean = false
        private set

    override val consumesMotionEvents: Boolean get() = isActive

    override val ownsSurface: Boolean get() = isActive

    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean {
        Log.w(TAG, "OnyxInkController: Onyx SDK not available, falling back to Canvas path")
        return false
    }

    override fun setStrokeStyle(widthPx: Float, color: Int) {
        // No-op
    }

    override fun setEnabled(enabled: Boolean) {
        // No-op
    }

    override fun detach() {
        isActive = false
    }

    companion object {
        private const val TAG = "OnyxInkController"
    }
}