package com.sentinel.app.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Forwards a validated URL to the user's browser of choice. We prefer Chrome
 * via Custom Tabs (lighter, faster, no internal WebView state), then any
 * other browser the user has installed. Sentinel's own package is excluded
 * to prevent re-entering the analysis loop.
 */
object ChromeLauncher {

    private const val CHROME_PACKAGE = "com.android.chrome"
    private val CHROME_VARIANTS = listOf(
        CHROME_PACKAGE,
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
    )

    fun open(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val ownPackage = context.packageName

        // First preference: Chrome (any channel) via Custom Tabs.
        val chromePackage = CHROME_VARIANTS.firstOrNull { isPackageInstalled(context, it) }
        if (chromePackage != null) {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            intent.intent.setPackage(chromePackage)
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                intent.launchUrl(context, uri)
                true
            } catch (error: Exception) {
                false
            }
        }

        // Fallback: any installed browser that handles VIEW, except Sentinel.
        val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvers = context.packageManager.queryIntentActivities(viewIntent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != ownPackage }
            .distinct()

        if (resolvers.isEmpty()) return false

        // Prefer the first non-Sentinel browser deterministically.
        viewIntent.setPackage(resolvers.first())
        return try {
            context.startActivity(viewIntent)
            true
        } catch (error: Exception) {
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (error: PackageManager.NameNotFoundException) {
        false
    }
}
