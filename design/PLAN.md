# 餐厅记录：原生 Android 技术方案与实施计划

> 状态：**方案待审批；本轮仅提交此文档，P0～P5 均在用户审批后启动。**
>
> 需求依据：[TASK.md](TASK.md)。编写及公开资料核对日期：2026-10-31。
>
> 应用正式定名「食记」，GitHub public 仓库建议为 **vampuke/dining-log**。账号、Cloudflare Account ID 和生产域名采用已知信息，不需要重新确认。

## 0. 决策摘要与范围

本项目是从零设计的私人餐厅记录应用。核心模型是 **餐厅档案 → 餐厅级菜品库 + 多次到店记录**；每次有效保存产生服务端计算的字段级修改记录。账号之间完全隔离。

| 项目 | 本方案的明确选择 | 原因 |
| --- | --- | --- |
| Android | Kotlin、Jetpack Compose、Material 3；minSdk 26、targetSdk 35、compileSdk 35 | 满足原生要求；声明式页面适合表单、列表和状态切换 |
| 视觉 | 最终采用 D2「Quiet Grid」：发丝线、严格行列、等宽数字与单一绿色状态色 | 用户已确认；在没有图片时仍能靠高密度排版形成稳定层级 |
| 领域 | Restaurant、Dish、Visit、Revision 独立建模 | 餐厅评价与菜品评价持续更新；到店日期不会改变菜品归属 |
| 注册 | 独立用户名 + 密码，显示名另设；一次性恢复码用于找回 | 不依赖邮件投递、短信、第三方身份服务或旧项目账号 |
| 密码 | PBKDF2-HMAC-SHA256，10,000 次；随机盐；额外 HMAC pepper | 为免费档降低迭代次数，离线破解抵抗力显著下降；补偿措施与真实 CPU 验证详见第 5 节 |
| 会话 | HS256 access JWT 10 分钟；不透明 refresh token，逐次轮换 | 使用 WebCrypto 即可签发验证；D1 会话检查支持即时撤销 |
| 后端 | 单个 TypeScript + Hono Worker、Zod 校验、D1、参数化 SQL | 小型项目不需要额外服务，直接 SQL 更容易审查事务与账号隔离 |
| 域名 | 所有生产 API 使用 https://dining.alanvampuke.top/api/v1 | 严格采用单子域名，使用 Workers Custom Domain |
| 数据历史 | before/after diff、版本号 CAS、D1 batch 原子提交 | 防止覆盖别的设备修改、伪造历史和“数据已改、历史未写” |
| 本地 | Room：已同步数据缓存 + 未提交草稿；首版需联网提交 | 支持弱网阅读和输入保留，控制首版同步与冲突处理成本 |
| CI/CD | GitHub Actions：Android 构建、校验、签名 APK；Wrangler 部署 Worker | public repo 可审查，签名及生产凭据只进入受保护的发布任务 |
| 成本 | **本项目采用 Workers 免费档，不付 USD 5/月基础订阅**；D1 使用免费额度 | 接受降低密码哈希成本的代价；10 ms CPU、每日请求与 D1 行读写额度、无 SLA 风险须实测和监控 |

### 0.1 本次严格执行的边界

- 全新代码、UI、领域结构和数据库；不引入 jessie_wish / Flutter / L'amour 代码，不迁移或兼容其数据。
- **不使用 R2。应用与 API 不提供图片、照片、头像、自定义图标文件、附件或任何文件上传入口。**
- 地址仅为纯文本；没有经纬度、地图、地理编码、定位、地图跳转。
- 没有社交、分享链接、公共餐厅页、多用户协作或跨账号查询。
- 菜品始终属于餐厅，数据库和请求都没有 visit_id / dish_visit 关系。
- 评分为**可空的 1.0–5.0 评分，精确到 0.1**。API 的 null 表示未评分，0 不是有效分数。餐厅评分不由菜品或到店次数自动计算。
- 生产代码、建仓库、创建云资源、写入 secret、购买套餐和部署，都不是本轮执行动作。本文中的 SQL、JSON、配置与 CI 示例仅用于方案审查。
- GitHub Releases 中的构建 APK 是软件分发产物，与应用业务的“文件上传”功能无关；业务端始终仅接受 JSON。

### 0.2 已知环境与命名

| 项目 | 采用值 |
| --- | --- |
| Cloudflare Account ID | 49eee8b8033579863adb3b7cf0b1852c |
| 托管主域名 | alanvampuke.top |
| 已有 NS | alex.ns.cloudflare.com / ali.ns.cloudflare.com；不更改 |
| 唯一生产 API 主机 | dining.alanvampuke.top |
| GitHub 所有者 | vampuke；已有 token 权限为 repo + workflow |
| 建议仓库 | https://github.com/vampuke/dining-log；**此链接表示目标位置，本轮不创建仓库** |
| 建议应用 applicationId | top.alanvampuke.dininglog |
| 建议 Worker / D1 名称 | dining-log-api / dining-log-prod |
| 本地及 CI | Wrangler 本地模拟 + 独立测试数据库；不共用生产数据 |

## 1. 产品定位与信息架构

### 1.1 解决的问题

让用户回答四个问题：“我在哪里吃过？”“哪道菜值得再点？”“什么时候去过？”“为什么我后来改了评分？”

餐厅是可持续更新的个人档案；菜品是这家餐厅的长期菜单笔记；visit 只描述一次到店。用户改变主观判断时，旧值仍可在修改记录中找到。

### 1.2 首版核心规则

1. 餐厅名称和类型必填，文本地址选填；主要菜系、评分、评价、备注可选。
2. 类型使用固定代码，菜系可自行输入一个主要菜系文本。首版不增加分类管理系统。
3. 「评价」记录体验判断；「备注」记录备忘，例如营业时间、排队提醒。两者不是同一个字段。
4. 菜品名称必填，可独立评分、评价；不要求对应某次到店。
5. visit 的就餐日期必填；可选餐别和当次备注。同一餐厅、同一天允许多条到店记录，不设日期唯一约束。
6. 餐厅可以先建档、以后补到店日期。没有 visit 时显示“尚未记录到店”，不编造日期。
7. 日期以用户输入的餐厅当地日历日期为准，存 YYYY-MM-DD。创建、修改时间另存 UTC 时刻。
8. 不记录具体用餐时刻，因此无需时区字段；日期不会因设备时区切换而改变。未来日期在 UI 提醒核对，服务端只验证格式与有效日期，不擅自改成今天。
9. 编辑页点击“保存”才提交；未保存输入属于草稿，不写历史。无有效变化的保存不递增业务版本、不产生 revision。
10. 删除餐厅会使它的菜品和到店记录一起不可访问，但不批量修改子记录的删除标记；30 天内可以恢复。已单独删除的子记录不会随父餐厅恢复而复活。

### 1.3 页面地图

~~~mermaid
flowchart TD
    Launch[Startup and session restore] --> Auth[Login / Register / Recover]
    Launch --> Restaurants[Restaurant list]
    Auth --> Restaurants
    Restaurants --> Search[Search and filters]
    Restaurants --> RestaurantEditor[Create / Edit restaurant]
    Restaurants --> RestaurantDetail[Restaurant detail]
    RestaurantDetail --> Overview[Address / Rating / Review / Notes]
    RestaurantDetail --> DishList[Restaurant dishes /restaurants/{id}/dishes]
    DishList --> DishEditor[Create / Edit dish]
    DishList --> DishDetail[Dish detail]
    RestaurantDetail --> VisitList[Visit history]
    VisitList --> VisitEditor[Create / Edit dining date]
    RestaurantDetail --> History[Restaurant and child revisions]
    DishDetail --> History
    History --> DiffDetail[Field before to after]
    DiffDetail --> Reapply[Reapply selected historical fields]
    Restaurants --> Timeline[Visit timeline]
    Restaurants --> Me[Account]
    Me --> Security[Change password / Recovery codes]
    Me --> Sessions[Device sessions / Logout]
    Me --> Drafts[Unsubmitted drafts]
    Me --> Trash[Recently deleted / Restore]
~~~ 

底部导航固定为「餐厅」「到店」「我的」。没有“菜品”独立一级页；菜品是餐厅详情下的二级页，路由为 `/restaurants/{id}/dishes`。菜品的新增、编辑、删除入口均为“餐厅详情 → 这家店的菜”，不做全局菜品列表页。餐厅列表顶栏提供搜索，主按钮为“记一家餐厅”；详情按概览、菜品、到店分区，修改记录用顶栏或区块入口进入。

| 页面 | 内容与操作 | 必须具备的状态 |
| --- | --- | --- |
| 登录 / 注册 | 用户名、密码、显示名；密码显示开关；恢复账号入口 | 输入错误、凭据错误、用户名占用、限流倒计时、网络失败 |
| 首次恢复码展示 | 一次性显示恢复码，提醒保存；确认后进入主页 | 不自动写剪贴板；截图是否允许由用户主动选择，默认敏感窗口保护 |
| 餐厅列表 | 名称、类型、主要菜系、评分、地址摘要、最近到店日期 | 初始加载、分页加载、真正无数据、筛选无结果、缓存、失败 |
| 搜索 / 筛选 | 关键词、类型、菜系、评分范围、是否已到店、到店日期范围 | 清空条件；空结果；离线时明确只搜索已缓存范围 |
| 餐厅详情 | 文本地址、总体评分、评价、备注；菜品 / 到店数量与入口 | 无评分、无菜品、无到店、读取缓存、记录已删除 |
| 餐厅内菜品页 / 详情 | `/restaurants/{id}/dishes`；名称、评分、评价、所属餐厅、修改记录；新增 / 编辑 / 删除均在本页完成 | 标明“属于该餐厅”；没有全局菜品列表或选择某次 visit 的控件 |
| 到店时间线 | 日期、餐别、餐厅名称、当次备注摘要 | 同日多次分别显示；按日期分页；日期区间筛选 |
| 编辑页 | 明确的字段标签、长度提示、评分控件、保存与草稿提示 | 保存中防重复；字段错误；断网保存草稿；409 冲突比较 |
| 修改记录列表 | 相对时间、绝对时间、对象、动作、变更字段数与摘要 | 初始创建、更新、删除、恢复均可识别；没有空 diff |
| diff 详情 | 同一次保存的各字段旧值、新值；长文本展开；可加说明 | null 显示“未填写”；删除 / 恢复有文字标识；原始值不可改写 |
| 我的 | 当前账号、设备会话、修改密码、恢复码、草稿、最近删除、注销 | 401 要求登录；退出需清理本地账号数据；注销说明 30 天清理期 |
| 最近删除 | 删除时间、剩余恢复期；餐厅 / 菜品 / 到店 / 隐藏历史分组 | 父餐厅须先恢复；过期不可恢复；历史隐藏不等同于擦除原始 diff |

### 1.4 关键交互

- 新增餐厅可以同时记录一次首访日期，服务端在同一事务中创建餐厅与 visit；也可以只创建档案。
- 评分控件显示星形图标、`4.3 / 5.0`、10 格刻度与 0.1 步进滑杆，另有“未评分”选项；不只靠颜色表示分数。
- 保存失败保留原输入；网络失败不会把草稿显示为已同步。
- 并发冲突展示“服务器当前值 / 我的草稿”，用户选择后重新提交新版本，不能静默覆盖。
- 软删除餐厅前显示影响的菜品、到店数量和恢复期限。
- 重新应用历史值时，展示当前值与拟修改值，由用户点击保存；生成一条新的正常修改记录。

## 2. 完全新的 UI 视觉方向

### 2.1 最终方向：D2「Quiet Grid」

此前在约 40 行改动前提出的 A「青瓷菜单」、B「石墨食单」、C「深海蓝档案」三个候选全部作废。视觉决策已经由用户确认，最终采用 D2「Quiet Grid」；其气质是精密但不冷漠的记录工具，信息严格落在行列中，数字快速可比，装饰退到最低。实现参考 Linear 的 whisper 级 1px 边框、紧凑控件、4px 小圆角、单一状态色和明确 tab 指示，以及 Notion 的高密度文档式层级和无衬线正文。

不引入位图插画或图片生成。品牌字标、类型图标使用新的矢量资源；图标不是餐厅真实照片，也不暗示上传能力。

### 2.2 D2 完整设计 token

