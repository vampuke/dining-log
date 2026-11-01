# P4 Android 产品闭环验收

## 交付与证据

- 基线：`master=17d8b4f`；交付分支：`feat/p4-android-product-loop`；[PR #4](https://github.com/vampuke/dining-log/pull/4)。本报告随该 PR 交付，最终合并状态可在 PR 查看。
- 实现与本轮验收源码：`984eeaf`；[GitHub Actions run 41295121480](https://github.com/vampuke/dining-log/actions/runs/41295121480)。**CI 核实中，以下用例只有在该 run 全部通过后才标为 verified。**
- **收尾核实（由 Hermes 独立执行，非 Codex 自述）**：最终分支 HEAD `b0b3435` 的 [CI run 41298479764](https://github.com/vampuke/dining-log/actions/runs/41298479764) 四个 job 全部 `success`：`android`、`backend`、`android-auth-instrumentation (26)`、`android-auth-instrumentation (35)`。PR #4 以 merge commit `b5fe4b1` 合并入 master（合并前解除 draft）。
- 所有 npm、Gradle、lint、JVM 与模拟器任务在 Actions 执行。本 VPS 未执行安装依赖、构建、容器或模拟器。
- 账号和记录是 CI 公开合成数据；端到端使用 Actions 内启动的原版 Worker、独立本地 D1 与两个实际 HTTP 登录 session。没有部署 Cloudflare，没有真实 secret。

## 页面清单与实际完成度

以下页面已实现；组件复用仍保持餐厅二级关系，没有全局菜品 tab。真机验收另列。

| 页面 / 入口 | 实际行为与实现位置 |
| --- | --- |
| 根导航与三 tab | `ProductRoot.kt` typed routes；餐厅 / 到店 / 我的。SessionState 切换根导航，退出清除业务 back stack；保存子记录后保留父餐厅路径。 |
| 餐厅列表 / 聚合搜索 | `restaurants/ListScreens.kt`：300ms debounce，类型、菜系、评分区间、未评分、到店状态、日期筛选；更新时间 / 评分排序；20 条 cursor 分页。菜品、短评、评价、备注命中均归到所属餐厅，显示命中类型。 |
| 餐厅详情 | `restaurants/DetailEditScreens.kt`：文字信息、★ 十分位评分、10 格计量条、评价、备注、店内菜品、到店及历史入口。长文可展开，缓存 / 详情过期 / 错误保留内容。 |
| 餐厅新建 / 编辑 | 同文件：餐厅与首访一个请求创建；表单自动保留草稿；在餐厅编辑内管理菜品。保存栏独立于滚动表单。 |
| 店内菜品列表 / 详情 / 编辑 / 回收站 | 复用上述组件：名称、★ 评分、短评，新建 / 修改 / 删除 / 恢复；仅归 restaurant_id，不传 visit_id；详情和编辑均有二级入口。 |
| 全局到店 / 餐厅内到店 | 同一组列表与详情编辑组件：按餐厅、日期筛选，日期 / 餐次 / 备注 CRUD，回收站恢复；同日多次使用不同 ID 保留。 |
| 修改历史列表 / 隐藏管理 | `ListScreens.kt`：时间倒序、相对时间和可展开的绝对时间；本机已知评分摘要展示 ★ 4.0 → ★ 4.3，其他历史先显示变更字段，完整 diff 按需读。菜品历史限定 entity_type / entity_id。 |
| 历史详情 / 说明 / 隐藏 / 恢复 | `revisions/HistoryScreens.kt`：完整旧新值、长文折叠、说明编辑、隐藏与取消隐藏；普通列表排除隐藏历史。 |
| 重新应用历史字段 | 同文件：先读取当前值并显示拟修改值，选择旧值 / 新值及字段；仅提交选中字段，重新 CAS。未知结果未确认时不能另选字段替换原请求。 |
| 三栏冲突 / 超期比较 | 同文件：base_snapshot / server_current / local_draft 可横向滚动；同字段必须选择，独立字段建议也需确认；超过 7 天先读服务器再确认新保存。 |
| 删除 / 恢复 | 详情二次确认 + 分类回收站；隐藏历史用独立管理入口。父餐厅不可用或删除冲突保留草稿，不能强制合并。 |
| 我的 / 账号安全 | `settings/SettingsScreen.kt`：账号、session 列表与撤销、改密码、退出、本机草稿、主题（原型有切换）及版本。退出前说明清理缓存 / 草稿；密码仅在账号内存中保留。 |

界面沿用既有 D2 Quiet Grid token，没有另建颜色或字号体系。无图片、头像、占位图、上传入口；评分展示使用 `★ 4.3`，Slider `steps=39` 与整数十分位实现 0.1 步进，另有 ±0.1 控件。Manifest 仅 INTERNET / ACCESS_NETWORK_STATE，正式配置禁止 cleartext，debug 仅允许明确的本地 API 地址。

## PLAN 10.4 每一步对应测试

主用例：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt` 的 `plan104RegistrationThroughOfflineConflictRestorePasswordAndLogin`，API 26 / 35 都执行；不是假 Repository 或纯截图流程。

| 10.4 步骤 | 具体操作与断言 |
| --- | --- |
| 注册 → 保存恢复码 | UI 注册真实 CI 账号；复制 8 个唯一恢复码，点击「我已安全保存」后进入初始空态。此处验证复制动作 / 门槛，真实手机安全保存行为 unverified。 |
| 新增餐厅与首访 | UI 输入、★ 4.0、首访日期并保存；实际 GET 断言 rating_tenths=40、visit_count=1；`RemoteContractTest.restaurantAndFirstVisitAreOneRequestAndDishNeverHasVisitId` 另核对 wire payload。 |
| 从详情新增菜品 | UI 新建辣子鸡及短评、★ 4.7；断言所属餐厅及写请求没有 visit_id，返回父餐厅。 |
| 再次到店 | 同一天再次记录，断言 visit_count=2；全局日期搜索得到两个不同 ID，均保留。 |
| 店名 / 菜系 / 菜品 / 短评 / 评价 / 笔记 / 日期搜索 | 逐词输入并等待 debounce；断言餐厅结果 ID 与餐厅 / 菜品 / 评价 / 备注命中类型，显示标签；另搜地址、用日期筛选并在到店 tab 搜日期。类型、菜系、评分区间、未评分、已到店、起止日期逐项核对。 |
| 改评分 | UI 滑杆 ★ 4.0 → ★ 4.3 保存；当前值与历史 changed_fields 断言。`ProductVisualTest.selectingDefaultRatingSetsAnUnratedRecordAndEveryStepIsOneTenth` 覆盖默认评分、±0.1 和滑杆。 |
| 查看旧值 / 新值 | 历史详情实际显示 `★ 4.0` / `★ 4.3`；随后加说明、隐藏，普通列表排除，隐藏管理页找到并恢复。 |
| 重新应用一个历史字段 | 当前 ★ 4.3 / 拟改 ★ 4.0 比较，只勾评分；实际 GET 断言回到 40，评价保持原值。RemoteContractTest 另核对 source / side / selected fields / expected_version。 |
| 断网浏览与草稿 | 注入 transport IOException + connectivity=false；缓存详情与离线标记可见，500ms 草稿落 Room；保存显示「已保留草稿」，服务器备注不变。物理断网 / 弱网 unverified。 |
| 联网手动保存 | 恢复 connectivity，断言尚未自动上传；点击保存才更新服务器。JVM `connectivityRecoveryRefreshesInForegroundAndNeverUploadsDrafts` 核对只前台刷新第一页。 |
| 响应丢失补充用例 | 实际服务器 commit 后丢弃 response；表单锁定，Room 保留原请求；手动重试断言相同 key / payload、version 只增加一次。DurableDraftTest 覆盖文件重开、7 天边界和 Retry-After。 |
| 跨设备冲突 | 第二个真实登录 session 修改同一字段；第一 session CAS 得 409；核对三栏各值，未选择时保存禁用；用户选草稿后用服务器版本 + 新 key 保存。这里只验证双 session，不代表两台物理设备已测。 |
| 删除 / 恢复 | 餐厅二次确认删除，普通列表排除，回收站找到并恢复；菜品数 / 到店数保留。另从餐厅编辑修改菜品、查看限定历史、删除 / 恢复；到店日期与备注编辑、删除 / 恢复及按餐厅筛选也执行。 |
| 修改密码 | UI 输入当前 / 新密码并确认；Keystore 存入当前返回 session，旧其他 session 已撤销。测试也先从 session 管理 UI 撤销第二 session。 |
| 退出 → 重新登录 | UI 二次确认退出；断言 token 清空、Room 五表均空、业务 tab 消失；用新密码登录，主页可达，编辑 / 历史不留在 back stack。 |

补充状态验收：`ProductVisualTest.emptyNoResultsOfflineAndRefreshingContentRemainDistinct` 断言初次空态、真正无结果、尚未取得服务器结果的空缓存、部分缓存搜索范围、加载失败、加载中仍有旧 items。Room 分页追加 / ID 去重、查询命中类型隔离、摘要不覆盖详情、详情过期、晚到的 A 响应不写回 B 由 `RoomCacheTest` / `DurableDraftTest` 验证。P2 单飞刷新测试继续执行。

缓存文本回归：`ProductRulesTest.currentFullValuesSupersedeOldExcerptsIncludingExplicitlyClearedFields`、`newerDishSummariesAndDeletionSupersedeOldDishDetailsInAggregatedSearch` 和 `DurableDraftTest.clearingReviewAndNoteClearsCachedExcerptsAndTheirOfflineSearchMatches` 核对完整值优先、显式清空、较新菜品摘要与删除状态，旧片段不能继续参与离线搜索。

## 明暗、大字体、键盘的验证方式与结果

本轮 API 26 / 35 结果已核实（run 41298479764 两个 instrumentation job 均 success，见开头收尾核实条目）。以下逐项对应实际执行的用例。

| 硬要求 | 验证方法 |
| --- | --- |
| 浅色 | `lightAndDarkListDetailEditSearchHistoryAndConflictRenderActualApprovedColors`：列表、详情、编辑、搜索、历史列表 / 详情、到店、我的、冲突。截图逐像素断言实际 bg=`#FBFBFA`、ink=`#181916`；弹窗 surface=`#FFFFFF`，同时断言前景语义和系统栏模式。 |
| 深色 | 同用例切换：实际 bg=`#0F0F0E`、ink=`#F2F2EC`；弹窗 surface=`#191918`。两套均计算正文、次级文字、错误文字、保存按钮对比度 ≥4.5:1，原阈值保持。实际像素背景 >250、前景 >10，未降低。 |
| 200% 字体 | 两套主题将 Compose LocalDensity.fontScale 设为 2f，读取实际 TextLayoutResult.hasVisualOverflow；编辑输入不裁字，保存可见 / 位于根边界内 / 可点击。三 tab 与列表 / 详情 / 历史 / 我的标题也断言不裁切。`longReviewsAndHistoryValuesCanBeReadInFullInBothThemesAt200Percent` 展开长评价及旧新值，全文可读并继续滚动到操作按钮。不是厂商系统设置实测。 |
| 键盘 | 浅色 / 深色的 200% 字体编辑页请求真实模拟器输入法，等待 TYPE_INPUT_METHOD 窗口 + 实际 IME inset >0；断言保存 bottom 同时 ≤可见窗口 bottom、≤输入法窗口 top，并点击保存。另测深色长错误消息；未使用虚构固定键盘高度。 |

截图与报告位于该 run 的 `android-auth-api-26` / `android-auth-api-35` artifacts；抓图通过 CI adb 复制，API 26 弹窗使用 UiAutomation 实际截图。主应用仍保护屏幕内容；测试使用独立 ComponentActivity 承载实际组件。真机系统字号 / 厂商输入法仍 unverified。

## 离线、草稿、冲突机制位置

| 机制 | 实现与验证位置 |
| --- | --- |
| 只读缓存 | `data/local/ProductDatabase.kt`：五表，复合主键含 account_id，Room Flow、事务写入、摘要 / 详情分开、版本过期标记。`ProductRepositoryImpl.observeList / refreshList / refreshDetail`：先 Room、网络成功再事务更新、失败保留；按查询保存 cursor 和命中标签。本地 `core/CacheRules.kt` 只搜缓存字段，UI 明确范围；联网只前台刷新第一页与已打开详情。 |
| 草稿 | `core/Drafts.kt` 的 DraftAutosaver 500ms，SavedDraft 包含 base / editable / original method、path、payload、key、startedAt；提交前 stopAndFlush，原请求先持久化。`ProductRepositoryImpl.submit`：离线不写服务器，未知结果保留原 key / payload，SAVING 重开视作 RESULT_UNKNOWN；7 天内手动确认，超过窗口先 GET 比较，确认后新 key / 当前版本保存。`ProductViewModel.mutate / previewApply` 防止重开操作覆盖未知请求；无 outbox、无自动重放。 |
| 冲突 | `core/Drafts.kt.compareConflict / ConflictComparison.confirmed` 合成三栏 / 独立字段建议 / 必选字段；`ProductRepositoryImpl.submit` 处理 VERSION_CONFLICT，`HistoryScreens.ConflictDialog` 等待选择确认；新请求使用 server_current.version / 新 key。删除 / 已删父餐厅禁止强制合并，草稿保留。 |
| 账号隔离 | `OwnerCache` 与 AccountSession 共用清理 / 写入门禁，账号 generation 拒绝晚到响应；退出先取消账号任务并清理 Room / token。Room 不存密码或 token，主题偏好使用 DataStore。 |
| 网络认证 | 既有独立 AuthGateway / RefreshCoordinator 单飞；Api wrapper 每请求最多一次认证重放，稳定 code 区分过期 / 撤销。connect 10s、call 20s，GET 最多两次带抖动重试，写不自动 transport 重放；429 保留原请求并尊重 Retry-After。 |

依赖版本基线未变。普通 CI 使用已提交的 Gradle locks；`android-ui` 的依赖维护模式在 Actions 生成锁，再以不改写锁的完整构建检查验证。`android/build.gradle.kts` 仅从 RuntimeClasspath 排除没有 JVM 运行时类的 `kotlin-stdlib-common` 元数据模块，解决 AGP ASM 解析与生成锁的差异；JVM stdlib 和其他实际依赖仍锁定。依赖维护模式不作为业务模拟器验收证据。

## verified / unverified、后端与保护文件

- **verified（Hermes 收尾核实）**：Actions 构建、lint、JVM、API 26 / 35 模拟器与 CI 本地 Worker / D1 路径；依据为 run 41298479764 的四个 success job。未删测试、未 skip、未降低断言阈值。
- **unverified**：真机弱网与切网、真实移动 / 联通 / 电信、TalkBack、真实进程终止、不同厂商 Keystore、真机系统 200% 字号与输入法、生产 Cloudflare / 真实 D1、稳定签名 release 发布。逐项在 [P4_BLOCKERS.md](P4_BLOCKERS.md)，不冒充已验证。
- **后端业务未改**：`backend/src`、迁移、后端测试与契约相对 `17d8b4f` 无改动，无需列后端 bug 修复。只新增 Actions 本地 Worker 测试 fixture / 启动脚本，常规 CI 增加 Android 检查。一次旧 run 的容量 wall p95 超阈值已保留失败；后续完整检查保持 100ms / 30 次阈值，不改变断言。
- `design/PLAN.md` 未修改，SHA256=`8346ddfac5231fb10528b330bcaea170b8925b8b6ed441ccff9e9577b18519f0`。
- `backend/migrations/0001_initial.sql` 未修改，SHA256=`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`。

## APK

已由 Hermes 从 run 41298479764 的 `app-debug` artifact（id `13079983471`）下载并发布。

- 文件：`/var/www/html/dl/dining/shiji-b0b3435.apk`（12,504,356 bytes），软链 `shiji-debug.apk`
- 公网：https://ora.vampuck.com/dl/dining/shiji-debug.apk （HTTP 200，`content-type: application/octet-stream`）
- SHA-256：`a573e51a80b85199235bfe79df3b52ed280e9b33d80acb9afa5820c93be9a250`；MD5：`a4426a68690ecccf9168734cf4054bd0`
- `aapt2 dump badging` 实测：`package: name='top.alanvampuke.dininglog' versionCode='4' versionName='0.4.0'`、`sdkVersion:'26'`、`targetSdkVersion:'35'`
- 权限实测仅 `INTERNET` 与 `ACCESS_NETWORK_STATE`（另有系统自动添加的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`），无相机 / 定位 / 存储
- APK 内含 14 个 `classes*.dex`，`AndroidManifest.xml` 与 `resources.arsc` 完整
- `SHA256SUMS` / `MD5SUMS` / `APK_BADGING.txt` 已更新到同目录

本期交付 debug APK；release 编译与正式签名发布属 P5。
