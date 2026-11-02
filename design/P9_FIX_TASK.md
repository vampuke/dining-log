# P9 修复任务书：按独立评审报告修复 P8

## 0. 总则

- 继续使用分支 **`p8-ux-fixes`**（不要新建分支），在其上追加修复提交。
- 评审报告：`design/p8-review/REVIEW.md`（**先完整读一遍**，共 0 BLOCKER / 4 MAJOR / 3 MINOR / 1 NIT）。
- 不改后端、`contracts/`、`backend/migrations/*.sql`；不改 `versionCode`/`versionName`（保持 7 / "0.7.0"）。
- 不引入位图；图标只用 VectorDrawable。
- 每项修复都要在浅色 + 深色下自证；不许删断言让测试变绿。
- 不要合并 PR、不要发版。

---

## R1 · MAJOR：更新弹窗高度写死 90%

**文件**：`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt:23`

现状：`Modifier.fillMaxWidth().fillMaxHeight(0.9f)`，内容只有几行时按钮下方留出近半屏纯色空白（见 `light-update.png` / `dark-update.png`，按钮约在 y=340，面板底约 y=569）。

**修法**：
- 改为内容自适应：`Modifier.fillMaxWidth().wrapContentHeight()`，加 `heightIn(max = …)` 上限（按可用窗口高度的 0.85 计算，不要写死 dp）。
- 保留第 24 行的 `Column(verticalScroll)` 作为超长内容/大字体时的内部滚动。
- 必须验证两种状态：短内容（空闲/下载中）面板紧贴最后一个按钮下方；长 release notes + 200% 字体时内部可滚动且不溢出屏幕。

---

## R2 · MAJOR：旋转后编辑中的输入被丢弃

**文件**：
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt:165`（`LaunchedEffect(route) { vm.openEditor(ref, route.create) }`）
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt:170`

现状：Activity 重建后 `LaunchedEffect` 重新执行，`openEditor` 无条件 `_editor.value = EditorUiState(loading = true)` 并用默认值/服务端缓存重建 `SaveInput`，**已输入但未保存的内容被覆盖**；同时 `saving` 标志与失败/冲突提示也丢失。

**修法**：
- 在 ViewModel 内记录「当前已打开的编辑器 key」= `(ref.kind, ref.id, ref.restaurantId, create)`。
- `openEditor` 对该 key **幂等**：key 相同且 `_editor.value.input != null` 时**直接返回**，保留 input / saving / errors / message / 冲突状态，不重新加载。
- 仅在以下时机清除该 key：`leaveEditor()`（`ProductViewModel.kt:211`）、保存成功完成（约 `ProductViewModel.kt:232`）。
- 全部状态留在内存，**不得重新引入持久化**。
- 验证：输入后旋转、保存失败后旋转、保存进行中旋转，三种情况输入与提示都不丢。

---

## R3 · MAJOR：标签移出输入框后丢失无障碍字段名

**文件**：
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt:152,163-166`
- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt:183`
- 直接调用方：`auth/AuthScreens.kt:126`、`settings/SettingsScreen.kt:107`、`restaurants/ListScreens.kt:69`

现状：`Column { label(); OutlinedTextField(...) }` —— label 变成兄弟节点，输入框既没有 `label` 也没有可访问名称。TalkBack 逐个聚焦输入框时读不到「地址」「评价」「备注」；空的可选字段全部读作「选填」，一旦有内容占位符消失，字段名彻底丢失。

**修法**：
- `BiteTextField` 新增 `accessibleLabel: String?` 参数，在 `OutlinedTextField` 的 modifier 上用 `Modifier.semantics { contentDescription = accessibleLabel }` 或 `onFocusChanged` 无关的 `semantics { this[ContentDescription] = ... }` 挂到**可编辑节点本身**。
- 同时把外层独立 label 的语义**标记为不参与无障碍**（`Modifier.clearAndSetSemantics {}`），避免读两遍。
- `FormField`（`QuietGridComponents.kt`）把它已有的 `label: String` 透传为 `accessibleLabel`。
- 认证页、设置页、搜索框等直接调用方补上对应名称（如「用户名」「密码」「一次性恢复码」「搜索」）。
- 验证四种状态都各自只有一个有意义的字段名：空、已填、disabled、error。可用 `androidTest` 断言 `SemanticsProperties.ContentDescription` / `EditableText` 节点上的名称。

---

## R4 · MAJOR：横屏时侧边系统栏/刘海不保护悬浮胶囊

