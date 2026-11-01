# P6 verification evidence

Scope: every item in `design/P6_TASK.md`, workflows A, B and C. Work is on `p6-account-endpoints-ui-update`, based on master `70e2e22`. PR: <https://github.com/vampuke/dining-log/pull/10>. All required checks passed in CI run **41337029020**, code commit `ce6732637a0a7b4e737d75cfce2c90735049fa80`. Only this evidence file changes after that tested code commit. Production deployment/release and physical-device limitations are separately marked **unverified** at the end.

## Executed checks

| Evidence | Actual command and result |
| --- | --- |
| L1 | `sha256sum backend/migrations/0001_initial.sql`, before implementation and after the migration verifier: `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`. The verifier also asserts this digest at `backend/scripts/verify_migration_0002.sh:12`. |
| L2 | `bash backend/scripts/verify_migration_0002.sh`: exit 0; `PASS migration 0002: all 14 tables preserved under foreign_keys=ON`; `foreign_key_check=0 integrity_check=ok indexes=5 exact SQL matches; NULL accepted; blank rejected; revision trigger ABORT verified`. |
| L3 | `cd backend && npm run check`: exit 0, TypeScript and ESLint passed. |
| L4 | `cd backend && npm test -- --config build/p6-local-vitest.config.ts --testTimeout=30000`: `Test Files 12 passed (12)` / `Tests 182 passed (182)`, 104.71 seconds (including the four account-purge tests). The temporary, ignored local config changes only Workers runtime concurrency to `singleWorker: true` and uses absolute paths. No test assertions were changed by that config. The 956 MiB VPS swapped heavily with one runtime per test file, so the default full command is separately required in CI. |
| L5 | `cd backend && node tests/contracts.mjs`: `Verified 45 v1 endpoint contracts and all references`; its assertions cover zero TODO/placeholders, strict public users and nullable addresses (`backend/tests/contracts.mjs:55`, `:63`, `:70`). |
| L6 | `node --test .github/scripts/release-guards.test.mjs`: 7 passed, 0 failed; `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'`: 18 tests, `OK`. |
| L7 | `python3 android/tools/verify_p6_visuals.py`: exit 0; `PASS P6 contrast failures=0; vector/adaptive/round/monochrome resources and matching native night background verified`. Numeric results are below. |
| L8 | `git diff --check`: exit 0. Changes were committed with English messages, then `git push origin p6-account-endpoints-ui-update`; no master push, merge, deployment or remote D1 mutation was performed. |

Completed run: <https://github.com/vampuke/dining-log/actions/runs/41337029020> (all four jobs **success**, 2026-11-01 UTC).

