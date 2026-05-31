# Sentinel — Architecture

## Three-layer strategy

Sentinel is designed as three independent protection layers. Each layer
covers a different attack surface and can be enabled or disabled without
breaking the others.

| Layer | Mechanism | Scope of protection | Sprint | Status |
|-------|-----------|---------------------|--------|--------|
| **L1** | Default browser, intent gating | The URL the user explicitly taps | 1 | Done |
| **L2** | `VpnService` based DNS filtering | All outbound DNS lookups (ads, trackers, sub-resources) | 2 | Done |
| **L3** | `AccessibilityService` URL bar monitoring | URLs typed or pasted directly into other browsers | 3 | Done |

Layers compose: an installation with L1+L2 covers tap-through and
sub-resource traffic; L1+L2+L3 covers in-browser typing too.

## Layer 1 — Sprint 1 implementation

### High-level flow

```
+----------------+    Intent(VIEW, http/https)    +----------------------+
| Source app     | -----------------------------> | LinkGateActivity     |
| (WhatsApp,     |                                | (singleTask)         |
|  SMS, Gmail,   |                                +----------+-----------+
|  Telegram, ...)|                                           |
+----------------+                                           | extracts URL
                                                             v
                                                +----------------------+
                                                | AnalysisChannel      |
                                                | (MethodChannel)      |
                                                +----------+-----------+
                                                           |
                                              analyze(url) |
                                                           v
                                                +----------------------+
                                                | LinkAnalyzer         |
                                                | (Kotlin coroutines)  |
                                                +----+-------------+---+
                                                     |             |
                                       parallel  +---+----+   +----+--------+
                                                 | Local  |   | SafeBrowsing|
                                                 | Black  |   | Provider    |
                                                 | list   |   | (v4 lookup) |
                                                 +---+----+   +----+--------+
                                                     \             /
                                                      \           /
                                                       v         v
                                                +----------------------+
                                                | AnalysisResult       |
                                                | (worst-verdict wins) |
                                                +----------+-----------+
                                                           |
                                                           v
                                                +----------------------+
                                                | Flutter UI           |
                                                | (Analyzing / Safe /  |
                                                |  Suspicious /        |
                                                |  Malicious)          |
                                                +----------+-----------+
                                                           |
                                          user confirms or auto-forward
                                                           v
                                                +----------------------+
                                                | ChromeLauncher       |
                                                | (CustomTabsIntent)   |
                                                +----------+-----------+
                                                           |
                                                           v
                                                +----------------------+
                                                | Chrome (or fallback) |
                                                +----------------------+
```

### Sequence (happy path, SAFE verdict)

```
User -> WhatsApp: taps link
WhatsApp -> Android: Intent(VIEW, https://example.com)
Android -> LinkGateActivity: dispatch (we are default browser)
LinkGateActivity -> AnalysisChannel: stores URL, configures engine
Flutter -> AnalysisService: currentUrl()
Flutter -> AnalyzingScreen: navigate
AnalyzingScreen -> AnalysisService: analyze(url)
AnalysisService -> LinkAnalyzer: analyze (coroutine)
LinkAnalyzer -> LocalBlacklistProvider: check (Dispatchers.IO)
LinkAnalyzer -> SafeBrowsingProvider: check (Dispatchers.IO)
LinkAnalyzer -> AnalyzingScreen: AnalysisResult(verdict=SAFE)
AnalyzingScreen -> VerdictScreen(green): pushReplacement
VerdictScreen -> countdown 2s -> proceedToChrome()
ChromeLauncher -> CustomTabsIntent: launch URL with package=Chrome
LinkGateActivity -> finishAndRemoveTask()
```

### Sequence (MALICIOUS verdict)

```
... (same up to LinkAnalyzer)
LocalBlacklistProvider: MALICIOUS (host matches blacklist)
SafeBrowsingProvider: SAFE
LinkAnalyzer: worst-verdict wins -> MALICIOUS
VerdictScreen(red): renders reasons + sources
User -> Torna indietro: cancelNavigation -> finishAndRemoveTask
  -- OR --
User -> Procedi comunque: confirm dialog -> proceedToChrome
```

## Key design decisions

### Single Activity, singleTask launch mode
LinkGateActivity is the only entry point. `singleTask` prevents multiple
instances stacking if the user taps several links in quick succession.
Subsequent links arrive via `onNewIntent`, which forwards them to Flutter
through an `onNewUrl` call on the same MethodChannel.

### Worst-verdict-wins aggregation
`LinkAnalyzer` runs every provider in parallel and merges their outcomes
by taking the highest-severity verdict. A `SUSPICIOUS` outcome from any
provider is enough to surface a warning, but only `MALICIOUS` triggers
the red screen.

