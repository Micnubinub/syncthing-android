# Code Audit Guide

Audit this repository for logic errors, security flaws, and best-practice violations. This is a
public Android app that wraps Syncthing: it runs `libsyncthingnative.so` as a child process and
drives it over the local REST API and an embedded WebView GUI. Prioritize anything exploitable,
abusable, data-destroying, or that leaks sensitive data.

Classification, severity, confidence, and review criteria come from `.ai/BestPractice.md`
(§2 classification, §4 project profile, §6–§12 checks). This file defines process and output.

## Process

1. Create the report: `mkdir -p outputs`, then pick
   `outputs/Audit$(openssl rand -hex 4).md`; regenerate if the name exists. Create it now and
   append findings as discovered.
2. Baseline: `git rev-parse HEAD`, `git status --short`, `git diff --stat`,
   `git log --oneline -20`. Record revision and dirty state in the report.
3. Orient before reading: `ripwire app/src/main/java --for="<audit focus>"` (or
   `graphify query "<question>"` when `graphify-out/graph.json` exists). Then read every in-scope
   file on the paths you trace fully; do not judge a symbol from a snippet.
4. Trace flows end to end: Compose screen -> ViewModel -> `RestApi` / `ConfigRouter` /
   `ConfigXml` -> `http/ApiRequest` + `SyncthingHttpClients` -> native core (`SyncthingRunnable`)
   -> `EventsPoller` -> `EventProcessor` -> persisted state -> UI state.
5. Run the candidate searches below. Searches yield candidates, not verdicts; check callers,
   dispatcher context, ownership, and cleanup paths before reporting.
6. Verify, from the repository root:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

State explicitly if these could not run (e.g. missing Go/NDK). No unit tests exist yet, so a
green `testDebugUnitTest` is not behavioral evidence.

## Scope

- Skip:
    - `Audit*`, `audit*`, `outputs/**`, `.ai/**` (prior reports and prompts).
    - Paths ignored by `.gitignore`.
    - Generated, vendored, and third-party code unless wrapper code misconfigures it:
      `syncthing/src/`, `app/src/main/jniLibs/`, `graphify-out/`, `build/`, `.gradle/`,
      `.kotlin/`, `*.iml`.
    - `values-*/strings.xml` translations.
- Never read secrets or machine config into notes or logs: `local.properties`,
  `release.keystore`, `KEYSTORE_*` / `KEY_*` env vars.
- Out of scope (local-only, user-controlled by design): zip handling, at-rest encryption,
  plaintext passwords in local storage, user-invoked scripts. Do not report these.
- Only write the file you generate. Do not modify any other file.

## Focus areas

- **Exported surface:** `MainActivity`, `ShareActivity` (`SEND` / `SEND_MULTIPLE` extras and
  URIs), `AppConfigReceiver` (`CONFIG_APP` permission and its `protectionLevel`), QS tiles,
  `FileProvider` paths. Check the merged manifest, not only the source manifest.
- **Local API and TLS:** API key handling and logging; loopback pinning in `ApiRequest`; custom
  trust in `SyncthingHttpClients`; redirects or host rewrites that could send the key or the trust
  exception to a non-loopback host; requests built from untrusted input; response closing.
- **Native subprocess (`SyncthingRunnable`, `SyncthingService`):** duplicate starts, unbounded or
  non-cancellable waits, API 24–25 fallback paths, orphaned core after app-process death, cleanup
  targeting the wrong process or port, unbounded stdout/stderr buffering, crash loops.
- **Config and data integrity:** `ConfigXml` edits racing the running core; identity or API key
  regenerated after recoverable read errors; folder/device removal versus data deletion;
  import/export completeness.
- **Coroutines and lifecycle:** blocking calls on main; `GlobalScope` or scopes outliving their
  owner; swallowed `CancellationException` in broad catches or `runCatching`; `callbackFlow`
  listeners (e.g. `settings/SharedPreferenceFlow.kt`) not unregistered; `EventsPoller` stopping
  with its owner.
- **Background execution:** `specialUse` FGS start eligibility (boot, background),
  `BootReceiver`, `SyncTriggerJobService` / `JobUtils` unique-work policy, wake/Wi-Fi/multicast
  lock release, `RunConditionMonitor` receivers.
- **Platform config:** `network_security_config.xml` (user CAs limited to loopback),
  `data_extraction_rules.xml`, `allowBackup`, requested permissions versus actual use.
- **Conventions:** apply `.ai/BestPractice.md` §4.1 to touched or reviewed code as
  `Requirement violation` / `Style violation`, severity Low unless runtime harm is established.

## Areas you can ignore

- Path traversal
- unencrypted exports

## Rules

- Concise. Sacrifice grammar for brevity. No emojis, no executive summary.
- One finding per root cause; list every file/line where it manifests.
- Report only issues grounded in code you read or checks you ran. No speculative line numbers, no
  fabricated CVE IDs. Never describe a hypothesis as confirmed.
- Smallest correct fix; preserve behavior unless defective.
- Order by severity (Critical, High, Medium, Low), then impact and likelihood. Omit empty
  severity sections.

## Candidate searches

```bash
rg -n -g '*.kt' '!!|GlobalScope|runBlocking|Thread\.sleep|AsyncTask' app/src
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

## Finding format (repeat per finding)

**Title:** specific failure and trigger
**Severity:** Critical | High | Medium | Low
**File/s:** `path/to/File.kt:line`
**Issue:** one-line description, including the violated requirement or invariant
**Reachability:** entry point and preconditions
**Impact:** concrete consequence
**Fix:** smallest specific correction

## Output

Append to the generated file, in order:

1. **Scope:** revision and dirty state; inspected modules/files; variants; toolchain; areas marked
   Inspected / Not applicable / Not inspected with reasons; assumptions.
2. **Findings**, ordered by risk (format above).
3. **Verification status:** per check, the command, result (Executed | Cached | Skipped | Failed |
   Not run), exit status, and limitation. Separate environment failures from app failures.
4. **Sign-off:** **Blocked** (unresolved Blocking finding), **Inconclusive** (essential scope or
   evidence unavailable), **Conditional** (runtime verification or decisions remain), or
   **Verified within scope**. If no defect is established, write
   **"No confirmed findings within the inspected scope."** and list unresolved candidates
   separately. Do not invent findings to fill the report.
