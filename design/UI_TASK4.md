# 第四轮：按实测数据修最后 3 个具体缺陷

对象：`/root/dining_log/design/proto/d1-ledger.html`、`d2-quiet-grid.html`、`d3-signboard.html`

上一轮已通过截图+浏览器实测复核，绝大多数问题已解决。**只剩下面 3 条是实测确认的真缺陷**，
另外请补两处文档同步。不要重新设计，不要动其他任何东西。

## 我已经在三个文件里插入了一段 `/* ---- fit pass ---- */` 覆盖样式

先读这三个文件末尾那段 fit pass，**在它基础上调整数值**，不要新增第二个覆盖块、不要删掉它。

## 缺陷 1（三个文件都有）：编辑页「评价」文本框仍差 6px，正文显示不全

实测：`#restaurant-review` 当前 `height: 132px`，但内容 `scrollHeight = 138px`（107 个汉字，含换行）。
即最后一行被裁掉。这是详情页有、编辑页看不到的内容，用户会认为数据不一致。

**修法（三选一，选最稳的）：**
- 把该 textarea 高度改成能完整容纳 138px（例如 140px），并确认 `scrollHeight <= clientHeight`
- 或者用 `rows` + `min-height` 让文本自适应
- 不许改成 `overflow: auto` 了事——这是 393×852 的静态画板，滚动条在截图里看不出来，等于还是藏内容

改完必须自测：`scrollHeight <= clientHeight`。

## 缺陷 2（只有 d3-signboard.html）：菜品库页面溢出 1px

实测 `#screen-dishes .screen-content` 的 `scrollHeight = 741`，`clientHeight = 740`，溢出 1px。
1px 虽小但会让最后一行贴着裁切线。

**修法**：把 d3 菜品行的高度或列表底部间距减 2px，使 `scrollHeight <= clientHeight`。

## 缺陷 3（三个文件）：确认所有 5 屏都零溢出

修完上面两条后，逐文件用浏览器验证这 5 个屏的 `scrollHeight - clientHeight <= 0`：
`screen-home`、`screen-detail`、`screen-dishes`、`screen-history`、`screen-form`。

如果发现别的屏还有 1–2px 溢出，一并修掉。注意：`screen-history` 的 clientHeight 是 804（它没有底部导航），
这是正常的，不要为了对齐而给它加导航。

## 文档同步（两处）

### A. `DIRECTIONS.md`
上一轮我给 d3 加了 `--nav-active` 变量（修复浅色下 tab 选中文字芥末黄对比度只有 1.67 的问题）。
请在 D3 的 token 表里补上：

| Token | Light | Dark |
|---|---|---|
| `--nav-active` | `#111111` | `#d9a21c` |

并在 D3 的对比度表补两行实测值（用 Python 算，确认数值一致）：

- Light `nav-active/bg`（`#111111` on `#f2efe6`）→ 应为 16.42:1
- Dark `nav-active/bg`（`#d9a21c` on `#0b0b0a`）→ 应为 8.57:1

同时说明：d3 的 tab 选中之所以不能直接用 `--accent`，是因为浅色下芥末黄 `#e8b325` 与米纸底 `#f2efe6`
对比度只有 1.67:1，不达 AA；因此浅色用墨黑、深色才用芥末黄。

### B. `DONE.md`
追加第四轮 3 条改动，每条一行，带上修完的实测数字（textarea client/scroll、d3 dishes 溢出的前后值）。

## 验证要求（必须真跑，把数字写进 DONE.md）

用 headless Chromium 打开三个文件，对每屏输出 `screenId: clientHeight / scrollHeight / over`，
以及 `#restaurant-review` 的 `clientHeight / scrollHeight`。全部 over ≤ 0 才算通过。

Chromium 可执行文件：`/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`
playwright-core：`/root/.npm/_npx/e41f203b7505f1fb/node_modules/playwright-core`

注意：页面脚本会按 `localStorage` / `prefers-color-scheme` 自己设置主题，**注入 dark class 后要等 900ms 再读取**，
否则会被页面脚本覆盖，测出的是错误主题下的值（我上一轮就踩过这个坑）。
