# Production Android Audit: Correctness, Data Integrity, and Reliability

## Role and objective

Audit the requested Android code for **demonstrable defects**, prioritizing:

1. Data loss, corruption, unintended deletion, and incorrect synchronization.
2. Security vulnerabilities, crashes, ANRs, and resource leaks.
3. Lifecycle, concurrency, recovery, and background-execution failures.
4. Measurable performance problems.
5. Explicit project-convention violations.

Do not equate “best practice” with a defect. Every finding must cite an explicit project requirement
or explain a concrete failure mechanism.

Treat supplied architecture and dependency versions as **claims to verify**, not established facts.

## Scope and operating rules

- Inspect before editing.
- Audit only the requested scope, plus dependencies needed to establish correctness.
- Start with findings. Apply fixes only when requested.
- Choose the smallest correct fix; preserve behavior unless behavior is defective.
- Do not introduce architectural layers, libraries, or storage migrations without approval.
- Ask before changing deletion semantics, conflict resolution, persistence formats, identity
  handling, or synchronization policy.
- Distinguish static evidence, reproduced behavior, and unverified hypotheses.
- Never claim a build, test, benchmark, or device check ran unless it actually ran.
- If tools, source files, or devices are unavailable, state the limitation.
- Never run destructive tests against production user data.

## Project conventions

For new or modified code:

- Kotlin only; no new Java files.
- Compose for new UI unless extending an existing View-based screen.
- Coroutines and Flow; no new RxJava, raw threads, or `GlobalScope`.
- Preserve MVVM + Repository and existing Dagger 2 integration.
- Expose immutable ViewModel state, normally `StateFlow`.
- Hoist UI state; use lifecycle-aware collection.
- Put user-visible strings in resources and reuse theme tokens.
- No `!!`; justify `lateinit` outside DI and tests.
- Use `.use { }` for owned closeable resources. Do not close borrowed resources.
- Inject dispatchers where needed; do not introduce scopes merely to silence warnings.

Convention-only findings are not runtime defects.

## Severity and confidence

| Severity      | Criteria                                                                                                   |
|---------------|------------------------------------------------------------------------------------------------------------|
| **Blocker**   | Credible data loss/corruption, security exposure, crash, ANR, resource leak, or prohibited main-thread I/O |
| **Violation** | Explicit project requirement is broken without demonstrated runtime harm                                   |
| **Nit**       | Local naming or style issue; report only in touched files                                                  |

For every finding, record confidence: **confirmed**, **strong static evidence**, or **needs
verification**.

Do not describe hypothetical harm as confirmed. Prioritize blockers by impact, likelihood, and
affected users.

## 1. Establish the project baseline

Inspect:

- `settings.gradle.kts`, root/module build files, version catalog, Gradle properties.
- Build variants, merged manifests, manifest placeholders, network security config.
- Kotlin, AGP, SDK levels, Compose compiler/BOM, Material, navigation, lifecycle.
- Dagger, OkHttp, Gson, kotlinx.serialization, WorkManager.
- Native build scripts, NDK and Go versions, ABI outputs, CI caching.
- R8 configuration, lint suppressions, tests, benchmarks, debug diagnostics.

Use versions actually declared and resolved. Verify compatibility against authoritative
documentation when available.

Map relevant entry points:

- Application, Activities, Services, Workers, Receivers, Providers.
- ViewModels, repositories, navigation destinations and scopes.
- Native process ownership, REST client, event polling.

Trace the relevant flow:

```text
UI action
  -> ViewModel
  -> Repository or existing RestApi boundary
  -> OkHttp
  -> local Go core
  -> acknowledgement / event / reconciliation
  -> durable state
  -> UI
```

Document where authoritative state lives and which component owns each resource.

## 2. Data integrity and loss prevention

Prioritize these paths:

- Folder/device creation, modification, and removal.
- Synchronization start/stop and run-condition changes.
- Configuration import/export and migrations.
- Identity, certificates, API keys, and preferences.
- Camera/QR results and user-selected documents.
- Interrupted writes, retries, process death, storage exhaustion.

For each mutation, answer:

1. What is the source of truth?
2. When is the operation considered committed?
3. Can interruption leave partial or inconsistent state?
4. Can retry duplicate or reverse the operation?
5. Can concurrent operations overwrite newer data?
6. How does recovery detect and repair an incomplete operation?

### Persistence

Verify:

- Critical writes report success only after the required durability boundary.
- Serialization or validation failure cannot replace valid data with defaults.
- Writes handle disk-full, permission, stream-close, and unavailable-storage errors.
- Related values cannot become inconsistent across separate writes.
- Read-modify-write operations are protected against lost updates.
- Backups and migrations are validated before replacing active data.
- Failed migration preserves the original recoverable state.
- Secrets and device identity are not silently regenerated after a recoverable read error.

