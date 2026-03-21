package com.opentether.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLogLevel { DEBUG, INFO, WARN, ERROR }

data class AppLogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
)

object AppLogger {
    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private val buffer = ArrayDeque<AppLogEntry>(MAX_ENTRIES)
    private var nextId = 1L

    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>> = _logs.asStateFlow()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append(AppLogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append(AppLogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append(AppLogLevel.WARN, tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        append(AppLogLevel.ERROR, tag, message)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }

    private fun append(level: AppLogLevel, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size == MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(
                AppLogEntry(
                    id = nextId++,
                    timestampMs = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message,
                ),
            )
            _logs.value = buffer.toList()
        }
    }
}
