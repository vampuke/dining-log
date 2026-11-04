# PR17 最终实施计划（下一阶段唯一输入）

基线 `f371a9b8962814d3594bb258e29998d024542783`。本文件包含完整改动、保留范围、测试迁移及验收，无需再阅读审计初稿来决定变体。行号指改动前源码；实际编辑按函数/片段定位。当前阶段只写文档，下面均为下一阶段工作。

## 目标与最终裁决

落实详情页 A1–A9/A5b 全部必做项，局部收紧编辑/历史详情；补齐更新弹窗次操作与嵌套评分框问题；执行会话滑动 180 天和删除设置页会话管理。

- B1：列表卡 padding **7dp**，外层纵距 **3dp**，评分列纵距 **3dp**，左右列间隔 **8dp**；B2：列表 item 间距 **7dp**。
- G1 **不做**：BitePanel 默认 padding **14dp**、spacedBy **8dp** 不变。
- E4 **变体 B**：原值/新值竖排配对，caption 标签到值 **2dp**；配对之间 **8dp**。
- F1/F2 **保持**：认证页切换入口与显示/隐藏密码文字按钮仍竖排；不新增眼睛图标、不改输入框图标接口。
- G2：仅普通字体且父内容宽≥**492dp** 时最低/最高评分横排；其余竖排。G3：普通字体且父内容宽≥**320dp** 时两个 Checkbox 组横排；其余竖排。

下列文件全在 `android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/` 下：`DE=restaurants/DetailEditScreens.kt`，`LS=restaurants/ListScreens.kt`，`HS=revisions/HistoryScreens.kt`，`SS=settings/SettingsScreen.kt`，`UD=settings/UpdateDialog.kt`，`BC=components/BiteComponents.kt`，`QC=components/QuietGridComponents.kt`，`HT=components/HistoryTimeText.kt`，`PR=ProductRoot.kt`，`VM=ProductViewModel.kt`。这些是明确路径缩写，以下每个任务给出原行号和原片段。

## 共用布局、testTag、大字体规则

1. 所有宽度判断使用 **BoxWithConstraints 得到的内容可用宽度**，扣除已存在的外边距/卡 padding 后判断，不使用屏幕宽度代替。`fontScale>1.3f` 一律走本文指定的大字体布局；1.3 本身属于普通字体。阈值为新设计选择，需要以验收验证。
2. 按钮对统一规则：普通字体且内容宽≥280dp，Row 中左主/第一操作 `weight(1f)`，右次操作包内容，间隔 12dp、居中对齐；否则 Column 间隔 8dp，两个按钮都通栏。第一操作原本为 BiteTextButton 时仍是 BiteTextButton。保留按钮组件自带 `heightIn(min=48.dp)`（BC:138/148），只设最小高度，不设固定高度。所有文字可换行，不用 ellipsis、省略文案或缩字号解围。回调为空时不占位，剩余按钮通栏。UD底部两个文字次操作有两处例外：内容宽阈值240dp、Row底部对齐，其余规则相同；Dialog窗口比页面窄，需要单独验证此阈值和关闭按钮下方16dp余量。
3. 标题＋trailing 的共用规则：普通字体且内容宽≥280dp 才同排，标题/标题块 weight，尾部按内容；不足或大字体时标题块在上、trailing 在下（按钮通栏、非按钮 metadata 行末对齐），间隔 8dp。保留完整标题和副标题，无硬性“一行/两行”上限。新 PageHeader slot 默认空，不改变未使用 slot 的正常布局。
4. 新横向内容不截断；短元信息用 FlowRow，横向 12dp、换行 4dp；长值/标题用弹性宽度与无限行，大字体优先单列。不能把 parent FlowRow 当成单个 Sticker/时间文本可换行的保证。
5. 所有现存 tags 名称、插值及对应操作/值语义原样保留，标签值移动时 tag 跟值走、操作移动时 tag 跟点击节点走。唯一退役 tag 是已授权删除功能的 `revoke:${session.id}`。不搬到注释、隐藏节点或死代码。原字面量 `testTag("...")` 保留在相应源码文件，动态的 `testTag(tag)` 保持原约定，不为重构改成只在另一文件调用统一 tag 工厂。不要给父容器做 merge/clear semantics 导致子按钮/值不可查找。
6. 新 tags 使用 `pr17:` 前缀。明确需要新增：历史刷新 `pr17:history-refresh`，三段小票测量容器 `pr17:receipt-head`、`pr17:detail-history-panel`、`pr17:receipt-footer`。如新增用于验收的操作/容器 tag，也遵守此前缀。保留 `page-title`、`back`、所有 screen/scroll tags 和原来的字段/展开 tags。
7. 描边、硬投影、主题 token、字体字号/行高、矢量资源均不改，不新增位图。QC:72–98 的 IME/insets/浮动栏余量算法以及 SaveBar（QC:227–238）不改。