**文件**：`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt:85-87`

现状：胶囊只用固定 `start/end = 20.dp` + `navigationBars` 的**底部** inset。横屏且导航栏在右侧（约 48dp）时，胶囊右缘仅距物理边缘 20dp，最后 28dp 落在系统栏区域内；刘海同样。

**修法**：
- 在 `Box(Modifier.align(Alignment.BottomCenter)...)` 的 modifier 上先应用 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))`，**再**加 20dp 边距。
- 保留现有 `barHeight` 实测与 `navigationBottom + 16.dp` 底部计算，不要动第 82 行的内容预留高度逻辑（评审已确认 D4 正确）。
- 验证：横屏两个方向各一次（含侧边系统栏 / 刘海），断言胶囊与内部三个控件都在安全区内。

---

## R5 · MINOR：进程被杀后残留未完成的 APK

**文件**：`android/app/src/main/kotlin/top/alanvampuke/dininglog/InAppUpdates.kt:28,63,94`

现状：直接往最终文件名 `app-<versionCode>.apk` 流式写入；常规异常/取消会在 118–124 行删除，但**进程终止会绕过清理逻辑**，留下半截文件（下次版本号变了就成了孤儿）。安装前 SHA 校验能挡住误装，所以只是 MINOR。

**修法**：
- 下载写入同目录 `.part` 临时文件，SHA-256 校验通过后再 `renameTo()` 成最终文件名（同一分区内原子）。
- 在 controller / service 初始化时清理 `updates/` 下所有 `.part` 以及**版本号不匹配**的残留 `app-*.apk`。
- 保留现有「取消后清理完成前禁用重试」的顺序（44–52 行）。

---

## R6 · MINOR：输入框状态矩阵没覆盖 200% 字体组合

**文件**：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P8VisualTest.kt:127-156`

现状：矩阵覆盖了主题 × enabled/error × 行数，但没给 200% 字体；断言只查 focus / 存在性 / 前景色，不查 label / placeholder / error 文本是否溢出或重叠。

**修法**：
- 把字体缩放加入矩阵（1.0 与 2.0 两档），覆盖 `enabled=false`、`isError=true`、`minLines=4` 的组合。
- 断言改为检查渲染后的 **bounds 无重叠、无裁切**（例如 label 底 ≤ 输入框顶、error 文本在父容器内、placeholder 未超出框宽），而不只看颜色。
- 补充这几种组合的浅/深截图到 `design/bite/p8-evidence/`。

---

## R7 · MINOR：删除选餐厅流程后仍留有不可达的菜单 tag

**文件**：`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt:34,98-101`；`android/app/src/androidTest/.../ProductEndToEndTest.kt:388`；`android/tools/verify_bite_visuals.py:94,96`

现状：`onChooseRestaurant` / `onClearRestaurant` 与 `choose-restaurant` / `clear-restaurant` 菜单分支仍在，但 `ProductRoot.kt` 的两处列表调用都没传这两个回调 → 永不可达。校验脚本因此把「tag 声明」误当成「可达 UI」。

**修法**：
- 删除 `ListScreens.kt` 里这两个参数、两个 `DropdownMenuItem` 分支与对应 import。
- 删除端到端测试里识别这两个 tag 的辅助分支，改为**断言这两个入口不存在**。
- 更新 `verify_bite_visuals.py` 的可达性白名单。
- **保留** `visit-history`、`add-visit` 等真实可达路径的断言。

---

## R8 · NIT：无用代码

- `revisions/HistoryScreens.kt:88` 的 `baseVersion` 参数在移除 base 对比列后未被使用，同时删掉 113 行多余实参。
- `ProductRoot.kt:7` 的 `border`、第 9 行的 `rotate` 导入未使用。
- `MainActivity.kt:7` 的 `ActivityNotFoundException`、第 9 行的 `Toast` 未使用。
- 全部删除。删完确认编译期无 "unused" 警告。

---

## 验收

1. `git diff --name-only master..HEAD -- backend/ contracts/` 为空；两个 migration SHA 不变。
2. `versionCode=7` / `versionName="0.7.0"` 不变。
3. 上述 R1–R8 逐条在 `design/bite/p8-evidence/evidence.json` 里标注修复位置（文件:行号）。
4. 新增截图：短/长内容更新弹窗（浅深）、200% 组合输入框（浅深）、横屏胶囊（两个方向）。
5. 提交到 `p8-ux-fixes` 并 push。**不要合并、不要发版。**
