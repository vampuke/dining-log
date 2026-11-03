# PR #16 独立评审

## 结论：不可合并

评审对象为 `origin/master...origin/pr14-rebased`：基线 `cee8b005b4547aad227f4f43bc91bc46a7398340`，目标 `e6a5f4091e165d5c609bd64f2259c8843e8598ef`，共同祖先为已合并 #13 的 `4db6cf7`。确认 8 文件、+111/-24；未修改产品代码。

存在确定的 Kotlin 静态编译错误，以及手动更新入口被开关隐藏等功能缺陷。以下运行行为分析均以修复编译错误为前提；没有运行编译器、Gradle、单测、仪器测试、lint 或应用。

行号均来自目标分支，不是评审分支。下文文件名唯一，对应路径见文末索引。问题编号 B/M/N 仅用于交叉引用，25 个任务问题在后面逐一回答。

## BLOCKER

- **[BLOCKER B1] Updates.kt:59 — `checkNow()` 引用没有保存为属性的构造参数，无法编译 — 证据：`Updates.kt:29` 的 `gateway`、`Updates.kt:30` 的 `installedVersionCode` 都没有 `val/var`；原来仅在 `init` 中使用，新成员函数在 `Updates.kt:59`、`Updates.kt:60` 使用它们。Kotlin 普通主构造参数可用于初始化代码，不能作为成员函数中的属性访问；这里会产生 unresolved reference。不是缺少 import，也不是 Compose 错误 — 建议：把两者声明为 `private val` 或保存到私有属性，再让 CI 完成 Android 编译。**

- **[BLOCKER B2] SettingsScreen.kt:53 — 关闭启动检查后，手动发现新版本仍没有可操作入口 — 证据：`Updates.kt:56`、`Updates.kt:61` 保留 `enabled=false` 并写入 release；`SettingsScreen.kt:53` 仍要求 `updateState.enabled && release != null` 才创建「有新版本」按钮。`SettingsScreen.kt:90` 的手动按钮始终可见，但成功后既没有新版本提示，也无法打开更新；`MainActivity.kt:118` 只有入口回调才设置 `showUpdate=true`。这直接违背开关关闭仍可手动查找更新的功能目的 — 建议：区分自动检查偏好与手动检查结果的可见性，使手动结果在偏好关闭时也可打开；保留明确的自动检查关闭语义。**

- **[BLOCKER B3] ListScreens.kt:148 — 删除范围说明后，把当前加载/筛选子集的统计呈现为无范围的「餐厅 / 均分 / 菜品」，误导用户 — 证据：`ListScreens.kt:68` 只传 `state.items`；`ListScreens.kt:143`、`ListScreens.kt:144`、`ListScreens.kt:148` 完全没有取服务器总数；`ProductApi.kt:115` 每页 limit=20，`ListScreens.kt:136` 仍有加载更多。新增注释 `ListScreens.kt:145` 承认均分仅覆盖已加载内容，却删除原有「已加载」说明。筛选时统计也仍显示，因为 `ListScreens.kt:68` 没有排除 filters。例：账号有 21 家餐厅，前 20 家都没有菜，第 21 家有菜，首屏可显示「20 家 / … / 0 道」 — 建议：在统计栏增加「当前已加载结果」等简短范围说明，或使用覆盖同一明确范围的服务端聚合。此项是展示误导，不是声称数据库计数被写坏。**

## MAJOR

- **[MAJOR M1] Updates.kt:56 — 手动请求完成时会覆盖检查期间已变更的偏好状态 — 证据：`Updates.kt:56` 在请求前抓取 enabled，`Updates.kt:61`、`Updates.kt:62` 用旧值重建整个状态；偏好收集器 `Updates.kt:36`、`Updates.kt:37` 同时写状态。可达顺序：enabled=true → 手动请求挂起 → 用户关闭开关，收集器写 false → 手动请求完成写回 true。持久化偏好仍是 false，但 `SettingsScreen.kt:83` 的 Switch 显示 true，更新入口也跟着错误显示；反向切换同样会写回旧 false。`collectLatest` 只取消自己的后台检查，取消不了 `MainActivity.kt:117` 启动的手动请求 — 建议：把偏好状态独立管理，结果更新保留当前偏好，并明确处理后台与手动检查的并行写入。**

- **[MAJOR M2] Updates.kt:55 — 没有 in-flight 守卫或请求所有权，重复调用会并发请求并相互覆盖结果/进度 — 证据：`Updates.kt:57` 无条件设置 checking，`Updates.kt:59` 无条件调用 latest；`SettingsScreen.kt:90` 的禁用只在 UI 收到状态并重组后生效，不是检查器的同步守卫。两个调用 A/B 均可挂起，A 完成即把 checking 变 false，而 B 仍在运行；较晚完成的旧结果可覆盖较新结果。后台检查 `Updates.kt:40`、`Updates.kt:42` 也不参与 checking 协调；异常/取消清理 `Updates.kt:64`、`Updates.kt:65` 可清除另一个请求的进度 — 建议：在检查器内原子拒绝重复调用或合并请求，并让请求完成/取消只修改自己拥有的状态；统一后台与手动的协调策略。**

