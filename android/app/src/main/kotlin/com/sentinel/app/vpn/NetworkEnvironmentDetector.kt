package com.sentinel.app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Best-effort detection of network-environment conditions that silently
 * defeat or conflict with Sentinel's UDP/53 DNS filtering, so the dashboard
 * can warn honestly instead of showing a clean "protected" state.
 *
 * Two conditions matter (IPv6 is now intercepted natively, so it is not a
 * gap any more):
 *  - Encrypted DNS: Android Private DNS (DoT/853) or a browser's Secure DNS
 *    (DoH/443) is not on UDP/53, so those lookups bypass us entirely.
 *  - Strict Private DNS (a specific hostname): can conflict with our
 *    advertised resolver and, on some devices, break resolution.
 *
 * Detection is permission-light and guarded: the literal Settings.Global
 * keys are read (the public constants are @hide), and every platform call
 * is wrapped so it can never throw or blank the dashboard. On API < 28
 * (no Private DNS feature) it reports a clean, unsupported status.
 */
object NetworkEnvironmentDetector {

    private const val TAG = "NetworkEnvDetector"

    enum class PrivateDnsMode { OFF, OPPORTUNISTIC, STRICT, UNKNOWN }

    data class EnvironmentStatus(
        val privateDnsMode: PrivateDnsMode,
        val privateDnsHostname: String?,
        val encryptedDnsActive: Boolean,
        val apiLevelSupported: Boolean,
    )

    fun inspect(context: Context): EnvironmentStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Private DNS does not exist before API 28; behave exactly as
            // before (no advisories).
            return EnvironmentStatus(PrivateDnsMode.OFF, null, encryptedDnsActive = false, apiLevelSupported = false)
        }
        val mode = readPrivateDnsMode(context)
        val hostname = if (mode == PrivateDnsMode.STRICT) readPrivateDnsSpecifier(context) else null
        val active = isPrivateDnsActive(context)
        return EnvironmentStatus(
            privateDnsMode = mode,
            privateDnsHostname = hostname,
            encryptedDnsActive = encryptedDnsActive(mode, active),
            apiLevelSupported = true,
        )
    }

    /** Pure mapping of the Settings.Global value to our enum. Unit-tested. */
    internal fun classify(modeString: String?): PrivateDnsMode = when (modeString?.lowercase()) {
        "off" -> PrivateDnsMode.OFF
        "opportunistic" -> PrivateDnsMode.OPPORTUNISTIC
        "hostname" -> PrivateDnsMode.STRICT
        null, "" -> PrivateDnsMode.OFF
        else -> PrivateDnsMode.UNKNOWN
    }

    /**
     * Strict always counts (the system forces DoT). Opportunistic counts
     * only when the link actually negotiated it, to avoid a false-positive
     * advisory. Pure; unit-tested.
     */
    internal fun encryptedDnsActive(mode: PrivateDnsMode, linkPrivateDnsActive: Boolean): Boolean =
        mode == PrivateDnsMode.STRICT ||
            (mode == PrivateDnsMode.OPPORTUNISTIC && linkPrivateDnsActive)

    private fun readPrivateDnsMode(context: Context): PrivateDnsMode = try {
        classify(Settings.Global.getString(context.contentResolver, "private_dns_mode"))
    } catch (error: Exception) {
        Log.w(TAG, "private_dns_mode read failed: ${error.message}")
        PrivateDnsMode.UNKNOWN
    }

    private fun readPrivateDnsSpecifier(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "private_dns_specifier")?.ifBlank { null }
    } catch (error: Exception) {
        null
    }

    // Only reached on API >= 28 (guarded in inspect()); isPrivateDnsActive is
    // the authoritative per-network signal that an opportunistic upgrade
    // actually succeeded.
    @SuppressLint("NewApi")
    private fun isPrivateDnsActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val linkProperties = cm.getLinkProperties(network) ?: return false
        linkProperties.isPrivateDnsActive
    } catch (error: Exception) {
        Log.w(TAG, "isPrivateDnsActive read failed: ${error.message}")
        false
    }
}
