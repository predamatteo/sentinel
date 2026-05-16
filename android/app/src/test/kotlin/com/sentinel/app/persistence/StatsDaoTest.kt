package com.sentinel.app.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sentinel.app.vpn.BlocklistCategory
import com.sentinel.app.vpn.VpnStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Exercises [StatsDao] and [EventsDao] against an in-memory Room
 * database. Run under Robolectric because Room needs a Context even
 * for the in-memory builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StatsDaoTest {

    private lateinit var db: SentinelDatabase
    private lateinit var repo: StatsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, SentinelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = db.asStatsRepository()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun incrementDailyStatsUpsertsAndAccumulates() = runTest {
        val day = LocalDate.of(2026, 5, 15)
        repo.incrementDaily(day, ads = 3L)
        repo.incrementDaily(day, threats = 2L)
        repo.incrementDaily(day, links = 5L, forwarded = 7L)

        val row = repo.todaySnapshot(day)
        assertNotNull(row)
        assertEquals(3L, row!!.adsBlocked)
        assertEquals(2L, row.threatsBlocked)
        assertEquals(5L, row.linksChecked)
        assertEquals(7L, row.queriesForwarded)
        assertEquals(0L, row.errors)
        assertEquals(StatsRepository.encodeDate(day), row.id)
    }

    @Test
    fun ensureDailyRowIsIdempotent() = runTest {
        val day = LocalDate.of(2026, 5, 15)
        repo.ensureDailyRow(day)
        repo.incrementDaily(day, ads = 1L)
        repo.ensureDailyRow(day) // must not wipe the previous values
        val row = repo.todaySnapshot(day)
        assertNotNull(row)
        assertEquals(1L, row!!.adsBlocked)
    }

    @Test
    fun dayRolloverKeepsPreviousRowIntact() = runTest {
        val yesterday = LocalDate.of(2026, 5, 14)
        val today = LocalDate.of(2026, 5, 15)
        repo.incrementDaily(yesterday, ads = 10L, threats = 4L)
        repo.incrementDaily(today, ads = 1L)

        val pastRow = repo.todaySnapshot(yesterday)
        val newRow = repo.todaySnapshot(today)

        assertEquals(10L, pastRow?.adsBlocked)
        assertEquals(4L, pastRow?.threatsBlocked)
        assertEquals(1L, newRow?.adsBlocked)
        assertEquals(0L, newRow?.threatsBlocked)
        // Two distinct rows must coexist after the rollover.
        assertFalse(pastRow!!.id == newRow!!.id)
    }

    @Test
    fun recordBlockEventStoresAndCategorises() = runTest {
        repo.recordBlockEvent(
            VpnStats.BlockEvent(
                domain = "ads.example.com",
                category = BlocklistCategory.ADS,
                reason = "ads",
                timestamp = 1_700_000_000_000L,
            ),
        )
        repo.recordBlockEvent(
            VpnStats.BlockEvent(
                domain = "phish.example",
                category = BlocklistCategory.THREATS,
                reason = "threats",
                timestamp = 1_700_000_000_500L,
            ),
        )
        val events = repo.recentBlocks(limit = 10)
        assertEquals(2, events.size)
        assertEquals("phish.example", events[0].domain)
        assertEquals(BlocklistCategory.THREATS, events[0].category)
        assertEquals("ads.example.com", events[1].domain)
        assertEquals(BlocklistCategory.ADS, events[1].category)
    }

    @Test
    fun pruneBlockEventsKeepsTheMostRecentOnes() = runTest {
        // Use a small keep window for the test; the production cap is 500
        // but the prune query is parameterised, so we can drive it via
        // the DAO directly.
        repeat(7) { i ->
            repo.recordBlockEvent(
                VpnStats.BlockEvent(
                    domain = "d$i.example",
                    category = BlocklistCategory.ADS,
                    reason = "ads",
                    timestamp = 1_000L + i,
                ),
            )
        }
        db.eventsDao().pruneBlockEvents(keep = 3)
        val remaining = repo.recentBlocks(limit = 100)
        assertEquals(3, remaining.size)
        // Most recent must survive.
        assertEquals("d6.example", remaining[0].domain)
    }

    @Test
    fun analysisEventRoundTripsJsonLists() = runTest {
        val reasons = listOf("Reason \"A\"", "Reason\nB", "Reason\\C")
        val sources = listOf("Source X", "Source Y")
        repo.recordAnalysisEvent(
            url = "https://example.com",
            verdict = "SUSPICIOUS",
            reasons = reasons,
            sources = sources,
            analyzedAt = 1_700_000_000_000L,
        )
        val events = db.eventsDao().recentAnalysisEvents(10)
        assertEquals(1, events.size)
        val decodedReasons = StatsRepository.decodeStringList(events[0].reasonsJson)
        val decodedSources = StatsRepository.decodeStringList(events[0].sourcesJson)
        assertEquals(reasons, decodedReasons)
        assertEquals(sources, decodedSources)
    }

    @Test
    fun observeRecentBlocksFlowEmitsCurrentState() = runTest {
        val flow = repo.observeRecentBlocksFlow(10)
        // Pre-seed two events before collecting so the first emission is
        // deterministic. We sample the flow exactly once.
        repo.recordBlockEvent(
            VpnStats.BlockEvent(
                domain = "first.example",
                category = BlocklistCategory.ADS,
                reason = "ads",
                timestamp = 2_000L,
            ),
        )
        val firstEmission = flow.first()
        assertEquals(1, firstEmission.size)
        assertEquals("first.example", firstEmission[0].domain)
    }

    @Test
    fun encodeDateIsOrderable() {
        val a = StatsRepository.encodeDate(LocalDate.of(2026, 1, 1))
        val b = StatsRepository.encodeDate(LocalDate.of(2026, 5, 15))
        val c = StatsRepository.encodeDate(LocalDate.of(2026, 12, 31))
        assertTrue(a < b)
        assertTrue(b < c)
    }

    @Test
    fun missingRowReturnsNull() = runTest {
        val day = LocalDate.of(2099, 1, 1)
        assertNull(repo.todaySnapshot(day))
    }
}
