package com.local.focuslock.domain
import org.junit.Assert.*
import org.junit.Test
class DurationInputTest {
    @Test fun onlyValidWholeMinutesAccepted() {
        for (s in listOf("", " ", "a", "-1", "0", "1.5", "16", "9".repeat(100))) assertNull(DurationInput.parse(s, 15))
        assertEquals(15, DurationInput.parse("15", 15))
    }
}
