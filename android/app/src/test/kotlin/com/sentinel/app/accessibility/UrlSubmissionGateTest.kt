package com.sentinel.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure-logic [UrlSubmissionGate] used by
 * [SentinelAccessibilityService] to decide when an in-flight URL bar
 * text is worth analysing.
 *
 * The gate is built around an internal LRU and a virtual clock so the
 * test can advance time without sleeping.
 */
class UrlSubmissionGateTest {

    private class FakeClock(initial: Long = 1_000L) {
        var now: Long = initial
        operator fun invoke(): Long = now
    }

    @Test
    fun looksLikeSubmittableUrlAcceptsFullUrls() {
        val gate = UrlSubmissionGate()
        assertTrue(gate.looksLikeSubmittableUrl("https://example.com"))
        assertTrue(gate.looksLikeSubmittableUrl("http://example.com/path?x=1"))
        assertTrue(gate.looksLikeSubmittableUrl("https://www.example.co.uk/x"))
    }

    @Test
    fun looksLikeSubmittableUrlAcceptsBareHosts() {
        val gate = UrlSubmissionGate()
        assertTrue(gate.looksLikeSubmittableUrl("example.com"))
        assertTrue(gate.looksLikeSubmittableUrl("github.com/anthropic"))
    }

    @Test
    fun looksLikeSubmittableUrlRejectsPartialTyping() {
        val gate = UrlSubmissionGate()
        assertFalse(gate.looksLikeSubmittableUrl(null))
        assertFalse(gate.looksLikeSubmittableUrl(""))
        assertFalse(gate.looksLikeSubmittableUrl("ex"))
        assertFalse(gate.looksLikeSubmittableUrl("https://"))
        assertFalse(gate.looksLikeSubmittableUrl("a.b"))         // first label too short
        assertFalse(gate.looksLikeSubmittableUrl("/path"))       // starts with slash
        assertFalse(gate.looksLikeSubmittableUrl("hello world")) // contains space
        assertFalse(gate.looksLikeSubmittableUrl("noTLDhere"))   // no dot
    }

    @Test
    fun shouldSubmitNormalisesBareHostToHttp() {
        val gate = UrlSubmissionGate()
        assertTrue(gate.shouldSubmit("example.com"))
        // Same host again immediately is rate-limited.
        assertFalse(gate.shouldSubmit("example.com"))
        assertFalse(gate.shouldSubmit("http://example.com"))
    }

    @Test
    fun shouldSubmitRespectsRateLimitWindow() {
        val clock = FakeClock(initial = 1_000L)
        val gate = UrlSubmissionGate(
            rateLimitWindowMs = 10_000L,
            clock = clock::invoke,
        )
        assertTrue(gate.shouldSubmit("https://example.com"))
        clock.now = 1_500L
        assertFalse(gate.shouldSubmit("https://example.com"))
        // Window expires.
        clock.now = 12_000L
        assertTrue(gate.shouldSubmit("https://example.com"))
    }

    @Test
    fun shouldSubmitDistinguishesUniqueUrls() {
        val gate = UrlSubmissionGate()
        assertTrue(gate.shouldSubmit("https://example.com"))
        assertTrue(gate.shouldSubmit("https://example.org"))
        assertTrue(gate.shouldSubmit("https://example.net"))
    }

    @Test
    fun shouldSubmitDropsInvalidCandidates() {
        val gate = UrlSubmissionGate()
        assertFalse(gate.shouldSubmit(""))
        assertFalse(gate.shouldSubmit("  "))
        assertFalse(gate.shouldSubmit("ab"))
        assertFalse(gate.shouldSubmit("hello world"))
    }

    @Test
    fun shouldSubmitEvictsOldestWhenOverCapacity() {
        val gate = UrlSubmissionGate(maxRemembered = 4)
        assertTrue(gate.shouldSubmit("https://a.example"))
        assertTrue(gate.shouldSubmit("https://b.example"))
        assertTrue(gate.shouldSubmit("https://c.example"))
        assertTrue(gate.shouldSubmit("https://d.example"))
        // Fifth entry triggers eviction of the oldest.
        assertTrue(gate.shouldSubmit("https://e.example"))
        assertEquals(4, gate.rememberedSize())
        // Oldest entry (a.example) is gone and should be admitted again.
        assertTrue(gate.shouldSubmit("https://a.example"))
    }

    @Test
    fun shouldSubmitNormalisesHostWithPath() {
        // The bare-host -> http://... normalisation also applies when a
        // path is present, but we still rate-limit per normalised URL.
        val gate = UrlSubmissionGate()
        assertTrue(gate.shouldSubmit("example.com/path"))
        // Same URL submitted with an explicit http:// is the same after
        // normalisation, so should be rate-limited.
        assertFalse(gate.shouldSubmit("http://example.com/path"))
    }
}