可在 QC 增加小型私有/内部响应式布局 helper 复用上述按钮对规则；不要使 helper 接收旧 tag 字符串并吞掉调用处的字面量 tags。新增布局需要的 ExperimentalLayoutApi opt-in 按实际 Compose API 加到相应函数/文件。

## 1. 全局组件：局部能力扩展，不执行 G1

| 文件、位置、原代码 | 最终改法 | tags / 大字体 / 验收 |
|---|---|---|
| QC:99–107，`PageHeader(title, onBack, subtitle)` | 参数末尾增加默认空的可选 trailing composable。卡内先排返回＋标题，再按共用标题规则排 trailing；副标题属于标题块，不因 trailing 消失。普通字体可把返回、弹性标题块、trailing 放同一 Row；回退时返回与标题一行，trailing 下一行。保留原 8dp padding 与 4dp subtitle 间距。 | `back`、`page-title` 原字面量仍在 QC，标题只有一个节点。用于详情/历史刷新及非首页列表新增；无 trailing 的调用保持原结构。检查 font1/1.3/1.31/2 标题完整。 |
| QC:119–138，Score 内部 `if (meter && value != null)` 绘制 120dp 评分条 | 将 :132–136 抽成 `RatingMeter(value, modifier=Modifier)`，保留现有 120dp 宽、10 段、5dp 高、2dp 段距、计算及评分 contentDescription；null 不绘制。Score 的 meter=true 仍调用它且默认行为不变。只有详情头调用独立 meter，Score 本身 meter=false。 | 不产生重复 meter 或重复评分语义。未评分不绘制有效分值条；检查未评分/低分/4.6+。 |
| QC:141–196，RatingEditor 自带框与“评分 · 选填” | 参数末尾增 `framed: Boolean = true`、`label: String = "评分 · 选填"`。framed=false 仅去外 brutalBox 与14dp padding，保留 fillMaxWidth、8dp内部间距、Score、轨道、slider与语义。标签使用传入 label。 | `rating`、调用的 `filter-min/filter-max`、`$tag:minus/plus` 保留。null 仍不可由触摸空轨道设置（原无交互 Canvas），设置评分仍显式写40，accessibility SetProgress及0.1步进不变。 |
| QC:191–195，减/加一行，清除另行 | 已评分且 font≤1.3、内容宽≥280dp：减、加、清除三文字按钮同排，间隔8dp，可等分弹性宽度；280以下普通字体：减/加两键 Row 各 weight(1f)，清除下一通栏行；font>1.3 三键 Column 各通栏、间隔8dp。未评分仅设置评分。 | 无现存清除 tag，不强行新增；minus/plus 原 tags 保留。验收10/50边界、加减和清除→未评分→设置评分。 |
| BC:72–81，Sticker 硬编码 `maxLines=1` | 参数末尾增 `maxLines: Int = Int.MAX_VALUE` 并转发 GridText，默认允许内部换行。样式、padding、描边、tilt、dieCut不变；不执行 BC:69–70 的全局间距变更。 | A5日期/A8最近日期与verdict长文在font200可读。普通字体短贴纸外观应一致。 |
| HT:20–32，时间 `maxLines=1, Ellipsis` | 按fontScale：>1.3时 maxLines无限、Clip；其余保持1行/ellipsis。保留min48dp宽/高、点击角色、状态说明及两套modifier。 | `history-time`、`absolute-time`、列表插值时间tags保留；font200切换后完整时间可见，不删除正常字体原有单行行为。 |

QC 的 FormField、ChoiceChips、ExpandableValue、ConfirmAction、ScreenFrame、SaveBar其它结构保持。ExpandableValue（QC:240–246）的240字预览、6dp内部距、expanded状态和展开操作不改；E4/N7的配对通过调用处嵌套Column实现。

## 2. 详情与编辑：DE

