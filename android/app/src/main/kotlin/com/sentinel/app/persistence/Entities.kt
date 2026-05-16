package com.sentinel.app.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Aggregated counters for a single calendar day. The primary key is the
 * date encoded as `yyyymmdd` so we can do range queries with a plain
 * integer comparator (no DATE/TIME column parsing).
 *
 * Sprint Quality: this is the canonical persisted truth. In-memory
 * counters in `VpnStats`/`AnalysisStats` are warmed from this row on
 * boot and incremented through-write to keep the two in sync.
 */
@Entity(tableName = "daily_stats")
data class DailyStatsEntity(
    @PrimaryKey val id: Long,
    val adsBlocked: Long,
    val threatsBlocked: Long,
    val linksChecked: Long,
    val queriesForwarded: Long,
    val errors: Long,
    val lastUpdatedAt: Long,
)

/**
 * Append-only log of every block decision. Bounded to ~500 most recent
 * entries by [EventsDao.pruneBlockEvents]. We do not store the upstream
 * matched-domain to keep the row narrow; the user-facing list shows
 * the queried name which is more informative anyway.
 */
@Entity(
    tableName = "block_events",
    indices = [Index("blockedAt"), Index("category")],
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val domain: String,
    val category: String,
    val reason: String,
    val blockedAt: Long,
)

/**
 * Append-only log of every analysis performed by the LinkAnalyzer.
 * Reasons and sources are stored as JSON arrays of strings. The future
 * Sprint 3 history view will paginate this table.
 */
@Entity(
    tableName = "analysis_events",
    indices = [Index("analyzedAt"), Index("verdict")],
)
data class AnalysisEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val verdict: String,
    val reasonsJson: String,
    val sourcesJson: String,
    val analyzedAt: Long,
)
