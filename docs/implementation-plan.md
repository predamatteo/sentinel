# Sentinel — Piano di implementazione fix (post-audit)

> Generato dall'audit performance + falsi positivi del 2026-05-31 (41 finding verificati).
> Le 7 fasi sono ordinate **dal rischio più alto al più basso**, come richiesto.
> Ogni fase è un'unità di lavoro committabile e testabile in isolamento.

## Principi

- **Un branch/commit per fase** (o per sotto-fase nelle fasi grandi). Niente edit paralleli sugli stessi file critici.
- **Test gate prima del merge**: unit JVM (`./gradlew :app:testDebugUnitTest`) + `flutter test` verdi; le fasi VPN richiedono anche test su dispositivo fisico.
- **L'ordine "rischio decrescente" è anche coerente con le dipendenze tecniche** (vedi grafo sotto), con una sola eccezione di valore: la curation blocklist (Fase 7) è a rischio minimo ma a impatto-utente massimo → può essere anticipata in parallelo in qualunque momento senza vincoli (è indipendente da tutto).

## Riepilogo fasi

| # | Fase | Rischio | Effort | Finding | Dipendenze |
|---|------|---------|--------|---------|------------|
| 1 | DNS hot-path asincrono + cache | 🔴 critical | XL | 6 | nessuna (deve precedere 2 e 5) |
| 2 | Copertura tunnel: IPv6 + TCP/53 | 🟠 high | L | 2 | preferibilmente **dopo** 1 |
| 3 | Link-analyzer fail-open | 🟡 medium | M | 7 | deve **precedere/accompagnare** 6 |
| 4 | Detection ambiente DNS cifrato + avvisi | 🟡 medium | M | 5 | indipendente |
| 5 | Robustezza parser DNS | 🟢 low | S | 3 | **dopo** 1 (riusa `forwardUpstream`) |
| 6 | UX verdetto + recovery falso positivo | 🟢 low | M | 5 | **dopo** 3 (contratto `UNAVAILABLE`) |
| 7 | Pulizia blocklist + whitelist | 🟢 low | M | 9 | indipendente (anticipabile subito) |

## Grafo dipendenze / ordine consigliato

```
1 (hot-path)  ──► 2 (IPv6/TCP)        [2 riusa l'executor async di 1]
            └──► 5 (parser)           [5 riusa forwardUpstream di 1]
3 (fail-open) ──► 6 (verdict UX)      [6 renderizza lo stato UNAVAILABLE di 3]
4 (avvisi ambiente)   — indipendente
7 (blocklist curation) — indipendente, quick win ad alto valore
```

Sequenza lineare consigliata: **1 → 2 → 3 → 4 → 5 → 6 → 7** (rispetta sia il rischio decrescente sia tutte le dipendenze). Se si vuole dare sollievo immediato all'utente, **7 può essere fatta per prima in un branch separato** senza toccare nessun'altra fase.

---

# FASE 1 — DNS hot-path asincrono + cache 🔴 CRITICAL (XL)

**Obiettivo:** eliminare l'head-of-line blocking e l'overhead per-query sull'hot-path DNS, **senza** rompere la risoluzione dei nomi di tutto il device.

**Finding coperti:** `hol-blocking-upstream` (CRITICAL), `per-query-socket-churn`, `no-dns-cache`, `per-packet-allocations`, `passthrough-copy-overhead`, `notification-update-cadence`.

**Perché è la più rischiosa:** ogni query DNS del telefono passa da `runLoop`. Un difetto nella correlazione delle risposte, nella scrittura sul tun fd o nella `protect()` del socket **lascia l'utente senza DNS** finché non disattiva manualmente la VPN. Introduce concorrenza dove non c'era.

### Architettura target
Loop di lettura **non bloccante** (read + parse + decisione) che delega il forwarding a un componente async con **un solo socket protetto persistente** e correlazione delle risposte stile NAT (txid riallocato), un **single-writer** sul tun fd, e una **cache LRU/TTL** delle risposte.

