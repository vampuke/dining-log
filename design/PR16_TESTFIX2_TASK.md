# PR #16 第五轮修复任务书（仅失败 B：断言精度容差）

## 背景（上一轮的成果，不要重做）

上一轮（`7999492`）已完成：
- 失败 A（e2e 回归）：`HistoryTimeText` 删 `fillMaxWidth()` 改 `widthIn(min = 48.dp)` —— CI run `41621011129` 实证 API 26 **44/44 全过**、API 35 的 `plan104` `passed (2m9.54s)`。**这条已关闭，禁止再碰 HistoryTimeText.kt。**
- 失败 B（取证）：断言消息已吐出实际值 —— **`actual 47.999992.dp`**，比 48dp 仅差 0.000008dp。

## 失败 B 的根因（已闭环，照此修，不要另行推理）

Codex 上一轮读 Compose 1.8.2 sources JAR 已确认机制：
1. `heightIn(min = 48.dp)` 在 `Size.kt:808–811` 把最小高度**向上取整为整数像素**（API 35 density 2.75 → 132px，再换回 dp 略小于 48）；
2. `getUnclippedBoundsInRoot()`（`BoundsAssertions.kt:256–265`）把 top/bottom **分别**经浮点除法换算成 dp；
3. `Dp.kt:52` 浮点减法、`:72` 直接比较 —— 两次舍入方向不一致，差出 0.000008dp，`>= 48.dp` 恰为假。

API 26 通过是因为其 device density 换算无此损失。**结论：这不是产品缺陷，是断言用「两次浮点换算后的 dp 差」去对比「精确 48dp」，比较域错了。**

## 修法（精确到行，只许改这一处）

文件：`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr16UiTest.kt`

当前（第 125–128 行）：
```kotlin
assertTrue(
    "history time touch target must be >= 48.dp (actual ${bounds.bottom - bounds.top})",
    bounds.bottom - bounds.top >= 48.dp,
)
```

改为（**0.5dp 容差，覆盖像素取整与浮点换算损失，仍远小于任何真实布局回退的量级**）：
```kotlin
assertTrue(
    "history time touch target must be >= 48.dp (actual ${bounds.bottom - bounds.top})",
    bounds.bottom - bounds.top >= 48.dp - 0.5.dp,
)
```

## 禁止清单（违反即打回）

- **禁止**改 `HistoryTimeText.kt`（失败 A 已闭环）。
- **禁止**改其他任何测试或产品代码。
- **禁止**把这个断言整个删掉、或改成 `> 0` 之类摆设。
- **禁止**在本轮动 `design/pr16-testfix/NOTES.md`（上一轮的调度脚本 bug 让它被重复提交了 6 次，本轮不再追加）。
- 提交只应触及 `Pr16UiTest.kt` 一个文件。

## 提交要求

- 分支 `pr14-rebased`（直接推这个分支，不要新建分支）。
- commit message 前缀 `fix:`，一次提交。
- 推送后结束，不要自行触发或等待 CI。
