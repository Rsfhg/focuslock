package com.local.focuslock.ui
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class MainActivityTest {
    @Test fun firstLaunchCreatesContentWithoutPermissions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            assertNotNull(activity.window.decorView.findViewById<android.view.View>(android.R.id.content))
        } finally { controller.pause().stop().destroy() }
    }
}
