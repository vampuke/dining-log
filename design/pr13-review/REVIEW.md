# PR #13 独立评审

**总评：暂不合并（REQUEST CHANGES）。** 空详情 panel 修复成立；时间统一有两处真实遗漏；未评分滑块的视觉默认值已改正，但仍把「无评分」编码成合法的 1.0，造成触摸端点与无障碍操作不一致。另有一个继承自 master 的 CI 阻断，不能把红灯解释成已通过构建。

发现计数：**1 BLOCKER（继承的 CI 阻断）、3 MAJOR、0 MINOR、2 NIT**。每个 BLOCKER / MAJOR 的最小修法见对应条目。

评审时间：2026-11-03 02:46 UTC。对象为 `92e5f5392714a6d499a61374fa2289d23e0d4898..b5a945616220c9011f1e0c5f734b76c30abcb8e1`，已完整读取 `design/PR13_REVIEW_TASK.md` 和六文件 diff（+37 / -16）。所有产品代码行号均指 **b5a9456**，不是报告分支。报告分支从当前 master `7faf5a1` 建立；它相对 `92e5f53` 只增加评审任务书及启动脚本，产品代码相同。

方法：只读检索、调用链检查、读取锁定版本的 Compose 源码、Python 日期/对比度计算，以及只读查询已有 GitHub CI。没有运行 Gradle、模拟器、合并、发布或修改 PR 分支。Python 静态校验使用 `-B`，没有输出资源或改写源码。

为压缩路径，下列前缀可直接展开成仓库路径；文中 `F/restaurants/ListScreens.kt:187` 等都是完整、可定位的文件行号：

| 前缀 | 仓库路径 |
| --- | --- |
| C/ | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/ |
| F/ | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ |
| D/ | android/data/src/main/kotlin/top/alanvampuke/dininglog/data/ |
| AT/ | android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ |
| CT/ | android/core/src/test/kotlin/top/alanvampuke/dininglog/core/ |
| DT/ | android/data/src/test/kotlin/top/alanvampuke/dininglog/data/ |
| FT/ | android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ |

## 分级发现与最小修法

### B1 — BLOCKER：主 Android CI 红灯，build/lint/unit-test 步骤被跳过（继承缺陷）

