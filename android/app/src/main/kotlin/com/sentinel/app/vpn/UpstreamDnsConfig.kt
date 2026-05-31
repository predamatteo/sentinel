package com.sentinel.app.vpn

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide configuration for the upstream DNS used to forward
 * non-blocked queries. Values are mutated from the platform channel
 * (Flutter passes them in) and read from inside the VpnService read
 * loop, so we route them through an [AtomicReference] for visibility.
 *
 * Defaults match Cloudflare (1.1.1.1 / 1.0.0.1), chosen because they are
 * fast, do not log clients and are the same defaults as Android Private
 * DNS uses when set to "cloudflare-dns.com".
 *
 * Sprint 2 ships plain UDP DNS only. DoT and DoH support are deferred.
 */
object UpstreamDnsConfig {

    data class Snapshot(
        val primary: String,
        val secondary: String,
        val dotHostname: String,
        // IPv6 upstreams for forwarding AAAA / IPv6-originated DNS queries.
        // Not yet wired to Remote Config; default to Cloudflare IPv6.
        val primaryV6: String = "2606:4700:4700::1111",
        val secondaryV6: String = "2606:4700:4700::1001",
    )

    private val state = AtomicReference(
        Snapshot(
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            dotHostname = "cloudflare-dns.com",
        )
    )

    fun current(): Snapshot = state.get()

    fun update(primary: String, secondary: String, dotHostname: String) {
        val cleanedPrimary = primary.trim().ifBlank { "1.1.1.1" }
        val cleanedSecondary = secondary.trim().ifBlank { cleanedPrimary }
        val cleanedDot = dotHostname.trim().ifBlank { "cloudflare-dns.com" }
        // IPv6 upstreams keep their defaults (not configurable yet).
        state.set(Snapshot(cleanedPrimary, cleanedSecondary, cleanedDot))
    }
}
