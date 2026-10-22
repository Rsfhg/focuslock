package com.local.focuslock.scheduling
import org.junit.Assert.*
import org.junit.Test
class WarningDeliveryTest {
    @Test fun notificationDenialStillAllowsForegroundWarning() {
        var shown = 0
        assertTrue(WarningDelivery.deliver(false, { shown++; true }, { fail("notification forbidden"); false }))
        assertEquals(1, shown)
    }
    @Test fun undeliverableBackgroundWarningIsNotConsumed() {
        assertFalse(WarningDelivery.deliver(false, { false }, { fail("notification forbidden"); false }))
    }
}
