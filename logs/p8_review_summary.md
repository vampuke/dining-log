# P8 independent review summary

Reviewed `p8-ux-fixes` at `c879a217b2f75741d8661478d803021d67aaa816`. **Overall: PARTIAL; request changes.** A/B/C/D: **PARTIAL**; E: **PASS**. Findings: **0 BLOCKER, 4 MAJOR, 3 MINOR, 1 NIT**.

The four MAJOR findings and minimal remedies are:

- **MAJOR / FAIL:** [UpdateDialog.kt:23](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt#L23) forces 90% height and leaves a large blank area in both update screenshots. Use content height with a maximum and bounded scrolling.
- **MAJOR / FAIL:** [ProductViewModel.kt:170](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt#L170) resets input on editor reopening after rotation. Preserve the active editor key/input/state in the retained ViewModel.
- **MAJOR / FAIL:** [BiteComponents.kt:163](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt#L163) separates labels from editable semantics. Give each editable node its field name and suppress redundant standalone label semantics.
- **MAJOR / FAIL:** [QuietGridComponents.kt:85](../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt#L85) omits horizontal system insets from the capsule. Apply safe horizontal insets before its 20dp margins.

Verified: 56 contrast pairs, 16 screenshot hashes/dimensions, all 80 protected backend/contract files, unchanged version 7/0.7.0 and populated exported-v1 migration SQL retention. List tails and visit-history reachability pass within the reviewed evidence. Gradle/emulator were not run.

Full findings, all 47 requirement/global grades and source/screenshot citations: [REVIEW.md](../design/p8-review/REVIEW.md). Only this summary and the report are changed on the already-existing `p8-review` branch, preserving its history. No PR merge or release was triggered.
