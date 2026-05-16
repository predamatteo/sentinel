# Sprint 1 — Summary

## What was built

### Native engine (Kotlin)
- `LinkGateActivity` — single Activity entry, handles MAIN/LAUNCHER and
  VIEW http/https intents, hosts the Flutter engine, exposes the incoming
  URL through the MethodChannel.
- `LinkAnalyzer` — fan-out / fan-in coroutine orchestrator with per-provider
  timeout (3 s default).
- `LocalBlacklistProvider` — reads `assets/blacklist/sample.txt`, caches
  the parsed set in memory under a `Mutex` so concurrent calls are safe,
  matches by exact host or `.suffix` boundary.
- `SafeBrowsingProvider` — Google Safe Browsing v4 lookup API client over
  `HttpURLConnection`, with graceful degradation when the API key is
  missing or the network call fails.
- `AnalysisChannel` — MethodChannel `com.sentinel.app/analysis` exposing
  `analyze`, `proceedToChrome`, `cancelNavigation`, `currentUrl`,
  `isDefaultBrowser`, `openDefaultBrowserSettings`.
- `ChromeLauncher` — `CustomTabsIntent` forwarding with Chrome channel
  selection, fallback to any installed browser, self-exclusion.
- `DefaultBrowserHelper` — `RoleManager` (API 29+) plus settings deep-link
  fallback for older devices.

### Flutter UI
- Material 3 themes (light + dark) seeded with deep indigo `#1A237E`.
- Three semantic colours: success `#1E8E3E`, warning `#F9AB00`,
  danger `#D93025`.
- Screens:
  - `AnalyzingScreen` — spinner, URL card, cancel button.
  - `VerdictScreen` — adapts to SAFE (green, 2 s auto-redirect to Chrome),
    SUSPICIOUS (amber, requires user choice) and MALICIOUS (red, guarded
    by confirm dialog before proceed).
  - `WelcomeScreen` — 3-page onboarding, ends on a "set as default
    browser" CTA backed by `RoleManager` or system settings.
  - `HomeScreen` — launcher view with protection status badge and a
    direct CTA to the default-browser settings.
- `AnalysisService` — typed Dart wrapper around the MethodChannel.
- `AnalysisResult` model with defensive parsing (missing fields fall back
  to safe defaults).

### Configuration
- AGP 8.11, Kotlin 2.2, Gradle 8.14
- `compileSdk 34`, `targetSdk 34`, `minSdk 24`
- Package name `com.sentinel.app`
- App label "Sentinel" (Italian + default)
- `INTERNET` permission (Safe Browsing API requires it)
- `<queries>` block listing browser packages and Custom Tabs services so
  Android 11+ package visibility lets us launch Chrome
- `local.properties` reads `SAFE_BROWSING_API_KEY` (optional) and exposes
  it via `BuildConfig`

### Localisation
- Italian (it-IT) is the source of truth (`lib/l10n/app_it.arb`).
- English (en) is the fallback.
- `flutter_localizations` + generated `AppLocalizations` class.
- Every visible string is localised. No hard-coded user text.

### Documentation
- `README.md` — bilingual (Italian user description + English dev setup).
- `docs/architecture.md` — 3-layer overview, Sprint 1 diagrams, design
  decisions, Sprint 2/3 plans.
- `docs/sprint-1-summary.md` — this file.

## Known limitations
- Release builds are debug-signed for sideload. Production signing is out
  of scope for Sprint 1.
- No persistent history of analysed URLs.
- No Custom Tabs warm-up service (cold launch only).
- Safe Browsing v4 is the **Lookup API**, not the Update API. The lookup
  variant is rate-limited by Google to ~10 000 queries/day per key and is
  simple to integrate; the Update API would let us cache hashes locally
  but adds significant complexity (delta updates, hash prefix matching).
  We will revisit this in Sprint 2 if we hit the rate limit.
- Local blacklist is bundled, not updatable. A remote-fetch mechanism is
  trivial to add but was not in this sprint's scope.
- The native side does not currently expose a way to **cancel** an
  in-flight analysis. The Flutter cancel button hides the UI and finishes
  the task, but the coroutines keep running to completion in the
  background. Acceptable for Sprint 1 (analyses are bounded by the 3 s
  per-provider timeout); will be fixed if it becomes user-visible.

## Manual test checklist

Prerequisites:
- Android device or emulator with API 24+
- ADB connected
- Build: `flutter build apk --debug`
- Install: `adb install -r build/app/outputs/flutter-apk/app-debug.apk`

Test 1 — first launch / onboarding
1. From the launcher tap Sentinel.
2. Onboarding should display 3 pages, Italian text, navigation via
   "Avanti" and "Salta".
3. On the last page, "Imposta ora" must open the system default-browser
   picker.
4. Choose Sentinel.
5. Press back to return to the app. The home screen should now show
   "Protezione attiva" with a green check icon.

Test 2 — SAFE verdict (whitelisted-looking URL)
1. In another app (Telegram, Messages, your own notes app, or via ADB:
   `adb shell am start -a android.intent.action.VIEW -d "https://www.google.com"`)
   open a link to `https://www.google.com`.
2. Sentinel must intercept it, show the analyzing screen briefly, then
   the green verdict screen.
3. After a 2 second countdown the URL must auto-open in Chrome.
4. Pressing "Annulla" before the countdown ends must close Sentinel
   without opening Chrome.

Test 3 — MALICIOUS verdict (blacklisted URL)
1. Open any URL on a host in the bundled blacklist, for example
   `https://paypa1-secure.com/login` (this host is in
   `assets/blacklist/sample.txt`).
   Via ADB: `adb shell am start -a android.intent.action.VIEW -d "https://paypa1-secure.com/login"`
2. The red verdict screen must show:
   - Title "Attenzione"
   - The URL
   - At least one reason mentioning "Dominio presente in blacklist locale"
3. "Torna indietro" closes Sentinel without opening Chrome.
4. "Procedi comunque" pops a confirmation dialog. "No, annulla" dismisses
   the dialog; "Sì, apri lo stesso" forwards to Chrome.

Test 4 — Safe Browsing not configured
1. With no SAFE_BROWSING_API_KEY in local.properties, open a clean URL
   that is not on the local blacklist (e.g. `https://www.example.com`).
2. The verdict should be SUSPICIOUS (amber) with the reason
   "Safe Browsing API non configurata".
3. Add a real key, rebuild, repeat — the verdict should now be SAFE.

Test 5 — Multiple links in succession
1. Open a link, let the verdict appear.
2. Without dismissing, open another link from the same source app.
3. Sentinel should show the analyzing screen for the new URL (not stack
   a second activity instance).

Test 6 — No browser fallback
1. On a device or emulator that does not have Chrome installed, open a
   safe link.
2. The "Apri ora" action should still work, falling back to any other
   installed browser. If no browser is installed at all, a localised
   snackbar must say "Nessun browser disponibile per aprire il link".
