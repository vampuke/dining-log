# M2：Apply 真实窗口字体矩阵

状态：**已完成**。[成功采集 run 41943522685](https://github.com/vampuke/dining-log/actions/runs/41943522685) 的 API26/35 均 2/0；HTML 中 Apply 测试分别 passed (1m53.35s) / passed (2m0.12s)。每 API 均 8 轮 requested == actual，保留节点自身 density 的 overflow 检查；18 段展开地址取样/截图，所有重测 overflow=false。

生产源码是 `5f676f8ade36f69e2d22a187a91f4841bff6d26e`；只注入测试变更。每轮设置系统 `font_scale`、recreate Activity、安装测试 fixture，再从 Dialog `TextLayoutResult.layoutInput.density.fontScale` 断言实际窗口字体。finally 设置并校验系统/Activity 字体恢复 1.0；恢复后的 Activity 无 Compose 内容，因此不在清理阶段调用 awaitIdle。

同时核对当前/拟修改的 name/address/review、空必填禁用、新旧侧选择、展开地址全文、逐屏滚到地址末端、保存与返回 footer 可见及回调。保存每轮恰好增量 1。扫描当前可见字段标签与文本，并按自身 density 保留原 overflow 检查。人工宿主 320dp 不充当弹窗宽度；窗口实测 width=1016px、density=2.625，API26 bounds=(32,32,1048,1496)，API35=(32,32,1048,1595)，详见原日志。

[字体与截图来源 manifest](M2-apply-manifest.json)、[API26 日志](M2-apply-api26/pr17-layout-api26.txt)、[API35 日志](M2-apply-api35/pr17-layout-api35.txt) 均纳入 git。文件名全部使用 actualFont；日志和 manifest 同时记录 requestedFont/actualFont。

| API | 主题 | requested | actual | footer 截图 |
| --- | --- | ---: | ---: | --- |
| 26 | light | 1.0 | 1.0 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.0-light-api26.png) |
| 26 | light | 1.3 | 1.3 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.3-light-api26.png) |
| 26 | light | 1.31 | 1.31 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.31-light-api26.png) |
| 26 | light | 2.0 | 2.0 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont2.0-light-api26.png) |
| 26 | dark | 1.0 | 1.0 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.0-dark-api26.png) |
| 26 | dark | 1.3 | 1.3 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.3-dark-api26.png) |
| 26 | dark | 1.31 | 1.31 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont1.31-dark-api26.png) |
| 26 | dark | 2.0 | 2.0 | [保存/返回](M2-apply-api26/pr17-apply-pairs-footer-actualFont2.0-dark-api26.png) |
| 35 | light | 1.0 | 1.0 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.0-light-api35.png) |
| 35 | light | 1.3 | 1.3 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.3-light-api35.png) |
| 35 | light | 1.31 | 1.31 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.31-light-api35.png) |
| 35 | light | 2.0 | 2.0 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont2.0-light-api35.png) |
| 35 | dark | 1.0 | 1.0 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.0-dark-api35.png) |
| 35 | dark | 1.3 | 1.3 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.3-dark-api35.png) |
| 35 | dark | 1.31 | 1.31 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont1.31-dark-api35.png) |
| 35 | dark | 2.0 | 2.0 | [保存/返回](M2-apply-api35/pr17-apply-pairs-footer-actualFont2.0-dark-api35.png) |

一张 API35 / dark / 1.31 的中途 `review-current` 图片未被成功 run 的每秒 adb pull 抓到，已从同生产 SHA 的初次真实采集补回，独立来源记录在 manifest。所有最终断言、16 张 footer 图和全部展开地址分段图来自成功 run；初次 run 在清理等待阶段被取消，不作为通过依据。此项不将历史 actual=1 的 font2 文件名充当真实 font200 证据。
