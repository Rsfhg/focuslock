package com.local.focuslock.enforcement

import kotlinx.coroutines.*
import com.local.focuslock.domain.*

data class WindowReading(val windows: List<WindowFact>, val capturedElapsedMs: Long, val capturedWallMs: Long = capturedElapsedMs)

/** Serial recovery probes must never hold up application-event delivery. */
class ForegroundSampler(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val read: (Map<Int, String>) -> WindowReading,
    private val onResult: (WindowReading) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private var latest: WindowReading? = null
    private var generation = 0L
    private var running = false
    fun invalidate() { generation++; latest = null }
    fun recent(nowElapsedMs: Long): WindowReading? = latest?.takeIf { nowElapsedMs - it.capturedElapsedMs in 0..750 }
    fun expiryProof(session: Session, now: ClockSample): WindowReading? = recent(now.elapsedMs)?.takeIf { reading ->
        if (now.bootId >= 0 && now.bootId == session.bootId) reading.capturedElapsedMs >= session.expiresElapsedMs
        else reading.capturedWallMs >= session.expiresWallMs
    }
    fun refresh(identities: Map<Int, String>) {
        if (running) return
        running = true
        val ticket = generation
        val copied = identities.toMap()
        scope.launch {
            try {
                val result = withContext(worker) { read(copied) }
                if (ticket == generation) { latest = result; onResult(result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure(e) }
            finally { running = false }
        }
    }
}
