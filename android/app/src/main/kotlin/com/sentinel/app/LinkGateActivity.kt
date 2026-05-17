package com.sentinel.app

import android.content.Intent
import com.sentinel.app.analysis.AnalysisStats
import com.sentinel.app.analysis.LinkAnalyzer
import com.sentinel.app.analysis.providers.LocalBlacklistProvider
import com.sentinel.app.analysis.providers.SafeBrowsingProvider
import com.sentinel.app.bridge.AccessibilityChannel
import com.sentinel.app.bridge.AnalysisChannel
import com.sentinel.app.bridge.DefaultBrowserHelper
import com.sentinel.app.bridge.StatsEventChannel
import com.sentinel.app.bridge.VpnChannel
import com.sentinel.app.persistence.StatsRepository
import com.sentinel.app.persistence.asStatsRepository
import com.sentinel.app.vpn.BlocklistRepository
import com.sentinel.app.vpn.StatsDatabaseProvider
import com.sentinel.app.vpn.VpnController
import com.sentinel.app.vpn.VpnStats
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single Activity that powers the Sprint 1 + 2 + 3 surface area:
 *
 *  - When launched from the home screen it shows the onboarding/dashboard
 *    UI (Flutter side).
 *  - When launched from another app via a VIEW http(s) intent it captures
 *    the URL, exposes it to Flutter through [AnalysisChannel.currentUrl],
 *    and the Dart side routes to the analyzing screen.
 *  - Hosts four platform channels:
 *      - `com.sentinel.app/analysis`      (method channel, Sprint 1)
 *      - `com.sentinel.app/vpn`           (method channel, Sprint 2)
 *      - `com.sentinel.app/stats_events`  (event channel, Sprint Quality)
 *      - `com.sentinel.app/accessibility` (method channel, Sprint 3)
 *
 * launchMode is `singleTask` (see AndroidManifest), so subsequent link
 * clicks arrive via [onNewIntent] rather than stacking new instances.
 */
class LinkGateActivity : FlutterActivity() {

    private var pendingUrl: String? = null
    private var analysisChannel: AnalysisChannel? = null
    private var vpnChannel: VpnChannel? = null
    private var statsChannel: StatsEventChannel? = null
    private var accessibilityChannel: AccessibilityChannel? = null
    private val boot = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUrl = extractUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = extractUrl(intent) ?: return
        pendingUrl = incoming
        // Notify Flutter that a new URL arrived so it can re-route to the
        // analyzing screen even when the engine is already running.
        analysisChannel?.notifyNewUrl(incoming)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Sprint Quality: build the persistence layer first so every other
        // component sees a non-null StatsRepository on attach. The DB
        // singleton is lazy and cheap to materialise.
        val database = StatsDatabaseProvider.get(applicationContext)
        val statsRepository: StatsRepository = database.asStatsRepository()
        VpnStats.bindRepository(statsRepository)
        AnalysisStats.bindRepository(statsRepository)
        boot.launch {
            VpnStats.warmFromDb()
            AnalysisStats.warmFromDb()
        }

        val blacklistProvider = LocalBlacklistProvider(applicationContext)
        val safeBrowsingProvider = SafeBrowsingProvider(
            context = applicationContext,
            apiKey = BuildConfig.SAFE_BROWSING_API_KEY,
        )
        val analyzer = LinkAnalyzer(
            providers = listOf(blacklistProvider, safeBrowsingProvider),
        )

        val analysis = AnalysisChannel(
            context = applicationContext,
            analyzer = analyzer,
            urlProvider = { pendingUrl },
            onClose = { finishAndRemoveTask() },
            defaultBrowserHelper = DefaultBrowserHelper(this),
            statsRepository = statsRepository,
        )
        analysis.attach(flutterEngine)
        analysisChannel = analysis

        // Singleton: the same instance is reused by SentinelVpnService so
        // a setUserWhitelist call from the Settings UI takes effect on the
        // running tunnel without an additional IPC hop.
        val blocklistRepo = BlocklistRepository.getInstance(applicationContext)
        val controller = VpnController(applicationContext, blocklistRepo)
        val vpn = VpnChannel(
            controller = controller,
            activityProvider = { this },
        )
        vpn.attach(flutterEngine)
        vpnChannel = vpn

        val stats = StatsEventChannel(statsRepository)
        stats.attach(flutterEngine)
        statsChannel = stats

        val accessibility = AccessibilityChannel(applicationContext)
        accessibility.attach(flutterEngine)
        accessibilityChannel = accessibility
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Forward consent results to the VPN channel first so the Dart
        // side can react before the default-browser helper takes over.
        if (vpnChannel?.onActivityResult(requestCode, resultCode) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        analysisChannel?.dispose()
        analysisChannel = null
        vpnChannel?.dispose()
        vpnChannel = null
        statsChannel?.dispose()
        statsChannel = null
        accessibilityChannel?.dispose()
        accessibilityChannel = null
        boot.cancel()
        super.onDestroy()
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return data.toString()
    }
}
