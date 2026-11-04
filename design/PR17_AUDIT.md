# PR17 密度审计结论（助手初稿）→ 供 Codex 讨论并出最终计划

本文件是助手对全 App 密度问题的审计结论。**你的任务不是直接实现**——先逐条评审本文件，指出错误/遗漏/更优做法（写入 `design/pr17-discussion/DISCUSSION.md`），给出你的最终改动计划，然后回到实现。讨论文档与实现分开，两阶段都要留档。

## 红线（先读）

- 所有 testTag 一律保留原名，不删不改（e2e/小屏/视觉测试全靠它们）；新增 tag 用 `pr17:` 前缀。
- 视觉守卫 `verify_bite_visuals.py` / `verify_p6_visuals.py` 只认字面量 `testTag("...")`，不动守卫脚本本身。
- 48dp 触达：BiteButton/BiteTextButton 自带 `heightIn(min=48.dp)`，放进 Row 不许丢失。
- 大字体（fontScale>1.3）不许截断：横向排布的行必须允许换行（FlowRow 或 weight+ellipsis 策略），`ProductVisualTest` font200 截图必须仍过。
- 深浅两主题目测协调；黑色描边/硬投影语言不变；零位图。
- 受保护哈希名单里的文件若被改动，同 commit 刷新 `android/tools/p8_protected_hashes.json`（只改 UI kt 文件一般不在名单，先查再动）。

## 助手审计结论（逐条评审，可反驳）

### A. 详情页（用户截图点名的页面，重点）

代码位置 `DetailEditScreens.kt` `RecordDetailScreen`。

| # | 问题 | 现状（行号） | 改法提案 |
|---|------|------------|---------|
| A1 | 编辑/删除记录各占一行 | :80-81 | 同一 Row：编辑 weight(1f)，删除包内容 |
| A2 | 信息卡字段竖排（标签一行、值一行） | :58-66 | 「标签：值」同行紧凑对，两列网格（Row 两 Column weight(1f)）；地址因文本长允许占满整行；`detail:$field` tag 跟值走 |
| A3 | 新增菜品/管理菜品各占一行 | :93-94 | 同一 Row：新增 weight(1f)，管理包内容 |
| A4 | 再次到店/查看全部各占一行 | :104-105 | 同一 Row：再次到店 weight(1f)，查看全部包内容 |
| A5 | 小票头卡片 6 段竖堆，右半屏全空 | :45-55 | 眉题与版本行合并；标题左 + Score 右上（Row）；评分条/verdict/日期/已修订收进标题下的一到两行（FlowRow）；每个元素不再独占一行 |
| A5b | 「刷新详情」独占一行且右侧全空 | :38 | 并入 PageHeader 行右端（PageHeader 里加 trailing slot；若无此机制则在 detail 页头部 Row 包一层） |
| A6 | 修改历史区块里按钮独占行 | :112-117 | 「查看修改历史」右对齐放标题行；时间轴内容保留 |
| A7 | 修改历史面板+条码+标语挤在同一卡 | :110-120 | 拆卡：时间轴卡 → 条码+标语收尾卡，两卡间隙 12dp；条码卡高度小，不增加总高 |
| A8 | 到店 sticker「最近到店 $it」独占行 | :103 | 挪到「到店记录」标题行右对齐（A4 的区块头 Row 里） |
| A9 | 「菜品在本餐厅内管理」提示独占行 | :95 | 与「店内菜品 · N 道」合并为同一行 caption（有菜品数时）或单独一行但右对齐放「管理」按钮（无菜品数时） |

### B. 列表页（含搜索页）

代码位置 `ListScreens.kt` `RecordListScreen` / `RecordRow`。

| # | 问题 | 现状 | 改法提案 |
|---|------|------|---------|
| B1 | 列表卡片内边距 8dp + spacedBy(4dp)，评分列竖排（Score 上、verdict 下） | :173-179 | 评分列保持竖排（横排会顶标题换行），但卡片 padding 8→7dp、spacedBy 4→3dp；右列间距 10→8dp。**保守微调，不改结构**。记录这一决策及理由，供复核会话判断 |
| B1 收益判断 | 列表是浏览页，密度不是痛点；详情页才是。列表只做保守微调 | — | — |
| B2 | 「已加载均分/菜品」统计条与筛选行之间无空隙、与搜索框之间挤 | :68 vs :45 spacedBy(6dp) | 统计条保持独立卡（它的 brutalBox 高度只有 34dp，合理）；spacedBy 6→7dp；**仅微调** |

