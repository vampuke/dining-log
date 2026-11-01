# P2 独立账号闭环交付记录

## 实现范围

- POST `/api/v1/auth/register`、`login`、`refresh`、`logout`、`recover`、`password`。
- GET `/api/v1/auth/session`、`/api/v1/auth/sessions`；DELETE `/api/v1/auth/sessions/{session_id}`。
- `contracts/openapi.yaml` 已同步请求、成功响应、429/Retry-After、可选 auth 幂等键和新增 session 路径。
- 用户名 NFKC→trim→lowercase，由 SQL UNIQUE 仲裁注册竞争。复用 P1 PBKDF2-HMAC-SHA256 10,000 + pepper 和 HS256 JWT；未知用户执行固定 dummy PBKDF2/HMAC verify，错误与错密码完全一致。
- D1 原子多窗口计数、IP HMAC、5 次连续失败锁定 15 分钟；正确登录/恢复清理失败计数。登录创建会话与密码/pepper CAS 同 batch，防止密码变更或并发锁定后仍创建旧会话。
- refresh 单代 7 天、session 最长 30 天；同 request ID 返回同一加密结果与响应 request_id；不同 ID/过期重用撤销整个 session。条件 UPDATE + mutation_guards + 唯一 parent 防并发第二代。
- 改密码/恢复递增 auth_version、撤销全部旧会话、同 batch 创建新的当前会话。`password_changed_at` 更新；即时失效主要由 user/session/JWT 三方 auth_version 和 D1 会话检查保障，避免 iat 同秒边界。
- 注册/恢复生成 8 个随机 128 bit Base32 恢复码；数据库只存规范化 SHA-256；恢复消费、作废旧码、改密码、撤销会话、创建新码/会话同 batch。
- Android core/auth 提供 AES-GCM 安全存储与刷新协调；data/auth 使用真正 Android Keystore 非导出 AES-256 key 和 noBackupFilesDir/AtomicFile。access 仅在内存；密文损坏或缺 key 删除文件回登录；无明文 fallback。
- 刷新前持久化 request ID/起始时间，重试复用旧 token + 同 ID；新 refresh 持久化后才发布新 access。Mutex 协调并发 401，每个业务请求最多重放一次；网络错误保留加密状态，撤销类错误清理会话。
- 登录/注册/恢复 Compose 页面使用现有 D2 浅深主题 token、等宽数字、24dp 网格、滚动/键盘适配、持续错误提示、密码显隐、加载/禁用态和 429 秒数文案。敏感窗口 FLAG_SECURE；复制恢复码为用户主动动作，API 33+ 标记敏感剪贴板。
- 切账号清 token、内存列表/草稿/导航，账号切换/清理与 refresh 持久化/发布共用会话 Mutex，并检查可见的 generation；取消账号任务，清理保留的本地缓存/草稿/数据库位置。P2 尚未实现 P3 的业务列表与 Room CRUD。

## 验证状态