### Per-provider timeout
Each provider gets `withTimeoutOrNull(3000ms)`. Network providers (Safe
Browsing) hit Google's API with their own connect/read timeouts at
2.5 seconds, so the outer timeout is a safety net. On timeout the provider
contributes a `SUSPICIOUS` outcome with the reason "Verifica online non
completata" rather than failing silently.

### Optional Safe Browsing key
The Safe Browsing provider returns a `SUSPICIOUS` outcome with reason
"Safe Browsing API non configurata" when `BuildConfig.SAFE_BROWSING_API_KEY`
is empty. This keeps the build green even without the secret and lets the
local blacklist provider work in isolation.

### CustomTabsIntent over WebView
We never render the page ourselves. `CustomTabsIntent` keeps the user
inside Chrome's process, gets all of Chrome's hardening (Safe Browsing,
sandboxing) and avoids storing any browsing state in Sentinel.

### Loop prevention
`ChromeLauncher` explicitly excludes our own package when resolving
fallback browsers, and prefers any Chrome channel via Custom Tabs. The
launch intent carries `FLAG_ACTIVITY_NEW_TASK` so we never reuse the
Sentinel task.

## Layer 2 — Sprint 2 implementation

### Component overview

```
+---------------------+        +-----------------------------+
| Flutter UI          |        | DnsPacketParser             |
| (Dashboard/Settings)|        | IpPacketParser              |
+----------+----------+        +-------------+---------------+
           |                                 ^
           | platform channel                | parses + builds packets
           v                                 |
+----------+----------+   reads/writes  +----+---------------+
| VpnChannel          | <-------------- | SentinelVpnService |
| VpnController       |                 | (foreground)       |
+----------+----------+                 +----+---------------+
           |                                 |
           | applies                         | queries
           v                                 v
+---------------------+              +---------------------+
| UpstreamDnsConfig   |              | BlocklistRepository |
| (atomic ref)        |              | bundled + remote +  |
+---------------------+              | user whitelist      |
                                     +---------------------+
```

### Sequence (DNS query, blocked)

```
App (e.g. Chrome) -> system resolver: A? ads.evil.com
system resolver -> kernel: UDP/53 packet
kernel -> SentinelVpnService.read(): IPv4 + UDP datagram
SentinelVpnService -> IpPacketParser.parseIpv4: header
SentinelVpnService -> IpPacketParser.parseUdp: dest port 53
SentinelVpnService -> DnsPacketParser.parseQuery: "ads.evil.com"
SentinelVpnService -> BlocklistRepository.isBlocked: true
SentinelVpnService -> DnsPacketParser.buildNxdomainResponse
SentinelVpnService -> IpPacketParser.buildIpv4UdpReply
SentinelVpnService -> kernel: write NXDOMAIN packet
kernel -> system resolver: NXDOMAIN
system resolver -> App: ENOTFOUND
SentinelVpnService -> VpnStats.recordBlock("ads.evil.com", "blocklist")
```

### Sequence (DNS query, forwarded)

```
... (same up to BlocklistRepository.isBlocked: false)
SentinelVpnService -> DatagramSocket(protect=true): send to 1.1.1.1:53
                                       (request payload echoed verbatim)
DatagramSocket -> upstream: standard query
upstream -> DatagramSocket: response
SentinelVpnService -> IpPacketParser.buildIpv4UdpReply: wrap response
SentinelVpnService -> kernel: write reply
SentinelVpnService -> VpnStats.recordForwarded
```

### Key design decisions

#### DNS-only tunnel
The service routes ONLY the sinkhole DNS IPs (`10.0.0.1/32` and the ULA
`fd00:5e71:1::1/128`) and advertises them as the DNS servers, so the OS
sends every DNS query to us while all other traffic uses the system
default network. Only UDP/53 is parsed; any other packet that reaches the
tun is echoed back unchanged. This is the standard "DNS-only VPN" pattern
(Blokada/DNS66/AdGuard) and keeps user-space free of a TCP state machine.
(Earlier drafts described this as a `0.0.0.0/0` catch-all — that was never
the implementation.)

Known bypasses / honest limits: encrypted DNS (system Private DNS over
DoT/853, or in-app DoH/443 like Chrome Secure DNS) is not on UDP/53 and is
not intercepted; an app with its own hardcoded resolver other than the
advertised sinkhole escapes (only the advertised DNS server's traffic is
routed to us); tethered clients are unprotected; strict Private DNS can
conflict with the advertised resolver.

#### Protected upstream socket
The Datagram socket forwarding non-blocked queries is `protect()`ed so
its packets bypass our own tunnel. Without this an upstream reply to the
sinkhole-advertised resolver could re-enter the tun and loop. A single
persistent protected socket is reused for all queries (see DnsForwarder).

