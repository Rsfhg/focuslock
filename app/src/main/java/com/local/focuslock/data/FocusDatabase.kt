package com.local.focuslock.data

import androidx.room.*
import com.local.focuslock.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleRow(@PrimaryKey val packageName: String, val mode: String, val maxMinutes: Int, val cooldownMinutes: Int) {
    fun model() = Rule(packageName, Mode.valueOf(mode), maxMinutes, cooldownMinutes)
    companion object { fun from(r: Rule) = RuleRow(r.packageName, r.mode.name, r.maxMinutes, r.cooldownMinutes) }
}
@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val packageName: String, val id: String, val mode: String, val maxMinutes: Int, val cooldownMinutes: Int,
    val startedWallMs: Long, val expiresWallMs: Long, val cooldownWallMs: Long,
    val expiresElapsedMs: Long, val cooldownElapsedMs: Long, val bootId: Long, val warned: Boolean
) {
    fun model() = Session(packageName, id, Rule(packageName, Mode.valueOf(mode), maxMinutes, cooldownMinutes), startedWallMs, expiresWallMs, cooldownWallMs, expiresElapsedMs, cooldownElapsedMs, bootId, warned)
    companion object {
        fun from(s: Session) = SessionRow(s.packageName, s.id, s.rule.mode.name, s.rule.maxMinutes, s.rule.cooldownMinutes, s.startedWallMs, s.expiresWallMs, s.cooldownWallMs, s.expiresElapsedMs, s.cooldownElapsedMs, s.bootId, s.warned)
    }
}
@Dao interface FocusDao {
    @Query("SELECT * FROM rules ORDER BY packageName") fun observeRules(): Flow<List<RuleRow>>
    @Query("SELECT * FROM sessions") fun observeSessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM rules WHERE packageName = :pkg") suspend fun rule(pkg: String): RuleRow?
    @Query("SELECT * FROM sessions WHERE packageName = :pkg") suspend fun session(pkg: String): SessionRow?
    @Query("SELECT * FROM sessions") suspend fun allSessions(): List<SessionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(row: RuleRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(row: SessionRow)
    @Query("DELETE FROM rules WHERE packageName = :pkg") suspend fun deleteRule(pkg: String)
    @Query("DELETE FROM sessions WHERE packageName = :pkg") suspend fun deleteSession(pkg: String)
}
@Database(entities = [RuleRow::class, SessionRow::class], version = 1, exportSchema = false)
abstract class FocusDatabase : RoomDatabase() { abstract fun dao(): FocusDao }
