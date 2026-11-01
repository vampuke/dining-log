# P2 任务：独立账号闭环

工作目录 `/root/dining_log`。方案 `/root/dining_log/design/PLAN.md` **只读**，是唯一事实来源。
先读 `PLAN.md` 第 5 节（认证与安全）、第 10 节表格里 P2 一行、**10.2 P2 必过场景**，以及 `backend/migrations/0001_initial.sql` 里已有的 auth 相关表（users / sessions / refresh_tokens / refresh_rotation_results / recovery_codes / auth_rate_limits / idempotency_keys / mutation_guards）。
**不要改 migration**，现有表结构就是设计好的，照着用。若确实发现缺字段，先写进 `P2_BLOCKERS.md` 并在提交信息里说明，不要擅自改表。

## 范围

P2 = 「独立账号闭环」：注册 / 登录 / 恢复 / 改密码 / session 管理全 API；Android Keystore 存储与刷新协调；Android 认证页面；auth 测试。

**不做**：餐厅 / 菜品 / visit 任何业务 CRUD（那是 P3）。不要提前实现。

---

## 一、后端 API（`backend/`）

在现有 Hono 骨架上实现，全部走 `/api/v1`，沿用已有 `contracts/openapi.yaml` 的风格并**同步更新契约**。

### 1.1 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 用户名 + 密码注册，返回 session |
| POST | `/auth/login` | 登录，返回 session |
| POST | `/auth/refresh` | 刷新 access token（轮换 refresh） |
| POST | `/auth/logout` | 撤销当前 session |
| POST | `/auth/recover` | 用一次性恢复码重置密码 |
| POST | `/auth/password` | 已登录改密码（要求旧密码） |
| GET | `/auth/session` | 当前账号信息 + session 元数据 |
| GET | `/auth/sessions` | 列出该账号所有活跃 session |
| DELETE | `/auth/sessions/:id` | 撤销指定 session |

### 1.2 必须遵守的规则

**用户名**
- NFKC 规范化 → trim → lowercase 后存储与比较，保证「同名用户名只有一人注册成功」
- 允许字符集与长度按 PLAN.md 第 5 节；不合法返回 400

**密码**
- PBKDF2-HMAC-SHA256，**10,000 次迭代** + pepper（复用已实现的 `src/auth/password.ts`，不要另写一套）
- 密码策略（长度 / 字符要求）按 PLAN.md；哈希里绝不出现明文，日志里也绝不能打印密码

**错误语义（防用户枚举）**
- 未知用户名与错误密码返回**同一个**错误码与同一条文案（401 `invalid_credentials`）
- 但两者都计入限流，且耗时不要出现明显差异（未知用户名也要跑一次假的 PBKDF2，避免时序侧信道）
- 登录成功 / 失败都要更新 `auth_rate_limits`

**限流与锁定**
- 按 IP + 用户名双维度，分钟窗口 + 小时窗口双层（表已在 migration 里）
- 连续 5 次失败锁定（按 PLAN.md 的参数）
- 超限返回 429，带 `Retry-After`

**Token**
- access：HS256 JWT，短有效期，claims 按 PLAN.md
- refresh：**一次一换**，用 `refresh_tokens` + `refresh_rotation_results`
- 刷新即作废旧 refresh
- **同一 request_id 丢包重试必须拿回同一结果**（查 `refresh_rotation_results`，命中就返回缓存结果而不是再次轮换）
- **不同 request_id 的旧 refresh 被重用 → 整个 session 撤销**（判定为 token 泄露）
- 多并发 401 只触发一次刷新是客户端职责（见二），但服务端必须保证同一 request_id 幂等

**改密码 / 恢复后**
- 旧 access 与 refresh 即使未到期也必须失效 → 在 users 上维护 `password_changed_at` 之类的判据（表里已有），校验时比对 token 的 `iat`
- 改密码 / 恢复需撤销该账号所有 session（或按 PLAN.md 的做法）

**恢复码**
- 一次性：双请求竞争只有一个成功（用条件 UPDATE 或 CAS，检查 affected rows）
- 使用后旧恢复码作废
- 恢复码存储为哈希，不存明文；生成时只在响应里返回一次

**幂等**
- 写操作接受 `Idempotency-Key` 头（或 PLAN 规定的字段），相同 key 相同 payload 返回同一结果，换 payload 返回 409
- 表 `idempotency_keys` 已在 migration 里

### 1.3 代码结构建议

