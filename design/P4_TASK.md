# P4 任务：Android 产品闭环

工作目录 `/root/dining_log`。方案 `/root/dining_log/design/PLAN.md` **只读**，唯一事实来源。
**开工前必须完整读**：`7.1 分层与模块`、`7.2 库选择与版本基线`、`7.3 Room 的范围与离线行为`、`7.4 冲突处理`、`7.5 状态、导航与刷新`、`10.4 P4 端到端路径`。

现状：
- 后端 P3 完整（29 领域端点 + 9 auth 端点，CI 全绿，master `17d8b4f`）
- Android 已有 4 模块（app/core/data/feature），P1 骨架 + P2 auth（Keystore、单飞刷新、登录/注册/恢复三页已做完）
- 视觉 token 已在 `android/feature/.../theme/`（D2 Quiet Grid 明暗两套），认证页已用它
- 参考原型：`design/proto/d2-quiet-grid.html`（含列表/详情/编辑/搜索/历史 5 个画板）

**本期目标**：把 Android 从「只有认证页的骨架」做成「能用完整流程记录餐厅」的产品。

---

## 一、视觉与设计（硬要求）

- 严格用现有 D2 token，**不要引入新的颜色/字号体系**
- **浅色 + 深色必须都正确**（用户手机常开深色，这是硬要求，不是加分项）
- 无图片、无头像、无占位图、无上传入口。只有文字、数字、图标、色块
- 大字体（200%）不破版、不遮挡、按钮可达
- 评分一律 `★ 4.3` 形式（图标+数字），编辑用滑杆 0.1 步进，展示十分位

---

## 二、页面（PLAN 10.4 的完整路径要能走通）

### 2.1 底部三 tab（不是四个）
`餐厅 / 到店 / 我的`

### 2.2 餐厅
- **列表**：搜索框、筛选（类型/菜系/评分区间/未评分/已到店/日期区间）、排序（更新时间 / 评分）、分页
- **详情**：餐厅信息、评分（★+数字+10 格计量条）、评价、备注、菜品列表（二级）、到店记录、修改历史入口
- **编辑**：新建 / 修改；**菜品在餐厅编辑内增删改**（菜品不是独立 tab，是餐厅的二级页）
- **删除 / 恢复**：二次确认，回收站

### 2.3 菜品（餐厅二级页）
- 列表（店内）、详情、新建/编辑/删除/恢复
- 字段：名称、评分、短评
- **不接收 visit_id**，菜品只归餐厅

### 2.4 到店
- 全局时间线（可按餐厅筛选）、日期、餐次、备注
- 新建/编辑/删除/恢复
- 同日多次到店都要保留

### 2.5 修改历史
- 列表按时间倒序，显示「3 天前 · 评分 4.0→4.3」这类摘要，可展开绝对时间
- 详情看完整 diff（旧值/新值对照），长文折叠
- 可加说明、隐藏、恢复
- **从历史重新应用字段**：先显示当前值与拟修改值，只恢复选中字段，提交时重新 CAS
- 隐藏历史普通列表不显示，管理页可查看并取消隐藏

