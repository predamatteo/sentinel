# Sprint 3 — Layer 3 (AccessibilityService)

## Goal
Add the third and final layer of the Sentinel protection stack: an
`AccessibilityService` that observes the URL bar of a curated set of
mainstream browsers and runs the existing analyser against URLs typed
directly into them. Surface the verdict as a system-wide overlay
when the URL is flagged as MALICIOUS or SUSPICIOUS.

## Status
Implemented. All build verifications green:
- `flutter analyze` -> 0 issues
- `flutter test` -> 19/19 passing (12 existing + 7 new)
- `./gradlew :app:testDebugUnitTest` -> 40/40 passing
- `flutter build apk --debug` -> built
- `flutter build apk --release` -> built (debug-signed)
- emoji grep across `lib/`, `android/app/src/`, `*.arb`, `*.xml` -> clean

## Components

### Kotlin (new)
- `accessibility/SentinelAccessibilityService.kt` — the
  AccessibilityService entry point. Filters events by browser package
  on the very first line (defense in depth on top of the manifest
  allow list); extracts URL bar text via
  `findAccessibilityNodeInfosByViewId`; debounces with a 700ms idle
  timer; rate-limits via a 64-entry / 10-second LRU; dispatches into
  the shared `LinkAnalyzer`.
- `accessibility/OverlayManager.kt` — owns the WindowManager overlay.
  Uses `TYPE_APPLICATION_OVERLAY` on API 26+, `TYPE_PHONE` on older
  devices (minSdk is 24). One overlay at a time. Programmatic layout
  with the existing shield icon, Material 3-ish card, dim background,
  200ms fade-in / 150ms fade-out, dismiss-on-tap-outside.
- `accessibility/AccessibilityHelper.kt` — pure helpers for the four
  system checks (service enabled, overlay allowed, open settings).
- `accessibility/UrlSubmissionGate.kt` — pure-logic LRU + heuristic
  layer, extracted for testability.
- `bridge/AccessibilityChannel.kt` — `com.sentinel.app/accessibility`
  MethodChannel mirroring the helper.

### Kotlin (modified)
- `LinkGateActivity.kt` — wires the new channel.
- `AndroidManifest.xml` — declares the service, adds
  `SYSTEM_ALERT_WINDOW`.
- `res/xml/accessibility_service_config.xml` (new) — system-side
  `packageNames=` allow list.
- `res/values/strings.xml`, `res/values-it/strings.xml` — labels and
  overlay strings shown by the system Settings app and inside the
  AccessibilityService process.

### Dart (new)
- `services/accessibility_service.dart` — thin MethodChannel wrapper
  with graceful PlatformException handling.

### Dart (modified)
- `app/app.dart` — propagates the new service through the router.
- `features/onboarding/welcome_screen.dart` — adds page 4 (Sentinella
  nei browser) with an inline "Imposta accessibilità" CTA.
- `features/settings/settings_screen.dart` — adds a "Protezione
  avanzata" section with two rows (accessibility toggle, overlay
  permission) plus an expandable privacy-details disclosure.
- `features/dashboard/dashboard_screen.dart` — adds the two advisory
  chips shown only when VPN is on and the L3 grants are incomplete.

### l10n
- 27 new keys across `app_it.arb` and `app_en.arb`.
- 7 new strings in `res/values/strings.xml` and `res/values-it/strings.xml`.

### Tests (new)
- `test/accessibility_service_test.dart` — 7 cases covering the Dart
  wrapper, including PlatformException and null-reply degradation.
- `android/app/src/test/kotlin/com/sentinel/app/accessibility/UrlSubmissionGateTest.kt`
  — 9 cases covering the URL submission heuristic and LRU.
- `android/app/src/test/kotlin/com/sentinel/app/accessibility/AccessibilityHelperTest.kt`
  — 4 Robolectric cases covering ENABLED_ACCESSIBILITY_SERVICES
  parsing.

## Non-obvious design decisions

1. **LRU rate-limiter lives in the service**, not in
   `LinkAnalyzer`. The analyser is also used by the intent-flow path
   where re-checks within 10s ARE meaningful (the user may have
   intentionally re-tapped a link); pushing the LRU there would
   degrade the L1 UX.
2. **Single service-owned CoroutineScope** instead of per-event
   scopes. Per-event scopes leaked when the user typed fast: the
   debounce-cancellation path could leave the analyser running on a
   no-longer-relevant string. A single scope with a `SupervisorJob`
   and `cancel()` in `onUnbind` is both simpler and tighter on
   resources.
