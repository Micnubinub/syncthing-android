# Code Audit Guide

Audit this repository for logic errors, security flaws, and best-practice violations. Public
Android app wrapping Syncthing: runs `libsyncthingnative.so` as a child process and drives it over
the local REST API and an embedded WebView GUI. Prioritize anything exploitable, abusable,
data-destroying, or leaking sensitive data.

Criteria come from `.ai/BestPractice.md` (§3 classification, §4 profile, §5–§13 checks, §15 report
requirements). This file defines process, scope, and output.

## Process

1. Create `outputs/Audit$(openssl rand -hex 4).md` (`mkdir -p outputs`; regenerate the name if it
   exists). Append findings as you go.
2. Baseline: `git rev-parse HEAD`, `git status --short`, `git diff --stat`,
   `git log --oneline -20`. Record revision and dirty state.
3. Orient: `ripwire app/src/main/java --for="<audit focus>"`. Then read in full every file on the
   paths you trace; never judge a symbol from a snippet.
4. Trace flows end to end along the §4 pipeline (screen -> ViewModel -> `RestApi`/`ConfigRouter`/
   `ConfigXml` -> `http/` -> `SyncthingRunnable` -> `EventsPoller` -> `EventProcessor` -> UI state).
5. Run the candidate searches below; confirm callers, dispatcher, ownership, and cleanup before
   reporting.
6. Verify from the repo root: `./gradlew assembleDebug`, then `./gradlew testDebugUnitTest`. Say
   so if they cannot run (e.g. no Go/NDK). No unit tests exist, so a green test run is not
   behavioral evidence.

## Scope

- **Skip:** `Audit*`, `audit*`, `outputs/**`, `.ai/**`; `.gitignore`d paths; generated or vendored
  code (`syncthing/src/`, `app/src/main/jniLibs/`, `build/`, `.gradle/`, `.kotlin/`, `*.iml`)
  unless wrapper code misconfigures it; `values-*/strings.xml`.
- **Out of scope (local, user-controlled by design):** zip/archive handling and path traversal,
  unencrypted exports and at-rest encryption, plaintext passwords in local storage, user-invoked
  scripts. Do not report these.
- Never read `local.properties`, `release.keystore`, or `KEYSTORE_*` / `KEY_*` env vars.
- Write only the generated report file.

## Focus areas

- **Exported surface:** `MainActivity` (launcher, leanback, QS tile prefs), `ShareActivity`
  (`SEND`/`SEND_MULTIPLE` extras and URIs), `AppConfigReceiver` (`CONFIG_APP`), the three
  `QuickSettingsTile*` services, `FileProvider` paths. Check the merged manifest.
- **Local API and TLS:** API-key handling and logging; `ApiRequest#forceLoopbackHost`;
  `SyncthingTrustManager`, `CertificateValidator`, and the user-CA loopback domain config;
  `onReceivedSslError` in both `WebViewActivityScreen` and `RootDestinations`; redirects or host
  rewrites that leak the key or trust exception off loopback; response closing.
- **Native subprocess (`SyncthingRunnable`, `SyncthingService`):** duplicate starts, unbounded or
  non-cancellable waits (`Thread.sleep` exit poll), API 24–25 fallbacks, orphaned core after app
  death, cleanup hitting the wrong process or port, unbounded output buffering, crash loops,
  `MulticastLock` release.
- **Config and data integrity:** `ConfigXml` edits racing the core; identity or API key regenerated
  after recoverable errors; folder/device removal vs. data deletion; import/export completeness.
- **Coroutines and lifecycle:** blocking on main (incl. `Thread.sleep` backoff in
  `SyncthingHttpClients`); scopes outliving owners; swallowed `CancellationException`;
  `SharedPreferenceFlow` listener cleanup and its write-back vs. direct `SharedPreferences` writers;
  `EventsPoller` stopping with its owner; `QRScannerActivityViewModel` executor shutdown.
- **Background execution:** `specialUse` FGS start from boot/background, `BootReceiver`,
  `SyncTriggerJobService` / `JobUtils` unique-work policy, `RunConditionMonitor` receivers via
  `ReceiverManager`.
- **Platform config:** `network_security_config.xml`, `data_extraction_rules.xml`,
  `allowBackup`, requested permissions vs. actual use.
- **Conventions:** `AGENTS.md` rules in reviewed code, as Requirement/Style violation, Low unless
  runtime harm is established.

## Candidate searches

Results are candidates, not verdicts. `rg` honors `.gitignore`; an empty search says nothing about
packaged output.

```bash
rg -n -g '*.kt' '!!|GlobalScope|runBlocking|Thread\.sleep|Executors|Thread\(' app/src
rg -n -g '*.kt' 'catch\s*\([^)]*:\s*(Exception|Throwable|Error)|runCatching' app/src
rg -n -g '*.kt' 'ProcessBuilder|waitFor|destroy(Forcibly)?\(|EventsPoller|\.execute\(' app/src
rg -n -g '*.kt' 'onReceivedSslError|proceed\(|X509TrustManager|HostnameVerifier|forceLoopbackHost|toLoopback' app/src
rg -n -g '*.kt' 'addJavascriptInterface|javaScriptEnabled|allowFileAccess|allowContentAccess|mixedContentMode|http://' app/src
rg -n -g '*.kt' 'Log\.[dewiv]\(.*(apiKey|api_key|password|token|X-API-Key)' app/src
rg -n -g '*.kt' 'getParcelableExtra|getStringExtra|EXTRA_STREAM|PendingIntent\.get|FLAG_MUTABLE' app/src
rg -n -g '*.kt' 'registerReceiver|WakeLock|WifiLock|MulticastLock|startForeground|enqueueUnique' app/src
rg -n -g '*.kt' 'SharedPreferences|fromJson|toJson|deleteRecursively|renameTo|\.commit\(\)' app/src
rg -n -g '*.kt' 'callbackFlow|awaitClose|stateIn|shareIn|SharingStarted|flatMapLatest|NonCancellable' app/src
rg -n 'exported|permission|foregroundServiceType|allowBackup|usesCleartextTraffic' app/src/main/AndroidManifest.xml
```

## Rules

- Terse. No emojis, no executive summary.
- One finding per root cause; list every location.
- Report only what code you read or checks you ran support. No guessed line numbers, no invented
  CVEs, no hypothesis stated as confirmed.
- Smallest correct fix; preserve behavior unless defective.
- Order Critical > High > Medium > Low, then impact and likelihood. Omit empty sections.

## Output

Append, in order:

1. **Scope and verification:** revision, dirty state, areas Inspected / Not applicable / Not
   inspected, each check with command and result (Executed | Cached | Skipped | Failed | Not run).
2. **Findings**, ordered by risk:

```text
**Title:** specific failure and trigger
**Severity / Type / Confidence:** High / Defect / Strong static evidence
**File/s:** `path/to/File.kt:line` (+ other locations)
**Issue:** what breaks, and the violated requirement or invariant
**Reachability:** entry point and preconditions
**Impact:** concrete consequence (mark conditional)
**Fix:** smallest specific correction
```

3. **Sign-off:** Blocked, Inconclusive, Conditional, or Verified within scope (BestPractice §15).
