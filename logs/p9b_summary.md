# P9 R9 screenshot fix — 食记

R9 is implemented and the code/tests are pushed on the existing `p8-ux-fixes` branch. Ordinary screens allow screenshots; LOGIN / REGISTER / RECOVER, one-time recovery-code display and change-password retain `FLAG_SECURE`. The reusable `DisposableEffect` clears the flag on disable and on sensitive-screen disposal. PR [#12](https://github.com/vampuke/dining-log/pull/12) remains open; no merge or release was triggered.

Read `design/P9_R9_SCREENSHOT.md` completely from Git history at `d4aa7476bba5f77b478c9c970e9a22b56f8d5dca`; it is absent from the implementation checkout.

Android file:line references describe the CI-verified commit `a823bb7298f515647e666cb63d043386a8a4fff4`.

| Change | Exact file:line |
| --- | --- |
| Remove the global secure flag and unused WindowManager import from onCreate; ordinary screens start screenshot-allowed. | `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt:62` |
| Add the reusable composable and window/enabled keyed effect; set on sensitive entry and clear on disable. | `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:16`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:19`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:20`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:21` |
| Clear the flag when the sensitive composition is disposed; unwrap ContextWrapper to find the Activity window. | `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:22`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:23`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:28` |
| Protect LOGIN / REGISTER / RECOVER and unacknowledged one-time recovery-code display; disable protection for authenticated content. | `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/auth/AuthScreens.kt:67` |
| Protect the activity while PasswordDialog is visible and explicitly secure its separate dialog window. | `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:23`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:91`, `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:96` |
| Assert SET on login, recovery, registration and one-time recovery-code display. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:118`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:121`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:124`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:131` |
| Assert CLEAR on the restaurant list after recovery-code acknowledgement and on restaurant editor/detail. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:140`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:145`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:156` |
| Assert settings CLEAR, password SET, successful password completion CLEAR, logout/login SET, then restaurant list CLEAR. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:343`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:347`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:351`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:363`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:369` |
| Read the Activity window flags on its UI thread and assert the exact FLAG_SECURE bit. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:391`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:396` |
| Add the light/dark regression for initial list CLEAR, password SET, repeated dismissal CLEAR and return to the list CLEAR. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:26`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:51`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:58`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:61`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:65`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:78` |
| Fix the CI input timing failure: reopen the keyboard by tapping and wait for measured IME visibility before scrolling again. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:48`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:460`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:466` |
| Keep the real BottomTabs in the test fixture outside the IME-gated overlay; assert tab presence and visibility before tapping. | `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:31`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:44`, `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt:69` |
| Record R9 locations, assertions, commits, CI results and limits while preserving earlier evidence. | `design/bite/p8-evidence/evidence.json:953` |
| Record the requested delivery summary and verification limits. | `logs/p9b_summary.md:1` |

Logical commits:

- `79392f227d1587c8dbdb3ec5de432cd7ddc01eab` — fix(p9-r9): scope screenshot protection to sensitive screens
- `fdbd6c4f7c7ebb96ec8ad5e3d968f517c3301584` — test(p9-r9): assert secure flags and cleanup across screen flows
- `435d8b665f9afd279922a77d9da21f87c271a9dd` — test(p9-r9): wait for keyboard transitions in screenshot flows
- `a823bb7298f515647e666cb63d043386a8a4fff4` — test(p9c): keep screenshot-flow tabs outside the IME-gated overlay
- `cfe417161830836712d2474fed8f2889e5ef26b4` — docs(p9c): record successful API 26 and 35 CI validation

Evidence and this summary are delivered in a separate documentation commit. The summary is explicitly added despite the repository's `logs/` ignore rule.

Static verification passed: both visual verifiers and all 56 light/dark contrast pairs; `git diff --check`; all 556 pre-R9 Android assertion lines preserved, plus all 587 assertion lines present before the keyboard timing fix. `backend/` and `contracts/` have no diff from pre-R9 `e8d3411`, including all migrations. Version remains `7 / 0.7.0`; version source, theme resources and system-bar behavior are unchanged. The recovery-code clipboard callback at `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt:102` is byte-for-byte unchanged, including API 33+ `EXTRA_IS_SENSITIVE` at `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt:104`. Untracked dispatcher scripts are excluded from commits. The P9c fixture follow-up in `a823bb7` and its separate summary are preserved and included in the final CI source.

CI passed Android build/lint/JVM tests and the API 26/API 35 instrumentation suites, including the R9 window-flag assertions. [CI run 41407187375](https://github.com/vampuke/dining-log/actions/runs/41407187375) verifies source `a823bb7298f515647e666cb63d043386a8a4fff4`.

- android: completed; success.
- android-auth-instrumentation (35): completed; success.
- android-auth-instrumentation (26): completed; success.

The final screenshot fixture uses unconditional real tabs so password-dialog IME transitions do not remove its navigation. The existing P9c summary at `logs/p9c_summary.md:1` records that follow-up.

The first R9 [run 41402772135](https://github.com/vampuke/dining-log/actions/runs/41402772135) passed Android build/lint/JVM checks, but failed one instrumentation test on each API. Reports pinpointed missing navigation tabs during keyboard transitions; the third commit fixes those interactions while retaining every assertion. Full details are recorded in the P9b evidence entry.

Could not verify locally: Gradle builds, lint/JVM tests or emulator execution, as instructed. Physical-device screenshots, screen recording, recent-task thumbnails and OEM behavior were not manually exercised.