### C. 设置页（本 PR 要删区块，顺手密度优化）

`SettingsScreen.kt`：

| # | 问题 | 现状 | 提案 |
|---|------|------|---------|
| C1 | 「检查更新」按钮独占一行 | :73-77 | 与「启动时检查更新」Switch 同一 Row：Switch 左、检查更新按钮右 |
| C2 | 「退出登录」文本按钮独占一行 | :83 | 红线：危险操作不并排（误触风险）。**保持独占一行，但加 `fillMaxWidth()` 改为通栏文本按钮**，右对齐文案或加说明副文案「将清理此账号的本机缓存」 |
| C2b | 「关于食记」卡里版本 + slogan 两行 | :63-64 | 保持不变（版本行信息密度已合理）|
| C2c | 主题 ChoiceChips 独占面板 | :59-61 | 保持独立面板（跟系统/浅色/深色 三选一已是紧凑形态）|

### D. 编辑页（EditScreen）

`DetailEditScreens.kt:131-214`：

| # | 问题 | 现状 | 改法提案 |
|---|------|------|---------|
| D1 | 编辑页空隙 spacedBy(16dp) | :140 | 16→12dp |
| D2 | 「店内菜品」区块：新增/管理各占一行 | :205-206 | 同 A3：同一 Row（新增 weight(1f)、管理包内容）；testTag 保持 `edit-add-dish` / `edit-manage-dishes` |
| D3 | 首访区块的「展开详情/收起」按钮 | :184-186 | 保持现状（toggle 跟标题同行已是紧凑形态） |
| D4 | 「点击保存后才会提交」提示独占行 | :144 | 保持（安全提示，不宜挤） |

### E. 历史详情页（RevisionDetailScreen）

`HistoryScreens.kt` `RevisionDetailScreen`：

| # | 问题 | 现状 | 改法提案 |
|---|---|---|---|
| E1 | 「刷新历史详情」独占一行 | :35 | 与 A5b 同法：并入 PageHeader 行右端 |
| E2 | 「加说明 / 修改说明」「隐藏此条历史」各占一行 | :58-59 | 同一 Row：加说明 weight(1f)、隐藏包内容（隐藏是危险操作但它是文本按钮、有确认框兜底，可并排；复核会话若反对，回退为独立行） |
| E3 | 「重新应用字段」独占行 | :64 | 保持独占（它是主操作，通栏正确）|
| E4 | 每个字段卡的「原值/新值」标签+值竖排 | :46-49 | 「原值」+ before 值同行（label 左、值右），「新值」+ after 同行；ExpandableValue 是可展开组件，改法：把 label 放 ExpandableValue 的收起态行首，展开态保持竖排。若 ExpandableValue 内部结构不易改，**回退为保持竖排、只把 label 与值视觉配对（label caption 紧贴值上方，间距 2dp）**，供复核会话裁决 |
| E4 回退版 | 「原值/新值」保持竖排，只把 label 与值间距收紧（label 下 2dp 就是值，不再 8dp） | — | — |
| E4 变体C | 「原值/新值」竖排保持，label 用 caption 字号；值上下间距 8→4dp | — | 三个变体任选其一，写明选择理由 |

| # | 问题 | 现状 | 提案 |
|---|---|---|---|
| E5 | diff 卡的 HorizontalDivider 在卡内底部 | :50 | 移除卡内 divider（卡片已有 brutalBox 边框，divider 冗余） |
| E6 | 时间行「版本 N → M」与 HistoryTimeText 罕排 | :39-41 | 同一 Row：时间左、版本范围右 |

### F. 认证页（AuthScreens.kt）

| # | 问题 | 现状 | 改法提案 |
|---|---|---|---|
| F1 | 登录/注册/恢复三个切换按钮竖堆 | :95-97 | 保持竖堆（已有注释「Stacked links stay reachable at large font sizes and narrow widths」说明是可及性决策——**不许改**） |
| F2 | 「显示密码/隐藏密码」按钮独占行 | :137 | 移到密码输入框内 leadingIcon/trailingIcon？BiteTextField 有 leadingIcon 参数。改成 trailingIcon 眼睛图标（QuietGridIcons 若无眼睛图标则保持现状，不新增图标绘制） |
| F2 回退 | 保持现状 | — | 图标库无眼睛图标，不新增绘制资产 |

