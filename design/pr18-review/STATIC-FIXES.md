# PR18 static fixes: F1 / F2 / F4

Branch: `fix/pr18-review-0.8.3`. Parent checkpoint: `ed9f8112352767006ad312d6e7ded6ec0644fea4`.
Scope follows `/tmp/dining-release-20261107/fix-pr18-static.md`: only the already-observed source-bound RED slices. This commit establishes static GREEN; Android rendering and acceptance remain unverified.

The only production changes are:

- F1 — [Colors.kt](../../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/theme/Colors.kt): enabled tertiary placeholders use `#786E60` light and `#998F7E` dark. Dark action text uses `#1C1915` over one persimmon `#E56E44`; the accent aliases share that value. This also makes the small high-rating text readable on the default editor's paper frame.
- F2 — [SettingsScreen.kt](../../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt): the actual update Switch explicitly binds enabled unchecked thumb/border to `secondary` and track to `surface` for InkPaper. Decorative 1dp rules, disabled defaults and classic Switch colors remain unchanged.
- F4 — [QuietGridComponents.kt](../../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt) and [BiteComponents.kt](../../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt): `Score`, `verdictFill` and `VerdictSticker` consistently emphasize `>=40` tenths (4.0/5.0). The stored 10..50 range, classic 32/40/46 tiers and explicit-unrated accessibility contract are unchanged.

## Observed RED → GREEN

Each command below ran before its corresponding production fix and again after it. Exact commands, timestamps, exit codes and output are retained in the linked JSON receipts; decoding `output` preserves the original progress whitespace and numeric diagnostics.

| Slice | Exact command | RED | GREEN | Receipts |
| --- | --- | --- | --- | --- |
| F1, including prospective F1/F4 paper contrast | `python3 -B android/tools/test_pr18_theme_regressions.py ComponentContrastTest.test_f1_enabled_button_normal_focus_and_press ComponentContrastTest.test_f1_enabled_field_placeholder_normal_focus_and_error ComponentContrastTest.test_f1_f4_reachable_high_score_on_actual_default_editor_frame` | Exit 1; 3 tests, 10 failures | Exit 0; 3 passed | [RED](static-evidence/f1-red.json), [GREEN](static-evidence/f1-green.json) |
| F2 | `python3 -B android/tools/test_pr18_theme_regressions.py ComponentContrastTest.test_f2_enabled_unchecked_update_switch_thumb_and_border` | Exit 1; 1 test, 8 failures | Exit 0; 1 passed | [RED](static-evidence/f2-red.json), [GREEN](static-evidence/f2-green.json) |
| F4, all three renderers | `python3 -B android/tools/test_pr18_theme_regressions.py ReachableRatingTest` | Exit 1; 3 tests, 9 failures; threshold 80 | Exit 0; 3 passed; threshold 40 | [RED](static-evidence/f4-red.json), [GREEN](static-evidence/f4-green.json) |

| Measured InkPaper role | RED ratio | GREEN ratio | Required minimum |
| --- | --- | --- | --- |
| Dark button normal | 3.4471:1 | 5.5413:1 | 4.5:1 |
| Dark button focused / pressed, 10% content-color state layer | 3.0456:1 | 4.7209:1 | 4.5:1 |
| Light enabled placeholder, normal / focused / error | 3.8338:1 | 5.0025:1 | 4.5:1 |
| Dark enabled placeholder, normal / focused / error | 4.3891:1 | 5.0044:1 | 4.5:1 |
| Dark accent on the actual default RatingEditor paper frame | 4.2290:1 (prospective guard) | 4.6132:1 | 4.5:1 |
| Light unchecked Switch thumb / border against track and adjoining surface | 1.5737 / 1.4708:1 | 5.9831 / 5.5921:1 | 3:1 |
| Dark unchecked Switch thumb / border against track and adjoining surface | 1.6112 / 1.7678:1 | 6.8646 / 7.5316:1 | 3:1 |

Light button contrast remains 5.4615 normally and 4.6182 focused/pressed; light accent/paper remains 4.6816. All three rating regressions check 39/40/50/null and exactly 11 emphasized values in the legal range.

The checkpoint's source measurement helper and all tests/asserted targets are unchanged. It reads actual `BiteButton` content/container tokens, both placeholder slot foregrounds and Material container colors, and the Settings `SwitchDefaults.colors` bindings. The paper guard reads `Score`'s high-branch foreground and `RatingEditor`'s default `brutalBox` background. All 42 role identities remain present; classic measurements are identical to the checkpoint.

## Final lightweight gates

| Exact command | Observed result | Exact output |
| --- | --- | --- |
| `python3 -B android/tools/test_pr18_theme_regressions.py` | Exit 0; 7 passed | [Receipt](static-evidence/theme-all-green.json) |
| `python3 -B android/tools/verify_bite_visuals.py` | Exit 0; retained classic/native/vector/tag/hash checks and InkPaper roles pass | [Receipt](static-evidence/bite-green.json) |
| `python3 -B android/tools/verify_p6_visuals.py` | Exit 0; retained checks and delegated BITE check pass | [Receipt](static-evidence/p6-green.json) |
| `node --test .github/scripts/release-guards.test.mjs` | Exit 0; 7 passed | [Receipt](static-evidence/release-guards-green.json) |
| `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'` | Exit 0; 18 passed | [Receipt](static-evidence/python-release-guards-green.json) |
| `git diff --check` | Exit 0 | [Receipt](static-evidence/diff-check-green.json) |

The [scope audit](static-evidence/scope-audit.json) compares against the parent checkpoint: 695 existing files outside the four-file production allowlist are byte-identical. This includes all four initial reviewers and every checkpoint receipt, MainActivityContent/InAppUpdates/UpdateIo and their seams, startup/install and legacy tests, workflows/release/signing guards, dependencies, backend/contracts/migrations, auth/session policy, version and APP_* values. All 80 protected hashes pass; none of the four edited source files is in `p8_protected_hashes.json`, so no hash entry needs refreshing.

## Pending Android evidence

The parent must supply actual artifacts from checkpoint Actions run `42358965911`; no Android behavior RED or acceptance is claimed here. F3 underline painting, F5 recreation/real-font-change/late-opt-in startup behavior and F6 installer ownership remain unchanged pending actual RED. F7 both-style/API26/API35 screen/component acceptance and F8 startup/preference orchestration remain prepared but unverified, including real system-font and IME evidence. Runtime verification of these static F1/F2/F4 fixes also remains pending.

No remote writes, local builds, JVMs, emulators, dependency installation, version bump or release action occurred. Stop after the local static-fix commit.
