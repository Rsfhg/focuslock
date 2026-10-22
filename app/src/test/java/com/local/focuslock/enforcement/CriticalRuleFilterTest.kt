package com.local.focuslock.enforcement
import com.local.focuslock.domain.*
import org.junit.Assert.*
import org.junit.Test
class CriticalRuleFilterTest {
    @Test fun coldLoadDoesNotPublishUnvalidatedDialerRule() {
        val filter = CriticalRuleFilter("ours")
        val rules = listOf(Rule("phone", Mode.COOLDOWN), Rule("game", Mode.FREE))
        assertTrue(filter.filter(rules).isEmpty())
        filter.update(setOf("phone", "home"))
        assertEquals(setOf("game"), filter.filter(rules).keys)
    }
    @Test fun unrelatedRuleEditPreservesKnownCriticalExclusions() {
        val filter = CriticalRuleFilter("ours")
        filter.update(setOf("phone", "home"))
        assertFalse("phone" in filter.filter(listOf(Rule("phone", Mode.FREE), Rule("game", Mode.FREE, 30))))
    }
}
