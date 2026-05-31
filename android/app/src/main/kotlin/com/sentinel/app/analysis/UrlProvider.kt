package com.sentinel.app.analysis

/**
 * Contract implemented by every analysis source. Implementations MUST NOT
 * throw: errors and unavailability are surfaced as a non-blocking
 * [Verdict.UNAVAILABLE] outcome with a textual reason, so one failing
 * provider never escalates a clean URL. Only positive evidence (a local
 * blacklist hit or a Safe Browsing match) yields SUSPICIOUS/MALICIOUS.
 */
interface UrlProvider {
    val sourceName: String
    suspend fun check(url: String): ProviderOutcome
}
