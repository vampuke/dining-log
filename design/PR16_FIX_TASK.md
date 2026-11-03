# PR #16 修复任务书

你是**实现者**。独立评审（`design/pr16-review/REVIEW.md`）判定 PR #16 **不可合并**，你要逐条修掉。

## 工作方式

- 分支：`pr16-rebased`（PR #16 的 head）
- 先 `git checkout pr16-rebased && git pull`
- **只改 Android 代码，不动 `backend/`、`contracts/`**
- 不要改 `android/tools/p8_protected_hashes.json` 里登记的其他文件；如果你确实改了 `backend/wrangler.toml`（不应该），必须同步更新对应哈希
- 每修一条，在 commit message 里写清编号（B1/M2/…）
- **不要碰版本号**（`versionCode`/`versionName`）

## 必须修（按优先级）

### B1 — 编译错误（最高优先级）

`android/core/.../core/Updates.kt`

`AppUpdateChecker` 的主构造参数 `gateway` 和 `installedVersionCode` **没有 `val`**，原来只在 `init` 里用，所以能编译。新增的成员函数 `checkNow()` 在函数体里引用它们 → `unresolved reference`，**整个模块编译不过**。

修法：给这两个参数加 `private val`。注意 `installedVersionCode` 目前只被 `init` 用，加 `private val` 后若 lint 报"从未在构造器外使用"可以接受，**不要**改成 `@Suppress` 掩盖。

### B2 — 开关关闭时手动查到新版本没有入口

`SettingsScreen.kt:53` 的「有新版本」按钮条件是 `updateState.enabled && release != null`。

用户关闭启动检查 → 手动点「检查更新」→ 查到新版本 → **按钮不出现，无法安装**。这直接废掉了新功能。

修法：把「自动检查开关」和「手动检查结果可见性」分开。手动检查成功发现新版本时，无论 `enabled` 是 false 都必须出现安装入口。建议在 `AppUpdateState` 里加一个字段区分来源（如 `manual: Boolean`），或让 `release != null` 本身就足以显示入口。

### B3 — 统计栏把子集当成全量

`ListScreens.kt:143-148` 只统计 `state.items`（当前已加载的一页，`ProductApi.kt:115` 每页 20 条），标签却写成无范围的「餐厅 / 均分 / 菜品」。

后果：账号有 21 家餐厅、只有第 21 家有菜，首屏会显示「20 家 / … / 0 道」。而且筛选时统计也没排除筛选条件。

修法：加范围说明（如「当前 N 条」前缀或小字标注「已加载」），或在筛选态下隐藏统计。**不要**改成宣称全量。

### M1 — 手动请求覆盖检查期间已变的偏好

`Updates.kt:56` 在请求前抓 `enabled`，`:61-62` 用旧值重建整个 `AppUpdateState`。用户在请求挂起期间关掉开关 → 收集器写入 `false` → 请求完成写回 `true` → Switch 显示错误状态（持久化值仍是 false）。

修法：结果更新时**保留当前** `enabled`（`copy()` 而非重建），不要用快照值。

### M2 — 无 in-flight 守卫，重复点击并发

`Updates.kt:57` 无条件设 `checking = true`，`:59` 无条件发起请求。UI 的 `enabled = !checking` 要等重组才生效，不是同步守卫。两次快速点击会并发请求、互相覆盖结果，先完成的把 `checking` 置 false 而另一个还在跑。

修法：在检查器内部加原子守卫（如 `AtomicBoolean` 或 `Mutex.tryLock`），重复调用直接返回。完成/取消只清自己拥有的状态。

### M3 — 手动检查失败没有任何反馈

`Updates.kt:65` 的 catch 只清 `checking`。`AppUpdateState` 没有失败字段，用户分不清「网络失败」和「从未检查」。而且失败后若 `upToDate` 残留可能误显示「已是最新版本」。

修法：加明确的失败状态（如 `failed: Boolean` 或 `error: String?`），UI 显示「检查失败，请重试」。**只有**有效响应确认无更新时才显示「已是最新版本」。