### Sotto-fasi (ognuna buildabile/committabile)
1. **(rischio nullo)** `BlocklistRepository`: precompute della whitelist unita in `AtomicReference` (no rebuild per lookup) + test.
2. **(puro)** `DnsPacketParser.parseAnswerTtlAndRcode` + `rewriteTransactionId` + test.
3. **`TunWriter`** (NEW): single-writer su `Channel<ByteArray>` drenato da una coroutine; instradare **tutte** le scritture tun qui mantenendo il forwarding ancora sincrono (isola e prova la correttezza del single-writer).
4. **(pericolosa)** `DnsForwarder` (NEW): un `DatagramChannel` protetto una sola volta, mappa di correlazione con txid riallocato (monotono) + sanity check `qName/qType`, una sola `receiveLoop`, happy-eyeballs / timeout ~1500 ms; spostare gli eventi terminali di `VpnStats` sulla transizione `map.remove`. **Device-test HOL + leak qui.**
5. **`DnsAnswerCache`** (NEW): LRU+TTL keyed `(qName,qType)`, expiry = min RR TTL; negative cache ≤30s; hit serviti dal dispatcher senza upstream. **Device-test cache + upstream irraggiungibile.**
6. **(polish)** rimuovere il contatore notifiche inline → timer coroutine ~2s; rimuovere `copyOf` upfront e il param `readView` morto.

### File
- `vpn/SentinelVpnService.kt` — riscrivere `runLoop`/`handlePacket`/`forwardUpstream`/`sendRecv`; wiring lifecycle di `DnsForwarder`/`TunWriter` in `startTunnel`/`shutdown`; notifiche su timer.
- `vpn/DnsForwarder.kt` **(NEW)** — socket protetto persistente, `PendingQuery`, correlazione, `receiveLoop`, happy-eyeballs.
- `vpn/TunWriter.kt` **(NEW)** — single-writer guard sul `FileOutputStream`.
- `vpn/DnsAnswerCache.kt` **(NEW)** — LRU+TTL.
- `vpn/DnsPacketParser.kt` — `parseAnswerTtlAndRcode`, `rewriteTransactionId` (parseQuery invariato).
- `vpn/BlocklistRepository.kt` — `mergedWhitelistRef` precomputato (sostituisce il rebuild a righe 179-183).
- `vpn/UpstreamDnsConfig.kt` — costante timeout per-attempt (~1500ms).
- `vpn/VpnStats.kt` — ordinare gli eventi terminali (exactly-once dalla `map.remove`).
- Test **(NEW)**: `DnsForwarderCorrelationTest`, `DnsAnswerCacheTest`; estendere `DnsPacketParserTest`.

### Rischi di regressione → mitigazioni
- **Socket non protetto → loop infinito nel tun, DNS totalmente morto.** → `protect()` una sola volta in `start()` prima di ogni `send`; abort+recordError se fallisce; `addDisallowedApplication` come secondo strato; verifica via tcpdump che l'upstream esca dalla rete reale.
- **Collisione di correlazione → risposta al client sbagliato.** → txid riallocato monotono come chiave (NAT-style) + sanity check `qName/qType`; ripristino txid originale prima della scrittura; test collisione/wraparound.
- **Scritture concorrenti sul tun fd → stream IPv4 corrotto.** → tutte le scritture via `TunWriter`; grep di review che `out.write` esista solo in `TunWriter`.
- **Riuso del buffer di lettura su pass-through → pacchetti corrotti.** → copia esplicita al punto di handoff (DNS payload `copyOfRange`, pass-through copia lo slice).
- **Cache avvelenata / NXDOMAIN cachato maschera recovery.** → rispetto stretto del min TTL; TTL=0 mai cachato; negative cache ≤30s; mai cachare risposte con TC bit.
- **Timeout 1500ms troppo aggressivo su reti lente.** → al primo scadere, re-send al secondario sullo stesso socket (first-win); errore solo dopo seconda finestra (~3s totali); costante tunabile.
- **Leak coroutine/socket allo shutdown.** → `close()` da `shutdown()`; verifica FD con `/proc/<pid>/fd`.

