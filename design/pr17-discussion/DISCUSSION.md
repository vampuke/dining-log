# PR17 密度评审定稿

评审基线：`f371a9b8962814d3594bb258e29998d024542783`，2026-11-04。已完整阅读 `design/PR17_AUDIT.md`，并逐行阅读 feature 下所有 UI Kotlin 文件。本阶段仅产出本文件与 `PLAN.md`；没有实现、运行新版 UI 或测得新版高度。以下收益是基于代码结构的判断，验收需使用相同数据实际测量。

明确裁决：**G1 不做；E4 选变体 B（竖排配对，标签到值 2dp）；F2 不做，保留文字按钮；B1 为 padding 7dp、外层及评分列间距 3dp、左右列间距 8dp；B2 为列表 item 间距 7dp。** G2 仅宽容器横排，G3 在普通字体且容器足够宽时横排。详情页全部必做项保留，布局细节以 [PLAN.md](PLAN.md) 为准。

## 证据索引与审计纠错

本文缩写均指真实文件；`DE:80–81` 表示索引中 DE 文件的第 80–81 行。各条同时引用源码片段，行号均为上述基线的行号。

| 缩写 | 文件 |
|---|---|
| DE | [restaurants/DetailEditScreens.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt:22) |
| LS | [restaurants/ListScreens.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt:29) |
| HS | [revisions/HistoryScreens.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/revisions/HistoryScreens.kt:22) |
| SS | [settings/SettingsScreen.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/SettingsScreen.kt:29) |
| UD | [settings/UpdateDialog.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/settings/UpdateDialog.kt:19) |
| AS | [auth/AuthScreens.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/auth/AuthScreens.kt:73) |
| BC | [components/BiteComponents.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt:66) |
| QC | [components/QuietGridComponents.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridComponents.kt:41) |
| HT | [components/HistoryTimeText.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/HistoryTimeText.kt:20) |
| PR | [ProductRoot.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt:49) |
| VM | [ProductViewModel.kt](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductViewModel.kt:19) |
| CP | [core/Product.kt](/root/dining_log/android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Product.kt:65) |

初稿有以下事实偏差，实施不能照抄：

- C1 实际在 SS:81–96，C2 在 SS:98–107；主题在 SS:74–77，版本和标语在 SS:79–80。初稿设置页行号不是当前版本。
- LS:149–155 的统计条是 `padding(vertical = 3.dp)`，内部两段文字，只有分隔线 `height(28.dp)`，**没有固定 34dp 高度**。LS:68–80 显示统计条和筛选行之间还隔着搜索框；两者并非直接相邻。
- F1 实际是 AS:95 的 `AuthPage.entries.filter { it != page }`，每页显示另外两个入口，并非同时竖堆三个入口。
- BC:153–159 的 BiteTextField 只有 `leadingIcon`，没有 `trailingIcon`；[QuietGridIcons.kt:15–89](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/QuietGridIcons.kt:15) 没有眼睛图标。
- QC:240–245 的 ExpandableValue 只是 `Column`、240 字预览、独立展开按钮，没有初稿所称的“收起态行首”结构。
- QC:99–105 的 PageHeader 没有 trailing slot，需真正新增可选 slot；BC:81 的 Sticker 硬编码 `maxLines = 1`，单纯换成 FlowRow 并不能解决单个贴纸截断。
- A2 的字段来源是 `editableFields`，CP:65–69：餐厅只剩类型、菜系、地址；到店只剩日期、餐次；菜品没有额外信息字段。`dish_count`、`visit_count`、`last_visit_date` 不是该信息卡字段，不能凭空塞进其两列。

## A：详情页逐条裁决

