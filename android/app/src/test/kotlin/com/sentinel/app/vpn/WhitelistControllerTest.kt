package com.sentinel.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WhitelistController]. The pure-Kotlin design lets us
 * exercise the persistence contract with an in-memory fake store, with
 * no need for Robolectric or instrumentation.
 */
class WhitelistControllerTest {

    @Test
    fun initialStateLoadsFromStore() {
        val store = InMemoryUserWhitelistStore(seed = setOf("example.com", "foo.bar"))
        val controller = WhitelistController(store)
        assertEquals(setOf("example.com", "foo.bar"), controller.current())
    }

    @Test
    fun replacePersistsAndPublishesAtomically() {
        val store = InMemoryUserWhitelistStore()
        val controller = WhitelistController(store)
        controller.replace(listOf("example.com", "foo.io"))
        assertEquals(setOf("example.com", "foo.io"), controller.current())
        assertEquals(setOf("example.com", "foo.io"), store.load())
    }

    @Test
    fun replaceNormalisesCaseTrailingDotAndWhitespace() {
        val store = InMemoryUserWhitelistStore()
        val controller = WhitelistController(store)
        controller.replace(listOf("  EXAMPLE.COM ", "Foo.Bar.", "\tbaz.io"))
        assertEquals(
            setOf("example.com", "foo.bar", "baz.io"),
            controller.current(),
        )
    }

    @Test
    fun replaceDropsBlankAndEmptyEntries() {
        val store = InMemoryUserWhitelistStore()
        val controller = WhitelistController(store)
        controller.replace(listOf("", "   ", "real.example", "."))
        assertEquals(setOf("real.example"), controller.current())
    }

    @Test
    fun freshControllerOverDirtyStoreReusesPersistedSet() {
        // Simulates the SentinelVpnService restart path: a new
        // BlocklistRepository / WhitelistController is built from
        // scratch and must observe the last-saved set without
        // requiring the Flutter UI to push it again.
        val store = InMemoryUserWhitelistStore()
        WhitelistController(store).replace(listOf("user-allowed.it"))
        val rebooted = WhitelistController(store)
        assertEquals(setOf("user-allowed.it"), rebooted.current())
    }

    @Test
    fun normaliseDropsBlank() {
        assertNull(WhitelistController.normalise(""))
        assertNull(WhitelistController.normalise("   "))
        assertNull(WhitelistController.normalise("\t"))
    }

    @Test
    fun normaliseLowercasesAndStripsTrailingDot() {
        assertEquals("example.com", WhitelistController.normalise("Example.COM."))
        assertEquals("foo.bar.io", WhitelistController.normalise(" Foo.Bar.IO "))
    }

    @Test
    fun replaceClearsPreviousEntries() {
        val store = InMemoryUserWhitelistStore(seed = setOf("old.example"))
        val controller = WhitelistController(store)
        assertTrue(controller.current().contains("old.example"))
        controller.replace(listOf("new.example"))
        assertEquals(setOf("new.example"), controller.current())
        assertFalse(store.load().contains("old.example"))
    }

    private class InMemoryUserWhitelistStore(
        seed: Set<String> = emptySet(),
    ) : UserWhitelistStore {
        private var saved: Set<String> = seed
        override fun load(): Set<String> = saved
        override fun save(domains: Set<String>) {
            saved = domains.toSet()
        }
    }
}