```
backend/src/auth/
  password.ts        （已有，复用）
  jwt.ts             （已有，复用/扩展）
  rate-limit.ts      限流与锁定
  session.ts         session 生命周期
  recovery.ts        恢复码
  routes.ts          Hono 路由
  schemas.ts         zod 校验
```

### 1.4 测试（必须真跑）

`backend/tests/auth/` 下补 vitest 用例，覆盖 **PLAN 10.2 的 8 条必过场景**：

1. 同名用户名只有一人注册成功；大小写规范化后不能重复（`Alice` / `alice` / `ALICE` 只能成功一个）
2. 正确登录、错误密码、未知用户名都遵守限流与稳定错误（断言错误码与文案完全一致）
3. 刷新后旧 refresh 不再可正常使用；同 request_id 丢包重试拿回同一结果
4. 多个并发 API 401 只触发一次刷新（客户端侧，见二）；不同 request_id 的旧 refresh 重用撤销整个 session
5. 修改密码 / 恢复后，旧 access 与 refresh 即使未到期也不能访问
6. 一次性恢复码双请求竞争只有一个成功；旧恢复码作废
7. 登录 A → 退出 → 登录 B，本地列表、草稿、导航没有 A 的数据（客户端侧，见二）
8. app 进程终止后能用安全存储恢复；密文损坏、重装后正确回到登录（客户端侧，见二）

1–6 在 backend 测试里必须全部有对应用例；7–8 放 Android 单测（能做的部分）并注明哪些需要真机。

---

## 二、Android（`android/`）

### 2.1 Keystore 存储

- 新建 `android/core/src/main/kotlin/.../auth/` 包
- refresh token / session 元数据用 **Android Keystore 加密后落 DataStore**（或按 PLAN.md 的方案）
- **绝对禁止明文 fallback**：加密不可用时宁可丢 session 回登录页，也不能降级存明文
- 密文损坏 → 清除并回登录，不能崩溃
- 账号切换时清空全部本地状态，**无跨账号残留**
- 重装后回到登录（Keystore 密钥随卸载销毁，这是预期行为）

### 2.2 刷新协调

- 用 Mutex 或类似的单飞去重：多个并发 401 只触发一次 refresh
- 刷新失败的判定收敛：网络错误可重试；refresh 被撤销 / 无效 → 直接登出，不要循环

### 2.3 认证页面（Compose，遵循 D2 Quiet Grid）

- 登录 / 注册 / 恢复 三个页面
- 视觉必须：
  - 用 `design/proto/d2-quiet-grid.html` 的 token（精确网格、等宽数字、无图片、无头像）
  - **浅色 / 深色两套都要正确**（用户手机常开深色模式，这条是硬要求）
  - 大字体不破版
  - 错误提示用明确的文案 + 颜色，不用 toast 闪一下就没
  - 密码可见性切换、加载态、禁用态、服务端限流的 429 文案
- 无图片、无头像、无图标照片，只有文字、数字、图标、色块

### 2.4 Android 测试

- 单元测试覆盖：Keystore 加解密往返、密文损坏回退、账号切换清理、刷新单飞去重
- 需要真机的（进程终止恢复、重装）写进 `P2_BLOCKERS.md` 的「需真机验证」清单，**不要声称已验证**

---

## 三、硬约束

- **本机只有 956MB 内存**：禁止在本机跑 `npm install` / `gradle` / `docker` / `vitest`。
  你只写代码 + `git commit` + `git push`，**所有构建与测试的执行在 GitHub Actions**。
  用 GitHub API 读 run 状态与日志（token 在 `~/.git-credentials`）。
- 本机可以用 `sqlite3` 验证 SQL，用 `tsc --noEmit` 之类轻量检查——但不确定内存安全就不跑。
- **不要降低断言的阈值或删测试来让 CI 变绿**。失败就读日志修根因。
- 不要部署 Cloudflare、不要创建真实 D1、不要写任何真实 secret 进仓库。
- 不要改 `backend/migrations/0001_initial.sql`（除非走 `P2_BLOCKERS.md` 流程）。
- 改动走**新分支 → PR → 合并**，不要直推 master（用户 Git 习惯）。

---

## 四、完成判据

1. CI 全绿（backend 含全部 auth 测试，android 含新增单测），**且 PR 已合并**
2. `P2_DONE.md` 写清：实现了哪些端点、10.2 八条场景各自的对应测试文件与用例名、**哪些是"已验证"哪些是"未验证/需真机"**、遇到的权衡
3. 后端 auth 测试数量与名称列出来，方便我复核
4. 如有任何做不到的，写进 `P2_BLOCKERS.md`

**不要夸大**。没做的就写没做，需真机的就写需真机。
