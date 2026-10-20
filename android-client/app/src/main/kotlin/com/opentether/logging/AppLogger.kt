package com.opentether.logging

import android.util.Log

/**
 * Thin Logcat facade used by the VPN and transport layers.
 *
 * The old in-memory log buffer existed only for the removed in-app log viewer.
 * Keeping logging here means there is no second, UI-owned log store to observe
 * or clear, and no unbounded list copy on every log call.
 */
object AppLogger {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