- **[MAJOR M3] Updates.kt:65 — 手动检查失败没有可辨识的反馈 — 证据：AppUpdateState 在 `Updates.kt:21`、`Updates.kt:22` 没有失败字段；catch 只清除 checking。`SettingsScreen.kt:90`、`SettingsScreen.kt:93` 只呈现进行中、可点击按钮及「已是最新版本」，没有错误或重试原因。网络失败后的状态可与从未检查相同，用户无法判断是否查到了结果 — 建议：给手动检查增加明确失败状态/非阻塞错误提示；只有有效响应确认版本不更新时显示「已是最新版本」。**

- **[MAJOR M4] DetailEditScreens.kt:187 — 折叠首访会隐藏阻止保存的字段错误 — 证据：日期和备注的错误仅在 `firstVisitExpanded` 为 true 时分别经 `DetailEditScreens.kt:188`、`DetailEditScreens.kt:190` 显示；折叠摘要 `DetailEditScreens.kt:182` 不显示错误，也没有错误触发的展开。可达操作：展开、填入非法日期或超长备注、收起、保存；`ProductViewModel.kt:227` 阻止提交并只给「请检查标记的输入」，实际标记在隐藏区。`DetailEditScreens.kt:180` 使用普通 remember，旋转重新建立组合时也会折叠，而 ViewModel 可保留非法输入 — 建议：校验失败自动展开首访，或在折叠摘要显示可访问的具体错误，并保存合理的展开状态。**

- **[MAJOR M5] ListScreens.kt:221 — 新历史时间控件缺少按钮角色和动作标签 — 证据：两态 `ListScreens.kt:221`、`ListScreens.kt:223` 都只有 `.clickable { ... }`，没有 `role=Role.Button`、`onClickLabel` 或状态语义；替换前使用的 `BiteTextButton` 在 `BiteComponents.kt:148` 包装 Material TextButton，并有 48dp 最小布局高度。新文字样式行高仅见 `Typography.kt:44`、`Typography.kt:45` 的 16.5sp。clickable 自带点击/焦点语义，因此不能说 TalkBack 完全无法操作；可确认丢失的是按钮角色、明确动作描述和显式最小布局目标，且控件位于可点击卡片 `ListScreens.kt:167` 内 — 建议：补两态动作标签、Button 角色、可读切换状态，并验证触摸目标与父卡片导航不会冲突。**

- **[MAJOR M6] ListScreens.kt:223 — 「时间文本切换相对/绝对」没有完整实现 — 证据：相对态控件仍是「显示绝对时间」，不是时间；相对时间在 `Product.kt:116` 的 historySummary 中并一直由 `ListScreens.kt:211` 显示。展开只改变下方控件文本，相对时间并不消失，因此绝对态同时显示两种时间。任务书指向的 `HistoryScreens.kt:38`、`HistoryScreens.kt:39`、`HistoryScreens.kt:40` 更是完全未改，仍是独立按钮加新增一行时间；对比目标与 master，此文件逐字相同 — 建议：明确本批只改列表还是也应改历史详情；若要求单个时间文字切换，分离修改摘要与时间，并让同一个控件真正替换时间显示。**

## MINOR / NIT

- **[MINOR N1] DetailEditScreens.kt:188 — 非空非法首访日期失去即时格式提示 — 证据：supporting 只按 `visit.date.isBlank()`，因此输入 `2026-13-45` 后「YYYY-MM-DD」立刻消失；`QuietGridComponents.kt:213` 只有 error 存在时才另显示错误。提交后 `ProductViewModel.kt:345` 会给包含格式的有效性错误，所以并非永久没有纠错线索 — 建议：非空但 `!validDate(visit.date)` 时仍保留格式提示，或及时校验。此条件只作用于首访日期，普通到店日期仍在 `DetailEditScreens.kt:165` 保留提示。**

- **[MINOR N2] ListScreens.kt:220 — 窄屏/大字体下时间切换可改变行高，尚无稳定尺寸保证 — 证据：短文本「显示绝对时间」变成「绝对时间 yyyy-MM-dd HH:mm · 收起」，`GridText` 在 `QuietGridComponents.kt:42` 默认无限行；`ListScreens.kt:177` 可用宽度来自 weight 列，没有预留切换后的高度。caption/date 都是 16.5sp 行高（`Typography.kt:44`、`Typography.kt:45`），一行时不因行高设置不同而变化，但长文本换行后卡片会增长，后续列表位置可能改变。没有设备运行证据，不能声称已观察到跳动或裁切 — 建议：增加窄屏/200% 字体的布局用例，保持文字完整可读，并决定是否预留稳定高度。**

- **[NIT N3] ListScreens.kt:211 — 去重只做精确相等，不处理空白/大小写 — 证据：`takeIf { it != record.title }`，不是 contains；不同大小写或额外空格会保留。生产映射 `Product.kt:20` 通常直接以 entity_label 作 title，精确相等本身合理，未发现必须做模糊匹配的产品契约 — 建议：若支持异源标题，明确是否 trim/规范化后再比较；不要用 contains 误删有用上下文。**

