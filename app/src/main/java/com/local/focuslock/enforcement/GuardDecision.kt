package com.local.focuslock.enforcement
import com.local.focuslock.domain.*
enum class GuardAction { ALLOW, REQUEST, COOLING, EXIT }
object GuardDecision {
    fun decide(target: String, foreground: String?, status: Status, wasUsing: Boolean): GuardAction = when {
        target != foreground -> GuardAction.ALLOW
        status.phase == Phase.USING -> GuardAction.ALLOW
        wasUsing -> GuardAction.EXIT
        status.phase == Phase.COOLING -> GuardAction.COOLING
        else -> GuardAction.REQUEST
    }
}
