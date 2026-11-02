# P8 任务书：五项交互与视觉修正

## 0. 总则

- 分支：从最新 `master` 新建 `p8-ux-fixes`。
- **不要改 versionCode / versionName**，发布时由负责人统一处理。
- 不改后端、不改 OpenAPI 契约、不改 `backend/migrations/*.sql`（SHA 必须保持不变）。
- 保持"纯文字/数字/图标/色块"，**不得引入任何位图或照片**；图标一律 VectorDrawable。
- 保持深色模式完整可读（本项目独立深色 token，详见 `theme/Colors.kt`），**每一项改动都要在浅色+深色下各验证一次**。
- 现有 `testTag` 是模拟器测试的契约，改动后必须同步更新受影响的测试，**不许删断言来让测试变绿**。
- 完成后在 `design/bite/p8-evidence/` 落 8 张截图（浅/深 × 首页/编辑/详情/更新弹窗，360×640）与一份 `evidence.json` 说明每项改动位置。

---

## A. 彻底删除草稿功能

现状：`SavedDraft` 在 main 侧只暴露两处 UI —— `settings/SettingsScreen.kt` 的「未提交草稿 · N」区块，与 `restaurants/DetailEditScreens.kt` 的「放弃本机草稿」按钮 + 确认弹窗。但 **core / data 层有完整的草稿持久化链路**（`core/Drafts.kt`、`Repositories.kt` 的 `DraftStore`、`data/local/ProductDatabase.kt` 的 `DraftRow`/`saved_drafts` 表/`clearDrafts`、`ProductRepositoryImpl`、`ProductViewModel` 内 56 处引用、`revisions/HistoryScreens.kt` 的冲突对比）。

要求**整条链路移除**，不是只藏 UI：

1. 删除 `SavedDraft`、`DraftStatus`、`FirstVisitInput`（若仅草稿使用）、`DraftStore` 接口与实现。
2. `ProductDatabase`：删除 `DraftRow` 实体与 `saved_drafts` 表，**Room 版本号 +1** 并提供 migration（`fallbackToDestructiveMigration` 不接受；必须 `addMigrations` 显式 DROP TABLE），保留其余表数据。
3. `ProductViewModel`：移除草稿观察/存储/恢复/丢弃/结果未知重试等相关状态与函数；保存流程改为直接提交（提交前在内存里保留待提交字段即可，**进程被杀后不再恢复**）。
4. `SettingsScreen`：删除「未提交草稿」标题、说明、草稿列表与 `onDraft` 参数。
5. `DetailEditScreens`：删除「放弃本机草稿」按钮与确认弹窗、删除 `onDiscard`；把第 139 行文案「输入会自动保留为本机草稿；点击保存后才会提交」改为**「点击保存后才会提交」**（不得再提草稿）。
6. `HistoryScreens`：删除「本地草稿」对比列与「保留服务器值，清除本机草稿」按钮；冲突弹窗只保留服务器值与本地未提交值的二选一（若该对比本身依赖草案对象，改为纯内存快照）。
7. 删除相关占位实体与测试：`DurableDraftTest`、`ProductViewModelTest` 中草稿用例、`P6UiTest`/`P6SmallScreenEndToEndTest` 中草稿相关断言（若断言的是"草稿存在"，改成断言"该入口不存在"）。
8. 全局再 grep 一次 `draft`（忽略大小写），除 `WriteAction`/revision 语义外**不得残留**。

> 注意：`DraftStatus.RESULT_UNKNOWN`（保存结果未知）是一个真实风险场景。删掉草稿后，网络中断时用户无法确认是否已保存。**必须**改为：保存失败时在编辑页停留并提示「保存失败，请检查网络后重试」，配合详情页下拉刷新确认；不得静默丢弃。

---

## B. 修复输入框 placeholder / 提示样式异常

**已定位的事实**（请先自行复核再动手）：