- **[NIT N4] SettingsScreen.kt:102 — 当前设备按钮变成「退出登录」，确认框仍叫「撤销此会话？ / 确认撤销」 — 证据：`SettingsScreen.kt:67` 只按 current 改了按钮文案，确认框标题和确认按钮没有同样分支，虽然正文解释了退出和缓存清理，后端操作语义也正确 — 建议：当前设备确认框采用「退出登录？ / 确认退出」，其他设备保持撤销措辞。**

## 25 个问题逐项回答

### 1. checkNow 签名、返回类型、调用点，以及 MainActivity 的 +1 行

`Updates.kt:55` 是无参数 `suspend fun checkNow()`，块体默认返回 Unit；`AppVersionGateway.latest()` 在 `Updates.kt:23` 返回 AppRelease，返回类型匹配。`MainActivity.kt:117` 新行是 `onCheckUpdate = { scope.launch { updates.checkNow() } }`，必要：将 UI 普通 Unit 回调桥接到协程调用；scope 在 `MainActivity.kt:78` 创建，launch import 在 `MainActivity.kt:31`。`ProductRoot.kt:52`、`ProductRoot.kt:138`、`SettingsScreen.kt:34`、`SettingsScreen.kt:90` 逐层接通；末尾新增有默认值的参数不破坏原有调用。**签名连线正确，但函数体因 B1 无法编译**：`Updates.kt:29`、`Updates.kt:30`、`Updates.kt:59`、`Updates.kt:60`。

### 2. 全量扫描 8 文件的 Composable 上下文

未发现本批重复 #13 的 DrawScope 错误。`QuietGridComponents.kt:155` 在空轨道 Canvas 之前读颜色，DrawScope `QuietGridComponents.kt:162` 只用捕获的 c；有评分 track 在 `QuietGridComponents.kt:178` 先读 c，再于 `QuietGridComponents.kt:179` 创建 Canvas。`BiteComponents.kt:102`、`BiteComponents.kt:121` 同样先读 c；绘制修饰符 `BiteComponents.kt:40`、`BiteComponents.kt:48`、`BiteComponents.kt:57`、`BiteComponents.kt:61` 只接受普通 Color。`QuietGridComponents.kt:125` 的 drawBehind 用 `QuietGridComponents.kt:120` 捕获的 c。新增 `DetailEditScreens.kt:180` 的 remember 初始化仅创建 mutableStateOf；`DetailEditScreens.kt:182` 的颜色读取在 composable Row 内容中。`ListScreens.kt:70`、`ListScreens.kt:77` 的文本/icon lambda 是 composable 槽；inline forEach 的 UI 调用也不能误判成普通异步回调。检查 `MainActivity.kt:71`、`MainActivity.kt:79`、`ProductRoot.kt:106`、`ProductRoot.kt:129`、`SettingsScreen.kt:38` 的副作用及各点击回调，未见 composable 调用被放入这些非 composable 执行体。**这是完整文件静态检查结论，不是 Compose 编译通过证明。**

### 3. 新符号 import、删除后残留引用

没有发现新增 import 缺失或已删除函数的残留调用。clickable 在 `ListScreens.kt:3` 已 import；remember/委托在 `DetailEditScreens.kt:8` 的 runtime.*；LocalDate/UUID 在 `DetailEditScreens.kt:13`、`DetailEditScreens.kt:14`；CancellationException 在 `Updates.kt:4`；AppUpdateState 和组件/主题由 `SettingsScreen.kt:24`、`SettingsScreen.kt:26`、`SettingsScreen.kt:27` 导入。`isNewerVersion` 在同包 `UpdateInstall.kt:8`；`GridText`、`BiteTextField`、`FormField` 定义分别在 `QuietGridComponents.kt:41`、`BiteComponents.kt:153`、`QuietGridComponents.kt:209`，新增调用签名匹配。diff 删除的是按钮/文字/分割线，没有删除函数。**B1 的 unresolved reference 不是 import 问题；必须存储构造参数。**

### 4. 有新版本时入口是否真的出现

开启偏好时，`Updates.kt:61` 写 release → `MainActivity.kt:68` 生命周期感知收集 StateFlow → `MainActivity.kt:116` 传入 ProductRoot → `ProductRoot.kt:138` 传入设置 → `SettingsScreen.kt:53`、`SettingsScreen.kt:54` 创建入口。它排在账号块后、会话块前；若用户在下方检查按钮处，需要回滚才能看到，并未自动弹窗。**关闭偏好时不会出现，见 B2**：`SettingsScreen.kt:53` 额外 gating；只有点击入口才由 `MainActivity.kt:118` 打开更新面板。

### 5. 最新版本、成功/无更新/失败是否可区分

有效响应版本不大于已安装版本时，`Updates.kt:62` 写 `upToDate=true, release=null`，`SettingsScreen.kt:93` 显示「已是最新版本」。新版本靠 release 区分，但入口还受 Q4 的开关问题影响。失败在 `Updates.kt:65` 仅恢复 checking=false，**与从未检查不可区分**，见 M3。

