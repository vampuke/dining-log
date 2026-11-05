# PR17 独立评审

评审日期：2026-11-05。实现目标：`5f676f8`。结论：**不可合并**，共 **BLOCKER 0 / MAJOR 2 / NIT 4**。没有确认新增的认证绕过、撤销失效或生产布局截断；拒绝合并的依据是两项明确的 PLAN 验收承诺尚未兑现。CI 通过不能替代这些缺失的验收。

本次仅写本文件，不修改生产代码、测试、PLAN、NOTES、基线或守卫；没有重新运行 Gradle/npm 或已经核实的 CI、视觉守卫。

## 评审范围与证据口径

完整阅读 `design/PR17_REVIEW_TASK.md`、`design/pr17-discussion/PLAN.md`、`NOTES.md` 和两轮 CIFIX 任务书。逐文件读取 diff，并读取认证、清理、Android 状态与测试相关上下文。

任务书的基线描述有误，不能据此遗漏主实现：

| 范围 | 实际内容 | 本次处理 |
| --- | --- | --- |
| 用户指定 `git diff 002023f..5f676f8` | 只有 `Pr17UiTest.kt`、`NOTES.md` 两文件，68 行增加、11 行删除；只有第二轮修复提交 | 两文件完整核对；具体差异见 N4 和偏离清单 |
| `git diff eaa8e42..5f676f8` | 真正 master 到实现，31 文件，1501 行增加、225 行删除 | 补审全部文件，包括 `35b74a1`、`9d1b2fd`、`5f676f8` |
| PLAN 基线 `f371a9b..eaa8e42` | Android、backend、contracts 的生产/测试代码 diff 为空 | 补审范围与 PLAN 的源码基线一致 |

