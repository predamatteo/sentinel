package com.sentinel.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fail-open invariant: UNAVAILABLE never escalates a verdict, but real
 * positive signals still win.
 */
class VerdictWorstTest {

    @Test
    fun safePlusUnavailableStaysSafe() {
        assertEquals(Verdict.SAFE, Verdict.worst(listOf(Verdict.SAFE, Verdict.UNAVAILABLE)))
    }

    @Test
    fun allUnavailableIsSafe() {
        assertEquals(Verdict.SAFE, Verdict.worst(listOf(Verdict.UNAVAILABLE, Verdict.UNAVAILABLE)))
    }

    @Test
    fun maliciousWinsOverUnavailable() {
        assertEquals(Verdict.MALICIOUS, Verdict.worst(listOf(Verdict.MALICIOUS, Verdict.UNAVAILABLE)))
    }

    @Test
    fun suspiciousBeatsSafe() {
        assertEquals(Verdict.SUSPICIOUS, Verdict.worst(listOf(Verdict.SAFE, Verdict.SUSPICIOUS)))
    }

    @Test
    fun maliciousBeatsSuspicious() {
        assertEquals(Verdict.MALICIOUS, Verdict.worst(listOf(Verdict.SUSPICIOUS, Verdict.MALICIOUS)))
    }

    @Test
    fun emptyIsSafe() {
        assertEquals(Verdict.SAFE, Verdict.worst(emptyList()))
    }
}
