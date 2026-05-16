package com.sentinel.app.vpn

import com.sentinel.app.persistence.SentinelDatabase
import com.sentinel.app.persistence.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide in-memory statistics for the Sentinel VPN tunnel.
 *
 * Sprint Quality:
 *  - separates `adsBlocked` from `threatsBlocked` so the dashboard can
 *    render the two categories independently. `totalBlocks` is exposed
 *    as the sum for callers that need a single number;
 *  - persists every counter mutation to [StatsRepository], so values
 *    survive process death and phone reboots;
 *  - rolls over at midnight (device local timezone): when a record* call
 *    detects the local date has advanced, the in-memory counters are
 *    reset and a new [DailyStatsEntity] row is created in Room. The
 *    previous day's row stays in DB for future history views.
 *
 * All counters are thread-safe. The singleton is process-wide because the
 * foreground service and the platform channel both need to write/read
 * without dragging dependencies around.
 */
object VpnStats {

    private val adsBlocked = AtomicLong(0L)
    private val threatsBlocked = AtomicLong(0L)
    private val totalQueries = AtomicLong(0L)
    private val totalForwards = AtomicLong(0L)
    private val totalErrors = AtomicLong(0L)
    private val recentBlocks = ArrayDeque<BlockEvent>()
    private val maxRecent = 100
    private val perDomain = ConcurrentHashMap<String, Long>()

    // Display dedup window for recentBlocks: a second block for the
    // same domain within this window replaces the previous entry
    // instead of being prepended. See recordBlock().
    private val RECENT_DEDUP_WINDOW_MS = 60_000L

    private val currentDay: AtomicReference<LocalDate> =
        AtomicReference(LocalDate.now(ZoneId.systemDefault()))

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var repository: StatsRepository? = null

    /** Wire the persistence layer. Called once from VpnController on boot. */
    fun bindRepository(repo: StatsRepository) {
        repository = repo
    }

    /**
     * Hydrate the in-memory counters from the persisted daily row for the
     * current local date. Safe to call multiple times.
     */
    suspend fun warmFromDb() {
        val repo = repository ?: return
        val today = currentDay.get()
        val row = repo.todaySnapshot(today)
        if (row != null) {
            adsBlocked.set(row.adsBlocked)
            threatsBlocked.set(row.threatsBlocked)
            totalQueries.set(row.queriesForwarded + row.adsBlocked + row.threatsBlocked)
            totalForwards.set(row.queriesForwarded)
            totalErrors.set(row.errors)
        }
        val events = repo.recentBlocks(maxRecent)
        synchronized(recentBlocks) {
            recentBlocks.clear()
            events.forEach { recentBlocks.addLast(it) }
        }
    }

    @Synchronized
    fun reset() {
        adsBlocked.set(0L)
        threatsBlocked.set(0L)
        totalQueries.set(0L)
        totalForwards.set(0L)
        totalErrors.set(0L)
        recentBlocks.clear()
        perDomain.clear()
        currentDay.set(LocalDate.now(ZoneId.systemDefault()))
    }

    fun recordQuery() {
        rolloverIfDayChanged()
        totalQueries.incrementAndGet()
    }

    fun recordForwarded() {
        rolloverIfDayChanged()
        totalForwards.incrementAndGet()
        val day = currentDay.get()
        repository?.let { repo ->
            ioScope.launch { repo.incrementDaily(day, forwarded = 1L) }
        }
    }

    fun recordError() {
        rolloverIfDayChanged()
        totalErrors.incrementAndGet()
        val day = currentDay.get()
        repository?.let { repo ->
            ioScope.launch { repo.incrementDaily(day, errors = 1L) }
        }
    }

    @Synchronized
    fun recordBlock(domain: String, category: BlocklistCategory) {
        rolloverIfDayChanged()
        val reason: String = when (category) {
            BlocklistCategory.ADS -> {
                adsBlocked.incrementAndGet()
                "ads"
            }
            BlocklistCategory.THREATS -> {
                threatsBlocked.incrementAndGet()
                "threats"
            }
        }
        perDomain.merge(domain, 1L) { a, b -> a + b }
        val event = BlockEvent(
            domain = domain,
            category = category,
            reason = reason,
            timestamp = System.currentTimeMillis(),
        )
        // Display dedup: Chrome (and other browsers) trigger many DNS
        // queries for the same host in quick succession (prefetch,
        // omnibox suggestions, retries). Without this the dashboard
        // would show dozens of identical rows for a single human
        // visit. Counters (adsBlocked / threatsBlocked) still
        // accumulate every block; only the visible recent list is
        // collapsed.
        val first = recentBlocks.firstOrNull()
        if (first != null &&
            first.domain == domain &&
            event.timestamp - first.timestamp < RECENT_DEDUP_WINDOW_MS
        ) {
            recentBlocks.removeFirst()
        }
        recentBlocks.addFirst(event)
        while (recentBlocks.size > maxRecent) {
            recentBlocks.removeLast()
        }
        val day = currentDay.get()
        repository?.let { repo ->
            ioScope.launch {
                if (category == BlocklistCategory.ADS) {
                    repo.incrementDaily(day, ads = 1L)
                } else {
                    repo.incrementDaily(day, threats = 1L)
                }
                repo.recordBlockEvent(event)
            }
        }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val ads = adsBlocked.get()
        val threats = threatsBlocked.get()
        return mapOf(
            "adsBlocked" to ads,
            "threatsBlocked" to threats,
            "totalBlocks" to (ads + threats),
            "totalQueries" to totalQueries.get(),
            "totalForwards" to totalForwards.get(),
            "totalErrors" to totalErrors.get(),
            "recentBlocks" to recentBlocks.map {
                mapOf(
                    "domain" to it.domain,
                    "category" to it.category.name,
                    "reason" to it.reason,
                    "timestamp" to it.timestamp,
                )
            },
        )
    }

    /** Returns the today's total blocks count in O(1) for the notification. */
    fun totalBlocksFast(): Long = adsBlocked.get() + threatsBlocked.get()

    /**
     * Reset in-memory counters when local midnight has passed. The previous
     * day's row remains in Room untouched; the new day starts at zero.
     * This is called from every record* entry point so it cannot drift.
     */
    private fun rolloverIfDayChanged() {
        val today = LocalDate.now(ZoneId.systemDefault())
        val prev = currentDay.get()
        if (today != prev && currentDay.compareAndSet(prev, today)) {
            synchronized(this) {
                adsBlocked.set(0L)
                threatsBlocked.set(0L)
                totalQueries.set(0L)
                totalForwards.set(0L)
                totalErrors.set(0L)
                recentBlocks.clear()
                perDomain.clear()
            }
            repository?.let { repo ->
                ioScope.launch { repo.ensureDailyRow(today) }
            }
        }
    }

    /** Test hook: force-overrides the rolling date. */
    internal fun overrideCurrentDayForTest(day: LocalDate) {
        currentDay.set(day)
    }

    data class BlockEvent(
        val domain: String,
        val category: BlocklistCategory,
        val reason: String,
        val timestamp: Long,
    )
}

/**
 * Convenience holder for SentinelDatabase access used by [VpnController].
 * Kept here so [VpnStats] does not need direct knowledge of the DB class
 * - it only sees [StatsRepository] which can be faked in tests.
 */
internal object StatsDatabaseProvider {
    @Volatile
    private var db: SentinelDatabase? = null

    fun get(context: android.content.Context): SentinelDatabase {
        val cached = db
        if (cached != null) return cached
        synchronized(this) {
            val again = db
            if (again != null) return again
            val built = SentinelDatabase.create(context.applicationContext)
            db = built
            return built
        }
    }
}
