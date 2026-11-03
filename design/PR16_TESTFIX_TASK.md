# PR #16 测试修复任务书（第四轮：两个真实测试失败）

CI run `41588823961`（commit `8052f96`）首次完整跑通编译与仪器测试：
- `android` job：SUCCESS（编译、单测、守卫全过）
- 仪器 API 35：**44 tests, 2 failed**（artifact `13209845959`）
- 仪器 API 26：**44 tests, 1 failed**（同一 e2e 用例）

这是 PR 分支的测试**第一次真正执行**。两个失败都要修，且只修失败，不许动别的。

## 失败 A：ProductEndToEndTest.plan104...（API 26 与 35 都失败）—— 真回归，必修

堆栈（API 35 HTML 报告）：
```
androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 45000 ms
  at ProductEndToEndTest.waitFor(ProductEndToEndTest.kt:402)
  at ProductEndToEndTest.plan104RegistrationThroughOfflineConflictRestorePasswordAndLogin(ProductEndToEndTest.kt:227)
```
失败点：`tap("record:${scoreRevision.ref.id}"); waitFor { vm.detail.value.cached.snapshot?.ref?.id == scoreRevision.ref.id ... }`

超时时的应用侧诊断日志（artifact `outputs/p4-screenshots/android-runtime.log` 最后一行）：
```
P4 E2E diagnostic: phase=score history note hide restore and reapply, query=ListQuery(kind=REVISION, ...),
detail=RecordRef(kind=RESTAURANT, id=796c7d94-...), ... items=[5 个修订 id], message=已保存到服务器
```
`detail` 仍指向餐厅记录 → **点击修订卡片根本没有触发导航**，不是网络或数据问题。

### 根因（已经用几何推演确认，照做即可）

这批改动把历史行的时间切换从「换行的窄按钮」改成了 `HistoryTimeText`：一个
`fillMaxWidth()` + `heightIn(min=48.dp)` 的**整条横带**，占满卡片内容宽度。
- 旧代码：`BiteTextButton`（TextButton）宽度收窄到文字，卡片中心的点击落在按钮外的空白 → 外层卡片 `clickable` 触发 → 打开详情。
- 新代码：横带全宽，卡片中心的点击**落进横带** → 被内层 toggle 吃掉 → 只切换了时间格式，导航永远不发生。

这不是测试写错，是产品交互回归：用户点卡片主体会被误当成「切换时间格式」。

### 修法（精确到行，不要自由发挥）

`android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/HistoryTimeText.kt`

把可点击盒子的修饰链从：
```kotlin
Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(...).semantics {...}, ...)
```
改为：
```kotlin
Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(...).semantics {...}, ...)
```
即：**删掉 `fillMaxWidth()`，加 `widthIn(min = 48.dp)`**。其余全部不动：
- `testTag`（外层 `history-time:*`）与 `textModifier` 的 `history-relative/absolute:*` 位置不变；
- 语义（Role.Button、stateDescription、onClickLabel）不变；
- `heightIn(min = 48.dp)` 保留——无障碍触达高度不变。

效果：可点击区域收窄为时间文字（含 48dp 最小宽高），卡片其余区域点击恢复打开详情。需要 `import androidx.compose.foundation.layout.widthIn`。

### 验收

- `Pr16UiTest` 的 `historyTimeHasBothActions...` 测试**一行都不许改**（它的断言与宽窄无关，高度断言仍须成立）。
- `plan104...` 在 API 26 与 35 全部通过。
- 禁止：删除/跳过 plan104、放宽 waitFor、改 scroll()/tap() 辅助函数、在产品代码里加等待。

## 失败 B：Pr16UiTest.kt:125 高度断言（仅 API 35 失败）—— 先取证，本轮不猜测根因

```
java.lang.AssertionError
  at org.junit.Assert.fail(Assert.java:87)
  at org.junit.Assert.assertTrue(Assert.java:42)
  at Pr16UiTest.historyTimeHasBothActionsAndStableHeightAtLargeFontWithoutOpeningTheCard(Pr16UiTest.kt:125)
```
第 125 行：`assertTrue(bounds.bottom - bounds.top >= 48.dp)`（fontScale=2f 场景）。

静态推演：`HistoryTimeText` 的盒子带 `heightIn(min = 48.dp)`，高度不可能 < 48dp，此断言在几何上应恒真——但它在 API 35 实际失败了，而 API 26 通过。**现有证据不足以定位**，本轮只做一件事：把断言改成带实际数值的失败消息，让下一轮日志直接给出真实尺寸：

```kotlin
assertTrue(
    "history time touch target must be >= 48.dp (actual ${bounds.bottom - bounds.top})",
    bounds.bottom - bounds.top >= 48.dp,
)
```

除消息文本外**一个字符都不许改**（断言强度、阈值、其余断言全部保持）。如果你在读码后确信找到了 API 35 特有的根因，把「文件+行号+推理」写进你的总结，但**本轮不要顺手改产品代码**——等数值回来再定。

## 提交要求

- 分支 `pr14-rebased`（PR #16 的 head，直接推这个分支，**不要新建分支**）。
- commit message 前缀 `fix:`，一次提交包含 A+B 两处改动。
- 改完自查：`git diff` 只应触及 `HistoryTimeText.kt` 与 `Pr16UiTest.kt` 两个文件。