| 项 | 裁决、源码依据、理由与最终布局 |
|---|---|
| A1 | **同意。** DE:80–81 的 `fillMaxWidth().testTag("edit")` 后跟 `testTag("delete")`，确实各占一行。普通字体同一行，编辑扩展、删除按内容宽度，间隔 12dp；窄容器/大字体竖排。删除仍为 danger 色并保留 DE:127 的 ConfirmAction，不把确认流程挪进编辑。 |
| A2 | **同意，限定实际字段。** DE:61–64 的标签与 `GridText(displayValue(...), Modifier.testTag("detail:$field"))` 分离，产生两段竖堆。餐厅第一行类型/菜系，各自“标签：值”同行；地址第二行通栏，保留地址 Sticker。到店日期/餐次同一行；菜品无空卡。仅渲染原逻辑筛出的非空字段，缺一项时另一项通栏。大字体单列，值可多行；tag 仍在值上。 |
| A3 | **同意。** DE:93–94 的新增通栏按钮与管理文字按钮竖堆；按 A1 的响应式规则合并。回调为空时不留空列，保留 `add-dish`。管理按钮文案和行为保留。 |
| A4 | **同意。** DE:104–105 的 `add-visit`、`visit-history` 各占一行；主操作再次到店扩展、查看全部按内容宽度，大字体回退。`查看全部到店记录` 比新增文案长，不能靠固定等分宽度处理。 |
| A5 | **同意，细节改定。** DE:45–55 将眉题、标题、带评分条的 Score、verdict、日期、修订章、更新时间逐个交给 BitePanel，BC:70 又给每个直接子项 8dp 间距。眉题合并版本；标题左、仅数字 Score 右；评分条从 QC:132–136 提取可复用 RatingMeter，下方 FlowRow 顺序为评分条→verdict→到店日期→修订章→更新时间。标题不可省略，元信息按可用宽度自然换行，不强求最多两行。修订章改成“已修订”，版本只在眉题出现。 |
| A5b | **同意。** DE:38 的 `detail-refresh` 独占一行；移到 DE:34 的 PageHeader trailing slot。刷新中状态/禁用规则保留，无缓存也能刷新。大字体改成标题块下一行的刷新操作，避免返回、标题、刷新三者争宽。 |
| A6 | **同意。** DE:112–117 是标题后带 rail 的时间轴，按钮在 rail 里。将 `history` 移到“修改历史”标题行右侧；rail 仅保留当前版本及更新时间。大字体标题和按钮分行，阅读顺序不变。 |
| A7 | **同意拆卡，反对默认两张 BitePanel 就能保证不增高的假设。** DE:118–121 的条码、标语、下票边与时间轴共用卡；BC:69 的每张卡有上下共 28dp 内边距，直接多一张默认卡会增高。改成时间轴卡＋局部紧凑收尾卡，间隔 12dp；收尾卡水平 padding 14dp、竖向 6dp、内部间隔 4dp，条码仍 20dp（BC:122），下票边接收尾卡。验收比较两卡及间隙的合计高度，不能只量时间轴卡。 |
| A8 | **同意。** DE:101–103 的标题、次数、`Sticker("最近到店 $it", c.mint)` 三段堆叠；次数并入标题“到店记录 · N 次”，最近日期贴纸在该标题行右侧。大字体标题/贴纸分行，贴纸自身允许多行。 |
| A9 | **同意合并提示，不照搬无计数时把管理再搬一次的方案。** DE:92 已有“店内菜品 · N 道”，DE:95 仅 `dishes.items.isEmpty()` 时提示管理位置；与 A3 再放管理会重复。统一一段 caption：有计数非空列表“店内菜品 · N 道”；有计数空列表追加“· 菜品在本餐厅内管理”；无计数空列表只显示原提示；无计数非空列表不额外生成文案。不能用预览列表大小冒充总数，不能将空预览等同于总数为零。 |

## B–G：逐条裁决