### M4 — 折叠首访隐藏了阻止保存的错误

`DetailEditScreens.kt:187` 折叠后，`:188`/`:190` 的日期和备注错误都不显示。用户展开→填非法值→收起→保存 → 被拦下但看不到哪个字段错了。

另外 `:180` 的 `firstVisitExpanded` 用普通 `remember`，旋转后重新折叠，而 ViewModel 保留着非法输入。

修法：**校验失败时自动展开**首访区（最稳），或在折叠摘要里显示具体错误。同时让展开状态在 `remember(visit.id)` 之外能被错误事件驱动。

### M5 — 时间切换控件缺无障碍语义

`ListScreens.kt:221`/`:223` 只有 `.clickable { }`，没有 `role = Role.Button`、没有 `onClickLabel`、没有状态语义。原来用的 `BiteTextButton` 有 48dp 最小高度。

修法：补 `role`、`onClickLabel`（如「切换为绝对时间」）、状态描述，并保证触摸目标 ≥48dp。注意它嵌在可点击卡片 `:167` 内，**不要**让点击冒泡触发父卡片导航。

### M6 — 「时间文本切换」没真正实现

任务要求是「时间文本本身可点切换」。实际实现是：相对时间一直由 `:211` 显示，下面额外一行「显示绝对时间」/「绝对时间 … · 收起」。结果绝对态**同时显示两种时间**，而且原来那个按钮还在（只是换了文字）。

修法：让同一个控件真正在「相对时间」和「绝对时间」之间切换——即把 `historySummary` 里显示相对时间的那部分和时间切换合并成一处，绝对态不再重复显示相对时间。

## 一并修掉（低成本）

- **N1** `DetailEditScreens.kt:188`：非空但非法（如 `2026-13-45`）时格式提示消失 → 条件改成 `visit.date.isBlank() || !validDate(visit.date)`
- **N2** `ListScreens.kt:220`：切换时行高可能变化 → 尽量约束为单行或固定高度，避免列表跳动
- **N4** `SettingsScreen.kt:102`：按钮已改「退出登录」，确认框还是「撤销此会话？/ 确认撤销」→ 当前设备改成「退出登录？/ 确认退出」，其他设备保持撤销措辞

**N3 不修**（精确相对比较是合理的，不要改成 contains）。

## 必须补的测试

评审指出这批**零新增测试**。至少补：

1. `checkNow()` 在 `enabled=false` 时仍能工作，且查到新版本后**安装入口可见**（B2）
2. 连续两次 `checkNow()` 只发起一次请求（M2）
3. `checkNow()` 失败时状态可辨识，**不**显示「已是最新版本」（M3）
4. 检查期间切换开关，完成后 `enabled` 保持用户最后的选择（M1）
5. 首访非法日期 + 折叠 + 保存 → 错误可见（M4）
6. 餐厅/菜品两套 chip 的阈值边界：46 进高档、31 进低档、32 不进低档、null 两档都排除（`CacheRules.kt:54` 与在线 `queries.ts` 必须一致）

单测能覆盖的放 `core/src/test`，需要 UI 的放 `app/src/androidTest`。

## 已知正确的部分（不要改坏）

- 筛选阈值本身是对的：`minRating=46` → `rating >= 46`；`maxRating=31` → `rating <= 31`（`CacheRules.kt:54`）。文案「★4.6+」「★3.1−」准确。
- 首访日期默认今天是 `LocalDate.now()`（设备本地），正确。
- `QuietGridComponents.kt` 的未评分空轨道逻辑刚在 #15 合并，**不要在冲突解决中丢掉**。
- `ListScreens.kt` 的 `formatAbsoluteTime` 必须保留。

## 完成标准

```bash
cd /root/dining_log
git checkout pr16-rebased
# ... 修改 ...
git add -A && git commit -m "..."
git push origin pr16-rebased
```

**不要合并 PR、不要发版**，推完分支就停。CI 会在 PR 上自动跑。
如果某条你判断不该修或修不了，**明说原因**，不要静默跳过。
