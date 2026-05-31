package com.sentinel.app.vpn

import com.sentinel.app.vpn.NetworkEnvironmentDetector.PrivateDnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the detector's classification helpers. */
class NetworkEnvironmentDetectorTest {

    @Test
    fun classifiesPrivateDnsModeStrings() {
        assertEquals(PrivateDnsMode.OFF, NetworkEnvironmentDetector.classify("off"))
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, NetworkEnvironmentDetector.classify("opportunistic"))
        assertEquals(PrivateDnsMode.STRICT, NetworkEnvironmentDetector.classify("hostname"))
        assertEquals(PrivateDnsMode.OFF, NetworkEnvironmentDetector.classify(null))
        assertEquals(PrivateDnsMode.OFF, NetworkEnvironmentDetector.classify(""))
        assertEquals(PrivateDnsMode.UNKNOWN, NetworkEnvironmentDetector.classify("garbage"))
    }

    @Test
    fun encryptedDnsActiveTruthTable() {
        // Strict always counts.
        assertTrue(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.STRICT, false))
        assertTrue(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.STRICT, true))
        // Opportunistic only when the link actually negotiated it.
        assertTrue(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.OPPORTUNISTIC, true))
        assertFalse(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.OPPORTUNISTIC, false))
        // Off / unknown never count.
        assertFalse(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.OFF, true))
        assertFalse(NetworkEnvironmentDetector.encryptedDnsActive(PrivateDnsMode.UNKNOWN, true))
    }
}