| 项 | 裁决、源码依据、理由与替代方案 |
|---|---|
| B1 | **同意保守微调。** LS:164–179 的卡已是左摘要、右 Score/verdict；LS:167–172 已有大字体竖排。改 LS:165 padding 8→7dp，LS:166 外间距 4→3dp，LS:173 左右列 10→8dp，LS:176 评分列 4→3dp。数字与 verdict 不横排；保留普通字体标题 1 行/摘要 2 行以及大字体无限行（LS:188–213）。旋转和描边外溢需目测，不能继续压缩到 6dp。 |
| B2 | **同意 6→7dp，纠正原因。** LS:45 的 item 间距 6dp 已存在，不是“无空隙”。7dp 是给描边/投影稍加余量，统计条保持独立、不固定高度。该值会同时影响所有列表的记录间距，增高是有意的 1dp 微调；验收首屏可读卡数和搜索区呼吸感。 |
| C1 | **同意，有完整标签。** SS:81–93 两个 Row 分别放开关、检查按钮。普通字体把“启动时检查更新”＋Switch 组成左组，检查按钮在右，左组必须保留文案及 `update-checks` 的语义；大字体分两行。最新/失败状态继续在下方，不能被挤进开关组。 |
| C2 | **同意保持独占一行并通栏；不新增副文案。** SS:99 的 logout 是包内容宽度，SS:107 的确认框已写“将清理此账号的本机缓存”。只加 `fillMaxWidth()`，保留当前居中文案、danger 色和确认框，不在页面重复缓存说明。独立出口对删除会话区块后的设置页仍合适。 |
| C2b | **同意保持。** SS:80 实际是一个含 `\n` 的 GridText，版本＋标语，非两张冗余卡；文字允许自然换行。 |
| C2c | **同意保持分组，纠正“面板”说法。** SS:74–76 是一个 LazyColumn item 内标题＋ChoiceChips，并没有 BitePanel。QC:217–218 已用 FlowRow 和 48dp 最小高度，不增添外卡。 |
| D1 | **同意。** DE:140 `spacedBy(16.dp)`→12dp，只改编辑页 item 间隙；不削减字段输入高度、错误区或 SaveBar。 |
| D2 | **同意。** DE:205–206 合并 `edit-add-dish` 与 `edit-manage-dishes`，采用 A3 的响应式规则，保留 `!state.saving` 禁用和 DE:203 的新餐厅先保存提示。 |
| D3 | **同意保持布局。** DE:182–186 已是 `weight(1f)` 摘要＋toggle，确实不是两条操作堆叠。大字体摘要可多行；收起/展开、错误自动展开（DE:179–180）及收起后错误（DE:194–195）保留。 |
| D4 | **同意保持。** DE:144 的“点击保存后才会提交”独立提示说明提交时机；不并入页头或藏在 SaveBar。 |
| E1 | **同意。** HS:31–35 的刷新独占一行；使用与 A5b 相同的 PageHeader slot 和回退规则，保留 entity_label 副标题。原按钮没有 tag，新增 `pr17:history-refresh`。 |
| E2 | **同意，但保留两个文字按钮的视觉等级。** HS:58–59 的 history-note/hide-history 竖排；普通字体同一行，说明按钮扩展、隐藏包内容，大字体分行。HS:70 的隐藏确认仍在；不把说明按钮升级成黄色主按钮。已隐藏状态 HS:61–62 仍是说明＋通栏取消隐藏。 |
| E3 | **同意保持。** HS:64 的 reapply 是主操作通栏；历史说明/隐藏行之后另行呈现，避免与直接编辑说明混淆。 |
| E4 | **同意变体 B，反对变体 A 和 C。** HS:46–49 的原值/新值标签是默认 bodyMedium，外层卡间距来自 BC:70 的 8dp。QC:243–245 是可达 240 字的段落＋展开操作；A 把长预览和值标签/操作强凑一行并无充分收益，且需要改通用展开结构；C 的 4dp 配对不如 B 清楚。每一对用独立 Column，标签 caption 到值 2dp，原/新对之间仍 8dp，ExpandableValue 内部 6dp 与 240 字规则不改。不是把整张卡所有间距改为 2dp。 |
| E5 | **同意。** HS:44 已有 BitePanel 边框和 rail，HS:50 底部 HorizontalDivider 没有分隔后续卡内内容，移除。HS:142 应用弹窗的 divider 分隔真实字段，没有外卡，不属于这项，保留。 |
| E6 | **同意，修复时间自身截断。** HS:39–41 时间、版本范围独占两段；普通字体时间左（弹性宽度）、版本右，大字体上下排。HT:25 自带 48dp 点击区域，HT:32 却固定 `maxLines = 1`；大字体时间应允许多行，不能仅依赖父容器换行。 |
| F1 | **同意保持。** AS:94–97 明确注明小屏/大字体可达性，保留两个切换入口竖排和 loading 禁用。 |
| F2 | **反对本期改眼睛按钮，明确不做。** AS:133–137 的显示/隐藏按钮能完整表达状态；BC:159/187 没有 trailingIcon 转发，图标库也无眼睛。放 leadingIcon 会误导输入用途；新绘图资产及输入框接口改造超出这次局部收益。保留现状，仍受 loading 控制。 |
| G1 | **反对，明确不做。** BC:69–70 的默认 14dp/8dp 不变。详情页 A1/A3/A4 各减少一条按钮行，A2/A5/A6/A8/A9 改结构，已针对真正堆叠来源；再全局收紧会波及评价、备注、认证等面板。下面补漏的嵌套评分框应移除一层，而不是把所有面板减 2dp。没有已测得的“全局收益最大”证据。 |
| G2 | **反对手机上直接等分，采用条件横排。** LS:237–238 使用完整 RatingEditor；QC:167–185 的滑条、QC:191–195 的微调/清除都在其内部。两列等分会显著缩短手机滑条，8dp 的中间间距不能解决。最终：fontScale≤1.3 且父内容宽≥492dp 时两个 240dp 以上编辑器并排（间隔 12dp）；否则完整竖排。标签合入各编辑器并去冗余内框，见 N3。 |
| G3 | **同意条件横排。** LS:234–235 的两个 Checkbox 行可组成两列，保持“只看未评分”“限定评分区间”全文。fontScale≤1.3 且父内容宽≥320dp 时各 weight(1f)、间隔 12dp；否则竖排。单行内部 Checkbox 与可换行标签居中对齐，去掉固定 `padding(top = 12.dp)`；原互斥回调及 tags 不变。 |

