package com.sentinel.app.bridge

import android.content.Context
import com.sentinel.app.accessibility.AccessibilityHelper
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Platform channel `com.sentinel.app/accessibility`. Exposes the
 * Layer-3 status flags and the two system-settings deep links the
 * Dart UI uses to onboard the user.
 *
 * Methods (Dart side calls):
 *  - `isAccessibilityEnabled()` -> Boolean
 *  - `openAccessibilitySettings()` -> Boolean
 *  - `canDrawOverlays()` -> Boolean
 *  - `openOverlaySettings()` -> Boolean
 *  - `isFullyConfigured()` -> Boolean (both above are granted)
 *
 * All methods are stateless and synchronous on the Kotlin side; they
 * read the system flags fresh on every call so the UI always sees
 * the latest state after returning from system Settings.
 */
class AccessibilityChannel(
    private val context: Context,
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

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isAccessibilityEnabled" ->
                result.success(AccessibilityHelper.isServiceEnabled(context))
            "openAccessibilitySettings" -> {
                AccessibilityHelper.openAccessibilitySettings(context)
                result.success(true)
            }
            "canDrawOverlays" ->
                result.success(AccessibilityHelper.canDrawOverlays(context))
            "openOverlaySettings" -> {
                AccessibilityHelper.openOverlaySettings(context)
                result.success(true)
            }
            "isFullyConfigured" ->
                result.success(AccessibilityHelper.isFullyConfigured(context))
            else -> result.notImplemented()
        }
    }

    companion object {
        const val CHANNEL_NAME = "com.sentinel.app/accessibility"
    }
}
