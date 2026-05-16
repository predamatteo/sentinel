package com.sentinel.app.bridge

import android.content.Context
import android.util.Log
import com.sentinel.app.analysis.AnalysisResult
import com.sentinel.app.analysis.AnalysisStats
import com.sentinel.app.analysis.LinkAnalyzer
import com.sentinel.app.persistence.StatsRepository
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exposes the native analysis engine to Flutter via a single MethodChannel.
 *
 * Methods (Dart side calls):
 *  - analyze(url: String) -> Map<String, dynamic>  // serialised AnalysisResult
 *  - proceedToChrome(url: String) -> Boolean        // true if forwarded
 *  - cancelNavigation() -> void                     // closes the current task
 *  - currentUrl() -> String?                        // URL the activity was opened with
 *  - isDefaultBrowser() -> Boolean                  // best-effort check
 *  - openDefaultBrowserSettings() -> Boolean        // opens system settings
 *
 * The channel owns its own [CoroutineScope] so analyses survive transient UI
 * detachments (rotation, etc.) and are cleanly cancelled on dispose().
 */
class AnalysisChannel(
    private val context: Context,
    private val analyzer: LinkAnalyzer,
    private val urlProvider: () -> String?,
    private val onClose: () -> Unit,
    private val defaultBrowserHelper: DefaultBrowserHelper,
    private val statsRepository: StatsRepository? = null,
) : MethodChannel.MethodCallHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var channel: MethodChannel? = null

    fun attach(engine: FlutterEngine) {
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL_NAME).also {
            it.setMethodCallHandler(this)
        }
    }

    fun dispose() {
        channel?.setMethodCallHandler(null)
        channel = null
        scope.cancel()
    }

    /**
     * Notify the Flutter side that a new URL arrived after the engine was
     * already configured (typically from [LinkGateActivity.onNewIntent]).
     * No-op when the channel is not yet attached.
     */
    fun notifyNewUrl(url: String) {
        channel?.invokeMethod("onNewUrl", url)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "analyze" -> handleAnalyze(call, result)
            "proceedToChrome" -> handleProceed(call, result)
            "cancelNavigation" -> handleCancel(result)
            "currentUrl" -> result.success(urlProvider())
            "isDefaultBrowser" -> result.success(defaultBrowserHelper.isDefault())
            "openDefaultBrowserSettings" -> result.success(defaultBrowserHelper.openSettings())
            else -> result.notImplemented()
        }
    }

    private fun handleAnalyze(call: MethodCall, result: MethodChannel.Result) {
        val url = call.argument<String>("url")
        if (url.isNullOrBlank()) {
            result.error("INVALID_ARG", "url argument is required", null)
            return
        }
        scope.launch {
            val outcome: AnalysisResult = try {
                analyzer.analyze(url)
            } catch (error: Exception) {
                // Internal errors must not increment the "Link verificati"
                // counter: the user did not actually get a verdict. Surface
                // the failure to Dart so the UI can fall back to the
                // suspicious-screen path.
                withContext(Dispatchers.Main) {
                    result.error("ANALYZE_FAILED", error.message, null)
                }
                return@launch
            }
            // Every result the analyzer returned counts as a "link verificato"
            // regardless of verdict: the goal of this counter is to show how
            // many checks the user has performed today.
            AnalysisStats.recordLinkChecked()
            statsRepository?.let { repo ->
                try {
                    repo.recordAnalysisEvent(
                        url = outcome.url,
                        verdict = outcome.verdict.name,
                        reasons = outcome.reasons,
                        sources = outcome.sources,
                        analyzedAt = outcome.analyzedAt.toEpochMilli(),
                    )
                } catch (error: Exception) {
                    // Persistence errors must not break the UX: the
                    // analysis result has already been computed. Log so
                    // the failure is visible in logcat instead of being
                    // dropped on the floor.
                    Log.w(TAG, "Failed to persist analysis event", error)
                }
            }
            withContext(Dispatchers.Main) {
                result.success(outcome.toMap())
            }
        }
    }

    private fun handleProceed(call: MethodCall, result: MethodChannel.Result) {
        val url = call.argument<String>("url")
        if (url.isNullOrBlank()) {
            result.error("INVALID_ARG", "url argument is required", null)
            return
        }
        val launched = ChromeLauncher.open(context, url)
        if (launched) onClose()
        result.success(launched)
    }

    private fun handleCancel(result: MethodChannel.Result) {
        onClose()
        result.success(null)
    }

    companion object {
        const val CHANNEL_NAME = "com.sentinel.app/analysis"
        private const val TAG = "AnalysisChannel"
    }
}
