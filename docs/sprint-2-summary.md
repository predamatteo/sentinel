# Sprint 2 — Summary

## What was built

### Firebase integration (Flutter side)
- `firebase_core`, `firebase_app_check`, `firebase_remote_config` added
  to `pubspec.yaml` with versions pinned for Flutter 3.41.
- `lib/main.dart` initialises Firebase, activates App Check
  (`AndroidProvider.debug` in `kDebugMode`, `playIntegrity` otherwise),
  then boots `RemoteConfigService`.
- `RemoteConfigService` (`lib/services/remote_config_service.dart`):
  singleton wrapping `FirebaseRemoteConfig`, sets defaults so the app
  works offline, fetches in the background, exposes `getBlocklistConfig`,
  `ensureFreshOnce`, and `forceRefresh`. Defaults match the project spec
  (Cloudflare upstream, empty remote URLs).

### Safe Browsing hardening (Kotlin side)
- `SafeBrowsingProvider` now takes a `Context` and sends
  `X-Android-Package` + `X-Android-Cert` headers expected by Google when
  the API key is restricted to "Android apps".
- The SHA-1 of the first signing certificate is read once via
  `PackageManager.GET_SIGNING_CERTIFICATES` (API 28+) or
  `GET_SIGNATURES` (older) and cached for the process lifetime.

### Layer 2 — DNS-only VpnService (Kotlin side)
- `SentinelVpnService` extends `VpnService`, claims `0.0.0.0/0` with a
  sinkhole DNS server inside the tun (`10.0.0.1`), runs in a foreground
  service with a sticky LOW-priority notification.
- The packet read loop:
  1. parses IPv4, drops non-IPv4 back into the tun untouched,
  2. for UDP/53 parses the DNS query, checks the blocklist (with
     user whitelist precedence), synthesises an `NXDOMAIN` reply when
     matched, otherwise forwards the original payload to an upstream
     UDP/53 resolver via a `protect()`ed `DatagramSocket`,
  3. writes back to the tun fd.
- `DnsPacketParser`: hand-rolled DNS wire parser. Handles label
  compression (RFC 1035 4.1.4 pointer with cycle guard); builds an
  NXDOMAIN response that echoes the question section verbatim so EDNS0
  OPT records (if any) survive.
- `IpPacketParser`: minimal IPv4 + UDP parser and reply builder. The
  reply builder swaps source/destination, fills both checksums (IP
  header and full UDP pseudo-header).
- `BlocklistRepository`: in-memory union of bundled assets
  (`ads.txt`, `malware.txt`) and locally cached remote downloads. Hot
  path uses an `AtomicReference<Set<String>>` for lock-free reads.
  Parent-label matching: `evil.com` in the list blocks `ads.evil.com`.
  Per-user whitelist always wins.
- `UpstreamDnsConfig`: process-wide `AtomicReference<Snapshot>` so the
  read loop sees changes from the Flutter side immediately.
- `VpnStats`: thread-safe counters for queries/blocks/forwards/errors
  and a bounded recent-blocks deque (100 entries). In-memory only.

### Platform channel + bridge
- New `bridge/VpnChannel.kt` exposes `com.sentinel.app/vpn` with:
  `requestStart`, `confirmStart`, `stop`, `isRunning`, `getStats`,
  `setWhitelist`, `refreshRemoteLists`, `setUpstream`. The Activity's
  `onActivityResult` forwards the VPN consent code through the channel.

### Flutter UI
- `DashboardScreen`: status card with toggle, "today" stats
  (threats / forwarded queries / links checked), recent blocks list,
  default-browser CTA when not yet default.
- `SettingsScreen`: protection switch, DNS upstream picker (Cloudflare,
  Quad9, Google, Custom), whitelist add/remove, force-refresh of
  Remote Config, About section.
- Onboarding extended with a 4th page that explains DNS filtering.
- `SentinelStatusColor` semantic helpers added to the existing theme.
- `VpnService` (Dart): typed wrapper over the platform channel, parses
  the stats payload defensively, exposes a `consentResults` stream so
  the UI reacts when the system VPN dialog dismisses.
- `WhitelistService`: SharedPreferences-backed list with validation.

### Localisation
Every new user-facing string lives in `app_it.arb` (primary) and
`app_en.arb` (fallback). Added 40+ keys covering dashboard, settings,
whitelist dialogs, VPN consent and runtime errors.

### Tests
- Dart (`flutter test`):
  - existing `widget_test.dart` still passes
  - `whitelist_service_test.dart` — validator accepts/rejects domains,
    case/whitespace normalisation
  - `vpn_stats_test.dart` — defensive parsing of the platform-channel
    map (full payload, missing fields, string values for counters)