| 位置、原片段 | 最终改法 | 保持策略与验收 |
|---|---|---|
| :34–40，PageHeader 后状态item中的 `detail-refresh`（A5b/N9） | 刷新放PageHeader trailing，保留“正在刷新…”与 !state.refreshing；状态item仅在 offline/stale/error/record==null 任一为真时建立，内部仍CacheStatus、ErrorMessage和原空态，不留正常在线空item。 | 保留 `detail-refresh`、`detail-empty` 与CacheStatus tags；有/无缓存、刷新中、失败、离线、stale都能查看状态和重试。 |
| :43–55，ReceiptEdge＋BitePanel小票头（A5） | 上票边位置不变；BitePanel加 `pr17:receipt-head`。第一段“BITE! 食记 · 探店小票 · 版本 N”。普通字体内容宽≥280dp：标题weight＋右侧Score(prominent=true,meter=false)；不足/大字体标题通栏后Score单列。仅餐厅/菜品显示Score。下方FlowRow依次：独立RatingMeter（有评分）、VerdictSticker（餐厅/菜品）、到店日期Sticker（沿用dining_date ?: last_visit_date）、已修订章（version>1）、更新于formatAbsoluteTime(updatedAt)。修订章只写“已修订”，版本只在眉题出现；日期条件/颜色/修订章padding7不变。 | 不删title/评分/未评分/日期/版本/修订/更新信息。不把title变成ellipsis。FlowRow允许超过2行。测试三实体、长title、未评分及version1/多版本。 |
| :58–66，extraFields 标签和值竖排（A2） | 仍用原 `editableFields(kind).filter{...非空...}`。餐厅短字段按category、cuisine顺序，两列Row各weight、间隔12dp；仅font≤1.3且内容宽≥280dp两列，否则单列。VISIT顺序dining_date、meal，同规则。address始终通栏并保留蓝色地址Sticker，标签＋值同行（值weight无限行），大字体标签在上值在下、间隔2dp。其它字段同样“标签：值”标签caption、值原body；单个短字段通栏，去空占位；DISH无此卡。 | `detail:$field` 在独立值GridText上，值仍displayValue。不得添加dish_count/visit_count/last_visit_date到extraFields；来源见core/Product.kt:65–69。非空筛选及值显示语义不改。 |
| :78–85，编辑/删除（A1） | 使用共用按钮对，edit扩展、delete包内容；删除danger和ConfirmAction(:127)不变。已删除分支仍说明＋通栏恢复，不套按钮对。 | `edit/delete/restore`保留；删除仅开启确认，取消无副作用；恢复确认同原逻辑。 |
| :91–95，菜品标题、计数、两个按钮、提示（A3/A9） | 标题保留“菜品测评清单”。将计数与空预览提示汇成一段caption，四态：count存在且预览非空→“店内菜品 · N 道”；count存在且预览空→再加“ · 菜品在本餐厅内管理”；count缺失且预览空→原提示；count缺失且预览非空→不显示caption。下方新增/管理共用按钮对。 | `add-dish`保留；管理原文案“管理店内菜品”与onDishes不变。不得用dishes.items.size当总数。预览RecordRows(:98)仍是独立Lazy items。 |
| :101–105，到店记录（A4/A8） | 标题改为有visit_count时“到店记录 · N 次”、无时原标题；右侧最近到店Sticker来自原非空last_visit_date。按共用标题规则回退。下方再次到店/查看全部共用按钮对。 | `add-visit/visit-history`保留。次数不另占一行，不重复生成日期。visits预览(:108)保持。 |
| :110–121，修改历史＋条码（A6/A7） | 分两个Lazy items，中间用现有12dp item间距。第一卡BitePanel加 `pr17:detail-history-panel`，标题＋history按钮按共用标题规则，rail里只保留当前版本和更新时间（原8dp内部距/start16）。第二item为单独brutalBox收尾Column，加 `pr17:receipt-footer`，水平padding14、竖向6、内部距4，ReceiptBarcode仍20dp、原标语caption，其后紧接ReceiptEdge(false)，不能在票边前加额外item间距。 | `history`字面量仍在DE点击节点。两卡及间隙合计高度在普通字体相同数据下≤旧布局，font200自然增高允许。不改默认BitePanel达成拆卡。 |
| :140，编辑 `spacedBy(16.dp)`（D1） | 改12dp；其它viewport/insets/contentPadding不变。 | `edit-scroll`、SaveBar和全部field tags保留；IME下保存可达。 |
| :149–151，评分外BitePanel（N3） | 保留BitePanel，只给RatingEditor传framed=false。 | 去内框而非删外卡；原enabled及onField保持。 |
| :205–206，编辑页店内菜品（D2） | 两按钮按共用规则，新增扩展、管理按内容。input.baseSnapshot==null 的保存后管理提示(:203)保持，不暴露未保存餐厅的子记录操作。 | `edit-add-dish/edit-manage-dishes`字面量、!state.saving及leaveEditor调用链（PR:167–171）保持。 |

DE:68–75评价/备注分别成卡、:144提交提示、:170–197首访checkbox/展开/错误逻辑保持；不改FormField minLines（地址2、评价/备注4）、首访日期与备注校验。

## 3. 列表与筛选：LS

