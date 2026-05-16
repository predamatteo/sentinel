# Sentinel

---

## Italiano

**Sentinel** è una app Android che ti protegge dai link pericolosi e dalle
pubblicità invasive. Quando tocchi un link in WhatsApp, SMS, Gmail,
Telegram o in qualsiasi altra app, Sentinel lo analizza prima di aprirlo.
Se è sicuro, lo apri come al solito in Chrome. Se è sospetto, ti avvisa e
ti permette di tornare indietro.

Con lo **Sprint 2** Sentinel può anche attivare un filtro DNS che blocca
pubblicità e domini sospetti per **tutte** le app del telefono — senza
decifrare il traffico HTTPS e senza inviare nulla all'esterno.

Con lo **Sprint 3** Sentinel può inoltre controllare i siti che digiti
direttamente nella barra degli indirizzi dei browser supportati
(Chrome, Firefox, Edge, Brave, Samsung Internet, Opera, DuckDuckGo,
Ecosia, Vivaldi, Kiwi). È la cosiddetta **Sentinella nei browser**, una
protezione opzionale basata sul servizio di accessibilità di Android.

### Cosa fa, in breve
1. Imposti Sentinel come browser predefinito.
2. Quando tocchi un link, Android lo passa a Sentinel.
3. Sentinel controlla il link con Google Safe Browsing e con una lista
   locale di siti già noti.
4. Se il link è sicuro, lo apre in Chrome (tramite Chrome Custom Tabs).
5. Se è sospetto o pericoloso, vedi una schermata rossa con i motivi e
   puoi decidere se tornare indietro o procedere comunque.
6. Opzionalmente puoi attivare il **filtro DNS Sentinel**: una piccola
   VPN locale che blocca i domini pubblicitari e malevoli per ogni app.

### Cosa NON fa
- Non è un browser. Non ha cronologia, schede, segnalibri. Non visualizza
  i siti: si limita ad analizzare il link e a passarlo a Chrome.
- Non legge il contenuto delle pagine HTTPS (nessun MITM).
- Non invia il tuo traffico all'esterno: la VPN è interamente locale.
- Non richiede root.
- Non è disponibile sul Play Store: si installa via sideload.

### Sentinella nei browser (Sprint 3)

La Sentinella nei browser è la terza linea di difesa. Quando è attiva,
Sentinel osserva **solo** l'URL nella barra degli indirizzi dei browser
supportati e lo analizza con la stessa pipeline usata per i link toccati
nelle altre app. Se il sito è pericoloso, sopra il browser appare un
avviso a tutto schermo con la possibilità di tornare indietro o
procedere comunque.

**Cosa vede Sentinel quando la Sentinella è attiva**
- L'URL scritto nella barra degli indirizzi di: Chrome, Firefox, Edge,
  Brave, Samsung Internet, Opera, DuckDuckGo, Ecosia, Vivaldi e Kiwi
  Browser.

**Cosa NON vede mai Sentinel — anche con la Sentinella attiva**
- Il contenuto delle pagine web (testo, immagini, password digitate).
- Messaggi, email, SMS, notifiche o testo di qualsiasi altra app.
- L'URL di qualsiasi browser che non sia nella lista qui sopra.

**Come attivarla**
1. Apri Sentinel.
2. Vai in **Impostazioni → Protezione avanzata**.
3. Tocca **Sentinella nei browser**: si apre l'elenco di accessibilità
   di Android. Trova "Sentinel" e attivalo.
