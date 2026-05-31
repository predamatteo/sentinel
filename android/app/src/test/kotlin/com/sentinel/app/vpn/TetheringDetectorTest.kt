package com.sentinel.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TetheringDetector.isTetherCandidate], the pure decision
 * function behind hotspot detection. The interface-enumeration side is a
 * thin platform wrapper and is not unit-tested (NetworkInterface is final
 * and cannot be fabricated without instrumentation).
 */
class TetheringDetectorTest {

    @Test
    fun wifiHotspotInterfacesAreDetected() {
        assertTrue(candidate("ap0"))
        assertTrue(candidate("wlan1"))
        assertTrue(candidate("swlan0"))
        assertTrue(candidate("softap0"))
    }

    @Test
    fun usbAndBluetoothTetherInterfacesAreDetected() {
        assertTrue(candidate("rndis0"))
        assertTrue(candidate("usb0"))
        assertTrue(candidate("bt-pan"))
    }

    @Test
    fun normalClientInterfacesAreIgnored() {
        // wlan0 is the station (client) interface, not the AP; tun0 is our
        // own VPN tunnel. Neither must be mistaken for a hotspot.
        assertFalse(candidate("wlan0"))
        assertFalse(candidate("rmnet0"))
        assertFalse(candidate("eth0"))
        assertFalse(candidate("tun0"))
        assertFalse(candidate("lo"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(candidate("AP0"))
        assertTrue(candidate("RNDIS0"))
    }

    @Test
    fun downInterfaceIsNotACandidate() {
        assertFalse(candidate("ap0", isUp = false))
    }

    @Test
    fun loopbackIsNotACandidate() {
        assertFalse(candidate("ap0", isLoopback = true))
    }

    @Test
    fun interfaceWithoutIpv4IsNotACandidate() {
        // A tether interface that is up but has no IPv4 yet (just brought
        // up) should not trigger the warning.
        assertFalse(candidate("ap0", hasIpv4 = false))
    }

    private fun candidate(
        name: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        hasIpv4: Boolean = true,
    ): Boolean = TetheringDetector.isTetherCandidate(name, isUp, isLoopback, hasIpv4)
}
