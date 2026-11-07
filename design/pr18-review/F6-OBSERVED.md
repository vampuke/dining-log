# F6 observed install transaction ownership

Candidate base: `37ac1eb71848cd6c31a79e3d2118d2d2d36b24c0`, branch `fix/pr18-f6-install`.
Real Android RED: checkpoint `02d5ff9d26231b1d06eb0f069c4a61e93343fe2e`, [Actions run 42360683696](https://github.com/vampuke/dining-log/actions/runs/42360683696).
The manager and `Pr18InstallTransactionTest.kt` at the candidate base are byte-identical to that executed checkpoint (SHA-256 `8b200e696c2d9144d9245486cb5ae139890e24102dcf20dd33981791f2d8a0d9` and `4fe28c595559123ba9d75f82a62d2394c79f488723235858dd8db6923966e3f4`, respectively).

## Actual RED read before production edits

Read all twelve F6 full-stack records in `/tmp/dining-release-20261107/verified-full-stacks-42360683696.json`, both complete `Pr18InstallTransactionTest.html` reports, all 27 transaction/checker/job diagnostic records per API in the actual `pr18-measurements-api35.txt` and `pr18-measurements-api26.txt`, and the complete production-manager instrumentation test.
Reports and measurements are under `/tmp/dining-release-20261107/ci-evidence-42360683696/android-auth-api-{35,26}/`.
Both APIs ran seven tests: six failures, one passing cancellation/manual-selection control, zero skipped tests. The same assertions and state/count evidence failed on both APIs.

| Exact `Pr18InstallTransactionTest` method | Observed result on API 35 and API 26 |
| --- | --- |
| `f6DelayedAutomaticBDoesNotReplaceReadyAOrPreventInstall` | Expected selected A (code 11), got B (12); A's verified APK disappeared and state became B/IDLE. Failure at `assertOwnedByA:82`, called at line 92. |
| `f6DelayedAutomaticBDoesNotReplacePermissionTransactionAOnReturn` | One A permission request preceded automatic B selection. The return rechecked B's missing file, producing B/FAILED with two verifications and no installer call. Expected A, got B at line 82, called at line 113. |
| `f6DelayedAutomaticBDoesNotSweepInstallingAOrReceiveAsynchronousFailureFromA` | B replaced A while A's verification was held; A's APK was swept. Resuming A produced B/FAILED with no installer call. Expected A, got B at line 82, called at line 132. |
| `f6RepeatedInstallTapsOwnOneJobAndOnePermissionRequest` | Expected one job, got three at line 147; diagnostics show three verifications and three permission requests. |
| `f6RepeatedPermissionReturnInRecreatedOwnerOwnsOneJobAndInstallerIntent` | Expected one return job, got three at line 166; four total verifications, one permission request and three installer calls for A. |
| `f6ExplicitCancelAndManualBSelectionCannotReceiveStaleAInstallerCompletion` | Explicit cancel/manual B selected B/IDLE before releasing A's verification. A then changed B to FAILED; expected IDLE, got FAILED at line 192. |
| `cancelRetryAndExplicitManualSelectionRemainUsableWithoutAutomaticDownload` | Passed. Manual B stays IDLE without automatic I/O, then becomes READY only after explicit download; service/connection counts increase from one to two. |

Each setup reached A/READY with 31/31 downloaded bytes, a real SHA-verified fixture file, one foreground-service start and one transport connection. The checker result was actually delayed, `manual=false`, release B, with two gateway requests. These are executed production `InAppUpdates` regressions with controlled instance I/O and verification barriers, rather than a substitute manager or a device GREEN claim.

## Cause and lifecycle trace

`AppUpdateChecker` publishes the delayed automatic release through `AppUpdateState`. `MainActivityContent` supplies `automatic=true` to the manager; Settings uses the explicit one-argument `open`. The manager ignores provenance and uses `UpdateInstallState.busy` as its only selection/job guard. That DTO deliberately covers DOWNLOADING and VERIFYING, so READY remains installable; NEED_PERMISSION and INSTALLING also pass the guard. Opening B immediately sweeps A's APK and replaces the entire state.

`install` captures A but launches every call in the singleton's Main.immediate scope, independently of Activity recreation. SHA verification suspends on IO. Its completion, invalid-file cleanup, permission request, installer launch and exception handler then operate without checking that A still owns the transaction. Reads of `state.value.copy(...)` target whichever release is now selected. `cancel` only marks a shared download flag/disconnects a connection; it neither cancels nor invalidates install workers. The ActivityResult callback calls `install(afterPermission=true)` against the manager's current release, with no outstanding-permission owner.

`UpdateDownloadService` owns its foreground job, cancels it before `finishCancellation`, and keeps download retry blocked until partial-file cleanup finishes. A cancellation outside downloading currently starts an unnecessary CANCEL service, whose delayed callback could act on a later selection. The real `AndroidUpdateInstallPlatform` rechecks SHA and uses the existing restricted FileProvider, APK MIME type and read grant to start ACTION_VIEW. The SHA promotion/recheck, release/URL/redirect validation, manifest/provider policy and signed-release package/signature guards must stay intact.

## Minimal candidate plan

1. Keep selection provenance in the manager: an unrelated automatic offer cannot replace an existing selection or sweep its file. Valid explicit manual selection remains available outside the existing download-busy stages.
2. Use a private manager ownership lock and generation, invalidated by selection, cancellation and explicit download retry. Cancel the previous install job. Keep a single owned install job, published before it starts, so repeated taps and permission returns cannot create duplicate active work.
3. Associate the outstanding permission request with its generation. Consume returns only for that owner; a stale A callback must neither verify nor install B. Keep outstanding requests deduplicated until their callback is consumed, including across Activity recreation and explicit replacement.
4. Check ownership for asynchronous download/install state publications, failure paths, invalid-file cleanup and external permission/installer actions. Download cancellation retains its existing busy-until-cleanup behavior; canceling a READY/permission/install transaction completes locally without starting a download service.
5. Leave the existing Core DTO, service/platform adapters, MainActivityContent, BiteComponents, trust checks, backend, release/version policy and CI untouched. Retain all original instrumentation assertions. Run existing lightweight Python guards and whitespace/scope audits; device behavior GREEN requires the parent's Actions rerun.

## Candidate validation

Implemented entirely in the manager. A lazy install job is stored before starting, and an outstanding Settings request retains its generation until consumed. Cancellation and explicit replacement invalidate the old worker immediately; cancellation exceptions are propagated rather than turned into failures. Verified-file cleanup, install completion/exception messages and platform actions check the owner under the same private lock. Download progress/completion/failure publications also check ownership, and final connection cleanup only disconnects that worker's connection. READY remains outside Core's download-busy flag; explicit continuation/retry after a completed job remains available.

The permission entry accepts returns only for an outstanding current-generation NEED_PERMISSION request. An obsolete A request cannot initiate B work, even if B has since downloaded a valid APK. While that old Settings request is outstanding, B cannot create a second permission launch; its return is discarded, and B can then continue explicitly. The existing Activity wrapper's synchronous `fail()` launch-error path clears a failed request so retry remains possible.

Executed from `/tmp/dining-pr18-f6-install`, all with exit code 0:

| Actual command | Result |
| --- | --- |
| `python3 -B android/tools/test_pr18_theme_regressions.py` | Seven static theme/rating tests passed. |
| `python3 -B android/tools/verify_bite_visuals.py` | Contrast, native colors, launcher/vector/tag and all 80 protected-file hashes passed. |
| `python3 -B android/tools/verify_p6_visuals.py` | Retained CI Python entry passed, including its BITE checks. |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'` | All 18 APK-metadata/release-install guard tests passed using synthetic tooling; no SDK/device invocation. |
| `python3 -B -` (inline scope/trust/delimiter audit) | Only the manager and this document differ from the base; 712 other tracked files unchanged. All seven original F6 test methods/assertions and their file bytes match the executed RED. Existing manager trust-check fragments are retained, all 80 pinned hashes match, and the checkpoint's retained Kotlin delimiter audit passes. |
| `git diff --check` | Passed. |

Exact changed files:

- `android/app/src/main/kotlin/top/alanvampuke/dininglog/InAppUpdates.kt`
- `design/pr18-review/F6-OBSERVED.md`

No pinned hash entry required updating: the manager is outside the protected backend/contracts baseline, whose entries and bytes remain unchanged. Core DTOs, download service, real installer platform, MainActivityContent, BiteComponents, legacy/new instrumentation tests, dependencies, backend, versions and CI are unchanged.

This is a candidate backed by the real RED and passing lightweight checks. Kotlin syntax/type compilation and all six device behavior fixes remain unverified until the parent reruns the full existing Actions suite on API 26 and API 35, including the passing cancel/retry/manual-selection control. No local Android/Gradle/JVM/emulator build, push, merge, tag, deployment or other remote write was performed. The candidate's full Git commit SHA is reported separately after committing these two files.
