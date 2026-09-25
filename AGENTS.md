## Execution environment

- **Linux / WSL** — shell 用 `bash`（UTF-8，中文无乱码问题）。`pwsh` 不保证可用，不要默认调用。
- **Windows 宿主 shell** — 运行命令用 `pwsh`（PowerShell 7）：UTF-8 end-to-end，中文脚本输出可干净往返。默认 Windows PowerShell 5.1 控制台按 GBK 解码，会乱码 UTF-8 文本 — 运行脚本与长验证请用 `pwsh -NoProfile -File <script>` / `Start-Process pwsh.exe`，不要用 5.1 shell。

## Agent skills

- **Issue tracker** — 何时读：spec/issue 存放、publish/fetch ticket、wayfinding 操作。→ `docs/agents/index.md`
- **Triage labels** — 何时读：技能提到 triage 角色标签时。→ `docs/agents/index.md`
- **Domain docs** — 何时读：探索代码前读领域词表与 ADR。→ `docs/agents/index.md`
- **Review reports** — 何时读：`improve-codebase-architecture` 阅读/产出评审 HTML 时。→ `docs/review/index.md`
- **Module maintenance status** — 何时读：动任何模块、调用工具链（to-tickets / triage / to-spec / domain-modeling）前。→ `docs/repo/index.md`
- **Build & demo runtime convention (adr-01)** — 何时读：构建工具链 / 演示运行时约定。→ `docs/adr/index.md`
- **Known TODOs** — 何时读：规划 demo-app 验证范围时。→ `docs/repo/index.md`
- **Code style / 纯重构规范** — 何时读：写新 Java 代码、改既有代码、拆分大方法、对齐代码风格时。→ `docs/repo/index.md`

## Task routing

- **小改** — 目标明确、可回滚，且不涉及 API、数据结构、并发或跨模块行为：直接实现，运行定向测试并复核 diff。
- **功能改动** — 行为变化或涉及多个文件：需求不清时先 grill；稳定后写 spec，多步骤再拆 tickets；分步实现并按 spec 评审。
- **大改 / 高风险** — 跨模块、架构取舍、迁移、兼容、安全或性能风险：先用 wayfinder / grill 收敛决策，再形成 spec / tickets；分步实现、独立评审，落地后记录 ADR。
- **按门槛触发** — grill 只用于需求或设计不清，tickets 只用于多步骤工作，ADR 只记录需要长期保留的架构决策。
- **统一收口** — 验证通过、验收项有证据、spec / ticket 状态回写、diff 不越界；commit / push 仅在用户明确要求时执行。
- **OMO 补短板** — 用于可并行的探索、竞争假设和独立复核；spec、领域语言与最终决策由 Matt Skills 主流程维护。
- **Skill 演化** — 先以内联规则运行；稳定使用一段时间后，再考虑抽成 /route-work skill。

## Agent tooling quirks (OpenCode + OMO)

- **工具怪癖排查** — 何时读：OpenCode/OMO 行为异常（发送失败、命令/agent 名不匹配）时。→ `docs/tutorial/index.html`