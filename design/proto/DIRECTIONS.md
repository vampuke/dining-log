# 食记三套视觉方向说明

本文以 `d1-ledger.html`、`d2-quiet-grid.html`、`d3-signboard.html` 当前 CSS 为准。颜色表完整收录两段 `:root`（主题色与方向级尺寸/字体变量）；字号、间距和圆角按最终层叠后的应用画板规则整理，画廊外壳另行注明。

对比度由 Python 3 按 WCAG 2.x 相对亮度公式计算：先将 sRGB 通道线性化，再取 `(Lmax + 0.05) / (Lmin + 0.05)`。表中统一采用普通文本的严格门槛：AA ≥ 4.5:1，AAA ≥ 7:1；显示值保留两位小数，判定使用未四舍五入值。按题目指定计算 `on-accent/accent`；D1、D2 的主按钮实际背景另用更深的 `--cta`。

## 方向一：账簿 / Ledger

**气质：**像一本持续增补的私人菜单册，暖纸、中文衬线与发丝线让记录显得安静而有人情味。

**借用的真实系统：Anthropic / Claude 的品牌与产品排版。**具体借用暖 parchment/ivory 中性色、terracotta 单强调色、衬线标题配无衬线正文、依靠留白与细分隔线而非阴影分层；再把它转译成中文账簿结构：20px 页边、65px 三行目录条目、固定 44px 评分列、正文长评使用宽松行高。没有照搬 Claude 的网页模块或卡片形态。

### 完整 token

