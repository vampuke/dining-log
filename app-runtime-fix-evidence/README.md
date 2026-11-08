指定基线为 `fd9294181b5fef8bb67cb3f2c337711f36e45beb`。本修复只修改 `Pr18Evidence.withOverlays` 与更新进度的一处断言：让真实的较低绘制层、外层留白和被裁剪的区域不再误挡点击，同时保留高层覆盖、未知顺序、窗口/IME、物理输入、保护截图和精确回调检查。生产代码、布局、颜色、legacy themes、字体、PNG 及捕获元数据全部保持基线字节。

已先读取 Actions **42435516388** 的完整 `summary.json` 和 API26/35 的全部失败 HTML/栈，再逐例检查实际几何、语义树或源码祖先关系。每套 API 实际执行 **190 cases / 32 failures / 0 skipped**；Android 构建制品记录 95 单元 cases 全通过。不能把这些基线结果当成本提交的 Android GREEN。

| 每套 API 的失败方法组 | failures | 实际证据与原因 |
| --- | ---: | --- |
| PasswordDialog | 4 | `password-save` 被无标签滚动容器的外层矩形误挡；实际滚动 viewport 位于保存栏上方，语义树显示滚动分支先绘制、底栏分支后绘制。 |
| UpdateAllStages | 4 | 节点实际文本为 `正在下载 · 50%`；Compose 1.8.2 的 `assertTextContains` 默认按完整文本项匹配，`50%` 并不是该文本项。 |
| LandscapeLists | 4 | `record-list` 与其行属于底栏下方的绘制分支，虽然矩形确实与 tab 重叠。 |
| NarrowLandscapeIme | 4 | `edit-scroll` 的外层包含留白，实际输入 viewport 已避开保存栏与真实 IME；保存栏属于后绘制分支。 |
| CompleteFlows | 4 | `save` 被 `edit-scroll` 外层矩形误挡；语义 viewport 和绘制分支都证实此处没有列表遮挡。 |
| Startup late opt-in / manual opt-in / manual opt-out | 12 | 每例均有实际 owner/root、touch point 与列表重叠记录。此失败点没有输出诊断树；层序依据是 ProductRoot → ListScreens → ScreenFrame 的源码关系，ScreenFrame 先放 content，再放浮动 capsule。 |

两套 API 的 64 个栈均与父上下文提取文件及原始 HTML SHA256 一致。32 个弹窗/屏幕点击实例保存了实际 unmerged 诊断树，24 个 Startup 实例明确标注为“源码层序”，8 个实例为进度文本匹配错误。没有补造 Startup 的运行时 rank、target ID 或其他元数据。

六份原始 Gradle HTML 自带 12 行尾空格，RED/GREEN 的原始 JUnit 输出保留末尾空行；为保留完整源文件哈希，本目录的 `.gitattributes` 仅关闭这些归档 HTML 和 RED/GREEN 原始日志的空白诊断，仍显示文本 diff。两次原始检查结果保存在 `lightweight/archival-whitespace-check.json`，源码、测试、文档及所有行为断言不受此规则影响。

运行时修复通过真实 `RootForTest.semanticsOwner.unmergedRootSemanticsNode` 的公开 `SemanticsNode.children` 遍历同一 root 的绘制顺序。该 API 在固定版本中明确是 inverse hit-test / paint order，内部按 zIndex、placement 和非语义布局祖先展开；代码没有调用这些内部符号。真实 `LayoutInfo.parentInfo` 链排除目标自身的输入祖先，也适用于 merged 语义树省略祖先的情况。候选输入 action 的选择条件原样保留；文本测量/替换 action 与 `SetText` 是不同 API，文本子节点不会因此成为输入覆盖，独立可点击子节点仍会拒绝。