| 位置、原片段 | 最终改法 | tags / 大字体 / 验收 |
|---|---|---|
| :45，`spacedBy(6.dp)`（B2） | 所有RecordListScreen变7dp；LoadedStats(:149–156)仍独立自适应框，3dp竖向padding、28dp分隔线不改，不新增34dp固定高度。 | `loaded-stats/search/record-list`保留。首页统计与搜索、筛选视觉间距增1dp；评估所有嵌套列表首屏卡数。 |
| :164–179，RecordRow（B1） | padding8→7；外spacedBy4→3；普通字体左右gap10→8；评分Column gap4→3。评分仍上数字下verdict；large分支及RowText结构不改。 | `record:$id/title:$id/record-summary:$id/matches:$id`原插值保留。保留min48dp、旋转、rail、正常字体摘要限制和large无限行；小屏首屏不得因微调减少完整卡数。 |
| :60–65，PageHeader与外部新增（N6） | 非首页分支移除外层Row/Box(weight)套层，改PageHeader trailing承载原新增按钮。 | `add`字面量仍在LS；back/page-title在QC。新增文案仍按kind取原文本；标题/副标题无省略。 |
| :48–57，首页品牌行（N6） | 图标38dp、BITE品牌/大字体“食记”、副标题保持。普通字体且行内容宽≥280dp同排新增；否则品牌组在上、原新增按钮下方通栏，间隔8dp。 | `page-title/add`保留，不把品牌页头改成普通PageHeader，不增位图。 |
| :234–235，两个Checkbox（G3） | font≤1.3且内容宽≥320dp时两组等分、gap12；否则Column gap8。每组Checkbox＋weight文本居中，文本无限行，删label的固定top12。 | `filter-unrated/filter-rating-range`继续在Checkbox；原回调：未评分清min/max；区间取消unrated及初始化10/50保持。 |
| :236–238，最低/最高评分（G2/N3） | 删除外部两个GridText标签，分别调用RatingEditor(label="最低评分"/"最高评分",framed=false,原tag)。font≤1.3且外筛选框内容宽≥492dp时两列weight、gap12，每列至少240dp；否则完整宽度的两个编辑器竖排、gap12。 | `filter-min/filter-max`和各minus/plus不改，全部数值回调及10/50fallback原样。不得单纯用Row等分手机宽度。 |

LS:222筛选外框padding14/spacedBy12不改。筛选快捷chips的横向滚动、所有筛选语义/阈值、日期起止独立字段(:242–243)、更多菜单/refresh/trash/load-more保持。日期含格式提示，暂不半宽排输入框。

## 4. 历史详情与预览：HS

| 位置、原片段 | 最终改法 | tags / 大字体 / 验收 |
|---|---|---|
| :30，`spacedBy(18.dp)`（N5） | 历史详情LazyColumn改12dp。 | `history-scroll/screen:history`保持；不改ReviewDialog内容间隔或尺寸。 |
| :31–36，页头/独立刷新（E1/N9） | 刷新进入PageHeader trailing，加 `pr17:history-refresh`，禁用和正在刷新文案保持。状态item只在offline/stale/error时建立；record==null的空态仍由原:67单独item承担。 | 保留entity_label副标题及缓存/error tags，不制造空状态item。 |
| :39–41，时间＋版本（E6） | 普通字体且内容宽≥280dp时时间weight左、版本包内容右，gap12，居中；否则时间、版本Column gap4。结合HT大字体多行规则。 | `history-time/absolute-time`保留，版本before默认0、after默认1以及日期created_at ?: updatedAt不变；点击可切换两种时间。 |
| :44–50，diff卡（E4B/E5） | 字段标题保持titleMedium；原值配对Column：caption secondary标签、2dp后原ExpandableValue；新值配对同样，label accentInk；两对及标题间沿用外卡8dp。删除:50卡内HorizontalDivider。 | `before:$field/after:$field/expand:$tag`原值及展开tags保留，保留rail/start16。短评分/长地址/空值/240字前后/多字段diff都验收。 |
| :56–64，说明/隐藏/重新应用（E2/E3） | 未隐藏时说明与隐藏按共用按钮对；二者仍BiteTextButton，隐藏danger并保留确认(:70)。restore分支仍说明＋通栏按钮。reapply另行通栏，位置仍在这些操作下方。 | `history-note/hide-history/restore-history/reapply`保留，错误/确认流程不简化。 |
| :98–100，ComparisonColumns单元格（N7） | 内Column标签到ExpandableValue距6→2dp，标签已有titleSmall保持；跨字段12dp、两栏170dp/font200时220dp、水平滚动及左右内容不改。 | `server_current/local_input/compare:$field:$index`保留。比较值不省略、不调换栏或内容。 |
| :137–140，ApplyFieldsDialog当前/拟值（N7） | 用一个分组Column替代四个直接滚动子项，内部两对caption标签＋2dp到ExpandableValue，对间8dp。 | `apply-current:$field/apply-proposed:$field/expand:$tag`保留。Checkbox选择/必填不能空/两侧选择/保存禁用及跨字段divider(:142)保持。 |

保持ReviewDialog(:74–86)的94%固定工作区、双层padding、weight滚动主体、独立返回和保存footer；保持OperationDialog(:147–158)和确认流程。它们不是E5要删除的卡内装饰线。

## 5. 设置页 UI 与更新弹窗

SS设置页删除项和数据链路见第6节，以下同时执行：