| Token | Light | Dark |
|---|---|---|
| `--bg` | `#f6f3ec` | `#141312` |
| `--surface` | `#fcfaf5` | `#1d1b19` |
| `--ink` | `#1a1815` | `#f4efe5` |
| `--secondary` | `#575047` | `#c4bbaf` |
| `--tertiary` | `#6d6359` | `#aaa093` |
| `--border` | `#d8d0c4` | `rgba(255, 255, 255, 0.18)` |
| `--accent` | `#b4523a` | `#e6a28b` |
| `--on-accent` | `#ffffff` | `#141312` |
| `--danger` | `#823321` | `#e6a28b` |
| `--accent-ink` | `#823321` | `#e6a28b` |
| `--cta` | `#823321` | `#e6a28b` |
| `--accent-soft` | `#f0e2d9` | `#342720` |
| `--border-strong` | `#8b8174` | `#84786a` |
| `--gallery` | `#eae5dc` | `#0e0d0c` |
| `--sans` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` |
| `--display` | `"Noto Serif SC", "Songti SC", system-ui, "PingFang SC", "Microsoft YaHei", serif` | `"Noto Serif SC", "Songti SC", system-ui, "PingFang SC", "Microsoft YaHei", serif` |
| `--mono` | `var(--sans)` | `var(--sans)` |
| `--edge` | `20px` | `20px` |
| `--radius` | `4px` | `4px` |
| `--button-radius` | `8px` | `8px` |

### 字号阶梯

未单列行高的文本继承 `body` 的 `1.5`；表内列出实际使用的主要行高变体。画板正文基准为 14px/1.5。

| 字号 | 行高 | 字重 | 主要用途 |
|---:|---|---|---|
| 9px | 13.5px | 500 | 列表、菜品与 diff 评分后的“分” |
| 10px | 15px | 500 | 修改预览评分后的“分” |
| 11px | 14 / 15 / 16 / 16.5 / 20px，脚注可到 19.8px | 400–600 | 状态栏、眉题、日期、caption、底部导航、脚注 |
| 12px | 16 / 18 / 19 / 21.6px | 400–600 | 餐厅元信息、筛选、类型、说明文字 |
| 13px | 19.5 / 20 / 20.8 / 22.1 / 22.75px | 400–600 | 搜索、工具栏、地址、备注、历史摘要、表单标签 |
| 14px | 21 / 22 / 25.2px | 400 / 600 | 正文基准、按钮、输入框、diff 正文、到店记录 |
| 15px | 29.25px（1.95） | 400 | 长评正文 |
| 16px | 21 / 24px | 400 / 500 | 餐厅名、评分选项 |
| 17px | 24px | 500 | 菜名 |
| 18px | 27px | 500 / 600 | 预览与 diff 小标题、到店日期标题 |
| 19px | 28.5px | 500 / 600 | 列表评分、章节标题 |
| 23px | 27.6px（1.2） | 400 | 菜品评分 |
| 27px | 37.8px（1.4） | 400 | diff 数值 |
| 28px | 42px | 400 | 修改前后预览数值 |
| 30px | 40.5px（1.35） | 600 | 通用/表单/到店面板标题 |
| 31px | 41.85px（1.35） | 600 | 菜品库、修改记录标题 |
| 32px | 44.8px（1.4） | 600 | 餐厅详情标题 |
| 35px | 47.25px（1.35） | 600 | 首页标题 |
| 37px | 40.7px（1.1） | 600 | 详情评分 |

画廊外壳使用 11/12/13px 辅助字与 34px 标题，窄屏标题降为 28px；这些不进入 Android 应用字号 token。

### 间距、圆角、阴影与动效

- 画板为 393×852px；应用水平边距 `20px`，状态栏高 28px，内容顶部内边距 12px，工具栏高 40px，底部导航高 68px。
- 首页标题区高 69px；搜索框高 40px、距标题 6px；筛选区距搜索 12px；列表统计距筛选 12px。
- 搜索框使用 `--border-strong`；评分 segmented control 为 `未评分 / 1 / 2 / 3 / 4 / 5` 六等分，单段高 44px、1px 边框、段间视觉间隙 0px，首尾沿用 4px `--radius`，选中段使用 `--accent` / `--on-accent`。
- 餐厅行高 65px、上下内边距 4px，文字列与 44px 评分列间隔 10px；菜品行最小 65px；历史行上下 17px。核心节奏以 4/6/8/10/12/16/20px 为主。
- 常规控件与面板圆角 4px，主/次按钮 8px；最新修改预览特意取消圆角与左右边框，保持账页感。系统手势条圆角 4px。
- 全局不使用 `box-shadow` 或 `text-shadow`，层级只靠纸色、边线和排版。
- 在 `prefers-reduced-motion: no-preference` 下，按钮/链接的背景、文字、边框颜色使用 `140ms ease-out`；Toast 同为 140ms，从 `translateY(4px)` 淡入。页面定位平时平滑滚动，系统要求减少动态时改为即时跳转。

### Python 实测对比度

| 主题 | 色对 | 对比度 | AA | AAA |
|---|---|---:|:---:|:---:|
| Light | `ink/bg` | 15.99:1 | 是 | 是 |
| Light | `secondary/bg` | 7.17:1 | 是 | 是 |
| Light | `tertiary/bg` | 5.29:1 | 是 | 否 |
| Light | `on-accent/accent` | 4.99:1 | 是 | 否 |
| Dark | `ink/bg` | 16.19:1 | 是 | 是 |
| Dark | `secondary/bg` | 9.79:1 | 是 | 是 |
| Dark | `tertiary/bg` | 7.21:1 | 是 | 是 |
| Dark | `on-accent/accent` | 8.75:1 | 是 | 是 |

## 方向二：Quiet Grid

**气质：**像一件精密但不冷漠的记录工具，信息严格落在行列中，数字快速可比，装饰退到最低。

**借用的真实系统：Linear + Notion。**从 Linear 借用 whisper 级 1px 边框、紧凑控件、4px 小圆角、单一状态色和明确的 tab 顶部指示；从 Notion 借用高密度文档式层级、无衬线正文、用排版而非装饰组织内容。日期、计数、评分单独使用 JetBrains Mono，并让评分右列与 3px 进度条保持对齐。

### 完整 token

| Token | Light | Dark |
|---|---|---|
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

### 字号阶梯

未单列行高时继承 1.5；正文基准仍为 14px/1.5，但产品主体主要收敛在 15/13/11px 三档。

| 字号 | 行高 | 字重 | 主要用途 |
|---:|---|---|---|
| 9px | 13.5px | 500 | 列表、菜品与 diff 评分后的“分” |
| 10px | 15px | 500 | 修改预览评分后的“分” |
| 11px | 14 / 15 / 16 / 16.5 / 18 / 20px | 400–600 | 状态、caption、日期、tab、计数、脚注 |
| 12px | 16 / 18 / 19 / 21.6px | 400–600 | 元信息、筛选、类型、说明 |
| 13px | 19.5 / 20 / 20.8 / 22.75px | 400–600 | 搜索、链接卡、菜评、表单输入与选项 |
| 14px | 21 / 22 / 25.2px | 400 / 600 | 正文基准、按钮、详情类型、diff/到店正文 |
| 15px | 21 / 22.5 / 23 / 28.5px | 400 / 600 / 700 | 餐厅名、章节标题、菜名、历史标题、长评、评分按钮 |
| 16px | 19.2px（1.2） | 400 | 菜品评分徽章 |
| 18px | 27px | 500 | 列表评分、到店日期标题 |
| 25px | 33.75px（1.35） | 700 | 通用、详情、菜品、历史、表单标题 |
| 26px | 35.1px（1.35） | 700 | 首页标题 |
| 27px | 37.8px（1.4） | 400 | diff 数值 |
| 28px | 42px | 400 | 修改前后预览数值 |
| 29px | 31.9px（1.1） | 500 | 详情评分 |

画廊外壳使用 11/12/13px 辅助字与 32px 标题，窄屏标题为 28px。

### 间距、圆角、阴影与动效

- 画板 393×852px；水平边距 `24px`，其余公共骨架仍为 28px 状态栏、12px 内容顶距、40px 工具栏和 68px 底部导航。
- 首页标题区仅 42px；搜索框高 36px、上距 8px；筛选项间隔 5px、上距 8px；列表统计上距 7px。餐厅行高 72px、上下内边距 7px，评分列固定 44px。
- 搜索框使用 `--border-strong`；评分 segmented control 为六等分、单段 44px 高、1px 边框和 0px 视觉间隙，首尾 4px 圆角，选中段使用 `--accent` / `--on-accent`。
- 详情信息盒内边距 13×14px；双列链接卡间隔 10px、高 64px；菜品行最小 66px、上下 9px；历史行上下 17px；大段之间主要用 18/19/22/24px。
- 常规面板和输入为 4px，按钮 6px，评分徽章与数值高亮为 3px；避免胶囊形。
- 无任何阴影；白面与底色仅靠 8% 黑或 18% 白的边线区分。
- 动效与 D1 相同：颜色状态 `140ms ease-out`，Toast 140ms/4px 淡入；平滑页面定位尊重 `prefers-reduced-motion`，减少动态时即时跳转。

### Python 实测对比度

| 主题 | 色对 | 对比度 | AA | AAA |
|---|---|---:|:---:|:---:|
| Light | `ink/bg` | 17.05:1 | 是 | 是 |
| Light | `secondary/bg` | 7.16:1 | 是 | 是 |
| Light | `tertiary/bg` | 5.23:1 | 是 | 否 |
| Light | `on-accent/accent` | 6.29:1 | 是 | 否 |
| Dark | `ink/bg` | 17.07:1 | 是 | 是 |
| Dark | `secondary/bg` | 9.76:1 | 是 | 是 |
| Dark | `tertiary/bg` | 7.50:1 | 是 | 是 |
| Dark | `on-accent/accent` | 8.73:1 | 是 | 是 |

## 方向三：招牌 / Signboard

**气质：**像街边面馆或居酒屋门口一眼能读懂的菜单招牌，以大字、粗线和芥末黄直接建立记忆点。

**借用的真实系统：现代日本居酒屋/拉面店菜单与商业导视。**借用米纸、纯黑、蛋黄黄的三色限制，900 字重的大标题、价格牌式等宽数字、1px/2px 规则线和完全直角的版面语法；所有色块都承载选中、评分或 diff 信息，不加入无意义纹样。底部 tab 的粗图标、黑字和 2px 下划线也按实体导视的“当前所在”逻辑处理。

### 完整 token

| Token | Light | Dark |
|---|---|---|
| `--bg` | `#f2efe6` | `#0b0b0a` |
| `--surface` | `#faf8f1` | `#181815` |
| `--ink` | `#111111` | `#f6f3e9` |
| `--secondary` | `#48463f` | `#c4c0b3` |
| `--tertiary` | `#645f52` | `#aba697` |
| `--border` | `#b8b2a3` | `rgba(255, 255, 255, 0.18)` |
| `--accent` | `#e8b325` | `#d9a21c` |
| `--on-accent` | `#111111` | `#0b0b0a` |
| `--danger` | `#8c2d22` | `#f0a099` |
| `--accent-ink` | `#111111` | `#f6f3e9` |
| `--cta` | `#e8b325` | `#d9a21c` |
| `--accent-soft` | `#e8b325` | `#d9a21c` |
| `--border-strong` | `#111111` | `#f6f3e9` |
| `--gallery` | `#e2ded2` | `#050505` |
| `--nav-active` | `#111111` | `#d9a21c` |
| `--sans` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` | `"Noto Sans SC", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` |
| `--display` | `var(--sans)` | `var(--sans)` |
| `--mono` | `"JetBrains Mono", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` | `"JetBrains Mono", system-ui, "PingFang SC", "Microsoft YaHei", sans-serif` |
| `--edge` | `24px` | `24px` |
| `--radius` | `0px` | `0px` |
| `--button-radius` | `0px` | `0px` |

### 字号阶梯

未单列行高时继承 1.5；这一方向刻意拉大标题与正文的比例。

| 字号 | 行高 | 字重 | 主要用途 |
|---:|---|---|---|
| 9px | 13.5px | 500 | 列表、菜品与 diff 评分后的“分” |
| 10px | 15px | 500 | 修改预览评分后的“分” |
| 11px | 14 / 15 / 16 / 16.5 / 20px | 400–600 | 状态、日期、caption、底部导航、脚注 |
| 12px | 16 / 18 / 19 / 21.6px | 400–700 | 元信息、筛选、备注标签、说明 |
| 13px | 19.5 / 20 / 20.8 / 22.75px | 400–700 | 搜索、详情概览、链接、表单标签与选项 |
| 14px | 21 / 22 / 25.2px | 400 / 600 | 正文基准、输入、diff 与到店正文 |
| 15px | 22.5 / 28.5px | 400 / 700 | 主按钮、长评正文 |
| 16px | 21 / 24px | 400 / 700 | 餐厅名、评分选项 |
| 17px | 24 / 25.5px | 700 | 菜名、历史条目标题 |
| 18px | 27px | 500 | 到店日期标题 |
| 19px | 28.5px | 600 | 列表评分 |
| 20px | 30px | 700 / 900 | 长评导语、预览/diff 小标题 |
| 22px | 33px | 900 | 章节标题 |
| 26px | 31.2px（1.2） | 600 | 菜品评分 |
| 28px | 42px | 400 | 修改前后预览数值 |
| 30px | 40.5px（1.35） | 900 | 到店面板标题 |
| 32px | 44.8px（1.4） | 400 | diff 数值 |
| 40px | 48px（1.2） | 900 | diff 页面标题 |
| 41px | 49.2px（1.2） | 900 | 表单标题 |
| 44px | 48.4 / 52.8px | 900 | 菜品库、修改记录标题 |
| 48px | 52.8 / 54.24px | 600 / 900 | 详情评分、两行餐厅名 |
| 56px | 61.6px（1.1） | 900 | 首页“食记” |

画廊外壳使用 11/12/13px 辅助字与 36px/900 标题，窄屏标题为 28px。

### 间距、圆角、阴影与动效

- 画板 393×852px，水平边距 `24px`；公共骨架仍为 28px 状态栏、12px 内容顶距、40px 工具栏和 68px 底部导航。
- 首页标题区高 91px，以 56px 标题换取品牌冲击；搜索框高 36px、上距 7px；筛选上距 10px、间隔 6px；列表统计上距 12px，并用 2px 黑线封底。
- 搜索框使用 `--border-strong`；评分 segmented control 为六等分、单段 44px 高、1px 边框和 0px 视觉间隙，首尾沿用 0px `--radius`，选中段使用 `--accent` / `--on-accent`。
- 餐厅行仅 61px、上下内边距 4px，靠 1px 强分隔线提高密度；菜品行最小 64px、上下 9px；详情标题与评分并排，间隔 12px；章节常用 15/17/19/22px 垂直间距。
- 应用组件统一 0px 圆角，按钮也为直角；唯一 4px 圆角来自系统手势条，不属于品牌组件。
- 无阴影；层级由 1px 与 2px 黑线、字号、字重和芥末黄色块完成。
- 动效保持克制且与另外两套一致：颜色过渡 `140ms ease-out`，Toast 140ms/4px 淡入；平滑页面定位在 reduced-motion 下切为即时。

### Python 实测对比度

| 主题 | 色对 | 对比度 | AA | AAA |
|---|---|---:|:---:|:---:|
| Light | `ink/bg` | 16.42:1 | 是 | 是 |
| Light | `secondary/bg` | 8.21:1 | 是 | 是 |
| Light | `tertiary/bg` | 5.53:1 | 是 | 否 |
| Light | `on-accent/accent` | 9.81:1 | 是 | 是 |
| Light | `nav-active/bg` | 16.42:1 | 是 | 是 |
| Dark | `ink/bg` | 17.74:1 | 是 | 是 |
| Dark | `secondary/bg` | 10.82:1 | 是 | 是 |
| Dark | `tertiary/bg` | 8.09:1 | 是 | 是 |
| Dark | `on-accent/accent` | 8.57:1 | 是 | 是 |
| Dark | `nav-active/bg` | 8.57:1 | 是 | 是 |

D3 的底部 tab 选中态不能直接复用 `--accent`：浅色主题中芥末黄 `#e8b325` 与米纸底 `#f2efe6` 的对比度只有 1.67:1，不满足 AA；因此 `--nav-active` 在浅色主题使用墨黑 `#111111`，仅在深色主题使用芥末黄 `#d9a21c`。

