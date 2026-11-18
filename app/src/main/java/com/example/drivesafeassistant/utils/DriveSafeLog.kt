package com.example.drivesafeassistant.utils

import android.util.Log

object DriveSafeLog {
    private const val TAG = "DriveSafe"
    fun call(function: String, detail: String = "") {
        Log.w(TAG, if (detail.isEmpty()) function else "$function | $detail")
    }
}
