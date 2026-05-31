package com.sentinel.app.vpn

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Best-effort detection of an active tethering / Wi-Fi hotspot interface.
 *
 * A foreground [android.net.VpnService] cannot see — let alone protect —
 * traffic from tethered clients: Android routes hotspot traffic around
 * app-based VPNs. Worse, while Sentinel is up it advertises the sinkhole
 * `10.0.0.1` as the system DNS, which the tethering DNS forwarder
 * (netd/dnsmasq) snapshots at hotspot start and keeps until the hotspot is
 * recycled — leaving tethered clients without name resolution even after
 * Sentinel is stopped or uninstalled. We cannot fix that without root, but
 * we can detect the hotspot and warn the user.
 *
 * Detection is heuristic by necessity: the privileged tethering APIs and
 * `WifiManager.isWifiApEnabled()` are hidden or blocked for sideloaded apps
 * on modern Android. Enumerating [NetworkInterface]s needs no permission and
 * is reliable enough for an advisory: we look for an up, non-loopback
 * interface whose name matches a known tether prefix and that carries an
 * IPv4 address. Covers Wi-Fi ("ap0", "wlan1", "swlan0", "softap0"), USB
 * ("rndis0", "usb0") and Bluetooth ("bt-pan") tethering. Our own tunnel
 * ("tun0") and the station interface ("wlan0") are deliberately excluded.
 */
object TetheringDetector {

    private const val TAG = "TetheringDetector"

    private val TETHER_PREFIXES = listOf(
        "ap", "wlan1", "swlan", "softap", "rndis", "usb", "bt-pan",
    )

    /** True when at least one tethering interface appears to be active. */
    fun isHotspotActive(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            interfaces.toList().any { iface ->
                isTetherCandidate(
                    name = iface.name ?: "",
                    isUp = safeIsUp(iface),
                    isLoopback = safeIsLoopback(iface),
                    hasIpv4 = hasIpv4Address(iface),
                )
            }
        } catch (error: SocketException) {
            // Enumeration can fail transiently; treat as "no hotspot".
            Log.w(TAG, "NetworkInterface enumeration failed: ${error.message}")
            false
        }
    }

    /**
     * Pure decision over a flattened view of a network interface. Extracted
     * so the matching logic is unit-testable without fabricating real
     * [NetworkInterface] instances (the class is final and unmockable).
     */
    internal fun isTetherCandidate(
        name: String,
        isUp: Boolean,
        isLoopback: Boolean,
        hasIpv4: Boolean,
    ): Boolean {
        if (!isUp || isLoopback || !hasIpv4) return false
        val normalised = name.lowercase()
        return TETHER_PREFIXES.any { normalised.startsWith(it) }
    }

    private fun safeIsUp(iface: NetworkInterface): Boolean =
        try {
            iface.isUp
        } catch (_: SocketException) {
            false
        }

    private fun safeIsLoopback(iface: NetworkInterface): Boolean =
        try {
            iface.isLoopback
        } catch (_: SocketException) {
            false
        }

    private fun hasIpv4Address(iface: NetworkInterface): Boolean =
        iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
}