| 文件、位置、原片段 | 最终改法 | tags / 大字体 / 验收 |
|---|---|---|
| SS:81–96，开关Row＋检查按钮Row（C1） | 组成响应式设置行：左组完整“启动时检查更新”标签＋Switch，右BiteTextButton检查更新；font≤1.3且内容宽≥280dp同排，左组weight、gap12，左组内部gap8；否则左组一行、检查按钮下一行通栏。标签自身weight允许多行。 | `update-checks/check-update`保留。开关语义contentDescription原样，checking禁用保留；失败/最新消息继续另行及原tag，关闭启动检查后仍可手动检查。 |
| SS:98–107，logout（C2） | 只加fillMaxWidth，保留danger居中文字和ConfirmAction“将清理此账号的本机缓存。服务器已保存记录仍属于账号。”；不增加副文案。 | `logout/confirm`原字面量，退出确认后清缓存等原行为保留。 |
| UD:34–35，下载阶段＋大小（N2） | 两段GridText收入FlowRow，gap12/换行4；busy条件与VERIFYING分支、percent/total缺失文案不改。进度条、取消仍下一独立项。 | `update-progress`在阶段文本节点，大小语义保留；不要让阶段、字节数和取消共用一个点击区域。 |
| UD:52–53，下载页＋关闭（N1） | 使用按钮对规则，但横排阈值为内容宽≥240dp且font≤1.3：打开下载页扩展、稍后/后台继续包内容；其它情况回退两个通栏。此Row用Alignment.Bottom，允许文字换行时两按钮不同高，仍保持关闭按钮底端到panel底端16dp。 | `update-download-page/update-close`原字面量仍在UD；busy关闭只后台继续，取消仍update-cancel；不动其它阶段按钮和回调。 |

UD:24–30 的Dialog默认宽度、LocalDensity继承、wrapContentHeight、85%高度上限、全内容verticalScroll、16dp内边距、12dp直接item间距保持。release notes全文不折叠、不改标题版本；`screen:update/update-scroll/update-now/update-retry/update-install/update-cancel/update-message`保持。

SS:40整体16dp item间距、主题独立item(:74–77)、关于版本标语(:79–80)、PasswordDialog(:110–139)、修改密码通栏按钮保持。没有“主题BitePanel”要删。密码footer确认与关闭分行、安全标志、内存输入、未知结果重试策略不动。

## 6. 已拍板：删除设置页账号刷新与会话管理全链路

按下列完整清单执行，不保留不可用入口或会话查询：

| 文件、行号、原代码 | 改法 |
|---|---|
| SS:29–35，参数onRefresh/onRevoke和 `var revoke` | 从SettingsScreen签名删除onRefresh、onRevoke，删revoke状态；保留onPassword/onLogout等全部其余参数。 |
| SS:49，账号与会话刷新；:58–70会话标题/列表/空态；:103–106撤销确认 | 全删，包括列表中当前设备退出入口和其它设备撤销入口。账号header、不同displayName、注册时间、settings-message(:42–50中其余内容)保留；唯一logout及:107确认保留。 |
| VM:19–21，SettingsUiState.sessions | 删sessions字段，其它loading/busy/message/password字段保持。 |
| VM:294–301，loadSettings中并行profile/sessions | 只调用security.profile()并赋profile/结束loading；开始loading/清message和异常/cancellation处理保留。移除不再需要的内层coroutineScope/async，不删其它业务使用的协程能力。loadSettings仍在进入设置页及改密码成功后调用。 |
| VM:302–307，revokeSession | 整个函数删除，密码busy/unknown处理及account清理保持。 |
| PR:133–138，SettingsScreen透传 | 去vm::loadSettings、vm::revokeSession两个参数，推荐剩余全部改为命名参数避免位置错位；LaunchedEffect vm.loadSettings仍保留。 |
| `android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Repositories.kt`:24–25 | 删除AccountSecurity.sessions/revokeSession声明；profile/changePassword保留。DeviceSession模型(:20–21)本期可保留作为未使用类型，不扩展清理范围。 |
| `android/data/src/main/kotlin/top/alanvampuke/dininglog/data/auth/RetrofitAccountSecurity.kt`:25–26、38–50 | 删除SecurityApi.sessions/revoke方法和两个override实现；删除仅为sessions引入的jsonArray import。profile/read/密码写入重放全部保留。 |

后端 `backend/src/auth/routes.ts`:158–170 GET /auth/sessions、DELETE /auth/sessions/:session_id 原样保留，contracts不变。Android登录/refresh/恢复/改密/logout能力保持；删除会话管理不删除后端撤销能力。

### 必须同步迁移的调用与测试

