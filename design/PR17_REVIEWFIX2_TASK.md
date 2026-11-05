# PR17 评审修复 Round 2 任务书（API26 Activity 生命周期竞态）

CI run（HEAD `e59bbfb`）：API35 instrumentation **success**，API26 **仅 1 失败**：

`Pr17UiTest.applyPreviewAndComparisonColumnsKeepTheirOwnValuesAndScroll`（API26 emu-5554-8.0.0）
`java.lang.IllegalStateException: Activity's stage must be [STOPPED, PAUSED, RESUMED] but was DESTROYED`
`at androidx.test.core.app.InstrumentationActivityInvoker.lambda$checkActivityStageIsIn$0`

## 助手诊断（复核后修）

`apply` 测试的 `finally` 块调 `setSystemFontAndRecreate(1f)`（不装 content）。`scenario.recreate()` 销毁并重建 Activity，但 **API26 模拟器上重建时序慢**：`finally` 返回时新 Activity 可能尚未 RESUMED（甚至旧实例刚 DESTROYED）。下一个测试方法 `settingsProfile...` 的 `render {}` → `compose.setContent` 走 `InstrumentationActivityInvoker.checkActivityStageIsIn` 断言 stage ∈ {STOPPED, PAUSED, RESUMED}，此时拿到 DESTROYED → 崩。

证据吻合：失败发生在 33/56（settingsProfile 是 apply 之后的下一个用例）；API35 重建快所以同代码绿；API26 只有这一处。

## 修法（按顺序做，最小改动）

1. `setSystemFontAndRecreate` 里 `recreate()` 之后、返回之前加：
   ```kotlin
   compose.waitUntil(10_000) {
       try { compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED } catch (_: Exception) { false }
   }
   ```
   （`scenario.state` 在 DESTROYED 瞬间可能抛 IllegalStateException——catch 住继续等。）
2. 同样在 `finally` 的 `setSystemFontAndRecreate(1f)` 之后确保等到了 RESUMED（第 1 条改在 helper 内，finally 自动受益）。
3. 防御性兜底：`render {}` 开头（compose.setContent 之前）检查 scenario state，若非 RESUMED 先 `scenario.recreate()` 并等待 RESUMED（同样带超时与 catch）。这样即便任何测试的清理不彻底，后续测试也能自愈。
4. 不改 M1/M2 的采集逻辑、断言、矩阵；不改生产代码。

## 纪律

- 一次提交推同一分支，message `fix: PR17 review round 2 (wait for RESUMED after recreate on API26)`。
- NOTES 追加 Round 2 小节：根因（Activity stage 竞态）、修法、为何只有 API26 挂。
- 不许删除/放宽 apply 测试任何断言；不许把 recreate 换成「不 recreate 直接改 LocalDensity」的倒退方案。

## 验收

推送后助手监控 CI。API26 与 API35 都须绿。
