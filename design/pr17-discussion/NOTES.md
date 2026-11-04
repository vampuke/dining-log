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

以下证据全部 **待 CI 采集后归档到 `design/pr17-discussion/evidence/`**：上述 `pr17-*.png`/布局日志；基线 commit 的同数据、同密度、同主题、同字体整页截图；A7 对照实测结果；首页/搜索/店内/到店/历史列表改前后完整卡数对比；font200 展开全文及窄容器 overflow 记录；深浅主题目测、票边连续/条码裁切检查；长页面/真实横屏/IME 与 API26/35 系统导航余量；保留 E2E 的清缓存与重新登录、原首访/离开编辑/IME 证据；backend 和 Gradle 的 CI 报告。暂不在 evidence/ 放置占位截图或冒充测量结果。

剩余限制来自用户明确的本机禁跑 Gradle/npm、证据由 CI 提供；设计实现没有静默放宽 PLAN 的验收标准。宽内容测试为逻辑宽度 fixture，不能替代真实设备横屏和目测；首屏收益与对比截图必须等待基线/实现 CI 产物，不能由本机静态守卫代替。

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
