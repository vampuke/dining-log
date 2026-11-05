# PR17 实施记录

唯一设计输入：[PLAN.md](PLAN.md)，基线 `f371a9b8962814d3594bb258e29998d024542783`。实施分支 `feat/pr17-density-and-sliding-sessions`；PR 标题 `density pass + sliding 180-day sessions`。组件和页面布局 → Android 会话删除及调用迁移 → 后端滑动及两个测试 → 基线/哈希 → 静态验收，均按第 10 节顺序实施，合并为一次实现提交。

## 实施与最终裁决

- 第 1 节：PageHeader 默认空 trailing slot；无 slot 时保持原结构。抽取 RatingMeter；RatingEditor 增加 framed/label，已评分加减清除按 280dp 和 fontScale 分支排布。Sticker 默认无限行；HistoryTimeText 仅 fontScale>1.3 时无限行/Clip。评分空轨道、显式设置 40、SetProgress、0.1 步进均保留。
- 第 2 节 A1–A9/A5b、D1/D2/N3：详情刷新进入页头；正常在线不建立空状态 item；小票眉题带唯一版本信息，弹性标题和突出评分、单个独立 meter、FlowRow 元信息；原非空字段顺序和 displayValue tags 保留；操作组、到店标题/最近日期、历史标题操作响应式排列；时间轴与条码收尾拆为两项，并增加三段测量 tags；编辑纵距 12dp、评分保留外卡去内框、已保存餐厅子操作响应式排列。
- 第 3 节 B1/B2/N6/G2/G3：列表卡 padding 7dp、纵距 3dp、左右 gap 8dp、评分纵距 3dp，列表 item 7dp；首页保留品牌与原矢量，其他列表新增进入 PageHeader；筛选 Checkbox 内容宽 320dp、两个评分编辑器内容宽 492dp 才横排，fontScale>1.3 均回退。最低/最高评分使用独立 label 和 framed=false。
- 第 4 节 E1–E6/N5/N7：历史详情纵距 12dp，刷新入页头并增加 pr17 tag；时间与版本响应式排列；diff 原/新值竖排配对，各标签到值 2dp、对间 8dp，删除仅 diff 卡内 divider；历史说明/隐藏按钮对、恢复和重新应用流程保留；比较和重新应用预览缩短配对间距，保留横滚、全文展开与各值 tags。
- 第 5 节 C1/C2/N1/N2：启动检查开关组和手动检查响应式同排；logout 通栏且原确认文案保留；下载阶段与大小 FlowRow，取消独立；更新弹窗次操作内容宽 240dp 同排且底部对齐，保留窗口尺寸、滚动主体、16dp 底部余量和阶段回调。
- 第 6 节：SettingsScreen 删除账号/会话刷新、会话列表和撤销确认；SettingsUiState、VM、ProductRoot、AccountSecurity、Retrofit SecurityApi 及实现同步移除会话查询/撤销。loadSettings 仍在进入设置及改密码后加载 profile。所有 fake 和调用同步迁移。仅删除授权的 PR16 会话 UI 用例，其余 PR16 用例原样保留。P6 等待 profile 非空并滚到 logout，原四次重选、导航 entry、profile/refresh 计数断言保留。E2E 使用当前拥有者 RefreshCoordinator token、单个 UUID Idempotency-Key 和 OkHttp DELETE，Response 关闭并断言 204；继续断言被撤销会话 access 401、refresh SESSION_REVOKED、当前 profile 可用，以及原改密/logout/Room 清缓存/再次登录。后端 sessions API 原样保留。
- 第 7 节：SESSION_TTL_SECONDS=15552000；issueSession 和成功 rotation 使用同一 180 天期限。rotation 的 session 更新时间和期限、token CAS、新 token、加密重放结果及两组 guard 保持在同一 batch。120 秒 retry 期限与 session 期限分别命名；同 request-id 重放不续期。保留 600 秒 access、7 天 refresh 空闲上限、过期/撤销/重用拒绝逻辑。
- 两个新增 auth 测试：只 fake Date 并在 finally 恢复真实时钟；6 天刷新步长循环到第 186 天，独立字面量验证 DB、响应、refresh 期限、session id 和 60 秒后字节一致重放，也检查重用/撤销不移动期限；三个独立 session 隔离 7 天 token 守卫，验证 180 天前 1 秒、恰好期限和超过 1 秒，使用新签 access，检查失败无消费、无子 token、无重放结果。
- 第 8 节：p7 仅删除 revoke 插值项，不重写其他数组/字段/protectedHashes；p8 仅刷新 session.ts、rotation.ts、auth.test.ts 三个 SHA-256。

对助手初稿的裁决采用 PLAN 的最终结果：B1/B2 使用明确 7/3/8dp；G1 不做，默认 BitePanel 14dp/8dp 保持；E4 采用变体 B 的竖排配对，长值保留全文展开；F1/F2 保持原认证页入口和密码显示文字操作，避免扩大输入框接口范围；G2/G3 使用父内容宽度 492/320dp 和字体分支，避免手机窄列；删除 Android 会话管理但保留后端撤销能力；180 天从最近成功刷新计算，7 天 token 空闲上限仍执行，不能声称 180 天不打开 App 仍可自动续期。

