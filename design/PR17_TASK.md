# PR17 任务书：会话滑动 180 天 + 删除会话管理 UI + 详情页密度优化

三个独立改动，一节一项。全部完成后一次提交到新分支 `feat/sliding-sessions-and-density`（基于最新 master），推 origin 并开 PR 到 master（标题「sliding 180-day sessions, drop session manager, densify detail page」）。禁止改动与本任务书无关的文件。

## 1. 会话改滑动窗口，有效期 30 天 → 180 天（后端）

现状（已核实）：
- `backend/src/auth/session.ts:53`：`expiresAt = now + 2592000`（30 天绝对窗口）
- `backend/src/auth/session.ts:46`：`newRefresh` 里 `Math.min(now + 604800, expiresAt)` 把 refresh token 封顶在 session 过期线内
- `backend/src/auth/rotation.ts:26-28`：refresh 时 `next = newRefresh(..., session.expires_at, ...)`，只更新 `last_refreshed_at`，**不延长** session

改法（滑动窗口 = 每次 refresh 顺延）：
1. `session.ts:53`：`now + 2592000` → `now + 15552000`（180 天）。
2. `rotation.ts` 的 rotate 逻辑：把 `session.expires_at` 的旧值替换为 `now + 15552000`（常量抽成 `session.ts` 里导出的 `SESSION_TTL_SECONDS = 15552000`，login 与 rotation 共用，不留两处魔法数）。具体：
   - `next = await newRefresh(user.id, session.id, now, /* 新线 */ now + SESSION_TTL_SECONDS, old.id)`
   - 同一批 commit 里加一条 `UPDATE sessions SET expires_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL`（新线写回 session 表——**必须与 refresh token 的写入同事务**，`commit()` 已有 batch 机制，加进同一数组即可）。
   - 响应里的 `session_expires_at` 用新线。
3. `newRefresh` 的 `Math.min(now + 604800, expiresAt)` 语义保持不变（单个 refresh token 仍最多 7 天一换，但封顶线现在是滑动后的新 180 天线）。

约束与测试（`backend/tests/auth/auth.test.ts`）：
- 已有测试 `JWT owner/version and refresh idle/absolute expiration are enforced`（auth.test.ts:253）手工把 session `expires_at` 改到过去再断言失效——**这个测试天然兼容滑动窗口，不许改**。
- 新增 2 个用例：
  a. **滑动续命**：注册 → 人工把 session/refresh 的 `created_at` 回拨到接近 180 天前（照抄 ：253 用例的 UPDATE 手法）→ refresh 成功 → 断言 session `expires_at` 变成「refresh 时刻 + 180 天」（允许 ±5 秒），且响应 `session_expires_at` 与 DB 一致。
  b. **绝对闲置上限**：回拨后**不** refresh，直接调一个需要 access 的端点（把 access 的 `iat` 做旧不可行就跳过 access 部分，改为：回拨后 refresh 直接 401）→ 断言 401 `SESSION_REVOKED`。
- 回归：`logout accepts expired access` 等既有用例必须原样绿；`contracts.mjs` 的端点清单不变。

## 2. 删除设置页的「刷新账号与会话」按钮和整个「登录会话」区块（Android）

删除目标（`SettingsScreen.kt`）：
- `:49` 的 `BiteTextButton(onClick = onRefresh...)「刷新账号与会话」` 整行。
- `:58`「登录会话」标题 item、`:59-69` 的 `items(state.sessions)` 面板、`:70` 的空态文案——整块删除。
- 对应确认弹窗分支：`revoking`/`ConfirmAction` 里「撤销此会话？」那条只服务非当前会话，一并删；**「退出登录」对话框（`:99` 的 logout 按钮 + `:107` 确认框）保留**——它走 `POST /auth/logout`，与撤销会话是两回事。

连带清理（删干净，不留死码）：
- `SettingsScreen.kt`：`revoking` state、`onRevoke` 参数、`DeviceSession` import。
- `ProductRoot.kt:135`：`vm::revokeSession` 参数及透传。
- `ProductViewModel.kt`：`revokeSession()`、`SettingsUiState.sessions`、`loadSettings()` 里的 `sessions` 请求（保留 profile 加载；`state.loading` 语义不变）。
- `data 层`：`RetrofitAccountSecurity.sessions()/revokeSession()` 及接口声明（`Repositories.kt:24` 的 `sessions()`）——若接口只剩一个实现且无他处引用则删，否则标注 `@Deprecated` 不可取，直接删调用方。