- `android/feature/src/test/.../feature/ProductViewModelTest.kt`:44–45、`android/app/src/androidTest/.../product/P6UiTest.kt`:298–299、`Pr16UiTest.kt`:255–256：删除AccountSecurity fake的sessions/revokeSession override，保留profile与changePassword。
- `Pr16UiTest.kt`:204–223：整体删除 `currentSessionConfirmationSaysLogoutAndOtherSessionsStillSayRevoke`。其余PR16用例保留；退出确认的覆盖由现有e2e及新增PR17 UI验收补足。
- `P6UiTest.kt`:145：改等 `profile != null && !loading`，不只等默认false的loading，避免未加载时提前通过；:148 在settings分支改 `settings-scroll.performScrollToNode(hasTestTag("logout"))`，其它列表仍原索引10。:149–160 的重选4次、entry不变、profile/refresh请求计数不变断言保留。不能删除滚动或重选验证。
- `ProductEndToEndTest.kt`:343–345：设置页等profile非空且loading结束；由测试代码 `runBlocking { withContext(Dispatchers.IO) { ... } }` 用OkHttp直接DELETE `baseUrl + "auth/sessions/" + other.sessionId`，通过 `refresh.authenticated` 获取**当前拥有者**Bearer token，带单个UUID Idempotency-Key，关闭Response并断言204。原测试:80–100已有OkHttp client，可复用；直接API请求不经过被删除的AndroidAccountSecurity接口。
- 同一e2e段断言被撤销other的access请求/auth/session为401且gateway.refresh(other.refreshToken, UUID请求id)失败为SESSION_REVOKED；断言当前账号仍可profile并继续原改密、logout、Room清缓存与再次登录(:346–366)。不要仅保留DELETE成功而丢失失效语义。参考原:476–482的IO直连辅助方式；AuthGateway.refresh(token:String,requestId:String)签名依据为`android/core/src/main/kotlin/top/alanvampuke/dininglog/core/auth/Authentication.kt`:20。
- 更新所有SettingsScreen调用，删两个废弃参数并尽量使用命名参数：`ProductVisualTest.kt`:73/172，`P8VisualTest.kt`:81–82，`P6UpdateUiTest.kt`:45–47/82–84/106–108，`ScreenshotSecurityTest.kt`:34–36及PR中调用；删除后的Pr16用例不迁移。现有图片/保护/IME断言保持，不能因为参数变更漏掉onPassword或onLogout。
- 最后用 `rg -n 'SettingsScreen\(|override suspend fun sessions|override suspend fun revokeSession|\.settings\.value\.sessions|vm::revokeSession|security\.sessions\(' android -g '*.kt'` 复查全部生产、debug、测试调用，依编译结果清掉仅因删除而无用的import。

## 7. 已拍板：后端会话滑动180天

| 文件与实际依据 | 完整改动 |
|---|---|
| `backend/src/auth/session.ts`:52–60，`now + 2592000` | 在此文件导出 `SESSION_TTL_SECONDS = 15552000`，issueSession使用now＋此常量；refresh创建上限、session INSERT和session_expires_at响应都使用同一expiresAt。不新增后端文件。 |
| `backend/src/auth/rotation.ts`:5、25–36，next和response仍用session.expires_at；UPDATE只写last_refreshed_at | import上述常量；一次获取now，同时计算retryExpires=now+120和sessionExpires=now+SESSION_TTL_SECONDS，变量分清两种期限。newRefresh(...,sessionExpires,old.id)；响应session_expires_at=iso(sessionExpires)；seal的response使用此响应，AAD仍retryExpires。原DB.batch中UPDATE sessions改为同时SET last_refreshed_at=now, expires_at=sessionExpires，WHERE保留id/user/未撤销/原expires_at>now/auth_version/有效user，bind顺序对应新列；原两处guard、token CAS、新token INSERT和加密重放结果INSERT继续同一个batch。 |

成功刷新才续期：session.ts:25–28、rotation.ts:20–23 的过期判断保留，过期/被撤销/旧token重用不续期。rotation.ts:9–16 的同request-id重放只解密原成功结果，不能再写session时间；不同request-id重用仍撤销。retry缓存120秒、access600秒、session.ts:46的refresh token 604800秒空闲上限都保持。新token的期限用新sessionExpires，不能沿用旧expires_at截短。响应、DB、加密重放中的新时间需一致。

现存未过期session在下一次合法刷新后自然滑动；无数据库全量回填，不复活已过期会话，不改迁移/清理/API schema。180天是session从最近一次成功刷新起的上限；7天refresh token空闲到期仍执行。不要在UI或测试说明中宣称180天不打开App仍可自动续期。

### 新增恰好两个auth测试

放 `backend/tests/auth/auth.test.ts`，使用现有register/refresh/lookupRefresh和真实Workers D1。既有 `JWT owner/version and refresh idle/absolute expiration are enforced`（:253–262）**整段不改**。

两个新增测试仅伪造Date时钟：`vi.useFakeTimers({ toFake: ['Date'] })`＋`vi.setSystemTime(...)`，让Date.now和new Date同时前进，保留网络/调度计时器的真实执行；各测试try/finally中`vi.useRealTimers()`。不能只spy Date.now再用未来JWT调profile：`backend/src/auth/jwt.ts`:25/29用Date.now签发，:40–43的jose.jwtVerify还核对真实日期与maxTokenAge，两种时间源必须一致。不改生产JWT验证选项，也不改既有测试。

