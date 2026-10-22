package com.local.focuslock.data
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.focuslock.domain.*
import com.local.focuslock.domain.Rule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class SessionRepositoryTest {
    private lateinit var db: FocusDatabase
    private lateinit var repo: SessionRepository
    private var now = ClockSample(1_000_000, 1000, 1)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FocusDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db, ClockSource { now }, IdSource { java.util.UUID.randomUUID().toString() }) { it != "system" }
    }
    @After fun cleanup() { db.close() }
    @Test fun doubleRequestCreatesOnlyOneSession() = runBlocking {
        assertEquals(MutationResult.APPLIED, repo.saveRule(Rule("a", Mode.FREE)))
        val results = coroutineScope { listOf(async { repo.request("a", 1) }, async { repo.request("a", 1) }).awaitAll() }
        assertEquals(1, results.count { it == MutationResult.APPLIED })
        assertEquals(1, results.count { it == MutationResult.ACTIVE_SESSION })
    }
    @Test fun activeSessionRejectsEveryRuleMutation() = runBlocking {
        repo.saveRule(Rule("a", Mode.COOLDOWN)); repo.request("a", 10)
        for (ms in listOf(0L, 600_000L)) {
            now = ClockSample(1_000_000 + ms, 1000 + ms, 1)
            assertEquals(MutationResult.ACTIVE_SESSION, repo.removeRule("a"))
            assertEquals(MutationResult.ACTIVE_SESSION, repo.saveRule(Rule("a", Mode.FREE)))
        }
        now = ClockSample(2_500_000, 1_501_000, 1)
        assertEquals(MutationResult.APPLIED, repo.removeRule("a"))
    }
    @Test fun reminderIsIdempotentAndOldCallbackIgnored() = runBlocking {
        repo.saveRule(Rule("a", Mode.FREE)); repo.request("a", 1)
        val id = repo.session("a")!!.id
        assertFalse(repo.markWarned("a", "wrong"))
        assertTrue(repo.markWarned("a", id)); assertFalse(repo.markWarned("a", id))
    }
    @Test fun forbiddenAndInvalidRulesAreRejected() = runBlocking {
        assertEquals(MutationResult.INVALID_INPUT, repo.saveRule(Rule("system", Mode.FREE)))
        assertEquals(MutationResult.INVALID_INPUT, repo.saveRule(Rule("a", Mode.FREE, 0)))
    }
    @Test fun writeFailureDoesNotGrantAccess() = runBlocking {
        repo.saveRule(Rule("a", Mode.FREE))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'write rejected'); END")
        assertEquals(MutationResult.STORAGE_ERROR, repo.request("a", 1))
        assertNull(repo.session("a"))
    }
    @Test fun fileDatabaseReopenPreservesSessionAndRebootCooldown() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "persist-${java.util.UUID.randomUUID()}.db"
        var persistent = Room.databaseBuilder(ctx, FocusDatabase::class.java, name).build()
        try {
            var store = SessionRepository(persistent, ClockSource { now }, IdSource { "fixed-session" })
            store.saveRule(Rule("game", Mode.COOLDOWN)); store.request("game", 10)
            val saved = store.session("game")!!
            persistent.close()
            now = ClockSample(2_200_000, 1000, 2)
            persistent = Room.databaseBuilder(ctx, FocusDatabase::class.java, name).build()
            store = SessionRepository(persistent, ClockSource { now }, IdSource { "new" })
            store.reconcile()
            assertEquals(saved.id, store.session("game")!!.id)
            assertEquals(Status(Phase.COOLING, 300_000), store.status("game"))
        } finally { persistent.close(); ctx.deleteDatabase(name) }
    }
}

