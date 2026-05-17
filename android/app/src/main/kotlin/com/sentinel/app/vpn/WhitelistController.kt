package com.sentinel.app.vpn

import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the live user whitelist set used by [BlocklistRepository].
 *
 * Responsibilities:
 *  - load the persisted set from [UserWhitelistStore] at construction
 *    so hot-path lookups see the last-known whitelist immediately,
 *  - normalise incoming domains (lowercase, trim, drop trailing dot,
 *    drop empties) so the matching set is always canonical,
 *  - publish the new set atomically and persist it through the store.
 *
 * Pure Kotlin, no Android dependencies, so the contract is covered by
 * fast JVM unit tests.
 */
class WhitelistController(private val store: UserWhitelistStore) {

    private val current: AtomicReference<Set<String>> = AtomicReference(store.load())

    /** Lock-free snapshot suitable for the DNS hot path. */
    fun current(): Set<String> = current.get()

    /**
     * Replace the user whitelist. The set is normalised, published
     * atomically, and persisted before this call returns. Subsequent
     * [current] reads observe the new set.
     */
    fun replace(domains: Collection<String>) {
        val normalised = normaliseAll(domains)
        current.set(normalised)
        store.save(normalised)
    }

    companion object {
        /** Lowercase, trim whitespace, drop trailing dot. Null for blank. */
        internal fun normalise(domain: String): String? {
            val n = domain.lowercase().trim().trimEnd('.')
            return if (n.isBlank()) null else n
        }

        internal fun normaliseAll(domains: Collection<String>): Set<String> {
            val out = HashSet<String>(domains.size)
            for (raw in domains) {
                val n = normalise(raw) ?: continue
                out.add(n)
            }
            return out
        }
    }
}
