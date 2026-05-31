package com.sentinel.app.analysis

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the fail-open aggregation: a provider that fails/times out
 * contributes a non-blocking UNAVAILABLE (surfaced as a note), never an
 * escalation — while a genuine MALICIOUS signal still drives the verdict.
 */
class LinkAnalyzerTest {

    private class FakeProvider(
        override val sourceName: String,
        private val outcome: ProviderOutcome,
        private val delayMs: Long = 0,
    ) : UrlProvider {
        override suspend fun check(url: String): ProviderOutcome {
            if (delayMs > 0) delay(delayMs)
            return outcome
        }
    }

    private class ThrowingProvider(override val sourceName: String) : UrlProvider {
        override suspend fun check(url: String): ProviderOutcome = throw RuntimeException("boom")
    }

    private fun outcome(source: String, verdict: Verdict, vararg reasons: String) =
        ProviderOutcome(source, verdict, reasons.toList())

    @Test
    fun unavailableDoesNotEscalateCleanUrl() = runBlocking {
        val analyzer = LinkAnalyzer(
            listOf(
                FakeProvider("local", outcome("local", Verdict.SAFE)),
                FakeProvider("sb", ProviderOutcome.unavailable("sb", "Verifica online non completata")),
            ),
        )
        val result = analyzer.analyze("http://example.com")
        assertEquals(Verdict.SAFE, result.verdict)
        assertTrue("reasons must stay empty", result.reasons.isEmpty())
        assertTrue("note is surfaced", result.notes.contains("Verifica online non completata"))
    }

    @Test
    fun maliciousSignalStillWins() = runBlocking {
        val analyzer = LinkAnalyzer(
            listOf(
                FakeProvider("local", outcome("local", Verdict.MALICIOUS, "Dominio in blacklist")),
                FakeProvider("sb", ProviderOutcome.unavailable("sb", "non disponibile")),
            ),
        )
        val result = analyzer.analyze("http://evil.test")
        assertEquals(Verdict.MALICIOUS, result.verdict)
        assertTrue(result.reasons.contains("Dominio in blacklist"))
    }

    @Test
    fun throwingProviderBecomesUnavailableNotSuspicious() = runBlocking {
        val analyzer = LinkAnalyzer(
            listOf(
                FakeProvider("local", outcome("local", Verdict.SAFE)),
                ThrowingProvider("sb"),
            ),
        )
        val result = analyzer.analyze("http://example.com")
        assertEquals(Verdict.SAFE, result.verdict)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun timedOutProviderDoesNotEscalate() = runBlocking {
        val analyzer = LinkAnalyzer(
            providers = listOf(
                FakeProvider("local", outcome("local", Verdict.SAFE)),
                FakeProvider("slow", outcome("slow", Verdict.MALICIOUS, "would-be-bad"), delayMs = 300),
            ),
            perProviderTimeoutMs = 50,
        )
        val result = analyzer.analyze("http://example.com")
        // The slow provider is cut off -> UNAVAILABLE, excluded from worst().
        assertEquals(Verdict.SAFE, result.verdict)
        assertTrue(result.notes.contains("Verifica online non completata"))
    }
}
