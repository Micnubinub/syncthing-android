# Engineering Guidelines — Syncthing for Android

## Project Overview

A native Android wrapper around the Syncthing Go binary. Kotlin + Jetpack Compose app that
bundles a cross-compiled `libsyncthing.so` and talks to it over its local REST API.

| Path                                   | Purpose                                                                                         |
|----------------------------------------|-------------------------------------------------------------------------------------------------|
| `app/`                                 | The Android app (`com.micnubinub.syncthing`)                                                    |
| `app/src/main/java/.../activities/`    | Activities (thin hosts; UI lives in Compose screens)                                            |
| `app/src/main/java/.../ui/screens/`    | Compose screens, one per activity/fragment (`*Screen.kt`)                                       |
| `app/src/main/java/.../ui/viewModels/` | ViewModels (`*ViewModel.kt`) with `State`/`Action` types                                        |
| `app/src/main/java/.../ui/components/` | Shared composables                                                                              |
| `app/src/main/java/.../navigation/`    | Navigation Compose root graph (`RootDestinations.kt`, `RootNavigation.kt`)                      |
| `app/src/main/java/.../onboarding/`    | First-run permission/onboarding pages                                                           |
| `app/src/main/java/.../service/`       | `SyncthingService`, `RestApi`, `EventProcessor`, `RunConditionMonitor`, etc.                    |
| `app/src/main/java/.../http/`          | OkHttp request wrappers and TLS trust for the local REST API                                    |
| `app/src/main/java/.../model/`         | Gson data classes mirroring Syncthing REST/config JSON                                          |
| `app/src/main/java/.../util/`          | `ConfigXml`, `ConfigRouter`, `FileUtils`, `Util`, extensions                                    |
| `app/src/main/res/values/strings.xml`  | Default strings; `values-*/` are translations                                                   |
| `app/src/main/play/`                   | Play Store listing text and release notes                                                       |
| `syncthing/`                           | Gradle module that builds the native Go binary (`buildNative`)                                  |
| `syncthing/src/`                       | Vendored Syncthing sources — generated, never edit                                              |
| `scripts/*.go`                         | Build tooling: `initDevEnv`, `installSyncthing`, `updateSyncthing`, `buildSyncthing`, `release` |
| `gradle/libs.versions.toml`            | Single source of truth for SDK/NDK/Go/app versions and all dependencies                         |
| `.github/workflows/`                   | CI (`build.yaml`, `recycle-runs.yml`)                                                           |
| `wiki/`                                | User-facing documentation                                                                       |
| `outputs/`                             | Audit/agent reports, not shipped                                                                |

**Stack**: Kotlin 2.4, AGP 9, JDK 21 target, Compose Material 3, Navigation Compose,
Dagger 2 (not Hilt), Coroutines/Flow, OkHttp, Gson, WorkManager, CameraX. `minSdk` 24,
`targetSdk`/`compileSdk` 37. Native side: Go + NDK via `ndk-build`.

## Core Principles

1. **Think first**: State assumptions. Ask when uncertain. Flag simpler paths.
2. **Simplicity**: Minimum code. No speculative features.
3. **Surgical**: Touch only what the request requires. Match existing style.
4. **Goal-driven**: Define success criteria. Loop until verified.
5. **Decompose**: Break large tasks into verifiable steps.

## Think Before Coding

- State assumptions explicitly. If the request is ambiguous, present the interpretations and ask;
  never pick one silently.
- Flag a simpler approach when you see one. Push back when warranted.
- If confused, stop and name the confusion. Do not guess.
- Read the surrounding code before writing any. Confirm the existing pattern (DI, navigation, state
  handling) before extending it.

## Simplicity

- No unrequested features, abstractions, config flags, or "future flexibility."
- No error handling for cases that cannot occur.
- If 200 lines could be 50, rewrite.
- Test: "Would a senior Android engineer call this overcomplicated?"

## Surgical Changes

- Every changed line must trace to the request.
- Do not "improve" adjacent code, comments, imports, or formatting.
- Do not refactor what is not broken.
- Match existing naming, module structure, and DI patterns.
- Remove orphans your change created. Leave pre-existing dead code; mention it instead.
- Do not reorder or reformat files the IDE would otherwise touch (e.g. `.idea/`, generated code).

