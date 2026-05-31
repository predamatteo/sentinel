package com.sentinel.app.analysis

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Coordinates parallel lookups across multiple [UrlProvider]s and produces a
 * consolidated [AnalysisResult]. Each provider has its own hard timeout; if a
 * provider does not respond in time (or fails) it contributes a non-blocking
 * [Verdict.UNAVAILABLE] outcome carrying an informational note, which is
 * EXCLUDED from the worst-severity aggregation — a failed check never
 * escalates a clean URL. Only real positive evidence yields SUSPICIOUS or
 * MALICIOUS.
 *
 * Invariant: [perProviderTimeoutMs] MUST exceed the slowest provider's own
 * connect+read budget (Safe Browsing: 2500+2500 = 5000ms); otherwise the
 * analyzer would cut an in-flight provider off and manufacture an
 * UNAVAILABLE on merely-slow networks. 6000ms leaves ~1s of headroom.
 */
class LinkAnalyzer(
    private val providers: List<UrlProvider>,
    private val perProviderTimeoutMs: Long = 6_000L,
) {
    suspend fun analyze(url: String): AnalysisResult = coroutineScope {
        val outcomes = providers.map { provider ->
            async {
                withTimeoutOrNull(perProviderTimeoutMs) {
                    runCatching { provider.check(url) }
                        .getOrElse { error ->
                            ProviderOutcome.unavailable(
                                source = provider.sourceName,
                                reason = "Errore interno: ${error.javaClass.simpleName}",
                            )
                        }
                } ?: ProviderOutcome.unavailable(
                    source = provider.sourceName,
                    reason = "Verifica online non completata",
                )
            }
        }.map { it.await() }

        val verdict = Verdict.worst(outcomes.map { it.verdict })
        // Blocking reasons carry ONLY real positive evidence.
        val reasons = outcomes
            .filter { it.verdict == Verdict.SUSPICIOUS || it.verdict == Verdict.MALICIOUS }
            .flatMap { it.reasons }
            .distinct()
        // Unavailable/failed checks become non-blocking informational notes,
        // never a verdict driver.
        val notes = outcomes
            .filter { it.verdict == Verdict.UNAVAILABLE }
            .flatMap { it.reasons }
            .distinct()
        val sources = outcomes.map { it.source }

        AnalysisResult(
            url = url,
            verdict = verdict,
            reasons = reasons,
            sources = sources,
            analyzedAt = Instant.now(),
            notes = notes,
        )
    }
}
