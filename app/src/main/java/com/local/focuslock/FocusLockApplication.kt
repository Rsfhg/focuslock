package com.local.focuslock
import android.app.Application
class FocusLockApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