## 三方向对比

| 方向 | 长文阅读 | 大量条目扫读 | 最不像模板 | 深色表现 | Compose 落地成本 |
|---|---|---|---|---|---|
| D1 账簿 | **最佳**：15px/1.95 长评，衬线只用于标题，正文不疲劳 | 很好：65px 三行条目、44px 固定评分列、20px 边距提供更宽文字区 | 很强：无卡片堆叠、无阴影，像目录而非通用 App | 很好：四组指定色对全部 AAA，暖黑仍保留纸本层级 | **中**：需打包中文衬线字体和自定义 Typography；Divider 行与 4/8dp Shape 本身直接 |
| D2 Quiet Grid | 好：15px/1.9 清楚，但全无衬线更偏工具 | **最佳**：mono 日期/评分、72px 行、固定数字列与 3px 评分轨道最利于横向比较 | 中上：列表克制，但详情双列边框卡仍有常见效率工具感 | 很好：深色四组均 AAA，10% 白边线稳定 | **低**：最接近 Material 3 的 ColorScheme/Typography/Shape 映射，只需补 mono 字体和顶部 tab 指示 |
| D3 招牌 | 中：正文 15px/1.9 合格，但 40–56px 标题占用较多阅读空间 | 很好：61px 行和强分隔线很快；超大页头会减少首屏可见条数 | **最佳**：零圆角、粗规则线、招牌级字阶最难与模板混淆 | **最佳**：正文三档暗色对比最高，芥末黄在暗底仍为 8.57:1 | **中高**：直角 Shape 简单，但多组 40–56sp 标题、两行店名和系统字体缩放需要逐屏约束换行与高度 |

## 推荐

**推荐 D1「账簿 / Ledger」作为主方向。**

理由不是它“更有质感”，而是它在同一套版面里同时解决了浏览与记录两种核心任务：393px 宽画板采用 20px 边距，正文区实际比 D2/D3 多 8px；餐厅列表不用卡片，而是 65px 发丝线条目，店名、菜系/地址/菜数、最近到店三行连续左读，评分固定在 44px 右列，8 条数据不需要在卡片边界之间反复横向寻找。进入详情后，15px/1.95 的无衬线长评承担阅读，衬线只负责 31–35px 标题和关键数字，层级鲜明但不会像 D3 的 44–56px 标题那样挤压内容或放大系统字体后的换行风险。

D1 还保留了明确的产品识别：20px 账页边距、无阴影、4px 面板/8px 按钮、赤陶色只落在评分与操作上；它比 D2 的小卡片网格更不像通用效率模板，又比 D3 的海报式强对比更适合每天新增、编辑和回读。深色下四组指定色对全部达到 AAA，因此这套编辑排版在常年深色模式中也无需牺牲辅助信息。
