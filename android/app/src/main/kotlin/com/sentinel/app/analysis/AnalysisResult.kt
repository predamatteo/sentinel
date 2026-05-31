package com.sentinel.app.analysis

import java.time.Instant

/**
 * Final verdict emitted by [LinkAnalyzer] after consulting all providers.
 *
 * The ordering is significant: when multiple providers disagree the higher
 * severity wins. [Verdict.fromOrdinal] resolves the worst across a list.
 */
enum class Verdict(val severity: Int) {
    SAFE(0),
    SUSPICIOUS(1),
    MALICIOUS(2),

    // A check that could not run or complete (blank API key, timeout,
    // HTTP/network error, internal exception). Negative severity so it can
    // NEVER win a worst() comparison, and it is explicitly filtered out
    // below: a failed check must not escalate a clean URL. Maps to the
    // Dart Verdict.unknown.
    UNAVAILABLE(-1);

    companion object {
        /**
         * Worst (highest-severity) verdict, IGNORING UNAVAILABLE. Only real
         * positive signals (blacklist / Safe Browsing matches) drive the
         * result; if every provider is UNAVAILABLE the result is SAFE
         * (fail-open) and the UI surfaces an informational note instead.
         */
        fun worst(values: List<Verdict>): Verdict =
            values.filter { it != UNAVAILABLE }.maxByOrNull { it.severity } ?: SAFE
    }
}

/**
 * Outcome of a single provider lookup. Providers must always return a value,
 * even on failure: in that case they emit a non-blocking UNAVAILABLE outcome
 * with an explanatory reason, never throw. Only positive evidence yields
 * SUSPICIOUS/MALICIOUS.
 */
data class ProviderOutcome(
    val source: String,
    val verdict: Verdict,
    val reasons: List<String>,
) {
    companion object {
        /** Convenience for the common "check could not run/complete" case. */
        fun unavailable(source: String, reason: String): ProviderOutcome =
            ProviderOutcome(source, Verdict.UNAVAILABLE, listOf(reason))
    }
}

/**
 * Aggregated analysis result returned to the Flutter UI through the
 * AnalysisChannel. Fields are kept primitive-friendly so they can be
 * serialised into a map without custom codecs.
 */
data class AnalysisResult(
    val url: String,
    val verdict: Verdict,
    val reasons: List<String>,
    val sources: List<String>,
    val analyzedAt: Instant,
    // Non-blocking informational notes (e.g. "online check could not
    // complete"). Kept separate from [reasons], which carry only real
    // SUSPICIOUS/MALICIOUS evidence, so the UI can de-escalate.
    val notes: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "url" to url,
        "verdict" to verdict.name,
        "reasons" to reasons,
        "sources" to sources,
        "analyzedAt" to analyzedAt.toEpochMilli(),
        "notes" to notes,
    )
}
