package com.sentinel.app.analysis

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Coordinates parallel lookups across multiple [UrlProvider]s and produces a
 * consolidated [AnalysisResult]. Each provider has its own hard timeout; if a
 * provider does not respond in time it contributes a SUSPICIOUS outcome
 * carrying the "verifica online non completata" reason so that the user is
 * informed rather than silently approving the link.
 */
class LinkAnalyzer(
    private val providers: List<UrlProvider>,
    private val perProviderTimeoutMs: Long = 3_000L,
) {
    suspend fun analyze(url: String): AnalysisResult = coroutineScope {
        val outcomes = providers.map { provider ->
            async {
                withTimeoutOrNull(perProviderTimeoutMs) {
                    runCatching { provider.check(url) }
                        .getOrElse { error ->
                            ProviderOutcome(
                                source = provider.sourceName,
                                verdict = Verdict.SUSPICIOUS,
                                reasons = listOf(
                                    "Errore interno: ${error.javaClass.simpleName}"
                                ),
                            )
                        }
                } ?: ProviderOutcome(
                    source = provider.sourceName,
                    verdict = Verdict.SUSPICIOUS,
                    reasons = listOf("Verifica online non completata"),
                )
            }
        }.map { it.await() }

        val verdict = Verdict.worst(outcomes.map { it.verdict })
        val reasons = outcomes
            .filter { it.verdict != Verdict.SAFE }
            .flatMap { it.reasons }
            .distinct()
        val sources = outcomes.map { it.source }

        AnalysisResult(
            url = url,
            verdict = verdict,
            reasons = reasons,
            sources = sources,
            analyzedAt = Instant.now(),
        )
    }
}
