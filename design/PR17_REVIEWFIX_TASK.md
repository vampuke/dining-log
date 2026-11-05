# PR17 评审修复任务书（关闭 M1/M2 + 顺带 N2/N3）

评审文档：`design/pr17-review/REVIEW.md`（结论「不可合并」，BLOCKER 0 / MAJOR 2 / NIT 4）。你的工作是关闭两个 MAJOR 并顺带修 N2/N3，最终让 PR 可合并。N1 是文档笔误（下次任务书基线写 `eaa8e42`，本条不用改文件）；N4 是增强建议（本轮**不做**，在 NOTES 里注明留待后续）。

## M1 — 列表首屏完整卡数验收（PLAN:64/:178/:184）

PLAN 承诺「同条件 before/after 比较，普通字体完整可读卡数不得下降」。现状：测试只数前三张 + 用被祖先裁切的 `boundsInRoot` 判定完整性 + 无基线。

修法（按评审 :39 的具体修法执行）：
1. **正确的完整性判定**：用 `SemanticsNode.positionInRoot + size`（未裁切矩形）与「列表可见区域且 `floating-tabs.top` 以上」比较，枚举全部实际可见 record 卡（不固定前三张）。
2. **真实基线**：在 master `eaa8e42` 与实现 `5f676f8` 各采集一次五类列表（餐厅/搜索/店内菜品/到店/历史）的同 fixture 首屏卡数与截图。实现方式自选：a) 两个 instrumentation variant 按 SHA 切换（推荐：测试里读一个 `BASELINE_MODE` extra 或直接在 CI 里跑两次，第二次 checkout 基线 commit 只跑这个测试类）；b) 把基线卡数硬编码为期望值（先在基线 commit 跑一次拿到数字，写进测试断言 `after >= baseline`）。选 b 的话必须把基线数字的来源 SHA 写进注释。
3. **归档**：五类列表的 before/after 卡数、截图、提交 SHA、设备参数写 `design/pr17-discussion/evidence/M1-list-firstscreen.md`，并把 `design/pr17-discussion/evidence/` 纳入 git（当前为空目录）。NOTES 的 M1 待验收状态改为已完成。
4. 若实测 after < before：停下来在 NOTES 报告退化数字与原因，不放宽断言。

## M2 — Apply 弹窗真实 font200 + 深色覆盖（PLAN:82/:168/:171/:185/:186）

现状：`ApplyFieldsDialog` 是 Compose Dialog，内容用窗口自身 density（requested 2 / actual 1），现有 font2.0 截图名不副实；且矩阵固定 light。

修法（按评审 :56 执行）：
1. **真实窗口字体**：在 instrumentation fixture 中设置系统 `Settings.System.FONT_SCALE`（`uiAutomation.executeShellCommand("settings put system font_scale 2.0")` + recreate Activity 或等价机制），使 Dialog window 真实获得 2.0；`try/finally` 恢复 1.0。若该路径在 API26/35 有兼容问题，改用 `ActivityScenario.recreate` 或在 dialog 内容外再包一层显式 `CompositionLocalProvider(LocalDensity provides ...)` 的**测试专用包装**（不改生产代码——包装只存在于 Pr17UiTest 里，产品 Dialog 不动）。
2. **矩阵补齐**：Apply 配对布局覆盖 font 1/1.3/1.31/2 × light/dark；每轮断言 `layoutInput.density.fontScale == 实际窗口字体`（requested==actual 才算过）；逐段滚到当前值/拟修改值、长地址展开全文、保存/返回 footer 可达；截图命名 `pr17-apply-*-actualFont<实际值>-<主题>`。
3. 截图/manifest 记录 requested 与 actual 两个值，杜绝「font2.0 实际 1.0」的口径问题。NOTES 的 M2 状态更新。

## N2 — e2e 直连 DELETE 的 401 语义（评审 :74）

`ProductEndToEndTest.kt:350` 的 OkHttp DELETE：非 204 响应先读 body 解析 auth error，`TOKEN_EXPIRED` 转 `AuthFailure` 抛出，让外层 `RefreshCoordinator.authenticated` 用同一 Idempotency-Key 重试；最终仍断言 204。被撤销 other 的 access 检查先证明删除前 token 有效（profile 200），删除后核对 `SESSION_REVOKED`；现有 refresh SESSION_REVOKED 断言保留。

## N3 — saved 累计断言（评审 :80）

`Pr17UiTest.kt:568`：点击前存 `beforeSaved`，断言 `saved == beforeSaved + 1`（消息带轮次字体/主题）。

## 纪律

- 不改生产布局、不改视觉守卫、不放宽任何既有断言；M1/M2/N2/N3 全部是测试与证据工作。
- 本机不跑 gradle/npm。
- 一次提交推同一分支，message `fix: PR17 review fixes (M1 list baseline, M2 apply real font200+dark, N2 delete retry, N3 saved delta)`。
- NOTES.md 追加「Review round」一节：M1/M2 关闭证据、N2/N3 修复说明、N4 声明不做及理由。
- REVIEW.md 不改（它是评审者的记录）。

## 验收

推送后助手跑 CI；全绿后助手解析 HTML 报告与 evidence 文档确认 M1/M2 关闭，然后合并。