For app-owned files, assess temporary-file writes, flush/sync requirements, and atomic replacement
where supported. For document providers, do not assume rename or replacement is atomic.

`SharedPreferences.apply()` has no persistence result. `commit()` reports success but must run
off-main. Moving a write off-main alone does not make a multi-step operation atomic.

### Import/export

Verify:

- Import size and content are validated before activation.
- Archive extraction, if present, prevents traversal and unsafe overwrites.
- Active synchronization cannot race with configuration replacement.
- Failure preserves the previous usable configuration.
- Export does not expose credentials or private identity unintentionally.
- Partial exports are not presented as completed backups.
- URI access remains valid for the operation’s lifetime.

### Deletion and synchronization

Verify:

- Distinguish removing a folder from configuration from deleting its contents.
- Destructive actions target stable IDs, not stale list positions.
- UI confirmation accurately describes local and remote consequences.
- Cancellation, retries, and offline recovery cannot unexpectedly repeat deletion.
- Conflict resolution cannot silently overwrite newer state.
- Optimistic UI updates reconcile or roll back after failure.
- Concurrent local and remote edits follow an explicit conflict policy.

Never change destructive semantics without approval.

## 3. REST, events, and concurrency

Verify:

- Blocking HTTP calls and stream reads do not run on main.
- Owned OkHttp responses and response bodies close on every path.
- HTTP status and parsing failures are handled before reporting success.
- Timeouts and coroutine cancellation terminate outstanding requests where appropriate.
- Non-idempotent mutations are not blindly retried after ambiguous failures.
- Read-modify-write API updates use supported concurrency controls or explicitly handle conflicts.
- Duplicate, stale, missing, and out-of-order events are handled safely.
- Reconnection reconciles authoritative state rather than assuming no events were missed.
- Reconnect attempts use bounded backoff and stop when their owner stops.
- Events from an old connection or process generation cannot overwrite current state.

A `SharedFlow` is not durable delivery. A `Channel` is not an exactly-once transaction mechanism.

Inspect the actual Go API contract before recommending retry or conflict behavior.

## 4. Native subprocess lifecycle

The Go `.so` is executed as a child process, not loaded through traditional JNI.

Trace ownership through startup, shutdown, failure, cancellation, and app-process death.

Verify:

- Concurrent starts cannot create duplicate cores.
- Readiness is established through a meaningful health check or connection.
- Startup waits are bounded and cancellation-aware.
- Standard output/error are drained without deadlocks or unbounded buffering.
- Graceful shutdown has a deadline and an API-compatible forced fallback.
- Blocking waits run off-main.
- Streams, monitoring jobs, and long-polling connections close after termination.
- Cleanup targets the owned child, not an unrelated process matching a name or port.
- Restart handles existing children and occupied ports safely.
- Abrupt parent death is tested rather than assumed to kill the child.
- Forced termination does not bypass an available safe shutdown path unnecessarily.

Verify native build inputs and cache keys include relevant source, toolchain, target ABI, and build
flags.

## 5. Lifecycle, coroutines, and resource ownership

Verify:

- Work belongs to an appropriate ViewModel, lifecycle, service, or application scope.
- Service-owned work does not depend on a shorter-lived screen scope.
- `CancellationException` is rethrown unless cancellation is deliberately handled and propagated.
- Cancellation cannot leave a mutation half-applied without recovery.
- Callback registrations, receivers, observers, sockets, and jobs have symmetric cleanup.
- ViewModels do not retain Activities, Views, or short-lived contexts.
- Shared clients are not shut down by consumers that do not own them.
- UI-bound flows stop unnecessary upstream work when unobserved.
- `stateIn` sharing policy matches ownership and required behavior.
- Callback flows unregister external callbacks on cancellation.
- `flatMapLatest` does not cancel writes or independent operations that must complete.

Do not flag long-running collection or eager sharing without examining the intended lifetime.

## 6. Main thread and performance

Trace execution context, not just function names.

Check for:

- Blocking network, filesystem, process, and preference operations.
- Large JSON parsing, bitmap processing, hashing, compression, sorting.
- Startup initialization and lifecycle callbacks that perform expensive work.
- Unbounded event processing, queues, buffers, and parallel requests.
- Lock contention and synchronous waits involving the main thread.

Use:

- `Dispatchers.IO` for blocking I/O.
- `Dispatchers.Default` for CPU-heavy work.
- Existing asynchronous APIs without unnecessary dispatcher hopping.

Verify debug StrictMode coverage. Record measurements or strong code-level evidence for performance
findings.

## 7. UI state and navigation

Verify:

