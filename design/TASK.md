# 任务：为「餐厅记录」原生 Android 应用产出完整技术方案与实施计划

你是资深 Android 架构师 + 后端架构师。请针对下面这个全新项目，产出一份**可直接执行**的完整方案文档。

## 项目背景

用户（个人开发者）想做一个独立于其现有项目（jessie_wish，Flutter）的**餐厅探店记录**应用。旧项目里已有类似的餐厅功能，但功能很弱（只有 名称/类型/评分/备注/菜品列表），这次要**全部重新设计**，不复用旧代码、不复用旧 UI、不复用旧数据结构。

## 硬性要求

1. **原生 Android**：Kotlin + Jetpack Compose（不要 Flutter，不要 XML 布局为主）。minSdk 26，targetSdk 35。
2. **完全新的 UI 设计**：不要沿用 L'amour 那套玫瑰/暖色设计系统。要一个适合「美食记录」的新视觉方向，请给出 2-3 个候选风格并推荐一个。
3. **完全新的数据结构**：重新设计领域模型。
4. **独立的用户体系**：应用需要自己的注册 / 登录（不是匿名，不是复用别人账号）。要考虑：注册方式（邮箱+密码 / 用户名+密码）、密码哈希、会话管理、token 刷新、登出、修改密码。
5. **代码放在 GitHub public repo**（账号 `vampuke`）。
6. **后端部署在 Cloudflare**：Workers + D1。**不使用 R2**。
7. 先出方案 → 用户审批 → 再开工。所以现在**不要写生产代码**，只要方案与计划。

## 必须覆盖的功能

- 餐厅：名称、类型/菜系、**地址**（纯文本，不做地理编码）、评分、评价、备注
- **菜品**：名称、评分、评价（绑定在餐厅级，不绑定到某次到店的 visit）
- **就餐日期**：需要记录，且同一家餐厅可以多次到店
- 搜索 / 筛选
- 用户注册与登录
- **字段级修改记录**（用户能点开看到「这条 3 天前从 4 分改成 5 分」这种 diff）

## 明确不做（用户已排除）

- ❌ **图片 / 照片上传与存储**（不接 R2，不做图床，不做任何形式的文件上传）
- ❌ 地图、地理编码、定位
- ❌ 社交、分享、多用户协作（每个账号只能看自己的数据）

## 关键决策（已由用户拍板，不要推翻）

- 修改记录做成**字段级 diff**，用户可点开查看
- 菜品**不绑定**到具体的某次到店（餐厅级菜品库）
- 后端用 **Cloudflare Workers + D1**，单域名
- **不使用 R2，不支持图片**

## 已知环境信息（直接采用，无需再问）

- Cloudflare Account ID: `49eee8b8033579863adb3b7cf0b1852c`
- 域名: `alanvampuke.top`（已托管在 Cloudflare，NS 是 alex.ns.cloudflare.com / ali.ns.cloudflare.com）
- 使用**单个子域名**方案：`dining.alanvampuke.top`（API 全部挂在这个域名下）
- GitHub: `vampuke`，token 有 repo + workflow 权限

## 需要你产出的内容

请写成一个 Markdown 文档，包含：

1. **产品定位与信息架构**：这个 app 解决什么问题，页面地图
2. **UI 视觉方向**：2-3 个候选风格的描述（配色/字体/形状/质感），推荐一个并说明理由。要考虑没有图片的场景下如何让界面不显得空——图标、色块、排版怎么撑起来
3. **数据模型**：D1 的完整建表 SQL（含索引、外键、软删除策略），并说明每张表的职责。注意 D1 是 SQLite 方言，注意它的限制（没有真正的 ALTER COLUMN、外键需 PRAGMA、单库单写者等）
4. **API 设计**：所有 endpoint 的 method / path / 请求体 / 响应体 / 鉴权要求。要覆盖 auth、restaurant、dish、visit、revision 的全部 CRUD + 搜索。注意 Cloudflare Workers 的限制（单次请求 CPU 10ms 免费档）
5. **鉴权方案**：密码哈希算法（选什么，为什么在 Workers 里可行——注意 Workers 没有 Node 原生 crypto 的一堆 API，只有 WebCrypto）、JWT 签发与验证、refresh token 策略、token 存哪（Android Keystore？EncryptedSharedPreferences？）
6. **修改记录的实现方式**：字段级 diff 在哪一层算（服务端比较 before/after 还是客户端提交 diff）、存储格式（JSON）、如何避免噪音（比如每次更新都记一条还是合并）
7. **Android 架构**：分层（UI / ViewModel / Repository / Remote / Local cache）、DI 方案、网络库、本地数据库（要不要 Room 做离线）、状态管理、导航
8. **Cloudflare 部署拓扑**：Workers 路由、D1 库、自定义域名 `dining.alanvampuke.top` 的配置、环境变量与 secret 管理、免费额度是否够用（给出容量估算：假设 500 家餐厅 × 5 个菜品 × 3 次到店，纯文本）
9. **风险与坑**：已知的技术风险（Workers 免费档 CPU 限制、D1 并发写、国内访问稳定性、token 安全等）
10. **分阶段实施计划**：P0~P5，每个阶段的交付物与验收标准
11. **CI/CD**：GitHub Actions 怎么跑（Android 构建 + 签名 + 后端 wrangler deploy）

## 输出要求

- 写到 `/root/dining_log/design/PLAN.md`
- 中文撰写，代码/SQL/JSON 用英文
- 要具体，不要写"使用合适的技术"这种空话；每个技术选择都要说清为什么
- 不确定的地方明确标注「需验证」并给出验证方法
- 篇幅不限，宁长勿短
