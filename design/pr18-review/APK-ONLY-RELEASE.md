# PR18 / 0.8.3：APK-only 发布边界

本轮只批准 `production_mode=apk-only` 路径。`full` 保留既有生产流程和守卫；其中裸 `wrangler deploy` 仍有根据 TOML 重建 vars、未完整继承 live bindings 的旧风险，本轮没有修复或批准这条部署路径。实现、轻量验证和提交均在本地完成；Actions、正式 release、APK 构建/设备验收和所有生产操作均未执行。

起点是 `1857cfdbf4a3ff96c63ab391ebdf2ff6a456ab5d`，已包含 0.8.3 版本提交。准确已发布源码为 `refs/tags/v0.8.2^{commit}` = `eae439a88e2d96f93dc341223c6502ff65c91881`；接手时 `master` = `45c7f65823042deb76171732dcb2a000f8620e65`，二者不同。source guard 使用 selection 中的准确 previous tag，每次解析实际本地 tag ref，禁止缺失时猜测或替换为 master。本轮没有重复已有版本/F5/F6/F7 提交。

`workflow_dispatch.inputs.production_mode` 是 required choice，选项为 `apk-only`、`full`，默认 `apk-only`。输入原样进入 job env；独立可信 inline bash gate 位于原 checkout/ancestry gate 后、任何候选脚本前，只接受这两个精确字符串。空值、未知值、大小写变体、空白和换行均失败。原仓库/ref、full SHA、HEAD、tag、master ancestry、准确 CI、版本、签名及两 API 守卫保留。

下表描述正式 workflow 的路径约束，不表示本轮已经执行正式发布：

| 步骤 | apk-only | full |
| --- | --- | --- |
| 可信 checkout/完整 SHA/tag/ancestry、CI/source/version 选择 | 必须通过 | 必须通过 |
| APK-only 离线 source guard | 必须通过，位于 backend 检查和所有生产步骤之前 | 跳过 |
| 完整 backend candidate 离线检查及 dry-run bundling | 保留 | 保留 |
| 正式 APK v2 验签、固定证书、checksum、旧版覆盖安装及 API 26/35 启动 | 保留 | 保留 |
| Worker/D1 checkpoint、pre-migration、domain guard | 跳过，不注入 CF token | 保留 |
| 生产 migration apply、D1 integrity、Worker deploy | 跳过，不注入 CF token | 保留 |
| 公网 GET health，HTTP 200 和精确 JSON | 保留 | 保留 |
| 生产 auth/CRUD/history/delete/restore/logout smoke | 跳过，不创建 smoke 账号 | 保留 |
| 发布真实签名 APK 和 checksum | 前面所有本模式必需检查通过后 | 前面所有本模式必需检查通过后 |

`.github/scripts/verify-apk-only-release.py` 仅使用 Python 标准库、Git 和已 checkout 的对象，不获取网络、CF token 或本地 bundle。它读取 `$RUNNER_TEMP/dining-release-selection.json`，拒绝缺失/畸形/重复键/非标准 JSON；要求 candidate commit、实际 HEAD 和 `RELEASE_COMMIT` 相同，selection tag 与 `RELEASE_TAG` 相同，并解析 `refs/tags/<previous.tag>^{commit}`。lightweight 与 annotated tag 均按真实 commit 比较，同名 branch 或指向 blob 的 tag 不可替代。

比较使用 `git --no-replace-objects ls-tree -r -t -z`，覆盖整个 backend 树的路径名称、对象类型、blob/tree ID 和 mode，包含空子树。除了根 tree 因允许的 TOML blob 变化而产生的 ID 变化，所有非 `backend/wrangler.toml` 条目必须完全一致；TOML 自身的 mode/type 也必须一致且为普通文件。文件增加、删除、改名、依赖/源码/migration 内容变化和 mode/type 变化均拒绝。

两个 Git TOML blob 分别由 `tomllib` 解析。仅以下六个已有顶层 `[vars]` 字段可以去除后比较，均要求在两份中存在且为字符串：