主题 token、字体/行高、描边/硬投影、矢量、FormField 最小行数、IME/insets/浮动栏余量、SaveBar、密码内存/安全标志/未知结果重试、ReviewDialog 和 OperationDialog 未扩大修改；未改迁移、contracts、锁文件、生产 JWT 验证选项、视觉守卫脚本或 DISCUSSION/PLAN。

## 真实验收状态

| 验收 | 结果/证据 |
| --- | --- |
| `python3 -B android/tools/verify_bite_visuals.py` | 本机 exit 0；全部对比度/原生颜色/矢量/tag/保护路径和哈希检查通过。 |
| `python3 -B android/tools/verify_p6_visuals.py` | 本机 exit 0。两份脚本与基线逐字未变。 |
| `git diff --check` | 本机 exit 0。 |
| 基线到实现的源码 tag 集合 | 静态比对通过：仅删除 `"revoke:${session.id}"`；生产新增恰为 `pr17:receipt-head`、`pr17:detail-history-panel`、`pr17:receipt-footer`、`pr17:history-refresh`。 |
| p7 最小退役、p8 三文件哈希 | 静态 JSON/哈希比对通过；p7 其余字段相同，p8 其余路径和值相同。 |
| Android 全仓库会话调用复查 | `rg` 未发现废弃 overrides、settings.sessions、vm::revokeSession、security.sessions 或会话 UI 操作；所有 SettingsScreen 调用保留 onPassword/onLogout 等必要回调。 |
| 原 auth 测试保留 | 静态比对：移除新增段后整个 auth.test.ts 与基线完全一致；恰好新增两个 it，原 JWT owner/version and refresh idle/absolute expiration 用例未改。 |
| 源码语法检查 | 使用临时目录中的 tree-sitter 做静态解析；修改的页面/组件和新增 Pr17UiTest 无语法错误；其他文件仅保留基线解析器已有的不支持语法。此项不等同 Kotlin/TypeScript 编译或测试通过。 |
| backend `npm run check`、`npm test` | **待 CI**；遵守用户要求，本机没有运行 npm，包括安装、check/test。 |
| PLAN 指定 Gradle assemble/test/lint 任务 | **待 CI**；本机没有运行 Gradle。现有 ci.yml 覆盖构建和业务验证。 |
| API 26/35 connectedDebugAndroidTest | **待 CI**；现有 CI suite 会包含新增 Pr17UiTest 及所有保留原用例。 |
| 实测收益、全文无 overflow、目测 | **待 CI 产物与审阅**；没有以代码推算宣布高度、首屏收益、截图或动态验证通过。 |

## 新增 UI 验收矩阵与待采集证据

`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt` 包含 11 个交互/结构用例，使用真实组件、回调、语义和 TextLayoutResult；没有为 7dp/3dp 编写实现镜像测试。

| 矩阵 | 新用例与证据范围 |
| --- | --- |
| 字体、主题、内容宽度 | fontScale 1/1.3/1.31/2，深浅主题，360×640、320dp、640×360 和宽内容容器；279/280 页面操作/标题边界、319/320 Checkbox、491/492 评分编辑器、239/240 Dialog 次操作。边界容器使用整数像素密度消除 dp 舍入；Dialog 从实测窗口宽度校准，使用其实际内容宽度。 |
| 详情/编辑 | 三实体、长标题/地址、displayValue 原值、评分/未评分、版本/修订/日期、缺字段计数、四种菜品 count/preview 组合、空/有预览、null 子操作、deleted/restore、missing/offline/stale/error/refreshing；操作回调与取消确认无副作用；编辑外评分卡和子操作。截图 `pr17-detail-*`、`pr17-edit-rating-one-frame-*`。 |
| A7 | 以原 DE 小票尾卡的测试 fixture 和相同内容宽、密度、字体、主题测旧卡；测新 history/footer 两卡加 12dp 的合计并断言普通字体不增长。输出 `pr17-receipt-tail-before/after-*` 和 A7 高度记录。此 fixture 仅重现旧尾卡，不替代整页基线截图。 |
| 评分/筛选 | null 触摸不赋值、设置 40、加减 0.1、清除、10/50、每组 SetProgress、min/max 不串值、unrated/range 互斥与 fallback；输出有两个 framed=false 编辑器的 `pr17-filter-*-with-editors-*`。 |
| 历史/预览 | null/短/长值、240/241 边界、展开/收起、时间切换与 font200 布局、隐藏/取消/恢复/reapply 回调，状态/禁用；apply 当前/拟值、必填不能空、两侧选择、保存禁用、比较横滚。输出 `pr17-history-*`、`pr17-apply-pairs-*`、`pr17-comparison-*`。 |
| 设置 | profile fixture、不同 displayName、无会话/撤销/刷新账号入口；主题、开关、手动检查、checking 禁用、失败/最新/新版本、改密可达、logout 取消/确认；真实加载和清缓存继续由迁移后的 P6/E2E 验证。输出 `pr17-settings-tail-*`。 |
| 更新 | IDLE、DOWNLOADING 已知/未知 percent/total、VERIFYING、READY、FAILED verified true/false、CANCELED、NEED_PERMISSION、INSTALLING；长版本/notes、message 有/无、各阶段 tags 和回调，取消与后台继续，内部滚动和关闭按钮下方 16dp。原 P8 短内容无需滚动与 16dp 断言未改。输出 `pr17-update-stage-*` 与 Dialog 边界测量。 |
| 列表/横屏/尾项 | 各实体列表和搜索窄容器，品牌/普通页头/新增可达，字体与主题、横屏内容 viewport、最后项位于浮动栏上方；记录首屏完整卡数。实际横屏方向、API26/35 系统 insets 和 IME 保存仍由原 P8/P6SmallScreen/E2E 等覆盖。输出 `pr17-list-*`、`pr17-search-narrow-*`。 |

