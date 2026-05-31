package com.sentinel.app.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Domain blocklist repository for the Sentinel VPN layer.
 *
 * Sources (priority order, low to high):
 *  1. Bundled assets shipped in the APK (always available, offline).
 *  2. Remote lists downloaded periodically from URLs configured via
 *     Firebase Remote Config and cached on internal storage.
 *
 * Sprint Quality: blocklists are categorised (ads vs threats) so the
 * dashboard can render dedicated counters. Each category lives in its
 * own [AtomicReference]<Set<String>> for lock-free hot-path reads;
 * [lookup] walks the parent labels to support subdomain matching.
 *
 * Cache file naming convention: `cached_<category>.txt` (e.g.
 * `cached_ads.txt`, `cached_threats.txt`) so categorisation survives
 * remote fetches and disk reloads.
 *
 * Whitelist precedence: a domain present in [setUserWhitelist] OR in
 * [defaultWhitelist] is NEVER reported as blocked, regardless of any
 * blocklist content.
 */
class BlocklistRepository(
    private val context: Context,
    private val bundledAds: List<String> = listOf("blocklist/ads.txt"),
    private val bundledThreats: List<String> = listOf("blocklist/malware.txt"),
    private val whitelistController: WhitelistController =
        WhitelistController(SharedPrefsUserWhitelistStore(context)),
) {
    private val mutex = Mutex()
    private val adsSet: AtomicReference<Set<String>> = AtomicReference(emptySet())
    private val threatsSet: AtomicReference<Set<String>> = AtomicReference(emptySet())

    // Default whitelist: critical domains for authentication and core OS
    // functionality that must never be blocked, even if a public list
    // (StevenBlack, AdGuard, ...) happens to include them. Bypasses the
    // user-configurable whitelist so the user cannot accidentally remove
    // these by clearing their personal list.
    private val defaultWhitelist: Set<String> = setOf(
        "graph.facebook.com",        // Facebook Login API (used by thousands of apps)
        "connect.facebook.net",      // Facebook JS SDK — web "Login with Facebook"
        "api.whatsapp.com",
        "web.whatsapp.com",
        "accounts.google.com",       // Google Sign-In
        "oauth2.googleapis.com",
        "login.microsoftonline.com", // Microsoft / Office 365 sign-in
        "appleid.apple.com",         // Sign in with Apple
        "id.atlassian.com",
        "auth.docker.io",
        // SERP click redirectors: blocking these makes Chrome show
        // "Sito non raggiungibile" when the user clicks on a sponsored
        // result, even though the destination is legitimate. Ad-delivery
        // subdomains (partner.googleadservices.com, pagead2.googlesyndication.com,
        // ad.doubleclick.net, ...) remain blocked via ads.txt.
        "googleadservices.com",
        "www.googleadservices.com",
        // Adjust click/deep-link redirector: whitelisting the redirect
        // subdomain lets the parent-label climb allow it while the
        // adjust.com apex (pure measurement) stays blocked in ads.txt.
        "app.adjust.com",
    )

    // Precomputed union of defaultWhitelist + the current user whitelist,
    // recomputed only when the user list changes (constructor +
    // setUserWhitelist). The DNS hot path reads this lock-free instead of
    // allocating a fresh merged set on every single lookup.
    //
    // Trade-off: setUserWhitelist does two separate writes (replace then
    // recompute), so there is a sub-microsecond window where lookup() may
    // still see the previous merged set. This is benign — setUserWhitelist
    // is a rare, manual user action and the hot-path savings (no per-query
    // set allocation, called millions of times) far outweigh a transient
    // window on a deliberate config change.
    private val mergedWhitelistRef: AtomicReference<Set<String>> =
        AtomicReference(defaultWhitelist)

    init {
        recomputeMergedWhitelist()
    }

    private fun recomputeMergedWhitelist() {
        val user = whitelistController.current()
        mergedWhitelistRef.set(
            if (user.isEmpty()) defaultWhitelist else defaultWhitelist + user,
        )
    }

    /**
     * Load the bundled lists and any cached remote lists. Idempotent: a
     * second call performs no I/O if the in-memory state is already
     * populated.
     */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (adsSet.get().isNotEmpty() || threatsSet.get().isNotEmpty()) {
            return@withContext
        }
        mutex.withLock {
            if (adsSet.get().isNotEmpty() || threatsSet.get().isNotEmpty()) {
                return@withLock
            }
            reloadInternal()
        }
    }

    /** Force-reload bundled + cached remote lists from disk. */
    suspend fun reloadFromDisk() = withContext(Dispatchers.IO) {
        mutex.withLock { reloadInternal() }
    }

    private fun reloadInternal() {
        val ads = HashSet<String>(8_192)
        val threats = HashSet<String>(8_192)
        bundledAds.forEach { path -> readAssetInto(path, ads) }
        bundledThreats.forEach { path -> readAssetInto(path, threats) }
        readCachedRemoteInto(ads, threats)
        adsSet.set(ads)
        threatsSet.set(threats)
        Log.i(TAG, "Blocklist loaded: ads=${ads.size} threats=${threats.size}")
    }

    /**
     * Download the remote list at [url] into the local cache, classified
     * under [category]. Atomically replaces the corresponding cache file
     * on success; on failure the previous cache is preserved.
     *
     * [tag] is a short identifier used as part of the cache file name
     * (e.g. "stevenblack"). Returns the number of valid entries written.
     */
    suspend fun fetchAndCacheRemote(
        tag: String,
        url: String,
        category: BlocklistCategory,
    ): Int = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext 0
        val safeTag = tag.replace(Regex("[^a-z0-9_-]"), "_").take(32)
        val fileName = "cached_${category.fileTag}_$safeTag.txt"
        val target = File(cacheDir(), fileName)
        val tmp = File(cacheDir(), "$fileName.tmp")
        var count = 0
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Remote blocklist fetch failed for $tag: HTTP $code")
                return@withContext 0
            }
            tmp.outputStream().buffered().use { sink ->
                connection.inputStream.buffered().use { source ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
            BufferedReader(InputStreamReader(tmp.inputStream(), Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (parseLine(line) != null) count += 1
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            Log.i(TAG, "Cached remote blocklist '$tag' (${category.name}) with $count entries")
        } catch (error: Exception) {
            Log.w(TAG, "Remote blocklist fetch failed for $tag: ${error.message}")
            tmp.delete()
        } finally {
            connection.disconnect()
        }
        count
    }

    /**
     * Backwards-compatible wrapper retained for callers that only care
     * about a boolean answer. New code paths should call [lookup].
     */
    fun isBlocked(domain: String): Boolean = lookup(domain) !is MatchResult.Allowed

    /**
     * Hot-path lookup returning either [MatchResult.Allowed] (whitelisted
     * or unknown), [MatchResult.BlockedByAds] or [MatchResult.BlockedByThreats].
     *
     * Threats win over ads when a domain is present in both lists; the
     * security signal is the more conservative one.
     */
    fun lookup(domain: String): MatchResult {
        return classify(domain, threatsSet.get(), adsSet.get(), mergedWhitelistRef.get())
    }

    /** Total entries across both categories (deduped). */
    fun size(): Int = adsSet.get().size + threatsSet.get().size

    /**
     * Replace the user whitelist and persist it to disk. Subsequent
     * [lookup] calls honor the new set, and the whitelist survives
     * process death, VPN service restarts, and device reboot.
     */
    fun setUserWhitelist(domains: Collection<String>) {
        whitelistController.replace(domains)
        recomputeMergedWhitelist()
    }

    /** Current user whitelist snapshot (excluding [defaultWhitelist]). */
    fun userWhitelist(): Set<String> = whitelistController.current()

    private fun cacheDir(): File {
        val dir = File(context.filesDir, "blocklists")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readAssetInto(path: String, acc: HashSet<String>) {
        try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val parsed = parseLine(line) ?: return@forEach
                    acc.add(parsed)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read bundled blocklist $path: ${error.message}")
        }
    }

    private fun readCachedRemoteInto(ads: HashSet<String>, threats: HashSet<String>) {
        val dir = cacheDir()
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.name.endsWith(".txt") }?.forEach { file ->
            val target: HashSet<String>? = when {
                file.name.startsWith("cached_${BlocklistCategory.ADS.fileTag}") -> ads
                file.name.startsWith("cached_${BlocklistCategory.THREATS.fileTag}") -> threats
                // Legacy caches (pre-Sprint Quality) without category prefix
                // are conservatively classified as threats so users do not
                // lose protection across an upgrade.
                else -> threats
            }
            try {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val parsed = parseLine(line) ?: return@forEach
                        target?.add(parsed)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not read cached blocklist ${file.name}: ${error.message}")
            }
        }
    }

    /**
     * Parse a single line in hosts-file-like format. Accepts:
     *  - bare domains:               example.com
     *  - hosts-file entries:         0.0.0.0 example.com
     *  - inline comments after '#':  example.com   # tracker
     * Returns the normalised domain or null for empty / comment lines.
     */
    private fun parseLine(line: String): String? {
        val withoutComment = line.substringBefore('#').trim()
        if (withoutComment.isEmpty()) return null
        val parts = withoutComment.split(Regex("\\s+"))
        val candidate = when {
            parts.size == 1 -> parts[0]
            parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" -> parts.getOrNull(1) ?: return null
            else -> parts[0]
        }
        val normalised = candidate.lowercase().trimEnd('.')
        if (normalised.length !in 1..253) return null
        if (!normalised.contains('.')) return null
        if (!normalised.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return normalised
    }

    companion object {
        private const val TAG = "BlocklistRepository"

        @Volatile
        private var INSTANCE: BlocklistRepository? = null

        /**
         * Process-wide singleton. Both [com.sentinel.app.LinkGateActivity]
         * (Flutter engine attach) and [SentinelVpnService] (worker)
         * MUST go through this so any [setUserWhitelist] call propagates
         * to the running tunnel without an additional broadcast.
         *
         * Threading: double-checked lock; the constructor is cheap (no
         * I/O beyond the SharedPreferences read of the persisted user
         * whitelist) so blocking under the monitor briefly is fine.
         */
        fun getInstance(context: Context): BlocklistRepository {
            val existing = INSTANCE
            if (existing != null) return existing
            synchronized(this) {
                val again = INSTANCE
                if (again != null) return again
                val created = BlocklistRepository(context.applicationContext)
                INSTANCE = created
                return created
            }
        }

        /**
         * Pure matching function exposed for unit testing. Threats are
         * checked first so a domain present in both categories is reported
         * as a threat (more conservative security signal).
         */
        internal fun classify(
            domain: String,
            threats: Set<String>,
            ads: Set<String>,
            whitelist: Set<String>,
        ): MatchResult {
            if (domain.isBlank()) return MatchResult.Allowed
            val normalised = domain.lowercase().trimEnd('.')
            if (whitelist.contains(normalised)) return MatchResult.Allowed
            if (threats.contains(normalised)) {
                return MatchResult.BlockedByThreats(normalised)
            }
            if (ads.contains(normalised)) {
                return MatchResult.BlockedByAds(normalised)
            }
            var i = normalised.indexOf('.')
            while (i in 1 until normalised.length - 1) {
                val parent = normalised.substring(i + 1)
                if (whitelist.contains(parent)) return MatchResult.Allowed
                if (threats.contains(parent)) {
                    return MatchResult.BlockedByThreats(parent)
                }
                if (ads.contains(parent)) {
                    return MatchResult.BlockedByAds(parent)
                }
                i = normalised.indexOf('.', i + 1)
            }
            return MatchResult.Allowed
        }

        /**
         * Legacy uncategorised matcher kept for the existing unit tests
         * that asserted a boolean contract. Delegates to [classify] using
         * the union of the two categories as a single threat set.
         */
        internal fun matches(
            domain: String,
            blocklist: Set<String>,
            whitelist: Set<String>,
        ): Boolean = classify(
            domain = domain,
            threats = blocklist,
            ads = emptySet(),
            whitelist = whitelist,
        ) !is MatchResult.Allowed
    }
}

/** Category for a blocklist entry. */
enum class BlocklistCategory(val fileTag: String) {
    ADS("ads"),
    THREATS("threats"),
}

/** Result of [BlocklistRepository.lookup]. */
sealed class MatchResult {
    data object Allowed : MatchResult()
    data class BlockedByAds(val matchedDomain: String) : MatchResult()
    data class BlockedByThreats(val matchedDomain: String) : MatchResult()

    val category: BlocklistCategory?
        get() = when (this) {
            is BlockedByAds -> BlocklistCategory.ADS
            is BlockedByThreats -> BlocklistCategory.THREATS
            Allowed -> null
        }
}