必须区分误报路径：串行异常会先经过 `Updates.kt:57` 清除 upToDate，所以网络失败本身不会被这条串行路径误报成「已是最新版本」。但 `Updates.kt:60`、`Updates.kt:62` 把 `!release.valid` 也当成最新，接口若返回非法 AppRelease 会误报；实际注入 `AppModule.kt:36` 的 PublicAppVersionGateway 在 `AppVersionGateway.kt:32`、`AppVersionGateway.kt:40`、`AppVersionGateway.kt:41` 校验并抛异常，因此生产中的非法响应一般走静默失败。并发时可由另一请求先写 upToDate=true，后失败请求 `Updates.kt:65` 的 copy 保留它；这不是可靠的本次成功反馈，见 M2。

### 6. 关闭启动检查时 checkNow 是否能工作

检查器没有偏好 early-return：`Updates.kt:55` 到 `Updates.kt:59` 无条件请求，与 `Updates.kt:38` 后台的 `if (enabled)` 独立。**网络请求能发出，完整手动更新功能却失败**：成功保留 false（`Updates.kt:56`、`Updates.kt:61`），入口隐藏（`SettingsScreen.kt:53`），见 B2。

### 7. 快速双击与并发重入

没有 in-flight guard。两个回调在 UI 禁用前到达，或两个调用方直接调用，都可分别执行 `Updates.kt:59`；`MainActivity.kt:117` 每次启动独立协程。`SettingsScreen.kt:90` 的 `enabled=!checking` 通常减少重复点击，但不保证原子排他。也会与 `Updates.kt:40` 的后台检查重叠。结果、异常及取消均可覆盖另一请求状态，见 M1/M2。**没有运行双击测试，不能说每次双击必然触发两个请求。**

### 8. 离页/旋转时的生命周期

手动调用使用 `MainActivity.kt:78` 的 rememberCoroutineScope，不属于设置页局部组合。因此离开设置页仍可继续写入检查器的 StateFlow（`Updates.kt:31`、`Updates.kt:32`），并不是写入已销毁的设置页 remember state。检查器是 `AppModule.kt:37` 的 Singleton；UI 用 `MainActivity.kt:68` 的 collectAsStateWithLifecycle。旋转销毁原组合会取消手动协程，`Updates.kt:63`、`Updates.kt:64` 清理 checking 并重抛取消；Retrofit suspend 网关在 `AppVersionGateway.kt:37`、`AppVersionGateway.kt:40` 使用可取消请求。未发现静态上的销毁页面 state 回调泄漏，但取消清理仍会与其他请求竞争（M2），需生命周期/并发测试，不能据此宣称旋转已实测安全。

### 9. 「餐厅 / 均分 / 菜品」数字来源，零菜品风险

`ListUiState` 没有单独的全局菜品总数，只有 `Product.kt:51` 到 `Product.kt:55` 的 items/分页/缓存状态。`ListScreens.kt:68` 传 items：餐厅数是 `records.size`（`ListScreens.kt:148`）；均分是非 null 且可解析的 rating_tenths 平均后除 10、四舍五入显示一位（`ListScreens.kt:143`、`ListScreens.kt:149`）；菜品数是每个摘要 `fields["dish_count"]` 的和（`ListScreens.kt:144`、`ListScreens.kt:150`），**不是缓存菜品行数**。

在线 dish_count 来自后端每家餐厅未删除菜品的 SQL count（`queries.ts:35`、`queries.ts:39`），Android 在 `ProductApi.kt:96`、`ProductApi.kt:107`、`ProductApi.kt:112` 保留到摘要 fields。缺失或非法计数时显示「未提供」，不会默认成零；空 records 的 any=false、sum=0，会显示 0 道。分页/筛选也可能只包含零菜品餐厅，账号其他餐厅实际有菜；缓存统计还可能过期。因此**可以出现无范围的「0 道」与账号实际有菜并存**，见 B3，不能归因于新增一个缺失的 ListUiState 菜品字段。

### 10. 评分阈值与实际 predicate，精确边界

一致，31 本身包含。`ListScreens.kt:88` 设置 minRating=46，`ListScreens.kt:89` 设置 maxRating=31，均清空另一侧并设置 unrated=false；回调经 `ProductViewModel.kt:142`、`ProductViewModel.kt:143` 设置 filters。在线 `ProductApi.kt:128`、`ProductApi.kt:129` 转成 min_rating=4.6/max_rating=3.1，后端 `queries.ts:33` 使用 **rating_tenths >= 46 / <= 31**；restaurant/dish 分别在 `queries.ts:59`、`queries.ts:73` 采用同一 ratingWhere。离线 `CacheRules.kt:54` 只排除 `< min`、`> max` 和缺失评分，因此也是 >=46、<=31。边界 45 排除高档、46 包含；31 包含低档、32 排除；null 不入两档。`BiteComponents.kt:86`、`BiteComponents.kt:88`、`BiteComponents.kt:89` 的 verdict 档位同样一致。未发现 `<31` 的 off-by-one。

