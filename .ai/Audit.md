# Code Audit Guide

Audit this repository for logic errors, security flaws, and best-practice
violations. This is a public Android app that wraps Syncthing (its REST API and
GUI). Prioritize anything exploitable, abusable, or that leaks sensitive data.

## Scope

- Read every file fully before flagging. Never infer unread content.
- Skip:
  - Paths matching `Audit*`, `audit*`, `outputs/**` (prior reports).
  - Paths ignored by `.gitignore`.
  - Lockfiles, generated code, vendored/third-party dirs, unless wrapper code
    modifies or misconfigures them.
- Out of scope (local-only, user-controlled by design): zip handling, at-rest
  encryption, plaintext passwords in local storage, user-invoked scripts.
  Do not report these.

## Focus areas

- **Input handling:** unvalidated data crossing trust boundaries; path
  traversal via file/folder names; injection into shell, intents, or REST calls.
- **Android surface:** exported activities/services/receivers/providers without
  permission checks; intent data used unvalidated; WebView config (JS bridges,
  `setAllowFileAccess`, mixed content); cleartext traffic; API key or
  Syncthing GUI credentials logged, in intents, or in world-readable storage.
- **Syncthing API wrapper:** API key handling; TLS/cert validation for local
  REST calls; requests built from untrusted input.
- **Coroutines:** blocking calls that should be `suspend`; `GlobalScope` or
  scopes not tied to lifecycle; blocking inside coroutines; missing
  cancellation or structured concurrency.
- **Best Practice:** apply checks defined in @BestPractice.md

## Severity rubric

- **Critical:** remote or unauthenticated compromise, secret exposure, RCE.
- **High:** local privilege escalation, auth bypass, sensitive data exposure
  with preconditions.
- **Medium:** best-practice violation with plausible but limited exploit path.
- **Low:** robustness or style issue, defense-in-depth gap, no direct exploit.

## Rules

- Extremely concise. Sacrifice grammar for brevity.
- No summary, tables, or emojis.
- One finding per issue. Merge duplicates into one entry listing all
  file/line refs.
- Report only issues grounded in code you read. No speculative line numbers,
  no fabricated CVE IDs.
- Omit any severity section with zero findings.
- Order: Critical, High, Medium, Low.
- Only touch the file you generated. Do not touch any other files

## Finding format (repeat per finding)

**Severity:** Critical | High | Medium | Low
**File/s:** `path/to/file` - `line(s)`
**Issue:** one-line description
**Impact:** concrete consequence
**Fix:** specific, actionable correction

## Output

1. Run `mkdir -p outputs`.
2. Generate name: `outputs/Audit$(openssl rand -hex 4).md`. If it exists,
   regenerate.
3. Create the file before starting the audit. Append findings as discovered.