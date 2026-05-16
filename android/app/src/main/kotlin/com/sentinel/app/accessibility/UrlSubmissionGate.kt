package com.sentinel.app.accessibility

/**
 * Pure-logic counterpart of the URL submission heuristic used by
 * [SentinelAccessibilityService]. Extracted so it can be unit-tested
 * without an AccessibilityService context.
 *
 * Two concerns:
 *  1) recognise when an in-progress URL string is "complete enough"
 *     to merit a network analysis (avoid hammering the analyzer
 *     on every keystroke);
 *  2) suppress duplicate submissions of the same URL within a short
 *     window so that re-renders of the same browser tab do not
 *     trigger repeated lookups.
 *
 * The gate is single-threaded — instances are accessed from the
 * accessibility service's main looper handler.
 */
class UrlSubmissionGate(
    private val rateLimitWindowMs: Long = DEFAULT_RATE_LIMIT_WINDOW_MS,
    private val maxRemembered: Int = DEFAULT_MAX_REMEMBERED,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // Linked LRU: insertion order preserves recency; we never search.
    private val recent: LinkedHashMap<String, Long> =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>?): Boolean {
                return size > maxRemembered
            }
        }

    /**
     * Decide whether a candidate text from the URL bar should trigger
     * analysis. Returns true at most once per URL within
     * [rateLimitWindowMs]. The decision side-effects the LRU; callers
     * MUST treat true as "I am consuming this URL".
     */
    fun shouldSubmit(candidate: String): Boolean {
        val normalised = normalise(candidate) ?: return false
        val now = clock()
        val previous = recent[normalised]
        if (previous != null && now - previous < rateLimitWindowMs) {
            return false
        }
        recent[normalised] = now
        return true
    }

    /**
     * Static heuristic for "this string looks like a URL submission".
     * Public so the service can short-circuit before scheduling the
     * debounce timer. The implementation is deliberately tolerant of
     * partial inputs (Chrome's omnibox often shows the URL without
     * scheme) while rejecting clearly partial typing.
     */
    fun looksLikeSubmittableUrl(raw: String?): Boolean {
        val text = raw?.trim() ?: return false
        if (text.length < MIN_URL_LENGTH) return false
        if (text.contains(' ')) return false
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            // Require something after the scheme so "https://" alone
            // does not pass.
            val afterScheme = text.substringAfter("://")
            return afterScheme.isNotEmpty() && afterScheme.contains('.')
        }
        // Bare host form like "example.com/path". Require at least one
        // dot and no path-only inputs starting with "/".
        if (text.startsWith("/")) return false
        if (!text.contains('.')) return false
        // Reject single-letter labels like ".com" or "a." that the user
        // is mid-typing.
        val firstLabel = text.substringBefore('.')
        if (firstLabel.length < 2) return false
        return true
    }

    /**
     * Normalise to the analyser's expected wire form. Schemes are kept
     * lowercase, bare hosts are prefixed with "http://" so that
     * UrlProvider implementations parse them as URIs.
     *
     * Public because the AccessibilityService MUST send the normalised
     * form to the analyzer — otherwise providers using `java.net.URI`
     * fall back to "host == null" and downgrade the verdict to
     * SUSPICIOUS, which the Layer 3 policy now treats as "do not
     * surface overlay".
     */
    fun normalise(raw: String): String? {
        val text = raw.trim()
        if (!looksLikeSubmittableUrl(text)) return null
        return if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            text
        } else {
            "http://$text"
        }
    }

    /** Test-only inspection. */
    internal fun rememberedSize(): Int = recent.size

    companion object {
        const val DEFAULT_RATE_LIMIT_WINDOW_MS = 10_000L
        const val DEFAULT_MAX_REMEMBERED = 64
        private const val MIN_URL_LENGTH = 4
    }
}
