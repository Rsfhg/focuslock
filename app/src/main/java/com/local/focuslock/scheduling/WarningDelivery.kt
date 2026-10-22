package com.local.focuslock.scheduling
object WarningDelivery {
    fun deliver(canNotify: Boolean, foreground: () -> Boolean, notification: () -> Boolean): Boolean = foreground() || (canNotify && notification())
}
