package com.local.focuslock.enforcement

import com.local.focuslock.domain.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSamplerTest {
    @Test fun slowWindowQueryDoesNotDelayRepeatedAppReentries() = runBlocking {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateDrawn = CountDownLatch(100)
        val host = object : GateHost {
            override fun show(pkg: String, rule: Rule, status: Status) { if (pkg == "game" && status.phase == Phase.READY) gateDrawn.countDown() }
            override fun dismiss() { }
            override fun returnHomeIfForeground(pkg: String) = HomeResult.UNCONFIRMED
            override fun warn(session: Session) { }
        }
        val guard = GateCoordinator("ours", ClockSource { ClockSample(1000, 1000, 1) }, host)
        val sampler = ForegroundSampler(scope, worker, {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            WindowReading(emptyList(), 1000)
        }, {}, { throw it })
        try {
            scope.launch { sampler.refresh(emptyMap()) }
            assertTrue("probe never started", started.await(2, TimeUnit.SECONDS))
            scope.launch {
                guard.updateSnapshot(EntrySnapshot(mapOf("game" to Rule("game", Mode.FREE)), emptyMap()))
                repeat(100) { guard.applicationOpened("home"); guard.applicationOpened("game") }
            }
            assertTrue("window query blocked the event/UI thread", gateDrawn.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); scope.cancel(); ui.close(); worker.close() }
    }

    @Test fun rapidRefreshesDoNotQueueDuplicateWindowQueries() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val sampler = ForegroundSampler(this, worker, {
            calls.incrementAndGet(); WindowReading(emptyList(), 1000)
        }, {}, { throw it })
        repeat(100) { sampler.refresh(emptyMap()) }
        advanceUntilIdle()
        assertEquals(1, calls.get())
    }

    @Test fun newApplicationSignalRejectsOlderInFlightLauncherReading() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val published = mutableListOf<WindowReading>()
        val sampler = ForegroundSampler(this, worker, {
            WindowReading(listOf(WindowFact(WindowKind.APP, "home", true, 0, 1)), 1000)
        }, { published.add(it) }, { throw it })
        sampler.refresh(mapOf(1 to "home"))
        sampler.invalidate() // A new game launch arrives while the recovery read is queued.
        advanceUntilIdle()
        assertTrue(published.isEmpty())
        assertNull(sampler.recent(1000))
    }

    @Test fun expiredCachedProofCannotReturnAnotherAppHome() = runTest {
        val sampler = ForegroundSampler(this, StandardTestDispatcher(testScheduler), {
            WindowReading(listOf(WindowFact(WindowKind.APP, "game", true, 0, 2)), 1000)
        }, {}, { throw it })
        sampler.refresh(emptyMap()); advanceUntilIdle()
        assertNotNull(sampler.recent(1001))
        assertNull(sampler.recent(20_000))
    }

    @Test fun homeProofMustBeSampledAfterUsageDeadline() = runTest {
        var capturedAt = 60_500L
        val session = SessionPolicy.create(Rule("game", Mode.FREE), 1, ClockSample(1000, 1000, 1), "s")
        val sampler = ForegroundSampler(this, StandardTestDispatcher(testScheduler), {
            WindowReading(listOf(WindowFact(WindowKind.APP, "game", true, 0, 2)), capturedAt)
        }, {}, { throw it })
        sampler.refresh(emptyMap()); advanceUntilIdle()
        assertNull("a pre-deadline game sample cannot prove foreground at expiry", sampler.expiryProof(session, ClockSample(61_000, 61_000, 1)))
        capturedAt = 61_100
        sampler.refresh(emptyMap()); advanceUntilIdle()
        assertEquals("game", sampler.expiryProof(session, ClockSample(61_200, 61_200, 1))?.windows?.single()?.pkg)
    }

    @Test fun crossBootExpiryUsesWallTimeAtSamplingInsteadOfCurrentUptimeOffset() = runTest {
        val session = SessionPolicy.create(Rule("game", Mode.FREE), 1, ClockSample(1000, 1000, 1), "s")
        var reading = WindowReading(listOf(WindowFact(WindowKind.APP, "game", true, 0, 2)), 10_000, 60_500)
        val sampler = ForegroundSampler(this, StandardTestDispatcher(testScheduler), { reading }, {}, { throw it })
        sampler.refresh(emptyMap()); advanceUntilIdle()
        assertNull(sampler.expiryProof(session, ClockSample(61_000, 10_500, 2)))
        reading = reading.copy(capturedElapsedMs = 10_600, capturedWallMs = 61_100)
        sampler.refresh(emptyMap()); advanceUntilIdle()
        assertNotNull(sampler.expiryProof(session, ClockSample(61_200, 10_700, 2)))
    }

    @Test fun failedQueryDoesNotDisableSubsequentRecovery() = runTest {
        var fail = true
        var errors = 0
        val published = mutableListOf<WindowReading>()
        val sampler = ForegroundSampler(this, StandardTestDispatcher(testScheduler), {
            if (fail) throw IllegalStateException("window query unavailable")
            WindowReading(emptyList(), 1000)
        }, { published.add(it) }, { errors++ })
        sampler.refresh(emptyMap()); advanceUntilIdle()
        fail = false; sampler.refresh(emptyMap()); advanceUntilIdle()
        assertEquals(1, errors)
        assertEquals(1, published.size)
    }

    @Test fun ignoredSystemUiSignalRevokesAppProofBeforeDeadline() = runTest {
        var now = ClockSample(1000, 1000, 1)
        var homes = 0
        val sampler = ForegroundSampler(this, StandardTestDispatcher(testScheduler), {
            WindowReading(listOf(WindowFact(WindowKind.APP, "game", true, 0, 2)), 60_500)
        }, {}, { throw it })
        val host = object : GateHost {
            override fun show(pkg: String, rule: Rule, status: Status) { }
            override fun dismiss() { }
            override fun returnHomeIfForeground(pkg: String): HomeResult {
                val foreground = sampler.recent(now.elapsedMs)?.windows?.singleOrNull()?.pkg
                return if (foreground == pkg) { homes++; HomeResult.EXITED } else HomeResult.UNCONFIRMED
            }
            override fun warn(session: Session) { }
        }
        val rule = Rule("game", Mode.COOLDOWN)
        val session = SessionPolicy.create(rule, 1, now, "s")
        val guard = GateCoordinator("ours", ClockSource { now }, host)
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to session)))
        guard.applicationOpened("game")
        sampler.refresh(emptyMap()); advanceUntilIdle()
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        val router = ApplicationSignalRouter("ours", { now.elapsedMs }, { emptySet() }, { sampler.invalidate() }) { _, _, _ -> fail("SystemUI must not become an application launch") }
        assertFalse(router.windowState("com.android.systemui", "ShadeWindow", 3, 61_000))
        guard.tick()
        assertEquals("old game proof must not return the open system shade home", 0, homes)
    }
}