1. **滑动续命与重放一致性**：固定时钟至t0，注册，断言DB及session_expires_at恰为t0＋15552000；每6天用当前refresh换新token，循环跨过创建后180天（每步避免7天token空闲到期），每次断言session.expires_at、last_refreshed_at、响应一致，session id不变，refresh_expires_at=t＋604800且不超过新session期限。至少最后一次验证同request-id在稍后但120秒内重放返回字节相同、DB期限不再次移动；跨180天仍能用新access访问profile。初始及每次计算期望用独立字面量15552000验证，不仅从生产常量导入自证。
2. **绝对闲置上限/期限边界不能复活**：至少两份独立session fixture，分别验证期限前1秒可续期、恰好180天期限及超过1秒不可续期。为隔离session的180天守卫，**仅测试数据库**把未消耗refresh的expires_at延长至180天之后（否则7天token先失败会遮蔽待测逻辑）；保留其他约束。使用上述可变Date时钟和当前有效测试access（原jwt.signAccessToken）避免旧600秒JWT先失败；在session过期时profile及refresh均401、SESSION_REVOKED，session expires_at/last_refreshed_at未变，无新增子token/重放结果、旧token consumed_at仍null。expires_at需要>created_at，符合`backend/migrations/0001_initial.sql`:33–35/58–59。不改生产token空闲政策。

既有同id并发只生成一个子token(:118–125)、异id重用撤销(:126–136)、refresh与logout竞争回滚(:263–274)、owner scope/密码修改等原测试保持并通过；不为了新增测试放松它们。

## 8. 守卫基线与哈希：必须同步

1. `android/tools/p7_baseline.json`:16 的SettingsScreen.testTags中，只删除序列项 `"\"revoke:${session.id}\""`。这是已删除会话UI的唯一退役tag；其它数组、baseline字段、protectedHashes不重写。不得注释保留旧tag来骗正则。依据：`android/tools/verify_bite_visuals.py`:98–102从源码提取并比较，:93–95没有会话tag例外。
2. 两个守卫脚本 `verify_bite_visuals.py` 与 `verify_p6_visuals.py` **不改**。所有保留tag继续用它们能识别的原字面量/原tag变量形式。
3. 同实现commit刷新 `android/tools/p8_protected_hashes.json` 内 `backend/src/auth/session.ts`、`backend/src/auth/rotation.ts`、`backend/tests/auth/auth.test.ts` 的SHA-256（现名单:22/25/57）。本计划未改config.ts及其它backend/contracts文件，不更新它们。若实现确有必要增加后端文件，必须把实际新增tracked路径纳入此哈希名单；不要移除路径绕过保护。
4. 守卫:113–117核对所有tracked backend/contracts路径和哈希，新增文件需先进入git索引再验收路径集合。不修改任何迁移、依赖lock或contract来应付视觉守卫。测试哈希与新增180天用例同commit更新。

## 9. 验收与证据（实现阶段执行）

### 自动检查

- 仓库根：`python3 -B android/tools/verify_bite_visuals.py`、`python3 -B android/tools/verify_p6_visuals.py`，二者exit0；脚本diff为空。比较基线到实现的源码tag集合：除revoke插值外无删除/重命名，新增均pr17前缀；基线只退役一个tag。
- backend目录：`npm run check`、`npm test`。两个新增session测试、原:253–262、rotation竞争/重放/撤销和保留的sessions API覆盖全部通过；不能改原期限用例内容降低要求。
- android目录：`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --no-daemon`。先覆盖签名/接口删除的编译及原业务测试，不改依赖锁。
- 模拟器API26、35，360×640dp：`./gradlew :app:connectedDebugAndroidTest --no-daemon`，保留现有ProductEndToEndTest、P6SmallScreenEndToEndTest、P6UiTest、Pr16UiTest剩余用例、ProductVisualTest、P8VisualTest、P6UpdateUiTest和ScreenshotSecurityTest。现CI入口 `.github/scripts/android-test.sh` 会运行该suite并采集截图（workflow android-ui.yml:47/77）。本地启动隔离测试API，沿用现e2e配置，不打生产服务。

为这次结构改动新增 `android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr17UiTest.kt`，集中验证真实行为和可达性，不给7dp/3dp这样的微调单独编写镜像测试。覆盖下列交互/视觉矩阵，可复用现fixture和截图工具：