3. **Programmatic overlay layout** instead of XML inflation. An
   AccessibilityService runs with the *application* theme; inflating
   an XML layout that depends on a Material theme attribute would
   silently fail or pick wrong styles. Building the FrameLayout
   procedurally keeps the overlay independent of the host app's
   styling.
4. **"Procedi comunque" does NOT push a second overlay**. The
   accessibility-service surface cannot host an Activity-bound
   AlertDialog, so the confirmation step is collapsed to a single
   toast: the user dismisses the warning, the browser continues
   showing the dangerous URL. This is the explicit "user chose to
   proceed" semantic, captured succinctly without inventing a second
   overlay stack.
5. **GLOBAL_ACTION_BACK is posted with a 50ms delay** after the
   overlay detach to avoid the back action being eaten by the
   overlay's own window. Documented inline at the only place this
   sequence happens (`OverlayManager.triggerBack`).
6. **Defense-in-depth allow list**. The manifest `packageNames=`
   already filters at the system level, but we re-check in Kotlin
   because (a) it is essentially free, and (b) it documents the
   privacy contract at the entry point.

## Open issues / external dependencies (user actions required)

| Step | Required for | Where |
|------|--------------|-------|
| Enable "Sentinel — Sentinella nei browser" in Accessibility | The service to receive events at all | Settings > Accessibility |
| Grant "Visualizza sopra altre app" | The overlay (otherwise fallback Toast) | Settings > Apps > Sentinel > Display over other apps |
| (Optional) Add a new browser package | Support for browsers outside the curated list | Edit `SentinelAccessibilityService.watchedBrowsers` + `urlBarIds` + `accessibility_service_config.xml` |

## Manual test plan (OnePlus 8T, Android 14)

1. Install: `flutter build apk --debug` then `adb install -r build/app/outputs/flutter-apk/app-debug.apk`.
2. First launch -> onboarding shows 5 pages. Verify page 4 ("Sentinella nei browser") and its "Imposta accessibilità" CTA.
3. Settings > Accessibility > toggle Sentinel ON. Confirm the system dialog with both "Use Sentinel?" and "Allow service".
4. Open Sentinel > Impostazioni > Protezione avanzata. The "Sentinella nei browser" row should show "Attiva" in green.
5. Tap the second row ("Permesso di sovrapposizione"). Grant the permission. Return to Sentinel. The row now shows "Concesso" in green.
6. On the Dashboard, tap **Attiva protezione** to start the VPN. The advisory chips should NOT appear (we have both grants).
7. Toggle the overlay permission OFF in system settings, return to Sentinel. The dashboard should now show "Permesso sovrapposizione mancante" with a "Concedi" CTA.
8. Toggle the accessibility service OFF, return. The dashboard should show "Protezione avanzata non attiva" with an "Attiva" CTA.
9. Restore both grants.
10. Open Chrome. Type a URL known to be in the bundled blacklist (see `android/app/src/main/assets/blacklist/sample.txt`). After ~700ms idle a full-screen warning overlay should appear with the shield icon, "Attenzione", the typed URL, the reasons and two buttons.
11. Tap **Torna indietro**. The overlay fades out and Chrome navigates back to the previous tab (or empty tab if it's a fresh window).
12. Type the same URL again immediately. The overlay should NOT re-appear (LRU rate-limit suppresses duplicates within 10s). Type a *different* known-malicious URL — the new overlay appears.
13. Type a known-safe URL like `https://example.com`. No overlay should appear; the dashboard's "Link verificati" counter should tick up by 1.
14. Open WhatsApp (or another non-browser app), type a URL anywhere (e.g. in a chat input). The overlay must NOT appear: WhatsApp is not in the watched-browsers list.

## Privacy posture statement (suitable to paste into the README)

Sentinel, with the Sentinella nei browser turned on, observes only
the URL displayed in the address bar of a hand-picked set of
mainstream Android browsers (Chrome, Firefox, Edge, Brave, Samsung
Internet, Opera, DuckDuckGo, Ecosia, Vivaldi, Kiwi Browser). It does
not read the content of pages, messages, emails, passwords, or any
text from other apps. The package allow-list is enforced both by the
Android system (via the AccessibilityService XML configuration) and
by Sentinel's own code as a defense in depth: events from any
package outside the list are discarded before any text is read. URLs
are logged only in debug builds and truncated to 32 characters; in
release builds nothing is logged about URL contents.