- Android JVM (`./gradlew :app:testDebugUnitTest`):
  - `DnsPacketParserTest` — parses a single-question query, rejects
    response/truncated buffers, validates the NXDOMAIN response bytes
  - `BlocklistMatchTest` — exact match, subdomain match, whitelist
    precedence, blank input

### Documentation
- README updated with Sprint 2 sections (Italian user description,
  English developer setup, App Check debug-token registration,
  Safe Browsing key restriction switch, VPN runtime walkthrough).
- `docs/architecture.md` updated: Layer 2 row marked done, DNS flow
  diagram added.

### Manifest + Gradle
- AndroidManifest: `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
  `ACCESS_NETWORK_STATE`. `SentinelVpnService` declared with
  `android:permission="android.permission.BIND_VPN_SERVICE"`,
  `foregroundServiceType="specialUse"`, and the special-use property
  describing "DNS-level content filtering for user security and
  privacy".
- `compileSdk` bumped from 34 to 36 (required by
  `shared_preferences_android`). `targetSdk` stays at 34.

## Non-obvious design decisions

### DNS-only tunnel, not full proxy
The VpnService advertises `0.0.0.0/0` so the kernel routes every IPv4
packet through us, but anything that is not UDP/53 is written straight
back to the tun fd untouched. Avoids the need to translate TCP streams
through user-space and keeps the read loop predictable.

### Sinkhole DNS inside the tun
`addDnsServer("10.0.0.1")` makes Android use that address for system
DNS. Apps that bypass system DNS still get their UDP/53 captured by the
catch-all route, but on devices where the system resolver is the only
client this is the fastest path.

### `protect()`ed upstream socket
The Datagram socket that forwards non-blocked queries to upstream
Cloudflare is `protect()`ed so its packets bypass our own tunnel —
otherwise we would loop forever. The destination IP (1.1.1.1) is also
covered by the catch-all route, so without `protect()` the OS would
hand us our own outbound packet.

### Plain UDP upstream, no DoT/DoH
Sprint 2 ships plain UDP/53 upstream. The sample/test loop and the
parsers are already structured to allow swapping the transport later;
DoT/DoH would require TLS state per query (DoT) or a streaming HTTP/2
client (DoH).

### App Check debug provider in debug builds
Hard-coding `AndroidProvider.playIntegrity` in debug breaks Remote
Config until the developer registers the device with a real attestation,
which costs time during day-to-day iteration. The debug provider keeps
the boot path identical at the code level and shifts the manual step
(register the debug token in Firebase Console) to first-launch.

### App Check init must follow Firebase.initializeApp
The activate() call requires Firebase to be alive. The boot path is
strictly: `Firebase.initializeApp` → `FirebaseAppCheck.activate` →
`RemoteConfigService.initialize`. Any error during this chain is logged
and swallowed so the app still boots in airplane mode (Layer 1 keeps
working without Firebase).

### Blocklist `matches` extracted as a static helper
The hot lookup function was extracted into a `companion object` so the
JVM tests can verify it without faking an Android Context. The `isBlocked`
instance method is now a one-line delegate to the static helper that
reads the live AtomicReferences.

### Custom IP + UDP checksums computed by hand
Some Android kernels drop UDP-over-VPN packets that arrive with a zero
UDP checksum. We compute the full pseudo-header checksum even though
the IPv4 spec allows zero, to maximise compatibility across devices.

## Known limitations
- DNS upstream is plain UDP. DoT/DoH is deferred to a later sprint.
- Stats are in-memory only and reset when the process dies.
- IPv6 is not routed through the tun. On devices with an IPv6-only
  upstream the kernel keeps using the regular IPv6 path (which is fine,
  no leaks for IPv4) but our blocking does not apply.
- TCP/53 fallback is not implemented. Real DNS clients fall back to TCP
  when a UDP response is truncated; with our small bundled list this is
  almost never relevant.
- Custom upstream DNS in settings does not validate IP format beyond
  whitespace stripping. Invalid addresses get treated as "unreachable"
  by the upstream socket and surface as `recordError`.
- The "Ads blocked" stat tile is mapped to total forwarded queries
  because the current blocklist union does not carry per-source labels.
  Splitting ads vs threats at the wire level needs a tagged map and is
  scheduled for a later sprint.

## Manual test plan

### Pre-requisites
- Android device (API 24+), ADB connected
- Build: `flutter build apk --debug`
- Install: `adb install -r build/app/outputs/flutter-apk/app-debug.apk`

### Test 1 — Sprint 1 regression
1. From the home launcher tap Sentinel — onboarding shows 4 pages now.
2. "Salta" jumps to the last page; "Avanti" walks them in order.
3. Set Sentinel as the default browser.
4. From another app open a link to `https://www.google.com`. The
   analyzing screen flashes, the green verdict screen appears, the URL
   opens in Chrome after the 2 s countdown.
