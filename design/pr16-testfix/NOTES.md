# PR #16 测试修复记录

依据完整阅读的 `design/PR16_TESTFIX_TASK.md` 执行。分支始终为 `pr14-rebased`，没有新建分支。A+B 同次提交为 `7999492`（`fix: narrow history time touch target and report height assertion`），已推送到 `origin/pr14-rebased`；本文件单独提交并推送。

实际改动文件清单：

- `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/HistoryTimeText.kt`：第 5–6 行移除 `fillMaxWidth` import、增加 `widthIn` import；第 25 行仅将 `modifier.fillMaxWidth().heightIn(min = 48.dp)` 替换为 `modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)`。其余代码不变。
- `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr16UiTest.kt`：原第 125 行断言按任务书增加实际高度失败消息，现为第 125–128 行。条件仍为 `bounds.bottom - bounds.top >= 48.dp`，阈值与其余断言不变。
- `design/pr16-testfix/NOTES.md`：记录实际改动、失败 B 的读码推理和验证范围。

失败 B 的读码推理（仓库行号均以修复提交 `7999492` 为准）：

1. `Pr16UiTest.kt:112–114` 保留设备的 `density.density`，只把 `fontScale` 设置为 `2f`，并把父 Column 的宽度设置为 `240.dp`。dp 的像素密度保持设备原值。
2. `restaurants/ListScreens.kt:161–169` 和 `:186–189` 依据字体倍率选择大字号布局；`ListScreens.kt:215–218` 把 `history-time:time` 标签传给 `HistoryTimeText` 外层 modifier，另把 `history-relative/absolute:*` 标签传给文字。`Pr16UiTest.kt:120–124` 使用未合并语义树查询外层标签，测量对象是时间盒子的布局边界。
3. `components/HistoryTimeText.kt:25–32` 保留 `heightIn(min = 48.dp)`、按钮语义、单行文字以及省略规则；`components/QuietGridComponents.kt:41–43` 直接交给 Compose Text；`theme/Typography.kt:19–22`、`:45` 定义时间文字为 `11.sp`、行高 `16.5.sp`。这些代码没有 API 26/35 分支，也没有从时间盒子移除最小高度的字体倍率分支。本轮 A 的修改只调整宽度链，不能据此断言 B 已修好。
4. `Pr16UiTest.kt:124–127` 实际比较的是 `getUnclippedBoundsInRoot()` 返回的 dp 坐标之差。仓库在 `android/app/gradle.lockfile:21`、`:39`、`:46` 锁定相关 Compose 库为 `1.8.2`。查阅这一版本的源码后确认：`foundation-layout` 的 `commonMain/androidx/compose/foundation/layout/Size.kt:808–811` 将最小高度舍入为整数像素，`:824–831` 仍服从传入约束；`ui-test` 的 `commonMain/androidx/compose/ui/test/BoundsAssertions.kt:256–265` 按节点 density 分别把边界转换成 dp；`ui-unit` 的 `commonMain/androidx/compose/ui/unit/Density.kt:82` 使用浮点除法，`Dp.kt:52`、`:72` 使用浮点减法和比较。因此不能仅凭源码中存在 `48.dp`，就证明最终 dp 坐标差必定精确满足该比较。上述机制是读码事实，尚不能证明本次失败由舍入或父约束造成，也不能解释 API 26/35 的差异。
5. 结论：目前没有证据足以确认 API 35 特有根因。新增消息会直接显示原表达式算出的实际高度；等待下一轮仪器日志后再定位，本轮没有针对 B 追加产品代码修改。

Compose 源码取自 Google Maven 对应版本的 sources JAR：

- https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.8.2/foundation-layout-android-1.8.2-sources.jar
- https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.2/ui-test-android-1.8.2-sources.jar
- https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.8.2/ui-unit-android-1.8.2-sources.jar

验证：提交前 `git diff` 只包含 A+B 两个指定 Kotlin 文件，`git diff --check` 通过；将文件与修改前版本逐字比较，确认仅有任务书指定的 import、修饰链和断言消息改动。没有修改 `plan104`、`waitFor`、`scroll` 或 `tap`。本机未配置 Android SDK，也没有模拟器；没有执行本地 Gradle 或仪器测试。API 26/35 的 `plan104` 验收及 B 的实际高度取证仍需推送后的 GitHub Actions 结果，当前不宣称仪器测试通过。
