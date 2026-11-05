# PR17 评审任务书（独立评审会话）

你是独立评审者，与实现者不是同一会话。评审对象：PR #17 `feat/pr17-density-and-sliding-sessions`（实现提交 `35b74a1` + 修复 `9d1b2fd`、`5f676f8`，基线 master `002023f`）。

## 你可以采信的证据（已由助手独立核实，不要重复验证）

- CI run `41891869332` 四 job 全绿：backend ✅（12 个测试文件全过，`auth.test.ts` 31 用例含两个新滑动用例）、android 编译+单测 ✅、双 API instrumentation 各 **55 tests / 0 failed**。
- Instrumentation HTML 报告已核实：API35 与 API26 均 55/0。
- 视觉守卫双脚本 exit 0（本机与 CI 双确认）。
- 290 张 pr17-*.png 截图与 `pr17-layout-api35.txt` 测量日志在 CI artifact；关键实证：A7 拆卡后 191.8dp < 旧 213.7dp（font1.0，两主题一致）；G2 横排在 496 轮真实触发（minimum[14,256] / maximum[268,510]）；G3 两 Checkbox 横排在 488 轮（unrated[26,102,50,150] / range[276,102,300,150]）。
- 后端滑动逻辑 diff 已由助手逐行读过：`SESSION_TTL_SECONDS=15552000` 共用、rotate 的 batch 原子写回、同 request-id 重放不续期。

## 评审范围（你要做的事）

1. **逐文件读 diff**（`git diff 002023f..5f676f8`）：对照 `design/pr17-discussion/PLAN.md` 的每一条改动承诺，找出：未实现的条目、超出 PLAN 的改动、testTag 丢失/改名、大字体回退缺失、深浅主题处理遗漏。
2. **安全与语义审查**：
   - 后端：滑动窗口的安全边界——refresh token 重放/撤销语义是否真的没被削弱；`session_expires_at` 响应、DB、加密重放三处时间一致性；`cleanupAuth` 是否会误清滑动后的活跃会话（读 `backend/src/auth/cleanup.ts` 对照）。
   - Android：删除会话管理链路是否有残留死码/死引用；`loadSettings` 改后 loading/busy 语义是否完好；e2e 的 OkHttp 直连 DELETE 是否正确携带 Idempotency-Key 与当前会话 token。
3. **UX 密度复核**：读 PLAN 的验收清单逐条对照实现，找出「承诺了但没做到」的布局项。特别注意深色模式与 font200 截图（可用 CI artifact 里的 pr17-*.png 名称清单核对覆盖面，不必看图内容——语义与几何断言已在测试里）。
4. **PLAN 偏离清单**：实现者 NOTES.md 里声明的差异是否合理；有没有 NOTES 没声明但实际存在的偏离。

## 产出

`design/pr17-review/REVIEW.md`：按 BLOCKER / MAJOR / NIT 三级列出问题，每条带文件:行号与具体修法；最后给「可合并 / 不可合并」结论。若发现 BLOCKER，列出最小修复集。不修改任何生产代码——你只写评审文档。
