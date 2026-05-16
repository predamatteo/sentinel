package com.sentinel.app.analysis

import com.sentinel.app.persistence.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide counter for link analyses performed by [LinkAnalyzer].
 *
 * Lives next to [com.sentinel.app.vpn.VpnStats] but is a separate object
 * because it is incremented from a different surface (the analysis
 * MethodChannel) and needs no event-deque overhead.
 *
 * Sprint Quality contract:
 *  - `recordLinkChecked()` increments only when the analyzer returned a
 *    non-error result (the channel is responsible for that distinction);
 *  - Counters roll over at local midnight, same convention as VpnStats;
 *  - `warmFromDb()` rehydrates the today value on boot so the dashboard
 *    shows accurate numbers immediately after activity start.
 */
object AnalysisStats {

    private val linksChecked = AtomicLong(0L)
    private val currentDay: AtomicReference<LocalDate> =
        AtomicReference(LocalDate.now(ZoneId.systemDefault()))

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var repository: StatsRepository? = null

    fun bindRepository(repo: StatsRepository) {
        repository = repo
    }

    suspend fun warmFromDb() {
        val repo = repository ?: return
        val today = currentDay.get()
        val row = repo.todaySnapshot(today)
        if (row != null) {
            linksChecked.set(row.linksChecked)
        }
    }

    /** Increment the today counter and write-through to Room. */
    fun recordLinkChecked() {
        rolloverIfDayChanged()
        linksChecked.incrementAndGet()
        val day = currentDay.get()
        repository?.let { repo ->
            ioScope.launch { repo.incrementDaily(day, links = 1L) }
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "linksChecked" to linksChecked.get(),
    )

    /** Test/diagnostic helper. */
    fun linksCheckedToday(): Long = linksChecked.get()

    @Synchronized
    fun reset() {
        linksChecked.set(0L)
        currentDay.set(LocalDate.now(ZoneId.systemDefault()))
    }

    private fun rolloverIfDayChanged() {
        val today = LocalDate.now(ZoneId.systemDefault())
        val prev = currentDay.get()
        if (today != prev && currentDay.compareAndSet(prev, today)) {
            linksChecked.set(0L)
            repository?.let { repo ->
                ioScope.launch { repo.ensureDailyRow(today) }
            }
        }
    }

    internal fun overrideCurrentDayForTest(day: LocalDate) {
        currentDay.set(day)
    }
}
