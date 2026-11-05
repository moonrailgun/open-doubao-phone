# Companion screenshot batches implementation plan

> **Status:** Implementation, fixes and review are complete. The user subsequently authorized staged Git commits for this conversation's changes.

**Goal:** Give companion mode recent visual context while bounding screenshot size and avoiding unnecessary comments.

**Architecture:** Sample independently every second into a five-frame memory buffer. A single request loop consumes manual inputs or fixed observation opportunities, sending the newest `min(5, intervalSeconds)` fresh frames plus bounded dialogue and a rolling summary. Keep the existing screenshot safety checks and optional Jev comparison.

**Tech stack:** Kotlin, coroutines, Android Bitmap, existing Chat Completions/Jev clients, JUnit and Android instrumentation.

**Spec:** Approved conversation: default interval 10 seconds; configurable 1–300 seconds; first automatic request after the interval; no queued automatic requests; silence for uninteresting changes; manual input bypasses change filtering. Preserve saved intervals. Capture and reply timestamps describe sampled evidence only, never unseen gaps or audio.

## Constraints and decisions

- Preserve pre-existing overlay changes and changelog entries; commit only this conversation's changes under the subsequent authorization. Do not push, deploy or call paid models.
- Clear frames and invalidate in-flight results on unavailable screens or app/window/geometry transitions; retain user dialogue across transitions. Stop/restart clears everything.
- Keep the last successful visual baseline across local/Jev skips; successful silence updates both baseline and summary.
- Ruling: exact JPEG equality is the only local change filter; coarse grids can miss subtitles and scores. Jev/model semantic silence handles minor changes, at the cost of more requests for compression noise.
- Ruling: companion-only encoding uses long edge <=1280 px, JPEG quality 75 then 65/55 as needed, and <=200 KiB per JPEG; reduce dimensions further if still too large. No new settings/dependencies; normal task screenshots keep existing encoding.
- Keep three comments, eight user inputs, a 300-character summary, and a 120-character displayed reply. Invalid structured responses must not consume pending input or replace valid context.

## Tasks

- [x] Add failing timing/default tests, then implement independent sampling, bounded batches, freshness/session protection and structured results in `core/CompanionController.kt`; adapt `CompanionControllerTest.kt`.
- [x] Extend `model/CloudModels.kt` and `core/JevVisionProtocol.kt` to send ordered images/timestamps, compare the whole batch in one optional Jev call, and parse `{summary, comment}`; adapt `CompanionModelsTest.kt`.
- [x] Add bounded companion encoding to `phone/AndroidPhone.kt`, wire it and batch validation in `phone/AssistantService.kt`, and add Bitmap instrumentation coverage.
- [x] Update defaults/validation in `model/CompanionConfig.kt`, matching tests, settings copy in `MainActivity.kt`, and the existing companion entry in both changelogs.
- [x] Run focused and full unit tests, lint/debug build, compile instrumentation tests, and changelog validation; review the complete diff. Run device tests only if a test device is available.

## Verification focus

- Busy model requests must not stop capture or create a backlog; manual messages remain ordered and take priority.
- A transient A→B→A event must reach the model; shorter intervals and unavailable samples must never be padded with stale images.
- Lock/app/rotation transitions during comparison or inference must discard stale output, including when returning to the same app.
- Silence must advance context; failures must retain input and safe prior context; model output types and length limits must be validated.
- Compression must preserve aspect ratio, avoid upscaling, obey the byte limit, and preserve masking; normal task screenshots must remain unchanged.

## Execution record