### Test
- **unit:** correlazione (keying, restore txid, collisione, timeout=1 recordError, reply non in mappa droppata); cache (TTL boundary, LRU, TTL=0, restamp txid byte-identico tranne 0-1); `parseAnswerTtlAndRcode` (single/multi-RR min TTL, NXDOMAIN); `BlocklistRepository` (merged whitelist, nessuna allocazione per lookup); `TunWriter` (no interleaving, overflow drop+error).
- **manual-device:** risoluzione baseline (10+ siti, latenza ≈ VPN-off); domini bloccati ancora NXDOMAIN + whitelist senza restart; **no leak** (tcpdump); **HOL eliminato** (primary blackhole → risolve via secondario, no freeze 10s, stall ~3s); upstream irraggiungibile (fail-fast ~3s, recovery immediata); cache (2ª query senza pacchetto upstream); lifecycle/leak (start/stop 10x, no crescita FD).

### Criteri di accettazione
Una upstream lenta/irraggiungibile **non** blocca le altre query (stall worst-case ~3s, no freeze UI); **un solo** socket protetto per tunnel; risoluzione invariata + domini bloccati ancora NXDOMAIN; tutte le scritture tun via `TunWriter`; correlazione corretta con txid originale ripristinato; cache LRU+TTL funzionante; nessun leak DNS; invariante `VpnStats` (`totalQueries == forwarded + blocked + errors + in-flight`); notifiche su timer; test verdi.

