# P9 追加项 R9：允许截图

## 背景

`android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt:66` 在 `onCreate` 中执行：

```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
```

这是 P2 阶段为账号安全加的全局防护（`PLAN.md:107`：敏感窗口默认保护，防止密码 / 恢复码 / 会话令牌被截屏）。副作用是**整个 App 任何界面都无法截图、录屏，也会在最近任务里被遮罩**。

用户现在要求：**应用需要允许截图。**

## 要求：按界面分层，不要一刀切

**不是**简单删掉那一行——那样密码页、恢复码页也能被截屏/录屏，等于把当初的安全兜底全拆了。要求改成**按当前界面动态切换**：

1. **默认允许截图**：餐厅列表、详情、编辑、菜品、到店记录、修改历史、设置（非敏感部分）、更新弹窗等**全部去掉 `FLAG_SECURE`**。

2. **仅在这些敏感界面保持防护**（继续 `addFlags(FLAG_SECURE)`）：
   - 认证页 `AuthPage.LOGIN` / `REGISTER` / `RECOVER`（密码、恢复码输入）——`auth/AuthScreens.kt:26`
   - 一次性恢复码展示界面（注册成功 / 重新生成恢复码后的那屏）——`auth/AuthScreens.kt:67` 附近
   - 修改密码弹窗 / 页面（`settings` 内 `change-password` 流程）

3. **实现方式**：不要在每个 Composable 里直接操作 window。用 `DisposableEffect` 在敏感界面的**进入/离开**时切换：

   ```
   DisposableEffect(isSensitive) {
       if (isSensitive) window.addFlags(FLAG_SECURE) else window.clearFlags(FLAG_SECURE)
       onDispose { if (isSensitive) window.clearFlags(FLAG_SECURE) }
   }
   ```
   放在一个可复用的 composable（例如 `SecureScreen(enabled: Boolean) { content }`）里，在 `MainActivity` 的 `setContent` 内按当前是否处于敏感页面传入布尔值。**离开敏感页必须清除标志**，否则后面所有页面又都截不了图——这是最容易做错的地方。

4. **`onCreate` 里那行全局 `addFlags` 必须删除**，改由上述机制按需施加。默认状态 = 允许截图。

5. **最近任务缩略图**：允许截图后，最近任务里会显示应用缩略图，这是预期行为，不需要额外处理。

6. **不要动剪贴板逻辑**：P2 已有的「复制恢复码时标记敏感剪贴板（API 33+）」保持不变。

## 验收

1. 餐厅列表 / 详情 / 编辑 / 设置 页可正常截图（`FLAG_SECURE` 未设置）。
2. 登录、注册、恢复账号、恢复码展示、修改密码界面仍然禁止截图。
3. **从敏感页返回普通页后，截图功能恢复**（证明 `clearFlags` 正确执行，没有残留）。
4. 为上述两点写 `androidTest` 断言：读取 `activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE`，分别在敏感页与普通页各断言一次，并覆盖「敏感页 → 普通页」的切换。
5. 在 `evidence.json` 里记录该项的修复位置与验证方式。
