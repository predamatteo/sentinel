# Sentinel

> App Android open-source che protegge da link malevoli e pubblicità invasive,
> senza decifrare il traffico HTTPS e senza inviare nulla all'esterno.

**Piattaforma:** Android 7.0+ (API 24) · **Stack:** Flutter + Kotlin · **Distribuzione:** sideload (no Play Store)

---

## Indice

- [Cosa fa](#cosa-fa)
- [Cosa NON fa](#cosa-non-fa)
- [Architettura a 3 livelli](#architettura-a-3-livelli)
- [Privacy](#privacy)
- [Setup di sviluppo](#setup-di-sviluppo)
- [Build & sideload](#build--sideload)
- [Configurazione Firebase](#configurazione-firebase)
- [Configurazione Safe Browsing](#configurazione-safe-browsing)
- [Test](#test)
- [Struttura del progetto](#struttura-del-progetto)
- [Limitazioni note](#limitazioni-note)
- [Contribuire](#contribuire)
- [Licenza](#licenza)

---

## Cosa fa

Quando tocchi un link in WhatsApp, SMS, Gmail, Telegram o in qualsiasi altra app,
Sentinel lo analizza prima che venga aperto:

1. Imposti Sentinel come browser predefinito.
2. Android gli consegna l'intent del link.
3. Sentinel controlla il link con **Google Safe Browsing** + **blacklist locale**.
4. **Verdetto verde** → apre il link in Chrome (via Chrome Custom Tabs).
5. **Verdetto rosso/arancio** → mostra una schermata di avviso con i motivi e
   ti permette di tornare indietro o procedere consapevolmente.

In più, opzionalmente:

- **Filtro DNS locale**: una mini-VPN on-device che blocca pubblicità e domini
  malevoli per **tutte** le app del telefono.
- **Sentinella nei browser**: legge l'URL nella barra degli indirizzi dei
  browser supportati e applica lo stesso filtro anche quando digiti
  direttamente (no intent dispatch).

## Cosa NON fa

- Non è un browser: niente cronologia, tab, segnalibri, rendering.
- Non fa MITM: non vede il contenuto delle pagine HTTPS.
- Non manda traffico all'esterno: la VPN è 100% locale.
- Non richiede root.
- Non è sul Play Store: si installa via sideload (vedi sotto).

---

## Architettura a 3 livelli

| Livello | Meccanismo | Cosa copre | Stato |
|---------|------------|------------|-------|
| **L1** | Default-browser intent gating | URL toccati dall'utente in altre app | Done (Sprint 1) |
| **L2** | `VpnService` con filtro DNS | Tutte le query DNS in uscita (ads/tracker/sub-resource) | Done (Sprint 2) |
| **L3** | `AccessibilityService` sulla URL bar | URL digitati direttamente nei browser supportati | Done (Sprint 3) |

I livelli si compongono: L1 da solo è già utile; L1+L2 copre anche il
sotto-traffico; L1+L2+L3 copre anche la digitazione manuale.

Diagrammi di sequenza completi in [`docs/architecture.md`](docs/architecture.md).

### Browser supportati dalla Sentinella (L3)

Chrome · Firefox · Edge · Brave · Samsung Internet · Opera · DuckDuckGo ·
Ecosia · Vivaldi · Kiwi Browser

L'elenco è una **allow-list di sistema** dichiarata in
`res/xml/accessibility_service_config.xml` — qualsiasi altra app non viene
mai osservata, neppure con il servizio attivo.

---

## Privacy

Garanzie forzate dal codice, non solo dalla policy:

- **`packageNames=` nel config XML**: filtro a livello sistema, non Sentinel.
  Solo i 10 browser elencati possono inviare eventi al servizio di
  accessibilità.
- **Double-check Kotlin**: la prima riga di `onAccessibilityEvent` rifiuta
  qualsiasi pacchetto non in `watchedBrowsers`. Defense in depth.
- **Solo URL, mai contenuto pagina**: il servizio chiama esclusivamente
  `findAccessibilityNodeInfosByViewId("url_bar")`, non scorre l'albero nodi
  per raccogliere testo.
- **Log troncati**: gli URL sono loggati solo in `BuildConfig.DEBUG` e
  troncati a 32 caratteri.
- **VPN locale**: nessun upstream Sentinel. Il forwarding va direttamente
  ai DNS pubblici (Cloudflare 1.1.1.1 per default).
- **Nessun MITM HTTPS**: il payload TCP non viene mai parsato.

---

## Setup di sviluppo

### Prerequisiti

- **Flutter** ≥ 3.41 (testato su 3.41.4)
- **Dart** ≥ 3.11
- **Android SDK**: `compileSdk 36`, `targetSdk 34`, `minSdk 24`
- **JDK 17** per la build (JDK 21+ va bene sull'host, Gradle 8.14 fa toolchain auto)
- **Android Gradle Plugin** 8.11, **Kotlin** 2.2
- Un device fisico o emulatore con **Android 7.0 (API 24)+**

### Clone e primo setup

```bash
git clone https://github.com/<your-user>/sentinel.git
cd sentinel
flutter pub get
flutter gen-l10n
```

### File da creare dopo il clone

Quattro file **non sono nel repo** per ragioni di sicurezza. Esistono come
template, vanno copiati e compilati con i propri valori:

| Template | Copia in | Contiene |
|----------|----------|----------|
| `android/local.properties.example` | `android/local.properties` | path SDK + `SAFE_BROWSING_API_KEY` |
| `android/app/google-services.json.example` | `android/app/google-services.json` | identificatori app Firebase |
| `lib/firebase_options.dart.example` | `lib/firebase_options.dart` | identificatori Firebase per Flutter |
| `android/key.properties.template` | `android/key.properties` (solo se firmi release) | credenziali keystore release |

Per Firebase, il modo più veloce è **rigenerare i file con FlutterFire CLI**
invece di copiarli a mano — vedi sezione [Configurazione Firebase](#configurazione-firebase).

---

## Build & sideload

```bash
# Build debug
flutter build apk --debug

# Sideload sul device
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# Build release (debug-signed se key.properties non esiste, vedi sotto)
flutter build apk --release
```

### Firma release

Se `android/key.properties` esiste, la release viene firmata col keystore
indicato. Altrimenti fallback a debug-signing per non bloccare i contributori.

```bash
# Genera un keystore (tieni fuori dal repo)
keytool -genkey -v \
  -keystore sentinel-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias sentinel
```

Poi copia `android/key.properties.template` in `android/key.properties` e
compila i quattro campi.

Dopo la prima release: registra il nuovo SHA-1 in:
- **Firebase Console** → Project Settings → Add fingerprint (per App Check)
- **Google Cloud Console** → Credentials → Safe Browsing key → Android apps

---

## Configurazione Firebase

> **Nota di sicurezza**: gli identificatori Firebase per app mobile
> (`google-services.json`, `firebase_options.dart`, l'API key Android) **non
> sono segreti** secondo Google ([docs ufficiali](https://firebase.google.com/docs/projects/learn-more#config-files-objects)).
> Sono identificatori client estraibili da qualsiasi APK. Qui li teniamo
> fuori dal repo per pulizia, ma la **vera protezione** del backend va fatta
> con Security Rules + App Check + restrizioni API key.

### Setup veloce con FlutterFire CLI

```bash
# 1. Installa la CLI (una volta)
dart pub global activate flutterfire_cli

# 2. Login Firebase
firebase login

# 3. Crea un progetto su https://console.firebase.google.com/
#    (o riusane uno esistente)

# 4. Configura Sentinel sul progetto — genera google-services.json
#    e lib/firebase_options.dart automaticamente
flutterfire configure --project=<your-project-id>
```

### App Check (consigliato)

In **Firebase Console → App Check → Apps**:
- Registra Sentinel Android con **Play Integrity** provider.
- In debug build, cerca in `adb logcat` la riga:
  `D FirebaseAppCheck: Enter this debug secret into the allow list: ABCDEF12-...`
  e registra il token in **Manage debug tokens**. Senza questo, Remote
  Config non funziona in debug.

### Security Rules

Se usi Firestore/Storage/Realtime DB: **scrivi le regole** prima di
pubblicare. Le regole di default in "test mode" sono completamente aperte.

---

## Configurazione Safe Browsing

L'integrazione Safe Browsing è **opzionale**. Senza chiave, il provider
ritorna `SUSPICIOUS` con motivo "Safe Browsing API non configurata" e il
resto della pipeline (blacklist locale) continua a funzionare.

Per abilitarla:

1. Su [Google Cloud Console](https://console.cloud.google.com/), abilita
   la **Safe Browsing API v4**.
2. Crea una API key.
3. Aggiungi a `android/local.properties`:
   ```properties
   SAFE_BROWSING_API_KEY=your_actual_key_here
   ```
4. Rebuild.
5. **IMPORTANTE**: restringi subito la key in Cloud Console:
   - **Application restrictions** → **Android apps**
   - Package: `com.sentinel.app`
   - SHA-1: quella del tuo keystore (debug per dev, release per produzione)
   - **API restrictions** → solo Safe Browsing API

Senza queste restrizioni, chiunque scompatti l'APK può usare la tua chiave
e farti consumare quota.

---

## Test

```bash
# Dart
flutter test

# Kotlin / Android JVM
cd android && ./gradlew :app:testDebugUnitTest
```

---

## Struttura del progetto

```
android/app/src/main/kotlin/com/sentinel/app/
  LinkGateActivity.kt              # Activity unica, wiring di tutti i channel
  analysis/                        # Pipeline analisi link (L1)
  bridge/                          # MethodChannel verso Flutter
  vpn/                             # Implementazione L2 (VpnService + DNS)
  accessibility/                   # Implementazione L3 (AccessibilityService)
  persistence/                     # Room DB per eventi e statistiche

android/app/src/main/res/xml/
  accessibility_service_config.xml # Allow-list browser (enforce di sistema)

android/app/src/main/assets/
  blacklist/sample.txt             # Blacklist host L1
  blocklist/ads.txt                # Lista DNS ads/tracker bundled
  blocklist/malware.txt            # Lista DNS malware bundled

lib/
  main.dart                        # Inizializzazione Firebase + App Check
  app/                             # Router, tema
  features/
    analysis/                      # Screen analisi + verdetto
    dashboard/                     # Dashboard L2
    settings/                      # Impostazioni
    onboarding/                    # Onboarding utente
  services/                        # Wrapper MethodChannel + storage locale
  l10n/                            # ARB strings (IT primario, EN fallback)

docs/
  architecture.md                  # Diagrammi e design decisions
  sprint-{1,2,3}-summary.md        # Storia degli sprint
```

---

## Limitazioni note

- **DNS upstream in chiaro (UDP)**: DoT/DoH non ancora supportati.
- **Solo IPv4**: la VPN annuncia un solo prefisso IPv4.
- **L3 fragile per design**: l'estrazione URL si appoggia ai `resource-id`
  della URL bar dei singoli browser, che possono cambiare a ogni release.
  C'è un fallback best-effort che cammina nell'albero nodi cercando un
  `EditText`, ma non è garantito.
- **Niente iOS**: l'app è Android-only.

---

## Aggiungere un nuovo browser all'L3

1. Recupera il package name del browser:
   ```bash
   adb shell pm list packages | grep <nome>
   ```
2. Trova il `resource-id` della URL bar:
   ```bash
   adb shell uiautomator dump /sdcard/dump.xml
   adb pull /sdcard/dump.xml
   ```
   Apri `dump.xml`, cerca l'EditText focused.
3. Aggiungi il package in **tre** posti:
   - `SentinelAccessibilityService.watchedBrowsers`
   - `SentinelAccessibilityService.urlBarIds`
   - `res/xml/accessibility_service_config.xml` (`packageNames`)
4. Build, install, verifica con
   `adb logcat -v time SentinelAxs:D *:S`.

---

## Contribuire

PR benvenute. Linee guida minime:

- **Linguaggio**: codice, commenti, identificatori in **inglese**. Stringhe
  utente in **italiano** (primary) + **inglese** (fallback). Modifica via
  `lib/l10n/*.arb` e rigenera con `flutter gen-l10n`.
- **Stile**: `flutter analyze` deve passare pulito.
- **Test**: aggiungi test per ogni nuovo provider analisi e per ogni
  modifica al DNS parser.
- **Sicurezza**: mai committare `google-services.json`, `firebase_options.dart`,
  `local.properties`, keystore o `key.properties`. Sono già nel `.gitignore`.

---

## Licenza

[MIT](LICENSE) © 2026 Matteo Preda.

---

## Ringraziamenti

- [Google Safe Browsing v4](https://developers.google.com/safe-browsing/v4)
  per la pipeline di reputazione.
- [Firebase App Check](https://firebase.google.com/docs/app-check) per
  attestare l'integrità del client.
- Le blocklist bundled derivano da liste pubbliche curate dalla community.
