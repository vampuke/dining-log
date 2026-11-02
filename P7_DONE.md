# P7 — BITE! sticker redesign

Branch: `p7-bite-sticker-redesign`. PR: https://github.com/vampuke/dining-log/pull/11.

Implementation is visual only. Public `QuietGrid*` symbol names and semantics keys remain as compatibility names; their values now implement BITE. Backend, `android/core`, `android/data`, `ProductViewModel`, navigation transitions, account operations and draft/conflict rules are unchanged.

## Verification matrix

| Requirement | Status | Evidence / limit |
|---|---|---|
| Read complete task and authoritative HTML before editing | verified | Task and all reference sections, including the 8 hand-drawn SVG definitions |
| Light + complete dark theme; existing system/user choice follows correctly | verified | Both token tables; native day/night and system-bar assertions pass on API 26/35 |
| Visible dark outlines, hard shadows and subdued dots | verified | Theme-specific tokens, screenshot inspection; stroke/shadow >=3:1 on bg/surface |
| All intended body-text combinations >=4.5:1 | verified | 46 WCAG measurements below; 0 failures; persisted `contrast.json` |
| No bitmap app assets | verified | Both Android resource trees checked for png/jpg/jpeg/webp; evidence screenshots are outside APK resources |
| Eight reference icon vectors and adaptive/round/monochrome launcher | verified | XML conversion and rendered icon tests in both wallpaper colors on API 26/35 |
| Every existing test tag preserved | verified | Master-baseline expressions checked; no selectors changed |
| >=3 complete records at 360×640 in both themes | verified | API 26: 4; API 35: 3, in both fixture and real-API tests; logs and captures below |
| Empty optional fields omitted; no null/-- | verified | Existing minimal-record and real-API assertions pass on both APIs |
| Tab reselect preserves entry and scrolls to top | verified | 4 reselections per tab, unchanged entry and refresh counts, both APIs |
| Page motion respects remove animations; dark frames have no white flash | verified | 15 frames × 16 ms, 0 white pixels, system animation setting observed, both APIs |
| 200% titles, full input/review values, keyboard and save remain readable/reachable | verified | Existing unweakened layout/IME/long-text tests pass on both APIs |
| App update remains in 我的; accessible labels and >=48 dp actions | verified | Update tests pass; navigation descriptions and button/chip minimum sizes retained |
| Backend/API/domain/Room/cache/drafts/conflicts/auth flow untouched | verified | Protected-file hashes and git diff; 70 unit tests and functional instrumentation pass |
| Local Android unit tests/lint | unverified locally | Attempt stopped before tasks: AGP 8.9.2 is uncached and cannot resolve offline; host lacks SDK/emulator |
| CI backend, Android build/unit/lint, API 26 and API 35 | verified | [Passing run 41369161142](https://github.com/vampuke/dining-log/actions/runs/41369161142), code commit `e854c919ff` |
| List/detail/editor in both themes, on both APIs | verified | 12 required-screen captures plus 4 rated-fixture density captures, provenance below |
| Physical handset / OEM launcher testing | unverified | No connected physical device; emulator coverage is API 26/35 |

Local Android command attempted (not passed):

```sh
JAVA_OPTS=-Xmx128m ./android/gradlew -p android :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx256m
```

Gradle 8.11.1 ran, then failed resolving `com.android.application:8.9.2` in offline mode. No unit test or lint task executed locally. The separate token/vector/baseline checks passed locally.

CI Android evidence: [run 41369161142](https://github.com/vampuke/dining-log/actions/runs/41369161142), source commit `e854c919ff0c3035cf8192bba47bbe91a2063a81`. Debug app and instrumentation APK builds, all four unit-test tasks, and `:app:lintDebug` succeeded. Downloaded XML reports show **70 unit tests, 0 failures, 0 skipped**. Both emulator reports show **27 tests, 0 failures, 0 skipped** each. Backend CI is green. Subsequent commits only add this report and evidence.

## Design choices and schema limits

- Compact masthead, three-column loaded statistics, outlined search, rating filter capsules and tilted cards preserve the small-screen density requirement. Card summaries retain category/cuisine, actual address excerpt and real latest visit date, or label `updatedAt` as 更新. Full review/dish previews are omitted on the restaurant list: its existing summary has neither review nor dish names. These are shown in the existing detail and child-list flows.
- Loaded count, average of rated loaded summaries, and sum of their existing dish counts are explicitly labelled 已加载. Missing counts display 未提供; absent ratings display 未评分. No total-range statistics are implied.
- Dish and restaurant sticker tiers derive from existing tenths only: >=46, >=40, >=32, below32; unrated stays neutral. Quick rating capsules call the existing filter callback. The prototype's modified-only filter is omitted because the current list-filter contract has no equivalent.
- Detail combines receipt edges, outlined paper modules, rating die-cut, address sticker, numbered dish rows, real current-version rail/history entry and decorative barcode. Full revision timelines remain in the existing history route; no additional request is introduced to populate an inline fake history.
- Restaurant/dish editing keeps its existing save-restaurant-first child management. The prototype's unsaved dynamic multi-dish submission and special revision-note state are omitted because they would change the draft/write semantics. Existing note field and separate child add/edit/delete flows are preserved.
- Authentication, recovery, account, session, password and comparison screens use the shared visual controls. The native splash stays matched to day/night paper. Dark mode uses warm neutral surfaces and subdued dots; pop stickers retain saturated colors with near-black text.
- Hard shadows use solid `drawBehind` geometry, zero blur. Primary controls translate 2 dp and reduce shadow 4→1 dp over 120 ms, respecting the existing remove-animations observer. Die-cut edges use 3 dp rims; receipt teeth use 16 dp periods/8 dp depth. Existing vector navigation silhouettes remain distinct when selected, with bolder rounded strokes.

## Light token table

| Token | Hex | Use |
|---|---|---|
| `bg` | `#FFFDF5` | Screen and native window/splash background |
| `surface` | `#FFFFFF` | Cards, fields, dialogs and navigation |
| `ink` | `#111111` | Primary body text |
| `secondary` | `#514B40` | Metadata, supporting/disabled text |
| `tertiary` | `#635C50` | Secondary Material role |
| `border` | `#111111` | Card/field outlines and revision rails |
| `accent` | `#FFD60A` | Rating meter and decorative yellow |
| `onAccent` | `#111111` | Button lettering |
| `danger` | `#A61B27` | Error text and revision stamp |
| `accentInk` | `#111111` | Action/link and selected navigation lettering |
| `cta` | `#FFD60A` | Primary button fill |
| `accentSoft` | `#FFD60A` | Selected navigation/chip fill |
| `borderStrong` | `#111111` | Strong outlines and empty meter |
| `gallery` | `#FAF4E4` | Gallery/background compatibility role |
| `paper` | `#FAF4E4` | Secondary form/receipt paper |
| `shadow` | `#111111` | Solid 4 dp offset shadow |
| `dieCut` | `#FFFFFF` | 3 dp die-cut outer rim |
| `dots` | `#D8D0C1` | Low-contrast decorative paper dots |
| `butter` | `#FFD60A` | High rating / yellow stickers |
| `popRed` | `#FF595E` | Low rating / add shortcut / slider thumb |
| `mint` | `#06D6A0` | Recommended rating / date stickers |
| `klein` | `#3A86FF` | Address sticker |
| `lilac` | `#8338EC` | V6 icon background only; no body text |
| `peach` | `#FF9F1C` | Ordinary rating stickers |
| `bubble` | `#FF70A6` | Decorative sticker palette |
| `stickerInk` | `#111111` | Lettering on every saturated fill |

## Light measured body contrast

Computed from the implemented sRGB values using WCAG relative luminance, with the 0.04045 transfer-function threshold. All sizes use the stricter 4.5:1 body threshold. Lilac has no body-text use. Decorative dots/barcode are not text.

| Foreground token (hex) | Background token (hex) | Ratio | Result |
|---|---|---:|---|
| ink `#111111` | bg `#FFFDF5` | 18.5419:1 | PASS |
| ink `#111111` | surface `#FFFFFF` | 18.8831:1 | PASS |
| ink `#111111` | paper `#FAF4E4` | 17.1974:1 | PASS |
| secondary `#514B40` | bg `#FFFDF5` | 8.4847:1 | PASS |
| secondary `#514B40` | surface `#FFFFFF` | 8.6408:1 | PASS |
| secondary `#514B40` | paper `#FAF4E4` | 7.8694:1 | PASS |
| tertiary `#635C50` | bg `#FFFDF5` | 6.4901:1 | PASS |
| tertiary `#635C50` | surface `#FFFFFF` | 6.6095:1 | PASS |
| tertiary `#635C50` | paper `#FAF4E4` | 6.0195:1 | PASS |
| danger `#A61B27` | bg `#FFFDF5` | 7.3449:1 | PASS |
| danger `#A61B27` | surface `#FFFFFF` | 7.4800:1 | PASS |
| danger `#A61B27` | paper `#FAF4E4` | 6.8123:1 | PASS |
| accentInk `#111111` | bg `#FFFDF5` | 18.5419:1 | PASS |
| accentInk `#111111` | surface `#FFFFFF` | 18.8831:1 | PASS |
| accentInk `#111111` | paper `#FAF4E4` | 17.1974:1 | PASS |
| onAccent `#111111` | cta `#FFD60A` | 13.3755:1 | PASS |
| accentInk `#111111` | accentSoft `#FFD60A` | 13.3755:1 | PASS |
| stickerInk `#111111` | butter `#FFD60A` | 13.3755:1 | PASS |
| stickerInk `#111111` | popRed `#FF595E` | 6.1528:1 | PASS |
| stickerInk `#111111` | mint `#06D6A0` | 10.0116:1 | PASS |
| stickerInk `#111111` | klein `#3A86FF` | 5.4257:1 | PASS |
| stickerInk `#111111` | peach `#FF9F1C` | 9.1970:1 | PASS |
| stickerInk `#111111` | bubble `#FF70A6` | 7.3017:1 | PASS |

Failures: **0**.

## Dark token table

| Token | Hex | Use |
|---|---|---|
| `bg` | `#14120E` | Screen and native window/splash background |
| `surface` | `#24211B` | Cards, fields, dialogs and navigation |
| `ink` | `#F5F2E8` | Primary body text |
| `secondary` | `#D3CBBB` | Metadata, supporting/disabled text |
| `tertiary` | `#BCB3A2` | Secondary Material role |
| `border` | `#F5F2E8` | Card/field outlines and revision rails |
| `accent` | `#FFD60A` | Rating meter and decorative yellow |
| `onAccent` | `#111111` | Button lettering |
| `danger` | `#FF9398` | Error text and revision stamp |
| `accentInk` | `#F5F2E8` | Action/link and selected navigation lettering |
| `cta` | `#FFD60A` | Primary button fill |
| `accentSoft` | `#393326` | Selected navigation/chip fill |
| `borderStrong` | `#F5F2E8` | Strong outlines and empty meter |
| `gallery` | `#14120E` | Gallery/background compatibility role |
| `paper` | `#302B23` | Secondary form/receipt paper |
| `shadow` | `#B5A88C` | Solid 4 dp offset shadow |
| `dieCut` | `#E6DDCA` | 3 dp die-cut outer rim |
| `dots` | `#343026` | Low-contrast decorative paper dots |
| `butter` | `#FFD60A` | High rating / yellow stickers |
| `popRed` | `#FF595E` | Low rating / add shortcut / slider thumb |
| `mint` | `#06D6A0` | Recommended rating / date stickers |
| `klein` | `#3A86FF` | Address sticker |
| `lilac` | `#8338EC` | V6 icon background only; no body text |
| `peach` | `#FF9F1C` | Ordinary rating stickers |
| `bubble` | `#FF70A6` | Decorative sticker palette |
| `stickerInk` | `#111111` | Lettering on every saturated fill |

## Dark measured body contrast

Computed from the implemented sRGB values using WCAG relative luminance, with the 0.04045 transfer-function threshold. All sizes use the stricter 4.5:1 body threshold. Lilac has no body-text use. Decorative dots/barcode are not text.

| Foreground token (hex) | Background token (hex) | Ratio | Result |
|---|---|---:|---|
| ink `#F5F2E8` | bg `#14120E` | 16.7009:1 | PASS |
| ink `#F5F2E8` | surface `#24211B` | 14.3296:1 | PASS |
| ink `#F5F2E8` | paper `#302B23` | 12.5367:1 | PASS |
| secondary `#D3CBBB` | bg `#14120E` | 11.6067:1 | PASS |
| secondary `#D3CBBB` | surface `#24211B` | 9.9586:1 | PASS |
| secondary `#D3CBBB` | paper `#302B23` | 8.7126:1 | PASS |
| tertiary `#BCB3A2` | bg `#14120E` | 9.0041:1 | PASS |
| tertiary `#BCB3A2` | surface `#24211B` | 7.7256:1 | PASS |
| tertiary `#BCB3A2` | paper `#302B23` | 6.7590:1 | PASS |
| danger `#FF9398` | bg `#14120E` | 8.7999:1 | PASS |
| danger `#FF9398` | surface `#24211B` | 7.5505:1 | PASS |
| danger `#FF9398` | paper `#302B23` | 6.6058:1 | PASS |
| accentInk `#F5F2E8` | bg `#14120E` | 16.7009:1 | PASS |
| accentInk `#F5F2E8` | surface `#24211B` | 14.3296:1 | PASS |
| accentInk `#F5F2E8` | paper `#302B23` | 12.5367:1 | PASS |
| onAccent `#111111` | cta `#FFD60A` | 13.3755:1 | PASS |
| accentInk `#F5F2E8` | accentSoft `#393326` | 11.1899:1 | PASS |
| stickerInk `#111111` | butter `#FFD60A` | 13.3755:1 | PASS |
| stickerInk `#111111` | popRed `#FF595E` | 6.1528:1 | PASS |
| stickerInk `#111111` | mint `#06D6A0` | 10.0116:1 | PASS |
| stickerInk `#111111` | klein `#3A86FF` | 5.4257:1 | PASS |
| stickerInk `#111111` | peach `#FF9F1C` | 9.1970:1 | PASS |
| stickerInk `#111111` | bubble `#FF70A6` | 7.3017:1 | PASS |

Failures: **0**.

## Screenshot evidence

Actual captures from passing run **41369161142**, code commit `e854c919ff0c3035cf8192bba47bbe91a2063a81`. List images exercise the real isolated test API with synthetic test records; detail/editor images use the existing visual-test fixtures. They are not production user data or generated mockups. All image dimensions and SHA-256 checksums are retained in [evidence.json](design/bite/p7-evidence/evidence.json).

| API / screen | Light | Dark |
|---|---|---|
| 26 / List | [Screenshot](design/bite/p7-evidence/p6-real-light-360x640-api26.png) | [Screenshot](design/bite/p7-evidence/p6-real-dark-360x640-api26.png) |
| 26 / Detail | [Screenshot](design/bite/p7-evidence/light-detail-api26.png) | [Screenshot](design/bite/p7-evidence/dark-detail-api26.png) |
| 26 / Editor | [Screenshot](design/bite/p7-evidence/light-edit-api26.png) | [Screenshot](design/bite/p7-evidence/dark-edit-api26.png) |
| 35 / List | [Screenshot](design/bite/p7-evidence/p6-real-light-360x640-api35.png) | [Screenshot](design/bite/p7-evidence/p6-real-dark-360x640-api35.png) |
| 35 / Detail | [Screenshot](design/bite/p7-evidence/light-detail-api35.png) | [Screenshot](design/bite/p7-evidence/dark-detail-api35.png) |
| 35 / Editor | [Screenshot](design/bite/p7-evidence/light-edit-api35.png) | [Screenshot](design/bite/p7-evidence/dark-edit-api35.png) |

Rated-fixture first-screen evidence:

| API | Complete records, both themes | Light | Dark |
|---|---:|---|---|
| 26 | 4 | [360×640](design/bite/p7-evidence/p6-light-360x640-api26.png) | [360×640](design/bite/p7-evidence/p6-dark-360x640-api26.png) |
| 35 | 3 | [360×640](design/bite/p7-evidence/p6-light-360x640-api35.png) | [360×640](design/bite/p7-evidence/p6-dark-360x640-api35.png) |

The real-API captures also contain 4 complete records on API 26 and 3 on API 35, in both themes. [API 26 runtime log](design/bite/p7-evidence/runtime-api26.log) and [API 35 runtime log](design/bite/p7-evidence/runtime-api35.log) record the measured counts, selected-icon contrast, tab reselections and zero-white-frame assertions. The copied dark screenshots additionally contain zero pure-white pixels. These are verification artifacts outside Android resources.

## VectorDrawable inventory

All eight live under `android/app/src/main/res/drawable/`:

1. `bite_v1_burger.xml` — V1 smiling burger (selected launcher).
2. `bite_v2_ramen.xml` — V2 ramen.
3. `bite_v3_receipt.xml` — V3 receipt stamp.
4. `bite_v4_bite.xml` — V4 bitten toast.
5. `bite_v5_radar.xml` — V5 fork location pin.
6. `bite_v6_price.xml` — V6 crown price tag.
7. `bite_v7_cloche.xml` — V7 cloche.
8. `bite_v8_notebook.xml` — V8 journal.

Replaced `ic_launcher_foreground.xml` and `ic_launcher_background.xml`; created `ic_launcher_monochrome.xml`. API 33 normal and round adaptive icons reference the dedicated monochrome resource. `android/feature/src/main/res/drawable/bite_v1_burger.xml` is the same generated V1 vector used in the masthead; `drawable/bite_v1_burger_dark.xml` substitutes the warm die-cut rim for pure white and is selected from the Compose theme, including an explicit dark choice on a light system. Conversion is reproducible with `python3 android/tools/convert_bite_icons.py`.

## Test changes, item by item

1. `P6UiTest.actualAdaptiveRoundAndMonochromeIconsAndNativeNightThemeResolveCorrectly`: expected rendered foreground changes from D2 `#FBFBFA` to BITE `#111111`; background changes from D2 `#23543C` to BITE `#FFD60A`. The existing >50/>100 pixel assertions, both wallpaper cases, >=4.5 contrast threshold, adaptive/round/monochrome existence and matching native night checks remain intact.
2. `verify_p6_visuals.py`: retained as the CI entry point, now invokes `verify_bite_visuals.py`. The D2 assumption that a launcher must have a single fill is replaced with verification of the multi-color reference vectors and dedicated alpha-only monochrome. The >=4.5 body/navigation threshold, native theme checks and no-bitmap checks remain; all tag expressions and protected-tree checks are added.

3. `AndroidAuthTest.recoveryDarkUsesApprovedTokens`: exact background assertion changes from D2 `#0F0F0E` to BITE `#14120E`; equality and recovery-form visibility assertions are retained.

The first emulator run found the obsolete auth color, a light-resource rim under a manually selected dark Compose theme, and a brand title overflow at 200% font. The fixes use an explicit dark masthead vector and a full-width Chinese title 食记 at large fonts; explicit width avoids the CJK fallback intrinsic-width overflow. API 35 additionally exposed the three-record density regression; compact card/header spacing and an explicit 64 dp navigation body plus actual system-bar padding fix it. The same zero-white-pixels, no-overflow and >=3 complete-record assertions remain intact.

No selector changes and no weakened assertions. Tests still assert three complete records, preserved navigation entries, actual pixel colors, no white navigation flash, 200% text without overflow, save above the real keyboard, explicit conflict choice and one-tenth rating steps.