| Token | Light | Dark |
| --- | --- | --- |
| `--bg` | `#fbfbfa` | `#0f0f0e` |
| `--surface` | `#ffffff` | `#191918` |
| `--ink` | `#181916` | `#f2f2ec` |
| `--secondary` | `#555650` | `#b8bab0` |
| `--tertiary` | `#6b6a66` | `#a1a398` |
| `--border` | `rgba(0, 0, 0, 0.08)` | `rgba(255, 255, 255, 0.18)` |
| `--accent` | `#2f6b4f` | `#6fbf95` |
| `--on-accent` | `#ffffff` | `#0f0f0e` |
| `--danger` | `#922f29` | `#f0a099` |
| `--accent-ink` | `#23543c` | `#6fbf95` |
| `--cta` | `#23543c` | `#6fbf95` |
| `--accent-soft` | `#e8f0ea` | `#1b2e23` |
| `--border-strong` | `#a3a59c` | `#686c60` |
| `--gallery` | `#f0f0ed` | `#080808` |
| `--sans` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` |
| `--display` | `var(--sans)` | `var(--sans)` |
| `--mono` | `"JetBrains Mono", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` | `"JetBrains Mono", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` |
| `--edge` | `24px` | `24px` |
| `--radius` | `4px` | `4px` |
| `--button-radius` | `6px` | `6px` |

未单列行高时继承 1.5；正文基准为 14px/1.5，产品主体收敛在 15/13/11px 三档。日期、计数和评分启用等宽数字与 tabular nums。本轮将详情主评分从原 D2 的 29px 提升为明确要求的 36px，其余字号阶梯完整保留：

| 字号 | 行高 | 字重 | 主要用途 |
| ---: | --- | --- | --- |
| 9px | 13.5px | 500 | 列表、菜品与 diff 评分后的“分” |
| 10px | 15px | 500 | 修改预览评分后的“分” |
| 11px | 14 / 15 / 16 / 16.5 / 18 / 20px | 400–600 | 状态、caption、日期、tab、计数、脚注 |
| 12px | 16 / 18 / 19 / 21.6px | 400–600 | 元信息、筛选、类型、说明 |
| 13px | 19.5 / 20 / 20.8 / 22.75px | 400–600 | 搜索、链接卡、菜评、表单输入与选项 |
| 14px | 21 / 22 / 25.2px | 400 / 600 | 正文基准、按钮、详情类型、diff / 到店正文 |
| 15px | 21 / 22.5 / 23 / 28.5px | 400 / 600 / 700 | 餐厅名、章节标题、菜名、历史标题、长评、评分按钮 |
| 16px | 19.2px（1.2） | 400 | 菜品评分徽章 |
| 18px | 27px | 500 | 列表评分、到店日期标题 |
| 25px | 33.75px（1.35） | 700 | 通用、详情、菜品、历史、表单标题 |
| 26px | 35.1px（1.35） | 700 | 首页标题 |
| 27px | 37.8px（1.4） | 400 | diff 数值 |
| 28px | 42px | 400 | 修改前后预览数值 |
| 29px | 31.9px（1.1） | 500 | D2 原始详情评分基线；本轮实际使用 36px |

画板基准为 393×852px，水平边距 24px；28px 状态栏、12px 内容顶距、40px 工具栏、68px 底部导航。首页标题区 42px；搜索框高 36px、上距 8px；筛选项间隔 5px、上距 8px；列表统计上距 7px；餐厅行基线 72px，清空搜索后的完整 8 行状态压缩为 69px 以容纳新增搜索范围提示，评分列始终固定 44px。详情信息盒内边距 13×14px；双列入口间隔 10px、高 64px；菜品原始基线行高 66px，本轮为同时展示 8 行和第二行展开编辑态，将常规行压缩为 44px；大段间距主要使用 18/19/22/24px。

评分滑杆轨道高 4px、滑钮 24px，`min=1 max=5 step=0.1`；已填轨道和滑钮使用 `--accent`。详情与编辑页刻度条均为宽 120px、高 3px、10 段、gap 2px；填充段数为 `round(score × 2)`。常规面板和输入圆角 4px，按钮 6px，评分数值高亮 3px，避免胶囊形；不使用阴影，只用 8% 黑或 18% 白发丝边线区分层级。颜色状态与 Toast 使用 `140ms ease-out`，平滑定位尊重 `prefers-reduced-motion`。

Python 按 WCAG 2.x 相对亮度公式实测：

| 主题 | 色对 | 对比度 | AA | AAA |
| --- | --- | ---: | :---: | :---: |
| Light | `ink/bg` | 17.05:1 | 是 | 是 |
| Light | `secondary/bg` | 7.16:1 | 是 | 是 |
| Light | `tertiary/bg` | 5.23:1 | 是 | 否 |
| Light | `on-accent/accent` | 6.29:1 | 是 | 否 |
| Dark | `ink/bg` | 17.07:1 | 是 | 是 |
| Dark | `secondary/bg` | 9.76:1 | 是 | 是 |
| Dark | `tertiary/bg` | 7.50:1 | 是 | 是 |
| Dark | `on-accent/accent` | 8.73:1 | 是 | 是 |

### 2.3 没有图片时如何避免空

餐厅列表不使用卡片和图片占位，而是 1px 发丝线条目：左侧依次放店名、菜系 / 地址 / 菜数、最近到店，右侧使用固定 44px 评分列。评分以 12px 实心星形图标加 18px 等宽十分位数字（如 `★ 4.3`）表达，便于纵向比较。详情顶部使用名称、菜系、地址、评价和笔记；评分使用星形图标、`4.3 / 5.0` 与 10 格微型计量条，不设置虚构封面。

- 餐厅名称是一级信息，菜系、地址、日期为次级；搜索命中菜品或评价时在餐厅行下明确标出命中字段。
- 菜品是餐厅详情下的二级页，常规行使用菜名、短评、`★ 4.3` 和更多操作；展开态原位显示编辑字段、评分滑杆、保存与删除。
- 日期时间线使用等宽日期和餐别文字，不使用照片占位。
- 空态使用一句具体说明和创建按钮；没有图片轮播、图库入口或“上传封面”提示。
- diff 用字段标签和“原值 → 新值”，评分显示时将十分位整数除以 10；较长文字上下对照，删除值同时显示“原值”标识。

**需验证 UI-01**：P0 继续用深色截图、200% 字体、TalkBack 和 API 35 真机复核；D2 原型的当前对比度数字是基线，不替代 Android 最终渲染实测。

## 3. 领域模型、完整 D1 SQL 与删除策略

### 3.1 通用约定

- 业务 ID 为 UUID v4 文本，客户端可为草稿预生成；服务端严格验证格式。user / session / revision / change_set ID 由服务端生成。
- 所有系统时刻在 D1 中为 UTC Unix 毫秒 INTEGER；API 输出 ISO 8601 UTC，例如 2026-10-31T08:30:00.000Z。
- dining_date 是 YYYY-MM-DD 文本，与系统时刻分开。
- 业务 version 从 1 开始，每次有效 create / update / delete / restore 顺序增长；创建前版本为 0。
- 请求不能提供 user_id、系统时间、历史 diff、业务版本的目标值、密码哈希或内部搜索影子字段。
- 可选短文本空白规范为 null；名称、类型必填，地址选填。业务文本保存时仅 NFC、首尾去空白、CRLF 转 LF，不压缩用户正文中间的换行。
- 密码完全保留输入，不 trim、不大小写转换、不 Unicode 归一化。
- 搜索索引文本另行做 NFKC、lowercase、空白规整，保留显示文本原样；餐厅聚合搜索使用 `restaurant_search.content`。
- D1 不用 REAL 存评分，而用 `rating_tenths INTEGER` 存十分位：1.0 存 10、4.3 存 43，NULL 表示未评分。REAL 在 JSON 往返和比较中会引入 `4.300000000000001` 一类噪声；十分位整数可以精确比较。API 层仍输出 `4.3` 数字，由服务端执行 `tenths / 10` 转换。
- restaurants.cuisine_normalized 同样由服务端生成，用于菜系精确筛选；D1 不能对保存的显示文本执行完整 NFKC 转换。
- 文本长度按 Unicode 码点计算，与 SQLite length(TEXT) 对齐；TypeScript / Kotlin 不直接把 UTF-16 code unit 数当字数，UTF-8 字节限制另行检查。
- 软删除字段采用 deleted_at。业务删除与历史隐藏使用同名字段，但含义在 API 和 UI 中明确区分。
- 首版不做 ORM；迁移 SQL 为数据库结构权威，TypeScript Repository 封装参数化查询。

### 3.2 表职责

| 表 | 职责 |
| --- | --- |
| users | 独立账号、显示名、慢哈希参数、pepper 版本、全局鉴权版本、注销状态 |
| sessions | 每次登录的一组设备会话，刷新家族、绝对到期时间、撤销状态 |
| refresh_tokens | 每代 refresh token 的哈希、父代、消费信息；检测重复使用 |
| refresh_rotation_results | 120 秒内重试需要的加密刷新结果，解决响应丢失问题 |
| recovery_codes | 一次性恢复码的哈希；消费或更新后失效 |
| auth_rate_limits | 全局账号 / IP 哈希维度的短期限流窗口；不存原始 IP |
| restaurants | 当前餐厅档案：名称、类型、菜系、文本地址、评分、评价、备注 |
| dishes | 餐厅级菜品库：名称、评分、评价；没有到店外键 |
| restaurant_search | 每家餐厅一行的反规范化聚合搜索文本；包含餐厅字段及其有效菜品名称、短评 |
| visits | 多次到店的日期、餐别、当次备注 |
| change_sets | 一次保存的事务分组；新建餐厅与首访可共享一个分组 |
| revisions | 不可改写的字段 diff、目标版本及可编辑说明、隐藏状态 |
| idempotency_keys | 业务请求指纹及提交结果，保留 7 天；不存密码或鉴权响应 |
| mutation_guards | D1 batch 中的受影响行数断言；成功事务结束时为空 |

~~~mermaid
erDiagram
    users ||--o{ sessions : owns
    sessions ||--o{ refresh_tokens : contains
    refresh_tokens ||--o| refresh_rotation_results : retries
    users ||--o{ recovery_codes : owns
    users ||--o{ restaurants : owns
    restaurants ||--o{ dishes : contains
    restaurants ||--o{ visits : records
    users ||--o{ change_sets : saves
    change_sets ||--o{ revisions : groups
    restaurants ||--o{ revisions : anchors
    dishes o|--o{ revisions : target
    visits o|--o{ revisions : target
    users ||--o{ idempotency_keys : deduplicates
~~~

### 3.3 完整首个迁移 DDL

以下 SQL 是审批后的首个迁移设计，使用 SQLite 方言，不含 PostgreSQL 类型或 ALTER COLUMN。

P6 将文本地址改为选填，通过新迁移 `0002_address_optional.sql` 应用；本节保留冻结的 0001 原始 DDL。重建 restaurants 时必须在同一事务中备份并恢复 dishes、visits、restaurant_search、revisions，重建原有五个索引，并通过逐表行数/校验和验证。

D1 默认强制开启外键，相当于每个事务 PRAGMA foreign_keys = ON。下方 pragma 用于本地 SQLite 校验；在 D1 不依靠它切换外键状态，也不尝试将外键关闭。

~~~sql
PRAGMA foreign_keys = ON;

CREATE TABLE users (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    username TEXT NOT NULL UNIQUE CHECK (
        length(username) BETWEEN 3 AND 32
        AND username = lower(username)
        AND username NOT GLOB '*[^a-z0-9_]*'
        AND substr(username, 1, 1) GLOB '[a-z]'
    ),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 50),
    password_algorithm TEXT NOT NULL
        CHECK (password_algorithm = 'pbkdf2-sha256+hmac-sha256-v1'),
    password_hash TEXT NOT NULL CHECK (length(password_hash) = 43),
    password_salt TEXT NOT NULL CHECK (length(password_salt) = 22),
    password_iterations INTEGER NOT NULL DEFAULT 10000 CHECK (password_iterations >= 10000),
    pepper_version TEXT NOT NULL,
    password_changed_at INTEGER NOT NULL,
    auth_version INTEGER NOT NULL DEFAULT 1 CHECK (auth_version >= 1),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE INDEX idx_users_deleted ON users(deleted_at)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE sessions (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_version INTEGER NOT NULL CHECK (auth_version >= 1),
    device_name TEXT NOT NULL CHECK (length(device_name) BETWEEN 1 AND 80),
    created_at INTEGER NOT NULL,
    last_refreshed_at INTEGER NOT NULL CHECK (last_refreshed_at >= created_at),
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    revoked_at INTEGER,
    revoke_reason TEXT CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'logout', 'logout_all', 'password_change', 'recovery',
            'token_reuse', 'user_revoke', 'account_delete', 'expired'
        )
    ),
    UNIQUE (user_id, id)
);
CREATE INDEX idx_sessions_user ON sessions(user_id, created_at DESC, id DESC);
CREATE INDEX idx_sessions_expiry ON sessions(expires_at);
CREATE INDEX idx_sessions_active ON sessions(user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
    parent_token_id TEXT CHECK (
        parent_token_id IS NULL OR length(parent_token_id) = 36
    ),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    consumed_at INTEGER,
    consumed_request_id TEXT CHECK (
        consumed_request_id IS NULL OR length(consumed_request_id) = 36
    ),
    UNIQUE (user_id, id),
    FOREIGN KEY (user_id, session_id)
        REFERENCES sessions(user_id, id) ON DELETE CASCADE,
    CHECK (
        (consumed_at IS NULL AND consumed_request_id IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_request_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX idx_refresh_one_child ON refresh_tokens(parent_token_id)
    WHERE parent_token_id IS NOT NULL;
CREATE INDEX idx_refresh_session ON refresh_tokens(session_id, created_at, id);

CREATE TABLE refresh_rotation_results (
    old_token_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    request_id TEXT NOT NULL CHECK (length(request_id) = 36),
    encryption_key_version TEXT NOT NULL,
    nonce TEXT NOT NULL CHECK (length(nonce) = 16),
    ciphertext TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    FOREIGN KEY (user_id, old_token_id)
        REFERENCES refresh_tokens(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_results_expiry ON refresh_rotation_results(expires_at);

CREATE TABLE recovery_codes (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL UNIQUE CHECK (length(code_hash) = 64),
    created_at INTEGER NOT NULL,
    used_at INTEGER,
    disabled_at INTEGER
);
CREATE INDEX idx_recovery_user ON recovery_codes(user_id);
CREATE INDEX idx_recovery_active ON recovery_codes(user_id, code_hash)
    WHERE used_at IS NULL AND disabled_at IS NULL;

CREATE TABLE auth_rate_limits (
    scope TEXT NOT NULL CHECK (scope IN ('login_account', 'login_ip', 'register_ip', 'recovery_account', 'recovery_ip')),
    key_hash TEXT NOT NULL CHECK (length(key_hash) = 64),
    window_start INTEGER NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts >= 1),
    expires_at INTEGER NOT NULL CHECK (expires_at > window_start),
    PRIMARY KEY (scope, key_hash, window_start)
);
CREATE INDEX idx_auth_rate_limits_expiry ON auth_rate_limits(expires_at);

CREATE TABLE restaurants (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    category TEXT NOT NULL CHECK (
        category IN ('restaurant', 'cafe', 'bakery', 'bar', 'street_food', 'other')
    ),
    cuisine TEXT CHECK (cuisine IS NULL OR length(cuisine) BETWEEN 1 AND 60),
    cuisine_normalized TEXT,
    address TEXT NOT NULL CHECK (length(trim(address)) BETWEEN 1 AND 500),
    rating_tenths INTEGER CHECK (
        rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        )
    ),
    review TEXT CHECK (review IS NULL OR length(review) <= 4000),
    note TEXT CHECK (note IS NULL OR length(note) <= 4000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    CHECK (
        (cuisine IS NULL AND cuisine_normalized IS NULL)
        OR (cuisine IS NOT NULL AND cuisine_normalized IS NOT NULL AND length(cuisine_normalized) > 0)
    )
);
CREATE INDEX idx_restaurants_live_updated ON restaurants(user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_category ON restaurants(user_id, category, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_cuisine ON restaurants(user_id, cuisine_normalized, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_live_rating ON restaurants(user_id, rating_tenths DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_restaurants_deleted ON restaurants(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE dishes (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    rating_tenths INTEGER CHECK (
        rating_tenths IS NULL OR (
            typeof(rating_tenths) = 'integer' AND rating_tenths BETWEEN 10 AND 50
        )
    ),
    review TEXT CHECK (review IS NULL OR length(review) <= 2000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    UNIQUE (user_id, restaurant_id, id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_dishes_restaurant_live ON dishes(user_id, restaurant_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_user_live ON dishes(user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_restaurant_rating ON dishes(user_id, restaurant_id, rating_tenths DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_dishes_deleted ON dishes(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE restaurant_search (
    restaurant_id TEXT PRIMARY KEY CHECK (length(restaurant_id) = 36),
    user_id TEXT NOT NULL,
    content TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (user_id, restaurant_id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_restaurant_search_user ON restaurant_search(user_id, updated_at DESC, restaurant_id);

CREATE TABLE visits (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    dining_date TEXT NOT NULL CHECK (
        length(dining_date) = 10
        AND dining_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        AND dining_date BETWEEN '1900-01-01' AND '2100-12-31'
        AND date(dining_date, '+0 days') IS NOT NULL
        AND date(dining_date, '+0 days') = dining_date
    ),
    meal TEXT CHECK (meal IS NULL OR meal IN ('breakfast', 'lunch', 'dinner', 'other')),
    note TEXT CHECK (note IS NULL OR length(note) <= 2000),
    search_text TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    UNIQUE (user_id, restaurant_id, id),
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_visits_restaurant_date ON visits(user_id, restaurant_id, dining_date DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_visits_user_date ON visits(user_id, dining_date DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_visits_deleted ON visits(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE change_sets (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL CHECK (length(request_id) = 36),
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, id),
    UNIQUE (user_id, request_id)
);
CREATE INDEX idx_change_sets_time ON change_sets(user_id, created_at DESC, id DESC);

CREATE TABLE revisions (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    change_set_id TEXT NOT NULL,
    restaurant_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('restaurant', 'dish', 'visit')),
    entity_id TEXT NOT NULL CHECK (length(entity_id) = 36),
    dish_id TEXT,
    visit_id TEXT,
    action TEXT NOT NULL CHECK (action IN ('create', 'update', 'delete', 'restore')),
    entity_version_before INTEGER NOT NULL CHECK (entity_version_before >= 0),
    entity_version_after INTEGER NOT NULL
        CHECK (entity_version_after = entity_version_before + 1),
    diff_json TEXT NOT NULL CHECK (
        json_valid(diff_json)
        AND json_type(diff_json) = 'array'
        AND json_array_length(diff_json) > 0
        AND length(CAST(diff_json AS BLOB)) <= 131072
    ),
    source_revision_id TEXT CHECK (
        source_revision_id IS NULL OR length(source_revision_id) = 36
    ),
    search_text TEXT NOT NULL,
    note TEXT CHECK (note IS NULL OR length(note) <= 500),
    note_search TEXT NOT NULL DEFAULT '',
    metadata_version INTEGER NOT NULL DEFAULT 1 CHECK (metadata_version >= 1),
    metadata_updated_at INTEGER NOT NULL,
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    UNIQUE (user_id, entity_type, entity_id, entity_version_after),
    FOREIGN KEY (user_id, change_set_id)
        REFERENCES change_sets(user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id)
        REFERENCES restaurants(user_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id, dish_id)
        REFERENCES dishes(user_id, restaurant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (user_id, restaurant_id, visit_id)
        REFERENCES visits(user_id, restaurant_id, id) ON DELETE CASCADE,
    CHECK (
        (entity_type = 'restaurant' AND entity_id = restaurant_id AND dish_id IS NULL AND visit_id IS NULL)
        OR (entity_type = 'dish' AND dish_id IS NOT NULL AND entity_id = dish_id AND visit_id IS NULL)
        OR (entity_type = 'visit' AND visit_id IS NOT NULL AND entity_id = visit_id AND dish_id IS NULL)
    ),
    CHECK (
        (action = 'create' AND entity_version_before = 0)
        OR (action <> 'create' AND entity_version_before >= 1)
    ),
    CHECK (metadata_updated_at >= created_at),
    CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE INDEX idx_revisions_restaurant_time ON revisions(user_id, restaurant_id, created_at DESC, id DESC);
CREATE INDEX idx_revisions_entity_time ON revisions(user_id, entity_type, entity_id, created_at DESC, id DESC);
CREATE INDEX idx_revisions_visible_time ON revisions(user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_revisions_hidden_time ON revisions(user_id, deleted_at DESC, id DESC)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_revisions_change_set ON revisions(user_id, change_set_id);

CREATE TRIGGER trg_revisions_core_immutable
BEFORE UPDATE ON revisions
WHEN NEW.id IS NOT OLD.id
    OR NEW.user_id IS NOT OLD.user_id
    OR NEW.change_set_id IS NOT OLD.change_set_id
    OR NEW.restaurant_id IS NOT OLD.restaurant_id
    OR NEW.entity_type IS NOT OLD.entity_type
    OR NEW.entity_id IS NOT OLD.entity_id
    OR NEW.dish_id IS NOT OLD.dish_id
    OR NEW.visit_id IS NOT OLD.visit_id
    OR NEW.action IS NOT OLD.action
    OR NEW.entity_version_before IS NOT OLD.entity_version_before
    OR NEW.entity_version_after IS NOT OLD.entity_version_after
    OR NEW.diff_json IS NOT OLD.diff_json
    OR NEW.source_revision_id IS NOT OLD.source_revision_id
    OR NEW.search_text IS NOT OLD.search_text
    OR NEW.created_at IS NOT OLD.created_at
BEGIN
    SELECT RAISE(ABORT, 'revision core is immutable');
END;

CREATE TABLE idempotency_keys (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) = 36),
    method TEXT NOT NULL CHECK (method IN ('POST', 'PATCH', 'DELETE')),
    path TEXT NOT NULL,
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 64),
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_json TEXT NOT NULL CHECK (json_valid(response_json)),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys(expires_at);

CREATE TABLE mutation_guards (
    operation_id TEXT PRIMARY KEY,
    affected_rows INTEGER NOT NULL CHECK (affected_rows = 1)
);
~~~

### 3.4 关联、完整性与软删除

**拥有者隔离**

所有读写 SQL 从已验证会话得到 user_id，并把它作为 WHERE / INSERT 绑定参数。D1 没有可依赖的行级权限，不能靠“UUID 难猜”代替授权。

组合外键阻止甲用户的 dish / visit / revision 引用乙用户餐厅；history 的具体目标用 dish_id / visit_id 加 CHECK 和外键表示，避免只有一个无法约束的多态 entity_id。

业务 API 不支持更换 user_id、restaurant_id 或实体类型。菜品建错餐厅时新建正确条目并删除旧条目，首版不实现跨餐厅移动。

refresh_tokens.parent_token_id 与 revisions.source_revision_id 是**只用于追踪的逻辑引用**：服务端写入时验证归属，不设自引用级联外键。这样既可分批清理，又避免 30 天高频刷新产生数千代父链后触及 SQLite 级联递归深度。refresh token 的真正安全归属由组合 session 外键约束。

**删除与恢复**

1. 对业务实体删除时只更新自身 deleted_at、updated_at、version，并记录一个 delete diff。
2. 删除餐厅不把所有子对象标为删除；每个子对象的读取、搜索、修改都必须 JOIN / EXISTS 检查父餐厅有效。
3. 恢复餐厅只恢复父行；之前单独删除的菜品或 visit 保持删除状态。
4. 恢复菜品 / visit 要求所属餐厅已恢复，且该实体仍在自己的 30 天窗口内。
5. 恢复窗口判定为 now < deleted_at + 30 days；到期即返回 410，即使物理清理尚未执行。
6. revision 的 DELETE 是隐藏：更新自身 deleted_at，不更改 diff。隐藏可恢复，原始 diff 只随目标永久清理或账号注销永久清理。
7. 注销账号立即设置 users.deleted_at、递增 auth_version 并撤销全部会话；30 天后清理。首版不提供注销撤回或注销期间登录。
8. 用户名在账号存续和注销保留期内占用；物理清理后可重新注册，但新 user_id 不继承原数据。

**维护与永久清理**

- 每小时 Cron 有预算地删除过期幂等结果、刷新重试密文、限流窗口、过期会话及已失效恢复码。
- consumed refresh token 保留至整个会话到期或撤销后的清理，不按单代 expires_at 提前删掉重放证据。
- 软删除到期的业务数据按“相关 revisions → 子对象 → 父对象 → 空 change_sets”分块清理。
- 账号数据按同样顺序及鉴权表分块删除，最终删除 users。最终外键 CASCADE 是兜底，不能一次触发大量级联代替分块。
- 每轮最多处理约 100 个待删主键，单条语句以 20 个 ID 为起点，使用子查询 LIMIT 或固定占位符；不依赖 DELETE ... LIMIT 编译选项。
- 不需要存储清理队列或 R2 任务。deleted_at 与尚存行本身就是可重复扫描的清理进度；任务中断后下次继续。
- 清理前再次验证过期条件；删除已过期不可恢复对象后，相关逻辑 source_revision_id 可保留为历史来源 ID，不要求来源永久存在。
- 定期删除没有 revisions 的 change_sets；永久清理也删除该目标相关幂等响应，避免响应快照在清理后继续保留敏感正文。

### 3.5 D1 限制与迁移方式

| 限制 / 易错点 | 本方案处理 |
| --- | --- |
| SQLite 没有通用 ALTER COLUMN | 加字段用 ADD COLUMN；修改类型、约束用 expand/contract 或建新表复制；重建索引、触发器并演练 |
| D1 默认强制外键 | 不用 PRAGMA foreign_keys = OFF；需要临时调整关系时仅用 defer_foreign_keys 延迟到同一事务结束 |
| deferred 不等于关闭 | 事务结束前必须修复全部引用；有 CASCADE 的父表替换必须特别演练，不能直接 DROP 父表造成误删 |
| 单库串行查询 / 单写入通道 | 缩短查询与事务；不在事务中执行密码哈希、外部网络请求或巨大清理 |
| Workers 中没有本方案可用的交互式数据库事务 | 使用 D1 batch() 原子提交；不拼 BEGIN / COMMIT 维持跨 await 或跨请求事务 |
| 单 SQL 最多 100 个绑定参数 | 分块及静态模板；预留 owner、版本、时间等占位符数量 |
| 单语句最多 100 KB；行 / 字符串约 2 MB | 业务 JSON 限 64 KiB；单份 diff 限 128 KiB；列表不返回完整历史正文 |
| LIKE / GLOB pattern 当前最多 50 bytes | 搜索用参数化 `instr(restaurant_search.content, ?)`，长中文不塞进 LIKE；DDL 的短 GLOB 校验仍适用 |
| SQLite lower() 不是完整 Unicode 大小写归一化 | 服务端生成 `restaurant_search.content` 及 cuisine_normalized，后者配索引支持规范化菜系精确筛选 |
| 普通索引不能加速任意子串匹配 | 先以 user_id / restaurant_id 索引缩小集合；首版不假装 instr 是全文索引 |
| FTS5 不等于中文分词 | 暂不启用；规模超出首版预算时再验证 tokenizer / trigram，不先增加依赖 |
| D1 调用次数 | 官方现行说明 batch 计一次调用，但每条语句自身的限制仍生效；设计仍控制每请求约 2～6 次 D1 往返 |
| 读副本一致性 | 首版使用主库语义，不主动启用读复制；如以后开启，采用 D1 Sessions / bookmark 实现写后读可见 |

每次迁移运行 foreign_key_check、quick_check，并用 EXPLAIN QUERY PLAN 检查主要查询。不能把本地 SQLite 通过当成真实 D1 通过。

**需验证 DB-01**：本 DDL 的 JSON CHECK、部分索引、日期归一化、组合外键、不可变触发器与 changes() 守卫，在 Wrangler 本地及审批后的真实 D1 临时测试数据中分别执行；测试结束删除测试数据。验证矩阵见第 12 节。

### 3.6 餐厅聚合搜索索引

首页搜索范围固定为：餐厅名称、类型、菜系、地址、餐厅评价、餐厅备注、菜品名称、菜品短评。`restaurant_search` 是反规范化索引表，每个有效餐厅只有一行；`content` 将上述字段按带类型标记的片段聚合，统一执行 NFKC、lowercase 与空白规整。查询先用 `user_id` 限定账号，再用参数化 `instr(content, ?)` 做各关键词 AND 匹配，不在查询时 JOIN dishes 扫描全表，也不依赖 FTS5 中文分词。

餐厅自身或任一菜品 create / update / delete / restore 发生有效变化时，服务端在同一个 D1 batch 内重建该餐厅的聚合文本并更新 `restaurant_search.updated_at`；删除餐厅由外键级联移除索引行。带类型标记的片段让服务端在命中行内判断 `restaurant / dish / review / note` 命中类型，并随结果返回 `matched_fields` 与可安全展示的 `match_label`，供 UI 显示“菜品『辣子鸡』匹配”或“评价中含『辣子鸡』”。

本表只保存规范化后的搜索影子文本，不替代业务正文，也不写入 revision。批次中业务写入、revision、索引重建和幂等结果必须共同成功或共同回滚。


## 4. API 设计：完整契约、CRUD 与搜索

### 4.1 协议约定

- 唯一 base URL：https://dining.alanvampuke.top/api/v1。
- 业务请求只接受 application/json；拒绝 multipart/form-data 和 application/octet-stream。请求体最多 64 KiB，读取时计实际字节，不能只相信 Content-Length。
- 公共鉴权接口除外，其余接口使用 Authorization: Bearer <access_token>。
- 所有响应使用 UTF-8 JSON；204 无响应体。用户数据与鉴权响应统一 Cache-Control: no-store。
- 字段使用 snake_case。Zod strict schema 拒绝未知字段；null 与字段省略不是同义词：PATCH 省略表示不改，null 表示清空允许为空的字段。
- **并发版本通过请求体 expected_version 表达**。它是“我编辑时看到的版本”，不是写入的新 version。版本不匹配返回 409 VERSION_CONFLICT。
- revision 元数据的并发字段使用 expected_metadata_version，不占用实体 version。
- 所有 restaurant / dish / visit / revision 写入及 PATCH /me 必须携带 UUID v4 的 Idempotency-Key；同一保存的重试复用该 key。
- Auth、安全操作、注销账号不进入业务幂等表，避免存密码或明文鉴权响应。refresh 使用单独的 X-Refresh-Request-Id 和加密短期结果。
- DELETE 业务接口采用带 JSON body 的契约，body 仅含 expected_version / expected_metadata_version；Android 用 Retrofit 的 @HTTP(method = "DELETE", hasBody = true) 明确声明。
- 服务端生成传输追踪 meta.request_id。change_sets.request_id 保存 Idempotency-Key，代表逻辑操作 ID；两者用途不同。
- owner 不在业务 JSON 中返回或接收。服务器所有查询仍必须绑定自己的 user_id。

**成功信封**

~~~json
{
  "data": {
    "id": "2b2e4654-3027-4f62-a850-9e39eefc3d1a",
    "name": "Lane Sichuan Bistro",
    "version": 7
  },
  "meta": {
    "request_id": "c3a3c8bc-3a48-4a75-8c2b-d6ad8c7c520c"
  }
}
~~~

**分页信封**

~~~json
{
  "data": {
    "items": []
  },
  "meta": {
    "request_id": "c3a3c8bc-3a48-4a75-8c2b-d6ad8c7c520c",
    "next_cursor": null,
    "has_more": false
  }
}
~~~

默认 limit=20，最大 50；查询 limit+1 判定 has_more，不默认计算全局 total。keyset cursor 包含排序方式、键值、ID、筛选指纹，解码后严格验证；cursor 不是授权凭据，查询仍绑定当前 user_id。不能把 cursor 的内容拼接成 SQL。

updated_desc 按 updated_at DESC, id DESC；rating_desc 在 D1 按 rating_tenths DESC, id DESC，API 仍返回十分位小数，未评分排最后；visit 按 dining_date DESC, id DESC；revision 按 created_at DESC, id DESC。分页不承诺跨多请求的事务快照，客户端按 ID 去重并在下拉刷新时重置 cursor。

**错误信封**

~~~json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "The record has changed. Reload before saving.",
    "field_errors": {},
    "current": {
      "id": "2b2e4654-3027-4f62-a850-9e39eefc3d1a",
      "rating": 4.3,
      "version": 8
    }
  },
  "meta": {
    "request_id": "c3a3c8bc-3a48-4a75-8c2b-d6ad8c7c520c"
  }
}
~~~

message 为稳定的英文兜底，Android 按 code / field_errors 本地化；不依赖字符串匹配做逻辑。

| HTTP | code 示例 | 用途 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / UNKNOWN_FIELD / INVALID_CURSOR / MISSING_VERSION | 格式、字段、日期、评分不合法 |
| 401 | INVALID_CREDENTIALS / TOKEN_EXPIRED / SESSION_REVOKED / REFRESH_REUSED | 凭据或会话失效 |
| 404 | NOT_FOUND / PARENT_UNAVAILABLE | 不存在、不属于当前用户、当前不可访问；不泄露他人对象是否存在 |
| 409 | VERSION_CONFLICT / IDEMPOTENCY_CONFLICT / USERNAME_TAKEN / ID_CONFLICT | 并发、幂等 key 被另一操作复用、注册冲突 |
| 410 | RESTORE_WINDOW_EXPIRED | 自己的已删除对象超过恢复窗口 |
| 413 | PAYLOAD_TOO_LARGE / DIFF_TOO_LARGE | 字节限制 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 非 JSON 请求 |
| 429 | RATE_LIMITED | 返回 Retry-After 秒数 |
| 503 | DATABASE_BUSY / TEMPORARILY_UNAVAILABLE | D1 overloaded 等暂时故障；可按规则重试 |

只有已经确认属于当前用户的对象才能返回 current 或 410；其他用户 ID 一律 404。

### 4.2 请求与响应类型定义

下表定义后续 endpoint 的类型。表格中的 ? 表示可省略，不是字段名的一部分。

| 类型 | 完整字段 |
| --- | --- |
| User | id、username、display_name、version、created_at、updated_at；没有密码、hash、pepper、内部 auth_version |
| AuthResult | user: User、token_type: "Bearer"、access_token、expires_in: 600、refresh_token、refresh_expires_at、session_expires_at、session_id |
| RegisterRequest | username: string、password: string、display_name?: string、device_name: string |
| LoginRequest | username: string、password: string、device_name: string |
| RestaurantInput | name: string、category: enum、address?: string\|null、cuisine?: string\|null、rating?: number\|null（1.0–5.0，最多一位小数）、review?: string\|null、note?: string\|null |
| RestaurantCreate | id: UUID、RestaurantInput 的全部字段、first_visit?: {id: UUID, dining_date: date, meal?: enum\|null, note?: string\|null} |
| RestaurantPatch | expected_version: integer、RestaurantInput 任意至少一个字段；id / first_visit / restaurant_id 不可修改 |
| Restaurant | id、name、category、cuisine、address、rating、review、note、version、created_at、updated_at、deleted_at |
| RestaurantSummary | id、name、category、cuisine、address_excerpt、rating、version、updated_at、dish_count、visit_count、last_visit_date；不带完整 review / note |
| RestaurantDetail | Restaurant 的全部字段及 stats: {dish_count, visit_count, last_visit_date} |
| DishCreate | id: UUID、name: string、rating?: number\|null（1.0–5.0，最多一位小数）、review?: string\|null |
| DishPatch | expected_version: integer、name?、rating?、review?；至少一个可改字段 |
| Dish | id、restaurant_id、name、rating、review、version、created_at、updated_at、deleted_at |
| DishSummary | id、restaurant_id、restaurant_name、name、rating、review_excerpt、version、updated_at |
| RestaurantSearchResult | restaurant: RestaurantSummary、matched_fields: (`restaurant`\|`dish`\|`review`\|`note`)[]、match_label: string\|null |
| VisitCreate | id: UUID、dining_date: date、meal?: enum\|null、note?: string\|null |
| VisitPatch | expected_version: integer、dining_date?、meal?、note?；至少一个可改字段 |
| Visit | id、restaurant_id、dining_date、meal、note、version、created_at、updated_at、deleted_at |
| VisitSummary | id、restaurant_id、restaurant_name、dining_date、meal、note_excerpt、version、updated_at |
| RevisionSummary | id、change_set_id、restaurant_id、restaurant_name、entity_type、entity_id、entity_label、action、entity_version_before、entity_version_after、changed_fields: string[]、note、metadata_version、created_at、deleted_at |
| Revision | RevisionSummary 全部字段，另有 diff: {field, before, after}[]、source_revision_id、metadata_updated_at |
| Tombstone | id、restaurant_id（对子对象）、version、deleted_at |
| MutationResult<T> | entity: T、changed: boolean、change_set_id: UUID\|null、revisions: RevisionSummary[] |
| RestaurantCreateResult | MutationResult<Restaurant> 全部字段，另有 first_visit: Visit\|null |
| RevisionMetadataResult | id、note、metadata_version、metadata_updated_at、deleted_at |
| RevisionApplyResult | revision: Revision\|null、entity: Restaurant\|Dish\|Visit、change_set_id: UUID\|null、changed: boolean |
| DeviceSession | id、device_name、created_at、last_refreshed_at、expires_at、is_current |
| Page<T> | data.items: T[]；meta 为前述分页信封 |

评分入站先校验 API 数字处于 1.0–5.0 且最多一位小数，再精确转换为 `rating_tenths`。存储层拒绝 0、拒绝大于 50、拒绝非整数 tenths；NULL 是唯一未评分表示。出站统一执行 `rating_tenths / 10`，因此 JSON 中 43 对应 `4.3`，不会把内部字段名暴露给客户端。
| DeleteBody / RestoreBody | expected_version: integer |
| RevisionDeleteBody / RevisionRestoreBody | expected_metadata_version: integer |

所有可空字段在资源响应中明确返回 null，不让客户端推测“字段未返回”是否代表缺失。UTC 时刻都是 ISO 字符串；版本都是整数；就餐日期始终是 date 字符串。

餐厅地址摘要等 excerpt 只用于列表，按 Unicode 字符安全截断并返回文本；客户端不能把 summary 当作完整详情覆盖缓存。

restaurant_name 和 entity_label 是当前对象的展示名称 / 到店日期，通过有 owner 条件的 JOIN 得到，不触发客户端 N+1 请求；历史中发生过的旧名称仍在原始 diff 中。

### 4.3 Auth 与用户体系 endpoint

鉴权标记：**Public** 无 token；**Access** 有效 access JWT + D1 会话；**Refresh** 有效 refresh token；**Reauth** 在 Access 基础上重新验证当前密码。

| Method | Path（均在 /api/v1 下） | 请求体 / query | 响应体与状态 | 鉴权 |
| --- | --- | --- | --- | --- |
| POST | /auth/register | RegisterRequest | 201：AuthResult + recovery_codes: string[8] | Public；注册限流 |
| POST | /auth/login | LoginRequest | 200：AuthResult | Public；账号 / IP 限流 |
| POST | /auth/refresh | {refresh_token: string}；X-Refresh-Request-Id 必填 UUID | 200：AuthResult；同 key 重试返回同轮换结果 | Refresh；单独限流 |
| POST | /auth/logout | {refresh_token: string} | 204；撤销该 refresh 所属会话，已撤销 / 无效 token 也返回 204 | Refresh 作为撤销凭据；不要求未过期 access |
| POST | /auth/logout-all | {password: string} | 204；auth_version +1，撤销全部会话 | Reauth |
| POST | /auth/password | {current_password: string, new_password: string, device_name: string} | 200：新 AuthResult；旧会话全部失效 | Reauth |
| POST | /auth/recover | {username: string, recovery_code: string, new_password: string, device_name: string} | 200：新 AuthResult + recovery_codes: string[8]；旧码全失效 | Public；有效一次性恢复码与恢复限流 |
| POST | /auth/recovery-codes | {password: string} | 200：{recovery_codes: string[8]}；作废全部旧码 | Reauth |
| GET | /auth/sessions | 无 body | 200：{items: DeviceSession[]}，只列当前有效会话 | Access |
| DELETE | /auth/sessions/{session_id} | 无 body | 204；只撤销自己指定的设备会话，已撤销也 204 | Access；非本人 session 404 |
| GET | /me | 无 body | 200：User | Access |
| PATCH | /me | {expected_version: integer, display_name: string}；Idempotency-Key | 200：User；用户名不允许修改 | Access |
| DELETE | /me | {expected_version: integer, password: string, confirm_username: string} | 202：{deleted_at, purge_after}；立即失效全部会话 | Reauth；不进业务幂等表 |

用户信息的 C/R/U/D 分别由注册、GET /me、PATCH /me、DELETE /me 实现。密码和会话操作各有独立接口，避免把安全字段暴露在通用用户 PATCH。

注册 / 恢复 / 修改密码响应丢失时不进行盲目自动重试：用户可用新凭据重新登录；注册后未拿到恢复码可在登录后重新生成。恢复码更新响应若丢失，登录后再次重新生成。

### 4.4 餐厅 CRUD 与搜索

| Method | Path | 请求体 / query | 响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| POST | /restaurants | RestaurantCreate；Idempotency-Key | 201：RestaurantCreateResult；餐厅与可选 first_visit 原子创建 | Access |
| GET | /restaurants | 下述 RestaurantQuery | 200：Page<RestaurantSummary> | Access |
| GET | /restaurants/{restaurant_id} | deleted=exclude\|include，默认 exclude | 200：RestaurantDetail；include 可查看自己的软删除对象 | Access |
| PATCH | /restaurants/{restaurant_id} | RestaurantPatch；Idempotency-Key | 200：MutationResult<Restaurant> | Access |
| DELETE | /restaurants/{restaurant_id} | DeleteBody；Idempotency-Key | 200：MutationResult<Tombstone> | Access |
| POST | /restaurants/{restaurant_id}/restore | RestoreBody；Idempotency-Key | 200：MutationResult<Restaurant>；过期 410 | Access |

**RestaurantQuery**

| 参数 | 格式、默认与语义 |
| --- | --- |
| q | 最多 100 个 Unicode 字符，分成最多 5 个非空关键词，AND 匹配 `restaurant_search.content`；覆盖餐厅名称、类型、菜系、地址、餐厅评价、餐厅备注、菜品名称、菜品短评，并返回命中字段类型 |
| category | 单个固定类型代码 |
| cuisine | NFKC / lowercase 后的精确匹配；首版一个主要菜系 |
| min_rating / max_rating | 1.0–5.0，最多一位小数；服务端转为 10–50 的 tenths 比较，未评分默认不匹配评分区间 |
| unrated | true 时只查未评分；与 min_rating / max_rating 同用返回 400 |
| visited | true / false；通过有效 visit 的 EXISTS / NOT EXISTS 判断 |
| visited_from / visited_to | YYYY-MM-DD，闭区间；任意一次有效 visit 落在区间即匹配 |
| sort | updated_desc（默认）或 rating_desc |
| deleted | exclude（默认）、only、include；only 用于最近删除列表，且不叠加已到店过滤 |
| cursor / limit | 前述 keyset 分页；筛选变化必须重置 cursor |

### 4.5 菜品 CRUD 与搜索

| Method | Path | 请求体 / query | 响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| POST | /restaurants/{restaurant_id}/dishes | DishCreate；Idempotency-Key | 201：MutationResult<Dish> | Access；父餐厅有效 |
| GET | /restaurants/{restaurant_id}/dishes | q、min_rating、max_rating、unrated、sort=updated_desc\|rating_desc、deleted、cursor、limit | 200：Page<DishSummary> | Access；父餐厅有效 |
| GET | /dishes/{dish_id} | deleted=exclude\|include | 200：Dish | Access；父餐厅有效 |
| PATCH | /dishes/{dish_id} | DishPatch；Idempotency-Key | 200：MutationResult<Dish> | Access；父餐厅有效 |
| DELETE | /dishes/{dish_id} | DeleteBody；Idempotency-Key | 200：MutationResult<Tombstone> | Access；父餐厅有效 |
| POST | /dishes/{dish_id}/restore | RestoreBody；Idempotency-Key | 200：MutationResult<Dish> | Access；父餐厅先恢复，30 天内 |

q 只在指定餐厅内匹配菜名与短评；不会加入 visit 日期作为菜品属性。没有跨餐厅的 `GET /dishes` 列表端点，也没有全局菜品一级页；跨字段首页搜索通过餐厅聚合搜索返回所属餐厅。接收到 visit_id、dish_visit 等未知字段直接返回 400。

### 4.6 到店 CRUD 与搜索

| Method | Path | 请求体 / query | 响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| POST | /restaurants/{restaurant_id}/visits | VisitCreate；Idempotency-Key | 201：MutationResult<Visit> | Access；父餐厅有效 |
| GET | /restaurants/{restaurant_id}/visits | q、from、to、meal、deleted、cursor、limit | 200：Page<VisitSummary> | Access；父餐厅有效 |
| GET | /visits | restaurant_id?、q、from、to、meal、deleted、cursor、limit | 200：Page<VisitSummary>；全局到店时间线 | Access；默认排除已删除父餐厅 |
| GET | /visits/{visit_id} | deleted=exclude\|include | 200：Visit | Access；父餐厅有效 |
| PATCH | /visits/{visit_id} | VisitPatch；Idempotency-Key | 200：MutationResult<Visit> | Access；父餐厅有效 |
| DELETE | /visits/{visit_id} | DeleteBody；Idempotency-Key | 200：MutationResult<Tombstone> | Access；父餐厅有效 |
| POST | /visits/{visit_id}/restore | RestoreBody；Idempotency-Key | 200：MutationResult<Visit> | Access；父餐厅先恢复，30 天内 |

q 匹配当次备注、餐厅名称；from / to 为日期闭区间，排序固定为 dining_date DESC, id DESC。同日多次到店有不同 id，均保留。

### 4.7 Revision 的全部 CRUD、查询与恢复

历史的“CRUD”必须与真实性兼容，因此区分：

- **C：**业务写入自动产生 revision；另外提供 POST /revisions 作为“重新应用历史字段”的命令，真正修改实体并生成一条新的 revision。
- **R：**列表、详情、字段筛选、关键词搜索。
- **U：**只改这条历史的用户说明 note，不改 before / after / 原始时间。
- **D：**软删除为隐藏，可恢复；原始 diff 仍保留至目标永久清理或账号注销。
- 不提供接受客户端伪造 diff 的“补历史”接口；如果尝试 PATCH diff，返回 400 UNKNOWN_FIELD。

| Method | Path | 请求体 / query | 响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| POST | /revisions | {source_revision_id: UUID, side: "before"\|"after", fields: string[], expected_version: integer}；Idempotency-Key | 有效变更 201、无变化 200：RevisionApplyResult；无变化时 revision / change_set_id 为 null、changed=false | Access；来源和目标均属于本人，目标有效 |
| GET | /revisions | restaurant_id?、entity_type?、entity_id?、action?、field?、q?、from?、to?、deleted、cursor、limit | 200：Page<RevisionSummary> | Access |
| GET | /restaurants/{restaurant_id}/revisions | 同上，但 restaurant_id 由路径决定 | 200：Page<RevisionSummary>；含餐厅、菜品、到店历史 | Access；可看自己的软删除餐厅历史 |
| GET | /dishes/{dish_id}/revisions | action、field、q、from、to、deleted、cursor、limit | 200：Page<RevisionSummary> | Access；目标属于本人且未永久清理 |
| GET | /visits/{visit_id}/revisions | 同上 | 200：Page<RevisionSummary> | Access；目标属于本人且未永久清理 |
| GET | /revisions/{revision_id} | deleted=exclude\|include | 200：Revision | Access |
| PATCH | /revisions/{revision_id} | {expected_metadata_version: integer, note: string\|null}；Idempotency-Key | 200：RevisionMetadataResult | Access |
| DELETE | /revisions/{revision_id} | RevisionDeleteBody；Idempotency-Key | 200：RevisionMetadataResult；隐藏，metadata_version +1 | Access |
| POST | /revisions/{revision_id}/restore | RevisionRestoreBody；Idempotency-Key | 200：RevisionMetadataResult；取消隐藏 | Access；原实体未永久清理 |

revision 的 from / to 接收 ISO 8601 UTC 时刻，from 包含、to 不包含。field 为已知字段代码，通过 json_each(diff_json) 检查；q 匹配旧值、新值、字段代码及用户说明。

POST /revisions 首版仅允许来源 action=update；fields 必须是该 diff 中存在的、允许编辑的业务字段。禁止应用 id、owner、version、系统时刻和 deleted_at；删除 / 恢复使用业务 restore 接口。服务器重新校验字段值，计算“当前实体 → 拟应用历史值”的 diff，不把来源 diff 原样复制。

fields 限 1～7 个且不得重复；非法、缺失或重复字段返回 400，避免用户请求制造重复 diff。

隐藏历史的恢复不设额外 30 天窗口，因为原始 diff 会跟随仍存续的目标保留；目标永久删除后为 404。UI 必须用“隐藏记录 / 取消隐藏”，不能宣称立即擦除存储。

### 4.8 综合搜索与健康检查

| Method | Path | 请求 / query | 响应 | 鉴权 |
| --- | --- | --- | --- | --- |
| GET | /search | q 必填；groups=restaurants,visits,revisions，默认 restaurants；每组 limit 默认 10、最大 20 | 200：{restaurants: RestaurantSearchResult[], visits: VisitSummary[], revisions: RevisionSummary[]}；菜品命中归入所属餐厅结果，不返回全局 dishes 组 | Access |
| GET | /health | 无 | 200：{status: "ok", api_version: "v1"}；不查询或暴露 D1 结构 / 账号 | Public |

综合搜索只作为快速入口，不承担多组独立 cursor；餐厅组覆盖餐厅 + 菜品 + 评价 + 备注，并通过 `matched_fields` 告知 UI 命中类型。“查看全部”跳到餐厅或到店分页接口；菜品继续进入 `/restaurants/{id}/dishes`。默认不扫完整历史，用户明确选择 revisions 组才增加这部分查询。

综合搜索排除已删除的业务目标和已隐藏 revision；最近删除的对象及其历史通过专用回收站 / history 接口查看。

### 4.9 请求示例与写入结果

~~~http
POST /api/v1/restaurants HTTP/1.1
Host: dining.alanvampuke.top
Authorization: Bearer <access_token>
Content-Type: application/json
Idempotency-Key: 21c87d5e-0366-41a1-9c85-70438b2b8d9f
~~~

~~~json
{
  "id": "2b2e4654-3027-4f62-a850-9e39eefc3d1a",
  "name": "Lane Sichuan Bistro",
  "category": "restaurant",
  "cuisine": "Sichuan",
  "address": "18 Zhongshan Road, 2F",
  "rating": 4.0,
  "review": "Good flavor and friendly service.",
  "note": "Closed on Mondays.",
  "first_visit": {
    "id": "636d8199-a5ab-4b88-8f3c-ecb391fd56ea",
    "dining_date": "2026-10-28",
    "meal": "dinner",
    "note": null
  }
}
~~~

~~~json
{
  "expected_version": 7,
  "rating": 4.3,
  "review": "More consistent on the second visit."
}
~~~

上述 PATCH 的成功数据包含 entity 的完整当前值、version=8、change_set_id 和一条包含两个字段的 revision 摘要。没有变化时返回 changed=false、原 version、change_set_id=null、revisions=[]。

### 4.10 原子写入、幂等与成本

每次业务变更依次执行：

1. 校验 JWT、D1 session、JSON、字段限制与幂等 key。
2. 查询自己的当前实体及父对象，读取当前 version 和可编辑字段；命中成功的幂等结果则返回原结果。
3. 在服务端规范化输入、验证 expected_version、计算 diff；所有昂贵计算都在数据库事务外。
4. 使用一个 D1 batch 执行“业务写入 → 行数守卫 → change_set → revision → 必要时重建 restaurant_search → 成功幂等响应 → 清理守卫”。
5. 批次任一语句失败则整批回滚；不能在批次提交后才判断更新 0 行并以为历史也回滚。
6. batch 失败后先重新查询同 key 的已提交幂等结果并核对指纹，命中则返回原成功结果；未命中再确认权限、区分 CAS 冲突和数据库错误。不会因第二个同 key 请求先在 CAS 处失败而错误返回 409，也不会把任意 SQL 异常都当作版本冲突。

CAS 的示意 SQL（不是独立运行事务的命令）：

~~~sql
UPDATE restaurants
SET rating_tenths = ?, updated_at = ?, version = version + 1
WHERE id = ? AND user_id = ? AND version = ? AND deleted_at IS NULL;

INSERT INTO mutation_guards (operation_id, affected_rows)
VALUES (?, changes());

-- Insert change_set, revision and idempotency response in the same batch.

DELETE FROM mutation_guards WHERE operation_id = ?;
~~~

CHECK affected_rows = 1 会让“CAS 更新 0 行”成为真正的 SQL 失败。changes() 必须紧跟对应业务 INSERT / UPDATE，不得夹入会改变计数的 SQL。

创建子对象使用 INSERT ... SELECT ... FROM restaurants WHERE user_id=? AND deleted_at IS NULL，并接行数守卫；修改子对象的 UPDATE 同样包含父餐厅有效的 EXISTS。这样即使父餐厅在预读取后被其他设备删除，也不会向失效父对象继续写入。

创建餐厅与首访是一个 batch，两个业务 INSERT 各有独立守卫、两个 revision、一个 change_set。首访校验失败时餐厅也不创建。

同一 batch 的每个守卫使用不同 operation_id，例如逻辑 key 加实体 ID；不能两次 INSERT 复用守卫主键。发生权限或版本竞态时，先排除已经成功的同 key 重试，再对本人对象返回 409 / 404。

**无变化保存**仍要原子确认版本和父对象有效，可使用带版本条件的 no-op UPDATE 加守卫，但不修改业务 updated_at / version、不写 change_set / revision；只存幂等结果。

请求指纹为 SHA-256(method + normalized path + canonical normalized JSON)。成功结果与业务写入同事务保存：

- 同一个 key、同一个指纹：返回同一个状态与结果，不产生第二条历史。
- 同一个 key、不同指纹：409 IDEMPOTENCY_CONFLICT。
- 同时到达的同 key 请求：唯一约束使一方失败；确认已提交结果后按指纹返回。
- 只缓存成功结果，7 天后清理；客户端不能在 7 天后自动复发未知结果的旧写入，先查询实体确认。
- 幂等结果是原操作结果，不保证仍是最新状态；客户端成功后按正常刷新策略更新。

普通列表每页最多 50 条，revision 列表只返回摘要；详情才读取 diff。SQL 用 owner 索引限定范围，避免全库扫描、N+1、无限嵌套 include。批处理减少往返，但不会免除 D1 的 rows read / written 计费。

revision 的 changed_fields 在 D1 查询中通过 json_each 聚合，只返回字段代码；不得先把所有完整 diff 传给 Worker 再截成摘要。列表的派生名称通过同一 owner 范围 JOIN 获取。

## 5. 鉴权、密码哈希与 Android token 存储

### 5.1 注册方式的取舍

选 **用户名 + 密码**：

- username 为 3～32 位 ASCII 小写字母、数字、下划线，首位字母；输入转小写后做唯一性检查。
- 显示名可用中文，1～50 字；缺省取 username，可独立修改。
- 不依赖邮箱服务，避免把“邮箱可填写”误当成“已拥有可验证、可投递的邮件系统”。
- 邮箱 + 密码是可行候选，但还需验证邮件投递、验证链接、找回流程、发信配额和域名邮件配置。本项目已确定 API 域名，不代表已有这些资源，因此首版不选。
- 没有第三方 OAuth、匿名账号、旧项目 SSO。数据库不共享，cookie / token / 密钥也不共享。
- 注册成功显示 8 个一次性恢复码，每个随机 128 bit，Base32 分组显示。D1 只存规范化码的 SHA-256；每个码只能使用一次。
- 恢复账号消费一个有效码后重设密码、撤销全部旧会话、作废全部旧码，并重新生成 8 个码。
- 重新生成恢复码必须重验密码。若密码和所有恢复码都遗失，首版没有可验证身份的自助找回路径；不能靠联系客服提供用户名绕过认证。

密码政策：至少 12 个 Unicode 字符，最多 128 个且 UTF-8 ≤512 bytes；允许空格和 Unicode，拒绝全空白、NUL 与常见弱口令表中的密码。服务端随包携带在 P0 固定版本和校验和的弱口令表，包含 password1234、123456789012 等长度已达 12 位的常见变体；注册、恢复、修改密码统一校验，客户端同步提示，不把密码发送给外部查表服务。无需“必须同时含四类字符”的机械规则。密码不落日志、不持久化到客户端、不进入历史或业务幂等响应。

### 5.2 密码算法及 Workers 可行性

**确定算法：PBKDF2-HMAC-SHA256，10,000 次，16-byte 随机盐，32-byte 派生值；新注册默认写入 password_iterations=10000。**

选择原因：

1. 是标准、成熟的慢哈希；本方案为免费档采用 10,000 次，低于 OWASP 当前推荐的 600,000 次，安全代价见第 5.2.1 节。
2. Workers 提供 WebCrypto 的 HMAC、PBKDF2、SHA-256、AES-GCM 和安全随机数；不需要 Node 原生 addon。
3. Argon2id 在一般服务器是更优先的候选，但不属于 WebCrypto 标准能力，引入 Wasm、内存与构建链会扩大此个人项目的验证范围；首版不采用。
4. 原生 bcrypt、Node crypto 的某些同步 API、native scrypt 不能因为包在 Node 开发机能跑就假定 Workers 可用。方案不依赖它们。

**实现路线明确如下：**

- 实现基线采用 **@noble/hashes 的标准 PBKDF2-HMAC-SHA256 纯 JavaScript 实现**，10,000 次；不是自写 KDF，不拼接多次低迭代 KDF，也不把 SHA-256 当密码哈希；发布路线必须先通过免费档 CPU 验证。
- salt 使用 crypto.getRandomValues。pepper、JWT、重试结果加密使用 Workers WebCrypto。
- P0 同时测原生 crypto.subtle.deriveBits 的相同参数；只有远端确认允许 10,000 次、结果与测试向量一致且 CPU 合格后，才可采用等价原生加速路径。
- **需验证 AUTH-01**：Workers 对原生 PBKDF2 有独立迭代上限检查，公开 workerd 代码不能证明具体账号的生产上限。用真实免费档 Worker 测 1k / 10k（1,000 / 10,000 次）两档，并对原生和标准 JS 路线分别记录结果或错误；每个鉴权场景的冷 / 热启动各测至少 30 次 CPU / wall / memory，不能仅用浏览器、Node 或本地 Miniflare 下结论。
- 如果原生上限仍阻挡，只有标准 JS 路线也通过 10 ms CPU 验证时才可发布；仍超限则按第 5.2.1 节继续下调并记录，不静默替换参数。库的版本、许可、测试向量和 Workers 打包结果在 P0 固定。
- **本项目采用免费档**；10,000 次仍不能仅靠估算承诺在 10 ms 内，未通过真实 Worker 验证前不发布密码登录服务。

**pepper**

先计算标准 PBKDF2 派生值，再使用独立 256-bit PASSWORD_PEPPER 的 HMAC-SHA256 生成最终存储值：

~~~text
derived = PBKDF2-HMAC-SHA256(password_utf8, random_salt_16, 10000, 32)
stored_hash = HMAC-SHA256(password_pepper_v1, derived)
~~~

users 保存 algorithm、password_iterations、salt、最终 hash、pepper_version。salt / hash 用无 padding Base64URL；pepper 只放 Worker secret，与 D1 数据分开。验证已有账号时读取该行保存的迭代次数和 pepper 版本，不直接套用新注册的当前配置。

验证密码时计算 derived，然后用 WebCrypto HMAC verify 比较 stored_hash，避免普通字符串提前退出比较。不能宣称 JavaScript 字符串比较恒定时间。

未知用户名使用固定 dummy 记录执行同等参数校验，统一返回 INVALID_CREDENTIALS；预先限流后才做慢哈希。注册用户名占用必须返回冲突，这是注册流程中的明确提示。

pepper 轮换保留旧版本；用户成功登录时若 pepper 或目标参数已变化，用本次输入密码按当前 pepper / 参数重算并以当前 hash / auth_version 做 CAS 更新。无法在没有用户密码的情况下直接替换旧哈希的 pepper。紧急轮换涉及重新验证 / 重设密码，不能删除旧 secret 后期待旧账号自动可用。

### 5.2.1 免费档参数取舍

用户已选择 Workers 免费档，并接受 PBKDF2 从 OWASP 当前推荐的 600,000 次降为本方案采用的 **10,000 次**。迭代次数只有推荐值的 1/60，对离线暴力破解的抵抗力显著下降；以下补偿措施主要限制在线尝试、降低弱密码与单独数据库泄露的风险，不能补回丢失的离线破解成本。

- 强密码按第 5.1 节执行：最短 12 位、拒绝固定常见弱口令表，注册 / 恢复 / 修改密码都由服务端校验。
- 登录、注册、恢复同时执行分钟与小时窗口，具体 username / IP 上限见第 5.3 节；所有窗口先于密码计算检查，超限返回 429 与 Retry-After。
- 保留独立 secret 中的 256-bit pepper；同一账号连续 5 次密码失败锁定 15 分钟，锁定期间不计算密码，具体全局计数与并发处理见第 5.3 节。
- 恢复码继续保持随机 128 bit、每码一次性；成功恢复原子消费当前码并作废全部旧码，不接受已使用或已禁用的码。
- **升级路径**：未来如切 Workers Paid，以 600,000 次作为届时重新核对 OWASP 后的升级目标值，不是当前采用值。下次成功登录先按旧行参数 / pepper 验证，再用本次密码按当前 pepper / 参数重算，CAS 更新 hash、salt、password_iterations、pepper_version，并与新会话同 batch 提交。password_iterations 字段用于逐账号渐进升级；不一次性批量重算，也不把旧最终 hash 当作密码再哈希。升级前另测原生 cap、CPU 及旧验证加新派生的总预算；未登录账号保留旧参数和所需旧 pepper。
- **P0 实测门槛**：10,000 次必须在真实免费档 Worker 验证完整鉴权请求的 10 ms CPU 预算，覆盖改密码的旧密码验证加新密码派生。若优化或等价原生路径后仍超限，继续下调迭代次数、重新实测并记录新增安全代价，同步修订本节、决策表、DDL 的 CHECK / 默认值、新注册写入值、dummy 参数、配置和验收条件；不能只信估算，不能把下调结果写成 10k 已通过。

AUTH-01 实测记录：**待 P0 授权实施后填写，本轮未运行真实 Worker**。需写回测试日期、免费档账户 / runtime / 库版本、实现路线、1k / 10k、场景与冷 / 热启动、样本数（每场景各 ≥30）、CPU 原始值及 p95 / p99 / 最大值、wall p95、峰值内存和 1102 / 超限次数；10k 必须稳定通过免费档 10 ms CPU，失败则保持门槛未通过并记录调整。

### 5.3 CPU 预算与限流

- 免费档每请求 10 ms CPU 是硬预算方向，Promise / await / waitUntil 不会让密码计算免费；拆成多个请求也不是首版解决方案。
- 本项目使用免费档，不配置 Paid 的 cpu_ms；改密码时的“验证旧密码 + 派生新密码”、登录时的 pepper 重算和普通 CRUD 都受每请求 10 ms CPU 约束。
- 哈希计算不进入 D1 batch，避免占用单库串行写入通道。
- P0 远端测注册、正确 / 错误登录、未知账号、改密码、恢复，分别记录 CPU、wall time、峰值内存、冷 / 热启动。
- 验收目标：正确登录 wall p95 ≤3 秒；改密码 / 恢复 p95 ≤5 秒；10k 完整鉴权请求 CPU 每个观测样本均 <10 ms、无 1102，p99 目标 ≤7 ms 以留约 30% 余量。若未达到，先优化标准实现或等价原生路径；仍超限按第 5.2.1 节下调、复测并写回，不自动改用 Paid。
- 一般查询的 D1 网络等待不等于 Worker JavaScript CPU；SQL 扫描仍受 D1 自身资源、耗时和计费约束。

免费档初始限流策略（同一请求须同时满足全部窗口，成功和失败尝试均计数）：

| 操作 | 全局 D1 窗口 | 补充措施 |
| --- | --- | --- |
| 登录 | 每 username 3 次 / 分钟、10 次 / 小时；每 IP 10 次 / 分钟、30 次 / 小时 | 密码验证前先消耗窗口；连续 5 次密码失败锁定账号 15 分钟；短时边缘限流减少攻击到 D1 的压力 |
| 注册 | 每 IP 1 次 / 分钟、3 次 / 小时、5 次 / 天 | REGISTRATION_ENABLED 可以关闭新增账号，不影响已有用户登录 |
| 恢复 | 每 username 1 次 / 分钟、3 次 / 小时；每 IP 3 次 / 分钟、10 次 / 小时 | 错误统一；先限流再查一次性恢复码与计算新哈希；恢复限流独立于登录锁定 |
| 刷新 / logout | 边缘每 IP 60 次 / 分钟；刷新受 session 状态约束 | Android 单飞刷新；异常 token 请求不做慢哈希 |

D1 用 INSERT ... ON CONFLICT DO UPDATE attempts=attempts+1 RETURNING attempts 原子计数。同一请求的各窗口在同一 batch 内计数，任一窗口超限即停止鉴权；计数保留到窗口过期。账号和 IP 标识经独立 RATE_LIMIT_HMAC_KEY 哈希，输入包含操作、账号 / IP 维度和分钟 / 小时 / 天窗口类型前缀；不记录原始 IP。恢复 IP 使用 recovery_ip scope。固定窗口可能在边界出现两倍短时额度，接受这一限制，小时窗口和可用的边缘短窗口进一步约束。

连续失败与锁定复用 auth_rate_limits 的 login_account scope，以独立 failed 前缀的 key_hash、window_start=0 保存 attempts / expires_at，与请求窗口分开。失败计数一小时无新失败则过期；第 5 次连续失败原子设 expires_at=now+15 分钟作为锁定截止。锁定期间检查 attempts>=5 且 expires_at>now，统一返回 429 RATE_LIMITED 和剩余秒数，不做密码计算、不延长锁定；到期重置失败计数。成功登录或一次有效恢复在同 batch 清除计数；创建 session 前同批检查没有有效锁定并以当前 hash / auth_version 做 CAS，防止并发越过锁定。未知用户名采用同样的限流 / 失败计数与 dummy 校验，避免锁定错误泄露账号是否存在。其他要求 Reauth 的密码校验复用账号登录窗口、失败计数与锁定，防止改密码 / 重新生成恢复码成为绕过入口。

Cloudflare Workers Rate Limiting binding 如采用，只作为近似的边缘预过滤，不代替全局账号窗口。**需验证 AUTH-02**：当前套餐可用性、配置字段与地域计数特征；不可用时用现有域名 WAF 能力与 D1 计数完成首版，不追加 KV / Durable Objects 身份服务。

### 5.4 JWT 签发与验证

采用 **HS256 + jose（WebCrypto 路径）**。单个受信 Worker 既签发又验证，不需要额外公钥服务；Android 不参与服务端授权验证。

~~~json
{
  "iss": "https://dining.alanvampuke.top",
  "aud": "dining-log-android",
  "sub": "e6fd0e20-9ef8-44b8-81bd-cc76115dfd38",
  "sid": "5874de6c-8387-4a47-ae2a-5c196f69fd49",
  "av": 2,
  "jti": "a14dd2fc-e774-4cfa-ae94-54ddb9a80963",
  "iat": 1793435400,
  "exp": 1793436000
}
~~~

JWT header 必须带 allowlist 中的 kid 和 alg=HS256。密钥至少随机 256 bit，和 password pepper / refresh retry key 完全独立。

验证顺序：

1. Bearer 格式和 token 字节长度（≤2 KiB）。
2. 固定 alg allowlist、kid、签名、iss、aud、exp、iat、sub / sid 类型；时钟容差最多 30 秒。
3. 查询 sessions JOIN users，检查 session.user_id=sub、未撤销、未过期、user 未注销、JWT av 与 user / session auth_version 一致。
4. 把可信 user_id、session_id 放进请求上下文。

JWT 的 issuer / audience 是固定配置，不从请求 Host 动态构造；拒绝 none、外部 jwks / jku / x5u 请求，不从 token 提供的 URL 取密钥。

每个鉴权请求进行一次短的 D1 会话查询，换取即时撤销；公私数据不放 Cloudflare Cache API。签名 key 导入可在 isolate 内缓存，session 有效性不做会导致撤销延迟的全局缓存。

JWT key 轮换时只使用当前 kid 签发，保留上一版本至少“10 分钟寿命 + 30 秒容差 + 发布缓冲”；紧急泄露直接撤销会话并停用旧 key。

### 5.5 Refresh token、轮换与重放

- token 为 token UUID + "." + 32-byte 随机 secret 的 Base64URL。它是不透明凭据，不是另一个 JWT。
- D1 仅存完整 token 的 SHA-256 hex；256-bit 随机 token 适合快速摘要，密码不适合。
- session 绝对有效期 30 天；单代 refresh 有效期为 min(now + 7 days, session.expires_at)。
- access 到期后使用 refresh；连续 7 天未刷新需重新登录，即使 30 天 session 期限未到。
- session 管理界面的最近活动采用 last_refreshed_at，不在每个 GET 更新 last_seen，减少写放大。

**刷新过程**

1. 校验 token 结构、哈希和 user / session 状态。
2. 若未消费，在事务外生成新 refresh、新 access JWT、AES-GCM 加密结果。
3. 单个 batch 条件 UPDATE 旧 token 的 consumed_at / consumed_request_id，紧接行数守卫；INSERT 新 token；UPDATE session；INSERT refresh_rotation_results；清理守卫。
4. 新 token 的 parent_token_id 唯一，旧 token 只可被消费一次。
5. refresh_rotation_results 用独立 AES-256-GCM key、随机 12-byte nonce；AAD 绑定 old_token_id、user_id、request_id、有效期。密文是鉴权响应，保留 120 秒。
6. 相同旧 token、相同 X-Refresh-Request-Id 在 120 秒内重试，且 session 仍有效：返回解密后的相同结果。
7. 相同旧 token 用不同 request_id，或重试窗口到期后再次使用：视为重放，撤销该 session，返回 401 REFRESH_REUSED，要求登录。
8. 并发批次失败后重新读取消费状态，按上述规则处理；不能仅把失败当临时网络错误继续签发第二代。

事务中的 session UPDATE 同样限定“未撤销、未过期、用户未注销且 auth_version 仍匹配”，并接独立行数守卫；登录创建 session 采用 INSERT ... SELECT 当前 users，并比较验证密码时读取的 hash / auth_version。防止慢哈希或预读取期间发生改密码、注销、登出后仍返回一套看似有效的新凭据。

短时密文是为了可靠重试，不是长期把 refresh 明文存数据库。拥有数据库和加密 secret 的攻击者仍能读取窗口内结果，因此 key 必须独立管理、日志禁止响应 body。

### 5.6 登出、改密码与恢复

- 单设备登出撤销整个 session，不只是删客户端 access。用 refresh token 可在 access 已过期时完成撤销。
- 撤销其他设备只操作本人 session；被撤销设备下一次请求即 401。
- 全部登出递增 auth_version 并撤销全部 sessions。
- 改密码在事务外验证旧密码和计算新哈希；事务中以读取的 auth_version / 当前 hash 做 CAS，更新密码、auth_version，撤销旧会话并创建新的当前会话。
- 恢复码消费、改密码、全部撤销、作废旧恢复码、生成新码、新 session 同批提交；双请求消费同一码只能一方成功。
- 密码、token、恢复码变化不进入餐厅 revision。
- 在线登出等待短时间撤销响应，随后清理本地。离线登出也清理本地并提示“服务器会话未能撤销，可在其他设备的会话管理中撤销”，不能虚报云端已撤销。
- 网络超时不等于账号失效；TOKEN_EXPIRED 尝试刷新，SESSION_REVOKED / REFRESH_REUSED 才终止当前会话。Reauth 的 INVALID_CREDENTIALS 是密码输入错误，不清除仍有效的登录会话。

### 5.7 Android 安全存储

采用 **Android Keystore + AES-256-GCM 加密的应用私有小文件**，不以已弃用的 EncryptedSharedPreferences 作为新项目基础。

- Keystore 存不可导出的 AES key，**不是直接存 token 字符串**。
- refresh token、session_id、刷新操作 request_id / 起始时间以随机 nonce 加密，写入 noBackupFilesDir。
- AAD 绑定文件格式版本及本应用 applicationId；每次加密重新生成 nonce，不复用。
- access token 默认只放进程内存；进程重启用安全保存的 refresh 换新 access。Room /普通 DataStore / SavedStateHandle 不存明文 token。
- 用 AtomicFile 或等价原子替换保证“新 refresh 已持久化后才向业务请求宣布刷新成功”。
- 刷新开始前保存稳定 request_id；请求超时或进程短时重启仍复用旧 token + 同 request_id。超过 120 秒无法拿回结果时要求登录。
- 使用 KeystoreKeyManager / SecureTokenStore 封装、限定 API；缓存和草稿用 Room，不与 token 混在一张表。
- 不要求生物识别才能用 key，以便后台刷新可运行；Keystore 尽量采用硬件支持，但 minSdk 26 不保证 StrongBox。不得把“使用 Keystore”写成“所有设备都有安全芯片”。
- key 无效、应用重装、备份恢复导致 key 缺失或 GCM 校验失败时，删除失效 token 文件并回到登录，不静默降级明文存储。
- 关闭系统云备份；显式 data extraction / backup rules 排除 token、Room 私人数据和草稿，验证厂商设备迁移行为。
- 登录 / 恢复码页面设置敏感窗口保护；密码不进入崩溃报告或分析事件。用户主动复制恢复码才写剪贴板，并标记为敏感数据。

本地 Room 首版存应用沙箱内的文本缓存，不提供端到端加密承诺。设备 root / 运行时被入侵仍可能读取当前内存及本地数据；SQLCipher 可留作以后专项需求，不为首版引入额外原生库。

**需验证 AUTH-03**：API 26 / 35 的 Keystore、进程终止、并发刷新、密文损坏、备份恢复、卸载重装场景；参考第 12 节的操作矩阵。

## 6. 字段级修改记录的实现

### 6.1 Diff 在服务端计算

客户端只发送期待版本及拟修改字段。服务器读取当前对象，应用规范化与业务校验后比较 before / after。不能信任客户端提供的“旧值”，也不能让客户端补写历史。

审计字段白名单：

| 对象 | 字段 |
| --- | --- |
| restaurant | name、category、cuisine、address、rating_tenths、review、note、deleted_at |
| dish | name、rating_tenths、review、deleted_at |
| visit | dining_date、meal、note、deleted_at |

不记录 `restaurant_search.content`、updated_at、系统 version、请求 trace、会话信息。评分 diff / revision 的旧值与新值同样按十分位整数保存，不写 REAL；例如 4.0→4.3 存为 40→43。API 与 UI 展示时除以 10，不把 43 与 "43" 当同一个合法输入。

~~~json
[
  {
    "field": "rating_tenths",
    "before": 40,
    "after": 43
  },
  {
    "field": "review",
    "before": "Good flavor.",
    "after": "Consistent flavor on repeat visits."
  }
]
~~~

每个 revision 对应一个实体的一次有效变化，可包含多个字段。change_set 关联一次用户保存中的多个实体，如新餐厅与首访。

### 6.2 创建、更新、删除与恢复

- create：version 0→1，对非空业务字段记录 before=null / after=value。没有值的可选字段不制造 null→null。
- update：只记录有效变化，version n→n+1。
- delete：deleted_at null→UTC 删除时刻，用户看到“删除记录”，不复制一份完整正文。
- restore：deleted_at 删除时刻→null，用户看到“恢复记录”。
- 重新应用历史字段：目标当前值→选中的历史值；action=update，source_revision_id 标识来源，记录新的版本与时间。
- metadata 的 note / 隐藏状态变化不产生“历史的历史”；metadata_version 独立递增，原始 diff 不动。

### 6.3 避免噪音与合并原则

1. UI 输入只更新草稿，点击保存发一次请求；搜索输入 debounce，评分滑杆变化不自动提交。
2. 先规范化再 diff：CRLF→LF、首尾去空白、可选空串→null。规范化前后的同义差异不单独记历史。
3. 正文内部换行、内部空格不随意压缩，它们可能是用户有意编辑。
4. 同次保存中的多个字段合成一条 revision，不按字段建多条 revision。
5. 相同 Idempotency-Key 的网络重试不增加 revision。
6. 不把不同保存操作按“几分钟内”在存储层合并，避免丢失用户明确保存过的中间值。
7. 历史列表可把同日 / 同 change_set 视觉分组，详情仍显示每次实际提交。
8. 保存成功后再次点击相同内容返回 changed=false，不更新业务时间与 version。

### 6.4 历史显示与重新应用

- “3 天前 · 评分 4.0→4.3”同时可展开绝对时间；存储值 40→43 在展示层除以 10，相对时间依据服务端时刻。
- null 显示“未填写 / 未评分”；类型代码在 Android 映射为中文；日期按 LocalDate 显示。
- 长评价先显示旧 / 新全文的折叠摘要，展开后上下对照；行级或字级高亮可用客户端文本比较，但不改变服务端字段级存储。
- 一条保存含多个字段时显示“修改了评分、评价等 2 个字段”，点击展开全部。
- 从历史重新应用字段，先展示当前值与拟修改值；只恢复用户选中字段。提交时重新 CAS，409 要求重新确认，不默默覆盖其他设备。
- 隐藏历史在普通列表排除，管理页可查看并取消隐藏；原始 diff 不可通过客户端修改。
- 审计内容只读且私有；无公共链接、导出分享或管理员跨账号查看页面。


## 7. Android 架构与实现选择

### 7.1 分层与模块

首版使用 4 个 Gradle 模块，控制个人开发者的构建和依赖成本：

~~~text
android/
  app/                    # Application, Activity, root navigation, Hilt entry
  core/                   # Pure Kotlin domain models and repository interfaces
  data/                   # Retrofit, Room, repositories, secure token store
  feature/                # Compose screens, ViewModels, shared design components
    auth/
    restaurants/
    dishes/
    visits/
    revisions/
    settings/
  gradle/libs.versions.toml
  gradle/wrapper/
~~~

~~~mermaid
flowchart LR
    UI[Compose UI] --> VM[ViewModel / StateFlow]
    VM --> Domain[Domain models / Repository interfaces]
    Repo[Repository implementation] -. implements .-> Domain
    Repo --> Remote[Retrofit / OkHttp / DTO]
    Repo --> Local[Room cache / Drafts]
    Remote --> API[Worker API]
    Local --> UIFlow[Flow of cached data]
    UIFlow --> VM
    Remote --> Auth[RefreshCoordinator / SecureTokenStore]
    Auth --> Keystore[Android Keystore]
~~~

| 层 | 职责与规则 |
| --- | --- |
| UI | 页面排版、输入、导航事件、无障碍；不执行 HTTP、SQL 或 token 处理 |
| ViewModel | 合成页面 StateFlow、触发加载 / 保存、管理查询与草稿状态；不持有 Activity |
| core | Restaurant / Dish / Visit / Revision、LocalDate、错误类型、Repository 接口；纯 Kotlin，容易单测 |
| Repository | 远程结果写 Room、区分缓存与详情完整度、保存草稿、协调版本与幂等重试 |
| Remote | Retrofit 接口、kotlinx.serialization DTO、Zod 契约对应映射、错误转换 |
| Local | Room DAO / Flow、缓存分页、草稿、当前账号数据清理；不存密码或 token |
| Auth | 独立的会话状态与 SecureTokenStore、单飞刷新、401 路由；业务 Repository 不复制鉴权逻辑 |
| app | Hilt 装配、单 Activity、主题及根导航；仅 Manifest / 必要配置使用 XML，页面全部 Compose |

简单 CRUD 直接通过 Repository，不为每个 getter 建一个空 UseCase 类。需要多步规则的“创建餐厅与首访”“保存草稿并提交”“重新应用历史”使用明确的 UseCase，方便验证交互语义。

### 7.2 库选择与版本基线

| 技术 | 选择与理由 |
| --- | --- |
| DI | Hilt + KSP；Android ViewModel、单例网络 / Room 生命周期集成成熟；编译期检查依赖 |
| 网络 | Retrofit 3.0.0 + OkHttp 4.12.0；REST 声明清晰，拦截、超时、Authenticator 和 Android 行为成熟 |
| JSON | kotlinx.serialization 1.8.1；Kotlin DTO 明确、无反射映射；与 Retrofit 官方 converter 集成 |
| 本地数据库 | Room 2.7.1；类型安全 DAO、Flow、事务与迁移支持；查询和草稿不能靠 preferences |
| 状态 | Kotlin Coroutines 1.10.2 + StateFlow；生命周期取消与页面状态合成直接 |
| 导航 | Navigation Compose 2.9.0 typed routes；只传 ID 和简单筛选参数，不传实体 JSON 或 token |
| UI | Compose BOM 2025.05.01 + Material 3；自建新的 ColorScheme / Typography / Shape token |
| 日期 | java.time.LocalDate / Instant，API 26 原生具备主要能力；date 不转换为本地午夜 UTC |
| 小设置 | 普通 DataStore 只保存主题、列表偏好等非秘密设置；token 使用前述 Keystore 方案 |
| 后台同步 | 首版以前台网络恢复刷新和手动保存为主，不建立后台离线写入队列；因此不需要 WorkManager |
| 日志 | debug 网络日志仅 BASIC，并脱敏 Authorization；release 不记录请求 / 响应正文 |

构建基线：

- JDK 17（Temurin）。
- Android Gradle Plugin 8.9.2 + Gradle Wrapper 8.11.1。
- Kotlin 2.1.21；Compose compiler Gradle plugin 与 Kotlin 同版本。
- KSP 2.1.21-2.0.1；Hilt 2.56.2。
- minSdk=26、compileSdk=35、targetSdk=35；Java / Kotlin JVM target 17。

此组合是明确的可验证基线，不是声称截至 2026-10-31 的所有依赖均为最新版。本次已从 Google Maven 核对 AGP 8.9.2、Compose BOM 2025.05.01、Room 2.7.1、Navigation 2.9.0 实际存在。

**需验证 ANDROID-01**：P0 构建一个最小 Compose + Hilt/KSP + Room + Retrofit 工程，完整跑 debug / release 编译。检查依赖兼容性和已公布的安全修复；如需更新，在 libs.versions.toml 固定新的完整组合并记录 ADR。禁止版本号使用 "+"、latest 或动态插件版本，提交 Gradle Wrapper 校验和与依赖锁定。

### 7.3 Room 的范围与离线行为

采用**已登录用户的只读缓存 + 编辑草稿**：

| 本地内容 | 保存策略 |
| --- | --- |
| 餐厅 / 菜品 / 到店列表摘要 | 主键包含 account_id、entity_id；分页拉取、增量 upsert |
| 完整实体详情 | 与摘要分开保存 / 更新，带服务端 version 和 fetched_at |
| revision 摘要 / diff 详情 | 分开缓存，详情按需拉取，不首屏下载全部历史 |
| SavedDraft | account_id、entity_type、entity_id、base_version、base_snapshot、editable_payload、idempotency_key、状态、时间 |
| 查询分页信息 | 按账号和查询指纹记录 cursor、缓存时间；筛选改变创建独立查询状态 |
| 用户设置 | 非秘密偏好；不把账号密码放 Room |

摘要不包含完整评价时，不能用截断文字覆盖详情。摘要 version 大于详情 version 时标记详情过期，下次打开刷新。

**读流程**

1. 页面先订阅 Room Flow，能立即显示缓存。
2. Repository 同时请求网络，成功后事务更新缓存。
3. 网络错误保留已有内容，显示“离线缓存 / 上次更新”，可手动重试。
4. 本地搜索只搜索已缓存的对象与字段，明确提示范围；不会把“没缓存到”说成服务器没有。
5. 联网后刷新第一页及已打开详情，不后台反复全量拉取 500 家餐厅及全部历史。

**写流程**

1. 输入自动保存为 Room 草稿，约 500 ms debounce；这是本地操作，不产生服务端历史。
2. 用户点击保存，检查网络并生成 / 固定 Idempotency-Key，把原请求保存在草稿状态中。
3. 服务端成功后写缓存、删除草稿；必要时重新读取父餐厅 stats。
4. 断网时显示“已保留草稿，联网后再保存”；不显示为已同步实体，不在后台自动上传。
5. 响应丢失时状态为 result_unknown；重试原 payload + 原 key，7 天内可拿回已提交结果。
6. 用户想修改 result_unknown 草稿时，先确认原请求结果，再生成新 payload / key；不能把旧 key 配新内容。
7. 超出 7 天后不盲目重放，先读取服务器当前实体，展示比较，由用户确认新的保存。

首版不做完整离线优先双向同步、outbox 自动重放、跨设备草稿同步或 CRDT。未来需要这些能力时单独设计同步日志与 tombstone 协议，不把 CRUD 的 deleted_at 当成完整同步游标。

### 7.4 冲突处理

409 VERSION_CONFLICT 时展示：

- base_snapshot：用户开始编辑时看到的值；
- server_current：服务器当前值；
- local_draft：尚未提交的编辑值。

只修改不同字段时可计算可合并建议，但用户仍确认保存；同字段冲突必须选择。重新提交使用 server_current.version 和新的 Idempotency-Key，产生一条新的真实 diff。

删除冲突、父餐厅被删除不进行“合并后强制保存”；提示先恢复 / 刷新，草稿保留。多设备对同家餐厅独立更新菜品不会递增餐厅版本，餐厅卡片统计通过查询刷新。

### 7.5 状态、导航与刷新

- 页面使用 StateFlow<...UiState>，同时保留 items、is_refreshing、is_loading_more、is_offline、error、query，不用一个 Loading 状态清掉旧列表。
- Compose 用 collectAsStateWithLifecycle，输入与网络工作不在 composable body 发起。
- 一次性 toast / 导航用明确事件通道；业务结果与草稿状态仍保留在状态模型中。
- SavedStateHandle 保存实体 ID、筛选和 tab；大表单用 Room，密码与 token 不进入 SavedStateHandle。
- 搜索输入 300 ms debounce；筛选切换用 flatMapLatest 取消旧查询；列表通过 ID 去重。
- 根导航随 SessionState 切换，注销 / 登出清空业务 back stack；恢复成功进入主页，返回不会回到密码页。
- 网络使用独立 AuthClient（无 access 拦截、无 Authenticator）与 ApiClient，防止刷新请求递归或占满相同 dispatcher。
- RefreshCoordinator 通过单飞锁协调请求；其他请求发现 token 已更新就用新 token 重试，每个请求最多一次认证重放。
- Authenticator 根据稳定错误 code 区分 TOKEN_EXPIRED、SESSION_REVOKED 和密码错误；先比较请求携带的 token 与当前 token，避免旧请求的 401 清掉已更换的新会话。错误密码不触发刷新，已撤销当前会话直接要求登录。
- API 超时基线：connect 10 秒、普通 call 20 秒；鉴权 call 20 秒覆盖慢哈希。DNS / TLS / 网络错误不无限重试。
- 只读 GET 可最多 2 次带抖动重试；业务写入仅在有稳定幂等 key 且 payload 未变时重试。429 尊重 Retry-After。
- targetSdk 35 按 edge-to-edge 行为处理 system bars、IME、返回手势；不能让保存按钮被键盘或导航条挡住。
- 正式环境禁用 cleartext；debug 对 10.0.2.2:8787 使用明确的开发网络配置。配置 XML 不属于 XML 布局。
- Manifest 只需 INTERNET、ACCESS_NETWORK_STATE 等普通网络权限，无运行时照片、相机、定位或存储权限。

退出账号先说明会清理该账号本地未提交草稿，再取消它的在途任务、清理 Room 与 token 文件；远程已保存记录仍属于账号。离线无法实时感知服务器撤销，联网后的首个认证失败必须立即锁定业务页面并清理会话。

## 8. Cloudflare 部署拓扑、配置与容量估算

### 8.1 正式拓扑

~~~mermaid
flowchart LR
    Android[Native Android app] -->|HTTPS JSON| Domain[dining.alanvampuke.top]
    Domain --> Worker[One Worker: dining-log-api]
    Worker --> D1[(D1: dining-log-prod)]
    Worker --> Secrets[Worker secret bindings]
    Cron[Hourly scheduled event] --> Worker
    GitHub[GitHub Actions] -->|Wrangler deploy| Worker
    GitHub -->|D1 migrations| D1
~~~

正式架构只有一个公开 Worker 主机和一个生产 D1 库；API 的 auth、餐厅、菜品、到店和历史均由同一个 Worker 提供。没有 media Worker、R2、对象存储、Pages 站点或额外 api / auth 子域名。

开发与 CI 用本地 Wrangler。P0 如需真实平台验证，审批后可创建临时 D1 验证库，并在唯一主机上短期开启由随机验证 secret 保护的测试路由；不新增公开域名、不混入正式用户数据。验证结束删除临时库和路由，然后再进入正式 API 发布。

### 8.2 Custom Domain 配置步骤

审批后按顺序：

1. 使用限定到已知 Account ID 和 alanvampuke.top zone 的 Cloudflare API token。
2. 创建 dining-log-prod D1，记录返回的 database_id；account_id 直接使用已知值。
3. 在 Worker Wrangler 配置中把 dining.alanvampuke.top 声明为 custom_domain=true。
4. 先检查该子域名是否已有 DNS / Worker 绑定；本项目只接管 dining 这个名字，不更改主域名或其他项目路由。
5. 用 Workers Custom Domain 部署 / 控制台绑定；Cloudflare 自动配置相应 DNS 和证书。
6. 不预先创建指向不存在源站的 A / AAAA，不填模拟 origin IP，不另建 CNAME 到一个假 API 服务器。
7. 关闭 workers.dev 与部署预览 URL；生产访问只走唯一自定义主机。
8. 核对 TLS、证书、DNS 生效，访问 /api/v1/health；其他路径返回一致 404。
9. 完成小数据 smoke：注册→登录→餐厅→菜品→两条 visit→改分→diff→刷新→登出→旧 access 401。
10. Cron 清理用同一 Worker scheduled handler，带每轮处理预算，不引入外部维护服务。

Custom Domain 的 pattern 是裸主机 dining.alanvampuke.top，**不是** dining.alanvampuke.top/*；后者是另一种 routes 路由模式。两种模式不混用。

### 8.3 Wrangler 配置示意

~~~toml
name = "dining-log-api"
main = "src/index.ts"
account_id = "49eee8b8033579863adb3b7cf0b1852c"
compatibility_date = "2026-10-31"
workers_dev = false
preview_urls = false

routes = [
  { pattern = "dining.alanvampuke.top", custom_domain = true }
]

[vars]
APP_ENV = "production"
AUTH_ISSUER = "https://dining.alanvampuke.top"
AUTH_AUDIENCE = "dining-log-android"
JWT_ACTIVE_KID = "v1"
PASSWORD_ACTIVE_PEPPER = "v1"
REFRESH_RETRY_KEY_ID = "v1"
ACCESS_TTL_SECONDS = "600"
REFRESH_IDLE_SECONDS = "604800"
SESSION_MAX_AGE_SECONDS = "2592000"
REFRESH_RETRY_SECONDS = "120"
PASSWORD_ITERATIONS = "10000"
REGISTRATION_ENABLED = "true"
API_BODY_MAX_BYTES = "65536"
REVISION_MAX_BYTES = "131072"
SOFT_DELETE_RETENTION_DAYS = "30"

[limits]
cpu_ms = 10000

[[d1_databases]]
binding = "DB"
database_name = "dining-log-prod"
database_id = "REPLACE_WITH_CREATED_D1_DATABASE_ID"
migrations_dir = "migrations"

[triggers]
crons = ["17 * * * *"]

[observability]
enabled = true
head_sampling_rate = 0.1
~~~

database_id 是部署前唯一需要创建资源后填入的标识，不是 secret。示例中的 placeholder 不能原样 deploy。

本项目按免费档部署，不配置 [limits] cpu_ms；该字段属 Paid 能力，免费档固定 10 ms。compatibility_date 固定，升级 Wrangler 时跑鉴权、SQL、网络回归。

**需验证 DEPLOY-01**：P0 固定 Wrangler 版本，用其 schema / deploy --dry-run 检查 custom_domain、preview_urls、observability 与 CPU 配置，并在真实账户验证证书及 DNS 绑定。observability 默认调用日志如包含查询关键词，配置关闭 invocation logs，仅输出自定义脱敏日志。

### 8.4 环境变量、secret 和权限

| 配置 | 存放位置 | 管理规则 |
| --- | --- | --- |
| account_id、数据库 ID、域名、TTL、开关 | Wrangler 配置 / GitHub vars | 属于非秘密配置，允许进入 public repo |
| JWT_HS256_KEYS | Worker secret，版本→随机 256-bit key 的 JSON | 只给 Worker，Android 只有 base URL |
| PASSWORD_PEPPERS | Worker secret，版本→随机 256-bit pepper 的 JSON | 与 D1、JWT key 分离，保留必要旧版本 |
| REFRESH_RETRY_KEYS | Worker secret，版本→AES-256 key 的 JSON | 只解密 120 秒窗口响应 |
| RATE_LIMIT_HMAC_KEY | Worker secret | 对账号 / IP 限流标识做 HMAC，独立用途 |
| CLOUDFLARE_API_TOKEN | GitHub production environment secret | 限定账号 / zone；不用 Global API Key |
| Android signing material | GitHub production environment secrets + 开发者离线加密备份 | key 不入 repo、不与业务 token 共用 |
| 本地开发 secret | backend/.dev.vars，列入 .gitignore | 只用测试 key，提供无真实值的 .dev.vars.example |

- 首次配置通过 Wrangler secret put 的交互 stdin 或受控 secret 上传方式；不写到命令行参数、工单、PR、日志或 Markdown。
- 常规 deploy 不改 secret、不把生产 secret 拉回开发机。Wrangler vars 不覆盖或代替 secret。
- 初次绑定可能需要 Workers Scripts Edit、D1 Edit、Workers Routes Edit、Zone Read、DNS Edit；范围限制到本账号和本 zone。
- 日常 CI token 按实际 Wrangler 操作缩减权限；把首次 DNS 配置权限和常规发布权限分开，避免给所有 zone 的编辑权限。
- **需验证 DEPLOY-02**：现有 Cloudflare token 是否有这些权限；TASK 给出的 GitHub token 权限不代表 Cloudflare 凭据已可用。批准后测试只读账号 / zone / D1 列表及 deploy 权限，再配置环境。
- GitHub public 仓库提交前检查历史、.dev.vars、keystore、DB export、本地 .wrangler 数据、log 中是否有 secret；发现真实泄露先撤销再处理仓库历史。

### 8.5 免费 / Paid 额度事实

截至核对日期，官方文档的主要额度如下；**实际账号套餐与计费页在 P0 再核对**，不同项目共享账号级额度。

| 项目 | Workers Free（本方案采用） | Workers Paid（对比参考，本项目不采用） |
| --- | --- | --- |
| Worker 请求 | 100,000 / day | 10 million / month 包含量，超量按官方计费 |
| HTTP CPU | 10 ms / request | 默认 30 秒，可配置至平台上限；本项目主动限 10 秒 |
| Worker 内存 | 128 MB | 128 MB |
| D1 rows read | 5 million / day | 25 billion / month 包含量 |
| D1 rows written | 100,000 / day | 50 million / month 包含量 |
| D1 总存储 | 5 GB / account | 首 5 GB 包含 |
| 单个 D1 库 | 最大 500 MB | 最大 10 GB，不能随意提高上限 |
| D1 Time Travel | 7 days | 30 days |
| Workers 基础订阅 | USD 0 | 约 USD 5 / month 起 |

**判断：纯文本数据容量足够，普通使用的请求、读写量在当前免费档额度内也足够；密码哈希的单次 CPU 曾是主要阻碍，本方案以 10,000 次迭代 + 严格限流换取免费档可运行，代价见第 5.2.1 节。**不能把“一个人一天只登录一次”理解成可以忽略该次请求的 CPU 上限。

本项目采用免费档，不购买 Workers Paid。上表 Paid 列仅作对照；若未来请求量或 CPU 预算确实超出免费额度，再单独评估升级，不在本轮范围。

### 8.6 指定数据量的存储估算

按**每个账号** 500 家餐厅 × 8 菜品 × 3 次到店：

- restaurants：500。
- dishes：4,000。
- visits：1,500。
- 业务实体共 6,000。
- 假设每实体生命周期平均 3 条 revision（含初次创建），共 18,000 条；多数保存一个实体，change_sets 约 17,500～18,000 条。
- 所有估算按 UTF-8 bytes，中文通常约 3 bytes / 字，不把“字数”当“字节数”。

| 内容 | 数量 × 平均大小（估算假设） | 估计体积 |
| --- | --- | --- |
| 餐厅当前数据 | 500 × 1.5 KiB（ID、地址、评价、备注等） | 约 0.73 MiB |
| 菜品当前数据 | 4,000 × 0.7 KiB | 约 2.73 MiB |
| 到店当前数据 | 1,500 × 0.4 KiB | 约 0.59 MiB |
| restaurant_search 聚合文本 | 500 × 约 300–600 bytes（约 8 道菜 × 菜名 + 短评） | 约 0.15–0.3 MB，可忽略 |
| 其他实体搜索影子文本 | 到店与历史按需生成 | 约 1～3 MiB |
| 字段 diff | 18,000 × 0.8 KiB | 约 14.06 MiB |
| change_sets | 18,000 × 0.15 KiB | 约 2.64 MiB |
| 用户 / 会话 / token | 活跃设备数和刷新频率相关 | 约 1～5 MiB |
| 最近 7 天幂等结果 | 10 保存 / day ×7，平均 2 KiB | 约 0.14 MiB |
| B-tree 索引、行头、页空隙 | 结合实测留余量 | 约 10～30 MiB |
| **合计** | 非满长度的日常文本、包含历史和索引 | **约 35～70 MiB，按 100 MiB 做初期预留** |

保守看单个 Free 库 500 MB 可容纳约 4 个“预留 100 MiB”的同规模账号；按实际 40 MiB 且保留 20% 空间约 8～10 个。不能把 Free 总计 5 GB 当成单库上限。本应用有独立注册能力，但不把这个个人规模方案当作无限用户 SaaS 承诺。

**历史增长比当前数据更重要：**

- 0.8 KiB / revision 是常规短文本假设，不是上限。单条 128 KiB 限制仍可能快速耗尽 500 MB。
- 每天新增 10 条 revision，平均 1 KiB，原始 diff 一年约 3.6 MiB；加分组、索引后约 7～12 MiB。
- 经常反复修改接近上限的长评价、长备注时，before / after 会重复占空间；隐藏不减数据库体积。
- 软删除及 30 天保留也占空间；永久清理后 D1 计费存储与页面回收效果以平台实测为准。
- 数据库到 70% 容量告警、80% 进入容量处理计划；先分析历史与文本分布，再考虑 Paid 或以后按账号分库，不能静默删有效历史。

**需验证 CAPACITY-01**：P0 / P1 用合成英文、中文短文、接近字段上限的长文生成上述 6,000 个对象和 18,000 条历史，测 sqlite / D1 报告存储、索引、查询 rows_read / rows_written；另测每实体 10 条历史的增长情况。合成数据不使用旧项目或真实个人信息。

### 8.7 请求、读写与 CPU 估算

以单人每天 100 个 API 请求、30 次搜索、10 次有效保存、约 12 次 refresh 为例：

- Worker：约 100～150 / day，远低于 Free 100,000 / day。
- 餐厅聚合搜索先按 owner 扫最多 500 条 `restaurant_search`，30 次约 15,000 rows；综合搜索若明确包含历史，最坏另加 18,000 ×30≈540,000 rows。
- 详情、认证、分页等另计，约 0.2～0.7 million rows read / day，仍低于 Free 5 million。
- 每保存写实体、revision、change_set、幂等结果及索引，粗估 10～20 计费行；10 保存约 100～200 rows written。
- refresh 约 4～5 个表行变更，加索引粗估 10～20 / 次；12 次约 120～240 行。限流与清理后整体通常仍低于几千行 / day。
- SQL 返回 20 条不意味着 rows_read=20，搜索扫描和索引也需计入；以上仅预算，不替代 D1 meta 观测。
- 普通 CPU 按 3～5 ms / request、100 / day，约 9,000～15,000 CPU ms / month。
- 密码校验按 10k 迭代保守估计 20～40 ms CPU / 次、30 次 / month，约 1,200 CPU ms / month；远低于免费档每日 10 万请求对应的可用 CPU 池。实际值以 P0 实测为准。
- 哈希实际耗时需测；该示例不能反推 Free 单次 10 ms 可用，也不包含恶意流量。
- 初次 6,000 对象建档可能产生数万计费写行，需分批控制，不能在一个 HTTP 请求导入全量。首版本身不提供批量文件导入。

### 8.8 日志、备份与运维

- 日志只记录 request_id、规范化 route 模板、method、status、duration、错误 code、D1 计数；不记录 Authorization、密码、恢复码、响应 token、评价正文、搜索 q。
- 错误捕获转换成稳定 code；生产不返回堆栈和 SQL。查询参数以固定模板打印，不能把 user 内容混入日志。
- 记录 1102 / CPU limit、DATABASE_BUSY、5xx、刷新重放事件计数；不自动收集联系人、设备广告 ID 或用户轨迹。
- 预发布查看 D1 Time Travel bookmark。重大迁移如需完整 SQL 备份，只在开发者本机受控导出、加密并放在仓库之外，不上传 R2 或公开 GitHub artifact。
- 回滚首选 Worker 上一兼容版本；schema 用 expand/contract 保持旧 Worker 可读。自动回滚不自动倒退数据库。
- D1 Time Travel 恢复会把**整个库**回到恢复点，可能丢失恢复点后的有效用户写入；灾难操作先暂停写入并明确选择恢复点，再恢复服务。
- Cron 在最后留预算退出；本轮未处理的过期数据下轮继续，不通过长事务持有单库。

## 9. 风险与具体处理

| 风险 | 影响 | 处理与验收依据 |
| --- | --- | --- |
| 免费 10 ms CPU 与慢哈希冲突 | 注册 / 登录触发 1102 | 采用免费档发布，PBKDF2 降到 10,000 次；P0 用真实 Worker 测全部鉴权动作，仍超限则继续下调并记录实测值 |
| 原生 PBKDF2 迭代 cap | 10,000 次以外的更高迭代无法 deriveBits | 标准纯 JS PBKDF2 路线可执行；远端验证后才启用原生等价加速；升级到 600,000 时才受 cap 影响 |
| D1 单库串行、搜索扫描 | 慢搜索拖延后续写入 | `restaurant_search` 将首页范围收敛到每餐厅一行，配合 owner 索引、分页和短 batch；6,000 / 18,000 数据集测 SQL 与整体延迟 |
| 预读取与写入间发生并发 | 覆盖、父对象失效、历史错配 | expected_version CAS、父有效 EXISTS、changes() 守卫与同 batch revision |
| 重试导致重复实体 / 历史 | 同一保存出现两条 | UUID、业务幂等、刷新短期密文；网络丢包场景验收 |
| refresh 重放与正常并发混淆 | 全会话失效或接受被盗 token | 单飞协调、稳定请求 ID、120 秒同请求重试；不同 ID 撤销 |
| token 进入公开仓库或日志 | 可被冒用 | secret scope、脱敏、禁正文日志、fork PR 无 secret、Keystore 密文 |
| Keystore / 备份迁移差异 | 恢复后不能解密或跨账号残留 | 关闭备份、显式规则、损坏即登录；API 26 / 35 真机验证 |
| 弱网与国内访问稳定性 | DNS / TLS / 网络超时，无法联网提交 | Room 阅读与草稿、合理超时、明确重试；中国移动 / 联通 / 电信 Wi-Fi 与蜂窝抽测 |
| Cloudflare 全球网络没有大陆可用性保证 | 某些地区持续无法访问 | 单域名方案内记录实际可用性；不承诺仅靠一个 CDN 配置解决，后端迁移须另行评审 |
| 无图片导致信息页松散 | 低使用意愿 | 类型色块、评分、文字层级、时间线；P0 真实长短文本原型评审 |
| 中文搜索与 FTS 分词 | 查询不到预期子串 | NFKC + instr；中文、全角、大小写、多关键词测试；不假定 unicode61 能中文分词 |
| 日期与时区转换 | 就餐日期偏一天 | LocalDate / YYYY-MM-DD；时区切换测试，数据库检查真实日期 |
| soft delete 恢复 / 清理竞态 | 超期恢复、部分子对象误复活 | 30 天硬判定，父子规则、分块清理再次条件检查 |
| history 体积长期增长 | 单库达到上限 | 用真实报告监控、明确平均值、70% 告警；不默默丢历史 |
| public repo 的 Actions 执行不可信 PR | 生产凭据被构建脚本读取 | PR 只有只读权限，无发布 secret；发布只能选主分支已审查提交 |
| APK 签名 key 丢失 | 已安装 app 不能正常升级 | 首次生成后离线加密备份；CI 验签，后续发布沿用同 key |
| targetSdk 35 的新系统行为 | edge-to-edge、返回和键盘布局问题 | API 35 真机 / 模拟器检查；按需求保持 targetSdk 35 |
| 将 GitHub APK 与商店上架混为一谈 | 发布预期不一致 | 首版通过 GitHub Releases 分发 APK；以后如上架 Play，需另核对当期 target 政策，不擅自改当前需求 |

**需验证 NETWORK-01**：P4 至少抽测三网中的可用样本，记录运营商、Wi-Fi / 蜂窝、DNS、TLS、首屏与鉴权耗时、超时率。无法取得某网络样本时写明缺口，不能把 VPN / 海外 CI 的成功当大陆验证。


## 10. 分阶段实施计划 P0～P5

按个人开发者串行实施估算约 **26～36 个有效工作日（约 5～7 周）**；这是工作量估计，不是未验证的日期承诺。所有阶段在本方案获批后开始；P0 验证失败必须先解决，不把关键风险带到正式发布。

| 阶段 | 工作与交付物 | 验收标准 | 估算 |
| --- | --- | --- | --- |
| **P0：决策与平台验证** | ADR：视觉、用户名 / 恢复码、离线范围、免费档额度预算；固定构建版本；最小 Compose 原型；Workers 密码 CPU 验证；D1 约束 / batch 验证；验证矩阵记录 | 下述 P0 门槛全部通过，或明确解决失败路线；不出现图片、坐标、旧项目代码；真实 D1 与本地结果分别记录 | 3～4 日 |
| **P1：新仓库与底座** | 创建 vampuke/dining-log public repo；MIT license 建议、README、Android 4 模块、Worker Hono 骨架、完整 migrations、OpenAPI 初稿、无 secret 的 CI | minSdk 26 / targetSdk 35 编译；本地迁移及完整性检查通过；独立包名 / 数据库 / secret；4,500 对象容量测试可重现 | 3～4 日 |
| **P2：独立账号闭环** | 注册 / 登录 / 恢复 / 改密码 / session 管理全 API；Keystore 存储与刷新协调；Android 认证页面；auth 测试 | 注册可用新账号登录；密码 hash 不为明文；刷新轮换、丢包重试、重放撤销、退出失效；进程重启可恢复；不同账号无残留 | 4～5 日 |
| **P3：领域 API 与修改历史** | 餐厅 / 菜品 / visit 全 CRUD；搜索筛选；服务端 diff；幂等 / CAS；revision 全 CRUD 语义；软删除 / 清理；OpenAPI v1 定稿 | 完整 P3 场景通过；无越权、无 visit→dish 绑定、无假 diff；同一保存 / 重试历史数量正确；并发一个成功一个冲突 | 6～8 日 |
| **P4：Android 产品闭环** | 三 tab 页面、详情、编辑、日期、搜索、历史、草稿、冲突、回收站、账号安全；新的明暗主题；Room 缓存 | 端到端用例通过；API 26 / 35 可运行；200% 字体、TalkBack、键盘、返回、弱网；三网样本记录 | 7～11 日 |
| **P5：签名、部署与发布** | 完整 Actions、release keystore、单域名绑定、D1 迁移、Worker 正式发布、Cron / 监控、已签 APK、SHA-256、运维说明 | CI 通过；APK 验签 / 安装 / 原签名升级；唯一域名 smoke；secret 不入 repo；恢复演练与最终需求矩阵通过 | 3～4 日 |

### 10.1 P0 的阻断门槛

1. **Android 版本链**：Compose + Hilt/KSP + Room + Retrofit 最小工程的 debug / release 编译通过，SDK / 插件与 Wrapper 版本写入文件。
2. **密码参数**：10,000 次 PBKDF2 已通过标准向量，并在真实 Worker（免费档）冷 / 热各 ≥30 次测得稳定落在 10 ms CPU 内；同步记录 1k / 10k 两档实测 CPU 与 wall，验证纯 JS 路线；若超限继续下调并写回文档，预算与响应时间满足第 5 节。
3. **真实 D1**：建表、组合外键、JSON CHECK、合法 / 非法日期、不可变 trigger、batch 中 0 行 CAS 回滚通过。
4. **新视觉**：没有图片的列表、详情、菜品、到店、diff、认证页面有短文 / 长文 / 空态原型；配色与大字体通过。
5. **云权限**：确认已知账号 / zone 可用、单子域名可绑定；取得限定权限凭据；确认账号当前处于免费档。
6. **验证资源**：使用合成数据；临时验证资源完成后清理；不能消耗其他项目的无限共享额度。免费档 D1 每日写行额度为 10 万，大样本初始化必须分批或分日进行，不能一次导入全量。

### 10.2 P2 必过场景

- 同名用户名只有一人注册成功；大小写规范化后不能重复。
- 正确登录、错误密码、未知用户名都遵守限流与稳定错误。
- 刷新后旧 refresh 不再可正常使用；同 request_id 丢包重试拿回同一结果。
- 多个并发 API 401 只触发一次刷新；不同 request_id 的旧 refresh 重用撤销整个 session。
- 修改密码 / 恢复后，旧 access 与 refresh 即使未到期也不能访问。
- 一次性恢复码双请求竞争只有一个成功；旧恢复码作废。
- 登录 A→退出→登录 B，本地列表、草稿、导航没有 A 的数据。
- app 进程终止后能用安全存储恢复；密文损坏、重装后正确回到登录。

### 10.3 P3 必过场景

1. 创建餐厅（有纯文本地址），创建 5 个菜品，记录 3 次到店，其中两次同日；菜品仍只归餐厅。
2. 初次创建记录存在；评分 4.0→4.3 时生成一个 revision，存储 diff 中 `rating_tenths` 的 before=40 / after=43，API / UI 显示 4.0 / 4.3。
3. 同时改地址和评价只生成一个实体 revision，包含两个字段。
4. 保存相同值、仅规范化空白不产生有效变更；业务 version 不增长。
5. 相同 key 重试不会重复建 restaurant / visit / revision；换 payload 复用 key 返回 409。
6. 两设备用相同 base version 写同一实体：只有一方成功；失败一方没有孤立 change_set / revision。
7. 在预读取与写入之间删除父餐厅：子写入失败，没有“已删除餐厅中新建菜品”。
8. A 访问 B 的列表、ID、子对象、revision、restore、session 均失败且不泄露内容；组合外键另行验证。
9. 删除餐厅后普通搜索与子对象 CRUD 不可访问；恢复父餐厅不复活先前单独删除的菜品。
10. 30 天边界，过期 restore 返回 410；分块清理不影响其他餐厅 / 账号。
11. revision 可读、加说明、隐藏、恢复；diff 和原始时间的 PATCH 被拒绝。
12. 通过 POST /revisions 重新应用一个历史字段生成新 revision，保留当前其他字段；来源 / 目标非本人失败。
13. 搜索必须命中菜品名、菜品短评、餐厅评价和餐厅笔记中的关键词，并返回 restaurant / dish / review / note 命中类型；中文、英文大小写、全角半角、多关键词、% 和 _ 字符均按 NFKC + lowercase + `instr` 的字面语义测试。
14. 6,000 业务对象 / 18,000 历史下，常用 SQL p95 目标 ≤100 ms；Worker 无意外 CPU 超限。端到端目标在稳定测试网络下普通 CRUD / 列表 p95 ≤1.5 秒。
15. 长评价、换行、null、未评分、合法闰日可往返；非法日期、评分 0、评分 >5、超过一位小数、字符串评分、内部非整数 tenths、超长内容有明确错误。

### 10.4 P4 端到端路径

注册→保存恢复码→新增餐厅与首访→从餐厅详情新增菜品→再次到店→按店名 / 菜系 / 菜品名 / 菜品短评 / 餐厅评价 / 笔记 / 日期搜索→改评分→查看旧值 / 新值→重新应用一个历史字段→断网浏览与保存草稿→联网手动保存→跨设备冲突→删除 / 恢复→修改密码→退出→重新登录。

验收同时覆盖初次空态、真正无结果、部分缓存、加载失败、长文字和键盘显示。没有图片字段、占位图上传提示或地图权限弹窗。

### 10.5 P5 交付清单

- public repo 的完整新实现、MIT（若采用）、README、架构 ADR、OpenAPI、迁移与运行说明。
- 唯一生产 API：https://dining.alanvampuke.top/api/v1。
- 可安装的 signed release APK、SHA-256、版本号、更新说明、GitHub Release。
- 正式 Cloudflare 配置、secret 名称清单（没有值）、日志 / Cron / Time Travel 操作说明。
- 本地启动、测试、签名、部署、回滚步骤均能按 README 执行。
- P0～P5 验收记录及仍需验证项的最终状态；未验证内容不包装为已通过。

## 11. CI/CD：GitHub Actions、签名与 Wrangler

### 11.1 单仓库布局及脚本契约

~~~text
dining-log/
  android/
  backend/
    src/
      routes/
      services/
      repositories/
      auth/
    migrations/
      0001_initial.sql
    tests/
    wrangler.toml
    package.json
    package-lock.json
    .dev.vars.example
  contracts/
    openapi.yaml
  design/
    TASK.md
    PLAN.md
    adr/
  .github/workflows/
    ci.yml
    android-ui.yml
    release.yml
  README.md
  CHANGELOG.md
  LICENSE
  .gitignore
~~~

backend：TypeScript、Hono、Zod、jose、@noble/hashes、Wrangler、Vitest + Cloudflare Workers pool。P0 从 npm 官方元数据选择已发布的稳定版本并用 package-lock.json 固定；不使用本机 native password 模块。

| 命令 | 将在实现阶段定义的行为 |
| --- | --- |
| npm ci | 按 lock 安装，不在 CI 自动更新依赖 |
| npm run check | TypeScript noEmit + ESLint；检查 Workers 类型与契约 |
| npm test | Vitest：输入规范化、权限、diff、刷新、真实本地 D1 batch / guard |
| npm run db:verify | 从空本地 D1 应用迁移，foreign_key_check / quick_check / 主要约束验证 |
| npx wrangler deploy --dry-run | 构建 Worker 与校验配置，不部署云端 |
| ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug | Android 编译、有业务意义的 JVM 测试、lint |
| ./gradlew :app:connectedDebugAndroidTest | 在 API 26 / 35 模拟器执行核心 UI 场景 |
| ./gradlew :app:assembleRelease | 读取 release 环境签名参数，生成 R8 优化 APK |
| npx wrangler d1 migrations apply DB --remote | 经发布流程确认后应用生产迁移 |
| npx wrangler deploy | 部署单域名 Worker，使用已有 secret |

文档、SQL、JSON 的样例校验可以在 CI 加入；生产数据、D1 SQL export、.wrangler 本地 DB、.dev.vars、keystore、credentials、run log、build 输出加入 .gitignore。

### 11.2 常规 CI 流程

1. pull_request / main push 仅跑校验和构建；不需要生产 secret。
2. Android 和 backend 两个独立 job 可并行；任何失败阻止发布。
3. 本地 D1 测试数据库每次从迁移创建；不访问 production D1。
4. Android UI 单独 workflow 在发布候选及 UI 改动时跑 API 26 / 35，使用 FakeRepository 与合成数据，不登录真实账号。
5. public 仓库 fork PR 使用只读 GITHUB_TOKEN；不使用 pull_request_target 执行 PR 代码，不把签名 / Cloudflare secret 注入检查任务。
6. CI action 和依赖最终锁定已验证的 commit SHA / lock；下方 major tag 为可读的流程示例，P5 落地时固定真实 SHA。

~~~yaml
name: ci

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: "22"
          cache: npm
          cache-dependency-path: backend/package-lock.json
      - run: npm ci
      - run: npm run check
      - run: npm test
      - run: npm run db:verify
      - run: npx wrangler deploy --dry-run

  android:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.event_name == 'pull_request' }}
      - name: Install Android SDK packages
        run: sdkmanager "platforms;android-35" "build-tools;35.0.0"
      - name: Verify Android build
        working-directory: android
        run: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: android/app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7
~~~

**需验证 CI-01**：P1 / P5 核对 runner 的 sdkmanager PATH、许可证、Node / Wrangler 引擎要求、所有 action SHA、Gradle task 名称和实际 artifact 路径。只有真实运行通过后才称 CI 可执行；本轮不创建这些 workflow 文件。

### 11.3 签名方案

采用**固定 release keystore + GitHub environment secrets**，首次生成后一直沿用：

| Secret | 用途 |
| --- | --- |
| ANDROID_KEYSTORE_B64 | 本地生成的 release keystore，Base64 包装用于 secret 传输；Base64 本身不是加密 |
| ANDROID_STORE_PASSWORD | keystore 密码 |
| ANDROID_KEY_ALIAS | 固定 alias，建议 dining-log |
| ANDROID_KEY_PASSWORD | key 密码 |
| CLOUDFLARE_API_TOKEN | Worker / D1 发布凭据 |

Gradle signingConfig 读取以下环境变量，不写进 build.gradle 源码：

~~~text
DINING_SIGNING_STORE_FILE
DINING_SIGNING_STORE_PASSWORD
DINING_SIGNING_KEY_ALIAS
DINING_SIGNING_KEY_PASSWORD
~~~

- 首次 key 建议 RSA 3072、足够长有效期；开发者保存两份离线加密备份，记录 alias、证书 SHA-256、恢复步骤。
- CI 只在受保护的 release job 解码到 RUNNER_TEMP，权限 600；签名完成清理文件。
- debug 使用独立 applicationIdSuffix，避免与正式用户数据 / 签名混淆。
- release 不允许缺 secret 时偷偷使用 debug key；必须直接失败。
- 使用 Android build-tools 的 apksigner verify --verbose --print-certs 验证签名，记录证书摘要；至少验证 v2 签名和 API 26 / 35 安装。
- 递增 versionCode，versionName 与 Release tag 对齐；版本号从已审查提交中的配置读取，不能每次临时生成另一套不一致版本。
- 本项目首版分发 APK，AAB / Play App Signing 不作为额外前置。

### 11.4 发布顺序与环境控制

发布采用 **workflow_dispatch**，明确选定主分支已审查的完整 commit SHA 和版本 tag；不是每次 push 自动部署。production environment 限制主分支，保存发布 secrets。方案审批不会自动触发任何 workflow。

具体顺序：

1. 选定提交，确认是 main 的祖先、版本号已递增、常规 CI 与 API 26 / 35 UI 候选检查通过。
2. 同一提交构建并验签 release APK；生成 SHA-256。
3. 记录当前 Worker deployment ID 与 D1 Time Travel bookmark；初次空库部署注明无历史数据。
4. 校验迁移对当前线上 Worker 的兼容性；新列默认 / 可空，破坏性变更分两个发布，不直接 DROP。
5. 应用生产 D1 migrations；检查完整性。
6. Wrangler deploy 到唯一 Custom Domain；secret 已提前设置。
7. /health 及受控自有 smoke 账号做完整链路；不输出密码 / token。首次可手工验收，后续自动 smoke 凭据仅在 production secret 中。
8. 成功后发布 signed APK + SHA-256 到 GitHub Releases。
9. 发布失败保留诊断；Worker 可回滚上一兼容部署，D1 不自动倒退；APK 发布失败不会伪报完成。

GitHub Actions concurrency 为生产设置单一 group、cancel-in-progress=false，避免两个 migration / deploy 相互打断。

### 11.5 Release workflow 关键示例

以下是包含构建、签名、后端部署及 APK 分发的流程骨架。完整实现还需接入“候选检查状态、版本号检查、迁移完整性与私有 smoke”脚本；不能把骨架直接当已验证生产 workflow。

~~~yaml
name: release

on:
  workflow_dispatch:
    inputs:
      commit_sha:
        description: "Full reviewed commit SHA on main"
        required: true
        type: string
      release_tag:
        description: "Release tag, for example v0.1.0"
        required: true
        type: string

permissions:
  contents: read

concurrency:
  group: dining-production
  cancel-in-progress: false

jobs:
  release:
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    timeout-minutes: 45
    environment: production
    permissions:
      contents: write
    env:
      RELEASE_COMMIT: ${{ inputs.commit_sha }}
      RELEASE_TAG: ${{ inputs.release_tag }}
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ inputs.commit_sha }}
          fetch-depth: 0

      - name: Validate release selection
        shell: bash
        run: |
          set -euo pipefail
          [[ "$RELEASE_COMMIT" =~ ^[0-9a-f]{40}$ ]]
          [[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]
          test "$(git rev-parse HEAD)" = "$RELEASE_COMMIT"
          git fetch origin main --no-tags
          git merge-base --is-ancestor "$RELEASE_COMMIT" origin/main

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/setup-node@v4
        with:
          node-version: "22"
          cache: npm
          cache-dependency-path: backend/package-lock.json

      - name: Verify backend candidate
        working-directory: backend
        run: |
          npm ci
          npm run check
          npm test
          npm run db:verify
          npx wrangler deploy --dry-run

      - name: Install Android SDK packages
        run: sdkmanager "platforms;android-35" "build-tools;35.0.0"

      - name: Build signed APK
        working-directory: android
        shell: bash
        env:
          DINING_KEYSTORE_B64: ${{ secrets.ANDROID_KEYSTORE_B64 }}
          DINING_SIGNING_STORE_PASSWORD: ${{ secrets.ANDROID_STORE_PASSWORD }}
          DINING_SIGNING_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          DINING_SIGNING_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          set -euo pipefail
          umask 077
          export DINING_SIGNING_STORE_FILE="$RUNNER_TEMP/dining-release.jks"
          trap 'rm -f "$DINING_SIGNING_STORE_FILE"' EXIT
          printf '%s' "$DINING_KEYSTORE_B64" | base64 --decode > "$DINING_SIGNING_STORE_FILE"
          unset DINING_KEYSTORE_B64
          ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon

      - name: Verify release signature
        shell: bash
        run: |
          set -euo pipefail
          "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs android/app/build/outputs/apk/release/app-release.apk
          sha256sum android/app/build/outputs/apk/release/app-release.apk > android/app/build/outputs/apk/release/app-release.apk.sha256

      - name: Deploy backend
        working-directory: backend
        env:
          CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
        run: |
          npx wrangler d1 time-travel info DB
          npx wrangler d1 migrations apply DB --remote
          npx wrangler deploy

      - name: Check API health
        run: curl --fail --silent --show-error https://dining.alanvampuke.top/api/v1/health

      - name: Publish release APK
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release create "$RELEASE_TAG" \
            android/app/build/outputs/apk/release/app-release.apk \
            android/app/build/outputs/apk/release/app-release.apk.sha256 \
            --repo "$GITHUB_REPOSITORY" \
            --target "$RELEASE_COMMIT" \
            --title "$RELEASE_TAG" \
            --generate-notes
~~~

发布脚本的完整落实要求：

- 校验 workflow_dispatch 输入和源码关系，不能拿 fork / 任意 PR 的代码执行签名 job。
- 所有可变输入通过结构化 env 传入，shell 正确引用；不在 shell 字符串里拼用户提供的描述、body 或 tag。
- 正式使用锁定 action SHA；部署使用 lock 中 Wrangler，不安装浮动最新版。
- 上述 Deploy backend 步骤加入 checkpoint 成功判定、migration 完整性检查；初次空库的特殊情况明确处理。
- Check API health 后再执行独立 npm run smoke:production；只检查 health 不能证明 auth、D1、diff 都可用。
- 在构建后对比 APK versionName / versionCode 与 tag / 上次 release，检查证书 fingerprint 与既有正式签名一致。
- checksum 文件生成时使用稳定文件名或明确完整路径，README 写出校验方式。
- signed APK 可作为发布产物；DB export、secret、keystore、auth 响应、测试账号数据不进入 artifact。
- release 已存在或迁移 / smoke 失败时停止，不自动覆盖已有发布产物，不把失败标记为成功。

## 12. 「需验证」清单、方法与本轮校验

### 12.1 后续验证矩阵

| ID | 待验证内容 | 具体方法 | 阶段 / 通过条件 |
| --- | --- | --- | --- |
| UI-01 | 新颜色、大字体、无图片排版 | 对比度计算，TalkBack、200% 字体，短 / 长文明暗截图 | P0 / P4，达到第 2 节规格 |
| DB-01 | D1 DDL、约束、trigger、batch / changes | 本地与真实 D1 分别建表；跨账号 FK、非法评分 / 日期、immutable、0 行更新回滚、批次后续失败注入 | P0，全部通过，无孤立记录 |
| AUTH-01 | PBKDF2 迭代次数与免费档 CPU | 真实 Worker（免费档）测 1k / 10k 两档；标准 JS 向量；冷 / 热每场景至少 30 次 CPU / wall / memory；顺带记录原生 cap 作为未来升级依据 | P0，10k 面板稳定落在 10 ms 内，超限则继续下调并回写文档 |
| AUTH-02 | 边缘限流套餐及语义 | 查当前 Rate Limiting binding 文档 / 账户；跨地域测试；D1 全局计数并发测试 | P0 / P2，近似边缘与全局规则职责明确 |
| AUTH-03 | Keystore 与 refresh 可靠性 | API 26 / 35；进程终止、丢响应、同 token 并发、损坏密文、迁移、重装 | P2 / P4，无明文 fallback，无跨账号残留 |
| ANDROID-01 | 固定依赖版本兼容 | 最小工程 debug / release、Hilt + KSP / Room；依赖安全修复核对 | P0 / P1，完整构建成功 |
| DEPLOY-01 | Wrangler / Custom Domain / logs | schema + dry-run、真实 DNS / TLS、关闭 workers.dev、日志不含 q / token | P0 / P5，只有唯一生产主机 |
| DEPLOY-02 | 凭据与共享额度 | 只读查账户 / zone / DB 与账单，限定权限实测；记录其他项目共享量 | P0，无误用其他账号或全区权限 |
| CAPACITY-01 | 体积、rows read / written | 合成 4,500 对象 / 13,500 历史，短文 / 中文 / 长文及 10 历史变体；查 D1 meta | P1 / P3，估算被实测校正，记录上限 |
| NETWORK-01 | 大陆实际网络 | 运营商和接入方式抽测 DNS、TLS、登录、列表；无样本明确缺口 | P4，记录可复现结果与剩余风险 |
| CI-01 | SDK、Node、Actions、路径 | 实际 PR、main、候选 UI、手动 release 全流程；验签与生产 smoke | P1 / P5，代码与文档一致 |
| OPS-01 | 清理、恢复与部署回滚 | 临时数据测试 30 天边界、分块中断、Worker 回滚、临时库 Time Travel 演练 | P3 / P5，恢复不误删其他账号，明确数据损失窗口 |

费用、CPU 和网络测试结果必须写实际观测条件；本地测量不能代替远端，海外测量不能代替国内。

### 12.2 本轮已经完成的文档级校验

本轮只读取资料、编写 PLAN.md，并在**内存 SQLite** 执行本文 DDL；没有生产代码、云资源或真实用户数据写入。

2026-10-31 环境 SQLite 3.53.1 的结果：

- 完整 DDL 成功：**13 张表、29 个显式索引、1 个不可变触发器**。
- 跨账号菜品→餐厅外键拒绝。
- 评分 0、6、4.5 拒绝。
- 非法日期 2026-02-30、2023-02-29、2026-13-01、格式错误和 1900 年以前拒绝。
- 2024-02-29 合法，同日两次到店可保存。
- 菜系显示值与规范化字段须同时有效；全角菜系按 NFKC 规范化后可精确查询，查询计划使用菜系索引。
- 原始 diff 修改被拒绝，revision 说明元数据可更新。
- 空 diff、非数组 JSON 和无效 JSON 被拒绝。
- CAS 更新 0 行导致守卫失败，事务回滚，无业务变更、无遗留守卫行。
- 业务更新成功后，后续 revision 语句失败也会回滚先前业务更新和守卫。
- PRAGMA foreign_key_check 无异常；quick_check 返回 ok。

另外已从 Google Maven metadata 核对第 7 节四个 Android 库 / 插件版本存在。

文档的 22 个 fenced block 配对、Markdown 表格列数、JSON / TOML / YAML 语法均已检查；API 表包含 44 个不重复的 method / path 条目。

这些结果说明本方案 SQL 在当前本地 SQLite 具有预期行为，**不代表真实 D1、Workers 密码性能、Android 构建或部署已经通过**；它们仍按上表进入审批后的验证。

## 13. 参考资料与事实来源

限额和价格按编写日核对，下次实施时重新读取官方文档和真实账户页。

1. [Cloudflare Workers Limits](https://developers.cloudflare.com/workers/platform/limits/)：免费 CPU、内存、请求、Paid 配置上限。
2. [Cloudflare Workers Pricing](https://developers.cloudflare.com/workers/platform/pricing/)：USD 5 基础示例、10 million requests、30 million CPU ms。
3. [Cloudflare D1 Limits](https://developers.cloudflare.com/d1/platform/limits/)：单库大小、单线程、参数数、LIKE bytes、batch 调用计数。
4. [Cloudflare D1 Pricing](https://developers.cloudflare.com/d1/platform/pricing/)：rows read / written、索引计费、包含存储。
5. [D1 Foreign Keys](https://developers.cloudflare.com/d1/sql-api/foreign-keys/)：默认强制外键、defer_foreign_keys、级联行为。
6. [D1 Database / batch / Sessions](https://developers.cloudflare.com/d1/worker-api/d1-database/)：batch 原子回滚与 Sessions。
7. [Workers WebCrypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/)：PBKDF2、HMAC、AES-GCM 等实际支持。
8. [workerd PBKDF2](https://github.com/cloudflare/workerd/blob/main/src/workerd/api/crypto/pbkdf2.c++) 与 [limit check](https://github.com/cloudflare/workerd/blob/main/src/workerd/api/crypto/impl.c++)：原生迭代上限检查；不据此猜测具体生产 cap。
9. [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)：PBKDF2-HMAC-SHA256 600,000 次与 pepper。
10. [@noble/hashes](https://github.com/paulmillr/noble-hashes)：标准无 native 扩展实现；具体发布版本在 P0 固定。
11. [jose](https://github.com/panva/jose)：基于 WebCrypto 的 JWT 工具。
12. [Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)：裸主机 custom_domain、DNS / 证书。
13. [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：密钥存储与硬件支持差异。
14. [AndroidX Security Releases](https://developer.android.com/jetpack/androidx/releases/security)：security-crypto 已弃用，采用平台与直接 Keystore。
15. [AGP 8.9 说明](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Hilt](https://dagger.dev/hilt/)。
16. [Room](https://developer.android.com/training/data-storage/room)、[Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)。
17. [Cloudflare GitHub Actions 部署](https://developers.cloudflare.com/workers/ci-cd/external-cicd/github-actions/)。
18. [GitHub Actions Security](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions)。

## 14. 审批后的执行入口

审批对象是这份具体方案中的新增选择：最终视觉方向 D2「Quiet Grid」、应用名「食记」、用户名密码与恢复码、1.0–5.0 且精确到 0.1 的评分、首版 Room 缓存 / 草稿范围、建议仓库与应用命名，以及 **Workers 免费档 + PBKDF2 10,000 次** 这一安全与成本的取舍。

Android 原生、独立账号、字段级 diff、餐厅级菜品、Workers + D1、唯一 dining.alanvampuke.top 域名、无 R2 / 图片 / 地图 / 社交，均为既定要求，不是待重新选择的项目。

方案获批后从 P0 开始，先验证安全哈希和 D1 原子性，再按阶段交付。正式资源 ID、secret 值、真实性能和国内网络观测只能在授权实施后补充；在此之前，此文档即本轮完整交付。

