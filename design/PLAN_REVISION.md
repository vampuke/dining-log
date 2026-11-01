# PLAN.md 修订清单（三项用户决策）

修订对象：`design/PLAN.md`（原 1981 行 → 现 1996 行）
配套任务书：`design/PATCH_TASK.md`
执行方式：Codex 完成主体改动后被 provider 限流中断，剩余 7 处由我补完并逐条 grep 复核。

## 1. 应用名称：食档 → 食记

| 位置 | 改动 |
|---|---|
| 第 7 行 | 「暂定应用名称『食档』」→「应用正式定名『食记』」 |
| 全文 | `grep -c 食档` = **0** |

仓库名 `vampuke/dining-log`、applicationId `top.alanvampuke.dininglog` 保持不变。

## 2. PBKDF2：600,000 → 10,000 次

| 位置 | 改动 |
|---|---|
| 第 19 行 决策表 | 密码行改为 10,000 次，并写明安全代价与第 5 节指引 |
| 第 254 行 DDL | `password_iterations INTEGER NOT NULL DEFAULT 10000 CHECK (password_iterations >= 10000)` |
| 第 965 行 | 算法确定段改为 10,000 次 + 新注册默认写 10000 |
| 第 969 / 976 / 979 / 981 / 988 行 | 迭代次数、实现路线、AUTH-01 验证档位（1k / 10k）、PBKDF2 公式同步 |
| 第 1000–1011 行 | **新增 5.2.1「免费档参数取舍」**：OWASP 差距（1/60）、强密码 12 位、分钟 + 小时双窗口限流、5 次失败锁 15 分钟、pepper、恢复码一次性、渐进升级路径（下次登录 CAS 重算到 600,000）、10k 必须真实 Worker 实测 |
| 第 1391 行 wrangler.toml | `PASSWORD_ITERATIONS = "10000"` |
| 第 1564 行 P0 门槛 | 改为 10,000 次需真实免费档 Worker 冷/热各 ≥30 次测得落在 10 ms 内 |
| 第 1929 行 AUTH-01 | 改为「1k / 10k 两档实测，10k 稳定过 10 ms，超限继续下调并回写」 |
| 第 1526 行 风险表 | 迭代 cap 风险改写为「升级到 600,000 时才受 cap 影响」 |

保留 600,000 的 3 处**均明确标注为 OWASP 推荐值 / 未来升级目标值，不是本方案采用值**（第 969、1002、1008 行）。
附录第 1978 行引用仍写 OWASP 推荐 600,000 —— 那是引用原文，保留正确。

## 3. 后端档位：改采 Workers 免费档

| 位置 | 改动 |
|---|---|
| 第 26 行 决策表 | 成本行改为「本项目采用 Workers 免费档，不付 USD 5/月」，并列明 10 ms CPU / 每日请求 / D1 行读写 / 无 SLA 风险 |
| 第 1416 行 | CPU 配置说明改为「按免费档部署，不配置 [limits] cpu_ms」 |
| 第 1444 行 | 额度表表头改为「Workers Free（本方案采用）/ Workers Paid（对比参考，本项目不采用）」 |
| 第 1456–1458 行 | 判断段落改写：免费档可运行，代价指向 5.2.1 |
| 第 1507 行 | 密码 CPU 预算按 10k 重算（20–40 ms/次 × 30 次/月 ≈ 1,200 CPU ms） |
| 第 1525 行 风险表 | 「Paid 为发布基线」→「采用免费档，10k，仍超限则继续下调」 |
| 第 1554 行 P0 | 「Paid 预算」→「免费档额度预算」 |
| 第 1567 / 1568 行 | 云权限改为确认处于免费档；验证资源改为 D1 每日 10 万写行须分批 |
| 第 1991 行 审批入口 | 审批对象改为「最终视觉方向、应用名食记、…、Workers 免费档 + PBKDF2 10,000 次的取舍」 |

## 验证

```bash
grep -c 食档 design/PLAN.md                       # 0
grep -n "PASSWORD_ITERATIONS" design/PLAN.md      # 10000
grep -n "password_iterations INTEGER" design/PLAN.md  # DEFAULT 10000 CHECK >= 10000
grep -n "600,000\|600000" design/PLAN.md          # 仅 OWASP 推荐/升级目标/引用
grep -c "Paid" design/PLAN.md                     # 剩余均为对照说明，无「本方案基线」
```

## 未做（留给后续）

- 第 2 节「视觉方向」仍是旧的 A 青瓷 / B 石墨 / C 深海蓝三候选，**待用户从 D1/D2/D3 选定后替换**。
  新方向的设计 token 见 `design/proto/DIRECTIONS.md`。
