package com.sentinel.app.analysis.providers

import android.content.Context
import com.sentinel.app.analysis.ProviderOutcome
import com.sentinel.app.analysis.UrlProvider
import com.sentinel.app.analysis.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Provider that checks the URL host (and parent domains) against a static
 * blacklist bundled in the APK under `assets/blacklist/`. The blacklist is
 * loaded lazily and cached in memory for the lifetime of the process.
 *
 * Matching is conservative: we match by exact host or by suffix on a
 * label-boundary, so `paypa1-secure.com` does NOT match `safepaypa1.com`.
 */
class LocalBlacklistProvider(
    private val context: Context,
    private val assetPath: String = "blacklist/sample.txt",
) : UrlProvider {

    override val sourceName: String = "Blacklist locale"

    private val mutex = Mutex()
    private var cached: Set<String>? = null

    override suspend fun check(url: String): ProviderOutcome = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host.isNullOrBlank()) {
            return@withContext ProviderOutcome(
                source = sourceName,
                verdict = Verdict.SUSPICIOUS,
                reasons = listOf("URL non valido"),
            )
        }
        val list = loadBlacklist()
        val match = list.firstOrNull { entry ->
            host == entry || host.endsWith(".$entry")
        }
        if (match != null) {
            ProviderOutcome(
                source = sourceName,
                verdict = Verdict.MALICIOUS,
                reasons = listOf("Dominio presente in blacklist locale: $match"),
            )
        } else {
            ProviderOutcome(
                source = sourceName,
                verdict = Verdict.SAFE,
                reasons = emptyList(),
            )
        }
    }

    private suspend fun loadBlacklist(): Set<String> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: run {
                val parsed = context.assets.open(assetPath).bufferedReader().useLines { lines ->
                    lines
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .map { it.lowercase() }
                        .toHashSet()
                }
                cached = parsed
                parsed
            }
        }
    }
}