## Goal-Driven Execution

Convert every task into a verifiable goal before starting:

| Request          | Goal                                                   |
|------------------|--------------------------------------------------------|
| "Add validation" | Write tests for invalid inputs, then make them pass    |
| "Fix bug"        | Write a reproducing test, then make it pass            |
| "Refactor X"     | Tests green before and after; behavior unchanged       |
| "Add screen"     | Preview renders, state flows from ViewModel, nav wired |

For multi-step work, state the plan up front as `[Step] -> verify: [check]` and report each check
result.

## Android Conventions

**Language**

- Kotlin only. No new Java files.
- No `!!`. No unchecked casts. The codebase currently has zero `!!`; keep it that way.
- `lateinit` is acceptable only for Dagger field injection (`@Inject lateinit var`) and test setup.
- Prefer `value?.let { name -> ... }` over `if (value != null) { ... }` when the result is used as
  an expression. Plain `if` is acceptable for statement-only branches or when it reads more clearly.
- Anything with `close()` must be wrapped in `use { }` (or `try/finally` when `use` does not
  apply).

**UI**

- Jetpack Compose only. There are no XML layouts; do not add any.
- One screen-level composable per activity/fragment in `ui/screens/`, named `<Host>Screen`.
- Stateless composables where possible; hoist state to the caller or ViewModel.
- Every new screen-level composable gets a `@Preview(showSystemUi = true)` matching existing
  screens.
- No hardcoded dimensions, colors, or strings. Use `MaterialTheme` and `stringResource(...)`.

**Async**

- Coroutines + Flow only. No RxJava, raw threads, `GlobalScope`, or `runBlocking` outside tests.
- Launch from `viewModelScope` / `lifecycleScope`; use `withContext(Dispatchers.IO)` for blocking
  I/O, matching the existing code. Do not introduce a dispatcher-injection abstraction as a side
  effect of another change.
- Collect lifecycle-aware: `collectAsStateWithLifecycle()` in Compose (never plain
  `collectAsState()`); `lifecycleScope` in Activities/Services.

**Architecture (MVVM + unidirectional data flow)**

- Follow the existing ViewModel shape exactly:
    - `data class <Name>State(...)` — immutable UI state.
    - `sealed interface <Name>Action` — every user intent as a `data object` / `data class`.
    - `class <Name>ViewModel : ViewModel()` with `private val _state = MutableStateFlow(...)`,
      `val state: StateFlow<...> = _state.asStateFlow()`, and a single `fun onAction(action)` that
      dispatches via `when`.
- Never expose `MutableStateFlow`, `MutableState`, or `MutableSharedFlow`.
- One-off events (navigation, snackbars, finish) go through a `Channel<<Name>Event>(BUFFERED)`
  exposed via `receiveAsFlow()`, as in `FolderActivityViewModel` / `DeviceActivityViewModel`. Do
  not invent a new mechanism.
- DI is Dagger 2: `SyncthingModule` + `DaggerComponent`, injected through
  `(application as SyncthingApp).component().inject(this)`. Add new injectable types to the
  module/component; do not introduce Hilt or manual singletons.
- Do not introduce new layers (use cases, repositories) unless the request asks for them.
- Syncthing REST/config access goes through `RestApi` / `ConfigRouter` / `ConfigXml`; do not call
  the local HTTP API directly from UI code.

**Resources**

- All user-facing strings in `app/src/main/res/values/strings.xml` with a descriptive key.
- Never edit `values-*/strings.xml` translations by hand; they are managed separately.
- Use theme tokens for colors, typography, and spacing.

## Build, Versions and Generated Files

- All versions live in `gradle/libs.versions.toml`. Never hardcode a version in a
  `build.gradle.kts`.
- `version-name` is `major.minor.patch.wrapper` and `version-code` must equal
  `major*1_000_000 + minor*10_000 + patch*100 + wrapper`; `validateAppVersionCode` fails the build
  otherwise. Bump both together, or use `scripts/updateSyncthing.go`.
- `assembleDebug` triggers `:syncthing:buildNative`, which needs Go (version in the catalog) and
  the NDK from `local.properties` / `ANDROID_HOME`. Run `go run scripts/initDevEnv.go` if tools
  are missing.
