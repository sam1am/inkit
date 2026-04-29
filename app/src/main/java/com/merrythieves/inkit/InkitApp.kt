package com.merrythieves.inkit

import android.app.Application
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class InkitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "HiddenApiBypass failed: ${t.message}")
            }
        }
    }
    companion object { private const val TAG = "InkitApp" }
}
