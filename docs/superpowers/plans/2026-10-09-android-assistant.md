# Android Jev assistant implementation

Spec: `../specs/2026-10-08-android-assistant-design.md` plus the user's subsequent elements-first / Jev request.

Build one phone-local Kotlin APK: Accessibility nodes first; a single planner call supplies task text and subgoals; Jev chooses a concrete local candidate and judges the previous effect in one request; a vision-capable chat-completions model handles planning, ambiguity and final summaries. No PC runtime, backend, root or multi-agent loop.

## Global constraints

- Work on `feat/android-jev-assistant`; no commits or external delivery requested.
- Keys encrypted with Android Keystore; no backups, cleartext endpoints, payload logging or screenshot persistence.
- The service owns one cancellable coroutine; stale observations and cancelled generations never dispatch actions.
- Only task-selected apps may be observed/uploaded. Password/locked screens pause. Unverified page actions need a one-time on-device user approval, scoped to the exact observed action; read, scroll and opening selected apps can run automatically. Browser navigation also requires confirmation until physical app/version profiles can be verified.
- Keep all 3 task types possible; do not substitute a mock model as the shipped implementation. Debug fixtures are for reproducible device tests only.

## Task 1: project and decision boundaries

Create Gradle wrapper, manifest, core observation/action models, bounded JSON parsers, Jev protocol, local candidate generation and action policy under `android/`. Write tests first for invalid probabilities, unknown choices/actions, malformed JSON, stale screens, password fields and unauthorized targets. From `android/`, run `./gradlew testDebugUnitTest`: first RED, then GREEN.

## Task 2: local loop and network

Implement cancellable HTTPS clients, one-time plan, batched Jev next-action/effect/completion, vision fallback, bounded history and summary; coroutine task states, stop/resume generation, timeout/step/no-progress bounds. Test a late response after stop, manual page changes on resume, no-progress pause, failed actions and bounded run. Protocol HTTP tests use MockWebServer, never real credentials.

## Task 3: Android execution and interaction

Build node observation/execution, screenshot API 30/34, fresh-window checks, gesture callbacks, overlay pause/stop/approval, lifecycle/lock handling, encrypted settings, task history and Compose task/config/results UI with system voice input. Instrument real Android operations using an isolated debug fixture app surface; never automate unrelated apps during tests.

## Task 4: integration and delivery

Run unit tests, lint, debug assemble, install and instrumentation on the available emulator. Inspect rendered app screenshots via adb, exercise permission/config/task/stop flows. Build user APK and README with configuration, limitations, device verification gaps and evidence. Review whole change with a fresh reviewer as required by executing-plans, fix material findings, rerun relevant checks. Physical Samsung and live model validation depend on device/credentials being available; clearly record any gaps.

## Review focus

Cancellation between network completion and action dispatch; stale node coordinates; sensitive text or keys in logs/history/backups; unsafe actions disguised as safe candidates; response-size and timeout bounds; Android service recreation and screenshot cleanup; no loss of existing history on failed writes.
