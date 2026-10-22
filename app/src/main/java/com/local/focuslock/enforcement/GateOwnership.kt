package com.local.focuslock.enforcement
class GateOwnership {
    private var generation = 0L
    fun replace(): Long = ++generation
    fun ticket(): Long = generation
    fun owns(ticket: Long): Boolean = ticket == generation
}
