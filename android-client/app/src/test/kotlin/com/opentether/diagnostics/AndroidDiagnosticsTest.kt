package com.opentether.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDiagnosticsTest {
    @Test
    fun api26IsSupported() {
        val result = AndroidDiagnosticsProvider.compatibilityForApi(26)

        assertEquals("Supported", result.first)
        assertTrue(result.second.contains("Android 8"))
    }

    @Test
    fun api31MentionsBackgroundStartRestrictions() {
        val result = AndroidDiagnosticsProvider.compatibilityForApi(31)

        assertEquals("Supported", result.first)
        assertTrue(result.second.contains("background-start restrictions"))
    }

    @Test
    fun api34MentionsForegroundServiceTypes() {
        val result = AndroidDiagnosticsProvider.compatibilityForApi(34)

        assertEquals("Supported", result.first)
        assertTrue(result.second.contains("foreground-service type"))
    }

    @Test
    fun belowMinimumApiIsUnsupported() {
        val result = AndroidDiagnosticsProvider.compatibilityForApi(25)

        assertEquals("Unsupported", result.first)
    }
}
