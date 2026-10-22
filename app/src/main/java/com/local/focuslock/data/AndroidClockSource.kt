package com.local.focuslock.data
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.local.focuslock.domain.*
class AndroidClockSource(private val context: Context) : ClockSource {
    override fun sample() = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (_: Exception) { -1L })
}
