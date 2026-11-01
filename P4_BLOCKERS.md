# P4 验证边界与需真机清单

自动化验证只在 GitHub Actions 执行，最终提交、run、用例和结果见 [P4_DONE.md](P4_DONE.md)。Room 文件重开、模拟器 Keystore、第二个 HTTP session 和主动注入的网络错误各有明确测试范围，不能据此认定真实手机、真实网络或生产 Cloudflare 已通过。

## 需真机（全部 unverified）

| 项目 | 仍需实际执行的验证 |
| --- | --- |
| 弱网与切网 | 真正断网、超时、丢包，Wi-Fi / 蜂窝切换；检查缓存不消失、草稿保留、未知结果原 key / payload 重试、联网不自动上传。模拟器的错误注入不代表这些物理网络条件。 |
| 移动 / 联通 / 电信 | 各采集稳定网络下的登录、普通 CRUD、搜索、历史和手动重试样本，记录网络类型、失败与耗时。尚无真实三网样本。 |
| TalkBack | 实际朗读三 tab、评分、表单错误、历史旧新值、三栏比较和冲突选择，验证焦点顺序、横向滚动、触摸目标、保存和返回。Compose 语义断言不代表 TalkBack 已验收。 |
| 进程终止 | 输入 debounce 前后、保存发出后及响应丢失时终止实际应用；重新打开，核对 Room 草稿、原请求、7 天比较、Keystore 恢复、账号与 back stack。关闭 Room 再建立 Repository 仅验证数据库持久化。 |
| 不同厂商 Keystore | 至少多个实际厂商 / Android 版本的安全存储、refresh 轮换、重启、密文损坏、重装与备份恢复；模拟器 AndroidKeyStore 的结果不代表这些实现。 |
| 手机系统显示与输入法 | 实际系统 200% 字体、不同屏幕 / 刘海 / 导航手势，以及厂商输入法弹出时保存按钮的位置与操作。Actions 覆盖固定模拟器和实际 IME 窗口边界，尚无真机记录。 |

## 发布环境（unverified，P5 范围）

- 本次未部署 Cloudflare、未建立或迁移真实 D1，也未写入生产 secret。Actions 的本地 Wrangler / Workers pool 不代表免费 Worker CPU、生产 D1、真实域名或网络延迟已通过。
- Android 正式 API 地址仍为 PLAN 指定地址。生产环境连通性、证书、数据恢复、监控和签名升级需 P5 验证。
- 交付的是 Actions 构建并检查的 **debug APK**；release 编译通过与稳定签名 release 发布是不同验证项。真实手机安装、原签名升级与厂商差异没有本期实测记录。

不将上述未验证项标成 verified。未完成的自动化检查也不会通过删测试、skip 或降低断言阈值处理。