## 补漏：逐文件复核所得

以下“实施/保持”均为明确结论，PLAN 收录全部实施项。阈值与新间距是本次设计选择，不是对现有尺寸的测量声明。

| 项 | 真实代码及问题 | 裁决与理由 |
|---|---|---|
| N1 | UD:52–53 连续两个 `fillMaxWidth()` BiteTextButton：“打开下载页”与“稍后/后台继续下载”，每个最少 48dp（BC:148）。 | **同意实施。** 放同一响应式次操作行，打开下载页扩展，关闭按内容；本弹窗内容宽≥240dp且普通字体时横排，大字体/不足240dp分行（区别于页面按钮对280dp阈值）。下载/重试/安装主操作（UD:43–49）与取消（UD:39）继续独立，避免混淆“取消”和“后台继续”。 |
| N2 | UD:34–35 的阶段/百分比、已下载/总大小各为一段，受 UD:30 的 12dp 直接子项间距影响。 | **同意实施。** 普通字体将两者放 FlowRow，横向 12dp、换行 4dp，进度条另行；必须保留“百分比待确认”“总大小未知”和校验阶段。不缩字号，不隐藏字节数。 |
| N3 | DE:149–151 `BitePanel { RatingEditor(...) }`；LS:222＋237–238 外筛选框中又放 RatingEditor；QC:142 的 RatingEditor 自带 `brutalBox(...).padding(14.dp)`。这是真实的双层框和双层内边距。 | **同意实施。** RatingEditor 增加默认 `framed=true` 与默认原标签参数；编辑页和筛选页传 `framed=false`，只保留外框。筛选传“最低评分/最高评分”替换内部“评分 · 选填”，删除外部重复标签；其它调用维持默认。不改 0.1 步进、未评分轨道和设置评分入口。 |
| N4 | QC:191–195 已有减/加一行，清除评分再独占一行。 | **同意实施。** 已评分且普通字体、编辑器内容宽≥280dp 时三操作一行，左右间隔 8dp；不足时保持减/加行＋清除下一行；fontScale>1.3 三操作竖排。未评分仍只显示设置评分，不能为了密度创造一个有效分值。 |
| N5 | HS:30 历史详情 item 间隔仍为 18dp，比 DE:33 的 12dp 更松，diff 多时累积；初稿只审了卡内。 | **同意实施。** 历史详情页 18→12dp，与详情页一致；ReviewDialog 的内容间距不跟随全局改。 |
| N6 | QC:99–105 的 PageHeader 是独立边框；LS:60–65 外层 Row 把它放入 Box(weight) 后，再在卡外放新增按钮。LS:48–57 首页品牌行也把新增按钮固定在右侧。 | **同意实施。** 非首页列表的新增放新增 trailing slot，减少页头容器套层并统一窄屏回退；首页保留品牌/图标，普通字体同排，fontScale>1.3 或不足宽时新增独立通栏。保持 `add`、`page-title`、`back` 原名。 |
| N7 | HS:137–140 应用字段预览标签和值分别是 ReviewDialog 滚动 Column 的直接子项，HS:81 为每个子项加 14dp；HS:98–100 比较栏标签/值间隔 6dp。 | **同意实施。** 应用弹窗的“当前值/拟修改值”采用 E4 的 caption＋2dp 配对，两个配对间 8dp；比较栏仅将单元格内 label/value 间距 6→2dp。保留跨字段 divider、双栏宽度及横向滚动（HS:89–103），不改变字段选择和保存。 |
| N8 | BC:81 Sticker 一行限制；HT:32 时间一行省略。FlowRow 只能换孩子，不能保证孩子内部文字完整。 | **同意实施。** Sticker 增加 `maxLines` 参数，默认无限行；不改样式或装饰。HistoryTimeText 仅大字体无限行，普通字体一行行为保留；A5/A8/E6 用足够宽的回退布局。 |
| N9 | DE:35–40、HS:32–36 的状态 item 原来还承载刷新按钮；挪走后，在线、新缓存、无错误时 CacheStatus（QC:113–116）不输出内容。 | **同意实施。** 状态 item 按真实可见内容条件建立：offline/stale/error 或详情空态；否则不建立，避免空 item 仍参与 LazyColumn item 间距。不删状态文本或 tags。 |
| N10 | UD:29 `wrapContentHeight().heightIn(max = maxHeight * 0.85f)`、UD:30 全内容可滚动、16dp padding。 | **同意保持。** 已是内容自适应＋长内容上限，没有固定大空白或多余卡。不改高度比例、Dialog 密度继承（UD:25–26）、滚动范围或底部 16dp。已有 P8VisualTest:137–178 专门验证这些行为。 |
| N11 | HS:78–83 ReviewDialog 固定 `fillMaxHeight(0.94f)`，外 12dp＋内 16dp，返回独占一行；短冲突可能留大空白。 | **确认遗漏，本期保持。** 这里是滚动比较内容＋固定保存 footer，HS:81 的 weight 保证长 diff 与底部操作共存，HS:89–103 还要求横向比较。改为 shrink-wrap 需要独立验证二维滚动和低高度窗口，收益与本期详情密度不直接重合；不将这种固定工作区误判为冗余卡。 |
| N12 | SS:120–123 修改密码 footer 的确认通栏＋关闭另行；SS:127 内容间隔 16dp。 | **同意保持。** 关闭和有副作用的密码提交分离，密码未知结果重试文案可很长；固定 footer、IME 和安全标志需维持。SS:113–114 的输入仅存 ViewModel 内存，不参与密度重构。 |
| N13 | AS:75 全页间隔 18dp；AS:104–110 每个恢复码独占一行并有复制/已保存操作。 | **同意保持。** 恢复码是一遍展示、需逐条抄录的敏感信息，不压成网格；F1/F2 留存后不对全认证页额外压间距。 |
| N14 | QC:79–98 将浮动栏高度＋系统导航栏＋间隔放入可滚动 content padding；QC:229–235 SaveBar 有 navigationBarsPadding 和最多 112dp 的滚动错误区域。 | **同意保持。** 列表尾部留白是避免胶囊遮挡最后一个按钮，不能拿它当可删空白；SaveBar 的消息上限避免错误吞掉保存按钮。PR:193/213 的 64dp/48dp 是导航与触达下限，不压缩。 |
| N15 | LS:242–243 日期起止字段竖排；DE:148–166 常规字段一项一张卡、地址 2 行、评价/备注 4 行；DE:68–75 评价和备注分卡。 | **同意保持。** 日期含格式提示、错误，半宽编辑会降低输入可读性；常规字段卡有独立输入/错误/选填职责，先移除 N3 的真实嵌套框，不无差别去外卡或合并长评价和备注。 |

