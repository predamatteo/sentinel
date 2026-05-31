package com.sentinel.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the parent-label matching logic used by [BlocklistRepository].
 * The actual repo's I/O paths require an Android Context, so this test
 * exercises the pure matching functions [BlocklistRepository.matches] and
 * [BlocklistRepository.classify].
 */
class BlocklistMatchTest {

    // Legacy uncategorised contract — kept so callers of `matches` still
    // work. New code should use `classify` directly.
    private val blocklist = setOf(
        "evil.com",
        "tracker.example.io",
        "doubleclick.net",
    )
    private val noWhitelist = emptySet<String>()

    @Test
    fun exactMatchIsBlocked() {
        assertTrue(BlocklistRepository.matches("evil.com", blocklist, noWhitelist))
        assertTrue(BlocklistRepository.matches("EVIL.COM", blocklist, noWhitelist))
        assertTrue(BlocklistRepository.matches("evil.com.", blocklist, noWhitelist))
    }

    @Test
    fun subdomainsAreBlocked() {
        assertTrue(BlocklistRepository.matches("ads.evil.com", blocklist, noWhitelist))
        assertTrue(BlocklistRepository.matches("deep.sub.tracker.example.io", blocklist, noWhitelist))
    }

    @Test
    fun unrelatedDomainsAreAllowed() {
        assertFalse(BlocklistRepository.matches("safe.com", blocklist, noWhitelist))
        assertFalse(BlocklistRepository.matches("example.io", blocklist, noWhitelist))
        assertFalse(BlocklistRepository.matches("notevil.com", blocklist, noWhitelist))
    }

    @Test
    fun whitelistPrecedesBlocklist() {
        val whitelist = setOf("evil.com")
        assertFalse(BlocklistRepository.matches("evil.com", blocklist, whitelist))
        // Subdomain of a whitelisted parent should be allowed.
        assertFalse(BlocklistRepository.matches("ads.evil.com", blocklist, whitelist))
    }

    @Test
    fun blankDomainIsNotBlocked() {
        assertFalse(BlocklistRepository.matches("", blocklist, noWhitelist))
    }

    // --- Sprint Quality: categorised `classify` contract ---------------

    private val threats = setOf("phish.example", "malware.test")
    private val ads = setOf("doubleclick.net", "ads.example.io")

    @Test
    fun classifyReturnsThreatForExactThreatMatch() {
        val result = BlocklistRepository.classify(
            "phish.example",
            threats,
            ads,
            noWhitelist,
        )
        assertTrue(result is MatchResult.BlockedByThreats)
        assertEquals(BlocklistCategory.THREATS, result.category)
    }

    @Test
    fun classifyReturnsAdsForExactAdsMatch() {
        val result = BlocklistRepository.classify(
            "doubleclick.net",
            threats,
            ads,
            noWhitelist,
        )
        assertTrue(result is MatchResult.BlockedByAds)
        assertEquals(BlocklistCategory.ADS, result.category)
    }

