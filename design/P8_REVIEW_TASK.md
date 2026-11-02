# P8 Review 任务书：独立完整评审

你是**独立评审者**，不是实现者。分支 `p8-ux-fixes` 已实现 P8 的全部五项改动，并已通过 CI。你的工作是**找问题**，不是夸奖。

## 评审对象

- 分支：`p8-ux-fixes`（相对 `master` 共 15 个提交）
- 任务书：`design/P8_TASK.md`（A–E 五节 + F 自查清单）
- 截图证据：`design/bite/p8-evidence/`（含 evidence.json、contrast.json、各页浅/深截图、200% 字体列表尾部截图）

## 评审方式（必须真的读代码和证据，不许只凭 commit message 判断）

逐条对照 `design/P8_TASK.md` 的 A–E，对每一项给出 **PASS / PARTIAL / FAIL** 和证据（文件:行号 或 截图文件名）。

**已知必须复核的缺陷（我已发现，请确认并补充修法）：**

1. `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt:23`
   `Modifier.fillMaxWidth().fillMaxHeight(0.9f)` 让更新弹窗恒定占据 90% 屏高。内容只有几行时，按钮下方出现占弹窗近半的黑色空白（见 `design/bite/p8-evidence/dark-update.png`、`light-update.png`）。
   → 要求改为内容自适应高度（`wrapContentHeight`），必要时设 `heightIn(max=)` 上限并在内容超长时内部滚动。

2. 同类问题：`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:78`
   `fillMaxHeight(0.94f)`，请判断该处是否为合理的全屏对比视图（若合理则说明理由，不必改；若同样造成大片空白则一并列入修复）。

**另外请独立检查这些点，不要预设它们是对的：**

- A 节：草稿链路是否**真的彻底**删除（core/data/feature 三层 + Room schema + 测试），有没有留下不可达代码、无用 import、失效的 `testTag`；保存失败路径是否仍能让用户知道「没保存成功」而不是静默丢数据。
- B 节：`BiteTextField` 在 **enabled=false / isError=true / minLines>1 / 200% 字体** 四种组合下是否都正常，label 与 placeholder 是否有重影、裁切或语义重复；深色下 `c.tertiary` 占位符是否偏暗。
- C 节：SHA-256 校验是否在**安装前**而非仅下载后执行；下载中断/取消后临时文件是否清理；`versionCode` 比较是否溢出安全；URL 是否只接受 API 返回的 https 地址；无「安装未知应用」权限时是否正确引导而非崩溃；前台服务通知在 Android 13+ 无通知权限时是否崩溃。
- D 节：悬浮胶囊在 **IME 弹出 / 大字体 / 旋转（横屏）** 下是否遮挡内容；列表底部预留高度是否与胶囊实际高度严格匹配（不是拍脑袋的固定 dp）；三个页面滚到底最后一条是否都能完整露出。
- E 节：旧版本持久化的 `"visits"` tab 是否安全回落；`VisitsRoute` 删除后是否还有悬空引用；**到店记录的查看入口是否真的可达**（不能只有「再次到店」新建入口，读不到历史就是功能倒退）；中间「＋」按钮的 `testTag` 与无障碍 `contentDescription` 是否正确。
- 全局：深色模式每一项是否都验证过；有无新增位图资源；`backend/` 与 `contracts/` 是否零改动（`git diff --name-only master..HEAD -- backend/ contracts/` 应为空）；`versionCode/versionName` 是否仍为 7 / "0.7.0"。

## 完成标准

1. 在 `design/p8-review/REVIEW.md` 写完整评审报告：逐项 PASS/PARTIAL/FAIL + 证据 + 问题分级（BLOCKER / MAJOR / MINOR / NIT）。
2. **只评审，不要修改 `p8-ux-fixes` 分支的代码。** 评审报告单独提交到分支 `p8-review`。
3. 若发现 BLOCKER 或 MAJOR，在报告中给出**具体到文件行号的最小修法建议**。
4. 不要合并 PR、不要发版。
