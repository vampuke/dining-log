# M1：五类列表首屏完整卡数

状态：**已完成，不减卡数**。20 组同条件比较（5 类 × light/dark × API26/35）全部 after == before；不宣称完整卡数增加。

## 来源与条件

- before 生产源码：`eaa8e4250902e6d01c989a1d6eabbf8f754f8b42`；[基线采集 run 41941966360](https://github.com/vampuke/dining-log/actions/runs/41941966360)。该 run 的两基线 job 各 1/0 通过；实现 job 因测试清理阶段等待空 Compose 根节点被取消，其产物不作为最终通过依据。
- after 生产源码：`5f676f8ade36f69e2d22a187a91f4841bff6d26e`；[修正清理后的断言采集 run 41943522685](https://github.com/vampuke/dining-log/actions/runs/41943522685)。两实现 job 各 2/0：M1 真正执行 `after >= baseline`，另一个为 M2 Apply 矩阵。
- measurement test：`Pr17ListFirstScreenTest.kt` 原样注入实际 checkout 的源码。未复制旧布局到新源码，未修改生产组件；基线使用 `BASELINE_MODE=true` 测量，最终实现使用 `BASELINE_MODE=false` 和由基线产物填写的常量断言。
- 设备：Actions `ubuntu-latest`，Android emulator x86_64 / pixel_2，API26/35，物理 1080×1920，420dpi，density=2.625；竖屏逻辑 fixture 360×640dp，fontScale=1.0，light/dark。API35 与 API26 的系统字体/insets 不同，分别使用各自真实基线，不跨 API 比较。
- fixture：20 张 `pr17-row-1..20`，标题“记录 n”，版本 2，时间 `2026-11-02T08:00:00Z`；相同 rating_tenths=47、dish_count=2、note=摘要，非餐厅 parent=`pr17-parent`。餐厅/搜索无 back，其余有 back；搜索文字“记录”；新增与 BottomTabs 都保留，筛选默认且不展开。
- 所有截图在未滚动首屏采集；tail 截图未用于首屏计数。

## 判定

枚举 unmerged semantics 中所有 `record:` 节点，用 **positionInRoot + size** 构造未裁切卡矩形。列表与宿主的 clipped bounds 取交集，再把 bottom 限定到 `floating-tabs.top`。仅与这个区域相交的实际可见卡进入枚举；只有完整矩形的四边都落在区域内才计为完整。日志同时保留全部可见卡矩形、完整卡 ID 和可见区域。

反例可直接复查：基线 API35/light 餐厅 row-3 未裁切 bottom=1328.5px，可见区域 bottom=1321px，因此不计入完整；基线 API26 到店有 6 张完整卡，证明没有固定查询前三张。阴影/票边沿用产品原样；测试不修改布局。

## 前后卡数与截图

| API / 主题 | 列表 | before | after | before 截图 | after 截图 |
| --- | --- | ---: | ---: | --- | --- |
| 26 / light | 餐厅 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-restaurant-font1.0-light-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-restaurant-font1.0-light-api26.png) |
| 26 / light | 搜索 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-search-font1.0-light-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-search-font1.0-light-api26.png) |
| 26 / light | 店内菜品 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-dish-font1.0-light-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-dish-font1.0-light-api26.png) |
| 26 / light | 到店 | 6 | 6 | [before](M1-before-api26/pr17-list-firstscreen-before-visit-font1.0-light-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-visit-font1.0-light-api26.png) |
| 26 / light | 历史 | 2 | 2 | [before](M1-before-api26/pr17-list-firstscreen-before-history-font1.0-light-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-history-font1.0-light-api26.png) |
| 26 / dark | 餐厅 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-restaurant-font1.0-dark-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-restaurant-font1.0-dark-api26.png) |
| 26 / dark | 搜索 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-search-font1.0-dark-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-search-font1.0-dark-api26.png) |
| 26 / dark | 店内菜品 | 4 | 4 | [before](M1-before-api26/pr17-list-firstscreen-before-dish-font1.0-dark-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-dish-font1.0-dark-api26.png) |
| 26 / dark | 到店 | 6 | 6 | [before](M1-before-api26/pr17-list-firstscreen-before-visit-font1.0-dark-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-visit-font1.0-dark-api26.png) |
| 26 / dark | 历史 | 2 | 2 | [before](M1-before-api26/pr17-list-firstscreen-before-history-font1.0-dark-api26.png) | [after](M1-after-api26/pr17-list-firstscreen-after-history-font1.0-dark-api26.png) |
| 35 / light | 餐厅 | 2 | 2 | [before](M1-before-api35/pr17-list-firstscreen-before-restaurant-font1.0-light-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-restaurant-font1.0-light-api35.png) |
| 35 / light | 搜索 | 3 | 3 | [before](M1-before-api35/pr17-list-firstscreen-before-search-font1.0-light-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-search-font1.0-light-api35.png) |
| 35 / light | 店内菜品 | 3 | 3 | [before](M1-before-api35/pr17-list-firstscreen-before-dish-font1.0-light-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-dish-font1.0-light-api35.png) |
| 35 / light | 到店 | 4 | 4 | [before](M1-before-api35/pr17-list-firstscreen-before-visit-font1.0-light-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-visit-font1.0-light-api35.png) |
| 35 / light | 历史 | 2 | 2 | [before](M1-before-api35/pr17-list-firstscreen-before-history-font1.0-light-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-history-font1.0-light-api35.png) |
| 35 / dark | 餐厅 | 2 | 2 | [before](M1-before-api35/pr17-list-firstscreen-before-restaurant-font1.0-dark-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-restaurant-font1.0-dark-api35.png) |
| 35 / dark | 搜索 | 3 | 3 | [before](M1-before-api35/pr17-list-firstscreen-before-search-font1.0-dark-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-search-font1.0-dark-api35.png) |
| 35 / dark | 店内菜品 | 3 | 3 | [before](M1-before-api35/pr17-list-firstscreen-before-dish-font1.0-dark-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-dish-font1.0-dark-api35.png) |
| 35 / dark | 到店 | 4 | 4 | [before](M1-before-api35/pr17-list-firstscreen-before-visit-font1.0-dark-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-visit-font1.0-dark-api35.png) |
| 35 / dark | 历史 | 2 | 2 | [before](M1-before-api35/pr17-list-firstscreen-before-history-font1.0-dark-api35.png) | [after](M1-after-api35/pr17-list-firstscreen-after-history-font1.0-dark-api35.png) |

## 原始测量日志

- [API26 before](M1-before-api26/pr17-firstscreen-api26.txt) / [after](M1-after-api26/pr17-firstscreen-api26.txt)
- [API35 before](M1-before-api35/pr17-firstscreen-api35.txt) / [after](M1-after-api35/pr17-firstscreen-api35.txt)

断言注释记录基线 eaa8e42 和来源 run：API26 餐厅/搜索/店内/到店/历史为 **4/4/4/6/2**；API35 为 **2/3/3/4/2**，两主题相同。普通字体没有减少完整卡数；部分卡与浮动栏遮挡卡未误计入。此证据只关闭 M1 首屏承诺，不外推为其他页面高度收益、真实横屏或全套业务 CI 通过。
