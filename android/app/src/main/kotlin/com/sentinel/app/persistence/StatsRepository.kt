package com.sentinel.app.persistence

import com.sentinel.app.vpn.BlocklistCategory
import com.sentinel.app.vpn.VpnStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Thin facade in front of the Room DAOs used by [VpnStats] and
 * [com.sentinel.app.analysis.AnalysisStats]. The repository owns the
 * date->id encoding (yyyymmdd as a Long), the pruning policy and
 * mapping from in-memory event objects to entities.
 *
 * Instances are obtained from [com.sentinel.app.vpn.StatsDatabaseProvider]
 * and are safe to share across threads (Room handles its own pool).
 */
class StatsRepository internal constructor(
    private val statsDao: StatsDao,
    private val eventsDao: EventsDao,
) {

    /** Returns the snapshot for [day] hydrating in-memory counters. */
    suspend fun todaySnapshot(day: LocalDate): DailyStatsEntity? {
        val id = encodeDate(day)
        return statsDao.findByDate(id)
    }

    /** Inserts an empty row for [day] if missing. Idempotent. */
    suspend fun ensureDailyRow(day: LocalDate) {
        statsDao.insertIfMissing(
            DailyStatsEntity(
                id = encodeDate(day),
                adsBlocked = 0L,
                threatsBlocked = 0L,
                linksChecked = 0L,
                queriesForwarded = 0L,
                errors = 0L,
                lastUpdatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Increment any subset of the daily counters in a single transaction.
     * Pass 0 for fields that should stay untouched.
     */
    suspend fun incrementDaily(
        day: LocalDate,
        ads: Long = 0L,
        threats: Long = 0L,
        links: Long = 0L,
        forwarded: Long = 0L,
        errors: Long = 0L,
    ) {
        statsDao.incrementDailyStats(
            date = encodeDate(day),
            ads = ads,
            threats = threats,
            links = links,
            forwarded = forwarded,
            errors = errors,
            now = System.currentTimeMillis(),
        )
    }

    /** Persist a single block event and prune the table opportunistically. */
    suspend fun recordBlockEvent(event: VpnStats.BlockEvent) {
        eventsDao.insertBlockEvent(
            BlockEventEntity(
                domain = event.domain,
                category = event.category.name,
                reason = event.reason,
                blockedAt = event.timestamp,
            ),
        )
        // Cheap LRU policy: keep at most MAX_BLOCK_EVENTS rows.
        if (eventsDao.countBlockEvents() > MAX_BLOCK_EVENTS) {
            eventsDao.pruneBlockEvents(MAX_BLOCK_EVENTS)
        }
    }

    /** Persist an analysis verdict for the future history view. */
    suspend fun recordAnalysisEvent(
        url: String,
        verdict: String,
        reasons: List<String>,
        sources: List<String>,
        analyzedAt: Long,
    ) {
        eventsDao.insertAnalysisEvent(
            AnalysisEventEntity(
                url = url,
                verdict = verdict,
                reasonsJson = encodeStringList(reasons),
                sourcesJson = encodeStringList(sources),
                analyzedAt = analyzedAt,
            ),
        )
        if (eventsDao.countAnalysisEvents() > MAX_ANALYSIS_EVENTS) {
            eventsDao.pruneAnalysisEvents(MAX_ANALYSIS_EVENTS)
        }
    }

    /** Fetch the [limit] most recent block events, mapped back to BlockEvent. */
    suspend fun recentBlocks(limit: Int): List<VpnStats.BlockEvent> {
        return eventsDao.recentBlockEvents(limit).map { row ->
            row.toBlockEvent()
        }
    }

    /**
     * Reactive variant of [recentBlocks]. Used by the EventChannel to
     * push updates to the dashboard the moment a block is persisted.
     */
    fun observeRecentBlocksFlow(limit: Int): Flow<List<VpnStats.BlockEvent>> {
        return eventsDao.observeRecentBlockEvents(limit).map { rows ->
            rows.map { it.toBlockEvent() }
        }
    }

    /**
     * Observe the daily snapshot for [day]. Emits null until the row
     * exists; consumers should treat null as "all zeros".
     */
    fun observeDailyStats(day: LocalDate): Flow<DailyStatsEntity?> {
        return statsDao.observeByDate(encodeDate(day))
    }

    private fun BlockEventEntity.toBlockEvent(): VpnStats.BlockEvent =
        VpnStats.BlockEvent(
            domain = domain,
            category = runCatching { BlocklistCategory.valueOf(category) }
                .getOrDefault(BlocklistCategory.THREATS),
            reason = reason,
            timestamp = blockedAt,
        )

    companion object {
        private const val MAX_BLOCK_EVENTS = 500
        private const val MAX_ANALYSIS_EVENTS = 500

        /** Encode a LocalDate as yyyymmdd so range queries stay numeric. */
        internal fun encodeDate(day: LocalDate): Long {
            return day.year * 10_000L + day.monthValue * 100L + day.dayOfMonth
        }

        /**
         * Minimal JSON-array encoder. We avoid pulling Moshi/Gson into the
         * VPN process; the rows are read back via [decodeStringList] so a
         * Json parser dependency is not justified.
         */
        internal fun encodeStringList(items: List<String>): String {
            if (items.isEmpty()) return "[]"
            val sb = StringBuilder("[")
            items.forEachIndexed { index, item ->
                if (index > 0) sb.append(',')
                sb.append('"')
                item.forEach { ch ->
                    when (ch) {
                        '\\' -> sb.append("\\\\")
                        '"' -> sb.append("\\\"")
                        '\n' -> sb.append("\\n")
                        '\r' -> sb.append("\\r")
                        '\t' -> sb.append("\\t")
                        else -> sb.append(ch)
                    }
                }
                sb.append('"')
            }
            sb.append(']')
            return sb.toString()
        }

        /** Counterpart to [encodeStringList]. */
        internal fun decodeStringList(json: String): List<String> {
            val trimmed = json.trim()
            if (trimmed.length < 2 || trimmed[0] != '[' || trimmed.last() != ']') {
                return emptyList()
            }
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            if (body.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var i = 0
            while (i < body.length) {
                while (i < body.length && body[i] != '"') i += 1
                if (i >= body.length) break
                i += 1
                val sb = StringBuilder()
                while (i < body.length && body[i] != '"') {
                    if (body[i] == '\\' && i + 1 < body.length) {
                        when (body[i + 1]) {
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(body[i + 1])
                        }
                        i += 2
                    } else {
                        sb.append(body[i])
                        i += 1
                    }
                }
                out.add(sb.toString())
                i += 1 // consume closing quote
            }
            return out
        }
    }
}

/** Factory entry point so VpnStats does not import Room types directly. */
internal fun SentinelDatabase.asStatsRepository(): StatsRepository =
    StatsRepository(statsDao(), eventsDao())
