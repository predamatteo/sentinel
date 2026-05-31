package com.sentinel.app.vpn

/**
 * Small LRU + TTL cache of upstream DNS answers, keyed by (qName, qType).
 *
 * Stores the raw upstream answer payload as a *template*: the DNS
 * transaction id (bytes 0-1) is request-specific, so callers must
 * re-stamp the returned bytes for the new requester via
 * [DnsPacketParser.rewriteTransactionId] before sending it back. The
 * template itself is never mutated.
 *
 * Only positive answers (RCODE=NoError, not truncated, with a non-zero
 * minimum RR TTL) should be cached by the caller; negative answers are
 * intentionally not cached here (no SOA parsing), so a domain that starts
 * failing upstream recovers immediately when it comes back.
 *
 * Thread-safe via coarse synchronization on the backing map; the hot path
 * does a single O(1) get under the monitor, which is cheap relative to the
 * upstream round-trip it avoids.
 */
class DnsAnswerCache(private val maxSize: Int = 1024) {

    data class Key(val qName: String, val qType: Int)

    private class Entry(val answer: ByteArray, val expiresAtMs: Long)

    // accessOrder=true turns this into an LRU: get() moves the entry to the
    // tail, and removeEldestEntry evicts the least-recently-used head.
    private val map = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean =
            size > maxSize
    }

    /**
     * Return the cached answer template for [key] if present and unexpired,
     * else null. Expired entries are evicted on access. The returned array
     * is the stored template — do NOT mutate it; re-stamp a copy instead.
     */
    @Synchronized
    fun get(key: Key, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val entry = map[key] ?: return null
        if (nowMs >= entry.expiresAtMs) {
            map.remove(key)
            return null
        }
        return entry.answer
    }

    /**
     * Cache [answer] under [key] for [ttlSeconds]. A non-positive TTL is
     * treated as non-cacheable and ignored.
     */
    @Synchronized
    fun put(
        key: Key,
        answer: ByteArray,
        ttlSeconds: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (ttlSeconds <= 0L) return
        map[key] = Entry(answer, nowMs + ttlSeconds * 1000L)
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