- fontScale 1、1.3、1.31、2；浅/深主题；360×640主设备，补320dp窄容器、横屏及宽容器。G2在父内容宽491/492dp、G3在319/320dp附近验证排布和操作可达（它们是内容宽而非屏幕宽）。页面按钮对279/280dp、更新弹窗按钮对239/240dp的回退，至少用代表性的最长按钮组验证。
- 详情餐厅/菜品/到店、deleted/restored、记录缺失、offline/stale/error/refreshing；长名称、长地址、缺失菜系/餐次/计数、未评分、version1/多版本、有/无子记录、回调null。检查值tags仍指向原displayValue；标题、score、meter、verdict、日期和修订信息不丢失/不重复。
- 编辑删除确认、菜品新增/管理、再次到店/查看全部、历史入口点击仍触发对应回调；窄/大字体可以滚到每个按钮，触达≥48dp。首访展开/错误、编辑离开确认、IME保存和存储规则由原测试继续覆盖。
- 历史diff多字段：before/after为null/短值/长地址、长度240及241以上；展开后可读全文、可收起；说明/隐藏/取消隐藏/重新应用均保留原确认与回调。时间切换与完整绝对时间在font200可读；apply-current/proposed配对不串值，比较两栏可横向滚动。
- 评分编辑器：输入null不冒充4.0；设置→加减0.1→清除→未评分；10/50边界；最低/最高区间各自操作及互斥checkbox回调不交叉。无双层框的编辑页和筛选页分别截图。
- 设置：默认profile加载成功再显示状态；无会话标题、撤销操作、刷新账号入口；主题/开关/手动检查/有新版本/最新/失败/改密码/logout仍可达。取消退出无动作，确认退出调用原回调；保留原清缓存e2e证据。
- 更新弹窗覆盖IDLE、DOWNLOADING（percent已知/未知、total已知/未知）、VERIFYING、READY、FAILED（verified true/false）、CANCELED、NEED_PERMISSION、INSTALLING，长版本名与长notes、message有无；所有阶段应有的原tags存在且对应回调正确，取消与后台继续不同。普通字体短说明无需滚动、底部两次操作同排时关闭按钮底端距panel底端16dp；font200长说明内部可滚动到update-close，仍16dp。保留P8VisualTest:137–178原断言，不改变它来掩盖高度错误。

### 目测与实际布局证据

保存实施前/后**同数据、同密度、同fontScale、同主题**截图及容器高度/文本布局记录，输出到 `design/pr17-discussion/evidence/`（实现阶段新建）：

| 场景 | 验收标准 |
|---|---|
| 详情普通字体首屏及底部 | 小票头与动作区堆叠减少；评分仍显眼、标题全文；编辑/删除、菜品、到店按钮组达到宽度条件时同排，A6历史按钮在标题组，A8日期在标题组；A2实际字段顺序正确。 |
| A7时间轴＋收尾 | 用新tags测两卡及12dp间隙合计，与旧DE:110–121卡内容区相同fixture比较；普通字体总高不得增加；上下票边连续，条码和标语不被裁切。大字体以全文和可达为准。 |
| 列表首页/搜索/店内/到店/历史列表 | 微调后普通字体首屏完整可读卡数不得下降，描边/投影不碰邻卡或文本；统计条自适应，大字体值不裁切，正常摘要省略规则保持。 |
| font200与窄容器 | 对新增横排相关的标题、label、按钮文字、地址、贴纸、版本范围、时间，采集TextLayoutResult并断言无visual overflow；折叠的长值240字预览属于原展开规则，展开后全文可达。不要将普通字体现有列表ellipsis当成这次新增截断。 |
| 深浅主题 | 使用原token，描边/硬投影/贴纸在两主题协调；对比度守卫通过并目测层次，没有靠缩字或去保护余量挤密度。 |
| 长页面/IME/横屏 | 列表最后一项、history/reapply/logout及save不被浮动栏、系统导航栏或键盘遮住；原API26遗留IME处理及API35insets验证保持。 |

数据字段不存在时不制造空卡，状态item无内容时不留额外item间隙。所有新高度/收益必须以测量为证，不能仅凭代码推算宣布通过。若验收发现截断，先按本计划放宽可换行高度/切回指定单列，不私自删文案、tags或改成G1全局收紧。

## 10. 交付范围与实施顺序

顺序：组件能力与DE/LS/HS/SS/UD局部排布 → 删除会话UI/接口及全部调用迁移 → 后端滑动与两个测试 → tag基线/哈希 → 自动与视觉验收。AS无改动；除列出的组件、业务删除、测试/基线迁移外不扩大生产范围。

下一阶段使用新分支 `feat/pr17-density-and-sliding-sessions`，将上述实现、测试迁移、基线/哈希更新及证据归档同一提交，推origin并开PR，标题 `density pass + sliding 180-day sessions`。实施总结写 `design/pr17-discussion/NOTES.md`：改动文件、每条验收证据、对助手初稿的裁决及理由、真实测试结果/任何剩余限制。DISCUSSION/PLAN本阶段不随实现重新改成另一个方案；若有无法满足的实测结果，NOTES明确记录与本计划的差异和原因，不能静默放宽验收。
