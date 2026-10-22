package com.local.focuslock.data
import com.local.focuslock.domain.*
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
class SessionRepository(private val db: FocusDatabase, private val clock: ClockSource, private val ids: IdSource, private val allowed: (String) -> Boolean = { true }) {
    val rules = db.dao().observeRules().map { rows -> rows.map { it.model() } }
    val sessions = db.dao().observeSessions().map { rows -> rows.map { it.model() } }
    private suspend fun mutate(action: suspend () -> MutationResult): MutationResult = try {
        db.withTransaction { action() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { MutationResult.STORAGE_ERROR }
    suspend fun request(packageName: String, minutes: Int): MutationResult = mutate {
        val r = db.dao().rule(packageName)?.model() ?: return@mutate MutationResult.NOT_FOUND
        if (!allowed(packageName) || minutes !in 1..r.maxMinutes) return@mutate MutationResult.INVALID_INPUT
        val now = clock.sample()
        if (SessionPolicy.status(session(packageName), now).phase != Phase.READY) return@mutate MutationResult.ACTIVE_SESSION
        db.dao().putSession(SessionRow.from(SessionPolicy.create(r, minutes, now, ids.next())))
        MutationResult.APPLIED
    }
    suspend fun saveRule(rule: Rule): MutationResult = mutate {
        if (!allowed(rule.packageName) || rule.maxMinutes !in 1..1440 || rule.cooldownMinutes !in 1..1440) return@mutate MutationResult.INVALID_INPUT
        if (status(rule.packageName).phase != Phase.READY) return@mutate MutationResult.ACTIVE_SESSION
        db.dao().putRule(RuleRow.from(rule)); MutationResult.APPLIED
    }
    suspend fun removeRule(packageName: String): MutationResult = mutate {
        if (status(packageName).phase != Phase.READY) return@mutate MutationResult.ACTIVE_SESSION
        db.dao().deleteRule(packageName); db.dao().deleteSession(packageName); MutationResult.APPLIED
    }
    suspend fun status(packageName: String): Status = SessionPolicy.status(session(packageName), clock.sample())
    suspend fun reconcile() { db.withTransaction {
        val now = clock.sample()
        for (row in db.dao().allSessions()) {
            val old = row.model()
            val updated = SessionPolicy.rebase(old, now)
            if (updated != old) db.dao().putSession(SessionRow.from(updated))
        }
    } }
    suspend fun markWarned(packageName: String, sessionId: String): Boolean = db.withTransaction {
        val s = session(packageName) ?: return@withTransaction false
        if (s.id != sessionId || s.warned) return@withTransaction false
        db.dao().putSession(SessionRow.from(s.copy(warned = true))); true
    }
    suspend fun session(packageName: String): Session? = db.dao().session(packageName)?.model()
    suspend fun rule(packageName: String): Rule? = db.dao().rule(packageName)?.model()
    suspend fun allSessions(): List<Session> = db.dao().allSessions().map { it.model() }
}