### 11. 菜品与餐厅 chip 文案分支

确实按 kind 分支：`ListScreens.kt:86`、`ListScreens.kt:87` 对 DISH 用「惊艳 ★4.6+ / 踩雷 ★3.1−」，否则在 restaurant/dish 外层限定内用餐厅文案（`ListScreens.kt:81`）。展开筛选说明 `ListScreens.kt:237` 也分 kind；餐厅特有 category/cuisine/到店状态只在 `ListScreens.kt:229` 分支出现。不是忘改的复制粘贴文案。

### 12. 折叠且不展开时默认日期/餐次是否进入 payload

会，数据独立于可见输入控件。`ProductViewModel.kt:185`、`ProductViewModel.kt:186` 新建 SaveInput.firstVisit，日期为今天；`Writes.kt:7` 的 meal 默认是 **null**，没有默认早餐/午餐。`DetailEditScreens.kt:180` 只新增显示展开 state，没有清空 firstVisit。保存从 `ProductViewModel.kt:225` 取 state.input，`ProductViewModel.kt:231` 提交该 input；`ProductApi.kt:166`、`ProductApi.kt:167`、`ProductApi.kt:168` 始终从 firstVisit 生成 dining_date、meal（null）与 note（null）。取消「同时新增首访」才在 `DetailEditScreens.kt:171` 把 firstVisit 设成 null，省略整个 first_visit。存在的是 M4 的隐藏错误问题，不是折叠漏传默认字段。

### 13. 「今天」使用本地日期还是 UTC

设备本地日期。首次创建在 `ProductViewModel.kt:183`、`ProductViewModel.kt:186`，重新勾选在 `DetailEditScreens.kt:171`，都是无 Clock/Zone 参数的 `LocalDate.now()`，依 JVM/设备默认时区，不是 Instant 在 UTC 下截断。后端和契约把 dining_date 当不做时区转换的日期（`schemas.ts:14`、`openapi.yaml:4762`）。该静态路径不会因为设备在 UTC±时区而按 UTC 日期错填；没有运行跨午夜/时区设备测试。与 #13 的绝对时间展示属于不同类型：`Product.kt:102` 仍用 systemDefault。

### 14. 非空非法日期的格式提示

会消失，但范围是新增折叠首访的日期控件：`DetailEditScreens.kt:188` 仅 isBlank 时给 supporting，因此 `2026-13-45` 在提交前没有格式提示。普通到店日期 `DetailEditScreens.kt:165` 仍常驻提示。提交后 `ProductViewModel.kt:345` 用 validDate 给带「YYYY-MM-DD（1900–2100）」的具体错误，`QuietGridComponents.kt:213` 优先显示 error；若区域折叠，该错误仍看不到，见 M4/N1。不能写成「非法日期能通过保存」或「用户永久失去所有纠错线索」。

### 15. 「不选餐次」语义及 null/空字符串契约

是标签变化，仍传 null code：普通到店 `DetailEditScreens.kt:159`，首访 `DetailEditScreens.kt:189`；`QuietGridComponents.kt:218` onSelect(code) 原样传出。普通字段提交在 `ProductApi.kt:163` 将 null 写 JsonNull，首访在 `ProductApi.kt:168` 也明确写 JsonNull，不是空字符串。后端 `schemas.ts:21` 允许 nullable meal，契约 `openapi.yaml:4446` 到 `openapi.yaml:4455` 的 Meal 允许 null，VisitCreate 引用它（`openapi.yaml:4763`、`openapi.yaml:4764`）。未发现语义变化或契约破坏。

### 16. 未设置显示名与 null 分支

正常注册未传显示名时，后端 `routes.ts:69` 写 `input.display_name ?? input.username`，因此 displayName **等于 username，不是 null**；`Repositories.kt:19` 的 AccountInfo.displayName 是非 nullable String。合法 User 契约 `openapi.yaml:4410`、`openapi.yaml:4423` 要求字符串 display_name。设置页在 `SettingsScreen.kt:46` 取得的 nullable 值来自 **profile 可能为 null**，并在 `SettingsScreen.kt:47` 隐藏账号行；用户名仍由 `SettingsScreen.kt:42` 的 PageHeader subtitle 显示，故不丢失账号识别。网关对异常缺失 display_name 在 `RetrofitAccountSecurity.kt:36` 兜底为 ""，此时不是 null，会显示空「账号」行；这是已有异常响应降级，非本批引入的正常注册问题。

### 17. 当前设备「退出登录」是否实际吊销全部会话