### Domande aperte
- `DatagramChannel`+`Selector` vs `receive` su coroutine dedicata (raccomandato il secondo per semplicità) — confermare `protect()` su `channel.socket()` su minSdk 24.
- Negative cache: cap fisso 30s vs parsing SOA min TTL (raccomandato cap fisso).
- Verdict cache per-dominio ora o dopo (il fix merged-whitelist già rimuove l'allocazione principale).
- TunWriter overflow: DROP_OLDEST vs DROP_LATEST (decidere dopo il test di carico).

---

# FASE 2 — Copertura tunnel: IPv6 + TCP/53 🟠 HIGH (L)

**Obiettivo:** far funzionare il filtro su reti moderne — intercettare IPv6 invece di lasciarlo passare, e gestire le risposte grandi/TCP — senza regredire IPv4 né rompere dual-stack/Private DNS.

**Finding coperti:** `ipv6-aaaa-leak-dual-stack` (HIGH), `no-tcp53-and-edns-truncation-fallback` (MEDIUM).

### Scope minimo (da spedire per primo)
1. **IPv6 interception:** in `startTunnel()` aggiungere `addAddress(ULA fd00::/…)`, `addRoute(sinkhole IPv6 /128)`, `addDnsServer(sinkhole IPv6)`. **CRITICO: instradare solo il /128**, mai `::/0` (preserva la connettività IPv6 non-DNS).
2. `handlePacket`: branch sul nibble di versione. v4 → invariato; v6 → `dispatchIpv6()` (parse header 40 byte; solo UDP/53; altrimenti passthrough).
3. **`buildIpv6UdpReply` con checksum UDP obbligatorio** sullo pseudo-header IPv6 (RFC 8200: checksum 0 illegale, il kernel droppa). Test contro ricalcolo indipendente.
4. Allargare il recv buffer in `sendRecv` da 4096 a 65535 (metà a basso costo del finding MEDIUM).
5. **TCP/53 MVP:** poiché non si instrada `0.0.0.0/0`/`::/0`, il retry TCP del client verso il resolver reale esce già dalla rete di default. Verificare via capture se arriva TCP/53 al sinkhole; se sì, **droppare** (non echo) e affidarsi al NXDOMAIN UDP (mai truncato).

### Scope completo (follow-up, solo se il device-test mostra domanda reale di TCP/53)
6. Proxy TCP/53 userspace (macchina a stati TCP, forward su socket TCP protetto). Dietro feature flag. **Fuori dall'MVP.**

### File
- `vpn/SentinelVpnService.kt` — Builder IPv6, branch versione in `handlePacket`, `dispatchIpv6()`, recv buffer 65535, upstream per famiglia.
- `vpn/IpPacketParser.kt` — `parseIpv6()`, `buildIpv6UdpReply()`, `ipv6UdpChecksum()` (non toccare `buildIpv4UdpReply`).
- `vpn/DnsPacketParser.kt` — helper TC=1 detection per risposte allowed.
- `IpPacketParserIpv6Test.kt` **(NEW)** + regressione IPv4 golden-bytes.
- `AndroidManifest.xml` — verifica (nessuna nuova permission).

### Rischi → mitigazioni
- **Prefisso IPv6 troppo largo → blackhole IPv6 ("no internet" con dashboard verde).** → solo `/128` del sinkhole; `curl -6` con VPN su.
- **Checksum IPv6 errato → kernel droppa, IPv6 DNS rotto silenziosamente.** → unit test vs riferimento + pacchetto reale; mai 0 (sostituire 0xFFFF); device-test AAAA su `ipv6.google.com`.
- **Private DNS strict bypassa il resolver advertised.** → testare Off/Automatic/Strict; documentare DoT/DoH come bypass noto.
- **Regressione del fast-path IPv4.** → non modificare `buildIpv4UdpReply`; test golden-bytes IPv4.

### Criteri di accettazione
AAAA di dominio bloccato → NXDOMAIN su dual-stack (no leak); `curl -6` funziona (no `::/0`); checksum IPv6 corretto/validato; IPv4 byte-identico; risposte UDP/EDNS0 grandi (≤65535) integre, no TCP/53 in loop; risoluzione in Private DNS Off/Auto/Strict; modello di threading uguale a IPv4.

> **Ordine:** preferibile **dopo** la Fase 1 per riusare l'executor off-loop; se anticipata, tenere il forward IPv6 sullo stesso path sincrono di IPv4 (no modello di threading parallelo).

---

# FASE 3 — Link-analyzer fail-open 🟡 MEDIUM (M)

**Obiettivo:** un controllo fallito/non disponibile **non** deve mai escalare un URL pulito a SUSPICIOUS. Solo segnali positivi reali (blacklist hit, match Safe Browsing) generano SUSPICIOUS/MALICIOUS.

**Finding coperti:** `worst-aggregation-fail-closed`, `timeout-maps-to-suspicious-by-design`, `unconfigured-key-maps-to-suspicious`, `network-error-maps-to-suspicious`, `http-non2xx-maps-to-suspicious`, `timeout-mismatch-manufactures-suspicious`.

**Perché medium (tocca la sicurezza):** va preservata **esattamente** la detection delle minacce reali — vietato fail-open su match SB/blacklist veri.

### Approccio
1. **`Verdict.UNAVAILABLE(-1)`** (severity negativa così non vince mai il max); `Verdict.worst` filtra `UNAVAILABLE` prima del max → `[SAFE, UNAVAILABLE]` = SAFE.
2. Campo **`notes: List<String>`** in `AnalysisResult`: `reasons` solo da SUSPICIOUS/MALICIOUS; `notes` da UNAVAILABLE; `sources` invariato.
3. I 6 rami di fallimento (blank key, HTTP non-2xx, eccezione, timeout, errore interno, URL non parsabile) → `UNAVAILABLE`. **Zero modifiche** ai rami che emettono MALICIOUS (`parseResponse`, match blacklist).
4. Timeout: `perProviderTimeoutMs` 3000 → **6000** (> connect 2500 + read 2500 + margine). Invariante documentata inline.
5. Dart: `analysis_models.dart` aggiunge `case 'UNAVAILABLE' → Verdict.unknown` + parsing difensivo di `notes`. **Non** toccare il rendering di `verdict_screen`/`analyzing_screen` (è della Fase 6).

### File
- `analysis/AnalysisResult.kt` — enum `UNAVAILABLE`, `Verdict.worst` filtrato, `notes`, fix KDoc.
- `analysis/UrlProvider.kt` — KDoc contratto.
- `analysis/LinkAnalyzer.kt` — timeout 6000, rami timeout/errore → UNAVAILABLE, split reasons/notes, KDoc.
- `analysis/providers/SafeBrowsingProvider.kt` — 3 rami failure → UNAVAILABLE (`parseResponse` **intatto**).
- `analysis/providers/LocalBlacklistProvider.kt` — ramo URL-non-valido → UNAVAILABLE (match MALICIOUS **intatto**).
- `lib/services/analysis_models.dart` — mapping + `notes`.
- Test **(NEW)**: `LinkAnalyzerTest`, `VerdictWorstTest`, `SafeBrowsingProviderTest`; estendere `widget_test.dart`.

### Rischi → mitigazioni
- **Match SB reale declassato a UNAVAILABLE (catastrofico).** → editare solo i 3 rami failure; `parseResponse` byte-identico; test che un `matches[]` sintetico dà MALICIOUS; gate di review sul diff.
- **All-UNAVAILABLE → SAFE: se SB down E blacklist non carica, tutto SAFE.** → la blacklist locale non dipende dalla rete; `notes` informa l'utente; test che blacklist-hit MALICIOUS vince anche con SB UNAVAILABLE.
- **Timeout 6000 aumenta la latenza peggiore.** → latenza SB tipica ~200ms; spinner con Cancel; accessibility off-thread.
- **`UNAVAILABLE` scritto nella tabella eventi.** → colonna `verdict` è String libera, nessuna migration; grep query stats per `'SUSPICIOUS'` hardcoded.

### Criteri di accettazione
`worst([SAFE,UNAVAILABLE])==SAFE`, `worst([MALICIOUS,UNAVAILABLE])==MALICIOUS`; nessun ramo emette più SUSPICIOUS su fallimento; minaccia SB/blacklist reale → ancora MALICIOUS (provato da test); i 5 fallimenti → `notes` non `reasons`; default timeout 6000 > 5000; `'UNAVAILABLE'` → `Verdict.unknown` deterministico; rendering Dart **non** modificato in questa fase.

### Domande aperte
- Ramo URL malformato: UNAVAILABLE (raccomandato) vs SUSPICIOUS cautelativo.
- All-UNAVAILABLE → SAFE accettabile per il threat model? (alternativa = scelta di rendering in Fase 6).
- `parseResponse` `@VisibleForTesting internal` per testare il path MALICIOUS senza refactor.
- Confermare `kotlinx-coroutines-test` (`runTest`) sul classpath di test.

---

# FASE 4 — Detection ambiente DNS cifrato + avvisi 🟡 MEDIUM (M)

**Obiettivo:** mostrare avvisi onesti quando l'ambiente bypassa/rompe il filtro, invece di uno stato "protetto" pulito; risolvere con grazia il conflitto Private DNS strict.

**Finding coperti:** `doh-dot-bypass-chrome-private-dns` (HIGH), `private-dns-strict-conflict-breaks-resolution`, IPv6-warning (fallback se la Fase 2 è rimandata), `single-vpn-replaces-existing-vpn-silently`, `hardcoded-resolver-escape-route` + fix doc.

### Approccio
1. **`NetworkEnvironmentDetector`** (NEW, modellato su `TetheringDetector`): legge `Settings.Global` `private_dns_mode`/`private_dns_specifier` (chiavi stringa, API 28+), `LinkProperties.isPrivateDnsActive`, e route IPv6 di default. Tutto guardato `>= P` + try/catch, mai throw.
2. Bridge: `VpnController.environmentStatus()` → `VpnChannel "getEnvironmentStatus"` → `VpnService.getEnvironmentStatus()` Dart (default safe su errore).
3. **Push immediato su `onRevoke`:** `VpnControllerHolder` con listener `@Volatile`; `VpnChannel` invoca `onRunningChanged`; il dashboard flippa istantaneamente (poll 2s resta come rete di sicurezza).
4. **Advisory dashboard:** strict Private DNS (emphasis warning) > encrypted DNS (informativo) ; IPv6 indipendente. Riusa il layout di `_HotspotAdvisoryCard`.
5. **Warning eviction VPN** (LOW): AlertDialog una volta per sessione prima del primo start.
6. **Nuove chiavi ARB** (en+it) — copy non allarmante già redatta (es. *"DNS cifrato attivo: il filtro potrebbe non vedere alcune richieste"*).
7. **Fix doc** `architecture.md`: correggere il "claims 0.0.0.0/0" → reale `/32` sinkhole; aggiungere sezione "Known bypasses".
8. (opzionale, feature-flag OFF) skip `addDnsServer` in strict Private DNS — validare su device.

### File
- `vpn/NetworkEnvironmentDetector.kt` **(NEW)** + `NetworkEnvironmentDetectorTest.kt` **(NEW)**.
- `vpn/VpnController.kt` — `environmentStatus()`, `setRunningListener`/`clearRunningListener`.
- `bridge/VpnChannel.kt` — case `getEnvironmentStatus`, push `onRunningChanged`.
- `vpn/SentinelVpnService.kt` — `onRevoke` push, `VpnControllerHolder` listener, (opz.) skip addDnsServer.
- `lib/services/vpn_service.dart` — `VpnEnvironmentStatus`, `getEnvironmentStatus()`, stream `runningChanges`.
- `lib/features/dashboard/dashboard_screen.dart` — 3 advisory card, sub a `runningChanges`, dialog eviction.
- `lib/l10n/app_en.arb` + `app_it.arb` — nuove chiavi.
- `docs/architecture.md` — fix routing + Known bypasses.

### Rischi → mitigazioni
- **Read piattaforma throw su OEM/API vecchi.** → guard `>= P` + try/catch → safe all-clear; default Dart su `PlatformException`.
- **Race push onRevoke vs poll 2s.** → notify solo su transizione reale; flip a false prima di `stopSelf`.
- **Falso "encrypted DNS" in opportunistic non negoziato.** → richiedere `isPrivateDnsActive==true`; solo strict flagga sempre.
- **Skip addDnsServer disabilita il filtro silenziosamente.** → feature-flag OFF di default; verifica device.
- **Codegen l10n rotto dal placeholder `{hostname}`.** → metadata tipizzata; `flutter gen-l10n`; key order identico en/it.

### Criteri di accettazione
Strict Private DNS → advisory di conflitto (con hostname), mai insieme a quello soft; opportunistic upgradato/strict → advisory "DNS cifrato"; route IPv6 default → advisory IPv6; revoke → dashboard inattivo immediato; start con altra VPN attiva → warning; nessun crash su API<28; `architecture.md` corretto; chiavi ARB en/it allineate.

---

# FASE 5 — Robustezza parser DNS 🟢 LOW (S)

**Obiettivo:** indurire il parser e correggere doc/contratti; **zero** cambi di comportamento per il traffico normale single-question.

**Finding coperti:** `no-multi-question-handling`, `readname-pointer-into-header-and-forward-only`, `edns0-stripped-from-nxdomain` (tutti LOW). **Non-goal esplicito:** non introdurre il leak upstream che i verificatori hanno escluso (`trust-iptotallength`, `unfiltered-passthrough` erano falsi allarmi — il routing `/32` black-hole è fail-closed, va preservato).

### Approccio
1. **`readName` bounds check** (prima di `i = pointer`): `if (pointer < HEADER_LENGTH) return null` (mai nel header) e `if (pointer >= i) return null` (solo backward). Mantenere `hops>8` come difesa.
2. Esporre `qdCount` su `DnsQuery` (già calcolato, ora scartato).
3. `handlePacket`: dopo `recordQuery()`, `if (query.qdCount != 1) { forwardUpstream(...); return }` (no sintesi di NXDOMAIN count-mismatched). **Dipende da `forwardUpstream` della Fase 1.**
4. Fix doc: KDoc classe + `DnsQuery` (l'EDNS0 NON è preservato; drop OPT su NXDOMAIN sintetico è RFC 6891-legale).

### File
- `vpn/DnsPacketParser.kt` — bounds check, `qdCount`, fix KDoc.
- `vpn/SentinelVpnService.kt` — guard `qdCount != 1` in `handlePacket`.
- `vpn/DnsPacketParserTest.kt` — `rejectsForwardPointer`, `rejectsPointerIntoHeader`, `parsesQdCountTwoExposesCount`, `parsesBackwardPointer` (regressione).

### Rischi → mitigazioni
- **Query con pointer forward legittimo rifiutata.** → i pointer forward sono non-conformi (RFC 1035 4.1.4); nessun resolver mainstream li emette; test `parsesBackwardPointer` prova che la compressione valida funziona.
- **Confronto backward mal specificato.** → confrontare contro `i` = offset del primo ottetto del pointer (invariante standard).
- **Routing qdCount>1 cambia accounting.** → multi-question praticamente inesistente; comportamento qdCount==1 invariato.

### Criteri di accettazione
`parseQuery` null su pointer forward e pointer-into-header; `qdCount` esposto; backward pointer valido ancora parsato; doc corrette; test esistenti + nuovi verdi.

---

# FASE 6 — UX verdetto + recovery falso positivo 🟢 LOW (M)

**Obiettivo:** smettere di presentare "verifica incompleta" come minaccia; dare un recovery in un tap per un dominio falsamente sinkholato.

**Finding coperti:** `suspicious-rendered-as-danger`, `suspicious-uses-malware-confirm-dialog`, `suspicious-copy-implies-block`, `no-recovery-from-dns-sinkhole`, `whitelist-requires-exact-domain-typing`.

### Approccio
1. **`_NeutralVerdictBody`** distinto dal danger body per SUSPICIOUS/UNKNOWN/UNAVAILABLE: palette info, icona info, azione primaria **"Apri"**.
2. Niente dialog malware per i verdetti non-MALICIOUS (rimuovere `askConfirm:true` o dialog neutro); rosso solo per MALICIOUS.
3. Copy de-escalation (*"Non siamo riusciti a verificare il sito, ma probabilmente è sicuro. Puoi aprirlo."*).
4. **`analyzing_screen`**: `unknown`/`unavailable` → non-blocking (auto-forward stile safe, mostrando le `notes`).
5. **Tile blocco recente tappabile** con "Consenti questo dominio" → `WhitelistService.add` + `VpnService.setWhitelist`; storico più completo e ricercabile (non solo "oggi", non cap a 10).
6. **`WhitelistService`**: normalizzare URL completi (strip scheme/path/www), snackbar successo/duplicato.

### File
- `lib/features/analysis/verdict_screen.dart` — `_NeutralVerdictBody`, branch dialog.
- `lib/features/analysis/analyzing_screen.dart` — auto-forward per unknown/unavailable.
- `lib/features/dashboard/dashboard_screen.dart` — `_BlockEventTile` tappabile, storico ricercabile.
- `lib/features/settings/settings_screen.dart` — feedback whitelist.
- `lib/services/whitelist_service.dart` — normalizzazione URL.
- `lib/services/analysis_models.dart` — rendering `Verdict.unavailable` (case dall'enum della Fase 3).
- `lib/l10n/app_en.arb` + `app_it.arb` — nuove chiavi `verdictUnavailable*`, copy de-escalation, "Consenti questo dominio".

### Rischi → mitigazioni
- **`case Verdict.unavailable` non compila senza la Fase 3.** → questa fase **dopo/insieme** a `link-analyzer-failopen` (gli step 1,2,4,5,6 sono indipendenti e anticipabili; solo lo step `unavailable` aspetta la Fase 3).
- **Auto-forward di unknown indebolisce la cautela.** → mostrare comunque le `notes`; il dialog malware resta solo per MALICIOUS.

### Criteri di accettazione
SUSPICIOUS/UNKNOWN/UNAVAILABLE con UI neutra e azione primaria "Apri"; nessun dialog malware fuori da MALICIOUS; copy rassicurante; un tap dal blocco recente whitelista il dominio e lo fa risolvere; whitelist accetta URL completi con feedback; chiavi ARB en/it allineate.

---

# FASE 7 — Pulizia blocklist + whitelist 🟢 LOW (M) — *quick win, anticipabile subito*

**Obiettivo:** smettere di NXDOMAIN-are servizi legittimi user-facing; documentare i dual-use; correggere il doc routing. **Solo dati + test + doc**, nessun cambio alla logica di matching.

**Finding coperti:** 9 (vedi sotto). **Massimo impatto-utente, rischio minimo → si può fare per prima in un branch isolato.**

### Modifiche puntuali
- **Rimuovere da `ads.txt`:** `usercentrics.eu` + `tag.usercentrics.eu` (CMP consent, HIGH — serve togliere l'apex per via del parent-climb), `amzn.to` (shortener Amazon), `gravatar.com` (avatar; o lista opt-in), `app.adjust.com` (redirector click; mantenere l'apex `adjust.com` se voluto).
- **NON rimuovere** `singular.net`/`api.singular.net` (i verificatori hanno escluso la rottura: l'host user-facing è `*.sng.link`, non in lista).
- **Aggiungere a `defaultWhitelist`** (`BlocklistRepository.kt`): `connect.facebook.net` (FB JS SDK / login web — coerente con l'intento già dichiarato per `graph.facebook.com`).
- **Dual-use** (`disqus.com`, `intercom.io`, `googletagmanager.com`, `sentry.io`, `optimizely.com`, `amplitude.com`, `pendo.io`): bloccarli di default è difendibile per un privacy-blocker, ma rompono funzioni → proporre una **categoria opt-in "non-ads"** (file/categoria separata) e documentarli.
- **Asimmetria apex** (`matching-logic-apex-overblock`): applicare lo stesso pattern di `googleadservices.com` (whitelist apex + blocco dei soli sottodomini di ad-delivery) a ogni apex di servizio legittimo che deve restare bloccato a livello di sottodominio.
- **Fix doc** `architecture.md`: `0.0.0.0/0` → reale `SINKHOLE 10.0.0.1/32`.

### File
- `android/app/src/main/assets/blocklist/ads.txt` — rimozioni/sposta.
- `vpn/BlocklistRepository.kt` — aggiunte a `defaultWhitelist`.
- `vpn/BlocklistMatchTest.kt` — test: ogni dominio sbloccato ora `Allowed`; `connect.facebook.net` `Allowed`; sottodomini ad-delivery ancora bloccati.
- `docs/architecture.md` — fix routing.
- (opz.) nuovo file lista opt-in dual-use + wiring.

### Rischi → mitigazioni
- **Rimuovere l'apex lascia attivi sottodomini di tracking.** → per i domini puramente ad (non in lista) nessun problema; per i dual-use usare opt-in; test di regressione che i sottodomini ad-delivery noti restano bloccati.

### Criteri di accettazione
`usercentrics.eu`/`amzn.to`/`gravatar.com`/`app.adjust.com` non più sinkholati; `connect.facebook.net` `Allowed` via `defaultWhitelist`; `singular.*` invariati; dual-use documentati/opt-in; `architecture.md` corretto; `BlocklistMatchTest` esteso e verde.

---

## Nota finale sull'ordine

L'ordine 1→7 affronta per primo il rischio tecnico maggiore (refactor dell'hot-path) quando il margine è massimo, ed evita rework: parser (5) e UX (6) si appoggiano rispettivamente all'hot-path rifattorizzato (1) e al contratto `UNAVAILABLE` (3). **Unica raccomandazione pratica:** la Fase 7 (pulizia blocklist) è a rischio minimo ma risolve subito gran parte dei "blocca siti buoni" — conviene aprirla in parallelo in un branch separato fin da subito, senza alterare la sequenza delle altre fasi.