5. Open a link to `https://paypa1-secure.com/login`. The red verdict
   screen appears with the reason mentioning the local blacklist.

### Test 2 — Enable the VPN
1. Open Sentinel from the launcher (or the verdict screen).
2. On the dashboard tap "Attiva protezione".
3. A system dialog appears: "Connection request — Sentinel wants to set
   up a VPN connection that allows it to monitor network traffic." Tap
   OK.
4. The persistent notification "Sentinel — Protezione attiva ·
   0 domini bloccati oggi" appears.
5. The dashboard status card turns green ("Protezione DNS attiva").

### Test 3 — Verify ad blocking
1. With the VPN on, open Chrome and load any page known to embed
   Google ads (any italian newspaper homepage, for example).
2. Watch the dashboard or pull the notification down — the blocked
   counter should grow within seconds.
3. Tap "Impostazioni" → confirm the recent-blocks list shows entries
   like `pagead2.googlesyndication.com` or `doubleclick.net`.

### Test 4 — Verify a known malware domain is NXDOMAIN'd
1. With the VPN on, run:
   `adb shell dumpsys dns`
   then:
   `adb shell ping -c 1 paypal-verify-account.com`
2. The ping must fail with "unknown host" (NXDOMAIN). Without the VPN
   the same name resolves normally (it is a public domain).

### Test 5 — Whitelist precedence
1. Open Sentinel → Impostazioni → "Aggiungi dominio".
2. Type `doubleclick.net` and confirm.
3. Reload a page that previously triggered a block. The blocked-counter
   stops incrementing for that host and the recent list no longer
   surfaces it.
4. Remove `doubleclick.net` from the whitelist → blocks resume.

### Test 6 — Switch upstream DNS to Quad9
1. Settings → DNS upstream → tap "Quad9 (9.9.9.9)".
2. Open Chrome to any non-blocked site. Resolution should still work
   (Quad9 returns answers for normal queries).
3. The change is process-wide: the next forwarded query goes to 9.9.9.9.

### Test 7 — Custom DNS
1. Settings → DNS upstream → "Personalizzato".
2. Enter `1.0.0.1` and `8.8.8.8` (mixed providers, intentional).
3. Save. Refresh a tab in Chrome — resolution still works.

### Test 8 — Stop and re-start the VPN
1. Tap "Disattiva" on the dashboard. Notification disappears, status
   card turns amber.
2. Tap "Attiva protezione" again. No consent dialog this time (Android
   remembers the grant). The VPN comes up immediately.

### Test 9 — Force refresh blocklists (requires Remote Config keys)
1. In Firebase Console, set `blocklist_url_ads` to a publicly-reachable
   hosts list URL (any AdGuard host list works).
2. In Sentinel → Impostazioni → "Aggiorna liste ora".
3. The snackbar shows "Liste aggiornate". Re-load a page; blocks should
   now include domains from the freshly cached list.

### Test 10 — Verify App Check (debug token flow)
1. With `kDebugMode = true` and the debug provider active, run the app
   and watch logcat for the "Enter this debug secret" line.
2. Register that token in Firebase Console → App Check → Manage debug
   tokens.
3. Force-refresh the lists (Test 9); the request must succeed (no 401
   from Remote Config).

### Test 11 — Safe Browsing key restriction
1. Set the Google Cloud key restriction to "Android apps", add the
   package and the debug-keystore SHA-1.
2. Build, install, open a link to any URL.
3. Safe Browsing requests must still succeed (they now carry the
   X-Android headers). Without the headers Google rejects with 403.

## What's intentionally not done
- Layer 3 (AccessibilityService URL-bar monitoring) — Sprint 3.
- Persistent statistics history. In-memory only this sprint.
- DoT/DoH upstream. Plain UDP/53 only.
- IPv6 packet inspection.
- TCP/53 fallback for truncated UDP responses.
- Production signing config. Release APKs remain debug-signed.
- Per-source labels for the blocklist (ads vs threats split in stats).
- A custom monochrome notification icon. The notification uses a
  system stock symbol; a proper adaptive icon comes with the UI polish
  pass scheduled with Sprint 3.
- A package_info_plus integration for the about screen — the version
  is hard-coded against the pubspec for now.