### 2.6 搜索
- 首页搜索聚合：搜餐厅名/类型/菜系/地址/评价/备注/**菜品名/菜品短评**
- 返回命中类型（`餐厅匹配` / `菜品匹配` / `评价中含` / `备注中含`）
- 菜品命中归入所属餐厅，**不出现全局菜品结果**
- 输入 300ms debounce

### 2.7 我的
- 账号信息、session 列表与撤销、修改密码、退出登录
- 主题切换（如果原型里有；没有就不加）
- 关于/版本

---

## 三、Room 缓存与离线（PLAN 7.3，这节是重点）

### 3.1 读流程
1. 页面先订阅 Room Flow，立即显示缓存
2. 同时请求网络，成功后**事务更新**缓存
3. 网络错误保留已有内容，显示「离线缓存 / 上次更新」，可手动重试
4. 本地搜索只搜已缓存内容，**明确提示范围**，不把「没缓存到」说成「服务器没有」
5. 联网后只刷新第一页和已打开详情，**不要后台反复全量拉取**

### 3.2 写流程（草稿）
1. 输入自动存 Room 草稿，约 500ms debounce，**不产生服务端历史**
2. 点保存时检查网络，生成/固定 `Idempotency-Key`，原请求存进草稿状态
3. 成功后写缓存、删草稿、必要时重读父餐厅 stats
4. 断网显示「已保留草稿，联网后再保存」，**不显示为已同步**，不后台自动上传
5. 响应丢失 → 状态 `result_unknown`，重试用**原 payload + 原 key**，7 天内可拿回
6. 想改 `result_unknown` 草稿 → 先确认原请求结果，再生成新 payload/key，**不能旧 key 配新内容**
7. 超 7 天不盲重放，先读服务器当前值展示比较，由用户确认新保存

### 3.3 Room 表
主键含 `account_id`；摘要与详情分开存（摘要不能截断文字覆盖详情）；摘要 version > 详情 version 时标记详情过期。**不要存密码或 token 到 Room。**

**首版不做**：完整离线优先双向同步、outbox 自动重放、跨设备草稿同步、CRDT。

---

## 四、冲突处理（PLAN 7.4）

409 `VERSION_CONFLICT` 时展示三栏：
- `base_snapshot`：用户开始编辑时看到的值
- `server_current`：服务器当前值
- `local_draft`：尚未提交的编辑值

只改不同字段可给可合并建议，但**用户仍要确认**；同字段冲突**必须选择**。重新提交用 `server_current.version` + **新**的 Idempotency-Key。

删除冲突 / 父餐厅被删：**不做合并后强制保存**，提示先恢复或刷新，草稿保留。

---

## 五、状态与网络（PLAN 7.5）

- `StateFlow<UiState>` 保留 `items / is_refreshing / is_loading_more / is_offline / error / query`，**不能用一个 Loading 清掉旧列表**
- 用 `collectAsStateWithLifecycle`，输入与网络**不在 composable body 发起**
- 搜索 300ms debounce，筛选切换用 `flatMapLatest` 取消旧查询，列表按 ID 去重
- 根导航随 `SessionState` 切换；登出/注销清空业务 back stack；恢复成功进主页，返回不回密码页
- **独立 AuthClient**（无 access 拦截、无 Authenticator）与 ApiClient，防刷新递归
- `RefreshCoordinator` 单飞；每个请求最多一次认证重放
- Authenticator 按稳定错误 code 区分 `TOKEN_EXPIRED` / `SESSION_REVOKED`；先比较请求 token 与当前 token，避免旧请求 401 清掉新会话
- 超时：connect 10s、普通 call 20s
- 只读 GET 最多 2 次带抖动重试；写入仅在幂等 key 稳定且 payload 未变时重试；429 尊重 `Retry-After`
- targetSdk 35 edge-to-edge：system bars、IME、返回手势都处理好，**保存按钮不能被键盘或导航条挡住**
- 正式环境禁 cleartext；Manifest 只要 INTERNET / ACCESS_NETWORK_STATE，**无相机/定位/存储权限**

---

## 六、测试

### 6.1 JVM 单测（core/data，能覆盖的都写）
- 状态合成：加载中保留旧 items、离线标记、分页追加去重
- 草稿：debounce 落 Room、key 生成与固定、`result_unknown` 重试用原 key、换 payload 生成新 key
- 冲突：三栏数据构造、只改不同字段的可合并建议、新 key 生成
- Room：account_id 隔离（登出 A 登入 B 无残留）、摘要/详情不互相覆盖、详情过期标记
- 刷新协调：并发 401 只刷新一次（P2 已有，别退化）

### 6.2 模拟器 instrumented 测试（CI 已有 android-auth-instrumentation 基础设施，扩到业务页）
至少覆盖：
- **浅色和深色**下：列表、详情、编辑、搜索、历史、冲突弹窗，文案与背景对比度可读（沿用 P2 auth 页断言色值的方式）
- 大字体 200% 下：编辑页保存按钮可达、输入框不裁字
- 键盘弹出时保存按钮不被遮挡
- 空态、无结果、离线标记各自可见
- 端到端：注册 → 新增餐厅+首访 → 加菜品 → 再次到店 → 搜索 → 改评分 → 看历史 → 重新应用 → 删除 → 恢复

需真机的（弱网、真实三网、TalkBack、进程终止、不同厂商）写进 `P4_BLOCKERS.md` 的「需真机」清单，**不许声称已验证**。

---

## 七、硬约束

- **本机 956MB**：禁止本机跑 `npm install` / `gradle` / `docker` / `vitest` / 模拟器。你只写代码 + commit + push，**所有构建测试在 GitHub Actions**。
- 不许降断言阈值、不许删测试、不许 skip 让 CI 变绿。
- 不许改 `backend/migrations/0001_initial.sql`（SHA 必须 `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`）和 `design/PLAN.md`。
- 不动后端业务逻辑（P3 已定稿）；若发现后端 bug 才能改，且要在 `P4_DONE.md` 单列说明。
- 不部署 Cloudflare、不写真实 secret。
- 走**新分支 → PR → 合并**，不直推 master。

---

## 八、完成判据

1. CI 全绿（后端不回归、android 构建 + lint + JVM 测试 + 模拟器测试），PR 已合并
2. `P4_DONE.md`：
   - 页面清单与实际完成度
   - 10.4 端到端路径逐步 → 对应测试
   - **浅色/深色/大字体/键盘** 各自验证方式与结果
   - 离线/草稿/冲突三种机制的实现位置
   - verified vs unverified（真机项一律 unverified）
   - 后端是否被改动（有就列）
3. 新 APK 发布到 `/var/www/html/dl/dining/`，命名 `shiji-<短sha>.apk` + 软链 `shiji-debug.apk`，sha256 记 `SHA256SUMS`
4. 做不到的写 `P4_BLOCKERS.md`

**不要夸大。** 模拟器测试不能冒充真机，CI 的 D1 pool 不能冒充真实 Cloudflare。
