package com.sentinel.app.vpn

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sentinel.app.analysis.AnalysisStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide bridge between Flutter and the [SentinelVpnService]. Holds
 * a reference to the current [Activity] only long enough to forward the
 * VpnService.prepare() consent screen back to the user.
 *
 * Lifecycle:
 *  - VPN consent is granted once per install. After that, `requestStart()`
 *    can transition directly to confirmStart() without UI.
 *  - On revocation (`Settings > VPN > Disconnect`), Android invokes
 *    `SentinelVpnService.onRevoke` which tears the tunnel down. The
 *    controller is informed via [VpnControllerHolder].
 */
class VpnController(
    private val context: Context,
    private val blocklist: BlocklistRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pendingConfirm: ((Boolean) -> Unit)? = null

    /**
     * Either return null (no consent intent needed) or an [Intent] that
     * the activity must launch with `startActivityForResult` to obtain
     * the user's VPN consent. After the user accepts, the activity calls
     * [onConsentResult].
     */
    fun prepareIntent(): Intent? = VpnService.prepare(context)

    /**
     * Start the foreground VPN service. Assumes consent has been granted
     * (call [prepareIntent] first). Safe to call repeatedly: if the
     * service is already running this is a no-op.
     */
    fun confirmStart() {
        scope.launch { blocklist.ensureLoaded() }
        val intent = Intent(context, SentinelVpnService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stop the VPN service. Idempotent. */
    fun stop() {
        val intent = Intent(context, SentinelVpnService::class.java).apply {
            action = SentinelVpnService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            // If the service is already gone there is nothing to do.
        }
    }

    fun isRunning(): Boolean = VpnControllerHolder.isRunning()

    /**
     * Best-effort check for an active tethering / hotspot interface. Used
     * by the dashboard to warn that tethered clients are unprotected and
     * may need the hotspot recycled after the VPN is stopped. See
     * [TetheringDetector] for why this is heuristic.
     */
    fun isHotspotActive(): Boolean = TetheringDetector.isHotspotActive()

    /**
     * Open the system tethering / wireless settings so the user can recycle
     * the hotspot. The dedicated TetherSettings screen is not part of the
     * public SDK and varies by OEM, so we try it first and fall back to the
     * guaranteed wireless-settings panel, then to top-level settings.
     */
    fun openHotspotSettings() {
        val candidates = listOf(
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.TetherSettings",
            ),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Target not present on this OEM; try the next candidate.
            } catch (_: Exception) {
                // Some OEMs throw SecurityException for the explicit
                // component; fall through to the next candidate.
            }
        }
    }

    /**
     * Build a combined snapshot of VPN and analysis counters. Used as the
     * one-shot read from the Dart side; the steady-state push path is the
     * `com.sentinel.app/stats_events` EventChannel.
     */
    fun snapshotStats(): Map<String, Any?> {
        val combined = VpnStats.snapshot().toMutableMap()
        combined["linksChecked"] = AnalysisStats.linksCheckedToday()
        return combined
    }

    /** Bridge for the platform channel: forwards a user whitelist set. */
    fun applyWhitelist(domains: Collection<String>) {
        blocklist.setUserWhitelist(domains)
    }

    /**
     * Bridge for the platform channel: kick a remote-list refresh.
     *
     * The Dart side passes a map of `<tag, url>` entries. We infer the
     * [BlocklistCategory] from the tag using a conservative mapping
     * (anything that does not look like an ads/tracker list ends up in
     * the `THREATS` category so we never under-block). New tags added
     * via Remote Config will inherit the threat classification until
     * the mapping is extended here.
     */
    fun refreshRemoteLists(targets: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            targets.forEach { (tag, url) ->
                if (url.isNotBlank()) {
                    val category = inferCategoryFromTag(tag)
                    blocklist.fetchAndCacheRemote(tag, url, category)
                }
            }
            blocklist.reloadFromDisk()
        }
    }

    private fun inferCategoryFromTag(tag: String): BlocklistCategory {
        val normalised = tag.lowercase()
        return when {
            normalised.contains("ads") -> BlocklistCategory.ADS
            normalised.contains("tracker") -> BlocklistCategory.ADS
            normalised.contains("affiliate") -> BlocklistCategory.ADS
            else -> BlocklistCategory.THREATS
        }
    }

    /** Bridge: update upstream DNS settings. */
    fun setUpstream(primary: String, secondary: String, dotHostname: String) {
        UpstreamDnsConfig.update(primary, secondary, dotHostname)
    }

    fun rememberPendingConfirm(callback: (Boolean) -> Unit) {
        pendingConfirm = callback
    }

    fun onConsentResult(granted: Boolean) {
        val cb = pendingConfirm
        pendingConfirm = null
        cb?.invoke(granted)
    }
}