覆盖区域改用公开 `touchBoundsInRoot`：滚动 viewport 的裁剪不会扩张到外层 padding，可点击控件的真实 minimum-touch extent 仍计入覆盖。只有同 root、可证实先绘制的候选被排除；不同 root 或 rank 缺失仍以 `paint-order-unknown` 保守拒绝。不同 Window owner 继续使用实际 accessibility window layer 与 screen rectangles，IME 逻辑原样保留。进度断言升级为三个 busy 分支的完整文本 `assertTextEquals`，包括精确的 50%、未知百分比和校验状态；错误、缺失、截断及额外文本均不能通过。

固定 API 来自本地 BOM 2025.05.01 / UI 与 UI-test 1.8.2 / Material3 1.3.2 的源代码 JAR。`pinned/source-manifest.json` 保存 JAR、完整源码条目 SHA256 与已读取的 API 片段；复制的文本 matcher/assertion 源码已逐字与 JAR 对照。

实际轻量验证直接抽取当前适配器、曝光条件、进度断言与生产进度表达式，连同固定 matcher 在独立 Kotlin/JUnit CONTROL 夹具中执行。相同 26 个测试及夹具输入在修复前后保持 SHA256 一致：**RED 26 tests / 11 failures → GREEN 26 tests / 0 failures**。其中 56 个 CI 几何 CONTROL 样本从全部误挡变为全部可达；低层列表/行/无标签 scrim、裁剪、外层留白、minimum-touch extent、文本子节点、独立可点击子节点、高层同标签覆盖、所有五类输入 action、未知顺序/owner、不同窗口和 IME 均有对抗检查。Startup CONTROL 里的 `-9001` 明确是合成测试 ID，层序来自已审计源码；这些 CONTROL 结果不是 Android 实际运行或新截图证据。

| 文件 | 用途 |
| --- | --- |
| `ci/summary.json`、`ci/api-{26,35}/*.html` | 原始汇总与六份完整失败 HTML，按字节保存。 |
| `ci/full-failures.json`、`ci/source-manifest.json`、`ci/read-receipt.json` | 全 64 栈、实际几何/原始诊断树、来源哈希及读取核对收据。 |
| `base/*.kt`、`source-manifest-base.json` | 原始修复输入、741 个基线文件 blob 清单、完整 Android 方法/case 身份。 |
| `lightweight/red/`、`lightweight/green/` | 实际源码快照、各编译/JUnit 命令、退出码、完整输出及输入/tool JAR SHA256。 |
| `lightweight/harness-setup-attempts.json` | 五次工具准备编译失败/超时的完整原始内容与哈希；不计入目标 RED/GREEN。 |
| `scope-green.json`、`verify-scope.py` | 739 个基线文件字节不变；只修改两处测试源码；原有 17 类 / 93 方法 / 174 cases 和完整 20 类 / 109 方法 / 190 cases 均保留。 |

轻量编译使用既有 Kotlin 1.9.24/JUnit JAR、低优先级、单核、256 MB 堆上限和分段编译。它没有编译 Android/Compose 依赖，不能替代项目 Kotlin 2.1.21 的 Android 编译或设备行为验证。收据在提交前执行，`execution_head` 仍为基线；实际测试的工作树内容由输入 SHA256 和 `scope-green.json` 绑定。

独立复核可执行以下轻量命令，结果写入临时目录以保留本提交的原始收据：

```sh
python3 -B app-runtime-fix-evidence/run-adversarial-tests.py --receipt /tmp/pr18-runtime-independent-controls
python3 -B app-runtime-fix-evidence/verify-scope.py --receipt /tmp/pr18-runtime-independent-scope.json
```

**本提交的 Android GREEN 始终待真实 Actions。** 父上下文应独立审查后在本提交 head 运行完整 API26/35 各 190 cases 的矩阵，并复核 Android 编译、lint、95 单元 cases 与 backend gates，再决定合并/发布。本次没有本地 Gradle、模拟器或 npm 重型构建，没有凭据/生产调用、provider fallback、resume、委派或 push。
