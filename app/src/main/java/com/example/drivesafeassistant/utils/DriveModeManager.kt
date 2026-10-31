package com.example.drivesafeassistant.utils

import android.content.Context

object DriveModeManager {
    private const val PREFS_NAME = "drive_safe_prefs"
    private const val KEY_DRIVE_MODE = "drive_mode_active"

    fun isActive(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DRIVE_MODE, false)
    }

    fun setActive(context: Context, active: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DRIVE_MODE, active)
            .apply()
    }
}