    @Test
    fun classifyReturnsAllowedForUnknownDomain() {
        val result = BlocklistRepository.classify(
            "totally-fine.test",
            threats,
            ads,
            noWhitelist,
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun classifyHonoursWhitelist() {
        val wl = setOf("phish.example")
        val result = BlocklistRepository.classify(
            "phish.example",
            threats,
            ads,
            wl,
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun classifyHonoursWhitelistOnSubdomain() {
        val wl = setOf("phish.example")
        val result = BlocklistRepository.classify(
            "deep.sub.phish.example",
            threats,
            ads,
            wl,
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun classifyClimbsParentLabelsForAds() {
        val result = BlocklistRepository.classify(
            "banner.ads.example.io",
            threats,
            ads,
            noWhitelist,
        )
        assertTrue(result is MatchResult.BlockedByAds)
        assertEquals("ads.example.io", (result as MatchResult.BlockedByAds).matchedDomain)
    }

    @Test
    fun classifyPrefersThreatOverAdsWhenBothMatch() {
        val sharedDomain = "both.example"
        val result = BlocklistRepository.classify(
            sharedDomain,
            setOf(sharedDomain),
            setOf(sharedDomain),
            noWhitelist,
        )
        assertTrue(
            "Threats must win over ads for the security signal to be conservative",
            result is MatchResult.BlockedByThreats,
        )
    }

    @Test
    fun classifyDoesNotMatchOnPartialLabelOverlap() {
        // The string "notevil.com" must not match the "evil.com" entry
        // by accident. Parent-label climb starts after the first dot.
        val result = BlocklistRepository.classify(
            "notevil.com",
            setOf("evil.com"),
            emptySet(),
            noWhitelist,
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun defaultWhitelistDomainNeverFlaggedAsThreat() {
        // graph.facebook.com is in the runtime defaultWhitelist; verify
        // the classify contract honours an explicit whitelist that
        // simulates the runtime merge.
        val result = BlocklistRepository.classify(
            "graph.facebook.com",
            setOf("graph.facebook.com"),
            emptySet(),
            setOf("graph.facebook.com"),
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun googleAdservicesApexAllowedSoSerpClicksWork() {
        // Regression guard: the apex googleadservices.com is the
        // redirector Chrome opens when a user clicks on a "Sponsorizzato"
        // SERP result. Blocking it returns NXDOMAIN and Chrome shows
        // "Sito non raggiungibile" even when the destination is
        // legitimate. The ad-delivery subdomain partner.googleadservices.com
        // is still expected to be blocked.
        val whitelist = setOf("googleadservices.com", "www.googleadservices.com")
        val adsWithApex = setOf("googleadservices.com", "partner.googleadservices.com")

        // Apex + canonical www. variant: must be allowed.
        val apex = BlocklistRepository.classify(
            "googleadservices.com",
            emptySet(),
            adsWithApex,
            whitelist,
        )
        assertTrue("apex must be allowed", apex is MatchResult.Allowed)

        val www = BlocklistRepository.classify(
            "www.googleadservices.com",
            emptySet(),
            adsWithApex,
            whitelist,
        )
        assertTrue("www variant must be allowed", www is MatchResult.Allowed)

        // partner.* must still be blocked even though the apex is
        // whitelisted — the parent-label climb finds the whitelist
        // entry only for the apex, not the partner subdomain.
        val partner = BlocklistRepository.classify(
            "partner.googleadservices.com",
            emptySet(),
            adsWithApex,
            whitelist,
        )
        assertTrue(
            "partner.googleadservices.com must remain blocked for ad delivery",
            partner is MatchResult.BlockedByAds,
        )
    }

    @Test
    fun connectFacebookNetWhitelistedForWebLogin() {
        // Mirrors the runtime defaultWhitelist merge: connect.facebook.net
        // (the Facebook JS SDK host) is allowed so web "Login with Facebook"
        // works even if a public list includes it.
        val whitelist = setOf("connect.facebook.net")
        val result = BlocklistRepository.classify(
            "connect.facebook.net",
            emptySet(),
            setOf("connect.facebook.net"),
            whitelist,
        )
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun adjustRedirectorAllowedWhileApexStaysBlocked() {
        // app.adjust.com (click / deep-link redirector) is whitelisted, while
        // the adjust.com apex and other adjust subdomains stay blocked via
        // the parent-label climb — the apex-asymmetry pattern.
        val whitelist = setOf("app.adjust.com")
        val ads = setOf("adjust.com")
        assertTrue(
            "redirector must be allowed",
            BlocklistRepository.classify("app.adjust.com", emptySet(), ads, whitelist)
                is MatchResult.Allowed,
        )
        assertTrue(
            "other adjust subdomains stay blocked via the apex",
            BlocklistRepository.classify("s2s.adjust.com", emptySet(), ads, whitelist)
                is MatchResult.BlockedByAds,
        )
    }
}