- `APP_VERSION_CODE`：正十进制 Android versionCode，最大 2147483647。
- `APP_VERSION_NAME`：稳定 X.Y.Z 结构，最多 80 字符。
- `APP_DOWNLOAD_URL`：有效的 HTTPS 结构，不含账号/密码或控制空白字符。
- `APP_APK_SHA256`：64 位十六进制摘要。
- `APP_RELEASE_NOTES`：最多 4000 UTF-16 code units，与现有 API 长度约束一致。
- `APP_RELEASED_AT`：正十进制 Unix timestamp，最大 JavaScript safe integer。

移除且仅移除这六个字段之后，类型保留的 TOML 语义序列必须完全相等。其他 `[vars]`、嵌套同名 APP 字段、D1 ID/bindings、limits、compatibility、routes 和所有其他 config 仍受比较；`false/0`、整数/浮点也不能混同。格式、注释或字段顺序变化只有在 TOML 语义相等时才通过，不按文本切片忽略 TOML 或整个 vars 表。

guard 将 pass/fail 固定诊断写入 `$RUNNER_TEMP/dining-apk-only-source-check.json`；通过记录包含 previous/candidate tag、commit、backend tree、TOML blob 和 SHA-256、非公告 config 语义 SHA-256、变更路径及六字段名称，不输出配置、binding 或 token 值。该文件加入原 `release-evidence` artifact，原证据路径保留。Publish 名称改为 required release checks；说明按实际模式注明，APK-only 明确记载部署、migration 和 CRUD smoke 被跳过。

生产 TOML 和六个公告值在本轮保持原样。真正正式 APK 发布、下载验签/checksum/覆盖安装验证后，生产六字段公告 PATCH 由父线程已审 Python 工具单独执行；本轮不执行 PATCH、migration、Worker deploy 或任何生产账号/数据操作。

CI 已有的 `test_*.py` discovery 自动包含新测试，无需修改 `ci.yml`。本轮未改保护清单或任何保护条目，也未改版本三文件、原 review 报告、Android 源码或仪器测试。原 17 个 named Android 仪器测试文件 / 93 个方法按起点逐文件 hash 比对，保持不变。

本地真实验证命令如下，全部 exit 0：

```sh
node --test .github/scripts/release-guards.test.mjs
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py' -v
python3 -B android/tools/verify_p6_visuals.py
python3 -B android/tools/verify_bite_visuals.py
python3 -B -m unittest discover -s android/tools -p 'test_*.py' -v
```

分别为原 node 守卫 7 项、release Python discovery 54 项（原 18 项 + 新 36 项）、两套 source guards、Android Python source tests 7 项。新测试使用真实临时 Git 仓库、commit/tag/mode/empty subtree 和真实语义 TOML，没有伪造 Git/API 返回；原安装测试继续使用其原有 synthetic adb 夹具，这不构成真 APK 或设备验收。

首个 RED 对原无条件 checkpoint 报错，exit 1；模式、缺失 source guard、六字段语义许可/必需字段、严格 selection JSON、模式发布说明和公告长度也分别保留真实 RED→GREEN。六字段切片的首次 GREEN 尝试因测试夹具误改嵌套同名变量而失败：guard 正确拒绝，随后仅修正夹具替换范围并真实 GREEN，失败日志保留。

负控在临时文件中删除 source guard、删除 inline mode gate、逐个删除七个生产步骤的 full 条件、移除 backend 内容比较、或忽略整个 vars 表，均使实际测试 exit 1。原 workflow/guard 未被负控修改。逐命令 stdout、exit、hash 与 RED/GREEN/负控记录位于 `/tmp/dining-release-20261107/apk-only-release-local-checks/`；准确 start/end/new commits、四文件 hash、来源证明及未执行范围汇总写入 `/tmp/dining-release-20261107/apk-only-release-implementation.json`。

这些结果只证明本轮本地发布边界实现和轻量守卫。准确整合 SHA 的 Actions、真实正式 release/APK、生产 readback 和另一个新会话的独立复核仍由父线程完成；不得据此宣称已经发布、生产验证成功或 whole PR 通过。