已有 [Android job](https://github.com/vampuke/dining-log/actions/runs/41566144019/job/125688046364) 标记 head 为 `b5a9456`，步骤 8「Verify P6 vectors, native backgrounds and navigation contrast」失败，步骤 9「Build, lint and test using committed dependency locks」为 `skipped`。对应调用是 `.github/workflows/ci.yml:104` 和 `:107`。

在 PR 的只读快照及报告分支（产品代码与基线相同）分别运行 `python3 -B android/tools/verify_p6_visuals.py`，均退出 1：

```text
android/tools/verify_p6_visuals.py:8 -> verify_bite_visuals.py:115
AssertionError: backend/wrangler.toml
```

`android/tools/p8_protected_hashes.json:80` 预期 SHA-256：
`2851fd254739cbdfdc3102cc05deeab0154d9a48a5fd22e4fdd4694a85397e34`；
实际文件 SHA-256：
`cb81129bcbd67eed9d583ce70e3be9e47b1a0d86fa5af7118104695e538962bb`。
`git diff 92e5f53..b5a9456 -- backend/ contracts/` 为空，因此此失败不是 PR 改动后端造成的。

**最小修法：** 在单独的维护提交中，核对现有 `backend/wrangler.toml` 的变更是否已获接受，然后仅更新 `android/tools/p8_protected_hashes.json:80` 的该文件哈希；保留 `verify_bite_visuals.py:115–117` 的保护断言。随后让 `.github/workflows/ci.yml:107` 的现有 build/lint/unit-test 检查实际执行并通过。本评审没有执行或触发该工作流。

不能据此声称「CI 从未编译」：同一 PR head 的 [API 26 job](https://github.com/vampuke/dining-log/actions/runs/41566144019/job/125688046762) 和 [API 35 job](https://github.com/vampuke/dining-log/actions/runs/41566144019/job/125688046701) 的「Keystore, authentication and product Compose tests (emulator)」步骤均成功。`.github/workflows/ci.yml:205` 调用 `.github/scripts/android-test.sh:12` 的 `:app:connectedDebugAndroidTest`，脚本 `:18–23` 传播 Gradle 退出码。这是已有 CI 对 PR 合并测试树的 debug 编译/仪器测试证据；仍不能替代被跳过的 lint 和 JVM 单元测试。

### M1 — MAJOR：餐厅卡片仍显示 UTC 日期，和详情的本地日期矛盾（遗漏）

`F/restaurants/ListScreens.kt:187` 在没有 `last_visit_date` 时仍用 `record.updatedAt.take(10)`，最终在 `:196` 渲染。列表由 `F/ProductRoot.kt:129`、`:177` 进入，记录由 `ListScreens.kt:123` 交给 `RecordRow`，再由 `:156` / `:162` 调用 `RowText`。这是活跃 UI，不是数据层代码。

可复现：`2026-11-03T00:12:12.491Z` 在 `America/Los_Angeles` 为 **2026-11-02 16:12**，但该卡片仍显示 **更新 2026-11-03**；详情 `DetailEditScreens.kt:54` 显示 **更新于 2026-11-02 16:12**。虽然卡片没有完整 ISO 的 T/Z，它仍直接截取 UTC 日期，违反「统一本地时间」的语义。

**最小修法：** 在 `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt:187` 的更新时间分支中，把 `it.take(10)` 改为 `formatAbsoluteTime(it).take(10)`（保留卡片的日期精度）；`last_visit_date` 是就餐日期，应继续按原日期显示。给无到店日期、洛杉矶跨午夜的卡片增加一个具体日期断言。

### M2 — MAJOR：删除/恢复历史 diff 仍直接显示原始 ISO（遗漏）

完整调用链：

1. `backend/src/domain/model.ts:6–8` 把 `deleted_at` 纳入 diff；`backend/src/domain/mutations.ts:66–77` 的删除/恢复会生成此字段的变更历史。
2. `backend/src/repositories/history.ts:16` 明确把 diff 的 `deleted_at.before/after` 转成 ISO。
3. `D/remote/ProductApi.kt:99–100` 原样构造 `Change`。
4. `F/revisions/HistoryScreens.kt:47` / `:49` 调用 `ExpandableValue`。
5. `F/components/QuietGridComponents.kt:223–225` 调用 `displayValue` 并渲染；`C/Product.kt:88` 的默认分支原样返回字符串。

因此打开删除历史，新值 `after:deleted_at` 会显示类似 `2026-11-03T00:12:12.491Z`；恢复历史的 `before:deleted_at` 同样如此。`C/Product.kt:74` 已把标签叫做「删除时间」，用户可见性明确。只搜索 `.deletedAt` 会漏掉这个字典/diff 路径。

**最小修法：** 在 `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Product.kt:83–88` 的 `displayValue` 中，在默认分支前加 `field == "deleted_at" -> formatAbsoluteTime(value)`；已有首个 null/空值分支保留「未填写」。这只改显示，不改 diff 存储或契约。增加非空删除时间格式及 null 显示断言，并确认历史的 before/after 调用此分支。

### M3 — MAJOR：未评分被编码成合法 1.0，最低端点触摸无效，操作语义不一致

`F/components/QuietGridComponents.kt:150–151` 将 null 映射成 `Slider(value = 1f, valueRange = 1f..5f)`。Compose 1.3.2 的 `Slider.kt:1628–1631` 在点击时执行 `dispatchRawDelta(0f)`；`:1983–1985` **仅在目标值不同于 state.value 时**调用 `onValueChange`。所以直接点最左端、吸附结果为 1f 时，`1f == 1f`，null 仍是 null，并不会写入 `"10"`。± 按钮又在 `QuietGridComponents.kt:176` 隐藏，无法从这里启动最低评分。

相反，应用自己的外层 `setProgress`（`:155–157`）没有相等值判断，`SetProgress(1f)` 会写入 `"10"` 并返回 true。Compose `SemanticsConfiguration.kt:112–138` 保留外层动作，所以这段覆盖确实有效。触摸和无障碍对同一个最低值给出不同结果。

改前 null 对应 4f：点 1f 可以选 1.0，点恰好 4f 无效；改后 null 对应 1f：点 1f 无效，点 4f 可以选 4.0。PR 修好了「伪 4.0」视觉，但只是把同值点击的死区从 4.0 移到了 1.0。拖到其他刻度后再回到 1.0 可写入最低分；这不是直接点端点的等价操作。

还存在语义混淆：null 的 `ProgressBarRangeInfo.current` 是 1f，范围 1..5、steps=39。应用只改 `contentDescription`（`:152`），没有 `stateDescription`。Compose delegate `:3194–3217` 因而合成 **0%** 状态；并不是源码必然让 TalkBack说「1.0 分」。它导出 `SeekBar` 和合法范围/current（`:849–862`），同时导出「未评分，拖动即设置评分，0.1 步进」描述。标准节点有「未评分」与「0%」并存，不能表示真正的无数值状态；具体朗读顺序取决于 TalkBack 版本。本评审未运行 TalkBack。

**最小可靠修法：** 在 `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt:150–172` 区分 null 与已评分交互：null 分支保留静态空轨道/纸色拇指，设置 `stateDescription = "未评分"`，通过已有 `:180` 的「设置评分」按钮进入评分；仅非 null 分支使用可操作 Slider 和范围语义。这样不需要给「无评分」借用一个合法评分值。同步将 `AT/ProductVisualTest.kt:280` 改为先点击「设置评分」，保留 4.0、±0.1、4.3 的断言；新增清除后无评分语义、按钮隐藏、设分后点击最低端点得到 `"10"` 的断言。如果产品坚持 null 时可直接拖/点轨道，则必须另做会响应“同值选择”的交互层；只改颜色、`SetProgress` 或 `onValueChange` 不会修复 Compose 已过滤掉的触摸回调。

### N1 — NIT：注释与实际评分入口不符

`F/components/QuietGridComponents.kt:174–175` 说「设置评分」是唯一入口，但 `:150` 的拖动以及 `:155` 的无障碍动作也会设置评分。改为描述实际允许的入口，或随 M3 的显式入口方案更新注释。

### N2 — NIT：epoch 回退文字不适合用户，但不是常规溢出缺陷

`C/Product.kt:105` 的裸数字回退没有日期语义；若确实失败，建议回退「时间不可用」。但不能用任意 Long 溢出来制造“必现”缺陷：`2^63 / 1000 / (365.2425 * 86400) ≈ 292,277,025` 年，小于 java.time Instant/ZonedDateTime 约十亿年的范围。正常缓存来自 epoch millis，通常不会进入回退。等级不应提升为合并阻断。

## 逐条回答任务书的 15 个问题

### 1. 三个修复达成情况 — PARTIAL

| 修复 | 判定 | 调用点和结果 |
| --- | --- | --- |
| 时间统一 | PARTIAL | `DetailEditScreens.kt:54,82,114`、`HistoryScreens.kt:40`、`ListScreens.kt:202`、`SettingsScreen.kt:43,57`、`QuietGridComponents.kt:115` 都调用新函数；M1/M2 两条显示路径仍遗漏。 |
| 空 panel | PASS | `DetailEditScreens.kt:57–65` 只有存在非空额外字段才注册 item；`C/Product.kt:67` 的菜品字段过滤后为空，确实不再产生那块 panel。有地址等非空字段时继续显示。 |
| 未评分滑块 | PARTIAL | `QuietGridComponents.kt:144,150,160,166–167,176` 保持「未评分」标签，LTR 拇指在最小值端、填充为零、纸色、无 ± 按钮；真实评分 1.0 用红色 thumb 并显示 ★1.0/计量/±，视觉有区别。M3 的触摸和范围语义未解决。 |

三个 RatingEditor 生产调用点全部检查：`DetailEditScreens.kt:150` 的编辑器可能传 null；`ListScreens.kt:219–220` 的筛选器分别传非 null 的最低/最高值，因此隐藏按钮的变更不影响它们。编辑器由 `F/ProductRoot.kt:166` 进入；其初始评分可为 null（`F/ProductViewModel.kt:183` 的默认字段分支）。

CacheStatus 的三个生产调用是 `DetailEditScreens.kt:35`、`HistoryScreens.kt:32`、`ListScreens.kt:104`，均到 `QuietGridComponents.kt:115` 的 millis formatter。`SettingsScreen` 由 `ProductRoot.kt:134` 进入；两个详情屏由 `:144` / `:147` 进入。

### 2. 全部时间引用覆盖 — FAIL

按要求在 b5a9456 全仓库运行：

```bash
rg -n '\.updatedAt|\.createdAt|Instant\.ofEpochMilli|\.deletedAt|\.expiresAt|\.lastSeenAt' .
```

共 **57 个匹配行**，逐行判断见末尾附录。完整的正常顶层 ISO 显示调用已迁移，餐厅卡片 `ListScreens.kt:187` 仍截取原时间；另外扩展搜索 `created_at|updated_at|deleted_at|expires_at|last_seen_at|releasedAt|fetchedAt|Instant|take\(10\)` 找到 M2 的 diff 显示遗漏。`dining_date` / `last_visit_date` 是业务日期，不应当转成 Instant。`AppRelease.releasedAt`（`C/Updates.kt:10`）没有 UI 直接显示；更新提示显示版本/进度，不构成时间遗漏。

### 3. millis 裸数字回退 — PARTIAL

失败时把 `1793609298000` 作为「上次更新」文字不可取（`C/Product.kt:105` → `F/components/QuietGridComponents.kt:115`），可改为「时间不可用」。但普通有效 millis 会被本地时间格式化；Long 极值不超 java.time 日期范围，不能假称必然溢出。见 N2。ISO 解析失败回原输入（`C/Product.kt:101`）是 PR 明说的容错合同，仍可能显示坏输入，不能理解成任何输入都绝不会显示原字符串。

### 4. 时区 / Locale / 非公历年 — PASS

`C/Product.kt:99,103` 的 `atZone(ZoneId.systemDefault())` 已选择设备时区，formatter 不需要再指定 zone。每次调用读取当前 systemDefault，没有永久缓存一个旧 zone。转换从 Instant 出发，因此 DST 的 instant→local 映射明确；格式按产品要求省略秒和时区标识。

**任务书关于 th-TH / 伊斯兰 Locale 必然改变 yyyy 年份的前提不成立。** `DateTimeFormatter.ofPattern` 用默认 FORMAT Locale，但不覆盖 chronology/zone；`ZonedDateTime` 是 ISO 日历。OpenJDK 17 源码 `DateTimeFormatter.java:553–566`、`DateTimeFormatterBuilder.java:2250–2272,2299–2300` 明确使用 null chronology、标准 DecimalStyle；`DateTimePrintContext.java:125–128` 在无覆盖时直接保留原 temporal。`ZonedDateTime.java:95` 明确为 ISO-8601 日历。这里没有 `localizedBy`、`withChronology` 或 Buddhist/Hijrah temporal，不能把 SimpleDateFormat/Calendar 的行为套过来。因此它不是此代码的真实泰历/伊斯兰历年份风险。

显式 `Locale.ROOT` 可让固定机器形状的产品格式更清楚，但不是修正已发生的非公历年份 bug。`C/Product.kt:90–92` 的既有 `relativeTime` 计算 Instant 之间的 Duration，再拼中文「分钟前/小时前/天前」，根本没有 DateTimeFormatter、年或 Locale；绝对与相对时间在时区语义上相容，但不存在另一份“relativeTime 年份 formatter”可比较。

### 5. Slider(value = 1f) 歧义 / 点端点 / SetProgress / TalkBack — PARTIAL

视觉方面 PASS：`QuietGridComponents.kt:166` 计算 `(1 - 1) / 4 = 0`；Compose `Slider.kt:713–720` 用最小值比例放置 thumb，在 LTR 到最左端。真实评分 1.0 和 null 同位置、同零轨道填充，但 thumb 颜色、文本、评分计量和 ± 可见性不同（`QuietGridComponents.kt:124,132,160,176`），没有继续伪装为 4.0。RTL 时最小值在右端，不能把“最左”视为所有布局方向都成立。

点击方面 FAIL：直接点左端不必然调用 `onValueChange(1f)`；锁定的 Compose 会过滤相等值，详见 M3。PR 没有在初始化/recomposition 时主动写入 1.0；触摸无效与意外自动写入是不同问题。未评分时拖到其他刻度会显式评分，符合 PR 保留拖动设分的意图。

无障碍方面 PARTIAL：current 为 1f，`SetProgress(1f)` 的自定义 action 确实写入 `"10"`，`SetProgress(4f)` 写入 `"40"`，`SetProgress(4.3f)` 写入 `"43"`；`steps=39` 给 `(5 - 1)/(39 + 1) = 0.1` 步距。Delegate 的 forward action 从 current 1.0 加 0.1，会设置 1.1（`AndroidComposeViewAccessibilityDelegateCompat.android.kt:1377–1393`）；最低点没有 backward action（`:875–879`）。标准状态文字为 0%，不应无证据断言 TalkBack 固定读「1.0 分」。内容描述和范围状态同时存在，需按 M3 区分真正 null 状态。

### 6. 隐藏 ± 的依赖 / 测试删除 — PASS

全仓库实际组件生产引用只有前述三个 RatingEditor 调用。`"$tag:minus"` / `"$tag:plus"` 标签仍在 `QuietGridComponents.kt:177–178`，没有删除。唯一直接消费是 `AT/ProductVisualTest.kt:282,284`，它先在 `:280` 对 null 执行 `SetProgress(4f)`、`:281` 确认 ★4.0，之后才点击按钮。因此现有测试的这个顺序不会因隐藏按钮而失败。

`AT/ProductEndToEndTest.kt:474` 的评分助手走 SetProgress；它在 `:151,161,192,220` 使用，不依赖未评分时的 ±。`git diff --name-only 92e5f53..b5a9456` 只有六个生产 Kotlin 文件：没有测试被删除、更新或改弱。**缺口：没有断言 null 时按钮不存在**，现有标签扫描 `android/tools/verify_bite_visuals.py:98–102` 只检查源码里标签保留，不能证明节点始终存在。

### 7. deletedAt == null 时的悬空「删除于」 — PASS（不是可达缺陷）

任务书孤立看表达式的担忧成立，但真实控制流不同：`DetailEditScreens.kt:78` 的 `if (record.deletedAt == null)` 渲染编辑/删除按钮；`:82` 位于其 **else**，只能在非 null 时渲染。record 是 `:26` 捕获的快照，`C/Product.kt:18` 的 `deletedAt` 是不可变 val，不会在同次 item 渲染中从非 null 变 null。null 记录不会看到那句回收站文字。

`formatAbsoluteTime` 返回非 nullable 的 Kotlin String（`C/Product.kt:98`），但坏输入仍可回退为空字符串。`?.let` 只是对属性做 null 处理；不能据表达式本身报告“必现悬空文案”。如果希望也防御非法空字符串，`DetailEditScreens.kt:82` 可改为先显示「此记录在回收站」，仅在 `deletedAt?.takeIf { it.isNotBlank() }` 的 let 内追加整个 `" · 删除于 " + formatAbsoluteTime(it)` 后缀。这是可选防御修法，不是 null 分支的必要修复。

### 8. extraFields 重算 / 记忆 / 空状态提示 — PASS

`DetailEditScreens.kt:57` 不使用 remember；它在 `:32` 的 LazyColumn 内容 DSL 每次执行/重建 item provider 时重算，读取该次捕获的 record。它不是每个可见 item 的重组都必然执行一次；也没有缺少 key 的 remember 导致旧字段残留。最多扫描 `C/Product.kt:65–69` 的几个字段，缓存不是正确性要求；要加 remember 应放在可组合函数外层，不能直接塞进非 composable 的 LazyListScope builder。

diff 中前后使用同一非空过滤式，空/blank 地址在改前就不会显示 `displayValue` 的「未填写」（`DetailEditScreens.kt:57–63`）。改前没有该 panel 的空状态提示，因此没有偷偷删掉现存提示。详情无缓存提示 `:38`、空菜品提示 `:94`、编辑页地址字段 `:159–161` 均保留。非空地址仍有标签和 `detail:address`。若未来希望显示“补填地址”，属于新的 UX 要求，不能拿它否认这次空 panel 修复。

### 9. ISO 测试断言回归 / 同步更新 / 改弱 — PASS

检查全部 `androidTest` 与 `test` 的 `assertTextEquals`、`assertTextContains`、`hasText`、绝对时间标签及 ISO 字面量：没有对 UI 旧 ISO 文字的断言，也没有 `assertTextContains("2026-")` 或 `absolute-time/history-absolute/history-time` 的测试匹配。

ISO 字面量主要是 fixtures，例如 `AT/ProductVisualTest.kt:48,52`、`AT/P6UiTest.kt:54,118`；`CT/ProductRulesTest.kt:40` 是删除状态/冲突规则断言，不是时间文案。`DT/RemoteContractTest.kt:45,77–80` 断言的是业务日期/请求过滤，保持日期语义。旧时间 UI 断言没有被删弱，因为它们本来不存在，测试文件 diff 也为空。此 PASS 仅表示没有既有断言回归，不代表覆盖充分。

### 10. 三项修复零新增断言是否可接受 — FAIL

`git diff --name-only 92e5f53..b5a9456` 没有 `test` 或 `androidTest` 文件。`CT/ProductRulesTest.kt:54–58` 只测评分显示、业务日期与评分历史摘要；`AT/ProductVisualTest.kt:276–288` 只验证 null 标签→SetProgress(4.0)→±→4.3，不检验新位置、无评分范围语义或真实触摸端点。

合并前应有两类有意义的回归检查：

- **时间：必须补。** 在 `CT/ProductRulesTest.kt` 测本地跨午夜、两个输入 helper 的一致输出、非法 ISO 回退及删除 diff 的显示；固定/恢复测试时区和 Locale，避免依赖 CI 默认环境。卡片单独断言洛杉矶日期，防止“helper 测了但调用点遗漏”的 M1 再发生。
- **评分交互/无障碍：必须补。** 扩展 `AT/ProductVisualTest.kt:276` 的用例，检查 null 状态与按钮不存在、显式设分、最低端点触摸、清除回 null、0.1 步距，以及 disabled 时不可改值；检查 stateDescription/current/actions，而非仅调用 SetProgress。
- **空 panel：可以从简。** 此处是同一过滤式加一个条件，`DetailEditScreens.kt:57–65` 足以静态确认；一次空菜品/有地址餐厅的布局复核即可，不要求为这种低风险显示条件新建整套测试。现有截图 fixtures `AT/ProductVisualTest.kt:48,64` 有地址，不能冒充空菜品的验证。

已有 CI API26/35 成功只验证已有断言，不能证明上述新行为已被覆盖。

### 11. 静态编译检查 / imports / 未用变量 — PARTIAL

新增 helper 在 `C/Product.kt:98,102` 是公开顶层函数，使用的 java.time 类为全限定名。五个调用文件均已有 `top.alanvampuke.dininglog.core.*`：

| 文件 | import 行 |
| --- | --- |
| `F/restaurants/DetailEditScreens.kt` | 15 |
| `F/revisions/HistoryScreens.kt` | 16 |
| `F/restaurants/ListScreens.kt` | 25 |
| `F/settings/SettingsScreen.kt` | 24 |
| `F/components/QuietGridComponents.kt` | 35 |

`android/feature/build.gradle.kts:26` 已依赖 core；`:12` 的 minSdk=26 支持 java.time。`QuietGridComponents.kt:140` 已 OptIn ExperimentalMaterial3Api。`value` 为不可变函数参数，在 `:176` 非 null 检查后捕获到按钮 lambda，`:177–178` 的 toInt 调用没有显而易见的 nullable 编译错误。相对旧实现，非法数字字符串本来就在 Score/meter 的 `C/Product.kt:85`、`QuietGridComponents.kt:133` 使用 toInt，并非此次新增的容错退化。

`track = { slider ->` 的 slider 仍在 `QuietGridComponents.kt:166` 使用；extraFields 在 `DetailEditScreens.kt:58,60` 使用。全仓库没有 `TechnicalName` 符号；也没有 `allWarningsAsErrors` 配置。没有发现明确的新增编译错误，但静态阅读不能证明整个 build/lint 通过。作者的“沙箱没编译”声明是真实起始风险；现有仪器测试 CI 提供 debug 编译证据，主 build/lint/unit-test 仍因 B1 未完成。

### 12. backend / contracts / migrations 零改动 — PASS

`git diff --name-only 92e5f53..b5a9456 -- backend/ contracts/` 输出为空；`git diff --name-status` 仅六个 Kotlin 文件。migrations 位于 `backend/migrations/`，一并零改动。B1 是基线哈希失配，不是此次修改了受保护文件。

### 13. 版本号不变 — PASS

`android/app/build.gradle.kts:44–45` 仍为 `versionCode = 8`、`versionName = "0.8.0"`；该文件不在 diff 中。

### 14. 未引入位图 — PASS

六个改动文件都是 Kotlin，没有任何 res/ 位图变更。静态校验执行通过了 `android/tools/verify_bite_visuals.py:89–90` 的应用/feature 资源后缀检查，然后才在 `:115` 失败。仓库已有 docs/design 的 PNG 截图是评审材料，不是此次新增的 Android 资源。

### 15. 未评分 thumb 的浅/深色对比度与可见性 — PASS（fill 弱，轮廓清楚）

`F/theme/Colors.kt:18,23,28,33` 给出 paper/surface 色；`QuietGridComponents.kt:160–161` 传 paper 填充、border 轮廓和 shadow。WCAG sRGB 相对亮度计算结果：

| 模式 | paper / surface | 比值 | border / surface | border / paper |
| --- | --- | --- | --- | --- |
| 浅色 | #FAF4E4 / #FFFFFF | **1.098019:1** | #111111：18.883061:1 | 17.197397:1 |
| 深色 | #302B23 / #24211B | **1.143011:1** | #F5F2E8：14.329591:1 | 12.536699:1 |

两种 fill 对 surface 都很弱，不能仅据填充判断“深色 thumb 几乎看不见”。**实际 RatingEditor 父容器也是 paper**（`QuietGridComponents.kt:142`），所以填充对其为 1:1；实际轨道用 bg（`:165`），paper/bg 浅色 1.078182:1、深色 1.332162:1。区分边界靠 `BiteComponents.kt:40–46` 的默认 **2.5dp 描边**；深色 border 对 paper 为 **12.536699:1**，shadow 对 paper 为 **5.980954:1**。代码证据足以否定“必然几乎不可见”的对比度缺陷。未运行模拟器，不能把此计算当作截图/实际触摸验证；M3 与颜色可见性无关。

## 可复现计算与外部源码证据

以下脚本只计算，不运行 Android：

```python
from datetime import datetime
from zoneinfo import ZoneInfo

def lum(h):
    values = [int(h[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055)**2.4
              for v in values]
    return sum(v * w for v, w in zip(linear, (.2126, .7152, .0722)))

def contrast(a, b):
    low, high = sorted((lum(a), lum(b)))
    return (high + .05) / (low + .05)

for paper, surface, border in [
    ('#FAF4E4', '#FFFFFF', '#111111'),
    ('#302B23', '#24211B', '#F5F2E8'),
]:
    print(contrast(paper, surface), contrast(border, surface),
          contrast(border, paper))

iso = '2026-11-03T00:12:12.491Z'
instant = datetime.fromisoformat(iso.replace('Z', '+00:00'))
print(instant.astimezone(ZoneInfo('America/Los_Angeles'))
      .strftime('%Y-%m-%d %H:%M'), iso[:10])
```

Compose 版本来自 `android/feature/gradle.lockfile:20,24,34`，没有用最新 main 分支替代项目实际依赖。读取下列 Google Maven **sources.jar** 中对应文件：

| 依赖 / 文件 | 固定版本与下载地址 | 本次读取的关键行 |
| --- | --- | --- |
| material3 / Slider.kt | [material3-android 1.3.2](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.3.2/material3-android-1.3.2-sources.jar) | 273–277：state 更新；695：外层 modifier 后接 sliderSemantics；713–720：thumb 位置；1447–1497：SetProgress/range；1628–1631、1983–1985：同值点击过滤。 |
| foundation / ProgressSemantics.kt | [foundation-android 1.8.2](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.8.2/foundation-android-1.8.2-sources.jar) | 49–50：ProgressBarRangeInfo(current, range, steps)。 |
| ui / SemanticsConfiguration.kt | [ui-android 1.8.2](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.8.2/ui-android-1.8.2-sources.jar) | 112–138：同一节点 peer semantics 的外层 action 优先。 |
| ui / AndroidComposeViewAccessibilityDelegateCompat.android.kt | 同上 | 849–884：SeekBar/RangeInfo/操作；1377–1393：步距；3147、3194–3217：stateDescription 和百分比回退。 |

为复核来源，解压得到的 `Slider.kt` SHA-256 为 `14beb4ac8e4b4891b29df126aba32b307a6c8eef15bbb090c5e88a88ea5a07b4`；delegate 为 `9f3ec8707c24eea0772e065af6b7e0077c5e87e4f352583778cfd11554064ae1`。

Locale 结论另对照固定 [OpenJDK 17.0.13+11 DateTimeFormatter.java](https://github.com/openjdk/jdk17u/blob/jdk-17.0.13%2B11/src/java.base/share/classes/java/time/format/DateTimeFormatter.java#L553)、[DateTimeFormatterBuilder.java](https://github.com/openjdk/jdk17u/blob/jdk-17.0.13%2B11/src/java.base/share/classes/java/time/format/DateTimeFormatterBuilder.java#L2250)、[DateTimePrintContext.java](https://github.com/openjdk/jdk17u/blob/jdk-17.0.13%2B11/src/java.base/share/classes/java/time/format/DateTimePrintContext.java#L125)、[ZonedDateTime.java](https://github.com/openjdk/jdk17u/blob/jdk-17.0.13%2B11/src/java.base/share/classes/java/time/ZonedDateTime.java#L95)；这是 Java API/源码语义检查，不是假称做了 Android Locale 实测。

CI 状态来自只读 `GET /repos/vampuke/dining-log/commits/b5a9456/check-runs` 与 `GET /actions/jobs/{id}`；全四项为 backend 成功、API26 成功、API35 成功、android 失败。没有 dispatch/rerun。以上结论只针对本报告指定的提交。

## 附录：要求的六种时间引用逐行清单

下表覆盖检索所得全部 57 行，包括测试、非 UI 状态判断和后端引用；“不适用”表示该处不向用户显示时间，不能为了满足 UI 格式把排序/存储/契约也改成格式化字符串。

| 文件:行号 | 匹配引用 | 判断 |
| --- | --- | --- |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/CacheRules.kt:38` | `.deletedAt` | 不适用：过滤已删除的菜品缓存；不显示时间。 |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/CacheRules.kt:71` | `.updatedAt` | 不适用：按原始 updatedAt 排序；格式化会改变数据语义。 |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Writes.kt:42` | `.deletedAt` | 不适用：删除/恢复写入冲突状态判断。 |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Product.kt:103` | `Instant.ofEpochMilli` | PASS：新 millis formatter 内部的 Instant 构造，随后 atZone/format。 |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Product.kt:113` | `.updatedAt` | PASS：historySummary 走 relativeTime；不是绝对时间显示。 |
| `android/core/src/test/kotlin/top/alanvampuke/dininglog/core/ProductRulesTest.kt:45` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/core/src/test/kotlin/top/alanvampuke/dininglog/core/ProductRulesTest.kt:98` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/core/src/test/kotlin/top/alanvampuke/dininglog/core/ProductRulesTest.kt:100` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/ProductRepositoryImpl.kt:113` | `.deletedAt` | 不适用：成功保存后的删除状态判断。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/ProductRepositoryImpl.kt:135` | `.updatedAt` | 不适用：缓存摘要传递原始 revision.created_at / updatedAt。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/ProductRepositoryImpl.kt:136` | `.deletedAt` | 不适用：缓存 fields/diff 与 deleted 布尔状态构造。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/remote/ProductApi.kt:111` | `.updatedAt` | 不适用：API→领域摘要保留原始时间，显示层负责格式化。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/remote/ProductApi.kt:112` | `.deletedAt` | 不适用：deleted 标志构造；不显示时间。 |
| `android/data/src/test/kotlin/top/alanvampuke/dininglog/data/DirectSaveTest.kt:98` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ProductViewModelTest.kt:17` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ProductViewModelTest.kt:263` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:38` | `.updatedAt` | PASS：created_at 回退 updatedAt，走 relativeTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:40` | `.updatedAt` | PASS：同一时间来源走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:57` | `.deletedAt` | 不适用：控制历史说明/隐藏/恢复按钮。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:125` | `.deletedAt` | 不适用：已删除记录禁用 apply-save。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:129` | `.deletedAt` | 不适用：显示“已删除”错误，不显示删除时间。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:134` | `.deletedAt` | 不适用：已删除记录禁用字段选择。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:54` | `.updatedAt` | PASS：版本更新时间走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:78` | `.deletedAt` | 不适用：null 分支显示编辑/删除按钮，阻止空删除时间文案。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:82` | `.deletedAt` | PASS：非 null 删除时间走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:87` | `.deletedAt` | 不适用：活跃餐厅才显示菜品/到店管理区域。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:114` | `.updatedAt` | PASS：修改历史区更新时间走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt:187` | `.updatedAt` | FAIL：updatedAt.take(10) 原始 UTC 日期直接进入用户卡片（M1）。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt:202` | `.updatedAt` | PASS：历史列表展开更新时间走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt:288` | `.deletedAt` | 不适用：已删除当前记录不允许提交重新应用字段。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:43` | `.createdAt` | PASS：profile.createdAt 走 formatAbsoluteTime。 |
| `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:57` | `.createdAt, .lastSeenAt, .expiresAt` | PASS：session.createdAt / lastSeenAt / expiresAt 三者均走 formatAbsoluteTime。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:232` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:236` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:295` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:299` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:312` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:318` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:331` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:335` | `.deletedAt` | 不适用：waitFor 检查 deletedAt 的删除/恢复完成状态；不是日期文案断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductVisualTest.kt:49` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductVisualTest.kt:52` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductVisualTest.kt:68` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductVisualTest.kt:70` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P8VisualTest.kt:57` | `.updatedAt` | 不适用：测试 fixture 的时间传递；不是时间文字断言。 |
| `design/P7_TASK.md:70` | `.updatedAt` | 不适用：设计任务书中的数据字段说明，不是 UI 调用。 |
| `backend/src/auth/session.ts:50` | `.expiresAt` | 不适用：refresh token 的 expiresAt 存储绑定。 |
| `backend/src/auth/session.ts:56` | `.expiresAt` | 不适用：认证响应保留 ISO 契约，客户端处理显示。 |
| `backend/src/auth/rotation.ts:28` | `.expiresAt` | 不适用：token/session 响应保留 ISO 契约。 |
| `backend/tests/capacity/capacity_test.ts:140` | `.createdAt, .updatedAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:146` | `.createdAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:153` | `.updatedAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:160` | `.createdAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:169` | `.createdAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:173` | `.updatedAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:175` | `.updatedAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
| `backend/tests/capacity/capacity_test.ts:294` | `.updatedAt` | 不适用：后端容量 fixture / SQL / 汇总排序；不显示 Android 时间文字。 |
