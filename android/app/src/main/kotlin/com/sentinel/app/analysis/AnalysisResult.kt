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
    MALICIOUS(2);

    companion object {
        fun worst(values: List<Verdict>): Verdict =
            values.maxByOrNull { it.severity } ?: SAFE
    }
}

/**
 * Outcome of a single provider lookup. Providers must always return a value,
 * even on failure: in that case they emit a non-blocking SUSPICIOUS verdict
 * with an explanatory reason, never throw.
 */
data class ProviderOutcome(
    val source: String,
    val verdict: Verdict,
    val reasons: List<String>,
)

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
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "url" to url,
        "verdict" to verdict.name,
        "reasons" to reasons,
        "sources" to sources,
        "analyzedAt" to analyzedAt.toEpochMilli(),
    )
}