- `components/BiteComponents.kt` 的 `BiteTextField` 用 `OutlinedTextField` 且**没有 `placeholder` 参数**，也未配置 `placeholderColor`。
- 所有调用方把 `label = { GridText(label) }` 传入，而 `GridText` 的默认 `color` 是 `LocalQuietGridColors.current.ink`（**实黑，不是次级灰**）。
- 结果：空字段的 label 浮在上方边框里、**用正文实黑色**呈现，视觉上像"已经填了内容"；同时输入区内完全空白，没有浅灰占位提示；`enabled=false` 与 `isError` 下也不一致。

要求统一成一套明确规则，并同时修好浅色/深色：

1. `BiteTextField` 增加 `placeholder: (@Composable () -> Unit)?` 透传，并显式配置 `placeholderColor`：
   - 浅色：`c.tertiary`（`#5D564A`），深色：`c.tertiary`（`#BCB3A2`）——两者对 `c.surface` 均已 ≥4.5:1。
2. label 颜色改为**非 ink**：收起（未聚焦、空值）用 `c.secondary`，聚焦用 `c.ink`，错误用 `c.danger`。即空字段不许再出现实黑 label。
3. 空值且未聚焦时，提供真正的浅灰 placeholder（例如「必填」「选填」或字段级示例），与 label 不重复堆叠：**要么 label 浮起 + placeholder 在框内，要么 label 不浮起 + placeholder 代替**，二选一，全项目一致。
4. `FormField`（`QuietGridComponents.kt`）同步支持 `placeholder`，并按字段给出合理示例（名称→「例如：兰巴赫」、菜系→「例如：川菜」、地址→「选填」、备注→「选填」等）。
5. 处理 `enabled=false`、`isError=true`、多行（`minLines>1`）三种组合下的表现，不得产生重影或文字被裁切。
6. 用 `verify_bite_visuals.py` 的 `contrast()` 复算新组合，全部 ≥4.5:1 并写入 evidence。

---

## C. 应用内更新：自动下载 + 自动安装

现状：只做 `Intent` 跳浏览器（`core/Updates.kt` + `MainActivity`），`AndroidManifest.xml` 只有 `INTERNET` / `ACCESS_NETWORK_STATE`。

要求升级为真正的应用内更新：

1. **权限**：`AndroidManifest.xml` 增加 `REQUEST_INSTALL_PACKAGES`；`<provider>`（FileProvider）声明 `androidx.core.content.FileProvider`，authority `${applicationId}.fileprovider`，`file_paths.xml` 只暴露 `cache/updates` 子目录（**不得**暴露 cache 根或外部存储）。
2. **下载**：更新弹窗给出「立即更新」。下载到 `context.cacheDir/updates/app-<versionCode>.apk`；用 `WorkManager` 或前台服务均可，但必须：
   - 下载中显示进度（百分比 + 已下载/总大小）；
   - 支持取消；
   - 断网/失败给出重试，不吞异常；
   - **下载完成后必须校验 SHA-256** 与 `/app/version` 返回的 `sha256` 一致，不一致则删除文件并提示"安装包校验失败"。
3. **安装**：API 26+ 用 `FileProvider.getUriForFile` + `Intent.ACTION_VIEW`（`application/vnd.android.package-archive`）+ `FLAG_GRANT_READ_URI_PERMISSION`。Android 8.0+ 需引导用户开启「安装未知应用」权限：用 `packageManager.canRequestPackageInstalls()` 判断，未授权时 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 跳设置，返回后再继续安装（**不得**直接 `startActivity` 硬 Crash）。
4. **保底**：任何一步失败（无权限、下载失败、校验失败、安装器不可用）都要回落到"打开下载页"的原行为，并给出可读提示。
5. **安全**：只接受 https；下载 URL 必须来自当前 API 域名返回的 `/app/version`，不接受任意输入；`versionCode` 必须为整数且 > 当前值才提示更新（保持现有逻辑）。
6. **测试**：为 SHA 校验、版本比较、URL 白名单写 JVM 单测（MockWebServer 可用）；FileProvider authority 与 `file_paths.xml` 用 `androidTest` 或 lint 断言。

> 不要实现静默安装（需 root/设备管理员），跳系统安装器即可。