#### Worst-checksum-paranoia
We compute both the IP-header checksum and the full UDP pseudo-header
checksum for synthesised replies. The IPv4 spec allows a zero UDP
checksum, but several Android kernels drop such packets in VPN
scenarios.

#### Hand-rolled DNS parser
`DnsPacketParser` is intentionally minimal (~120 lines): it parses the
first question, handles label compression with a pointer-cycle guard,
and builds NXDOMAIN replies that echo the original question section
verbatim. Pulling `dnsjava` would add tens of KB to the APK for one
parser + one synthesiser; not worth it.

#### Stats live in-process
`VpnStats` is a singleton holding atomic counters and a 100-entry
recent-blocks deque. Persistent storage is deferred to a later sprint
where the dashboard UX warrants historical charts.

## Layer 3 — Sprint 3 implementation

Layer 3 is implemented as an `AccessibilityService` that observes the
URL bar of a curated set of mainstream Android browsers. It catches
URLs that bypass intent dispatch entirely — direct typing, omnibox
autocomplete, history reopen.

### Component overview

```
+----------------------+    only allowed packages    +------------------------------+
| Watched browser      | --------------------------> | SentinelAccessibilityService |
| (Chrome, Firefox,    |     AccessibilityEvent      | (one main-looper handler +   |
|  Edge, Brave, etc.)  |                             |  one IO CoroutineScope)      |
+----------------------+                             +-------+----------------------+
                                                             |
                                package allow-list check     |
                                                             v
                                                +----------------------------+
                                                | findAccessibilityNodeInfos |
                                                | ByViewId( url_bar )        |
                                                +-------+--------------------+
                                                        |
                                       text candidate   v
                                                +----------------------------+
                                                | UrlSubmissionGate          |
                                                | - looksLikeSubmittableUrl  |
                                                | - 10s LRU rate-limit       |
                                                | - 700ms idle debounce      |
                                                +-------+--------------------+
                                                        |
                                                        v
                                                +----------------------------+
                                                | LinkAnalyzer (reused)      |
                                                | LocalBlacklistProvider     |
                                                | SafeBrowsingProvider       |
                                                +-------+--------------------+
                                                        |
                                                        v
                                          +-------------+-------------+
                                          |                           |
                                       SAFE                  SUSPICIOUS / MALICIOUS
                                          |                           |
                                          v                           v
                                +-------------------+    +-------------------------+
                                | AnalysisStats     |    | OverlayManager          |
                                | recordLinkChecked |    | TYPE_APPLICATION_OVERLAY|
                                +-------------------+    | dim card + actions      |
                                                         +-----------+-------------+
                                                                     |
                                                            "Torna indietro" -> 50ms
                                                                     |       delay
                                                                     v
                                                         performGlobalAction(BACK)
```

### Privacy contract
- `packageNames=` in `res/xml/accessibility_service_config.xml` makes
  the system itself filter events: only the 15 hand-listed browser
  packages will ever reach Sentinel.
- The Kotlin `watchedBrowsers` set re-applies the same check as
  defense in depth on the very first line of
  `onAccessibilityEvent`. Events from any other package never touch
  string content.
- The service only calls `findAccessibilityNodeInfosByViewId(url_bar)`;
  it never traverses arbitrary nodes to harvest text. The fallback
  walks at most six levels deep looking strictly for an `EditText`
  / `UrlBar` element when the curated id is missing.
- URLs are logged only in `BuildConfig.DEBUG` builds, truncated to
  32 characters.

### Heuristics for "URL was actually submitted"
The gate's `looksLikeSubmittableUrl` accepts strings that:
- start with `http://` / `https://` and contain at least one dot
  after the scheme, OR
- look like a bare host (`example.com`, `github.com/repo`) — at
  least one dot, no leading `/`, first label of length >= 2, no
  spaces.

The service additionally debounces using a 700ms idle timer keyed
on the URL bar contents, so we don't analyse on every keystroke.
The `UrlSubmissionGate` also dedupes inside a 10s sliding window
with a 64-entry LRU.

### Overlay behaviour
- One overlay at a time. New verdicts replace the previous card in
  place.
- The dim background is dismissable (tap outside the card).
- `Torna indietro` posts a 50ms delayed `performGlobalAction(
  GLOBAL_ACTION_BACK)` *after* the overlay has been detached. The
  delay prevents the back action from being consumed by the
  removed overlay window.
- The overlay is also dismissed on service `onUnbind` / `onDestroy`
  to avoid the WindowManager leak.

### Required user grants
- Settings > Accessibility > Sentinel (BIND_ACCESSIBILITY_SERVICE).
- Settings > Apps > Sentinel > Display over other apps
  (SYSTEM_ALERT_WINDOW). Without this the overlay falls back to a
  Toast and the dashboard surfaces a "Permesso sovrapposizione
  mancante" advisory chip.
