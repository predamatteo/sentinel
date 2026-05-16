package com.sentinel.app.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for aggregated daily counters. The increment* methods are a
 * single-statement UPSERT (insert-with-replace + UPDATE) so the call
 * site does not have to read-modify-write itself.
 */
@Dao
interface StatsDao {

    @Query("SELECT * FROM daily_stats WHERE id = :date LIMIT 1")
    suspend fun findByDate(date: Long): DailyStatsEntity?

    @Query("SELECT * FROM daily_stats WHERE id = :date LIMIT 1")
    fun observeByDate(date: Long): Flow<DailyStatsEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: DailyStatsEntity): Long

    @Query(
        """
        UPDATE daily_stats SET
            adsBlocked = adsBlocked + :ads,
            threatsBlocked = threatsBlocked + :threats,
            linksChecked = linksChecked + :links,
            queriesForwarded = queriesForwarded + :forwarded,
            errors = errors + :errors,
            lastUpdatedAt = :now
        WHERE id = :date
        """,
    )
    suspend fun incrementInPlace(
        date: Long,
        ads: Long,
        threats: Long,
        links: Long,
        forwarded: Long,
        errors: Long,
        now: Long,
    ): Int

    /**
     * Atomic upsert: ensures a row exists for [date], then applies the
     * deltas. Returns the resulting row.
     */
    @Transaction
    suspend fun incrementDailyStats(
        date: Long,
        ads: Long,
        threats: Long,
        links: Long,
        forwarded: Long,
        errors: Long,
        now: Long,
    ): DailyStatsEntity? {
        insertIfMissing(
            DailyStatsEntity(
                id = date,
                adsBlocked = 0L,
                threatsBlocked = 0L,
                linksChecked = 0L,
                queriesForwarded = 0L,
                errors = 0L,
                lastUpdatedAt = now,
            ),
        )
        incrementInPlace(date, ads, threats, links, forwarded, errors, now)
        return findByDate(date)
    }

    @Query("SELECT * FROM daily_stats ORDER BY id DESC LIMIT :limit")
    suspend fun lastDays(limit: Int): List<DailyStatsEntity>
}
