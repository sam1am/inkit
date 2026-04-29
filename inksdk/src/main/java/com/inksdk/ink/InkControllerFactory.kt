package com.inksdk.ink

import android.os.Build

/**
 * Picks the best [InkController] for this device.
 *
 *  1. Bigme — `Build.MANUFACTURER == "Bigme"` → [BigmeInkController]
 *     (com.xrz.HandwrittenClient daemon path).
 *  2. Otherwise → [OnyxInkController], which fails cleanly on non-Onyx devices.
 *
 * The decision is finalised inside [InkController.attach]: each candidate
 * either succeeds or returns false. The host should fall back to its
 * MotionEvent + Canvas path when [InkController.isActive] is false post-attach.
 */
object InkControllerFactory {

    fun create(): InkController =
        if (BigmeInkController.isBigmeDevice()) BigmeInkController()
        else OnyxInkController()

    fun createNoop(): InkController = NoopInkController

    /** True iff the current device is identified as a Bigme device. */
    fun isBigmeDevice(): Boolean =
        Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
            Build.BRAND.equals("Bigme", ignoreCase = true)
}