4. Torna in Sentinel e concedi il **Permesso di sovrapposizione**
   (serve per mostrare l'avviso sopra il browser).
5. Torna in Sentinel: nella dashboard non comparirà più l'avviso
   "Protezione avanzata non attiva".

L'app funziona perfettamente anche senza la Sentinella (basta L1 + L2).
È una protezione opzionale, attivabile e disattivabile in qualsiasi
momento.

---

## English (developer setup)

Sentinel is a Flutter + Kotlin Android app that gates outbound `http(s)`
links through an analysis pipeline (Layer 1) and runs an optional local
DNS-filtering VPN (Layer 2). HTTPS payloads are never decrypted.

### Architecture
Three-layer strategy. **Sprint 1 implemented Layer 1; Sprint 2 implements
Layer 2.**

| Layer | Mechanism | Status |
|-------|-----------|--------|
| L1    | Default-browser intent gating | Done (Sprint 1) |
| L2    | `VpnService` DNS filtering    | Done (Sprint 2) |
| L3    | `AccessibilityService` URL bar | Done (Sprint 3) |

See `docs/architecture.md` for the full diagram, the Layer 2 sequence
diagrams, and design notes.

### Prerequisites
- Flutter `>= 3.41` (tested on 3.41.4)
- Dart `>= 3.11`
- Android SDK: `compileSdk 36`, `targetSdk 34`, `minSdk 24`
- JDK 17 for builds; JDK 21+ on the host is fine because Gradle 8.14
  auto-toolchains
- Android Gradle Plugin `8.11`, Kotlin `2.2`
- A physical device or emulator running **Android 7.0 (API 24)** or newer
- Firebase project (already configured for this checkout: project id
  `sentinel-4052a`)

### Configure the Safe Browsing API key (optional)
The Safe Browsing provider is optional. Without a key it returns a
`SUSPICIOUS` outcome with the reason `Safe Browsing API non configurata`,
so the rest of the pipeline still works (the local blacklist still runs).

To enable it:
1. Create a project on the Google Cloud Console.
2. Enable the **Safe Browsing API** (v4 lookup) on that project.
3. Generate an API key.
4. Add the following line to `android/local.properties` (do not commit):
   ```properties
   SAFE_BROWSING_API_KEY=your_actual_key_here
   ```
5. Rebuild.

After Sprint 2 the request also sends `X-Android-Package` and
`X-Android-Cert` (SHA-1 of the signing certificate). Once everything
works locally, switch the key restriction in Google Cloud Console to
**Android apps** and add the app's package + signing SHA-1 there so the
key becomes useless to anyone who extracts it from the APK.

### Firebase + App Check (Sprint 2)
`google-services.json` is already committed under `android/app/`. The
Firebase Console project is `sentinel-4052a` (App Check is registered
with Play Integrity).

**Debug builds use the App Check debug provider** so developers can
exercise Remote Config without Play Integrity attestations. The first
time you run a debug build, look in logcat for a line like:

```
D FirebaseAppCheck: Enter this debug secret into the allow list in
   the Firebase Console for your project: ABCDEF12-3456-...
```

Copy that token and register it in Firebase Console:
**App Check → Apps → Sentinel (Android) → Manage debug tokens → Add**.
After the token is registered, Remote Config fetches succeed in debug.

### Sprint 2 — enabling DNS protection at runtime
1. Build and install: `flutter build apk --debug` then
   `adb install -r build/app/outputs/flutter-apk/app-debug.apk`.
2. Launch Sentinel from the home screen.
3. Tap **Attiva protezione** on the dashboard.
4. Android shows a system "Connection request" dialog explaining that
   Sentinel wants to set up a VPN — accept it.
5. A persistent notification appears: "Sentinel — Protezione attiva ·
   N domini bloccati oggi". The status card on the dashboard turns
   green and the stats start ticking.

To disable: tap **Disattiva** on the dashboard, or pull down the
notification shade and tap the VPN system entry → Disconnect.

### Sprint 2 — refreshing blocklists
The bundled `ads.txt` and `malware.txt` ship inside the APK. URLs to
extended remote lists are read from Firebase Remote Config keys:

- `blocklist_url_ads`
- `blocklist_url_phishing`

A "Aggiorna liste ora" button in **Impostazioni** forces a fresh fetch
of Remote Config and downloads the URLs into the app's private cache.
Subsequent VPN start-ups load the cached lists automatically.

### Sprint 2 — switching the Safe Browsing key restriction
Once you have a working `SAFE_BROWSING_API_KEY` and a successful build:

1. Open Google Cloud Console → APIs & Services → Credentials.
2. Open the API key used by Sentinel.
3. Under **Application restrictions** pick **Android apps**.
4. Add the package name `com.sentinel.app` and the signing SHA-1.
   In Sprint 2 the build is debug-signed, so use the SHA-1 of the
   debug keystore:
   `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
5. Save. Sentinel already sends `X-Android-Package` and
   `X-Android-Cert`, so requests continue to work.

### Build
```bash
flutter pub get
flutter gen-l10n
flutter build apk --debug      # debug-signed APK at build/app/outputs/flutter-apk/
flutter build apk --release    # release build (debug-signed unless key.properties is present)
```

### Sideload to a device
```bash
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

### Release signing (Sprint Quality)
The release build automatically picks up a real keystore when
`android/key.properties` exists. Without it, `flutter build apk --release`
falls back to debug signing so contributors without the keystore can
still produce sideloadable APKs.

1. Generate a keystore once (keep it outside the repository):
   ```bash
   keytool -genkey -v \
     -keystore sentinel-release.jks \
     -keyalg RSA -keysize 2048 \
     -validity 10000 \
     -alias sentinel
   ```
2. Copy `android/key.properties.template` to `android/key.properties`
   (already gitignored) and fill the four fields.
3. Build:
   ```bash
   flutter build apk --release
   ```
4. After the first release build, register the new release SHA-1 in:
   - **Firebase Console → Project Settings → Your apps → Add fingerprint**
     (so App Check Play Integrity attestations work for release builds).
   - **Google Cloud Console → Credentials → Safe Browsing API key →
     Application restrictions → Android apps** (so the same Safe Browsing
     key keeps working in release).

### Project layout (Sprint 2 additions in **bold**)
```
android/app/src/main/kotlin/com/sentinel/app/
  LinkGateActivity.kt              # Single Activity, wires all channels
  analysis/                        # Sprint 1 link-analysis engine
  bridge/
    AnalysisChannel.kt
    ChromeLauncher.kt
    DefaultBrowserHelper.kt
    VpnChannel.kt
    AccessibilityChannel.kt        # Sprint 3: com.sentinel.app/accessibility
  vpn/                             # Layer 2 implementation
    SentinelVpnService.kt
    VpnController.kt
    BlocklistRepository.kt
    DnsPacketParser.kt
    IpPacketParser.kt
    UpstreamDnsConfig.kt
    VpnStats.kt
  accessibility/                   # Sprint 3: Layer 3 implementation
    SentinelAccessibilityService.kt
    AccessibilityHelper.kt
    OverlayManager.kt
    UrlSubmissionGate.kt

android/app/src/main/res/xml/
  accessibility_service_config.xml # Sprint 3: system-side allow list

android/app/src/main/assets/
  blacklist/sample.txt             # Sprint 1 host blacklist
  blocklist/ads.txt                # NEW: bundled ad/tracker baseline
  blocklist/malware.txt            # NEW: bundled malware/phishing baseline

android/app/src/test/kotlin/com/sentinel/app/vpn/
  DnsPacketParserTest.kt           # NEW: parses + synthesises DNS bytes
  BlocklistMatchTest.kt            # NEW: parent-label match + whitelist

lib/
  main.dart                        # NOW initialises Firebase + App Check
  app/app.dart                     # Updated router (Dashboard landing)
  app/theme.dart                   # + SentinelStatusColor helpers
  features/analysis/               # Sprint 1
  features/dashboard/              # NEW: dashboard_screen.dart
  features/settings/               # NEW: settings_screen.dart
  features/onboarding/             # + extra DNS page
  services/
    analysis_service.dart          # Sprint 1
    analysis_models.dart           # Sprint 1
    vpn_service.dart               # NEW: MethodChannel wrapper for VPN
    whitelist_service.dart         # NEW: SharedPreferences-backed list
    remote_config_service.dart     # NEW: Firebase Remote Config
  l10n/                            # ARB sources (Sprint 2 strings added)

docs/
  architecture.md                  # Updated for Layer 2
  sprint-1-summary.md
  sprint-2-summary.md              # NEW
```

### Localisation
All user-facing strings live in `lib/l10n/app_it.arb` (primary) and
`lib/l10n/app_en.arb` (fallback). Code, comments and identifiers are in
English. Regenerate with `flutter gen-l10n` after editing.

### Tests
```bash
# Dart side
flutter test

# Android JVM side
cd android && ./gradlew :app:testDebugUnitTest
```

### Sprint 3 — AccessibilityService (Layer 3)

Sprint 3 adds `SentinelAccessibilityService`, which reads the URL bar
of a curated set of mainstream browsers and runs the same analysis
pipeline used by Layer 1 on whatever the user just typed.

#### Adding a new browser
1. Find the browser's package name (`adb shell pm list packages | grep
   <name>`).
2. Discover the view id of its URL bar:
   ```bash
   adb shell uiautomator dump /sdcard/dump.xml
   adb pull /sdcard/dump.xml
   ```
   Open `dump.xml`, find the focused EditText / UrlBar; the
   `resource-id` attribute is the id you need.
3. Append the package to:
   - `SentinelAccessibilityService.watchedBrowsers`
   - `SentinelAccessibilityService.urlBarIds`
   - `android/app/src/main/res/xml/accessibility_service_config.xml`
     (`packageNames=...` attribute)
4. Bump no version: changes are config-only. Confirm the manifest
   parses (`flutter build apk --debug`).

#### Debugging the service
- Enable verbose logging in a debug build:
  `adb logcat -v time SentinelAxs:D *:S`
- Force-attach the service after install:
  `adb shell settings put secure enabled_accessibility_services
  com.sentinel.app/com.sentinel.app.accessibility.SentinelAccessibilityService`
  (the user will still need to acknowledge the system dialog on
  Android 12+).
- Test the heuristic without a network: type a URL known to be in
  `android/app/src/main/assets/blacklist/sample.txt` and verify the
  overlay appears.

#### Privacy guarantees enforced in code
- `res/xml/accessibility_service_config.xml#packageNames` is the
  system-enforced allow list. Add a package here and only here to
  observe it.
- `SentinelAccessibilityService.watchedBrowsers` is the defense-in-
  depth Kotlin check on the very first line of
  `onAccessibilityEvent`.
- URLs are logged only in `BuildConfig.DEBUG` builds and truncated
  to 32 characters.

### Limitations and future work
- DNS upstream is plain UDP. DoT/DoH support is deferred.
- No IPv6 path: the VPN advertises only an IPv4 prefix.
- Accessibility URL extraction relies on hand-curated view ids; new
  browser builds can change them without warning. The fallback walks
  the active window tree but is best-effort.
