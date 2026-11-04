# PR17 CI 修复任务书（第二轮：单测残留 ratingEditor :401 边界断言）

CI run `41886927903`（HEAD `9d1b2fd`）：backend ✅ / android ✅ / instrumentation 两台 API 各**只剩 1 个失败**：

`Pr17UiTest.ratingEditorNullSteppersBoundariesAndFilterControlsDoNotCrossValues` → `java.lang.AssertionError`（无消息）at **`Pr17UiTest.kt:401`** = `assertTrue(max.left > min.right)`（G2 横排判定）。Round 1 其余 10 个失败全部转绿（HTML 报告核实：apply/detail/editor/history/update 等 passed）。

## 助手诊断（已用 CI 证据 narrowed 到单一问题）

证据（`pr17-layout-api35.txt`）：
- font=1.0 轮次完整记录了 content=319/320/491 三轮的 `filter font=...` 行（记录点 :422 在轮次末尾），**492 轮没有记录** → 挂在 :401（记录点之前）。
- 491 轮：`minimum=Rect(4,252,515,280) maximum=Rect(4,448,515,476)` —— 两编辑器同列竖排 ✓（491<492 竖排正确），`assertEquals(min.left, max.left, 1f)` 过。
- 492 轮挂 → 真实执行时 `max.left <= min.right`，即 **492 轮没有进横排分支**（或 bounds 语义与预期不符）。

疑点（你要先证实再修，不许照抄）：
1. 492 轮 `BoxWithConstraints` 的**实际 maxWidth 值**是多少？测试 `configure(font, night, content + 28)` 传 width=520，`ListFilterPanel` 外框 `padding(14.dp)`×2 → 理论 492.0 恰好等于阈值。`>= 492.dp` 在 dp→px 转换（density=1, exactPixels）下应为 492px>=492px=true。但若 `brutalBox` 的 stroke（2.5dp）或 `Column(spacedBy)` 之外的任何 1px 因素让实际内容宽 = 491.x，边界断言就会翻车。**这是测试用「恰好等于阈值」做边界轮次的结构脆弱性**，不是产品布局 bug——491/492 两个死边界轮次本质是在赌像素舍入。
2. `filter-min`/`filter-max` 的 Rect 右缘 515 超出了理论容器右缘（519-28=491+4=495?）——先算清 ListFilterPanel 实际嵌套 padding 链（外框 brutalBox→padding14→Column→BoxWithConstraints→RatingEditor），确认编辑器节点 bounds 的真实宽度语义，别把绘制溢出当成布局宽度。

## 修法（纪律：不许删断言、不许放宽 48dp、不许改生产布局）

1. **边界轮次去悬崖化**：把 `listOf(319, 320, 491, 492)` 改成有明显余量的两对轮次——竖排侧 `488`（<492 且远离 1px 悬崖）、横排侧 `496`（>492 有 4dp 余量）。同理 G3 的 `319/320` 对改为 `316/324`（阈值 320，两侧各留 4dp）。**同时保留**：在断言前 `recordEvidence` 输出每轮 BoxWithConstraints 的实际 maxWidth（在 ListFilterPanel 的 G2/G3 BoxWithConstraints 里加一行 `recordEvidence` 不行——那是生产代码；改为在测试里通过 `bounds("pr17:controls-scroll")` 与编辑器 bounds 推算，或直接用 `onRoot().fetchSemanticsNode()` 层级拿面板宽度）——你要设计一个**不改生产代码**就能记录实际宽度的取法。
2. **:401 断言补消息**：`assertTrue("at content=$content min=$min max=$max must be side-by-side", max.left > min.right)`，竖排分支同样补消息。以后再挂能直接看到实际值。
3. 顺带核对 ratingEditor 测试里其它裸 `assertTrue`（:303/:304/:401/:409）——:409 在 `runOnIdle` 里的 `assertTrue(filters.unrated)` 若挂也无消息，一并补消息（不改语义）。
4. 其余全部保持：Round 1 的修复、48dp `heightIn`、dialog scale 处理、重测机制都不动。
5. 一次提交推同一分支，message `fix: PR17 round 2 (de-cliff filter boundary rounds, assertion messages)`。NOTES.md 追加 Round 2 小节。

## 验收

推送后由助手监控 CI。若 496 轮仍竖排（即 G2 横排分支在真实环境根本不可达），那才是产品问题——届时停下来在 NOTES 里报告，不要继续猜。
