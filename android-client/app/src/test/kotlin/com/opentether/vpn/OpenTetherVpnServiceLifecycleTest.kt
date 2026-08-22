package com.opentether.vpn

import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTetherVpnServiceLifecycleTest {
    @Test
    fun serviceActionsAreDefined() {
        assertTrue(ACTION_START.isNotBlank())
        assertTrue(ACTION_STOP.isNotBlank())
        assertTrue(ACTION_START != ACTION_STOP)
    }
}