| Evidence | Actual command/result and retained report |
| --- | --- |
| BCI | [Backend job 124938917415](https://github.com/vampuke/dining-log/actions/runs/41337029020/job/124938917415): unchanged `npm test` → **12 files / 182 tests passed**, no failures/skips; `npm run check`, contracts, `npm run db:verify` (18), SQLite CLI checks, 0002 verifier, Worker bundle and isolated smoke passed. Both capacity commands ran the selected 4500/6000 fixture and passed; each intentionally skips the other size's case. `capacity-results` artifact contains the measured report. |
| J1 | [Android job 124938917565](https://github.com/vampuke/dining-log/actions/runs/41337029020/job/124938917565): `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --no-daemon` → `BUILD SUCCESSFUL in 2m 28s`. Parsed all TEST XML: core 36 + data 24 + feature 10 = **70 tests, zero failures/errors/skips**; app JVM task has no source. All four missing-signing-variable refusal checks and debug APK manifest/signature checks passed. |
| J1 reports | `android-build` artifact [13095767471](https://github.com/vampuke/dining-log/actions/runs/41337029020/artifacts/13095767471): `core/build/test-results/test/TEST-top.alanvampuke.dininglog.core.UpdatesTest.xml` (5), `data/build/test-results/testDebugUnitTest/TEST-top.alanvampuke.dininglog.data.AppVersionTest.xml` (2), `TEST-top.alanvampuke.dininglog.data.UpdatePreferencesTest.xml` (1), `feature/build/test-results/testDebugUnitTest/TEST-top.alanvampuke.dininglog.feature.ProductViewModelTest.xml` (10). |
| E26 | [API 26 job 124938917558](https://github.com/vampuke/dining-log/actions/runs/41337029020/job/124938917558): `bash .github/scripts/android-test.sh`, which runs full `./gradlew :app:connectedDebugAndroidTest --no-daemon` (`:12`) → **27 tests, 0 failures, 0 skipped**, 100% successful in `reports/androidTests/connected/debug/index.html`. `BUILD SUCCESSFUL in 3m 46s`. Artifact [android-auth-api-26 / 13096020712](https://github.com/vampuke/dining-log/actions/runs/41337029020/artifacts/13096020712). |
| E35 | [API 35 job 124938917628](https://github.com/vampuke/dining-log/actions/runs/41337029020/job/124938917628): same full command → **27 tests, 0 failures, 0 skipped**, 100% successful in the same HTML report path. `BUILD SUCCESSFUL in 5m 9s`. Artifact [android-auth-api-35 / 13096360409](https://github.com/vampuke/dining-log/actions/runs/41337029020/artifacts/13096360409). |
| E26/E35 P6 reports | Each artifact's `reports/androidTests/connected/debug/top.alanvampuke.dininglog.product.P6SmallScreenEndToEndTest.html` (1 test), `top.alanvampuke.dininglog.product.P6UiTest.html` (6) and `top.alanvampuke.dininglog.product.P6UpdateUiTest.html` (1) reports **zero failures**. All 19 existing instrumentation tests also pass. |
| M1 | Each E26/E35 artifact's `outputs/p4-screenshots/android-runtime.log` contains actual `P6 real_api viewport=360x640dp ... complete_record_items=5` (API26) / `=3` (API35) in both themes, and the same rich-fixture counts. Both log `dark_navigation_frames=15 sample_interval_ms=16 white_pixels=0 remove_animations_observed=true`, all three tabs with `reselections=4 entry_preserved=true scrolled_to_top=true`, and the contrast values below. |
| M2 | All eight small-screen screenshots reviewed in those artifacts: `outputs/p4-screenshots/p4-screenshots/p6-{real-,}{light,dark}-360x640-api{26,35}.png`. Labels, vector tabs, optional-field omission and complete row counts match M1. These are generated test artifacts, not app assets. |
| L9 | Changed-file review and in-memory scan of 66 paths: no standard credential/private-key signatures or private key/keystore/bitmap assets. The scan prints paths/counts only; credentials used for GitHub access remain in the helper process memory. |

Android Gradle and emulators ran in Actions as required by `android/gradle.properties:1` and `README.md:23`; the 956 MiB VPS has no emulator or complete Android SDK. Backend tests were explicitly requested by the user and used existing installed dependencies with a temporary single-runtime local config (L4). CI uses the unchanged default `npm test` (BCI). The temporary local config was removed after verification.

The API-27 `windowLightNavigationBar` attribute is in `values-v27/themes.xml:3` and the API-31 override; lint passed without suppressing the earlier finding. The P6 real-API test's synthetic client has its own documentation IP (`P6SmallScreenEndToEndTest.kt:71`), keeping it independent of the existing P4 registration minute window. No production rate limit or existing test assertion was changed.

## General rules

| Task item | Status | Evidence |
| --- | --- | --- |
| 0.1 Frozen 0001; new structure only in 0002 | **verified** | L1, L2; `backend/migrations/0002_address_optional.sql:10`. No change to 0001. |
| 0.2 No credentials in repository, logs or documentation | **verified** | L9 and changed-file review: no actual credential/token/password/keystore material was added. Existing public synthetic test fixtures are not production secrets. GitHub credentials are read only into process memory; the real-API UI test generates its test credentials/key in memory and uses an in-memory token file/database (`P6SmallScreenEndToEndTest.kt:54`, `:56`, `:62`). Version config is public release metadata. |
| 0.3 Assertions remain strict; verification gaps are explicit | **verified** | Exact public keys, byte-identical replay, positive-height complete-row bounds, exact index SQL and ≥4.5:1 contrast remain asserted. Existing E2E assertions are retained; its helper opens the menu for moved actions (`ProductEndToEndTest.kt:383`). BCI/J1/E26/E35 pass; production/release limits below remain explicitly unverified. |
| 0.4 Chinese UI; English code/comments/commits | **verified** | Chinese labels in `DetailEditScreens.kt:121`, `ListScreens.kt:49`, `SettingsScreen.kt:49`; English comments in `backend/src/auth/account-cleanup.ts:6` and migration script; English commit history on the P6 branch. |
| 0.5 D2 Quiet Grid; no photos/avatars/bitmap assets | **verified** | L7 asserts no PNG/WebP under app resources. Launcher XML is vector only; tab vectors are in `QuietGridIcons.kt:9`. Existing grid/monospaced-number theme is retained. Screenshots are generated test artifacts, not app assets. |
| 0.6 Reproducible script before destructive migration verification | **verified** | L2; the submitted verifier creates an isolated DB and records every table before applying DROP (`verify_migration_0002.sh:51`), including injected rollback (`:61`). No production DROP was run. |

Paths abbreviated to Android filenames in the tables below refer to `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/` unless a different module is shown. P6 instrumentation files are under `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/`.

## A: five account endpoints

All five endpoints are registered by `backend/src/auth/account.ts`; the old placeholder route module has been removed. L4 includes the 19 new account tests plus the existing 29 authentication tests.

| Task item | Status | Evidence |
| --- | --- | --- |
| POST /auth/logout-all, password, Reauth, 204 | **verified** | `backend/src/auth/account.ts:58`; account test `:68` checks 204, two sessions/refresh tokens revoked, owner isolation. |
| POST /auth/recovery-codes, password, Reauth, eight codes | **verified** | Handler `account.ts:66`; `backend/tests/auth/account.test.ts:88` checks exact eight, unique/disjoint values and disabled old hashes. |
| GET /me, Access, public User | **verified** | Handler `account.ts:36`; account test `:26` checks both owners and exact response keys. |
| PATCH /me, expected_version/display_name, required idempotency | **verified** | Handler `account.ts:40`; account tests `:115`, `:121`, `:134`. |
| DELETE /me, version/password/confirmation, Reauth, 202 | **verified** | Handler `account.ts:78`; account tests `:144`, `:151`. |
| A1 User keys exactly id, username, display_name, version, created_at, updated_at | **verified** | `backend/src/auth/session.ts:20`; `backend/tests/auth/account.test.ts:32`, `:128`, `:142`, `:148` assert exact sorted key sets for GET, PATCH/replay and conflict current. |
| A2 Real slow-hash reauthentication; wrong password uses shared slow path/dummy user | **verified** | Reuses `verifyLoginPassword` in `backend/src/auth/routes.ts:47` through `account.ts:22`. Tests `account.test.ts:36` spy on the real verifier: exactly two calls, identical stored parameters, 10,000 iterations and results `[false,true]` for all three Reauth endpoints. Existing auth tests retain unknown-user/dummy-user coverage. This verifies the common hashing work, not a statistical network timing study. |
| A3 logout-all immediately rejects unexpired old access tokens | **verified** | Account test `:68` cryptographically verifies the JWT and asserts exp > current time before checking 401; auth_version becomes 2 and B remains at 1. |
| A4 Eight fresh, unique, disjoint codes; old codes unusable; hashed storage and CAS | **verified** | `backend/src/auth/recovery.ts:14` excludes all retained hashes; `account.ts:73` disables old codes in the guarded batch. Account test `:88` checks hash sets, no plaintext in rows, attempts every old code through the real recovery endpoint, then successfully uses a new code. |
| A5 Same-key byte replay/no version increment; public 409 current; username strict 400 | **verified** | Account tests `:115`, `:121`, `:134`: reject username/internal fields and missing/invalid UUID; same response bytes; version stays 2; concurrent same key commits once; different/stale request gives VERSION_CONFLICT and six-key current. |
| A6 Immediate soft delete, auth/session revocation, exactly 30 days | **verified** | Account test `:151`: DB deleted_at equals response timestamp, av increments, unexpired access/refresh fail, purge_after difference is exactly 30×86400 seconds. |
| A6 Confirmation mismatch 400; no business idempotency; username retained | **verified** | Tests `:144`, `:151`: wrong confirmation/stale version do not delete, idempotency row count is unchanged, re-register returns 409 USERNAME_TAKEN, login returns 401, all retained data counts are unchanged, B still reads its profile. |
| A6 Physical purge after retention, bounded cleanup, username reusable with fresh ownership | **verified** | `backend/src/auth/account-cleanup.ts:8` drains ≤20 rows per credential stage, then requires all 11 child tables empty before deleting users (`:26`); scheduled after auth/domain cleanup at `backend/src/index.ts:37`. L4 includes 4 tests in `backend/tests/auth/account-cleanup.test.ts:60`, `:73`, `:97`, `:115`: one-second-before boundary checksum preservation, exact-boundary bounded progress/owner isolation/eventual purge, fresh-ID re-registration with no inherited rows, and late batch CHECK failure rollback. |
| A7 OpenAPI implements all five; no placeholder/TODO | **verified** | L5; `contracts/openapi.yaml:241`, `:394`, `:539`; strict User at `:4403`. No notImplemented handler remains under backend/src. |
| A8 Tests in backend/tests/auth; login, password, success, replay/conflict, isolation | **verified** | L4: `backend/tests/auth/account.test.ts` has 19 passing tests; `:21` covers all five unauthenticated 401 paths, `:36` wrong/correct password, `:50` concurrent auth-change rollback, `:68`/`:88`/`:121`/`:151` isolate B. |

## B: safe optional-address migration

The SQL uses the required four-child backup/restore pattern with foreign_keys ON (`0002_address_optional.sql:3`). D1 rejects SQL BEGIN/COMMIT statements, so the file relies on D1's atomic migration batch. The standalone verifier supplies the equivalent explicit SQLite transaction (`verify_migration_0002.sh:85`), and populated D1 tests verify both success and rollback. It must never be split into individual remote execute calls; this is documented in `docs/OPERATIONS.md` under “P6 地址迁移”.

| Task item | Status | Evidence |
| --- | --- | --- |
| B0 Cascade-loss trap handled; FK check alone insufficient | **verified** | Four backups precede DROP (`0002_address_optional.sql:5`), four restorations follow rename (`:42`); L2 compares all 14 tables, not only FK validity. |
| B1 Same-transaction backup/restore of dishes, visits, search and revisions | **verified** | L2; populated D1 tests at `backend/tests/migration0002.test.ts:30` and `:51` passed in L4. Failure immediately after DROP rolls back every original checksum and original NOT NULL schema. |
| B1 Five original indexes recreated | **verified** | `0002_address_optional.sql:51`; L2 checks exact names, index_list and original SQL, not merely the index count. |
| B2.1 Start from 0001 alone; multiple users/restaurants/all child types | **verified** | Verifier `:11` loads only 0001, `:16` seeds fixtures and `:23` adds records for both owners. All 14 tables are populated (`:52`), including deleted rows and dish/visit revisions. |
| B2.2 Record exact count and checksum for every table before migration | **verified** | Verifier `:44` sorts complete JSON rows and computes SHA-256; before snapshot at `:51`; L2 prints each exact row count and digest. |
| B2.3 Apply 0002 | **verified** | Verifier `:59`, `:85`; D1 test `migration0002.test.ts:30` uses the real migration statements in batch. |
| B2.4 Every table count/checksum unchanged, no child row lost | **verified** | Verifier `:88` compares exact tuples for all 14 tables. L2 output: users=2, restaurants=3, dishes=4, visits=5, restaurant_search=3, revisions=10, all unchanged. |
| B2.5 FK check empty; full integrity_check=ok | **verified** | Verifier `:91` and `:114` run both actual PRAGMAs. D1's authorizer rejects full integrity_check (SQLITE_AUTH); supplemental D1 tests use supported quick_check, while the standalone full integrity assertion remains intact. |
| B2.6 index_list has exactly five nonautomatic indexes | **verified** | Verifier `:93` selects origin `c` and asserts the exact original name set. |
| B2.7 Five SQL definitions byte-for-byte equal to 0001 | **verified** | Verifier `:53` captures SQLite's original sql strings and `:95` compares the full name→SQL dictionaries. L2: `indexes=5 exact SQL matches`. |
| B2.8 NULL accepted; empty and spaces rejected by CHECK | **verified** | Verifier `:98` inserts actual NULL; `:99` attempts both invalid values and requires CHECK failure and zero invalid rows. |
| B2.9 Immutable revision trigger exists and UPDATE diff_json ABORTs | **verified** | Verifier `:96` compares the exact trigger definition; `:107` attempts UPDATE and requires exact `revision core is immutable`. |
| B3 Backend optional address and blank→null | **verified** | `backend/src/domain/schemas.ts:12` / `:19`; L4 includes four omitted/null/empty/spaces create-and-clear cases at `backend/tests/domain/queries.test.ts:10`. |
| B3 Null-safe search remains correct and contains no literal null | **verified** | Existing `.filter(v => v !== null)` retained in `backend/src/domain/search-index.ts:5`; the four passing query cases assert real index presence/content and successful search at `queries.test.ts:10`. |
| B3 Android permits omission and labels address 选填 | **verified** | `ProductViewModel.kt:391`, `restaurants/DetailEditScreens.kt:121`; J1 covers editor submission at `android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ProductViewModelTest.kt:85`. E26/E35 real-API test at `P6SmallScreenEndToEndTest.kt:131` saved name/category-only through the actual editor, ViewModel, Room and Worker in both themes. |
| B3 PLAN required-field rules revised | **verified** | `design/PLAN.md:63` and `:218` now require name/category, allow optional address. `:762` input type is nullable/optional. `:267` explains why the historical 0001 DDL remains frozen. |

Child checksum evidence from the executed verifier:

| Table | Rows before = after | SHA-256 before = after |
| --- | --- | --- |
| dishes | 4 | `9533eb5a4c7095c4fb713a4bd8675000641c3a4efe1a644dfd81986f90c0613e` |
| visits | 5 | `2c89c9b76ead9f82bba958ce01647ce10f0f8cbc842da6c21f43a85a3dd1e7b9` |
| restaurant_search | 3 | `27c7f4ee8e09aa8c275a28ea4150a6e1e4abe8c588864558f10688e34e220055` |
| revisions | 10 | `b3af77591da0a7f9a5d4e36a4d0dad3f98a3f60d17af78c6ce8b89972a3611f0` |

## C: navigation, layout, icons and updates

| Task item | Status | Evidence |
| --- | --- | --- |
| C1 Four navigation enter/exit/pop transitions | **verified** | `ProductRoot.kt:121` defines all four, with 200ms entry, 180ms exit, fade plus 12dp displacement (`:51`). |
| C1 Respect live system remove-animations preference | **verified** | `components/NavigationMotion.kt:14` reads and observes Settings.Global.ANIMATOR_DURATION_SCALE; all four transitions return None at zero. E26/E35: `P6UiTest.kt:172` changes the actual setting from 1 to 0 and verifies both observed states plus immediate destination rendering. |
| C1 Dark navigation/native launch background avoids white flash | **verified** | `ProductRoot.kt:117` paints theme bg; `MainActivity.kt:113` updates the window; L7 matches light/night native resources. E26/E35 at `P6UiTest.kt:183`: 15 transition frames, first frame included, 16ms spacing, positive dark-bg count and zero exact white pixels. Native night/splash resolution also passes at `P6UiTest.kt:217`. Physical cold-launch behavior is separately unverified below. |
| C2 Adaptive icon at mipmap-anydpi-v26 and vector layers | **verified** | L7; `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:1` references pure foreground/background vectors; manifest `:7` selects it. |
| C2 Round icon | **verified** | L7; v26/v33 ic_launcher_round XML; manifest `android/app/src/main/AndroidManifest.xml:8`. |
| C2 食记/Quiet Grid meaning; simple nongradient artwork | **verified** | Foreground is a square journal with grid ruling and a three-tine fork; two colors, no gradient, raster, avatar or photograph. `android/app/src/main/res/drawable/ic_launcher_foreground.xml:1`. |
| C2 ≥4.5:1 foreground/background on light and dark wallpaper | **verified** | L7: 8.4335:1 for #FBFBFA on opaque #23543C. E26/E35 actual adaptive and round icon rendering over both wallpapers at `P6UiTest.kt:201` requires >50 foreground and >100 background pixels plus ≥4.5:1. |
| C2 Android 13+ monochrome layer | **verified** | L7 verifies both v33 adaptive XML files contain monochrome; E35 at `P6UiTest.kt:205` requires nonnull actual AdaptiveIconDrawable.monochrome for both launcher resources. |
| C2 Own app theme and native night/splash resources | **verified** | Manifest uses Theme.DiningLog (`android/app/src/main/AndroidManifest.xml:15`). Base/night and API-31 splash resources match L7. E26/E35 at `P6UiTest.kt:217` resolves actual native night background; E35 also resolves splash to QuietGridDark.bg. API-27 bar attribute is version gated; J1 lint/build passes. |
| C2 Design and measured contrast documented | **verified** | This section and numeric table below; L7 executed successfully. |
| C3 Real semantic restaurant/calendar/person vectors; no dependency added | **verified** | `components/QuietGridIcons.kt:9`; `ProductRoot.kt:216` renders Icon and selects distinct vectors at `:210`; no added icon dependency or lock changes. |
| C3 Outlined inactive / filled active, independent of color | **verified** | `ProductRoot.kt:202` exposes TabIconFilled semantics. E26/E35 at `P6UiTest.kt:83` captures actual rendered silhouettes before/after selecting every tab in both themes and requires a shape change independently of color. |
| C3 Selected icon and label ≥4.5:1, both themes | **verified** | L7: light icon 7.5195:1 / label 8.7324:1; dark icon 6.5420:1 / label 8.0114:1. E26/E35 at `P6UiTest.kt:101` confirms the actual glyph/indicator are painted in those colors and both ratios pass for all three tabs. |
| C3 Existing tab test tags preserved | **verified** | `ProductRoot.kt:222` preserves tab:restaurants, tab:visits, tab:settings. Existing E2E assertions use the same tags. |
| C4 Only optional labels show 选填 | **verified** | `restaurants/DetailEditScreens.kt:113` has plain 类型; `:121` adds 选填 only to optional text. Required name/date labels remain plain; optional score/meal/first-visit note are marked optional. |
| C4 Name/category-only restaurant save | **verified** | L4 verifies backend; J1 verifies validation and editor submission. E26/E35 at `P6SmallScreenEndToEndTest.kt:131` saves through the actual editor in both themes, reads the result back through the API and requires category=restaurant and all four optional text fields NULL. |
| C4 Empty address/note/review omitted in list/detail, no null/empty/dash placeholders | **verified** | `restaurants/DetailEditScreens.kt:39`, `:46`, `:50` omit blank field rows; list at `restaurants/ListScreens.kt:124`, `:134` omits empty optional text. E26/E35 at `P6UiTest.kt:113` requires missing detail tags and zero null/dash labels; real API test at `P6SmallScreenEndToEndTest.kt:123`, `:144` checks list and detail omission. M2 screenshots reviewed in both themes. |
| C5 Current destination checked before any navigation | **verified** | `ProductRoot.kt:83` uses currentBackStackEntry destination.hasRoute; current branch returns before vm.selectTab or navigate (`:89`). |
| C5 Reselect scrolls each root list to top without rebuilding entry | **verified** | `ProductRoot.kt:52`, `:91` retains root list states and scrolls to top. E26/E35 at `P6UiTest.kt:135` performs four reselections for each of three tabs, requires exact real NavigationEntryId equality, visible page-title, and unchanged refresh/profile call counts. |
| C5 True tab switches retain launchSingleTop | **verified** | `ProductRoot.kt:97` retains launchSingleTop for all three destinations; test also opens a real detail before returning with the same highlighted restaurant tab. |
| C6 Compact title; no branding line | **verified** | `components/QuietGridComponents.kt:66` replaces the old branding/title stack with a compact header. |
| C6 Search retained; permanent explanation removed | **verified** | `restaurants/ListScreens.kt:41` retains single-line search; no separate explanatory copy beneath it. |
| C6 One primary add button; secondary filter/menu actions; no debug refresh row | **verified** | `restaurants/ListScreens.kt:48` has one row with add/filter/more; `:61` places refresh, restaurant filtering and trash in DropdownMenu. Refresh label is 刷新. |
| C6 First 360×640dp screen shows ≥3 complete real records, light and dark | **verified** | E26/E35 at `P6SmallScreenEndToEndTest.kt:84` creates five records through real Worker/D1, verifies NULL optional fields and uses requiredSize(360dp,640dp). `:112` verifies exact viewport; `:121` requires ≥3 positive-height, fully unclipped, displayed real record-list items. M1: 5 on API26 / 3 on API35 in each theme. M2 screenshots reviewed. |
| C6 Independent rich-record small-screen coverage | **verified** | E26/E35 at `P6UiTest.kt:57` checks actual rendered fixture rows with address/cuisine/rating/last visit, both themes, exact viewport and ≥3 complete rows by unclipped bounds (`:74`). M1: 5 API26 / 3 API35. M2 screenshots reviewed. |
| C7 Public GET app/version from six Wrangler vars, exact raw latest JSON, no D1 | **verified** | `backend/src/routes/app-version.ts:18`; vars in `backend/wrangler.toml:29` and isolated fixture. L4 includes 9 tests at `backend/tests/app-version.test.ts:16`, with a DB proxy that throws on access and no auth bindings. |
| C7 Actual published URL, version, SHA-256 and release time | **verified** | Config advertises published v0.5.0: code 5, digest `d77da09cd64b914076693d0bcfc03916a655d6b8cc5a24ce59b73a3e1d43d678`, published_at 2026-11-01T14:40:38Z = 1793544038. Read-only `GET /repos/vampuke/dining-log/releases/tags/v0.5.0` returned the same published_at, app-release.apk URL, `digest=sha256:d77da09cd64b914076693d0bcfc03916a655d6b8cc5a24ce59b73a3e1d43d678`, and size=2541529; config equality assertions passed (`backend/wrangler.toml:29`). Android code 6 is unreleased; no checksum for an unpublished artifact is invented. |
| C7 Background startup check, first screen not blocked, failure silent | **verified** | `android/app/src/main/kotlin/top/alanvampuke/dininglog/AppModule.kt:37` starts the checker on IO scope; `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Updates.kt:29` waits for settings and silently catches transport errors. J1: five UpdatesTest tests, including delayed settings/no initial request (`UpdatesTest.kt:27`) and silent failure (`:64`). |
| C7 Inline 我的 entry, no interrupting dialog | **verified** | `settings/SettingsScreen.kt:47`; E26/E35 at `P6UpdateUiTest.kt:26` asserts no dialog, displays the inline version entry, clicks it and toggles it off. |
| C7 ACTION_VIEW HTTPS link, no app download/install/installer permission | **verified** | `android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt:121` creates ACTION_VIEW after validating release metadata. E26/E35 at `P6UpdateUiTest.kt:26` checks action/data and actual installed package permissions. Manifest has only network permissions. The app downloads no APK bytes, so conditional download-time digest checking does not apply. |
| C7 Numeric BuildConfig.VERSION_CODE comparison | **verified** | AppModule.kt:38 passes BuildConfig.VERSION_CODE; Updates.kt:36 compares integer codes. J1 at `android/core/src/test/kotlin/top/alanvampuke/dininglog/core/UpdatesTest.kt:19` passes equal/lower/higher codes with misleading version names. Source version is 6 / 0.6.0 (`android/app/build.gradle.kts:44`). |
| C7 Persistent opt-out, cancels request, removes entry, restart retains setting | **verified** | `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/AndroidPreferences.kt:29` persists the setting. J1: UpdatesTest.kt:43 requires cancellation/removal; `UpdatePreferencesTest.kt:19` closes and reopens the actual DataStore file and requires opt-out/theme retained. E26/E35 at `P6UpdateUiTest.kt:26` exercises the UI toggle. |
| C7 Anonymous request, no Authorization/cookie/account identifiers | **verified** | Dedicated client at `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/remote/AppVersionGateway.kt:31` has no account/session/refresh dependency. J1 at `AppVersionTest.kt:14` checks exact anonymous GET path, no auth/cookies/user/idempotency headers or body. Backend unauthenticated access without auth bindings or D1 is verified in L4. |

Measured static colors from L7:

| Element | Foreground / background | Contrast | Failures |
| --- | --- | --- | --- |
| Launcher, opaque background over either wallpaper | #FBFBFA / #23543C | 8.4335:1 | 0 |
| Light selected tab icon | #23543C / #E8F0EA | 7.5195:1 | 0 |
| Light selected tab label | #23543C / #FFFFFF | 8.7324:1 | 0 |
| Dark selected tab icon | #6FBF95 / #1B2E23 | 6.5420:1 | 0 |
| Dark selected tab label | #6FBF95 / #191918 | 8.0114:1 | 0 |
| Light inactive icon/label | #555650 / #FFFFFF | 7.4121:1 | 0 |
| Dark inactive icon/label | #B8BAB0 / #191918 | 8.9551:1 | 0 |
| Light body text | #181916 / #FBFBFA | 17.0505:1 | 0 |
| Dark body text | #F2F2EC / #0F0F0E | 17.0665:1 | 0 |

## Deliverables and completion criteria

| Task item | Status | Evidence |
| --- | --- | --- |
| Deliverable 1: five endpoints, tests, OpenAPI | **verified** | A table, L3–L5. |
| Deliverable 2: 0002 and actually executed preservation verifier | **verified** | B table, L1–L2 and populated D1 tests in L4. |
| Deliverable 3: all Android UI/update changes | **verified** | Implemented sources and J1/E26/E35 runtime evidence throughout B/C; complete build, lint, JVM and both full emulator suites pass. |
| Deliverable 4: item-by-item verified/unverified evidence | **verified** | This file covers every task section, with actual commands, source/assertion lines, CI jobs, artifact report paths and explicit unperformed production/device checks. |
| Deliverable 5: backend/Android/API26/API35 CI | **verified** | Run 41337029020 at ce67326: BCI, J1, E26 and E35 all completed with success. |
| Completion: frozen hash unchanged | **verified** | L1. |
| Completion: migration child data zero loss | **verified** | L2 plus D1 success/fault rollback tests in L4. |
| Completion: npm test all green; added tests >0 | **verified** | L4 and BCI: full 182/182. Added tests include 19 account, 4 account cleanup, 2 migration, 4 address, 9 version and one auth/session routing regression (39 new backend cases). Default CI npm test is unchanged. |
| Completion: no five-endpoint OpenAPI placeholders/TODO | **verified** | L5. |
| Completion: install and run all existing instrumentation on API 26/35 | **verified** | E26/E35: installed debug and test APKs, full connectedDebugAndroidTest (`.github/scripts/android-test.sh:12`), 27/27 on each API with zero failures/skips, including all 19 existing cases and all 8 new P6 cases. |
| Completion: ≥3 first-screen assertion exists and passes | **verified** | E26/E35, M1/M2; both real-API and rich-row exact-viewport tests require ≥3 complete rows and passed in both themes on API26 and API35. |
| Completion: light/dark contrast measured with zero failures | **verified** | L7 numeric table; E26/E35 actual glyph/silhouette/launcher checks pass in both themes and over both wallpapers. |
| Completion: genuine evidence for every item | **verified** | Exact command outputs, test names, source/assertion lines, CI job and artifact links, complete-row/contrast/frame measurements and explicit verification limits throughout. |
| Submission: named branch, English logical commits, PR, no master push | **verified** | PR #10 targets master from p6-account-endpoints-ui-update. Account `2dc85af`, migration `dbb1078`, version feed `ff0ea95`, Android `590f55f`, operations `9253aa3`, visibility `87d4b0c`, regressions `46cda47`, test APIs `5102929`, initial evidence `1674a5d`, bounded account cleanup `2c22548`, API theme/client isolation `ce67326`. All work was committed on the named branch and only that branch was pushed. |

## Work not performed

- **unverified** — Production D1 application, production Worker deployment and production app/version response. The authorized deliverable is a branch/PR; isolated databases/CI were used and production was not mutated.
- **unverified** — Signed 0.6.0 APK publication and changing the public feed to 0.6.0. No such release artifact exists yet; `docs/OPERATIONS.md` documents updating all six public vars after the actual release/digest exists.
- **unverified** — Physical-phone/wallpaper launch or upgrade from the signed 0.5.0 production APK. CI emulators cannot establish physical-device or signed-upgrade behavior.
- **unverified** — Statistical production password timing and production capacity/Time Travel restore. No production measurements or destructive restore were performed; these are not substituted by synthetic CI checks.