**不许动的**：后端 `GET /auth/sessions`、`DELETE /auth/sessions/{id}` 两个端点（合同 45 条端点不变，`contracts.mjs` 不改）——API 仍在，只是 App 不再消费列表/撤销；`/auth/logout` 照用。

## 3. 详情页密度优化（Android，BITE! 风格不破）

现状全部竖向堆叠、右侧大片空白。目标布局（保持全部 testTag 原名，测试才不破）：

a. **小票头卡片**：评分行改为横向——`Score + VerdictSticker` 放同一 `Row`（Score 左、verdict 右对齐），日期 sticker 与「已修订」印章跟在后面或换下一行右侧对齐，不要每个元素独占一行。
b. **信息卡片**（extraFields）：改为**两列网格**（`Row` 里两个 `Column` 各 weight(1f)，或 `FlowRow`）：每个字段是「标签 + 值」的紧凑对（标签上行小字、值下行），两列排布；字段 ≤3 个时单行三列。`detail:$field` tag 跟着值走。
c. **操作卡片**：「编辑」（主按钮）与「删除记录」（次级）同一 `Row`——编辑 weight(1f)，删除包内容；合计宽度 = 屏宽，不再各占一行。
d. **菜品区头**：「菜品测评清单」标题、「N 道」caption、「新增菜品」「管理店内菜品」合并为一个区块头：标题行 = 标题 + count 右对齐；按钮行 = 新增菜品 weight(1f) + 管理店内菜品同排。
e. **到店区头**：同 d——标题行 = 「到店记录」+「已记录 N 次」右对齐；按钮行 = 「再次到店」weight(1f) + 「查看全部到店记录」同排；「最近到店」sticker 挪到标题行或条目卡内，不独占行。
f. **修改历史区头**：「查看修改历史」按钮与标题同一行（标题左、按钮右），时间轴保留。

红线：
- 所有 testTag 原样保留（`edit`/`delete`/`add-dish`/`visit-history`/`add-visit`/`history`/`detail:$field`/`detail-scroll`），e2e 与小屏测试（`P6SmallScreenEndToEndTest` 对 `detail:address` 的存在性断言）不改。
- 48dp 最小触达：按钮放 Row 里不许丢 `heightIn(min = 48.dp)`（BiteButton/BiteTextButton 自带，别在外面再包一层裁掉它）。
- 大字体（fontScale>1.3）：按钮行允许换行——用 `FlowRow` 或让次级按钮在空间不足时换到第二行，不许硬挤导致文本截断；`ProductVisualTest` 的 font200 detail 截图必须仍通过（背景/前景像素断言与标签色断言不涉及布局，但截图会人工复核）。
- 深浅两套主题都要目测协调（黑色描边、硬投影不变）。

## 验收清单（实现者自查后写进总结）

1. 后端：新 2 用例 + 全部既有 auth 用例绿；`contracts.mjs` 绿。
2. Android：`UpdatesTest`/`ProductRulesTest` 等单测绿；e2e `plan104` 全程绿（它点 `edit`/`delete`/`add-dish`/`add-visit`/`visit-history`/`history`/`revoke:*`——注意 e2e 里 `revoke:${other.sessionId}` 这条链路：第 2 节删了 App 的撤销入口，e2e 相应段落**必须改**为直接调 API 撤销另一会话（`security.revokeSession` 删了就用 OkHttp 直连 DELETE），断言服务端行为不变）。
3. `P6UiTest` 里 `sessions.size == 12` 的等待（:145）随 sessions 区块删除而失效——改为等 `settings.value.loading == false` 即可，其余断言不动。
4. `Pr16UiTest.currentSessionConfirmationSaysLogoutAndOtherSessionsStillSayRevoke` 测试**整体删除**（它测的就是被删的 UI）。
5. 视觉守卫本地过：`python3 -B android/tools/verify_p6_visuals.py` 与 `verify_bite_visuals.py` exit 0。
6. 汇报格式：分三节列「改动文件 + 关键行为变化 + 每条验收的证据」。
