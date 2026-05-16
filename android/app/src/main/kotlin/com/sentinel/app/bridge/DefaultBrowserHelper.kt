package com.sentinel.app.bridge

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * Best-effort helper to inspect and request the "default browser" role.
 *
 * On Android 10+ (API 29) we use [RoleManager.ROLE_BROWSER] to query and
 * request the role. On older versions we fall back to comparing the
 * resolveActivity for a generic http intent against our package, and we
 * deep-link the user to the default-apps settings screen.
 */
class DefaultBrowserHelper(private val activity: Activity) {

    fun isDefault(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isDefaultViaRoleManager()
        }
        return isDefaultViaResolver()
    }

    fun openSettings(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (requestRole()) return true
            }
            // Fallback: send the user to the default-apps settings screen.
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            true
        } catch (error: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", activity.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                true
            } catch (fallback: Exception) {
                false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isDefaultViaRoleManager(): Boolean {
        val manager = activity.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    private fun isDefaultViaResolver(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))
        val resolved = activity.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == activity.packageName
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestRole(): Boolean {
        val manager = activity.getSystemService(RoleManager::class.java) ?: return false
        if (!manager.isRoleAvailable(RoleManager.ROLE_BROWSER)) return false
        if (manager.isRoleHeld(RoleManager.ROLE_BROWSER)) return true
        val intent = manager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        activity.startActivityForResult(intent, REQUEST_DEFAULT_BROWSER_ROLE)
        return true
    }

    companion object {
        const val REQUEST_DEFAULT_BROWSER_ROLE = 4271
    }
}
