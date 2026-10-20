package com.opentether.ui

import com.opentether.data.TunnelTransport
import com.opentether.runtime.TunnelPhase
import com.opentether.runtime.TunnelRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPresentationTest {
    @Test
    fun idleOffersOnlyConnect() {
        val presentation = connectionPresentation(TunnelRuntimeState())

        assertEquals(TunnelPhase.Idle, presentation.phase)
        assertEquals(PrimaryAction.Connect, presentation.action)
        assertEquals("Connect", presentation.actionLabel)
        assertFalse(presentation.showDetails)
    }

    @Test
    fun connectedOffersDisconnectAndReliableDuration() {
        val state = TunnelRuntimeState(
            phase = TunnelPhase.Connected,
            isRunning = true,
            sessionStartedAtMs = 1_000L,
        )

        val presentation = connectionPresentation(state)

        assertEquals(PrimaryAction.Disconnect, presentation.action)
        assertEquals("Disconnect", presentation.actionLabel)
        assertTrue(presentation.showDuration)
    }

    @Test
    fun permissionRequestCannotLaunchDuplicateConnection() {
        val presentation = connectionPresentation(
            TunnelRuntimeState(phase = TunnelPhase.AwaitingPermission),
        )

        assertEquals(PrimaryAction.None, presentation.action)
        assertTrue(presentation.isBusy)
        assertFalse(presentation.showDetails)
    }

    @Test
    fun permissionDenialOffersRetry() {
        val presentation = connectionPresentation(
            TunnelRuntimeState(phase = TunnelPhase.PermissionDenied),
        )

        assertEquals(PrimaryAction.Connect, presentation.action)
        assertEquals("Try again", presentation.actionLabel)
        assertFalse(presentation.showDetails)
    }

    @Test
    fun runningErrorKeepsDisconnectAndShowsDetails() {
        val presentation = connectionPresentation(
            TunnelRuntimeState(
                phase = TunnelPhase.Error,
                isRunning = true,
                transport = TunnelTransport.ADB,
                lastError = "relay error",
            ),
        )

        assertEquals(PrimaryAction.Disconnect, presentation.action)
        assertTrue(presentation.showDetails)
        assertEquals(ConnectionTone.Warning, presentation.tone)
    }

    @Test
    fun stoppedErrorOffersRetryAndTransportSpecificGuidance() {
        val presentation = connectionPresentation(
            TunnelRuntimeState(
                phase = TunnelPhase.Error,
                isRunning = false,
                transport = TunnelTransport.AOA,
                lastError = "accessory closed",
            ),
        )

        assertEquals(PrimaryAction.Connect, presentation.action)
        assertTrue(presentation.showDetails)
        assertTrue(presentation.description.contains("accessory"))
    }

    @Test
    fun diagnosticSummaryIsBoundedAndSanitized() {
        val summary = buildDiagnosticSummary(
            runtime = TunnelRuntimeState(
                phase = TunnelPhase.Error,
                isRunning = false,
                lastError = "line one\nline two\twith tabs",
            ),
            diagnostics = null,
            appVersion = "1.2.3",
        )

        assertTrue(summary.contains("OpenTether 1.2.3"))
        assertTrue(summary.contains("Last error: line one line two with tabs"))
        assertFalse(summary.contains("\n\n"))
    }
}
