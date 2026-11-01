# Changelog

## 0.6.0 — unreleased / P6

- 补全账号资料、修改显示名、退出全部设备、重生成恢复码与账号注销；重认证、版本冲突、幂等重放和账号隔离均有测试。30 天后分块清理账号及鉴权数据，清空全部子表后才释放用户名。
- 地址改为选填；0002 在同一迁移事务内备份并恢复四张级联子表，逐表校验和、索引与失败回滚有独立验证脚本。
- 加入尊重系统动画偏好的短过渡、自适应与主题图标、描边/实心 tab 图标及重复点击滚动到顶部。
- 压缩标题与列表操作区，次要操作移入菜单；360×640dp 深浅色首屏与真实 API 最小字段保存加入模拟器验收。
- 新增无需登录的版本端点与匿名后台检查；「我的」页提供下载链接和持久化开关，下载交给系统浏览器。
- Android versionCode=6、versionName=0.6.0；版本端点暂指向真实已发布的 v0.5.0，正式发布后更新配置中的公开 APK 元数据。

## 0.5.0 — 2026-11-01 / P5

- 正式 APK 使用固定 release keystore；versionCode=5、versionName=0.5.0，缺少任一签名变量即失败。
- debug 包名为 `top.alanvampuke.dininglog.debug`；应用内版本读取构建信息，模拟器测试与 APK 身份检查同步更新。
- 实现受 production environment 和单一 concurrency group 保护的手动发布；仅接受 master 已通过 CI 与 API 26/35 检查的完整提交 SHA。
- workflow 自身先验证 SHA、HEAD 和 master 祖先关系，再执行所选提交中的校验脚本；未合并分支的代码不能先运行并绕过发布检查。
- 发布前构建、验签、检查版本和 SHA-256，并验证 API 26/35 安装；记录 Worker / D1 checkpoint 后依次迁移、完整性检查、部署、health、独立业务 smoke，全部成功才创建 GitHub Release。
- 补充部署、日志、Cron、Time Travel、回滚与验收记录；真实验证及仍未验证事项见 P5_DONE.md。

## 0.1.0 — P1

- 建立 Android 四模块、D2 Quiet Grid 明暗主题与整数十分位领域模型。
- 建立 Hono Worker、健康检查、43 个契约端点与 D1 Repository 占位。
- 原样落地 PLAN.md 3.3 的完整迁移，加入可复现的 sqlite3 约束、不可变性与回滚检查。
- 实现 PBKDF2-HMAC-SHA256 10,000 次加独立 HMAC pepper、HS256 JWT 工具与测试。
- 建立 OpenAPI 3.1 初稿和仅在 GitHub Actions 执行重活的 CI。
- 未部署 Cloudflare；业务 API 与 Android 页面留待后续阶段。