补充阅读覆盖：PR:49–225 的导航与 BottomTabs；HT:20–34；[NavigationMotion.kt:14–25](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/NavigationMotion.kt:14)、[SecureScreen.kt:16–32](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/SecureScreen.kt:16) 无布局密度来源；QuietGridIcons 全文件仅矢量；theme 的 Colors/Shapes/Theme/Typography 全文无独占操作行。字体行高来自 [Typography.kt:23–52](/root/dining_log/android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/theme/Typography.kt:23)，本期不通过缩小字号和行高达成密度。

## 已拍板事项：直接纳入与实施约束补齐

滑动 180 天与删除设置页两区块不参与投票。详情 A1–A9/A5b 必做，以上评审只是落实具体排布。下一阶段全量实施输入见 PLAN。

1. [session.ts:53–60](/root/dining_log/backend/src/auth/session.ts:53) 新会话当前 `now + 2592000`；[rotation.ts:25–35](/root/dining_log/backend/src/auth/rotation.ts:25) 当前仍将 `session.expires_at` 用作新 token 上限、响应，并只写 last_refreshed_at。计划统一为成功刷新时 `now + 15552000`，在原 guarded batch 同时写 session 及新 token，响应与加密重放结果用新时间。保留 activeSession 的 `expires_at>?`（session.ts:25–28），过期不能复活，同 request-id 重放不再续期（rotation.ts:9–16）。`newRefresh` 的 7 天 token 空闲上限（session.ts:46 的 `604800`）保持：180 天是 session 的滑动上限，不承诺一个未刷新的 token 可闲置 180 天。既有 auth.test.ts:253–262 不改，新增两个测试隔离验证 session 滑动与期限边界。
2. SS:49、58–70、103–106 删除；SS:99/107 的 logout 与确认保留。VM:19/294–307、PR:135–138、[Repositories.kt:22–26](/root/dining_log/android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Repositories.kt:22)、[RetrofitAccountSecurity.kt:24–49](/root/dining_log/android/data/src/main/kotlin/top/alanvampuke/dininglog/data/auth/RetrofitAccountSecurity.kt:24) 删除会话列表/撤销链路；后端 [routes.ts:158–170](/root/dining_log/backend/src/auth/routes.ts:158) 两端点保持。
3. “所有 tag 保留”和删除会话行有一处具体冲突：SS:67 的 `revoke:${session.id}` 随已授权功能删除而退役，其余全部保留。不能用注释、不可见节点或死代码伪造保留。[verify_bite_visuals.py:98–102](/root/dining_log/android/tools/verify_bite_visuals.py:98) 从源码正则提取 tags，[p7_baseline.json:16](/root/dining_log/android/tools/p7_baseline.json:16) 仍要求这个 tag，故计划只删该基线条目，守卫脚本原样保留。
4. [P6UiTest.kt:145–148](/root/dining_log/android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/P6UiTest.kt:145) 不仅等 12 sessions，还固定 `performScrollToIndex(10)`；删除后设置页 item 数不足。必须改成等 profile 加载完成、设置页滚到 `logout`，保留重选 tab 不重建/不刷新断言。[ProductEndToEndTest.kt:343–345](/root/dining_log/android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ProductEndToEndTest.kt:343) 改直接 DELETE API，同时断言另一会话真的失效，不能只删该段覆盖。
5. [p8_protected_hashes.json:22/25/57](/root/dining_log/android/tools/p8_protected_hashes.json:22) 已保护 rotation/session/auth 测试。同一实现提交更新对应哈希；不改守卫脚本、不移除保护路径。新增共享常量放 session.ts 导出，避免新增受保护后端文件导致名单键不完整（守卫 :116–117 检查路径集合）。

## 本阶段验收结论

已完成源码阅读、逐条决策和下一阶段验收设计；尚无新版截图或新版测试通过证据。本阶段只检查两个文档的决策覆盖、行号与源码片段，以及工作区改动范围。实现阶段必须执行 PLAN 中的测试和视觉验收，不能以本评审代替验证。
