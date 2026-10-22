package com.local.focuslock.scheduling
import com.local.focuslock.domain.*
object SchedulePolicy {
    fun shouldWarn(session: Session, now: ClockSample): Boolean = !session.warned && SessionPolicy.status(session, now).let { it.phase == Phase.USING && it.remainingMs <= 60_000 }
    fun matches(session: Session?, id: String): Boolean = session?.id == id
}
