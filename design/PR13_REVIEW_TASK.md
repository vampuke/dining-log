# PR #13 独立评审任务书

你是**独立评审者**，不是实现者。

## 评审对象

- PR：`vampuke/dining-log#13` —「fix: 截图评审的前 3 个问题（时间格式、空白详情 panel、未评分滑块）」
- 分支：`fix/top3-ux-fixes` → `master`
- 提交：`b5a9456`（1 个提交，基于 master `92e5f53`）
- 改动：6 文件，+37 / -16

用 `git diff 92e5f53..b5a9456` 读完整改动。PR 描述里作者自己声明：**「这批改动在沙箱里没有跑通编译检查」**（本地无 Gradle 缓存、wrapper 下载遇 SSL 错误、无 compileSdk 35 platforms），要求合并前先跑构建。这一点你必须当作已知风险处理。

## PR 声称修的三件事

1. **时间格式统一**：`core/Product.kt` 新增 `formatAbsoluteTime(iso)` / `formatAbsoluteTimeMillis(epochMilli)`，本地时区输出 `yyyy-MM-dd HH:mm`，解析失败回退原字符串。详情页、我的页、历史绝对时间、缓存「上次更新」都改用它，不再显示 `2026-11-03T00:12:12.491Z`。
2. **详情空白 panel**：`DetailEditScreens.kt` 先算非空字段，为空则不渲染那块 panel。
3. **未评分滑块**：`QuietGridComponents.kt` 的 `RatingEditor` 在未评分时拇指停最左、无填充、拇指纸色，±0.1 按钮隐藏直到有评分。

## 你必须回答的问题（逐条给 PASS / PARTIAL / FAIL + 文件:行号证据）

### 一、功能性
1. 三个修复是否真的达成了声称的效果？逐个在代码里追到调用点确认，不要只看函数定义。
2. `formatAbsoluteTime` 的所有调用点覆盖是否完整——**还有没有遗漏的地方仍在直接插值 ISO 字符串或 `Instant`？** 全仓库搜索 `\.updatedAt`、`\.createdAt`、`Instant\.ofEpochMilli`、`\.deletedAt`、`\.expiresAt`、`\.lastSeenAt`，列出每一处判断是否已走新函数。
3. `formatAbsoluteTimeMillis` 的 `getOrDefault(epochMilli.toString())` 回退——失败时给用户看一串裸数字（如 `1793609298000`）是否可接受？

### 二、正确性（重点，请深入）
4. **时区语义**：`DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")` **没有指定 Locale 和 Zone**。`atZone(systemDefault())` 已处理时区，但 formatter 用了默认 Locale——在非公历 Locale（如泰历 `th-TH`、部分伊斯兰历 Locale）下 `yyyy` 会输出非公历年。请判断这是真实风险还是理论风险，并对照项目里已有的时间格式化写法（`relativeTime`）判断是否一致。
5. **未评分滑块是否真的不再「假装 4.0」**：未评分时 `Slider(value = 1f)`。`1f` 落在 `valueRange = 1f..5f` 的最左端，**它本身就是一个合法评分值（1.0）**。请判断：
   - 视觉上拇指是否确实在轨道最左端、且看起来不像「已评分 1.0」？
   - 用户**直接点轨道左端**会触发 `onValueChange(1f)` → 写入 `"10"`，把一个未评分记录变成「1.0 分」。这是否是 PR 意图之外的副作用？与改动前（默认 40→4.0）相比是变好还是变坏？
   - 无障碍 `setProgress` 在未评分时的 `currentValue` 传的是什么？如果传 `1f`，TalkBack 会把未评分读成「1.0 分」吗？请读 `Slider` 的 semantics 实现，确认未评分时屏幕阅读器朗读的到底是什么。
6. **隐藏 ±0.1 按钮的连锁影响**：`if (value != null)` 让两个按钮在未评分时不存在。检查是否有现存测试、`testTag`（`$tag:minus` / `$tag:plus`）或其他代码依赖它们始终存在。若有测试会因此失败或被偷偷删掉，指出来。
7. `DetailEditScreens.kt` 的 `deletedAt` 改动：`formatAbsoluteTime(it) ?: ""` —— 注意 `formatAbsoluteTime` 返回的是 **非空 String**，`?.let{}` 只在 `deletedAt == null` 时不追加。当 `deletedAt` 为 null 时，用户会看到「此记录在回收站 · 删除于 」**结尾带一个悬空的「删除于」**。这是否是真实缺陷？给出修法。
8. `extraFields` 被提到 `item {}` 外计算——确认它是每次重组重算还是被正确记忆，以及这个改动是否会让「空 panel 消失」但**同时把本该显示的空状态提示也一起干掉了**（例如用户会困惑「地址在哪」）。

### 三、回归风险
9. 时间格式从 ISO 改为本地时间后，**是否有测试断言了旧的 ISO 字符串**？搜 `androidTest` 与 `test` 里的时间断言（如 `assertTextContains("2026-")`、`hasText` 匹配时间）。若断言语义变了，测试是否被同步更新，还是会被静默改弱？
10. 这次改动**没有新增任何测试**。PR 声称三个修复，但 0 个新断言。判断是否可接受：哪些修复必须有测试才敢合并，哪些可以从简。
11. 构建从未跑通。请静态检查明显会编译失败的点：新增函数是否已 import（`formatAbsoluteTime` 在 `DetailEditScreens.kt` / `HistoryScreens.kt` / `ListScreens.kt` / `SettingsScreen.kt` / `QuietGridComponents.kt` 中是否可见？它在 `core` 包，这些文件是否 import 了 `top.alanvampuke.dininglog.core.*`？）；`TechnicalName` 之类未使用变量；`slider` lambda 参数是否仍被使用。

### 四、边界与规范
12. 后端 / `contracts/` / migrations 是否零改动（`git diff --name-only 92e5f53..b5a9456 -- backend/ contracts/`）。
13. `versionCode` / `versionName` 是否未被改（应保持 8 / "0.8.0"）。
14. 是否引入了位图资源。
15. 深色模式下：未评分时拇指用 `c.paper`（浅色 `#FAF4E4` / 深色 `#302B23`）。在 `c.surface` 背景上，这两个对比度分别是多少？用一个快速脚本算，判断未评分拇指在深色下是否**几乎看不见**（这会让「未评分」看起来像「滑块坏了」）。

## 输出要求

1. 报告写到 `design/pr13-review/REVIEW.md`。
2. **只评审，不改 PR #13 的代码。** 报告提交到新分支 `pr13-review`（从 `master` 建），push。
3. 每条发现必须给 `文件:行号` 或可复现的计算结果，禁止模糊表述。
4. 结论分级：BLOCKER / MAJOR / MINOR / NIT，并给出一句话总评（可否合并）。
5. 对每个 BLOCKER / MAJOR 给出**最小修法**（具体到文件行号）。
6. 不要合并 PR、不要发版、不要修改 `fix/top3-ux-fixes` 分支。
