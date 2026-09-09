# Engineering Guidelines (Android)

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
- No `!!`. No unchecked casts. Justify any `lateinit` outside DI or test setup.
- Prefer `value?.let { name -> ... }` over `if (value != null) { ... }` when the result is used as
  an expression. Plain `if` is acceptable for statement-only branches or when it reads more clearly.
- Anything with `close()` must be wrapped in `use { }` (or `bracket`/`try/finally` when `use` does
  not apply).

**UI**

- Jetpack Compose for new UI. No new XML layouts unless the screen already uses Views.
- Stateless composables where possible; hoist state to the caller or ViewModel.
- Every new screen-level composable gets a `@Preview`.
- No hardcoded dimensions, colors, or strings. Use `MaterialTheme` and `strings.xml`.

**Async**

- Coroutines + Flow only. No RxJava, raw threads, `GlobalScope`, or `runBlocking` outside tests.
- Inject dispatchers; never hardcode `Dispatchers.IO` inside a class.
- Collect lifecycle-aware: `repeatOnLifecycle` in Views, `collectAsStateWithLifecycle` in Compose.

**Architecture**

- Follow the existing pattern (e.g. MVVM + Repository). Do not introduce new layers.
- ViewModels expose `StateFlow`/immutable state only. Never expose `MutableStateFlow` or
  `MutableState`.
- One-off events (navigation, snackbars) go through the existing event mechanism; do not invent a
  new one.

**Resources**

- All user-facing strings in `strings.xml` with a descriptive key.
- Use theme tokens for colors, typography, and spacing.

## Verification

Run in this order; all must pass before reporting done:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

- Run instrumented tests only when the change touches UI or DB behavior they cover.
- If a check cannot be run, say so explicitly rather than implying it passed.

## Hard Rules

- **No `!!` or unsafe casts.** Justify any legacy exception in a comment.
- **No destructive actions** without explicit request: migrations, deletions, dependency removals,
  schema changes.
- **No new dependencies** unless requested. Prefer AndroidX/Jetpack when approved.
- **No version changes** to `minSdk`, `targetSdk`, `compileSdk`, AGP, Kotlin, or Compose BOM unless
  requested.
- **No secrets or `local.properties` values** in code or logs.
- **Search with `rg`**, never `grep`.
- **Ignore** anything in `.gitignore`: `build/`, `.gradle/`, `local.properties`, `*.iml`.

## Reporting

When done, report:

1. What changed and why (one line per file).
2. Which verification steps ran and their results.
3. Anything noticed but intentionally not touched.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
