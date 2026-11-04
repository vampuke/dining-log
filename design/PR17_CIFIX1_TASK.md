# PR17 CI 修复任务书（第一轮：instrumentation 双 API 失败）

CI run `41879322769`：backend ✅ / android(编译+单测) ✅ / **android-auth-instrumentation (26) ❌ / (35) ❌**。

分支 `feat/pr17-density-and-sliding-sessions`，HEAD `35b74a1`。日志已存 `/tmp/pr17_i35.log`（API35 job `126747431138`）；证据 artifact 已解压 `/tmp/pr17_art35/`（含 `pr17-layout-api35.txt` 与 pr17-*.png 截图）。

## 失败清单（API35 实测）

| 测试 | 失败断言 |
|---|---|
| settingsProfileUpdatePassword…WithoutSessionManagement | `overflow at font=1.0: owner` |
| receiptTailIsMeasuredAgainstTheOriginalCard… | `overflow at font=1.0: BITE! 食记 · 探店小票 · 版本 3` |
| applyPreviewAndComparisonColumns… | `expected:<2.0> but was:<1.0>` |
| editorKeepsTheOuterRatingCard… | `overflow at font=1.0: 评分 · 选填` |
| historyPairsFullTextTime… | `overflow at font=1.0: 评价` |
| detailEntityStateMatrix… | `overflow at font=1.0: ‹ 返回` |
| ratingEditorNullSteppers… | `filter-unrated keeps a 48dp touch target` |
| pageActionsAndHeadersUseContentWidthBoundary… | `overflow at font=1.0: BITE! 食记 · 探店小票 · 版本 3` |
| updateStageMatrix… | `overflow at font=1.0: 更新说明` |
| listKindsBrandHeaders… | `overflow at font=1.0: 店内菜品` |
| **ProductVisualTest.bothTabsAndPageTitlesRemainUnclippedAt200Percent…**（旧测试回归！） | `200% detail title 探店小票: size=312 x 112, widthOverflow=true, heightOverflow=false` |

API26 失败集合基本相同（job 日志另行拉取核对：`/tmp/pr17_i26.log` 先下载）。

## 助手已做的诊断（供你复核，不许照抄结论——逐条验证后再修）

1. **「expected:<2.0> but was:<1.0>」（apply 测试）是决定性线索**：`assertTextFits` 断言 `layout.layoutInput.density.fontScale == scale`，挂时 layout 的 fontScale=1.0 而 scale=2.0——即 **ApplyFieldsDialog（Compose Dialog）里的 Text 用系统 density（fontScale 1.0）排版，没吃到测试 setContent 里 `CompositionLocalProvider(LocalDensity provides Density(measuredDensity, scale))` 的覆盖**。Compose 的 `Dialog`/`AlertDialog` 内容在独立 window 的 subcomposition 里，density 由 dialog window 的 resources 决定，自定义 LocalDensity 不生效（需你核实 Compose 版本的实际行为——仓库 Compose BOM 见 `android/gradle/libs.versions.toml`）。
   - 推论：**assertVisibleTextFits 全页扫描会扫到对话框节点**（compose 测试框架把 dialog window 注册进同一 hierarchy），这些节点 fontScale 恒为 1.0。
   - 修法方向（你裁决）：a) assertTextFits 跳过 `layout.layoutInput.density.fontScale != scale` 的节点（视为「外部窗口文本」，单独记录 evidence 不断言）；b) 或对 dialog 内文本单独用其自身 fontScale 做溢出断言。**不许放宽 hasVisualOverflow 本身**。
2. **ProductVisualTest 200% detail title 回归是唯一已证实的「真布局挤压」**：detail 页 PageHeader 现在带 trailing（刷新按钮）。fontScale 2.0 走 ResponsiveHeading 回退分支：`Column { title(fillMaxWidth)=Row{back; Column(weight){title}}; trailing下一行 }`——title 宽度 = 内容宽 - back 按钮宽。font200 时 back「‹ 返回」变宽（labelLarge 14sp×2），title 只剩 ~139dp（实测 size=312px @density~2.24），「探店小票」36sp×4字≈322px 超出。旧版 PageHeader 无 trailing 时同结构却绿——说明旧版该行没有 trailing 参与宽度分配，或旧 title 约束不同。**你必须先在本地读新旧 PageHeader 布局推导出精确差异再修**；修法必须保证 font200 时 title 独占整行（back 不应参与挤压 title——考虑回退分支里把 back 放 title 上一行，或 title lambda 里不给 back 与 title 同 Row），且 font≤1.3 时行为不变。
3. **font=1.0 的 7 处溢出报告**：这些短文本（owner=5字符、‹ 返回、评价、店内菜品、更新说明、评分 · 选填、BITE!…版本 3）在 360dp 宽度下数学上不可能真溢出。结合发现 1 的 stale-layout 证据，主嫌疑是 **`key(scale, width)` 重建子树后 GetTextLayoutResult 返回了上一 density/约束下缓存的 TextLayoutResult**（compose 语义缓存未随重建失效），或 assertVisibleTextFits 扫到了对话框窗口节点。你需要在修复中让断言先 `compose.waitForIdle()` + `compose.awaitIdle()`（若 API 可用）并复核：溢出节点是否都在对话框内、或 layout.fontScale 与当前 scale 不一致——**若不一致则按发现 1 的跳过规则处理，不误报**。若复核后发现 fontScale 一致且仍溢出，那是真溢出，按真溢出修布局。
4. **`filter-unrated keeps a 48dp touch target`**：G3 改动把 Checkbox 行改两列后，`filter-unrated` 的 bounds 高度 <47.5dp（Pr17UiTest.assertPair 的断言，`box.height / pixelsPerDp >= 47.5f`）。Checkbox 本身默认 48dp，但 G3 用 `Row { Checkbox; GridText(weight) }` 且删了 label 的 `padding(top=12dp)`——行高塌到 Checkbox 实际高度以下？你核对 G3 新布局：给 Checkbox 行加 `Modifier.heightIn(min = 48.dp)`（加在 Checkbox 本体或行上，保持 tag 在 Checkbox 上）。landscape/横排分支同理。
5. **注意 API26 日志**：先下载 job 日志（check-runs API 找 26 的 job id），确认失败集合与 35 是否一致；若 26 还有额外失败一并修。

## 修复纪律

- 只改测试基建与 PLAN 中布局实现的相关点位；**不许删断言了事、不许放宽 hasVisualOverflow 语义、不许把 48dp 断言容差放大**。
- 布局修复（发现 2）以「ProductVisualTest 200% 断言恢复绿 + 截图证据」为准。
- 若确证 Compose Dialog 不继承自定义 Density：Pr17UiTest 中所有 dialog 内的 assertTextFits 目标（apply-comparison、update-stage、settings 对话框文本）改用「节点自身 layoutInput 的 fontScale」断言溢出（跳过 scale 一致性检查），并在 NOTES 里写明这是测试基建限制不是产品缺陷。
- 全部修改一次提交推到同一分支（不新开分支），commit message `fix: PR17 instrumentation round 1 (dialog density, font200 title squeeze, touch target)`。
- NOTES.md 追加「Round 1 修复」一节：每条失败的根因结论 + 修复点 + 证据。

## 验收

推送后 CI 四个 job 全绿才算完成（本轮只要求你修完推上去；CI 由助手监控，若再挂会开第二轮任务书）。