已验证：GitHub Actions [run 41279089880](https://github.com/vampuke/dining-log/actions/runs/41279089880)，代码 commit `8f34f61`，backend、android、API 26/35 instrumentation 四个任务全部成功。

- 后端 `tsc`/ESLint、103 个测试（含下列 29 个 auth 用例）全部通过；SQLite 迁移检查与原有两组容量场景通过，未降低任何阈值。
- Android debug APK、`:core:test`（该 run 的 12 个 auth JVM 用例）、app unit test task 和 lint 通过。JVM 测试报告位于 `android-build` artifact。
- API 26 与 API 35 模拟器分别执行下列 7 个 instrumentation 用例；Keystore 往返/损坏/缺 key，以及浅深主题、滚动大字体、持续 429 提示与密码显隐通过。报告位于 `android-auth-api-26`/`android-auth-api-35` artifacts。
- 交付主 PR：[#1](https://github.com/vampuke/dining-log/pull/1)。跨线程收尾修复与最终 14 个 JVM 用例的 CI 见追加 [PR #2](https://github.com/vampuke/dining-log/pull/2) 的 Actions 检查；合并 commit 和合并后 master CI 可从 PR 与 [Actions](https://github.com/vampuke/dining-log/actions/workflows/ci.yml) 复核。

全部构建/测试仅在 GitHub Actions 执行。本机未运行 npm install/npm ci、Gradle、Docker 或 Vitest。未部署 Cloudflare、未建真实 D1、未写真实 secret。`design/PLAN.md`、`backend/migrations/0001_initial.sql`、P1 `password.ts`/`jwt.ts` 未修改。

## PLAN 10.2 八条映射

| 场景 | 文件与用例名 | 验证边界 |
| --- | --- | --- |
| 1 同名注册/规范化 | `backend/tests/auth/auth.test.ts`：`S1 concurrent NFKC/case registrations create exactly one account`；`S1 username validation and strong Unicode password policy are shared` | 已验证：GitHub Actions Workers 本地 D1 pool |
| 2 登录稳定错误/限流 | 同上：`S2 success, wrong password and unknown username consume all login windows with identical errors`；`S2 minute limits run before PBKDF2 and carry Retry-After`；`S2 fifth consecutive failure locks known and unknown users for exactly fifteen minutes`；其他 S2 用例与 exact configured budgets 用例 | 断言相同 code/message/field_errors、dummy KDF 被调用、窗口/锁定参数；没有宣称真实边缘时序统计通过 |
| 3 refresh 轮换/同 ID 重试 | 同上：`S3 same request-id lost-response retry returns byte-identical encrypted rotation result`；`S3 concurrent same request-id rotations create one child and replay the winner` | 包含同 ID 并发、唯一子 token、加密存储断言 |
| 4 并发 401/跨 ID 重用 | 后端同上：`S4 cross request-id old-token reuse revokes the entire session immediately`；`S4 expired replay window revokes session even with the original request-id`。Android `AuthTest.kt`：`concurrent401OperationsRefreshOnceAndReplayEachRequestOnce`、`concurrentNetworkFailuresAreCoalescedAndKeepEncryptedRetryState` | 客户端单飞在 JVM 测试；后端重用撤销在 Workers/D1 测试 |
| 5 改密码/恢复即时失效 | 后端同上：`S5 password change invalidates all old access/refresh within the same second and creates current session`；`S5 recovery invalidates every old session/token and stores only recovery hashes`；`S5 password-change racing with slow login cannot create an old-version session` | 同秒 token 与旧会话失效、并发登录 CAS；新会话可用 |
| 6 一次性恢复码 CAS | 同上：`S6 recovery CAS race commits exactly one new password/session/code set` | 该测试通过 test-only spy 放行 1/min 限流，以真正竞争恢复事务；生产无 bypass。独立测试仍严格验证恢复限流。只有一个消费/新会话/新码集合；全部旧码失效 |
| 7 A→退出→B 无残留 | `android/core/.../auth/AuthTest.kt`：`switchingAccountsClearsListsDraftsNavigationAndOwnerJobs`、`accountSwitchDuringRefreshCannotPublishPreviousAccount`、`accountSwitchBetweenRefreshReadAndPendingWriteCannotRestoreOldAccount`、`suspendedAccountCleanupSerializesLaterAccountInstall` | 已验证：生命周期与内存状态单测；未验证：完整业务页面、真机账号切换，见 P2_BLOCKERS |
| 8 重启/损坏/重装 | 同上：`simulatedProcessRestartRecoversPendingRotationWithoutPersistingAccess`、`damagedCiphertextClearsSessionAndReturnsLogin`、`missingKeyNeverFallsBackToPlaintext`、`expiredPendingRetryRequiresLoginWithoutSendingOldToken`；`android/app/src/androidTest/.../auth/AndroidAuthTest.kt`：`realAndroidKeystoreRoundTripCorruptionAndMissingKeyReturnLogin` | 已验证：JVM 进程模拟 + API 26/35 模拟器 Keystore 检查；未验证：真实杀进程、卸载重装、备份恢复与厂商迁移未验证 |

## 后端 auth 用例清单

`backend/tests/auth/auth.test.ts` 共 **29** 个用例：

1. `S1 concurrent NFKC/case registrations create exactly one account`
2. `S1 username validation and strong Unicode password policy are shared`
3. `S2 success, wrong password and unknown username consume all login windows with identical errors`
4. `S2 minute limits run before PBKDF2 and carry Retry-After`
5. `S2 fifth consecutive failure locks known and unknown users for exactly fifteen minutes`
6. `S2 success clears consecutive failures and registration respects IP minute/hour/day budgets`
7. `S2 login hour/IP windows and recovery limits are independent of login lock`
8. `S3 same request-id lost-response retry returns byte-identical encrypted rotation result`
9. `S3 concurrent same request-id rotations create one child and replay the winner`
10. `S4 cross request-id old-token reuse revokes the entire session immediately`
11. `S4 expired replay window revokes session even with the original request-id`
12. `S5 password change invalidates all old access/refresh within the same second and creates current session`
13. `S5 failed reauth retains current session and uses the same lock/window budget`
14. `S5 recovery invalidates every old session/token and stores only recovery hashes`
15. `S6 recovery CAS race commits exactly one new password/session/code set`
16. `S5 password-change racing with slow login cannot create an old-version session`
17. `session listing/revocation is owner scoped and logout accepts expired access`
18. `auth idempotency replays exactly, conflicts on changed payload and never stores plaintext secrets`
19. `strict JSON schemas reject unknown fields, missing refresh id, oversized bodies and media types`
20. `password rows contain salted PBKDF2 10000 plus pepper, never plaintext`
21. `concurrent login/recovery idempotency commits one session and conflicts on changed payload`
22. `DELETE own session and logout idempotent retries return empty 204 after revocation`
23. `JWT owner/version and refresh idle/absolute expiration are enforced`
24. `refresh racing with logout cannot consume token or commit a child`
25. `IP hour/minute, registration day/hour and recovery hour limits enforce exact configured budgets`
26. `fifth failure racing with a verified login prevents session creation`
27. `revoked sessions and expired auth idempotency windows cannot reveal cached credentials`
28. `successful login upgrades an old pepper with hash CAS in the session batch`
29. `auth janitor wipes expired credential ciphertext but keeps conflict fingerprints`

## Android 用例清单

`android/core/src/test/kotlin/top/alanvampuke/dininglog/core/auth/AuthTest.kt` 共 **14** 个 JVM auth 用例（最后两项为跨线程修复新增）：

- `secureStoreRoundTripUsesFreshNonceAndBindsApplicationId`
- `damagedCiphertextClearsSessionAndReturnsLogin`
- `missingKeyNeverFallsBackToPlaintext`
- `switchingAccountsClearsListsDraftsNavigationAndOwnerJobs`
- `concurrent401OperationsRefreshOnceAndReplayEachRequestOnce`
- `timeoutRetryReusesDurableRequestIdAndOldToken`
- `simulatedProcessRestartRecoversPendingRotationWithoutPersistingAccess`
- `expiredPendingRetryRequiresLoginWithoutSendingOldToken`
- `revokedRefreshClearsLocalStateAndDoesNotLoop`
- `diskFailureDoesNotPublishNewAccessOrLeaveSession`
- `accountSwitchDuringRefreshCannotPublishPreviousAccount`
- `concurrentNetworkFailuresAreCoalescedAndKeepEncryptedRetryState`
- `accountSwitchBetweenRefreshReadAndPendingWriteCannotRestoreOldAccount`
- `suspendedAccountCleanupSerializesLaterAccountInstall`

`android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/auth/AndroidAuthTest.kt` 共 **7** 个 instrumentation 用例，在 API 26/35 模拟器运行：

- `realAndroidKeystoreRoundTripCorruptionAndMissingKeyReturnLogin`
- `loginLightLargeFontShowsPersistent429AndPasswordToggle`
- `registrationDarkLargeFontKeepsInputsAndSubmitReachable`
- `recoveryLightLargeFontKeepsRecoveryInputAndSubmitReachable`
- `loginDarkLargeFontKeepsSubmitReachable`
- `registrationLightLargeFontKeepsSubmitReachable`
- `recoveryDarkUsesApprovedTokens`

## 权衡与遗留

详见 [P2_BLOCKERS.md](P2_BLOCKERS.md)。auth 幂等按本次明确要求使用现有表的 120 秒加密结果，密码仅进入独立前缀 HMAC 指纹，不存明文业务响应；过期立即拒绝解密，每小时 janitor 擦除密文，24 小时清理指纹。刷新/logout IP 限流使用独立前缀 D1 计数，未验证真实边缘 binding。弱口令表为新增小型固定 v1 表，校验和见 `backend/src/auth/weak-passwords.sha256`。

原有 logout-all、recovery-codes 与 /me 其他账号 CRUD 仍为 501；当前明确指定的 9 个端点之外功能不冒充完成。真实 Workers 免费档 CPU、跨地域限流、真机 Keystore/进程终止/重装/迁移与完整业务跨账号隔离仍未验证。
