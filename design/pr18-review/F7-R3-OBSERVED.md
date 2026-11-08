# F7 R3 capture and physical-input boundary

These source corrections start at `d77041459c489c45b1cc9eee987de208d4412dad` on
`fix/pr18-f7-r3fix`. Earlier F7 notes describe historical candidates. This note
records the current mechanism and the scope of the evidence obtained locally.

`Pr18EvidenceBoundary.kt` is the pure Kotlin acceptance seam used by both Android
capture adapters and by physical input. A PNG seal freezes the observed capture
source, original encoded SHA256, captured pixel SHA256/dimensions, and outcome
before writing. Readback must match that expectation, including metadata. A
deferred receipt rechecks storage while retaining the original provenance.

| Finding | Source correction |
| --- | --- |
| F7-R3-001 | A ready, attached protected owner can yield observed `SOURCE_INVALID` denial with metadata and no PNG, or a wholly black/transparent successful copy labelled `SECURE_REDACTED`. Readable protected PNGs have a separate status/name. FLAG_SECURE remains checked before/after. Other failures and unprotected blank frames fail. |
| F7-R3-002 | Actual Dialog product owners supply pixels when dialogs exist; an intentionally blank host supplies protection context. Host and all dialogs retain flag checks. Both bitmap paths accept palette secondary/accent foreground and reject blank product content. |
| F7-R3-003 | The seal compares provenance with the observed capture attempt, validates the original encoder output, then compares readback bytes, decoded pixels/dimensions, and exact metadata. Explicit CONTROL sources cannot enter runtime persistence. Own-window provenance includes frame readiness, draw/commit observations and actual crop/result/flags. |
| F7-R3-004 | WINDOW bounds use root SCREEN minus root WINDOW offsets. Android local visibility is translated into SCREEN space; `getGlobalVisibleRect` is root-relative. The original IME save uses the physical boundary, every IME rectangle, and its original exact +1 write assertion. |
| F7-R3-005 | Generic actionable Compose surfaces, higher windows and all IME regions participate in exposure. A live target also binds root/token, accessibility window and semantics ID, clipped/visible SCREEN bounds, display and configuration. UiAutomation injects physical DOWN/UP events and rejects unavailable/failed injection. Original callbacks/state assertions remain. |

Observed locally: twelve behavioral RED→GREEN cycles, each RED compiling
successfully and failing the intended assertion. The final bounded Kotlin
1.9.24/JUnit 4.13.2 run compiled with exit 0 and returned `OK (25 tests)` with
exit 0. This executes the actual pure boundary with JVM ImageIO; it does not
compile or execute the Android adapters. Logs, commands, retained classes and
later source snapshots are in `/tmp/dining-release-20261107/f7-r3fix-work`.
The final run is `004-visible-green`.

The unchanged positive CONTROL asset is
`android/app/src/androidTest/assets/pr18-capture-controls/legacy-real-accent-api26.png`:
13,862 bytes, 945×1680, SHA256
`6cc97b08f0455ec77f5c823d96a69b1d5a2f4e5e41be293c918737adc7de0c71`.
It is the original `pr18-rating-40-ink_paper-dark-actualFont1.0-api26.png`
entry under `outputs/p4-screenshots/p4-screenshots/` in legacy run
42372736617's API26 artifact. It has zero primary ink and 16,838 accent foreground
pixels. Its fixture provenance is explicit. Negative images exist only in
memory and are never written as runtime screenshots.

The added `Pr18CaptureBoundaryTest` has six device controls for Android decoding,
byte/pixel/provenance substitution, null/decode/compress outcomes, fixture
rejection, and a real production Dialog capture. `Pr18PhysicalTouchBoundaryTest`
has six device controls, including a real offset Dialog, wrong owner/bounds,
hidden target, untagged Compose cover, native OnTouchListener cover and a
nonfocusable popup. Each occlusion negative first requires a successful physical
tap, then zero target callback delta and a relevant rejection. Native cover
callbacks must match the exact observed rejection phase. CONTROL scenes produce
geometry logs, with no screenshots.

STATIC preservation checks compare base/start/current inventories: all original
17 named-test files and 93 method names remain. Of 895 existing assertion calls
in the starting candidate, 894 remain identical and the original IME comparison
is rewritten for SCREEN coordinates. Original fonts, styles, themes, rotations,
16dp/48dp and callback gates remain in source; no Ignore/Assume or direct F7
view-dispatched clicks remain. The device source now has 105 methods across 19
files, including the twelve added controls. These are source counts.

Android SDK35/Kotlin2.1.21 compilation, API26/35 execution, actual secure
PixelCopy/input/IME/overlay timing, completion of every original iteration,
zero-skip candidate CI and artifact delivery await the parent. No Gradle,
emulator, Android build, push, deploy or model delegation ran here. The exact candidate
SHA and command/evidence manifest are delivered in
`/tmp/dining-release-20261107/f7-r3fix-implementation.json` for the parent's new
independent review session.
