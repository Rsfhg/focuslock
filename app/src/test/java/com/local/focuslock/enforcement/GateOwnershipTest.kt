package com.local.focuslock.enforcement
import org.junit.Assert.*
import org.junit.Test
class GateOwnershipTest {
    @Test fun delayedApplicationCompletionCannotDismissReplacementGate() {
        val gates = GateOwnership()
        gates.replace()
        val pendingA = gates.ticket()
        gates.replace()
        val pendingB = gates.ticket()
        val dismissed = mutableListOf<String>()
        if (gates.owns(pendingA)) dismissed.add("B")
        assertTrue(dismissed.isEmpty())
        assertTrue(gates.owns(pendingB))
    }
}
