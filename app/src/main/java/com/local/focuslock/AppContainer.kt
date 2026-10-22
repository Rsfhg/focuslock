package com.local.focuslock
import android.content.Context
import androidx.room.Room
import com.local.focuslock.data.*
import com.local.focuslock.domain.IdSource
import com.local.focuslock.scheduling.SessionScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = AndroidClockSource(context)
    val catalog = AppCatalog(context)
    val database = Room.databaseBuilder(context, FocusDatabase::class.java, "focuslock.db").build()
    val repository = SessionRepository(database, clock, IdSource { UUID.randomUUID().toString() }, catalog::allowed)
    val scheduler = SessionScheduler(context, repository, clock)
    init {
        scope.launch {
            try { repository.reconcile() } catch (_: Exception) { }
            repository.sessions.collect { scheduler.reschedule(it, clock.sample()) }
        }
    }
}
val Context.appContainer: AppContainer get() = (applicationContext as FocusLockApplication).container
