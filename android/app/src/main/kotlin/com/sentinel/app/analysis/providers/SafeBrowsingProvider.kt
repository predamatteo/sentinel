package com.sentinel.app.analysis.providers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sentinel.app.analysis.ProviderOutcome
import com.sentinel.app.analysis.UrlProvider
import com.sentinel.app.analysis.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Google Safe Browsing Lookup API v4 client.
 *
 * The API key is injected via BuildConfig.SAFE_BROWSING_API_KEY which is in
 * turn populated from `local.properties` at build time. When the key is
 * missing or empty the provider does NOT alarm: it returns an UNAVAILABLE
 * outcome explaining that the online check could not run, so the user can
 * still make an informed decision.
 *
 * Sprint 2 hardening: the request includes the X-Android-Package and
 * X-Android-Cert headers expected by Google when the API key is
 * restricted to a specific Android app. The headers are harmless when the
 * restriction is "None"; after Sprint 2 the user should switch the API
 * key restriction to "Android apps" in Google Cloud Console so the key
 * leaks become useless to a third party.
 *
 * Endpoint: https://safebrowsing.googleapis.com/v4/threatMatches:find
 */
class SafeBrowsingProvider(
    private val context: Context,
    private val apiKey: String,
    private val clientId: String = "sentinel-app",
    private val clientVersion: String = "1.0.0",
    private val endpoint: String = "https://safebrowsing.googleapis.com/v4/threatMatches:find",
    private val connectTimeoutMs: Int = 2_500,
    private val readTimeoutMs: Int = 2_500,
) : UrlProvider {

    override val sourceName: String = "Google Safe Browsing"

    // Signing certificate SHA-1 never changes at runtime, cache it.
    @Volatile
    private var cachedSigningSha1: String? = null

    override suspend fun check(url: String): ProviderOutcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            // Unconfigured provider is a config gap, not a threat signal:
            // fail-open as UNAVAILABLE so it never escalates a clean URL.
            return@withContext ProviderOutcome.unavailable(
                source = sourceName,
                reason = "Safe Browsing API non configurata",
            )
        }

        val requestBody = buildRequestBody(url)
        val target = URL("$endpoint?key=$apiKey")
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            // Headers required by Google when the API key is restricted to
            // an Android app. Safe to include even when no restriction is
            // configured.
            setRequestProperty("X-Android-Package", context.packageName)
            signingCertSha1()?.let { setRequestProperty("X-Android-Cert", it) }
        }

        return@withContext try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode"
                // Service-availability problem (429 quota, 403 key restriction,
                // 5xx outage) — not a threat verdict. Fail-open.
                return@withContext ProviderOutcome.unavailable(
                    source = sourceName,
                    reason = "Verifica Safe Browsing non disponibile (HTTP $responseCode): $errorBody",
                )
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseResponse(body)
        } catch (error: Exception) {
            // Transport error (offline, DNS, TLS, timeout) — about the
            // network, not the URL. Fail-open as UNAVAILABLE.
            ProviderOutcome.unavailable(
                source = sourceName,
                reason = "Verifica Safe Browsing non disponibile: ${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(url: String): String {
        val client = JSONObject()
            .put("clientId", clientId)
            .put("clientVersion", clientVersion)

        val threatTypes = JSONArray()
            .put("MALWARE")
            .put("SOCIAL_ENGINEERING")
            .put("UNWANTED_SOFTWARE")
            .put("POTENTIALLY_HARMFUL_APPLICATION")

        val threatInfo = JSONObject()
            .put("threatTypes", threatTypes)
            .put("platformTypes", JSONArray().put("ANY_PLATFORM"))
            .put("threatEntryTypes", JSONArray().put("URL"))
            .put("threatEntries", JSONArray().put(JSONObject().put("url", url)))

        return JSONObject()
            .put("client", client)
            .put("threatInfo", threatInfo)
            .toString()
    }

    @androidx.annotation.VisibleForTesting
    internal fun parseResponse(body: String): ProviderOutcome {
        if (body.isBlank() || body.trim() == "{}") {
            return ProviderOutcome(
                source = sourceName,
                verdict = Verdict.SAFE,
                reasons = emptyList(),
            )
        }
        val json = JSONObject(body)
        val matches = json.optJSONArray("matches")
        if (matches == null || matches.length() == 0) {
            return ProviderOutcome(
                source = sourceName,
                verdict = Verdict.SAFE,
                reasons = emptyList(),
            )
        }
        val reasons = (0 until matches.length()).map { i ->
            val match = matches.getJSONObject(i)
            val threatType = match.optString("threatType", "MINACCIA")
            italianReasonFor(threatType)
        }.distinct()
        return ProviderOutcome(
            source = sourceName,
            verdict = Verdict.MALICIOUS,
            reasons = reasons,
        )
    }

    private fun italianReasonFor(threatType: String): String = when (threatType) {
        "MALWARE" -> "Segnalato come distributore di malware"
        "SOCIAL_ENGINEERING" -> "Segnalato come phishing o ingegneria sociale"
        "UNWANTED_SOFTWARE" -> "Segnalato come software indesiderato"
        "POTENTIALLY_HARMFUL_APPLICATION" -> "Segnalato come applicazione potenzialmente dannosa"
        else -> "Minaccia rilevata: $threatType"
    }

    /**
     * Compute the SHA-1 of the first signing certificate of this APK,
     * formatted as upper-case hex separated by colons (the same format
     * Google's "App restrictions" UI expects).
     *
     * Result is cached for the process lifetime: the signature does not
     * change at runtime.
     */
    private fun signingCertSha1(): String? {
        cachedSigningSha1?.let { return it }
        val pm = context.packageManager
        val packageName = context.packageName
        val signatures: Array<android.content.pm.Signature>? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sig = info.signingInfo
                when {
                    sig == null -> null
                    sig.hasMultipleSigners() -> sig.apkContentsSigners
                    else -> sig.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
        } catch (error: Exception) {
            null
        }
        val first = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(first.toByteArray())
        val hex = digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        cachedSigningSha1 = hex
        return hex
    }
}
