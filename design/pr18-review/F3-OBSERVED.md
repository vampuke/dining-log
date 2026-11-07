# PR18 F3 — observed device RED and candidate

## Diagnosis recorded before production edits

Starting branch: `fix/pr18-review-0.8.3`; verified HEAD
`37ac1eb71848cd6c31a79e3d2118d2d2d36b24c0` and empty porcelain status.
Actual test-only checkpoint: `02d5ff9d26231b1d06eb0f069c4a61e93343fe2e`.
[Actions run 42360683696](https://github.com/vampuke/dining-log/actions/runs/42360683696)
reported API35 **174 tests / 51 failures / 0 skipped** and API26
**174 / 58 / 0**. Those totals include other findings; F3 accounts for four failures.

The existing `/tmp/dining-release-20261107/observed-full-stacks-42360683696.json`
is valid JSON, with 109 failure records. An independent recursive Python
`HTMLParser` extraction of every `div.test`'s `h3` and `pre` under both report
trees matched all 109 records exactly (58 API26, 51 API35). The interrupted
command was not rerun. `summary.json`'s `artifacts[].cases[]` was used for
case status only, not as a stack trace.

Both original class reports were read:

- `/tmp/dining-release-20261107/ci-evidence-42360683696/android-auth-api-26/reports/androidTests/connected/debug/top.alanvampuke.dininglog.product.Pr18ComponentTest.html`
- `/tmp/dining-release-20261107/ci-evidence-42360683696/android-auth-api-35/reports/androidTests/connected/debug/top.alanvampuke.dininglog.product.Pr18ComponentTest.html`

Each has the following exact failure for both `[INK_PAPER-dark=false]` and
`[INK_PAPER-dark=true]` variants of
`f3InputRuleIsPaintedAboveTheContainerBeforeSupportingTextForEveryFieldState`:

```text
java.lang.AssertionError: F3 empty: full-width input underline must be visible above the container, before support
at org.junit.Assert.fail(Assert.java:89)
at org.junit.Assert.assertTrue(Assert.java:42)
at top.alanvampuke.dininglog.product.Pr18ComponentTest.f3InputRuleIsPaintedAboveTheContainerBeforeSupportingTextForEveryFieldState(Pr18ComponentTest.kt:118)
```

Both QuietGrid variants of this method passed on both APIs. InkPaper aborted
at the first `empty` case at actual font scale 1.0; this run does **not** prove
RED for later populated/multiline/supporting/error/large-font cases.

## Pixels, geometry and the test oracle

The device measurements are under each API artifact's
`outputs/p4-screenshots/p4-screenshots/pr18-measurements-api<API>.txt`, lines
61 (light) and 192 (dark). Every failing case records density **2.625**,
bitmap **861 × 147**, `support=null`, `inputLastSurfacePx=146`,
`supportStartPx=147`, `ruleRows=[]`, `requestedFocus=false`,
`actualFocus=false`, `error=false`, `enabled=true`, requested/actual font **1.0**.

| API | Theme | Field bounds, pixels | Expected rule ARGB / RGB |
| --- | --- | --- | --- |
| 26 | light | `(42,104)-(903,251)` | `-1712689` / `#E5DDCF` |
| 26 | dark | `(42,104)-(903,251)` | `-12963030` / `#3A332A` |
| 35 | light | `(42,113)-(903,260)` | `-1712689` / `#E5DDCF` |
| 35 | dark | `(42,113)-(903,260)` | `-12963030` / `#3A332A` |

An independent Pillow crop of each saved **945 × 1680** viewport PNG using
those field bounds reproduced the test's middle-half-width sampling:
per-channel tolerance ≤3, rule coverage ≥95%, surface coverage ≥90%.
All four crops had zero rule rows, last surface row 146, and **100% surface
coverage across the sampled bottom row**. Expected ARGB matches current
palette `border`; the fixed F1/F2/F4 colors did not change this role.

PNG names are `pr18-field-empty-ink_paper-<light|dark>-actualFont1.0-api<API>.png`
in the measurement directory. SHA-256, API26 light/dark then API35 light/dark:

```text
65a2455bcb0e1938071f68deb97a8075a85f92b7e8685378056a105c116f2cd8
511e7911eda70716153ac1dbc53a885efd7daa89fd0ea5ce957ad9b8e5191ad0
8432d4622a60d8f3f936605c8e665e10363b0bddc56f94f955c2f904f6f271b1
659c7eb4978ad75958fe3d064b74a79da574ab8822c508783e24c7eb4d3abe02
```

[Pr18ComponentTest.kt](../../android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr18ComponentTest.kt):91–120
selects `danger` for error, `ink` for focused InkPaper, otherwise `border`;
requires a painted surface and a full-width rule within 2dp of its last row,
before the supporting text; unsupported fields also require an input-bottom
rule. Focus, style, accessibility and surface preconditions passed before
line 118. QuietGrid controls show bottom border rows 141–146 and last surface
140 with identical capture size/density. This is a genuine missing underline,
not a failed focus precondition, stale palette or unsupported screenshot oracle.

## Applicability and pinned Compose mechanism

`git diff 02d5ff9... HEAD` shows the whole `BiteTextField` body unchanged,
as are `Pr18ComponentTest.kt`, `Pr18Evidence.kt` and dependency declarations.
The only checkpoint-to-HEAD changes in `BiteComponents.kt` are the already-fixed
F4 thresholds/comment. Starting source lines **210–214** draw the InkPaper
rule behind the entire field; **220–222** attach it to `OutlinedTextField`;
**234** keeps support inside that field; **237–240** keep opaque `surface`
containers and transparent Material borders.

The pinned BOM `2025.05.01` selects UI/Foundation **1.8.2** and Material3
**1.3.2**. Existing Google Maven sources under `/tmp/pr17_compose_sources/`
were inspected; relevant extracted entries byte-match the source jars.
No dependency was changed or build run.

- `ui-android/.../draw/DrawModifier.kt:106–112`: `drawBehind` calls its drawing
  lambda **before** `drawContent()`.
- `material3-android/.../OutlinedTextField.kt:207–274`: the public wrapper
  creates `BasicTextField` with `OutlinedTextFieldDefaults.DecorationBox` and
  its default `Container`.
- `TextFieldDefaults.kt:768–800`: that container draws `textFieldBackground`
  after the outer drawBehind. `internal/TextFieldImpl.kt:320–325` confirms
  the background draws the container's shape with the supplied color.
- `OutlinedTextField.kt:712–743,1035–1039`: layout measures container height
  as `totalHeight - supportingHeight` and places it at zero. The outer field
  height includes support. An outer drawWithContent alone would still put
  the rule below support.
- `TextFieldDefaults.kt:852–905` exposes the public DecorationBox `container`
  slot, preserving Material's existing input/support measurement and decoration.

Pinned-input SHA-256 (these are evidence identities, not verifier updates):

| File | SHA-256 |
| --- | --- |
| `compose-bom-2025.05.01.pom` | `00c14ac373ca3f73dcaa21524ce5f6564656a7485cf096e799449ab968ca57b8` |
| `material3-android-1.3.2-sources.jar` | `e58d105a4fcf1f65a6b2838ca8a6fe40b5784b5dba9ddcf27aabcdfc92b3e4e2` |
| `ui-android-1.8.2-sources.jar` | `963e15c8a0568027095e95461152f7e59213261389d00a90aa5c7b12ff0ce2a0` |

## Ranked falsifiable causes and candidate decision

1. **Opaque container overpaints the outer drawBehind rule — confirmed for
   observed empty fields.** Predicts surface-only bottom rows in both themes
   and APIs; logs, independent PNG crops and pinned draw order agree. Moving
   the same 1dp rule after container content should restore those pixels.
2. **Wrong geometry when supporting text exists — source-confirmed related
   mechanism, device observation pending.** Predicts the old outer rule below
   the support area; pinned measurement proves these are different heights.
   Paint in the container slot to use its measured input height, including
   multiline and actual font changes, without manual offsets or padding.
3. **Wrong state/color, crop or thin-line tolerance — rejected for observed
   RED.** Focus is correct, expected color equals the source role, density and
   bounds agree, the bottom row is fully surface-colored, and QuietGrid's
   corresponding capture passes. No test assertion needs changing.

The smallest supported public mechanism is to use the same pinned
`BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox` only for InkPaper,
and draw the existing 1dp rule **after** the existing container background
inside its `container` slot. Keep QuietGrid's public `OutlinedTextField`
path, border, and all common slots/colors. Preserve the public wrapper's
minimum sizes, explicit text-style color precedence, selection/cursor colors,
localized default error semantics, enabled state, transforms, line settings,
focus interaction source and callback forwarding. Capture palette/state in
composition before the non-composable draw lambda. No arbitrary geometry,
new spacing, palette/rating changes, test changes or verifier weakening.

## Candidate verification and remaining device work

The candidate changes only
[BiteComponents.kt](../../android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt)
and adds this report. InkPaper now uses the same `BasicTextField` and outlined
decoration as the pinned public Material wrapper. Its `Container` receives
`Modifier.drawWithContent`: `drawContent()` first, then the original 1dp rule
at `size.height - 0.5dp`. The container's own measured height excludes support.
The shared colors are moved without changing any binding; the Classic call's
arguments, border/modifier chain, label and support slots match starting HEAD.
The InkPaper wrapper keeps Material's text-style color precedence, selection
colors, cursor, min sizes and localized default error message (the pinned
Material implementation resolves `androidx.compose.ui.R.string.default_error_message`).

Lightweight checks ran after the candidate was applied:

| Exact command / check | Result |
| --- | --- |
| `python3 -B android/tools/test_pr18_theme_regressions.py` | Exit 0; all 7 existing F1/F2/F4 tests passed |
| `python3 -B android/tools/verify_bite_visuals.py` | Exit 0; retained contrast/native/vector/tag and protected-file gates plus InkPaper roles passed |
| `python3 -B /tmp/dining-f3-source-audit.py` | Exit 0; Kotlin delimiter check, Classic call/color/slot equivalence, InkPaper forwarding and pinned wrapper contract audit passed |
| `git diff --check` | Exit 0 |
| `git diff --cached --check` | Exit 0; staged candidate and report passed before committing |

The temporary source audit normalizes comments, whitespace and optional
semicolons for call comparisons; it preserves literal strings. It also checks
that all other component bodies and the public field signature are byte-identical,
**712 other tracked files** are unchanged, all **80 protected hashes** match,
and only this report is added. The F3 tests, F1/F2/F4 fixes, pinned dependencies,
verifiers, hash entries, versions, backend/migrations, F5/F6 and CI/release
policy remain untouched. The independent original-PNG check above establishes
observed RED, not a synthetic candidate rendering result.

No Android GREEN or compilation success is claimed. A new parent-run
Actions execution must test the unchanged F3 method on API26/API35 in both
styles/themes through all nine states and actual fonts 1.0/1.3/1.31/2.0,
including rule rows/input/support geometry, text fit, spoken label, focus,
enabled/disabled input callbacks, and supporting/error foregrounds. F1/F2/F4
device acceptance also remains pending; this candidate does not address F5/F6.

No local Gradle/JVM/emulator/npm build, push, CI trigger, merge, tag,
deployment, credential access or external-system mutation was performed.
Stop after the local candidate commit; its full SHA is returned in the final report.
