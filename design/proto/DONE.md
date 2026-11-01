# 第三轮完成清单

- 三份表单评分均改为单行六段 segmented control，移除重复评分状态。
- 列表、菜品、详情、修改预览与 diff 的评分口径统一为指定格式。
- 修改记录移除重复分组标题，列表摘要仅保留字段名，预览保留评分变化入口。
- 餐厅详情去除重复到店次数，并与编辑表单逐字统一评分、地址、评价和笔记。
- 类型语义统一为菜系，筛选与表单使用四个常用菜系加“更多”，diff 改为“小吃 → 川菜”。
- 三套深色底部导航均加强为实心图标、强调色文字与顶部 2px 指示条。
- 三份首页确认状态栏后保留 12px 内容间距，排序控件归入列表标题行。
- 深色搜索边框与列表行 hover/active 提示已加强，排序控件补充下拉指示。

# 第四轮完成清单

- 三份编辑页评价框均在原 fit pass 中由 CSS 高度 132px 调至 140px；headless Chromium 实测从 `client/scroll/over = 130/138/8` 修为 `138/138/0`，正文完整可见。
- D3 菜品库将列表脚注顶部间距缩短 2px；`.screen-content` 实测从 `client/scroll/over = 740/741/1` 修为 `740/740/0`。
- Headless Chromium 全量复核（Light/Dark 数字一致，dark class 注入后等待 900ms）：D1 `screen-home 736/736/0，screen-detail 740/740/0，screen-dishes 740/740/0，screen-history 804/804/0，screen-form 720/720/0，#restaurant-review 138/138/0`；D2 `screen-home 736/736/0，screen-detail 740/740/0，screen-dishes 740/740/0，screen-history 804/804/0，screen-form 720/720/0，#restaurant-review 138/138/0`；D3 `screen-home 736/736/0，screen-detail 740/740/0，screen-dishes 740/740/0，screen-history 804/804/0，screen-form 720/720/0，#restaurant-review 138/138/0`。

# 第五轮完成清单

- D2 评分统一为 0.1 精度：列表与菜品使用 12px 实心星 + 18px 等宽数字，详情使用 20px 星 + 36px `4.3 / 5.0` + 120×3px 十格计量条，编辑页改为 `min=1 / max=5 / step=0.1` 的真实滑杆、实时读数、十格刻度和清除评分状态。
- D2 移除菜品独立 tab，底部导航改为「餐厅 / 到店 / 我的」；03 画板改成餐厅详情下的「这家店的菜」，展示 8 道菜、每行更多按钮、麻婆豆腐展开编辑态及“+ 新增菜品”。
- D2 首页搜索默认以“辣子鸡”展示 2 条跨菜品 / 评价命中及命中字段提示；“清空”后恢复 8 家列表和原 placeholder。PLAN 同步改为 rating_tenths 十分位整数、餐厅内菜品路由、restaurant_search 聚合索引和 D2 Quiet Grid 最终视觉。
- Headless Chromium 实测（`clientHeight / scrollHeight / over`，直接注入 dark class 后等待 900ms；Light/Dark 数字一致）：`screen-home 736/736/0，screen-detail 740/740/0，screen-dishes 749/749/0，screen-history 804/804/0，screen-form 720/720/0`，5 个画板全部 `over = 0`；清空搜索后的首页仍为 `736/736/0`。
- 评分联动验证：Playwright 先把滑杆置于 5.0，再用鼠标按 24px 滑钮的有效轨道坐标拖到 4.3；DOM 实测 `range.value=4.3`、读数 `4.3`、隐藏提交值 `4.3`、十格中 9 格填充、轨道填充 `82.5%`。点击“清除评分”后读数为“未评分”、滑杆 disabled、填充 0 格。
- 搜索交互验证：初始 `query=辣子鸡`、可见 2 条、标题“2 条结果 · 含菜品与评价”；点击“清空”后 `query` 为空、可见 8 条、标题“8 家 · 按最近到店排序”，placeholder 恢复为“搜索店名、菜系、地址、菜品、评价”。

# 第六轮完成清单

- 三处工具栏文字改前 → 改后：修改记录页“餐厅档案”→“修改记录”；菜品页“这家店的菜”→“这家店的菜”（核验原文已正确）；表单页“餐厅档案”→“编辑餐厅”。
- 十格刻度条由 3px 改为 6px、段间距改为 3px；headless Chromium 实测 `getBoundingClientRect().height`：详情页 `6px`（宽 `120px`），编辑页 `6px`（宽 `321px`），每段圆角 `1px`，未填充段使用 `--border-strong`。
- 评分步进联动实测：初始读数 / range / 隐藏值为 `4.3 / 4.3 / 4.3`，点击 `+` 三次后均为 `4.6`；填充段从 `9` → `9`，符合 `Math.round(4.6 × 2) = 9` 的取整逻辑。
- 删除确认条切换生效：Playwright 点击“删除这道菜”后实测原保存/删除按钮 `hidden=true`、确认条 `hidden=false`，文案为“确定删除「麻婆豆腐」？”；点击“取消”后两者状态恢复；再次打开并点击“删除”后该行带 `deleted` class，标题变为“02 · 已删除”。
- Light/Dark 均用 headless Chromium 复核，注入 dark class 后等待 `900ms`；五个画板 `scrollHeight - clientHeight` 均为 `0`，页面脚本无报错。
