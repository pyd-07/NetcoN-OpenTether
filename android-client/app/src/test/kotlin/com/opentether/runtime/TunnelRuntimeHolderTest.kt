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
    fun permissionRequestShowsWaitingStateWithoutStartingVpn() {
        TunnelRuntimeHolder.onPermissionRequested(TunnelTransport.ADB)

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.AwaitingPermission, state.phase)
        assertFalse(state.isRunning)
        assertNull(state.lastError)
    }

    @Test
    fun permissionDenialClearsRunningStateAndError() {
        TunnelRuntimeHolder.onServiceStarting(TunnelTransport.ADB)
        TunnelRuntimeHolder.onStartupError(TunnelTransport.ADB, "old failure")

        TunnelRuntimeHolder.onPermissionDenied()

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.PermissionDenied, state.phase)
        assertFalse(state.isRunning)
        assertNull(state.lastError)
    }

    @Test
    fun reconnectClearsPreviousSessionStartAndStaleError() {
        TunnelRuntimeHolder.onServiceStarting(TunnelTransport.ADB)
        TunnelRuntimeHolder.onTransportConnected(TunnelTransport.ADB, "connected", nowMs = 1_234L)
        assertEquals(1_234L, TunnelRuntimeHolder.state.value.sessionStartedAtMs)

        TunnelRuntimeHolder.onError(TunnelTransport.ADB, "relay error")
        TunnelRuntimeHolder.onTransportDisconnected(TunnelTransport.ADB, "retrying in 2000 ms")

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.AwaitingTransport, state.phase)
        assertTrue(state.isRunning)
        assertNull("reconnect must not keep the previous session start", state.sessionStartedAtMs)
        assertNull("reconnect must not keep a stale error", state.lastError)
    }

    @Test
    fun reconnectThenConnectStartsANewSessionClock() {
        TunnelRuntimeHolder.onTransportConnected(TunnelTransport.ADB, "first", nowMs = 1_000L)
        TunnelRuntimeHolder.onTransportDisconnected(TunnelTransport.ADB, "dropped")
        TunnelRuntimeHolder.onTransportConnected(TunnelTransport.ADB, "second", nowMs = 9_000L)

        val state = TunnelRuntimeHolder.state.value
        assertEquals(TunnelPhase.Connected, state.phase)
        assertEquals(9_000L, state.sessionStartedAtMs)
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