- Generated or vendored — never edit, never commit: `syncthing/src/`, `app/src/main/jniLibs/`,
  `build/`, `.gradle/`, `.kotlin/`, `graphify-out/`.
- Secrets and machine config — never read into code or logs: `local.properties`,
  `release.keystore`, `KEYSTORE_*` / `KEY_*` env vars.

## Verification

Run in this order; all must pass before reporting done:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

- There are currently no unit or instrumented tests. When you add the first tests for a class,
  put them in `app/src/test/` (JVM) and keep them free of Android framework dependencies where
  possible.
- Lint is configured with `abortOnError = false` and is not a gate; do not rely on it to catch
  issues.
- Run instrumented tests only when the change touches UI or DB behavior they cover.
- If a check cannot be run (e.g. no Go/NDK on this machine), say so explicitly rather than
  implying it passed.

## Hard Rules

- **No `!!` or unsafe casts.** Justify any legacy exception in a comment.
- **No destructive actions** without explicit request: migrations, deletions, dependency removals,
  schema changes, touching `syncthing/src/`.
- **No new dependencies** unless requested. Prefer AndroidX/Jetpack when approved; add them to
  `libs.versions.toml`, never inline.
- **No version changes** to `min-sdk`, `target-sdk`, `compile-sdk`, `ndk-version`, `go_version`,
  AGP, Kotlin, Compose, or `version-*` unless requested.
- **No secrets or `local.properties` values** in code or logs.
- **Search with `rg`**, never `grep`.
- **Ignore** anything in `.gitignore`: `build/`, `.gradle/`, `local.properties`, `*.iml`,
  `graphify-out/`, `app/src/main/jniLibs/`.
- **Do not commit** unless explicitly asked.

## Reporting

When done, report:

1. What changed and why (one line per file).
2. Which verification steps ran and their results.
3. Anything noticed but intentionally not touched.

## ripwire — deterministic codebase maps (on PATH as `ripwire`)

Reach for it BEFORE blind grep + whole-file reads. First call ~1s cold; after that warm, ~0.1s.
- Orient on a task: `ripwire <dir> --for="<task in words>"` — ranked, quality-annotated
  signatures. Paste symbol/file names from the issue verbatim; named mentions get anchored.
- One task: `--pack-task="<task>" --legend=compact`; before parallel agents: `--plan-lanes=N --task="<goal>"`, then read `lanes[].execution`.
- Have a stack trace / build error: `ripwire <dir> --from-trace=FILE --legend=compact` (`-` = stdin) —
  paste the error, don't paraphrase it into a query.
- Who calls X: `--callers=SYM --legend=compact`. "Is it safe to change X?" needs the full blast radius:
  `--impact=SYM --legend=compact` (transitive) plus `--uses=SYM --legend=compact` (every read/write/import site).
- Apply a whole-symbol edit without a whole-file Read: `--replace-symbol-body=SYM` plus `--edit-payload=FILE|-`
  (or insert-before/after); the receipt carries region, blob_sha, edit_check, tests_to_run + ONE next= — no re-read after it; `--edit-check=SYM --legend=compact` is for a contract question WITHOUT an edit in hand.
- Before writing a new fn/class/helper: `--exemplar="<what you're writing>" --legend=compact` — duplicates are born on small tasks.
- Before calling work done: `--quality-delta --legend=compact` (what you made worse), then `--test-gate --legend=compact`.
- Trust notes: counts marked counts_floor are floors, not totals; a zero means "none found", never "none exists".
- The commands above ask for the compact legend (terse definitions of only the attributes present); add `--legend=full` when a definition's reasoning is needed: a term you do not recognise, a floor or cap you need explained, a map a human will read.

Defaults to break (less context is measurably MORE accurate, not just cheaper — code-repair
accuracy fell 29% -> 3% as context grew 32K -> 256K tokens, LongCodeBench):
- Do NOT open a file you have not located first: rank with `--for`/`--grep`, then read what it names.
- Do NOT read a whole file to understand one symbol: `--expand=SYM --legend=compact` gives the body + callee sigs.
- Do NOT fan reads across several files to learn one thing: `--pack-task="<task>" --legend=compact` is one call.