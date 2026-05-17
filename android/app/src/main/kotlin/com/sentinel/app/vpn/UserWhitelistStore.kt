package com.sentinel.app.vpn

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent storage for the user-managed domain whitelist.
 *
 * The whitelist lives on disk so it survives:
 *  - the [SentinelVpnService] being killed by the OS,
 *  - a stop/start cycle from the UI,
 *  - a device reboot where the system auto-restarts the VPN before the
 *    Flutter engine has a chance to push the in-memory list.
 *
 * The interface is split from the SharedPreferences-backed implementation
 * so unit tests can inject an in-memory fake without pulling in
 * Robolectric or instrumented tests.
 */
interface UserWhitelistStore {
    fun load(): Set<String>
    fun save(domains: Set<String>)
}

/**
 * Default [UserWhitelistStore] backed by a dedicated SharedPreferences
 * file. Keeps the storage namespace separate from the Flutter
 * `shared_preferences` plugin so the contract is owned by Kotlin and
 * cannot drift if the Dart plugin's internal encoding ever changes.
 */
class SharedPrefsUserWhitelistStore(context: Context) : UserWhitelistStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_FILE,
        Context.MODE_PRIVATE,
    )

    override fun load(): Set<String> {
        // Defensive copy: SharedPreferences docs warn against mutating
        // the returned set directly.
        return prefs.getStringSet(KEY_DOMAINS, emptySet())?.toSet() ?: emptySet()
    }

    override fun save(domains: Set<String>) {
        prefs.edit { putStringSet(KEY_DOMAINS, domains) }
    }

    companion object {
        private const val PREFS_FILE = "sentinel_user_whitelist"
        private const val KEY_DOMAINS = "domains"
    }
}
