# P2 未验证项与边界

## 需真机验证（PLAN 10.2 的 7–8）

- API 26 / 35 真机：A 登录→退出→B 登录，检查本地列表、草稿、导航及任务无 A 残留。P2 没有 P3 的业务缓存/草稿 CRUD，单测验证生命周期端口和内存状态；不能冒充完整业务页面验收。
- 真机杀进程再启动：Keystore 文件恢复、刷新丢响应后同 request ID 恢复、超过 120 秒回登录。
- 真机修改密文/失效密钥：回登录且文件删除；检查不同厂商 Keystore 和硬件支持。
- 真机卸载重装、云备份恢复、厂商设备迁移：不恢复旧 token 或私人数据；无明文 fallback。
- 真机浅色/深色、大字体、键盘弹出、无障碍和敏感窗口/剪贴板行为。CI 模拟器能覆盖的结果另列于 P2_DONE，不替代这些真机项。

## 平台验证仍未完成

- AUTH-01：未部署 Worker、未建真实 D1、未写真实 secret。P2 的 GitHub Actions Workers/D1 测试不能证明免费档真实边缘每请求 <10 ms CPU；PBKDF2 保持 P1 的 10,000 次实现，未修改阈值。
- AUTH-02：没有验证真实账户的边缘 Rate Limiting binding/WAF。注册/登录/恢复使用 D1 全局原子窗口；刷新/logout 使用独立前缀的 D1 IP 60/min 补充限流，未声称验证跨地域行为。

## 设计取舍

- migration 与 design/PLAN.md 保持不变。9 个指定端点实现；原有 logout-all、recovery-codes、/me 修改/注销骨架不在此次指定范围，仍返回 501。注册/恢复响应全部丢失且超出幂等窗口后，尚无恢复码重新生成接口，应重新走有效恢复码恢复或等待后续补齐；不声称已实现 PLAN 4.3 全部账号 CRUD。
- 用户明确要求 auth 接受幂等键，而 PLAN 4.1 排除 auth 明文业务幂等响应。为满足当前任务，auth 可选 UUID v4 key 使用现有 idempotency_keys：密码参与独立前缀 HMAC 指纹，响应只存 AES-GCM 密文、AAD 绑定用户/key/方法/路径/指纹/到期，120 秒逻辑重试窗口；沿用每小时 cron 清理过期密文，因此物理清理可能滞后一个周期，指纹保留 24 小时；同 key 换 payload 或超窗 409。refresh 单独使用 X-Refresh-Request-Id 和 refresh_rotation_results。同用户内幂等，不声称匿名跨用户名全局 key 去重。
- P1 没有随包固定弱口令表。P2 新增小型 v1 表与校验和，含 PLAN 指定变体；这不是完整泄露密码库，后续应扩大固定离线词表。
- core 为既有纯 JVM 模块：安全存储端口/AES-GCM/刷新协调位于 core/auth；Android Keystore 与 AtomicFile 适配位于 data/auth。无 DataStore 明文回退。