- Read callers, settings, model protocols and existing tests. Saved pre-existing diff at `/tmp/open-doubao-companion-preexisting.patch` for preservation checks.
- Pre-build output inspection: only `os/custom/out/v1.56` (5.4 GiB) remains, no open files/build or flashing process found. Retained this sole version; no redundant version to remove. No Android device connected.
- Implemented independent sampling and serialized requests, bounded fresh batches and dialogue, structured summaries and silence, default interval/settings updates, and companion-only screenshot compression. Added four Bitmap instrumentation tests for dimensions, size, masking and subtitle differences.
- Completed one independent review and fixed its window-transition finding: accessibility/configuration events immediately invalidate pending captures and replies, including A→B→A changes between sampling ticks. Excluded the companion overlay's own window events and kept newly subscribed events out of ordinary task settling updates.
- Verified three event-invalidation regression tests fail before integration and pass afterward; all 24 controller tests then passed. Logs: `/tmp/open-doubao-companion-event-red.log` and `/tmp/open-doubao-companion-event-green.log`.
- Final verification: `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain` exited 0. All 325 tests in 53 suites passed, with no failures, errors or skips. Debug Lint reported 74 warnings and no errors. Log: `/tmp/open-doubao-companion-final-check.log`.
- `python3 scripts/changelog.py check` and `git diff --check` passed. Reconstructed the initial worktree from HEAD plus the saved patch: the existing overlay test is byte-for-byte preserved, each changelog only replaces its existing companion entry, and service additions are limited to this task's wiring and scene validation.
- Device tests were compiled but not run because no device is connected. No live model call or on-device assessment of clarity, compression timing or billing was performed; smaller JPEG bytes do not guarantee proportional token savings.
- No Git staging, commits, deployment or Computer Use performed. Build cleanup removed 0 directories and retained the sole 5.4 GiB rollback candidate; its bootability was not independently verified.

## Fix and re-review record

- Fixed temporary typing/speech pauses clearing the visual summary, recent replies and comparison baseline. Pauses now invalidate screenshots and in-flight results while retaining context; actual scene changes still clear visual context.
- Fixed short observation intervals starving inference when capture takes over a second. Frame timestamps now describe capture completion, and the five-second freshness window is independent of the interval's frame-count limit.
- Re-review found that a message sent before the keyboard finished closing could wait until the next automatic interval. The first successful sample after resume now wakes a pending manual request; provider failures still wait for the normal retry opportunity.
- Added eight controller regression tests, including pause/resume during capture or inference, scene changes during pauses, slow capture, deduplication, and keyboard-close timing. Confirmed failures before each fix, then passing results. Logs: `/tmp/open-doubao-companion-fixes-red.log`, `/tmp/open-doubao-companion-fixes-green.log`, and `/tmp/open-doubao-companion-resume-red.log`.
- Final verification: `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain` exited 0. All 333 tests in 53 suites passed, including 32 controller tests, with no failures, errors or skips. Debug Lint reported 74 warnings and no errors. Log: `/tmp/open-doubao-companion-fixes-final.log`.
- A Pixel 6 API 34 emulator became available. All four `CompanionScreenshotTest` instrumentation tests passed: dimensions/no upscaling, high-entropy size limits, window/overlay masking, and encoded subtitle differences. Log: `/tmp/open-doubao-companion-fixes-device.log`. This installed the debug app and test APK on the emulator; there was no physical-device installation or deployment.
- Re-reviewed the controller, model calls, screenshot handling, service input/speech lifecycle and window events; no further blocking defects identified. Updated the existing companion entry in both changelogs. Other changelog entries and concurrent overlay work were preserved. The overlay regression test changed in a separate actor's commit during this task, so it is no longer byte-identical to the pre-fix snapshot; this task did not edit it.
- No staging or commits by this agent. Build-output checks removed 0 directories and retained `os/custom/out/v1.56` (5.4 GiB), the sole rollback candidate with bootability unverified; no build/flash process or file use prevented cleanup. Last disk check showed 16 GiB available.
- No live-model calls or physical-device end-to-end checks. Keyboard/service event timing and perceived compression clarity still need real-device validation; reduced image bytes do not establish model-token savings.

## Staged delivery record

- The user subsequently authorized small, independently usable commits for this conversation's changes. Split the delivery into observation defaults, continuous screenshot context with lifecycle fixes, and screenshot compression with its instrumentation tests and this ledger.
- Export each proposed Git tree into an isolated snapshot and validate that exact state before staging it. Check the committed tree and parent afterward, and preserve the final worktree file bytes throughout shared-file staging.
- Stage 1, `cc19dc1` (`feat(android): shorten companion observation intervals`): 320 unit tests passed; Debug Lint reported 74 warnings and no errors; Debug build, instrumentation compilation, changelog validation and whitespace checks passed.
- Stage 2, `b7389ea` (`feat(android): add continuous companion screenshot context`): 333 unit tests passed; Debug Lint reported 73 warnings and no errors; Debug build, instrumentation compilation, changelog validation and whitespace checks passed. Timing, pause/resume and stale-result fixes remain together with batching.
- Stage 3 contains companion-only screenshot compression, its instrumentation tests, matching settings/changelog wording, and this ledger. Apply the same isolated-tree checks before committing. No Android device is currently connected, so device tests from the earlier fix/re-review record are not rerun during staged delivery.
