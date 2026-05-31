package com.sentinel.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for the LRU+TTL DNS answer cache. Time is injected
 * via the nowMs parameter so the tests are deterministic.
 */
class DnsAnswerCacheTest {

    private val key = DnsAnswerCache.Key("example.com", 1)

    @Test
    fun returnsStoredAnswerWithinTtl() {
        val cache = DnsAnswerCache()
        val answer = byteArrayOf(1, 2, 3)
        cache.put(key, answer, ttlSeconds = 60, nowMs = 1_000)
        assertArrayEquals(answer, cache.get(key, nowMs = 1_000))
        // expiresAt = 1000 + 60_000 = 61_000; still valid just before it.
        assertArrayEquals(answer, cache.get(key, nowMs = 60_999))
    }

    @Test
    fun expiresAtTtlBoundary() {
        val cache = DnsAnswerCache()
        cache.put(key, byteArrayOf(1), ttlSeconds = 10, nowMs = 0)
        // expiresAt = 10_000; get is expired at exactly the boundary.
        assertNull(cache.get(key, nowMs = 10_000))
        assertNull(cache.get(key, nowMs = 20_000))
    }

    @Test
    fun ttlZeroIsNotCached() {
        val cache = DnsAnswerCache()
        cache.put(key, byteArrayOf(1), ttlSeconds = 0, nowMs = 0)
        assertNull(cache.get(key, nowMs = 0))
    }

    @Test
    fun evictsLeastRecentlyUsedOverCapacity() {
        val cache = DnsAnswerCache(maxSize = 2)
        val k1 = DnsAnswerCache.Key("a.com", 1)
        val k2 = DnsAnswerCache.Key("b.com", 1)
        val k3 = DnsAnswerCache.Key("c.com", 1)
        cache.put(k1, byteArrayOf(1), ttlSeconds = 100, nowMs = 0)
        cache.put(k2, byteArrayOf(2), ttlSeconds = 100, nowMs = 0)
        // Touch k1 so k2 becomes the least-recently-used entry.
        assertNotNull(cache.get(k1, nowMs = 0))
        cache.put(k3, byteArrayOf(3), ttlSeconds = 100, nowMs = 0)
        assertNotNull(cache.get(k1, nowMs = 0))
        assertNull(cache.get(k2, nowMs = 0))
        assertNotNull(cache.get(k3, nowMs = 0))
    }
}