不会吊销全部。按钮只对 current 改文案（`SettingsScreen.kt:67`），确认后传单个 id（`SettingsScreen.kt:102`）→ `ProductViewModel.kt:304` → `RetrofitAccountSecurity.kt:26`、`RetrofitAccountSecurity.kt:46` 的 DELETE auth/sessions/{id}。实际后端路由 `routes.ts:163`、`routes.ts:168` 检查目标属于此用户，`routes.ts:170` 调用 revokeSession；SQL `session.ts:72` 同时限定 **user_id=? AND id=?**，不是用户所有 sessions。Android 在 `RetrofitAccountSecurity.kt:49` 仅 id 匹配当前 session 时 account.clear；`Authentication.kt:39`、`Authentication.kt:40`、`Authentication.kt:41`、`Authentication.kt:42` 清会话、账号 scope、token 与本地缓存。因此退出当前会话的语义正确；确认框文案尚未同步，见 N4。未实测多设备吊销。

### 18. 去掉会话卡片底部分割线后的视觉边界

代码仍有完整卡片外框：`SettingsScreen.kt:60` 使用 BitePanel，`BiteComponents.kt:69` 加 brutalBox 和 14dp padding；`BiteComponents.kt:43`、`BiteComponents.kt:44`、`BiteComponents.kt:45`、`BiteComponents.kt:46` 绘制阴影/背景/圆角边框，`SettingsScreen.kt:40` 会话项之间还有 16dp 间距。删除的是内部按钮下面的 divider，不是外边框，最后一个会话也用同一组件。静态上没有「最后一项没有视觉边界」的证据；视觉是否显得截断需要实际截图，未验证。

### 19. 时间文字切换的 TalkBack 焦点/触发与角色标签

首先纠正文件范围：`HistoryScreens.kt:39` 的按钮仍存在，真正新改的是 `ListScreens.kt:220` 到 `ListScreens.kt:224` 的历史列表行。Modifier.clickable 会提供点击 action/焦点，Text 会提供文字语义（`QuietGridComponents.kt:43`），所以不能断言 TalkBack 无法聚焦或触发。**可以确认两态都没有 onClickLabel/Role.Button**，父卡片还可点击导航（`ListScreens.kt:167`），见 M5。也没有记录真实 TalkBack 手势测试；应验证子动作独立触发而不打开详情，以及两态动作说明和触摸目标。

### 20. 切换行高、列表跳动、裁切

两种样式都用 11sp/16.5sp（`Typography.kt:44`、`Typography.kt:45`），**一行时 nominal 行高一致**。但 `ListScreens.kt:220` 的绝对态比 `ListScreens.kt:223` 长，`QuietGridComponents.kt:42` 的 GridText 默认 maxLines=Int.MAX_VALUE，weight 列宽受屏幕限制（`ListScreens.kt:177`）；窄屏和字体放大时可以换行增长，没有预留高度，后续位置可能变动。时间本身没有 fixedHeight 或 maxLines 限制，不能从代码声称它必然裁切。仍显示两种时间的行为见 M6；详情页 `HistoryScreens.kt:40` 本来就是额外增加一行，未被本批稳定行高。需布局实测，见 N2。

### 21. 标题/副标题去重条件

`ListScreens.kt:211` 的 `it != record.title` 是精确字符串不等；相等则删 entity_label，不是 contains；entity_label 不存在才补「记录」。大小写或空格不同会保留重复视觉文本。生产 `ProductApi.kt:108`、`ProductApi.kt:111` 从 snapshot.title 建 summary，`Product.kt:20` 通常直接使用 entity_label，所以一般精确相等，未发现生产必须忽略大小写的证据。`HistoryScreens.kt:30` 的标题副标题未改，不要把列表去重写成历史详情也完成了。见 N3/M6。

### 22. #13 回归：空轨道和 formatAbsoluteTime

两项保留。对比 master/目标分支，`QuietGridComponents.kt:141` 到字段 placeholder 之前的整个 RatingEditor 源码逐字相同：`QuietGridComponents.kt:152` 的 null 分支画空 Canvas，`QuietGridComponents.kt:155` hoist 颜色；非 null 才 Slider（`QuietGridComponents.kt:167`），非 null 才 steppers（`QuietGridComponents.kt:191`），显式设置/清除按钮仍在 `QuietGridComponents.kt:195`。null 分支原有 enabled SetProgress action（`QuietGridComponents.kt:158`）也保留，不能说这批删除了它。`ListScreens.kt:202` 的更新时间日期仍调用 formatAbsoluteTime，新增绝对时间 `ListScreens.kt:220` 也调用；helper `Product.kt:101` 到 `Product.kt:108` 与 master 相同、仍本地时区格式。**没有丢掉指定的两个已合并修复，但新增 checkNow 使整个项目无法编译（B1），列表统计范围说明被删除（B3），历史 TextButton 的语义退化（M5）。**

### 23. 新增字符串与资源风格

确有硬编码：「不选餐次」在 `DetailEditScreens.kt:159`、「检查更新 / 正在检查… / 已是最新版本」在 `SettingsScreen.kt:91`、`SettingsScreen.kt:93`，chip 文案在 `ListScreens.kt:86`、`ListScreens.kt:87`，例子在 `QuietGridComponents.kt:204` 到 `QuietGridComponents.kt:206`。现有风格同样硬编码：`Product.kt:71`、`Product.kt:80` 的标签，`BiteComponents.kt:84` 的 verdict，`HistoryScreens.kt:39` 的原时间按钮。因此不是本批单独引入的资源体系回归；若要国际化，宜整体提取字符串、格式参数与复数，而不把这批孤立文字资源化列为编译 BLOCKER。