---

## D. 底部 tab 改为悬浮式（不贴底遮挡）

现状（`ProductRoot.kt:212`）：`NavigationBar` 是 `Scaffold` 的 `bottomBar`，`Modifier.padding(horizontal=12.dp, vertical=6.dp)`，`height = 64.dp`（大字体 `112.dp`）+ `WindowInsets.navigationBars` 底边距，`containerColor = c.surface`，且**贴到底部并参与 content padding**，因此会遮挡内容并与系统导航条叠加。

要求改成设计稿里的**悬浮胶囊**：

1. 移出 `Scaffold.bottomBar`，改为 `Box` 内用 `Modifier.align(Alignment.BottomCenter)` 叠加在内容之上；
2. 距屏幕底 = `WindowInsets.navigationBars` 高度 + `16.dp`，左右各 `20.dp`；
3. 圆角胶囊（`RoundedCornerShape(28.dp)` 或与设计稿一致的 16–20dp），保留 BITE 描边（2.5dp `c.border`）+ 硬投影（偏移 4dp `c.shadow`，无模糊）；
4. 内容列表**底部增加等价 padding**（悬浮高度 + 安全区 + 16dp），保证最后一条记录可完整滚动到胶囊上方，不被遮挡——这是本次最容易做漏的点，必须用截图证明最后一条可见；
5. 键盘弹出时（IME 可见）胶囊应随内容上移或隐藏（二选一，说明理由），不得压住输入框；
6. 大字体（`fontScale > 1.3`）下高度自适应，不得截断文字；
7. 三个卡片/页面滚动到底时都要能露出完整最后一条。

---

## E. 去掉「到店」tab，添加按钮居中

1. 底部导航从三项减为两项：**餐厅 / 我的**。
2. 删除 `VisitsRoute` 及其 `composable`、`visitScroll`、`onAdd` 中 `selected == "visits"` 的分支、`vm.selectTab` 的 visits 映射、`restoredTab` 的 visits 分支。
3. **到店功能必须仍然可达**：餐厅详情页已有「再次到店」按钮（`DetailEditScreens.kt:99`，`testTag("add-visit")`），保持不变并确保它仍能创建到店记录。到店记录的查看入口改为：餐厅详情页内的到店历史区块（若已有则保留，若无则需在详情页补一个"到店记录"入口）。
4. 「添加」按钮（此前在 `BottomTabs` 的 `onAdd`）移到导航栏**居中**：两 tab 布局下，胶囊内部为 `[餐厅] [＋] [我的]`，`＋` 视觉居中且为强调色（`c.cta` 底 + `c.onAccent` 字），保持 `testTag`（当前为 `add`，确认后沿用或新增 `add-center`）。
5. 点击 `＋`：在餐厅列表页 → 新建餐厅；在「我的」页 → 新建餐厅（行为一致，不做上下文特判）。
6. 同步更新所有引用 `visits` tab 的测试与 `restoredTab` 持久化逻辑（旧版本存过 `"visits"` 的用户升级后要安全回落到餐厅页）。

---

## F. 自查清单（提交前逐条确认并写进 evidence.json）

1. `sha256sum backend/migrations/*.sql` 与改动前一致。
2. `git diff --stat` 不包含 `backend/`、`contracts/`。
3. 全局 `grep -ri draft` 无残留。
4. 浅色 + 深色截图各 4 张，全部人工确认无重叠/无遮挡/无裁切。
5. `REQUEST_INSTALL_PACKAGES` 与 FileProvider 已声明，`file_paths.xml` 仅含 `cache/updates`。
6. 底部胶囊悬浮，最后一条记录可完整滚动可见（截图证明）。
7. 底部导航只有「餐厅 / 我的」两项，中间是添加按钮。
8. 所有原本存在的 `testTag` 要么保留、要么有对应新 tag 且测试已同步。

---

## 交付

- 完成后提交到 `p8-ux-fixes` 并 push，PR 描述里逐条对应 A–E 并附截图路径。
- 不要自行合并、不要发版。