截图与 TextLayoutResult/容器测量由 CI 运行产生，写入现有采集目录 `p4-screenshots/`，布局日志名 `pr17-layout-api26.txt` / `pr17-layout-api35.txt`；现有 `.github/scripts/android-test.sh` 在 instrumentation 运行中持续复制并由 workflow 上传，无需改变 CI 或本机伪造图片。

以下为初次实施时的待采集清单；**M1 五类列表同条件基线/实现比较与 M2 Apply 实际窗口字体矩阵已在文末 Review round 关闭并归档**。其他未在 Review round 列为完成的证据仍待审阅：上述 `pr17-*.png`/布局日志；基线 commit 的同数据、同密度、同主题、同字体整页截图；A7 对照实测结果；首页/搜索/店内/到店/历史列表改前后完整卡数对比；font200 展开全文及窄容器 overflow 记录；深浅主题目测、票边连续/条码裁切检查；长页面/真实横屏/IME 与 API26/35 系统导航余量；保留 E2E 的清缓存与重新登录、原首访/离开编辑/IME 证据；backend 和 Gradle 的 CI 报告。暂不在 evidence/ 放置占位截图或冒充测量结果。

本机禁跑 Gradle/npm、证据由 CI 提供的限制仍保留。宽内容测试为逻辑宽度 fixture，不能替代真实设备横屏和目测；M1 首屏收益与 M2 Apply 实际字体由 Review round 的真实 CI 产物验证，其他验收不得由本机静态守卫替代。

## 改动文件

- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P6UiTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P6UpdateUiTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P8VisualTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr16UiTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductVisualTest.kt`
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt`
- `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Repositories.kt`
- `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/auth/RetrofitAccountSecurity.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/HistoryTimeText.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt`
- `android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ProductViewModelTest.kt`
- `android/tools/p7_baseline.json`
- `android/tools/p8_protected_hashes.json`
- `backend/src/auth/rotation.ts`
- `backend/src/auth/session.ts`
- `backend/tests/auth/auth.test.ts`
- `design/pr17-discussion/NOTES.md`

## Round 1 修复

任务输入：[PR17_CIFIX1_TASK.md](../PR17_CIFIX1_TASK.md)。在原分支 `feat/pr17-density-and-sliding-sessions` 修复；本轮只有一个修复提交，消息为 `fix: PR17 instrumentation round 1 (dialog density, font200 title squeeze, touch target)`。本机没有运行 Gradle/npm，动态验收与修复后截图由推送后的 CI 提供。

### API26 日志与失败集合

