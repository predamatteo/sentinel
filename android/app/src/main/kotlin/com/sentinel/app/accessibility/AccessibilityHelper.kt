package com.sentinel.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * Pure helpers around the two system flags that Layer 3 depends on:
 *  - is the [SentinelAccessibilityService] enabled in
 *    Settings > Accessibility?
 *  - has the user granted SYSTEM_ALERT_WINDOW so we can draw an
 *    overlay on top of arbitrary apps?
 *
 * The class is intentionally object-less: every method takes the
 * [Context] explicitly so the helpers are trivially testable from
 * Robolectric and do not retain references to activities.
 */
object AccessibilityHelper {

    /**
     * True iff [SentinelAccessibilityService] is listed under the
     * system-wide ENABLED_ACCESSIBILITY_SERVICES secure setting.
     *
     * The system setting stores the entries as
     * `pkg/cls:pkg/cls:...`, so we walk a [TextUtils.SimpleStringSplitter]
     * matching either the canonical or flattened ComponentName form.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SentinelAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component == expected) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * SYSTEM_ALERT_WINDOW grant. Below API 23 this permission was a normal
     * install-time grant, so the call returns true automatically.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Convenience used by the dashboard advisory chip + Settings UI. */
    fun isFullyConfigured(context: Context): Boolean =
        isServiceEnabled(context) && canDrawOverlays(context)
}
