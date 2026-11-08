# PR18 evidence inventory

This directory currently contains executed lightweight output and an expected Android evidence manifest. Android instrumentation has not been compiled or run locally. There are no new Android screenshots or measured Android values here.

`lightweight/results.json` lists exact commands and exit codes. The `.log` files retain plain output; `theme-regressions.json` preserves the exact unittest output in its `output` field, including progress-line trailing spaces. Decoding that field reproduces the original UTF-8 bytes and its recorded output hash. `index-check-before-log-encoding.json` retains the actual artifact-only whitespace rejection. `component-roles.json` contains 42 source-bound component measurements and the three current rating thresholds. The RatingEditor high-score/paper pair is explicitly prospective because the present 80 threshold cannot execute on stored ratings. `audit-checkpoint.py` audits source neutrality, unchanged legacy/protected files and Kotlin delimiters; its passing result is not Android compilation. `review-hashes.json` verifies the four immutable reviewer copies.

## Parent CI execution and retention

Run the existing complete `:app:connectedDebugAndroidTest` suite through the unchanged `.github/scripts/android-test.sh` in the existing independent `android-auth-instrumentation` API26/API35 jobs. New JUnit classes are discovered by the full suite; no class filter or workflow edit is needed. P6 also invokes the extended BITE contrast gate, so the separate Android build job is expected to stop at the new source-bound RED until fixes exist. That static failure is not a replacement for real instrumentation RED.

The tests write to the app-owned external `files/p4-screenshots` directory for `top.alanvampuke.dininglog.debug`. The existing script copies it while instrumentation is still running, before UTP uninstalls the APKs. The existing artifacts `android-auth-api-26` and `android-auth-api-35` retain:

- Connected Android reports under `android/app/build/reports/androidTests/connected/`.
- Captures and logs under `android/app/build/outputs/p4-screenshots/`, possibly nested under another `p4-screenshots/` directory because of `adb pull`.
- The existing `android-runtime.log`, including `DiningP4` measurement diagnostics.

The parent should harvest actual files, preserve their original names and add the CI run/checkpoint SHA, API and failure/acceptance disposition. Do not create placeholder images or substitute static palette calculations for Android measurements.

## Expected Android files and proof

Expected log names are `pr18-measurements-api26.txt` and `pr18-measurements-api35.txt`. Every measurement line records style, dark/light, requested font, actual Activity font, API and density. Scenario lines add palette ARGB, physical layout/semantic bounds, text-layout density and overflow, callback deltas, RPC/service/installer counts, transaction release/APK ownership or real keyboard geometry as appropriate.

Screenshot names follow `pr18-<case>-<ink_paper|quiet_grid>-<light|dark>-actualFont<scale>-api<26|35>.png`. These are naming expectations, not files or values claimed to exist. Case prefixes include:

- `field-<state>`, `active-text-contrast`, `settings-unchecked-switch`, `rating-<39|40|50|unrated>`, `explicit-unrated`.
- `startup-popup`, `startup-dismiss-rotate`, `startup-dismiss-system-font`, `startup-initial-opt-out-late-enable`.
- `flow-*` for restaurant/search/dish/visit/history/detail/edit/settings and confirmation flows.
- `landscape-*`, `landscape-ime`, and `auth-<page>-form/completed`.
- `update-stage-<index>-footer`, `password-real-window`, `conflict-expanded-local`, `apply-expanded-address-end`, `confirmation-*`, `operation-retry`.
- `preferences-*`, including reopened real files, each style/theme choice and system-night resolution.
- `failure-*` captures and semantics where a guarded UI scenario fails.

Some methods stop on their first expected RED, so later cases/captures in that method will not yet exist. The manifest is an acceptance inventory. Missing later files after an earlier recorded failure must not be described as passed coverage.

Palette proof uses literal expected backgrounds for both styles, component/navigation foreground/background semantics and captured ink/background pixels. Production flows also assert the real Activity window ARGB. Merely recording the requested enum is insufficient.

Fonts 1.0/1.3/1.31/2.0 are set through actual system `font_scale`, followed by waiting for the new RESUMED Activity where recreation occurs. Real dialog TextLayoutResult density must match Activity resources at 2.0. No fitted density or synthetic font LocalDensity is used by the harness. Landscape waits also verify display rotations 90/270. The IME case measures the actual accessibility input-method window and actual save-button coordinates.

Targets measure Material's actual reserved outer LayoutInfo width/height with a 0.5dp pixel-rounding allowance, while logging inner coordinates and semantic bounds. A clickable 24dp negative control must still be rejected despite automatic touch expansion. Text uses the unchanged pinned-Compose 1.8.2 remeasurement mechanism, preserving constraints, maxLines, wrapping and overflow; width, height and maxLines negative controls remain required. Existing legacy overflow assertions are unchanged.

Password/auth captures may contain protected black regions because the production `FLAG_SECURE` policy remains intact. Retain the actual capture and font/layout evidence, and report any capture exception. Do not disable security flags, draw replacement text or claim a protected screenshot proves visual appearance. Font/resource/layout assertions and explicit security assertions remain available.

## Controlled orchestration limits

The shared internal composition is exactly the production composition called by MainActivity. The existing non-exported P9VisualActivity supplies real lifecycle, configuration, dialog and layout owners. This does not claim Hilt MainActivity injection was exercised. The orientation-handling host is explicitly recreated for the dismissal/new-owner regression.

Preference tests use real independently opened/closed DataStore files. Reopened-file/fresh-owner tests preserve the same secure token-store fixture and create new account/checker/installer owners. They simulate process-owned collaborator restart and do not kill the OS process. Auth responses and repository operations are controlled fixtures; existing real backend/auth gates remain untouched.

Update tests use production AppUpdateChecker and InAppUpdates validation, SHA rechecks and real fixture-file promotion. A ContextWrapper controls service scheduling and instance-owned collaborators control responses/installer verification timing. They do not access the real network or invoke the system package installer. Delayed B, repeated taps, permission-return and cancel/manual-selection assertions record ownership/counts before failing.

Compilation, missing owners, wrong actual-font preconditions, failed measurement negative controls and fixture/setup errors are harness failures, not observed F1..F6 behavior RED. Keep their diagnostics and correct only the harness/seams until the relevant actual behavioral failure is observed. See [CHECKPOINT.md](../CHECKPOINT.md) for the exact expected RED methods and remaining phases.
