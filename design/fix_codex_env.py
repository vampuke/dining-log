# 生成 Codex 的 env 文件：保留 agentrouter，同时补上 ark key
import pathlib, re

env_path = pathlib.Path.home() / '.hermes/.env'
ark = None
for line in env_path.read_text().splitlines():
    if line.startswith('HERMES_CUSTOM_ARK_CN_BEIJING_VOLCES_COM_API_KEY='):
        ark = line.split('=', 1)[1].strip().strip('"').strip("'")
        break
assert ark and ark.startswith('ark-'), f'ark key not found: {ark!r}'

codex_env = pathlib.Path.home() / '.codex/env'
existing = codex_env.read_text() if codex_env.exists() else ''
lines = [l for l in existing.splitlines() if 'AGENTROUTER_API_KEY' in l]

new = []
new.append(lines[0] if lines else 'export AGENTROUTER_API_KEY=""')
new.append(f'export ARK_API_KEY="{ark}"')
codex_env.write_text('\n'.join(new) + '\n')
print('wrote', codex_env)
for l in new:
    print(' ', re.sub(r'"[^"]*"', '"<redacted>"', l))
