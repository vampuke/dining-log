P8 independent review: REQUEST CHANGES (overall PARTIAL).

Reviewed p8-ux-fixes at c879a217b2f75741d8661478d803021d67aaa816 against master a5397a3. Full instructions recovered from master:design/P8_REVIEW_TASK.md. The pre-existing p8-review branch/history was preserved; only review documentation was updated.

Findings: 0 BLOCKER, 3 MAJOR, 4 MINOR, 1 NIT. Major fixes: content-sized update dialog (UpdateDialog.kt:23); retain in-memory request identity for unchanged lost-response retries (ProductApi.kt:172 / ProductRepositoryImpl.kt:73); preserve the active in-memory editor on activity recreation (ProductViewModel.kt:170 / ProductRoot.kt:165). Minor findings concern field accessibility labels, missing visual/lifecycle combinations, abandoned partial APKs and raw transport error copy.

Grades: A PARTIAL, B PARTIAL, C PARTIAL, D PARTIAL, E PASS, F PARTIAL. Report: design/p8-review/REVIEW.md; every specification item has a grade/citation and each MAJOR has a concrete minimal fix.

Read-only verification passed: 16 screenshot hashes/dimensions, 56 contrast ratios, populated v1 DROP SQL and retained schema equality, unchanged backend/contracts/migration hashes/version 7 / 0.7.0, vector resources and tag/removal checks. No Gradle/emulator execution, implementation changes, PR merge or release action. Physical installer and missing lifecycle behavior remain unverified.