采信任务书已核实的 CI run [41891869332](https://github.com/vampuke/dining-log/actions/runs/41891869332)：backend 12 个测试文件、auth 31 用例通过；Android 构建/单测通过；API26/35 各 55/0；两份视觉守卫通过。采信 A7、G2、G3 的已核实实测结果，不重新验证其通过状态。

为判断覆盖范围，读取现有解包目录 `/tmp/pr17_final26/`、`/tmp/pr17_final35/` 的截图名称和 `outputs/p4-screenshots/p4-screenshots/pr17-layout-api{26,35}.txt`。截图文件名仅证明采集轮次，实际字体以文本布局日志为准。本次不以文件名替代真实窗口字体，也不据此宣布已经完成目测。

## BLOCKER

**未发现可确认的 BLOCKER。** 因此没有需要列出的 BLOCKER 最小修复集。安全审查的通过依据和边界见下文；这不表示未覆盖场景也已获证明。

## MAJOR

### M1 — 列表首屏“不减卡数”的密度验收没有实现，现有计数方法还会把裁切后的卡当完整卡

- 主位置：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt:728`。
- 关联：同文件 `:730`、`:734`；`design/pr17-discussion/NOTES.md:55`。
- PLAN 要求：`design/pr17-discussion/PLAN.md:64`、`:178`、`:184`，同 fixture、密度、字体、主题比较首页/搜索/店内/到店/历史列表，普通字体完整可读卡数不得下降，证据归档。

现有测试仅查询第 1–3 张卡，再记录 `first-three-complete`；没有基线测量，没有 `after >= before` 断言，三张以上的收益或损失也不可观察。用于判定的 `SemanticsNode.boundsInRoot` 是**经过祖先裁切后的矩形**，部分露出的卡也可能满足 top/bottom 在 viewport 内；此性质可从当前 Compose 1.8.2 的 `SemanticsNode.kt` GEOMETRY 注释及实现核对。比较区域还使用整个 `pr17:viewport`，没有扣除浮动 tabs 的遮挡区。

因此，“测试绿 + first-three-complete=3”无法证明三张完整，也无法证明没有从六张退到三张。实际日志确实只有这种单边计数，例如 API35 普通字体餐厅竖屏为 3、横屏为 1；这些数字**不能**用作改前后退化结论。新增列表截图在滚到最后一项后采集，主要证明尾项可达；搜索新增截图只有 normal/light 的窄容器轮次。NOTES 仍明确说基线截图及首屏比较待归档，`design/pr17-discussion/evidence/` 当前为空、没有 tracked 文件。

**具体修法：**测试使用未裁切的卡矩形（`positionInRoot` + `size`，或正确的 layout coordinates），与实际列表可见区域及 `floating-tabs.top` 比较；枚举全部实际可见 record 卡，不能固定前三张。分别在真实基线 `eaa8e42` 与 `5f676f8` 用相同 fixture/设备参数采集首屏，给五类列表保存 before/after 卡数与图片并断言普通字体 `after >= before`。把截图/测量清单、提交 SHA、设备密度和结果归档到 PLAN 指定 evidence 目录，更新 NOTES 的待验收状态。若确实减少卡数，调整相关局部布局后再比较，不放宽验收。

**关闭条件：**五类列表均有可复查的同条件比较；裁切半卡及被浮动栏遮住的卡不计入完整卡；普通字体没有减少。仅补“已通过”文字或再跑原测试不能关闭此项。

### M2 — 重新应用弹窗的 font200 验收实际仍在 font100 运行，改动的配对布局也缺少深色覆盖

- 主位置：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt:126`、`:136`。
- 调用矩阵：同文件 `:558`、`:559`、`:572`、`:575`。
- 被验收实现：`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:156`。
- PLAN 要求：`design/pr17-discussion/PLAN.md:82`、`:168`、`:171`、`:185`、`:186`。

Round 1 修复改为 dialog 节点不检查请求字体与实际字体一致，这正确避免了人工 LocalDensity 不进入独立窗口的误报，却没有补回真实窗口大字体测试。Apply 矩阵仍调用 `configure(2f, false, 320)`，然后保存 `pr17-apply-pairs-font2.0-light-api*.png`；实际 dialog 从系统 resources 读取普通字体。

两 API 的日志均给出决定性证据：重新应用长地址的两轮分别是 `text window=dialog requestedFont=1.0 font=1.0` 和 `requestedFont=2.0 font=1.0`，API35 两轮都为 `reportedSize=932 x 723`、12 行。也就是说，第二张图没有验收 200% 的窗口字体，320dp 的人工宿主宽也不等于弹窗实际宽。该矩阵只遍历 1/2、固定 `dark=false`；名称清单只有两张 light Apply 图，没有 dark Apply 图。修改密码/确认窗口也出现 requested 2、actual 1，但这里最直接的合并风险是 PR17 实际改动的 Apply 标签/值分组。

NOTES `:102` 已诚实声明此限制，因此不将它认定为隐瞒或生产系统字体缺陷；但声明限制不能满足 PLAN 对改动布局的真实 font200 与深浅主题验收。宿主 ComparisonColumns 的 font200 测试不能替代独立 Apply 窗口。

**具体修法：**补充测试层的真实窗口字体配置，例如在 instrumentation fixture 中设置/恢复系统 fontScale、重建 Activity/Dialog，并用 `try/finally` 恢复；或者使用能验证 Dialog owner resources 的等价机制。对实际窗口明确断言请求字体等于 `layoutInput.density.fontScale`，覆盖 1/1.3/1.31/2 与 light/dark。逐段滚到当前值/拟修改值、长值展开、保存/返回，扫描相应标签和文本布局并采集实际窗口宽度。保留现有自身密度溢出检查；不要为通过测试而改变生产 Dialog 的既有继承策略。截图命名和 manifest 同时记录 requested/actual 字体，更新 NOTES。

**关闭条件：**至少改动的 Apply 配对布局在实际 200% 窗口字体、两主题下有全文与 footer 可达证据，完整矩阵有明确覆盖结果；不能再用 actual 1 的 `font2.0` 文件名充当通过依据。

## NIT

### N1 — 评审基线被写成已包含主实现的提交

位置：`design/PR17_REVIEW_TASK.md:3`、`:15`。

`002023f` 是第二轮修复任务书提交，祖先中已经包含 `35b74a1` 和 `9d1b2fd`，并非 master。该描述会让后续评审只看到两个文件。**修法：**后续任务书/评审摘要使用实际 master `eaa8e42` 或等价的 PLAN 源码基线 `f371a9b`；`002023f..5f676f8` 明确标成 Round 2 增量。此次已补审，不因这个文档错误遗漏生产代码。

### N2 — 直连 DELETE 没有把过期 access 的 HTTP 错误交给 RefreshCoordinator

位置：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:350`；关联 `:355`。

当前 owning token、单 UUID key、IO 和 Response.use 都正确；问题是非 204 会抛 AssertionError。`RefreshCoordinator.authenticated` 在 `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/auth/Authentication.kt:112` 只处理 AuthFailure，只有 `TOKEN_EXPIRED` 才刷新并重试。如果 DELETE 恰跨过 600 秒 access 边界，外层 authenticated 无法处理本次 401。当前 CI 通过说明本次未触发，不说明该路径具备原 Retrofit read 的错误语义。

**修法：**在测试 HTTP helper 内关闭 response 前解析非成功响应的 auth error，将 `TOKEN_EXPIRED` 转为 AuthFailure，让 authenticated 以同一个 key 重试，再对最终 204 断言。被撤销 other 的 access 检查也建议先证明删除前 token 有效，删除后核对 `SESSION_REVOKED`，避免仅凭过期 token 的 401 误归因；现有 refresh 的 `SESSION_REVOKED` 断言必须保留。本项影响测试可靠性，不是生产 DELETE 的认证漏洞。

### N3 — 保存回调断言使用累计值，后续轮次可能没有调用仍通过

位置：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt:568`。

`saved` 在循环外初始化；第一轮后大于 0，第二轮即使不再触发回调，`saved > 0` 仍通过。Round 2 只加消息没有改变此问题。**修法：**点击前保存 `beforeSaved`，在 `runOnIdle` 中断言 `saved == beforeSaved + 1`，或每轮清零并断言恰好 1。新消息应包含轮次字体/主题，不能把累计成功当作每轮成功。

### N4 — 稳定化轮次覆盖阈值两侧，但失去恰好阈值的证明

位置：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt:412`；关联 `:421`、`:431`；`design/pr17-discussion/NOTES.md:148`。

原 319/320、491/492 改为 316/324、488/496，遵循 `design/PR17_CIFIX2_TASK.md:20` 的明确修复指令，NOTES 已声明，不能认定为擅自放宽或横排未触发。采用实际 Slider layout bounds 修正语义外扩是正确的；实测 G2/G3 分支可达也已获证明。

但是 PLAN `:168` 的精确内容边界覆盖没有保留。`>=` 错改为 `>`、或 G2 恰好 492dp 时不能给每列 240dp，都不会被当前 ±4dp 轮次发现。日志记录实际 maxWidth，而分支期望仍使用传入 content，没有断言二者相符。

**修法：**保留稳定轮次，另加已校准的内容宽 319/320、491/492 检查，继续使用实际布局坐标；断言测得父内容宽等于目标宽，再验证排布和 G2 的 240dp 下限。此为补充建议，当前生产 `>=` 与父内容宽判断静态正确，此项不单独阻止合并。

## 安全与语义审查结果

| 审查点 | 定位与判断 |
| --- | --- |
| 初始 180 天期限一致 | `backend/src/auth/session.ts:7`、`:55`、`:58`、`:59`：一个 expiresAt 用于 session INSERT、新 refresh 上限与响应，无全量回填或复活逻辑 |
| 成功刷新才滑动 | `backend/src/auth/rotation.ts:20`、`:23`、`:26`、`:34`：拒绝旧 session/token 过期；session UPDATE 保留 owner、未撤销、旧 expires_at>now、auth_version 和有效用户条件 |
| CAS 与原子性 | `backend/src/auth/rotation.ts:32`：token CAS、两组 mutation guard、session 更新时间/期限、新 token、加密结果同一 D1 batch；未移出事务，失败不会只提交续期 |
| 三处期限一致 | `backend/src/auth/rotation.ts:27`、`:29`、`:30`、`:35`：新 refresh 使用 sessionExpires，响应为 iso(sessionExpires)，seal 加密同一 response，DB 写同一整数；AAD/缓存仍使用独立 retryExpires |
| 重放/撤销语义 | `backend/src/auth/rotation.ts:9`、`:12`、`:15`、`:21`、`:39`：120 秒内同 request-id 解密原响应，不写 session；异 request-id 或失效缓存仍撤销；读缓存前验证活跃 session，撤销后的 token 不因密文存在而恢复 |
| TTL 安全边界 | `backend/src/auth/session.ts:48`：refresh 仍 min(now+604800, session expiry)，access 600 秒未改。滑动 180 天不能推导为闲置 180 天仍可自动登录 |
| cleanupAuth 交互 | `backend/src/auth/cleanup.ts:6`：仅清 rotation 密文、auth idempotency 密文/指纹及 rate limits，完全不删除 sessions/refresh_tokens，不按 created_at 或旧固定 30 天清活跃 session。120 秒重放缓存不会随 180 天续期一起延长 |
| 其他会话物理清理 | `backend/src/auth/account-cleanup.ts:8`：sessions/token 清理限定用户已删除超过 30 天，不能误清未删除用户的滑动会话；本 PR 未改该文件 |
| 两个新真实 D1 测试 | `backend/tests/auth/auth.test.ts:263`、`:306`：仅 fake Date，finally 恢复；31 次六天步长跨创建后 180 天；DB/响应/新 token/字节重放与不再次续期；三个独立 fixture 验证前 1 秒/恰好/后 1 秒，失败无消费/子 token/缓存 |
| 既有安全测试保留 | 删除上述新增测试段后 `auth.test.ts` 与基线逐字一致；原 owner/version/idle/absolute、并发、重用、logout 竞争、sessions API 测试未删改 |
| Android 删除链路 | 全仓 rg 未发现已删 methods/override、state.sessions、vm::revokeSession 或 security.sessions 调用。只剩 `Repositories.kt:20` 的 DeviceSession 未使用类型，PLAN `:113` 明确允许保留，不报死链路 |
| loading/busy | `ProductViewModel.kt:294`：开始 loading/清 message；profile 成功或普通异常结束 loading；CancellationException 继续抛出。只复制相关字段，busy/passwordUnknown/passwordRevision 不被重置。进入设置及改密成功后 loadSettings 保留；继承的取消处理未被此次削弱 |
| DELETE 语义 | `ProductEndToEndTest.kt:345`：IO 中使用当前 owning RefreshCoordinator 的 Bearer、other.sessionId、闭包外单 UUID Idempotency-Key，Response.use、204、other access 401/refresh SESSION_REVOKED、own profile、改密/清缓存/再登录保留；错误适配不足见 N2 |
| 后端 sessions API | `backend/src/auth/routes.ts:158`、`:163` 与基线逐字不变。Android 去掉管理 UI/接口没有删掉服务器 owner-scoped 撤销能力 |

上述是此次改动及任务要求范围内的判断，不以继承的实现选择自动制造新增安全缺陷。

## 逐文件对照 PLAN

以下缩写均为确定路径：`A = android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/`，`F = android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/`。表内文件可按此前缀还原；问题条目已给出完整路径和行号。

| 完整差异中的文件（31 个） | PLAN 对照 / 结果 |
| --- | --- |
| A/P6UiTest.kt:145 | profile 非空且 loading 结束；设置滚到 logout，其余列表索引 10；四次重选、entry、profile/refresh 计数检查保留；fake 两方法移除 |
| A/P6UpdateUiTest.kt:45 | 三处 SettingsScreen 删除参数、必要回调改命名参数；原更新检查/开关用例不删 |
| A/P8VisualTest.kt:81 | 只迁移 SettingsScreen 参数；原短更新内容、滚动、16dp/IME/insets 断言未改 |
| A/Pr16UiTest.kt:203 | 仅退役授权的会话确认测试及 fake overrides/无用 import；其余测试保留 |
| A/Pr17UiTest.kt:48 | 新集中验收矩阵；真实字体和首屏收益缺口 M1/M2，回调与精确边界建议 N3/N4 |
| A/ProductEndToEndTest.kt:343 | 等待修正、直连撤销及失效链路迁移；原密码/logout/Room/再登录保留；N2 |
| A/ProductVisualTest.kt:73 | Settings 参数迁移；font200 换页截图前仅加 waitForIdle，原 overflow 断言不放宽 |
| A/ScreenshotSecurityTest.kt:34 | Settings 参数迁移，onPassword/onLogout 保留；安全截图断言保留 |
| android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Repositories.kt:22 | AccountSecurity 删除 sessions/revokeSession；profile/changePassword 保留；DeviceSession 按授权保留 |
| android/data/src/main/kotlin/top/alanvampuke/dininglog/data/auth/RetrofitAccountSecurity.kt:23 | SecurityApi 两接口和两个 override 删除；jsonArray import 删除；profile/read/改密未知结果与重放逻辑保留 |
| F/ProductRoot.kt:135 | Settings 命名参数透传，无刷新/撤销；LaunchedEffect loadSettings 及编辑离开链路保留 |
| F/ProductViewModel.kt:19 | state.sessions 与 revokeSession 删除；loadSettings 只取 profile，其余状态/协程业务不扩大修改 |
| F/components/BiteComponents.kt:72 | Sticker 参数尾部增加 maxLines，默认无限行；BitePanel 14dp/8dp、描边投影与按钮最小高度不变，G1 未实施 |
| F/components/HistoryTimeText.kt:21 | 仅 font>1.3 无限行/Clip；正常字体单行 ellipsis；48dp 点击目标、状态说明及两 modifier 保留 |
| F/components/QuietGridComponents.kt:100 | 响应式 helper、PageHeader trailing、独立 RatingMeter、RatingEditor framed/label 与三键分支；原调用处 tags 保留。大字体返回/标题分行是声明的 Round 1 偏离 |
| F/restaurants/DetailEditScreens.kt:35 | A1–A9/A5b、D1/D2/N3 全部布局点有实现，见下一节；没有移除确认、额外字段筛选或未保存子操作保护 |
| F/restaurants/ListScreens.kt:45 | B1 7/3/8/3dp、B2 7dp；首页品牌和非首页 trailing；G2/G3 父内容宽阈值和字体回退实现；未改 RowText、日期字段/筛选语义 |
| F/revisions/HistoryScreens.kt:31 | E1–E6/N5/N7 实现；只删 diff 卡内 divider；Review/Operation 工作区和 footer 不改；Apply 的真实字体/主题验收 M2 |
| F/settings/SettingsScreen.kt:28 | 账号刷新/会话卡/撤销确认全删；C1 响应式检查行、C2 logout 通栏；主题、账号资料、密码/确认流程保留 |
| F/settings/UpdateDialog.kt:36 | N2 阶段/大小 FlowRow；N1 次操作 240dp、Bottom；原窗口、LocalDensity 继承、85%/全内容滚动、16dp 与阶段回调保留 |
| android/feature/src/test/kotlin/top/alanvampuke/dininglog/feature/ProductViewModelTest.kt:44 | 只删除 fake 的两会话方法，业务测试不放宽 |
| android/tools/p7_baseline.json:16 | 只退役 revoke 插值 tag，未重写其他基线/保护字段 |
| android/tools/p8_protected_hashes.json:22 | 只更新 rotation.ts/session.ts/auth.test.ts 三个哈希，未删保护路径 |
| backend/src/auth/rotation.ts:20 | TTL/新 token/响应/DB 滑动修改严格落在原 guarded batch 内；安全审查通过 |
| backend/src/auth/session.ts:7 | 单 TTL 常量与 issueSession 期限；不改 schema、migration、token idle |
| backend/tests/auth/auth.test.ts:263 | 恰好两新测试，原文件其余内容逐字保留 |
| design/PR17_CIFIX1_TASK.md:1 | 主 PLAN 外的后续修复任务输入；未执行其建议性错误诊断作为既定事实 |
| design/PR17_CIFIX2_TASK.md:20 | 主 PLAN 外的明确边界稳定化授权；N4 属补验收建议 |
| design/pr17-discussion/NOTES.md:1 | 实现/修复、已声明差异与剩余限制；CI/证据待验收段未更新到最终完成，见 M1/M2 |
| design/run_pr17_cifix1.sh:1 | 修复调度脚本，没有进入 App/Worker 运行链路；为后续修复配套范围 |
| design/run_pr17_cifix2.sh:1 | 同上；未将调度脚本误当生产功能或验收证据 |

额外核对：主题、字体字号/行高、矢量、AuthScreens、contracts、migrations、依赖锁及两个视觉守卫 diff 均为空；ScreenFrame、SaveBar、FormField、ChoiceChips、ExpandableValue 的原保护逻辑没有扩大修改。源码 tag 集合比对仅删除 `revoke:${session.id}`，新增仅四个指定 `pr17:` 生产 tags；没有发现 tag 改名、移动到隐藏节点/注释，或新容器 merge/clear semantics 吞掉子操作。

## UX 密度验收逐项

“实现通过”指该承诺能由最终源码和已有相关测试证明；未有改前后几何证据的收益不据代码推算宣布通过。

| PLAN 条目 | 实现及验收判断 |
| --- | --- |
| 共用 1：内容宽和 font>1.3 回退 | 所有新增布局判断来自 padding 内 BoxWithConstraints，未用屏幕宽；生产边界正确。精确 G2/G3 回归覆盖 N4 |
| 共用 2：按钮对、null、48dp、换行 | 280dp/12dp/8dp、第一 weight/第二包内容；null 不占位并令剩余按钮通栏；保留按钮最小高度，无固定高/删文案/新 ellipsis；UD 例外 240dp/Bottom 实现 |
| 共用 3：标题/trailing/副标题 | 普通字体 280dp 同排；窄/大字体分行、metadata 靠行末；无 trailing 保持原结构。有 trailing 的大字体返回另起一行，已声明合理偏离 |
| 共用 4：元信息/长值 | 新元信息 FlowRow 12dp/4dp；Sticker 内部无限行，标题/值无限行、大字体单列；没有以 FlowRow 代替文字自身换行 |
| 共用 5–6：tags | 原字面量/插值/值与点击节点语义保留；唯一退役 revoke；历史刷新和三个测量 tags 均存在 |
| 共用 7：视觉与余量保护 | tokens/字体/描边/投影/矢量未改；IME/insets/SaveBar 原代码保持 |
| 组件：PageHeader | 默认空 slot 保持未使用者；page-title 单节点、back 保留；1.31/2 的标题全宽证据来自有 trailing 分支 |
| 组件：Score/RatingMeter | 保留 120dp/10 段/5dp/2dp/原评分语义；详情只一个 meter，null 不绘制；实体/未评分断言存在 |
| 组件：RatingEditor | framed=false 只去内框/padding；null 空轨道/显式 40、SetProgress、0.1、10/50 保留；三键布局与字体分支实现 |
| 组件：Sticker/HistoryTimeText | 贴纸可内部换行；时间只大字体无限行，正常 ellipsis 未改；大字体历史完整绝对时间有直接布局断言 |
| A1 编辑/删除 | 同排与回退 helper；danger/ConfirmAction/取消无副作用保留；已删除说明和恢复通栏 |
| A2 extraFields | 原非空筛选，餐厅 category/cuisine、到店 dining_date/meal 顺序；address 通栏蓝色 label，值 tags/原 displayValue；单字段无空位，DISH 无卡；大字体 2dp 配对 |
| A3/A9 菜品计数和提示 | 四态 caption 实现，使用服务器 count，无预览 size 冒充总数；新增/管理和独立预览 Lazy items 保留 |
| A4/A8 到店组 | 次数进入标题；最近日期取原非空 last_visit_date；标题回退和两个按钮实现；未额外生成重复最近日期行 |
| A5 小票头 | 眉题版本、弹性全名/突出评分；餐厅/菜品限定；独立 meter/verdict/date/修订/更新 FlowRow，date fallback/颜色/padding 保留；低分与高分使用原计算 |
| A5b/N9 详情刷新/状态 | trailing 保留刷新文案/禁用；offline/stale/error/missing 才建状态 item；空态/cache/error tags 和重试保留 |
| A6/A7 历史/收尾 | 两 Lazy items、原 12dp 间距；rail 只版本/更新时间，history 在标题组；footer 14dp/6dp/4dp、原条码/标语，票边同 item 紧接。API35 已核实 213.7→191.8dp；1/1.3 两主题比较有日志，不能外推为整页收益 |
| D1 编辑纵距 | 16→12dp，原 viewport/contentPadding/SaveBar 和 IME 处理保持 |
| D2 编辑子操作 / N3 内框 | 已保存餐厅新增/管理同排与大字体回退；未保存保护、saving 禁用/leaveEditor 保留；评分外卡保留、内框移除 |
| B1/B2 列表微调 | 7/3/8/3dp 与 item 7dp 源码吻合；large、RowText、摘要 ellipsis、旋转/rail/48dp 保持。完整首屏卡数不减：**未验收，M1** |
| N6 列表标题 | 首页仍 38dp 矢量/BITE/大字体食记/副标题；新增响应式；非首页新增进 PageHeader，tags/文案保留 |
| G2/N3 最低/最高评分 | 内容宽≥492、font≤1.3 两列/12dp，余者竖排；framed=false/独立 label、值/SetProgress/回调不串；496 的实际横排已确认；精确 240dp 下限覆盖建议 N4 |
| G3 Checkbox | 内容宽≥320、font≤1.3 等分/12dp，余者 Column/8dp；文本 weight、无 top12；互斥/fallback 保留；本体 heightIn(48dp) 修复，原触达容差未放宽 |
| E1/N5/N9 历史头/状态 | 18→12dp；刷新进 trailing、有 pr17 tag；offline/stale/error 才建状态，missing 独立空态保留 |
| E2/E3 历史操作 | 说明/隐藏仍两个文字按钮，danger/确认；隐藏后说明+恢复通栏，reapply 另行通栏；各回调/取消检查保留 |
| E4B/E5 diff 配对 | caption 原值/新值各到值 2dp、对间原 8dp；titleMedium/rail/展开 240 阈值不改，只删除 diff 卡内 divider；null/240/241/长地址检查存在 |
| E6 时间/版本 | 280dp/普通字体 time weight、version 包内容，窄/大字 4dp 竖排；0/1 默认及 created_at fallback 保留，切换/大字体完整时间检查存在 |
| N7 ComparisonColumns | 单元格标签到值 6→2dp，170/220dp、跨字段 12dp/横滚/值与 tags 保留；实际宿主大字体有覆盖，新增深色矩阵未补齐 |
| N7 Apply 配对 | 分组 Column/两对 2dp/对间 8dp、caption/颜色/选中/必填/跨字段 divider/footer 均实现；真实窗口 font200/深色验收：**缺失，M2** |
| C1 启动检查与手动检查 | 完整 label+Switch+check 的 280dp/字体回退实现；语义、禁用、失败/最新另行、关闭启动仍可手动检查保留 |
| C2 logout / 会话删除 | logout 通栏/danger/原确认文案；取消无动作、确认回调及 E2E 清缓存保留；无会话标题/撤销/刷新账号入口 |
| N1 更新次操作 | 240dp/普通同排、Bottom/12dp，回退通栏/8dp；busy 后台继续与 cancel 分离；原 P8 短说明和 16dp 断言未改 |
| N2 更新进度 | 阶段与大小 FlowRow 12dp/4dp，progress tag 在文本、取消独立；percent/total 未知、VERIFYING 文案和原回调保留 |
| G1/F1/F2 保留项 | 默认 BitePanel 14dp/8dp 未动；认证切换/显示密码仍竖排，无新增眼睛接口或位图 |

PLAN 第 9 节的验收清单进一步核对：

| 验收清单逐条 | 结论 / 实际限度 |
| --- | --- |
| 自动 1：双视觉守卫、脚本/tag/基线 | 已核实通过；脚本 diff 空、tag/基线最小变更另经静态核对 |
| 自动 2：backend check/test、安全原测试 | 已核实通过；新增恰好两测试，原文件内容未改 |
| 自动 3：Android assemble/单测/lint | 已核实通过；现 CI android job 调用 PLAN 完整任务列表，无需本机重跑 |
| 自动 4：API26/35 保留整个 suite | 已核实各 55/0；仅删除授权 PR16 会话测试，保留业务/安全/IME 测试 |
| UI 1：字体/主题/360、窄、横宽/边界 | 宿主四字体/两主题、逻辑宽高 fixture 存在；页面 279/280、UD 239/240；G2/G3 稳定两侧成立，精确边界 N4；独立窗口实际字体 M2 |
| UI 2：三实体/长字段/缺失/状态/null 回调 | 新矩阵及原测试有行为覆盖；值 tag/displayValue、版本/未评分和 meter 数量检查存在。所有状态并非都各自保存截图，不能把一张 head 图作为全状态目测 |
| UI 3：删除/子操作/历史/触达/IME | 回调和取消/确认检查保留；代表按钮对触达≥47.5dp 的原容差；首访/离开/IME/存储由原 suite 继续覆盖 |
| UI 4：历史/展开/时间/Apply/比较 | null/240/241/长地址/展开收起、隐藏恢复 reapply、独立值/必填/横滚覆盖；Apply 真实大字与深色缺 M2，逐轮保存回调建议 N3 |
| UI 5：评分行为 | null 不触摸赋值、设置40/步进/清除/10/50、min/max 独立 SetProgress 和 Checkbox 互斥覆盖；编辑/筛选去内框截图存在 |
| UI 6：设置加载/删除入口/更新/密码/logout | 等 profile 的 P6/E2E、入口不存在、主题/开关/手动/新版本/失败/最新、密码可达、取消/确认及清缓存覆盖 |
| UI 7：更新全阶段/长说明/16dp | 11 variants 包括已知/未知 percent/total、VERIFYING/READY/FAILED 两 verified/CANCELED/NEED_PERMISSION/INSTALLING；长版本/notes/message、回调/scroll/16dp 覆盖；Update 明确继承宿主密度，不能套用 Apply 的实际字体限制 |
| 布局证据 1：详情首屏/底部减少堆叠 | 指定布局在源码实现、操作与文字有测试；整页同条件 before/after 尚未归档，不能声明整页量化收益 |
| 布局证据 2：A7 两卡加 gap 总高 | 已有可信同 fixture 实测，普通字体不增长；票边/条码源码连续。旧尾卡 fixture 只证明 A7，不能替代其他页面 baseline |
| 布局证据 3：五类列表完整首屏不下降 | **不满足，M1**；不能用尾项可达或只记录前三张替代 |
| 布局证据 4：font200/窄容器 TextLayoutResult | 宿主新增文字有直接/扫描检查和重测负例；祖先可见裁切与文字自身 overflow 是不同量，M1；独立 Apply font200 不满足，M2 |
| 布局证据 5：深浅/层次 | token/描边/投影未动、守卫通过；多数新截图两主题齐，Apply 缺 dark；NOTES 的目测/归档仍待完成，不以此编造实际颜色缺陷 |
| 布局证据 6：长页面/IME/真实横屏 | 原 P8/P6SmallScreen/E2E 保留；新尾项避浮动 tabs 断言存在。640×360 人工 density fixture 不能单独等同真实设备旋转，NOTES 已声明 |
| 收尾：无空卡/状态空 item/不得静默放宽 | 原非空字段筛选、条件状态 item 和空回调实现吻合；无 G1、删文案/缩字。证据未归档及缺失验收必须如实保留为未完成 |

截图名称覆盖面：detail-head/dish/visit/visit-actions、edit-rating-one-frame、history-actions、settings-tail 每组四字体×两主题；filter 四个宽度及 with-editors 每组四字体×两主题；update-stage 0–10 每组四字体×两主题；四类 list portrait/landscape-tail 每组四字体×两主题；A7 before/after 各 1/1.3×两主题。Apply 和 Comparison 各只有 1/2 的 light 两张；header-full-title 279/280 只有 1.31/2 的 light；search-narrow 只有 normal/light 一张。尤其 **Apply 的 filename font2 与 actual font1 不一致**，已在 M2 单列，不能被总体截图数量掩盖。

## PLAN 偏离清单与裁决

| 偏离 | NOTES 是否声明 | 裁决 |
| --- | --- | --- |
| 有 trailing 的大字体 PageHeader：back 在标题上一行，标题全宽，而非 PLAN 原“返回与标题一行” | `NOTES.md:104` | 合理，且符合 CIFIX1 明确全宽修复要求；普通字体/无 slot 不变，没有借修复缩字或丢标题 |
| Checkbox 本体新增最小 48dp | `NOTES.md:109` | 合理；保留 tag 在 toggleable 本体、语义触达和原容差，不构成全局密度变更 |
| TextLayoutResult 在已测文字框内以原参数重测 | `NOTES.md:105`、`:106`、`:107` | 原简单 String 语义重建问题有源码说明；保留 overflow/原旧测试、另加 width/height/maxLines 负例，合理。此方法不能替代实际窗口字体或祖先可见区域验证 |
| Dialog 用节点实际密度，跳过人工宿主 scale 一致性 | `NOTES.md:102` | 修复误报合理；不能视为已完成 font200 窗口验收，M2 仍成立 |
| G2/G3 改 ±4dp 轮次、用 layout coordinates 和公共布局宽 | `NOTES.md:148`、`:149`、`:150` | 明确授权且修正了 Slider 语义扩张误用；横排实际可达，不报产品不可达。补精确边界建议 N4 |
| 图片/测量先输出 CI p4-screenshots，后归档 evidence | `NOTES.md:53`、`:55` | 采集渠道合理；后续归档和基线比较未完成，不能关闭 M1 |
| 本机不跑 Gradle/npm，由 CI 提供测试结果 | `NOTES.md:33`、`:57` | 有明确实现阶段约束，已采信绿 CI，不以本机未跑测试为问题 |
| 额外 CIFIX 文档及两调度脚本 | 后续任务书明确；初始改动文件表未补录四项 | 配套修复范围合理；不构成生产 scope creep，交付摘要可补齐 |
| 列表完整计数只看前三张、使用裁切 bounds、没有 baseline 比较 | NOTES 声明比较待采集，但未说明计数方法不能证明完整性 | **未声明的验收方法缺陷，M1** |
| Apply/Comparison 新矩阵固定浅色，且 Apply 不实际进入 200% | 字体限制已声明；主题缺口未明确声明 | **覆盖缺口，M2**；不能将“主题 token 未改”替代改动布局的深色验收 |

## 合并结论与最小补交集

**不可合并。** BLOCKER 为 0；M1/M2 是尚未完成的明确验收条件，而非把当前 CI 的 55/0 改判成失败，也不是已经证明线上存在裁字或认证漏洞。

使此结论可改为“可合并”的最小补交集：

1. 修正完整卡计数，补五类列表真实同条件 baseline/实现首屏比较、断言及 evidence/NOTES 归档，关闭 M1。
2. 补 Apply 实际窗口字体与两主题矩阵，实际 font200 全文/操作/footer 可达，修正截图证据口径，关闭 M2。
3. 对补充测试运行相关 API26/35 instrumentation；保留已有安全/业务/视觉守卫，不删除原断言或放宽规则。若补验收暴露产品退化，再做对应局部修复及验证。

N1–N4 是文档/测试可靠性与边界增强建议，不单独阻止合并。本评审没有代为实施任何修复。
