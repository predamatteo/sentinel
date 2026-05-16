package com.sentinel.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sentinel.app.BuildConfig
import com.sentinel.app.analysis.AnalysisResult
import com.sentinel.app.analysis.AnalysisStats
import com.sentinel.app.analysis.LinkAnalyzer
import com.sentinel.app.analysis.Verdict
import com.sentinel.app.analysis.providers.LocalBlacklistProvider
import com.sentinel.app.analysis.providers.SafeBrowsingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Layer 3 of the protection stack.
 *
 * Observes [AccessibilityEvent]s coming from a hand-curated list of
 * mainstream Android browsers, reads the current URL from the address
 * bar, and routes complete URL submissions through [LinkAnalyzer]. On
 * a MALICIOUS / SUSPICIOUS verdict an [OverlayManager] surface is
 * drawn on top of the offending browser.
 *
 * Privacy contract (also documented in the user-facing
 * `accessibility_service_description` string):
 *  - Events from packages NOT in [watchedBrowsers] are dropped before
 *    we touch any string content. The native AccessibilityService
 *    framework also enforces this via `packageNames=...` in
 *    res/xml/accessibility_service_config.xml, but the in-process
 *    check is a defense in depth.
 *  - Even for watched packages we only read the URL bar node — we
 *    never call `getRootInActiveWindow()` to read arbitrary content.
 *  - We never log URL contents at INFO level. A debug-build-only log
 *    (gated on [BuildConfig.DEBUG]) prints the first 32 chars only.
 *
 * Threading:
 *  - Event handling and overlay updates run on the main looper.
 *  - The single owned [scope] dispatches analyzer calls onto IO so
 *    the main looper is never blocked.
 */
class SentinelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val gate = UrlSubmissionGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var analyzer: LinkAnalyzer
    private lateinit var overlay: OverlayManager

    @Volatile
    private var pendingPackage: String? = null

    @Volatile
    private var lastCandidate: String? = null
    private var pendingRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        analyzer = buildAnalyzer()
        overlay = OverlayManager.get(applicationContext)
        Log.i(TAG, "SentinelAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        val isBrowser = packageName in watchedBrowsers

        // Auto-dismiss the overlay as soon as the user leaves the
        // browser that triggered it. We do this for ALL packages —
        // including the launcher, recents, and other apps — using
        // only the package name of the window state change. No text
        // content from non-browser apps is read; the privacy
        // contract is preserved by the text/content branches below.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val owner = overlay.currentOverlayPackage()
            if (owner != null && owner != packageName) {
                // Skip our own app: TYPE_APPLICATION_OVERLAY attaching
                // raises a STATE_CHANGED with our package, which would
                // otherwise kill the overlay we just drew.
                val ours = applicationContext.packageName
                // Skip the system UI: it raises transient state
                // changes during back/recents/swipe animations.
                if (packageName != ours &&
                    packageName != SYSTEM_UI_PACKAGE &&
                    !overlay.shouldRetainOnPackageChange(System.currentTimeMillis())
                ) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "auto-dismiss overlay: left $owner -> $packageName")
                    }
                    overlay.hide()
                }
            }
        }

        if (!isBrowser) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleBrowserEvent(packageName)

            else -> { /* ignored */ }
        }
    }

    private fun handleBrowserEvent(packageName: String) {
        val candidate = extractUrlBarText(packageName) ?: return
        if (!gate.looksLikeSubmittableUrl(candidate)) return
        // Schedule a debounce: only emit if the URL bar text stops
        // changing for IDLE_AFTER_MS. Cancel any in-flight pending
        // runnable so rapid typing collapses into a single dispatch.
        pendingPackage = packageName
        lastCandidate = candidate
        pendingRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            val frozen = lastCandidate ?: return@Runnable
            if (!gate.shouldSubmit(frozen)) return@Runnable
            // Pass the *normalised* form (with http:// scheme prefixed
            // when the user typed a bare host) so URI-based providers
            // such as LocalBlacklistProvider can parse the host. Without
            // this, "paypa1-secure.com" becomes host=null -> SUSPICIOUS
            // -> overlay suppressed.
            val normalised = gate.normalise(frozen) ?: return@Runnable
            dispatchAnalysis(normalised)
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, IDLE_AFTER_MS)
    }

    private fun extractUrlBarText(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            val ids = urlBarIds[packageName] ?: return fallbackUrlBarText(root)
            for (id in ids) {
                val matches = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                for (node in matches) {
                    val text = node.text?.toString()
                    if (!text.isNullOrBlank()) return text
                }
            }
            fallbackUrlBarText(root)
        } catch (error: Exception) {
            Log.d(TAG, "url-bar extraction failed for $packageName", error)
            null
        }
    }

    /**
     * Best-effort fallback when the package-specific id list does not
     * match (e.g. browser updated their internal view ids). We walk
     * the active window root and pick the first focused EditText-like
     * node whose text looks URL-shaped.
     */
    private fun fallbackUrlBarText(root: AccessibilityNodeInfo): String? {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < MAX_FALLBACK_DEPTH) {
            val size = queue.size
            for (i in 0 until size) {
                val node = queue.removeFirst()
                val cls = node.className?.toString() ?: ""
                if (cls.contains("EditText", ignoreCase = true) ||
                    cls.contains("UrlBar", ignoreCase = true)
                ) {
                    val text = node.text?.toString()
                    if (gate.looksLikeSubmittableUrl(text)) return text
                }
                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    queue.add(child)
                }
            }
            depth += 1
        }
        return null
    }

    private fun dispatchAnalysis(url: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "analyzing url=${url.take(URL_LOG_PREFIX_LEN)}...")
        }
        scope.launch {
            val result: AnalysisResult = try {
                analyzer.analyze(url)
            } catch (error: Exception) {
                Log.w(TAG, "analyzer threw for in-bar URL", error)
                return@launch
            }
            AnalysisStats.recordLinkChecked()
            // Layer 3 overlay policy: only the definitely-bad verdict
            // (MALICIOUS) interrupts the user. SUSPICIOUS / UNKNOWN
            // (e.g. SafeBrowsing timeout or "not configured") would
            // produce too many false positives for normal browsing
            // since EVERY typed URL would receive at least one
            // SUSPICIOUS verdict whenever the API is slow. Layer 1
            // (intent gating) is the right place to surface those
            // intermediate signals because the user is already in a
            // pause-and-confirm context there. Here we stay silent.
            if (result.verdict != Verdict.MALICIOUS) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "skipping overlay for ${result.verdict} verdict")
                }
                return@launch
            }
            val owner = pendingPackage
            handler.post {
                // UX 2 (2026-05-15 hot-fix v2): show overlay FIRST
                // so it visually covers Chrome's transient error
                // page, THEN issue GLOBAL_ACTION_BACK so the browser
                // silently navigates back behind the overlay. This
                // works because the overlay window uses
                // FLAG_NOT_FOCUSABLE (see OverlayManager.buildLayoutParams)
                // which prevents our window from consuming the back
                // action — the system routes it to the focused
                // window, which is still the browser.
                overlay.show(
                    url = result.url,
                    verdict = result.verdict,
                    reasons = result.reasons,
                    owningPackage = owner,
                    onBack = { /* back already fired pre-emptively */ },
                )
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun buildAnalyzer(): LinkAnalyzer {
        val local = LocalBlacklistProvider(applicationContext)
        val safe = SafeBrowsingProvider(
            context = applicationContext,
            apiKey = BuildConfig.SAFE_BROWSING_API_KEY,
        )
        return LinkAnalyzer(providers = listOf(local, safe))
    }

    override fun onInterrupt() {
        pendingRunnable?.let(handler::removeCallbacks)
        pendingRunnable = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        pendingRunnable?.let(handler::removeCallbacks)
        pendingRunnable = null
        runCatching { overlay.hide() }
        scope.cancel()
    }

    companion object {
        private const val TAG = "SentinelAxs"
        // Debounce after the URL bar stops changing before we hit the
        // analyzer. Reduced 700ms -> 350ms -> 200ms after user
        // feedback (2026-05-15) on perceived latency. Local blacklist
        // responds in <5ms and Safe Browsing in ~200ms, so 200ms is
        // still enough to aggregate rapid keystrokes. The LRU
        // rate-limit in UrlSubmissionGate protects from double
        // dispatching the same URL.
        private const val IDLE_AFTER_MS = 200L
        private const val MAX_FALLBACK_DEPTH = 6
        private const val URL_LOG_PREFIX_LEN = 32

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /**
         * Hand-curated set of browser packages we observe. The
         * AccessibilityService framework filters at the system level
         * (see res/xml/accessibility_service_config.xml#packageNames),
         * but we duplicate the check here as defense in depth so that
         * a misconfigured XML never lets events from another app
         * reach our string-processing code.
         */
        internal val watchedBrowsers: Set<String> = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.microsoft.emmx",          // Edge
            "com.brave.browser",
            "com.sec.android.app.sbrowser", // Samsung Internet
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.ecosia.android",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
        )

        /**
         * Per-package list of candidate `viewIdResourceName`s for the
         * URL bar widget. Order matters: the first non-blank text node
         * wins. Add new entries as browsers change layouts.
         *
         * To discover new ids on a device:
         *   adb shell uiautomator dump
         *   adb pull /sdcard/window_dump.xml
         */
        internal val urlBarIds: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
            "com.chrome.canary" to listOf("com.chrome.canary:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title",
            ),
            "org.mozilla.firefox_beta" to listOf(
                "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            ),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            ),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "com.duckduckgo.mobile.android" to listOf(
                "com.duckduckgo.mobile.android:id/omnibarTextInput",
            ),
        )
    }
}
