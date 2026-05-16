package com.sentinel.app.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the append-only event logs (block events and analysis events).
 * Both tables are bounded by [pruneBlockEvents]/[pruneAnalysisEvents]
 * which the [StatsRepository] schedules opportunistically on every
 * insert.
 */
@Dao
interface EventsDao {

    // --- block events ---------------------------------------------------

    @Insert
    suspend fun insertBlockEvent(event: BlockEventEntity): Long

    @Query("SELECT * FROM block_events ORDER BY blockedAt DESC LIMIT :limit")
    suspend fun recentBlockEvents(limit: Int): List<BlockEventEntity>

    @Query("SELECT * FROM block_events ORDER BY blockedAt DESC LIMIT :limit")
    fun observeRecentBlockEvents(limit: Int): Flow<List<BlockEventEntity>>

    @Query(
        """
        DELETE FROM block_events WHERE id IN (
            SELECT id FROM block_events ORDER BY blockedAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun pruneBlockEvents(keep: Int): Int

    @Query("DELETE FROM block_events WHERE blockedAt < :olderThan")
    suspend fun deleteBlockEventsOlderThan(olderThan: Long): Int

    @Query("SELECT COUNT(*) FROM block_events")
    suspend fun countBlockEvents(): Int

    // --- analysis events ------------------------------------------------

    @Insert
    suspend fun insertAnalysisEvent(event: AnalysisEventEntity): Long

    @Query("SELECT * FROM analysis_events ORDER BY analyzedAt DESC LIMIT :limit")
    suspend fun recentAnalysisEvents(limit: Int): List<AnalysisEventEntity>

    @Query(
        """
        DELETE FROM analysis_events WHERE id IN (
            SELECT id FROM analysis_events ORDER BY analyzedAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun pruneAnalysisEvents(keep: Int): Int

    @Query("DELETE FROM analysis_events WHERE analyzedAt < :olderThan")
    suspend fun deleteAnalysisEventsOlderThan(olderThan: Long): Int

    @Query("SELECT COUNT(*) FROM analysis_events")
    suspend fun countAnalysisEvents(): Int
}
