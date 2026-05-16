package com.sentinel.app.analysis

/**
 * Contract implemented by every analysis source. Implementations MUST NOT
 * throw: errors are surfaced as a SUSPICIOUS verdict with a textual reason,
 * so that one failing provider never blocks the overall pipeline.
 */
interface UrlProvider {
    val sourceName: String
    suspend fun check(url: String): ProviderOutcome
}
