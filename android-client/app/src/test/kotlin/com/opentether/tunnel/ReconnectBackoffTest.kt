package com.opentether.tunnel

import com.opentether.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun backoffGrowsExponentiallyThenCaps() {
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(4_000L, reconnectDelayMs(2))
        assertEquals(8_000L, reconnectDelayMs(3))
        assertEquals(16_000L, reconnectDelayMs(4))
        assertEquals(30_000L, reconnectDelayMs(5))
        assertEquals(30_000L, reconnectDelayMs(6))
        assertEquals(30_000L, reconnectDelayMs(100))
    }

    @Test
    fun backoffStartsAtConfiguredMinimumForNonPositiveAttempts() {
        assertEquals(Constants.RECONNECT_DELAY_MS, reconnectDelayMs(0))
        assertEquals(Constants.RECONNECT_DELAY_MS, reconnectDelayMs(-3))
    }
}