### 24. 是否新增测试；必须补的用例与断言

**没有新增或修改测试。** diff 8 文件全是 main Kotlin 源；检索目标分支测试目录没有 checkNow、check-update、first-visit-toggle、history-time:/history-absolute: 调用或断言。现有 `UpdatesTest.kt:19`、`UpdatesTest.kt:43`、`UpdatesTest.kt:64` 只覆盖自动检查相关行为，`P6UpdateUiTest.kt:29`、`P6UpdateUiTest.kt:35` 直接塞入旧 AppUpdateState，`RemoteContractTest.kt:41`、`RemoteContractTest.kt:45` 只直接验证构造好的首访请求日期，不能覆盖新增交互。

必须补充至少以下用例（这里只建议，没有编写或运行）：

- **手动检查的三种结果**（`Updates.kt:55`、`SettingsScreen.kt:90`、`SettingsScreen.kt:93`）：有效新版本得到可打开入口；同版/旧版显示最新；网络/解析/校验失败显示失败而非最新，并允许重试，checking 每种终态都 false。
- **启动检查关闭的手动新版本**（`Updates.kt:61`、`SettingsScreen.kt:53`）：偏好 false 时后台请求数=0；手动请求数=1；完成后偏好仍 false，但新版本入口可见且点击把同一 release 交给 onOpenUpdate。
- **手动请求期间切换偏好**（`Updates.kt:56`、`Updates.kt:61`、`Updates.kt:37`）：用挂起 gateway，分别 true→false 和 false→true，完成后 UI enabled 始终等于最后持久化偏好，旧结果不覆盖新偏好。
- **重复检查及后台重叠**（`Updates.kt:40`、`Updates.kt:59`、`Updates.kt:65`）：两个同时调用只发生一次实际网络请求或按明确策略合并；一个完成/取消不提前清除另一个请求的 checking；旧结果不覆盖新请求结果。
- **离页/旋转取消**（`MainActivity.kt:78`、`MainActivity.kt:117`、`Updates.kt:64`）：离设置页按约定继续全局检查；销毁 Activity 后手动请求取消，checking 清除；旋转后的新检查不被旧取消清理覆盖，无旧 UI 回调。
- **餐厅/菜品两套 chip 边界**（`ListScreens.kt:86`、`ListScreens.kt:88`、`queries.ts:33`、`CacheRules.kt:54`）：输入 45/46/31/32/null，在线/离线两套结果一致，高档仅 >=46、低档含 31 排除 32、两档都排除 null；验证 DISH 与 RESTAURANT 标签不同。
- **统计的分页/缺失/空集范围**（`ListScreens.kt:68`、`ListScreens.kt:150`、`ProductApi.kt:115`）：21 家餐厅、第 21 家有菜时，首屏明确标注已加载子集；加载更多后数量/均分/菜品数同步更新；缺少任意 dish_count 显示未提供，空缓存不能被描述成已确认账号零菜品。
- **首访不展开直接保存**（`DetailEditScreens.kt:180`、`ProductViewModel.kt:186`、`ProductApi.kt:168`）：通过真实 ViewModel/请求路径保存，payload 含设备本地今天及 meal:null；勾选关闭时不含 first_visit；控件可见性不影响提交数据。
- **首访非法输入后折叠/旋转再保存**（`DetailEditScreens.kt:187`、`ProductViewModel.kt:227`、`ProductViewModel.kt:345`）：非法日期和超长 note 都阻止提交，并在无需猜测展开位置的情况下显示具体错误；非空非法日期保留格式线索。
- **时间文字的语义与布局**（`ListScreens.kt:221`、`ListScreens.kt:223`、`Typography.kt:45`）：两态具有 OnClick、Button role、不同动作标签；触发只切换时间不调用父导航；窄屏、200% 字体无文字丢失，若要求稳定行高则断言相邻卡片位置不变；明确断言相对/绝对态是否只显示一种时间。
- **显示名/当前设备会话文案和隔离**（`SettingsScreen.kt:47`、`SettingsScreen.kt:67`、`session.ts:72`）：profile=null/default username/custom name 三种展示正确；当前会话退出只吊销目标 id、清本机账号数据，第二会话仍可用，其他设备按钮仍为撤销。

### 25. CI 能覆盖哪些行为

配置可以覆盖编译错误：PR 触发 `.github/workflows/ci.yml:3`，Android job 在 `.github/workflows/ci.yml:102` 安装 SDK35，`.github/workflows/ci.yml:107` 明确执行 assembleDebug/assembleDebugAndroidTest、core/data/feature/app 单测和 lint。B1 应在这些构建任务中报错，不能因为作者没编译就绕过。API26/35 仪器矩阵在 `.github/workflows/ci.yml:170`、`.github/workflows/ci.yml:205` 调用脚本；`.github/scripts/android-test.sh:12` 跑 connectedDebugAndroidTest。