### G. 全局组件（BiteComponents.kt）

| # | 问题 | 现状 | 提案 |
|---|---|---|---|
| G1 | BitePanel 内边距 14dp + spacedBy(8dp) | :69-70 | 14→12dp padding、spacedBy 8→6dp。**注意波及全 App 所有面板**，收益最大也风险最大——讨论时评估：详情页收了 (A5、A2、A6、A7、A9) 后，BitePanel 收紧的边际收益还剩多少？若边际收益小则**放弃 G1**，避免全 App 视觉回归。（我的初判：放弃 G1）|
| G1 边际收益判断 | 详情页改完后 BitePanel 收紧边际收益小 → 倾向放弃 G1 | — | 你的判断？标记为待讨论，不是结论 |
| G2 | ListFilterPanel 的评分区间 RatingEditor 两行 | :236-239 | 「最低评分/最高评分」两行→同一 Row 各 weight(1f)（大字体允许换行） |
| G3 | 筛选面板「只看未评分/限定评分区间」两个 Checkbox 行 | :234-235 | 同一 Row 两列（各 weight(1f)，大字体换行） |
| G2/G3 回退 | 大字体下不换行会截断 → 保留竖排 | — | — |

## 与密度无关但同批执行的既定改动（用户已拍板）

这些不参与讨论，是已拍板事项，照做：
1. **会话滑动 180 天**（后端 `session.ts:53` `2592000`→`15552000`、`rotation.ts` refresh 时顺延 session 过期线 `now + 15552000` 并与新 refresh token 同事务写回、抽 `SESSION_TTL_SECONDS` 共用常量；新增 2 个 auth 测试：滑动续命 + 绝对闲置上限；既有测试 `JWT owner/version and refresh idle/absolute expiration are enforced` 不许改）。
2. **删除设置页「刷新账号与会话」+「登录会话」区块**：「刷新账号与会话」按钮、「登录会话」标题、`items(state.sessions)` 面板、空态文案、`revoking` state、非当前会话的「撤销此会话？」确认分支全部删除；**保留「退出登录」**（`logout` 按钮 + `ConfirmAction` 确认框）。连带删：`ProductViewModel.kt` `revokeSession()`、`SettingsUiState.sessions`、`loadSettings()` 的 sessions 请求、数据层 `RetrofitAccountSecurity.sessions()/revokeSession()`、`Repositories.kt` 接口声明、`ProductRoot.kt` 透传。保留后端 `/auth/sessions`、`/auth/sessions/{id}` 端点（API 不动、合同不变）。e2e 里撤销其他会话的段落改为直接调 API（OkHttp 直连 DELETE）。`P6UiTest` 里等 sessions 的等待改为等 settings 加载完成；`Pr16UiTest.currentSessionConfirmationSaysLogoutAndOtherSessionsStillSayRevoke` 整体删除。
3. **详情页 A1/A3/A4/A6/A8**（用户点名的四处按钮对 + 到店 sticker 搬家）是**必做**；A2 信息卡两列、A5 小票头、A5b 刷新按钮进头部、A7 拆卡、A9 合并提示——**必做**，但**布局细节（两列的具体分配、A5 的 FlowRow 内元素顺序）由你在讨论文档里出最终方案**。
必做项若你有更优布局提案，在讨论文档里提，回写本文件执行以讨论文档为准。

## 产出物

1. `design/pr17-discussion/DISCUSSION.md`——逐条评审上述 A–G，每条给「同意/反对+理由/替代方案」，对 G1 给出明确决策（做/不做）；对 E4 三个变体给出明确选择；对 B1/B2 的保守微调给出明确数值。
最终计划必须覆盖：A1–A9、B、C1、D1/D2、E1/E2/E4/E5/E6、F2（或回退）、G2/G3（或回退），以及已拍板三项。
3. 实现照最终计划执行，一次提交到新分支 `feat/pr17-density-and-sliding-sessions`，推 origin 开 PR（标题「density pass + sliding 180-day sessions」）。
4. 实现总结写 `design/pr17-discussion/NOTES.md`，分节列：改动文件、每条验收证据、讨论中被推翻/修正的助手提案及理由。
