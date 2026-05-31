package com.sentinel.app.bridge

import android.app.Activity
import android.content.Intent
import com.sentinel.app.vpn.VpnController
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Platform channel `com.sentinel.app/vpn`.
 *
 * Methods (Dart side calls):
 *  - requestStart() -> "ready" | "consent_required" | "error"
 *  - confirmStart() -> Boolean
 *  - stop() -> Boolean
 *  - isRunning() -> Boolean
 *  - isHotspotActive() -> Boolean
 *  - openHotspotSettings() -> Boolean
 *  - getEnvironmentStatus() -> Map<String, Any?>
 *  - openPrivateDnsSettings() -> Boolean
 *  - getStats() -> Map<String, Any?>
 *  - setWhitelist({ domains: List<String> }) -> Boolean
 *  - refreshRemoteLists({ targets: Map<String, String> }) -> Boolean
 *  - setUpstream({ primary, secondary, dotHostname }) -> Boolean
 *
 * The channel does NOT own the consent dance: when [requestStart] returns
 * "consent_required", the LinkGateActivity calls
 * [Activity.startActivityForResult] with the prepare intent. The result is
 * fed back via [onActivityResult] -> [VpnController.onConsentResult].
 */
class VpnChannel(
    private val controller: VpnController,
    private val activityProvider: () -> Activity?,
) : MethodChannel.MethodCallHandler {

    private var channel: MethodChannel? = null

    fun attach(engine: FlutterEngine) {
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL_NAME).also {
            it.setMethodCallHandler(this)
        }
    }

    fun dispose() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    /** Forward an activity result. Returns true if it was the consent code. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_VPN_CONSENT) return false
        controller.onConsentResult(resultCode == Activity.RESULT_OK)
        return true
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestStart" -> handleRequestStart(result)
            "confirmStart" -> handleConfirmStart(result)
            "stop" -> { controller.stop(); result.success(true) }
            "isRunning" -> result.success(controller.isRunning())
            "isHotspotActive" -> result.success(controller.isHotspotActive())
            "openHotspotSettings" -> {
                controller.openHotspotSettings()
                result.success(true)
            }
            "getEnvironmentStatus" -> result.success(controller.environmentStatus())
            "openPrivateDnsSettings" -> {
                controller.openPrivateDnsSettings()
                result.success(true)
            }
            "getStats" -> result.success(controller.snapshotStats())
            "setWhitelist" -> {
                val domains = (call.argument<List<*>>("domains") ?: emptyList<Any?>())
                    .filterIsInstance<String>()
                controller.applyWhitelist(domains)
                result.success(true)
            }
            "refreshRemoteLists" -> {
                @Suppress("UNCHECKED_CAST")
                val raw = call.argument<Map<String, String>>("targets") ?: emptyMap()
                controller.refreshRemoteLists(raw)
                result.success(true)
            }
            "setUpstream" -> {
                val primary = call.argument<String>("primary") ?: "1.1.1.1"
                val secondary = call.argument<String>("secondary") ?: "1.0.0.1"
                val dotHostname = call.argument<String>("dotHostname") ?: "cloudflare-dns.com"
                controller.setUpstream(primary, secondary, dotHostname)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun handleRequestStart(result: MethodChannel.Result) {
        if (controller.isRunning()) {
            result.success("ready")
            return
        }
        val prepare = controller.prepareIntent()
        if (prepare == null) {
            // Consent already granted previously.
            result.success("ready")
            return
        }
        val activity = activityProvider() ?: run {
            result.success("error")
            return
        }
        controller.rememberPendingConfirm { granted ->
            channel?.invokeMethod("onConsentResult", granted)
        }
        try {
            activity.startActivityForResult(prepare, REQUEST_VPN_CONSENT)
            result.success("consent_required")
        } catch (error: Exception) {
            result.error("CONSENT_FAILED", error.message, null)
        }
    }

    private fun handleConfirmStart(result: MethodChannel.Result) {
        try {
            controller.confirmStart()
            result.success(true)
        } catch (error: Exception) {
            result.error("START_FAILED", error.message, null)
        }
    }

    companion object {
        const val CHANNEL_NAME = "com.sentinel.app/vpn"
        const val REQUEST_VPN_CONSENT = 8121
    }
}
