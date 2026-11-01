#!/bin/bash
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1

codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
  --output-last-message /root/dining_log/logs/dirmd_summary.md \
  "只做一件事：读取 /root/dining_log/design/proto/ 下的 d1-ledger.html、d2-quiet-grid.html、d3-signboard.html 三个文件里 :root 与 :root.dark 的 CSS 变量和字号/间距/圆角规则，然后用 Python 精确计算对比度，写出 /root/dining_log/design/proto/DIRECTIONS.md（中文）。必须包含：每方向一节（气质一句话、借用的真实设计系统及具体借用规则、light/dark 完整 token 表、字号阶梯、间距圆角动效）；用 Python 实测的对比度表（ink/bg、secondary/bg、tertiary/bg、on-accent/accent，两位小数，标注 AA/AAA）；三方向对比表（长文阅读/大量条目扫读/最不像模板/深色表现/Compose 落地成本）；最后明确推荐一个并给出具体版面层面的理由。不要修改任何 HTML 文件。" \
  > /root/dining_log/logs/dirmd.log 2>&1
echo "EXIT=$?"