先读取 `~/.git-credentials` 的 GitHub token，在内存中用于 API 请求，没有输出或写入仓库。查询 run `41879322769` 的 `head_sha=35b74a1ed68335b2e8c26fd091b516f24814ab36`，再查询该 commit 的 **check-runs API**，从 API26 check 的 `details_url` 确认 job id **126747431004**，下载原始日志到 `/tmp/pr17_i26.log`（229019 bytes）。[API26 job](https://github.com/vampuke/dining-log/actions/runs/41879322769/job/126747431004)；[API35 job](https://github.com/vampuke/dining-log/actions/runs/41879322769/job/126747431138)。

逐个解析并比较两份日志：两边均 **11 个同名测试失败**，API26 没有额外失败。pageActions 测试在 API26 先报 `‹ 返回`，API35 先报小票品牌行；旧 ProductVisualTest 的 detail title 在 API26 为 `312 x 92`，API35 为 `312 x 112`，均报告 widthOverflow。其余首个断言文本一致。结构化核对结果留在 `/tmp/pr17_failure_comparison.json`，job 元信息留在 `/tmp/pr17_i26_job.json`。

### 五项诊断逐条复核与裁决

1. **Dialog fontScale：Apply 诊断成立，但不能推广为所有 Dialog 都是 1.0。** 从 [BOM 2025.05.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.05.01/compose-bom-2025.05.01.pom) 核对到 Compose UI/Foundation/Test **1.8.2**、Material3 **1.3.2**，读取对应 Google Maven sources。`AndroidDialog.android.kt:175` 的 Dialog 创建独立 owner；`ProvideCommonCompositionLocals` 再提供 `LocalDensity provides owner.density`；`AndroidComposeView` 的 density 来自 window context 的 resources。宿主的 CompositionLocalProvider 位于这次重新提供之前。`ReviewDialog`/`ApplyFieldsDialog`、PasswordDialog、AlertDialog 没有在窗口内容中再次提供宿主 density，API 的 `expected 2.0 / actual 1.0` 与此一致。**UpdateDialog 已在内部显式提供宿主 density**，旧 artifact 的 dialog-pair 记录也显示 1/1.3/1.31/2 四档边界确实改变，不能把 update-stage 的失败归因于相同的 scale 丢失。
   - `Pr17UiTest.assertTextFits` 通过 `hasAnyAncestor(isDialog())` 识别窗口节点；全部 dialog 内目标用其自身 `layoutInput.density` 测量并严格断言溢出，跳过与宿主 scale 的一致性检查。宿主节点仍严格核对配置的 scale。Apply 外的 ComparisonColumns 在宿主渲染，仍执行宿主检查。设置页增加打开密码、退出确认期间的文本扫描；Update 的现有扫描自动采用节点实际 scale。证据同时记录 window、requestedFont、实际 font/density。这是测试覆盖人工 Density 的限制，不能据此报告产品系统字体缺陷，也不能宣称 Apply/Password 已覆盖人工 200% 窗口字体。
2. **标题约束的精确新旧差异成立；“312px 就是被挤后的约束宽、已证实真裁字”不成立。** 读取 `35b74a1^` 和当前 PageHeader：旧结构为 `Row { back; GridText(weight(1f)) }`，标题本身收到剩余宽度的紧约束；PR17 使用 trailing 时变为 `ResponsiveHeading -> Row { back; Column(weight(1f)) { GridText(title) } }`，Column 内的标题收到 `minWidth=0` 的松约束，可以只报告文字自然宽。font200 回退把 trailing 移到下行，但仍让 back 占标题行宽。`headlineSmall` 实际为 **18sp**，200% 为 36sp，不能把 `TextLayoutResult.size.width=312` 直接当作 Column 的 maxWidth；第 3 项的语义重建也会制造 widthOverflow。
   - 对有 trailing 且 fontScale>1.3 的 PageHeader，返回按钮移到标题上行；标题 `fillMaxWidth` 独占内容宽，subtitle 保留 4dp 间距，trailing 仍在下一行。普通字体分支和未使用 slot 的结构保持。新增 1.31/2 倍、279/280dp 内容宽下的返回/标题分行与标题整行宽断言，采集 `pr17-header-full-title-*-font*-*-api*.png`。**ProductVisualTest 原 font200 hasVisualOverflow 断言完全保留**，仅在换页后、截图前增加 waitForIdle；修复后是否绿与是否可读仍以 CI/截图验收。
3. **短文本误报有源码可证的原因，不能照抄 stale density 缓存结论。** Foundation 1.8.2 `ParagraphLayoutCache.slowCreateTextLayoutResultOrNull` 用 `prevConstraints.copyMaxDimensions()` 直接构造 MultiParagraph，却配上原 Paragraph 的 `layoutSize`；原 Paragraph 通过 `LayoutUtils.finalMaxWidth` 收敛到 intrinsic width，而 MultiParagraph 的 `width` 直接等于传入 maxWidth。`TextLayoutResult.didOverflowWidth` 比较 `size.width < multiParagraph.width`，所以松约束下自然宽短文本也会失败。`TextStringSimpleNode.applySemantics` 保存的是读取当前 layoutCache 的函数，并非固定保存上一次 TextLayoutResult；没有源码或原失败日志证实“key 重建沿用上一 fontScale”。settings、receipt、editor、history、detail 的相应扫描时已关闭确认窗口或没有窗口，不能全归为 Dialog。
   - 在**实际已测得的文字框**内，使用相同 text/style/placeholders、font resolver、density、layoutDirection、softWrap、maxLines、overflow，通过 `TextMeasurer(cacheSize=0, skipCache=true)` 得到一致的 TextLayoutResult，仍断言 `hasVisualOverflow == false`，不改 overflow 的定义、容差或文案。证据在断言前记录原约束、原尺寸/overflow 与重测后的宽高 overflow，后续若仍失败可直接定位真实裁切。
   - `configure`、文本取样与截图先 `waitForIdle()`，再 `runBlocking { awaitIdle() }`；已核对 1.8.2 的 AndroidComposeTestRule 提供 awaitIdle，且等待包含下一绘制帧。全页扫描只处理 `isDisplayed()` 的未合并文字，长字段原有滚动/展开后的直接检查保留；宿主 scale 不一致仍失败。新增 `measuredTextLayoutsStillRejectRealWidthHeightAndMaxLinesOverflow`，同时验证松约束短文本通过，以及 nowrap 窄框、过低文字框、超出 maxLines 的文字仍报告对应 overflow。该负例测试待 CI 执行，不能用静态解析替代通过记录。
4. **48dp 失败成立，“删 top padding 导致 Row 塌高”不是精确根因。** Material3 1.3.2 Checkbox 的 modifier 顺序是调用方 modifier → minimumInteractiveComponentSize → triStateToggleable → 2dp padding → 居中的 20dp Canvas；默认 48dp 余量在 toggleable 外侧。`SemanticsNode.findCoordinatorToGetBounds` 选择 `outerMergingSemantics`，即 toggleable 对应的坐标，因此 tag 查询的 `boundsInRoot` 可以是内部约 **24dp**，不能由外部行高推断触达语义已满足 48dp。
   - `filter-unrated` 和 `filter-rating-range` 两个 Checkbox 本体增加 `heightIn(min=48.dp)`，tag 留在 Checkbox，内部 toggleable 获得最小高度约束；共享的 lambdas 同时覆盖横排与竖排/横屏。回调、320dp 分支和原 `>=47.5dp` 断言保持，assertPair 在断言前追加实际 bounds/heightDp 证据。
5. **API26 核对已先执行。** check-runs 找到的 job、原始下载、11 项集合逐项对比见本节开头；没有用 API35 推测 API26，没有额外 API26 失败需要另加修复。

### 每项原失败的根因、修复与证据

下面是原 run 两套 API 的完整失败集；测试名省略所属类前缀，最后一项属于 ProductVisualTest，其余属于 Pr17UiTest。每项修复后动态结果均待新 CI。

| 测试 | 根因复核结论与修复 | 原证据与修复后采集点 |
| --- | --- | --- |
| settingsProfileUpdatePasswordAndLogoutRemainReachableWithoutSessionManagement | `owner` 是宿主 subtitle 的松约束语义重建误报，不是未关闭 Dialog；按实际文字框重测，另增加设置窗口文字扫描及自身 scale 处理。 | 双 API `overflow at font=1.0: owner`；新 text window/constraints/overflow 记录及 settings-tail。 |
| receiptTailIsMeasuredAgainstTheOriginalCardWithMatchingData | 品牌行没有固定铺满父宽，简单 Text 的语义重建误报；可见文字按实际框重测，A7 原高度断言保留。 | 双 API 品牌行 overflow；API35 原 A7 `old=213.71428, new=191.80952`；新 A7/text/receipt-tail-before/after。 |
| applyPreviewAndComparisonColumnsKeepTheirOwnValuesAndScroll | ReviewDialog 的独立 owner 提供系统 scale；dialog 使用自身 scale，宿主 comparison 仍核对请求 scale，全文 overflow 仍检查。 | 双 API `expected 2.0 / actual 1.0`；新 apply 的 dialog/host 密度及 apply-pairs/comparison。 |
| editorKeepsTheOuterRatingCardAndSavedRestaurantChildActions | `评分 · 选填` 是松约束 GridText 的语义重建误报；实际框重测，原外评分卡/按钮回调矩阵保留。 | 双 API 对应 label overflow；新 text/编辑评分截图。 |
| historyPairsFullTextTimeAndConfirmationCallbacksRemainReachable | `评价` 是松约束字段标签的语义重建误报；重测，原标题/全文展开、时间切换及确认回调断言保留。 | 双 API `评价` overflow；新 text/history-actions。 |
| detailEntityStateMatrixPreservesValuesMetadataAndAllCallbacks | 返回按钮内部 Text 的语义宽与实际文字宽不一致；重测，并修正大字体页头返回/标题排布。 | 双 API `‹ 返回` overflow；新 text/detail-head/header-full-title。 |
| ratingEditorNullSteppersBoundariesAndFilterControlsDoNotCrossValues | Checkbox 48dp 外部余量不保证内部 toggleable 的语义 bounds；两本体约束最小 48dp。 | 双 API filter-unrated 48dp 断言；新 touch-target bounds，原 319/320、491/492 与独立数值矩阵。 |
| pageActionsAndHeadersUseContentWidthBoundaryAndSingleCallbackFillsTheRow | API26 返回文本、API35 品牌文本均涉及松约束语义重建；重测并增加大字体页头整行布局验证。 | 原两 API 不同首个文本；新 page-pair/header/text/header-full-title，原 279/280 与单回调通栏断言。 |
| updateStageMatrixPreservesCallbacksProgressAndScrollableBottomPadding | UpdateDialog 明确继承宿主 scale，短 `更新说明` 的误报来自语义宽重建；自身 scale 重测与 idle，原阶段回调/16dp 底部断言保留。 | 双 API `更新说明` overflow；原 dialog-pair 四档字体证实覆盖；新 text/update-stage。 |
| listKindsBrandHeadersAndLandscapeRemainReachableWithAllFontsAndThemes | `店内菜品` 在新 Column(weight) 内按自然宽布局，却被语义 MP 用父 maxWidth 重建；重测，大字体 PageHeader 标题整行。 | 双 API 对应标题 overflow；新 text/list 全字体、主题、横屏截图。 |
| bothTabsAndPageTitlesRemainUnclippedAt200PercentInBothThemes | 旧/新紧松约束差异触发 raw widthOverflow，原记录不能证明 312px 就是压缩后的约束；产品落实返回/标题分行、标题全宽。 | API26 `312x92`、API35 `312x112`；旧 API35 `light-detail-font200-tabs` 截图实际仍显示首页，原截图本身有换页绘制时序问题；新增截图前 idle，新两主题各页 font200 截图及**原 raw overflow 断言**。 |

### 本轮本机验证与交付范围

- 两个未修改的视觉守卫 `python3 -B android/tools/verify_bite_visuals.py`、`python3 -B android/tools/verify_p6_visuals.py` 均 exit 0，保留 tags/矢量/配色/后端与 contracts 保护检查通过。
- 四个修改的 Kotlin 文件用 `/tmp/pr17-syntax-parser` 的 tree-sitter 做静态解析，均无语法错误；`git diff --check` 通过。静态解析不是 Kotlin 编译或 instrumentation 通过记录。
- **没有本机 Gradle/npm 执行，没有伪造修复后截图或宣称四个 CI job 已绿**。原 ProductVisualTest、48dp 容差与更新 footer 的 16dp 断言保留；新增负例和截图随原 instrumentation suite 运行。新 CI 由助手监控，本轮交付为修复并推送。
- 本轮仅改 `Pr17UiTest.kt`、`ProductVisualTest.kt`、`QuietGridComponents.kt`、`ListScreens.kt`、本 NOTES；没有改依赖/BOM、生产 Dialog、主题字号、守卫脚本/基线、PLAN/DISCUSSION 或业务会话代码。

## Round 2 修复：筛选边界轮次与 bounds 语义

### 源码与 CI 复核

- 独立查询 [CI run 41886927903](https://github.com/vampuke/dining-log/actions/runs/41886927903)，确认 HEAD 为 `9d1b2fd247d8e7f1148727543fa20048ee63e905`，backend/android 成功，API26/35 instrumentation 失败。两份 job 原日志（`/tmp/pr17_r2_i26.log`、`/tmp/pr17_r2_i35.log`）均为 55 项中仅 1 项失败；两套 HTML 的失败栈均指向 `ratingEditorNullSteppersBoundariesAndFilterControlsDoNotCrossValues` 原 `Pr17UiTest.kt:401`。API35 HTML 的 apply/detail/editor/history/update 等原 Round 1 失败用例均 passed。
- API35 artifact `13352029636`（`/tmp/pr17_r2_art35/`）及独立下载的 API26 artifact `13351569695`（所需 HTML/布局日志提取到 `/tmp/pr17_r2_review26/`）都只有 font=1.0 的 content=319/320/491 轮末记录，没有 492 轮末记录。491 轮两 Slider 的语义 x 区间均为 `[4, 515]`，确认该轮竖排及同列断言通过；缺少 492 记录只证明它在记录前失败，**不能据此断定没有触发横排**。
- `ListFilterPanel` 的宽度链是测试 viewport/滚动 Column → `fillMaxWidth().brutalBox(...).padding(14.dp)` → G2/G3 `BoxWithConstraints(fillMaxWidth)` → 铺满内容宽的 Row/Column → `RatingEditor(framed=false)`。`brutalBox` 仅 `drawBehind`，描边 2.5dp 和阴影不参与测量；`Arrangement.spacedBy` 只影响相应子项间距。测试 `exactPixels=true` 使用 density=1，原 492 轮 viewport=520px，按该源码约束内容宽为 `520-14-14=492px`，没有发现会吞掉 1px 的布局 modifier。旧 CI 没有直接记录 BoxWithConstraints 的 maxWidth，故这个 492 值是源码推导，不冒充旧 CI 的实测。
- **已证实的根因是 Slider 语义 bounds 被用于布局分列断言。** BOM 对应 Material3 1.3.2 `Slider.sliderSemantics` 追加 `IncreaseHorizontalSemanticsBounds`；其 `internal/AccessibilityUtil.kt` 的 `HorizontalSemanticsBoundsPadding=10.dp`，通过负 padding 保持布局宽度，同时左右各扩大语义框 10dp。UI 1.8.2 `SemanticsNode.findCoordinatorToGetBounds` 选择这个 merging semantics coordinator。491 轮真实布局应为 `[14,505]`，语义框恰为 CI 的 `[4,515]`，外扩全部对上，并非绘制溢出或额外 padding。原 492 轮正常两列布局应为 `[14,254]` / `[266,506]`，语义框变成 `[4,264]` / `[256,516]`，原 `max.left > min.right` 即 `256 > 264` 必然失败；12dp 列间距被两侧合计 20dp 的语义扩张覆盖 8dp。仅把 492 换成 496 仍无法修复这个坐标语义错误。

### 修复与证据记录方法

- 仅在测试中将筛选内容轮次改为 `316/324/488/496`：G3 的 320dp 阈值两侧及 G2 的 492dp 阈值两侧各留 4dp；四档字体、深浅主题、阈值条件和全部数值/回调/互斥断言保留。
- G2 使用 Slider `SemanticsNode.layoutInfo.coordinates` 的实际内层布局位置与尺寸构造 Rect，避开语义扩张和滚动裁剪；两个编辑器在同一滚动偏移下取样。仍严格断言横排 `max.left > min.right`，竖排仍核对同列及原 1px 容差；两分支补 font/theme/content/min/max 消息。原始语义 bounds 另存，便于与旧 CI 对照，未用硬编码减去 10dp 的方式修剪语义框。
- **不改生产代码取得实际 maxWidth：** 从 G3 两 Checkbox、G2 两 Slider 的 `layoutInfo.parentInfo` 链分别查找最近公共布局祖先。当前生产结构中它就是对应 BoxWithConstraints 直接子级的 `fillMaxWidth` Row/Column；两分支均铺满约束宽，因此其实际测得的 `LayoutInfo.width / LayoutInfo.density.density` 等于该 BoxWithConstraints 的 maxWidth。此取法不依赖测试传入的 `content+28`，也不把单列 weight 后的 Slider 宽或扩张后的语义框当作父宽。G3 在 `assertPair` 前、G2 在分列断言前分别 `recordEvidence("filter-constraints group=G3/G2 ...")`，输出 requested content、实际 maxWidthDp/widthPx/density、字体、主题及相关 bounds；G2 同时输出布局和语义两套 Rect。将来若断言失败，该轮宽度与坐标已经落盘。
- 原 :303/:304 的标题/刷新断言、:409 的 `filters.unrated` 断言补上下文消息；全文件剩余裸 `assertTrue(saved > 0)` 也只补保存回调消息，不改条件。原 :401 分列断言保留且补消息。

### 验证与交付范围

- 本轮只改 `Pr17UiTest.kt` 与本 NOTES。Round 1 的生产布局修复、Checkbox `heightIn(min=48.dp)`、`>=47.5dp` 触达断言、Dialog scale 处理、文本重测和等待机制原样保留。
- 本机 Kotlin tree-sitter 静态解析无错误；原两个 Python 视觉守卫均通过；`git diff --check` 通过。本机没有运行 Gradle/npm；静态检查不能替代编译和 instrumentation 结果。
- 本轮交付为一次修复提交推送到原分支；新 CI 由助手监控，修复后实测 maxWidth、截图与动态结果待新 CI 产物。若 content=496、普通字体仍实际竖排，应在 NOTES 报告产品分支问题并停止进一步猜测，不继续改生产布局。

## Review round

本轮对应 [PR17_REVIEWFIX_TASK.md](../PR17_REVIEWFIX_TASK.md) 和评审 M1/M2/N2/N3。只修改 instrumentation 测试和证据/记录，不改生产布局、视觉守卫或 REVIEW.md，不放宽既有 overflow、触达、尾项/浮动栏和业务断言。

### M1：已完成

- 完整卡矩形改为 `SemanticsNode.positionInRoot + size`，列表/宿主 clipped viewport 取交集并截到 `floating-tabs.top`。枚举全部实际可见 `record:` 卡，四边完整落在区域内才计数；原全字体/主题/横屏列表用例复用此测量，尾项可达断言保留。
- 新增可原样注入基线的 `Pr17ListFirstScreenTest.kt`。真实 checkout `eaa8e42` 的 API26/35 两基线 job 各 1/0 通过，来源 [run 41941966360](https://github.com/vampuke/dining-log/actions/runs/41941966360)。真实 `5f676f8` 的最终采集来源 [run 41943522685](https://github.com/vampuke/dining-log/actions/runs/41943522685)，M1 以 `BASELINE_MODE=false` 实际断言 `after >= baseline`。
- 同 20 行 fixture、360×640dp、Pixel 2 / 1080×1920 / 420dpi / density 2.625、普通字体 1.0、两主题，五类列表在每 API 的 before/after 都相等：API26 餐厅/搜索/店内/到店/历史 **4/4/4/6/2 → 4/4/4/6/2**；API35 **2/3/3/4/2 → 2/3/3/4/2**。两个 API 使用各自系统字体/insets 基线，未用代码推算数字、未宣称卡数增加。
- 常量断言注释包含来源 eaa8e42、run 和实际数字；[M1-list-firstscreen.md](evidence/M1-list-firstscreen.md) 归档完整比较表、40 张首屏图片、四份卡矩形/可见区域/完整卡 ID 日志、完整生产 SHA 和设备参数，均纳入 git。基线 API35 餐厅 row-3 越过 floating-tabs.top，不计入完整；API26 到店计到第六张，覆盖原前三张以外的卡。

### M2：已完成

- Apply 每轮使用 instrumentation shell 设置系统 `font_scale` 并 recreate Activity，重新安装测试 fixture；产品 Dialog 继承方式原样不动。Activity resources 与每个被扫描的 Dialog TextLayoutResult 均断言 requested == actual。try/finally 恢复并校验系统/Activity fontScale=1.0。
- font **1/1.3/1.31/2 × light/dark × API26/35** 共 16 轮全部通过，实际字体逐轮等于请求字体。逐段检查 name/address/review 当前/拟修改值、必填空值禁用、选择新旧侧与字段、长地址展开全文和逐屏滚至末端、保存/返回 footer 可见及各回调；Comparison 原全文展开和横滚断言也扩展到完整矩阵。保留原自身 density 重测 overflow 检查，两个 API 均无重测 overflow。
- 真实窗口 width=1016px、density=2.625；API26 bounds=(32,32,1048,1496)，API35=(32,32,1048,1595)。不把人工宿主 320dp 当弹窗实际宽度。截图统一 `pr17-apply-*-actualFont<实际值>-<主题>-api*.png`，manifest 和布局日志同时记录 requested/actual。
- [M2-apply-window-font.md](evidence/M2-apply-window-font.md)、[M2-apply-manifest.json](evidence/M2-apply-manifest.json)、两 API 原始布局日志和全部可回收 Apply 图归档。16 张 footer 图与 36 张展开地址分段图都来自最终成功 run；一张 API35/font1.31/dark 的中途 review-current 图由同生产 SHA 的初次采集补回，manifest 单独标出来源，不将被取消 run 当作通过记录。
- 采集测试清理时曾重建空 Activity 后 awaitIdle，等待不到 Compose root。修复为仅安装下一轮内容后等待绘制，清理阶段只恢复/校验字体；初次实现采集被取消，最终通过依据为修复后的 run，不隐藏该失败路径。

### N2 / N3

- **N2 已修复**：直连 DELETE 非 204 时先读/解析 auth error，`TOKEN_EXPIRED` 抛 `AuthFailure`，交给外层 authenticated 用同一个闭包外 UUID Idempotency-Key 刷新并重试；最终仍严格断言 204。other 会话先 refresh 保持同 session id，并证明将用于撤销检查的 access 在 DELETE 前 profile=200；DELETE 后断言 401 且错误码 `SESSION_REVOKED`，refresh 的 `SESSION_REVOKED`、own profile、改密/logout/Room 清理/再登录断言保留。修改后的 E2E 文件已随本轮 CI 编译；完整 E2E 运行由推送后的全套 CI 验证，本轮定向采集没有冒充全套业务通过。
- **N3 已修复并在两 API 验证**：每轮点击前记录 beforeSaved，在 runOnIdle 严格断言 `saved == beforeSaved + 1`，消息包含请求/实际字体和主题；不再用累计 saved>0 证明后续轮次调用成功。

### N4：本轮不做

N4 的校准后精确内容宽 319/320、491/492 属补充增强建议，不是本轮 M1/M2 关闭条件；任务书明确要求本轮不做。保留既有 316/324、488/496 稳定轮次、实际布局坐标测量、生产 `>=` 阈值和既有断言，精确边界增强留待后续，不扩大本轮范围。N1 的任务书基线笔误按本轮要求不修改记录文件。

### 验证和交付

- 最终定向采集 [run 41943522685](https://github.com/vampuke/dining-log/actions/runs/41943522685)：API26/35 均 **2/0**。独立解析两套 HTML：M1 分别 passed (5.198s)/(7.623s)，Apply 分别 passed (1m53.35s)/(2m0.12s)；每 API 8 个 requested=actual 记录、8 张 footer、18 段展开地址，重测 overflow=0。
- 本机没有运行 Gradle/npm。两份原视觉守卫、Kotlin tree-sitter 静态解析与 git diff --check 通过；静态检查不冒充编译或全套 CI。本轮 compile/instrumentation 由临时采集 CI 执行，正式分支的 CI 配置和脚本不变；临时采集分支在交付时删除。
- 本轮源文件清单：`Pr17UiTest.kt`、`Pr17ListFirstScreenTest.kt`、`ProductEndToEndTest.kt`、本 NOTES；新增 `evidence/M1-list-firstscreen.md`、`M2-apply-window-font.md`、`M2-apply-manifest.json`、`SHA256SUMS`，以及 M1 before/after 各两 API 的截图/日志目录、M2 两 API 的截图/日志目录。校验清单覆盖全部 204 张 PNG 和原始日志，证据纳入 git。
- 以任务书指定 message 一次提交并推送 `feat/pr17-density-and-sliding-sessions`；推送后的完整 CI/HTML 复核及合并仍由助手继续执行，本轮没有代为合并。