行为覆盖不足：现有 `P6UpdateUiTest.kt:35` 没传 onCheckUpdate，只测旧入口与开关；`UpdatesTest.kt:22`、`UpdatesTest.kt:47` 没调 checkNow；`P8VisualTest.kt:76`、`P8VisualTest.kt:77` 的 EditScreen fixture 没有 firstVisit，`P8VisualTest.kt:91` 的证据遍历没有历史列表页。即使编译修好、旧测试绿了，也不能证明 B2/M1–M6 的新增交互正确。截图采集 `P8VisualTest.kt:118` 不是自动人工观感审核；lint 也不能推断所有请求竞态、统计范围、边界及 TalkBack 动作质量。需要 Q24 的显式断言与实际 UI/TalkBack 走查。**只读取 CI 配置和测试代码，未读取 PR 的实时 CI 结果，未宣称 CI 已成功或实际报出了哪条诊断。**

## 证据文件索引

正文 `文件名:行号` 均对应目标 `e6a5f4091e165d5c609bd64f2259c8843e8598ef` 下列路径；工作分支来自 master，不能拿其产品源码行号代替目标行号。

| 文件名 | 仓库路径 |
| --- | --- |
| MainActivity.kt | android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt |
| Updates.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Updates.kt |
| ProductRoot.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt |
| BiteComponents.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt |
| QuietGridComponents.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt |
| DetailEditScreens.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt |
| ListScreens.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt |
| SettingsScreen.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt |
| HistoryScreens.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt |
| ProductViewModel.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt |
| Typography.kt | android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/theme/Typography.kt |
| Product.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Product.kt |
| Writes.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Writes.kt |
| CacheRules.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/CacheRules.kt |
| Repositories.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Repositories.kt |
| UpdateInstall.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/UpdateInstall.kt |
| Authentication.kt | android/core/src/main/kotlin/top/alanvampuke/dininglog/core/auth/Authentication.kt |
| ProductApi.kt | android/data/src/main/kotlin/top/alanvampuke/dininglog/data/remote/ProductApi.kt |
| AppVersionGateway.kt | android/data/src/main/kotlin/top/alanvampuke/dininglog/data/remote/AppVersionGateway.kt |
| RetrofitAccountSecurity.kt | android/data/src/main/kotlin/top/alanvampuke/dininglog/data/auth/RetrofitAccountSecurity.kt |
| AppModule.kt | android/app/src/main/kotlin/top/alanvampuke/dininglog/AppModule.kt |
| UpdatesTest.kt | android/core/src/test/kotlin/top/alanvampuke/dininglog/core/UpdatesTest.kt |
| RemoteContractTest.kt | android/data/src/test/kotlin/top/alanvampuke/dininglog/data/RemoteContractTest.kt |
| P6UpdateUiTest.kt | android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P6UpdateUiTest.kt |
| P8VisualTest.kt | android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P8VisualTest.kt |
| routes.ts | backend/src/auth/routes.ts |
| session.ts | backend/src/auth/session.ts |
| queries.ts | backend/src/domain/queries.ts |
| schemas.ts | backend/src/domain/schemas.ts |
| openapi.yaml | contracts/openapi.yaml |

## 我验证了什么 / 我没能验证什么

**我验证了什么（源码/静态证据）：**

- 完整读取 `design/PR16_REVIEW_TASK.md`，以及全部 8 个目标版本的改动文件：MainActivity 142 行、Updates 67 行、ProductRoot 225 行、BiteComponents 202 行、QuietGridComponents 253 行、DetailEditScreens 209 行、ListScreens 253 行、SettingsScreen 135 行；不是仅看 diff。
- 读取完整 diff，逐一核对 import、签名、构造参数生命周期、Composable/DrawScope/副作用上下文；沿 ViewModel、请求序列化、在线 SQL、离线过滤、注册默认值、指定会话吊销 SQL、账号清理、CI 配置和相关现有测试追踪证据。
- 源码比较确认 RatingEditor、formatAbsoluteTime helpers、HistoryScreens 全文相对 master 未变；核对所有 25 问均有目标文件行号。没有测试文件改动，测试目录未出现新增交互符号。
- 用 git ls-remote 确认远端 master/pr14-rebased 与上述固定评审提交一致。并发顺序、折叠错误操作路径、21 家分页示例是基于源码构造的可达反例，**不是运行记录**。

**我没能验证什么（未运行/未取得证据）：**

- 未编译、未运行 Kotlin/Compose 编译器、Gradle/npm/docker、单测、仪器测试、lint；B1 是基于 Kotlin 作用域规则的静态确定缺陷，没有伪造编译器日志。
- 未运行应用、网络请求、旋转/离页、跨时区/午夜、多设备退出、快速双击、TalkBack 或大字体布局；未查看本批实际截图。布局换行/跳动、触摸目标冲突与辅助技术体验均标为需要实测，未冒充已经观察到的现象。
- 未查询实时 CI run 结果；只解释仓库配置和已有断言的能力。功能分析仅在 B1 修复后成立；当前目标代码不能因这些静态路径看似可达而被视为可运行。
