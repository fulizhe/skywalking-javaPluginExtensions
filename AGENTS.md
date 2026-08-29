## Execution environment (Windows)

- **pwsh 运行约定** — 运行命令用 `pwsh`（PowerShell 7）：UTF-8 end-to-end，中文脚本输出可干净往返。默认 Windows PowerShell 5.1 控制台按 GBK 解码，会乱码 UTF-8 文本 — 运行脚本与长验证请用 `pwsh -NoProfile -File <script>` / `Start-Process pwsh.exe`，不要用 5.1 shell。

## Agent skills

- **Issue tracker** — 何时读：spec/issue 存放、publish/fetch ticket、wayfinding 操作。→ `docs/agents/index.md`
- **Triage labels** — 何时读：技能提到 triage 角色标签时。→ `docs/agents/index.md`
- **Domain docs** — 何时读：探索代码前读领域词表与 ADR。→ `docs/agents/index.md`
- **Review reports** — 何时读：`improve-codebase-architecture` 阅读/产出评审 HTML 时。→ `docs/review/index.md`
- **Module maintenance status** — 何时读：动任何模块、调用工具链（to-tickets / triage / to-spec / domain-modeling）前。→ `docs/repo/index.md`
- **Build & demo runtime convention (adr-01)** — 何时读：构建工具链 / 演示运行时约定。→ `docs/adr/index.md`
- **Known TODOs** — 何时读：规划 demo-app 验证范围时。→ `docs/repo/index.md`

## Agent tooling quirks (OpenCode + OMO)

- **工具怪癖排查** — 何时读：OpenCode/OMO 行为异常（发送失败、命令/agent 名不匹配）时。→ `docs/tutorial/index.html`