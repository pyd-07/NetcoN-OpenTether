package com.opentether.runtime

import com.opentether.data.TunnelTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelRuntimeHolderTest {
    @Before
    fun resetBefore() {
        TunnelRuntimeHolder.onStopped()
    }

    @After
    fun resetAfter() {
        TunnelRuntimeHolder.onStopped()
    }

    @Test
    fun startupErrorClearsRunningState() {
        TunnelRuntimeHolder.onServiceStarting(TunnelTransport.ADB)
        assertTrue(TunnelRuntimeHolder.state.value.isRunning)

        TunnelRuntimeHolder.onStartupError(TunnelTransport.ADB, "startup failed")

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.Error, state.phase)
        assertFalse(state.isRunning)
        assertEquals("startup failed", state.lastError)
        assertNull(state.sessionStartedAtMs)
    }

    @Test
    fun runtimeErrorKeepsRunningStateForReconnect() {
        TunnelRuntimeHolder.onServiceStarting(TunnelTransport.ADB)
        TunnelRuntimeHolder.onTransportConnected(TunnelTransport.ADB, "connected")

        TunnelRuntimeHolder.onError(TunnelTransport.ADB, "relay error")

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.Error, state.phase)
        assertTrue(state.isRunning)
        assertEquals("relay error", state.lastError)
    }

    @Test
    fun permissionDenialClearsRunningStateAndError() {
        TunnelRuntimeHolder.onServiceStarting(TunnelTransport.ADB)
        TunnelRuntimeHolder.onStartupError(TunnelTransport.ADB, "old failure")

        TunnelRuntimeHolder.onPermissionDenied()

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.Idle, state.phase)
        assertFalse(state.isRunning)
        assertNull(state.lastError)
    }

    @Test
    fun stoppedStateClearsLastError() {
        TunnelRuntimeHolder.onStartupError(TunnelTransport.ADB, "failed")

        TunnelRuntimeHolder.onStopped()

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.Idle, state.phase)
        assertFalse(state.isRunning)
        assertNull(state.lastError)
    }
}