- Compose collects state with `collectAsStateWithLifecycle`.
- Views collect using lifecycle-aware APIs such as `repeatOnLifecycle`.
- Durable user expectations are matched by appropriate saved state or persistence.
- Drafts, selections, and pending actions survive required recreation scenarios.
- `remember` and effect keys avoid stale captures and unintended restarts.
- `rememberUpdatedState` is used where an effect should retain its lifetime but read current values.
- Side effects do not execute directly during composition.
- ViewModels have the intended destination, graph, Activity, or service lifetime.
- Lazy items use stable unique keys when identity or ordering matters.
- UI does not report success before the underlying mutation is acknowledged.
- Accessibility labels, touch targets, and error feedback support the actual controls.

Report instability and recomposition only when they violate an explicit requirement or affect a
measured hot path. Do not mechanically add memoization.

## 8. Background execution and security

Verify against the actual SDK levels and execution paths:

- Foreground service type, permissions, justification, and startup timing.
- Background-start restrictions and exception handling.
- Notification permission handling without confusing it with FGS startup permission.
- Worker cancellation, deadlines, retry policy, and duplicate execution.
- Wake-lock ownership, timeout, and idle release.
- Receiver export status, caller validation, and permission guards.
- Exact-alarm access only if exact alarms are used.
- API-level guards for APIs above `minSdk`.
- Local REST binding, authentication, certificate validation, and hostname behavior.
- Trust exceptions cannot apply to external destinations or unsafe redirects.
- Logs, exports, backups, and crash reports do not leak credentials.
- Release-only serialization behavior is tested with R8 enabled.

Do not assume Android network security settings govern networking performed by the Go subprocess.

## 9. Targeted static searches

Use searches to identify candidates, not to declare defects. Adapt paths to the inspected module
structure.

```bash
rg -n --glob '*.kt' \
  '!!|lateinit|GlobalScope|runBlocking|Thread\.sleep|AsyncTask' \
  app/src/main

rg -n --glob '*.kt' \
  'catch\s*\([^)]*:\s*(Exception|Throwable)|runCatching' \
  app/src/main

rg -n --glob '*.kt' \
  'commit\(|apply\(|fromJson|toJson|delete\(|deleteRecursively|renameTo' \
  app/src/main

rg -n --glob '*.kt' \
  'ProcessBuilder|waitFor|destroyForcibly|EventsPoller|execute\(' \
  app/src/main

rg -n --glob '*.kt' \
  'registerReceiver|FileObserver|WakeLock|FileInputStream|FileOutputStream' \
  app/src/main

rg -n --glob '*.kt' \
  'MutableStateFlow|SharingStarted|callbackFlow|channelFlow|flatMapLatest' \
  app/src/main
```

Inspect surrounding code, callers, dispatcher context, and cleanup paths before reporting.

## 10. Verification

Run supported tasks and record results:

```bash
./gradlew :app:assembleDebug
./gradlew :app:lint
```

After fixes, rerun relevant checks and reproduce the original failure. Inspect the minified release
variant when serialization, reflection, or resource lookup is involved.

### Data-integrity fault tests

Use disposable folders, isolated device identities, and test configurations.

Exercise:

- Process termination before, during, and after a write.
- Lost connection after the server commits but before the client receives a response.
- Duplicate mutation requests and duplicate events.
- Concurrent edits and stale UI submissions.
- Malformed imports, interrupted exports, disk-full, and permission failures.
- Native startup failure, restart, and shutdown during active synchronization.
- Activity recreation separately from app-process death.

For every scenario, define expected invariants:

- Previously committed data remains recoverable.
- Failed operations do not appear successful.
- Retries do not create unintended duplicate effects.
- Pending work is recoverable or explicitly reported as incomplete.
- Restart reconciles with authoritative state.
- No orphan process, leaked resource, or idle wake lock remains.

Use LeakCanary, Perfetto, profilers, and device diagnostics when available. Report observed results,
not universal “zero leak” or frame-time guarantees.

## Required audit output

### Scope and limitations

List inspected files, variants, relevant versions, assumptions, and unavailable checks.

### Findings, ordered by risk

For each finding provide:

- **ID, severity, confidence**
- **Location:** file and line range
- **Failure:** exact defect and triggering conditions
- **Impact:** affected data, users, or resources
- **Evidence:** control flow, API contract, reproduction, or measurement
- **Smallest fix**
- **Verification:** regression test or reproduction steps
- **Trade-off:** only if behavior or migration decisions require approval

### Verification status

Separate:

- Static inspection completed
- Build/lint/test results
- Runtime checks completed
- Checks not performed

### Sign-off

Use one of:

- **Blocked:** unresolved confirmed blockers.
- **Conditional:** static checks passed, but required runtime verification remains.
- **Verified within scope:** named checks passed for the stated scenarios.

If no defects are established, say **“No confirmed findings within the inspected scope.”** Do not
invent findings to fill the report.