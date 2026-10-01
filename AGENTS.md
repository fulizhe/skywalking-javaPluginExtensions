## 核心准则

**这四条优先于本文件其余内容。** 动手前（写代码 / 写文档 / 跑验证）先对照它们。

1. **快速进入验证循环** —— 优先把**用户能自己跑起来**的验证路径交到手：命令 + 预期看到什么。
   过程能省则省；把"我该怎么验证"摆在"我做了什么"前面。
2. **不主动新增文档** —— 默认只改已有文档。确需新文档（新的 README / notes / 清单类）时**先问用户**；
   spec 或票里点名要写的文档照写。同类文档的"又一份"尤其要先问。
3. **文档开头直达目的** —— 开头三行内就让读者能动手：怎么跑 / 是什么 / 前提是什么。
   细节、坑、参考往后放或交给链接；命令进代码块，不埋在段落里。
4. **不替用户跑长验证，也不让用户干等** —— 拆短调用、后台起进程先回报。详见「验证节奏」。

## Execution environment

- **Linux / WSL** — shell 用 `bash`（UTF-8，中文无乱码问题）。`pwsh` 不保证可用，不要默认调用。
- **Windows 宿主 shell** — 运行命令用 `pwsh`（PowerShell 7）：UTF-8 end-to-end，中文脚本输出可干净往返。默认 Windows PowerShell 5.1 控制台按 GBK 解码，会乱码 UTF-8 文本 — 运行脚本与长验证请用 `pwsh -NoProfile -File <script>` / `Start-Process pwsh.exe`，不要用 5.1 shell。

## 版本线（version lines）

- **`master` = 2.0.0 开发线** —— 功能与 AI 开发在此进行。
- **`release/1.0.0` = 1.0.0 冻结线** —— 只收 bugfix / 必要诊断小改，不加功能；每轮打 `1.0.0-maintN` tag。
- 维护细则（tag 不移动、bugfix 走 cherry-pick 不 merge、版本号策略）→ `docs/repo/version-lines.md`

## Agent skills

- **写作规则与教学页面格式** — 何时读：写或改任何面向人的文档（README / NOTES / spec / 页面）时看 §1；改 `docs/tutorial/` 下的页面时看 §2。→ `docs/agents/working-rules.md`
- **demo-app 入口与压测档位** — 何时读：改 demo-app 脚本、跑压测、或排查中间件与端口时。→ `docs/agents/working-rules.md` §3
- **Issue tracker** — 何时读：spec/issue 存放、publish/fetch ticket、wayfinding 操作。→ `docs/agents/index.md`
- **Triage labels** — 何时读：技能提到 triage 角色标签时。→ `docs/agents/index.md`
- **Domain docs** — 何时读：探索代码前读领域词表与 ADR。→ `docs/agents/index.md`
- **Review reports** — 何时读：`improve-codebase-architecture` 阅读/产出评审 HTML 时。→ `docs/review/index.md`
- **Module maintenance status** — 何时读：动任何模块、调用工具链（to-tickets / triage / to-spec / domain-modeling）前。→ `docs/repo/index.md`
- **Build & demo runtime convention (adr-01)** — 何时读：构建工具链 / 演示运行时约定。→ `docs/adr/index.md`
- **Known TODOs** — 何时读：规划 demo-app 验证范围时。→ `docs/repo/index.md`
- **Code style / 纯重构规范** — 何时读：写新 Java 代码、改既有代码、拆分大方法、对齐代码风格时。→ `docs/repo/index.md`
- **教学空间页面格式** — 何时读：新增或修改 `docs/tutorial/` 下的教学页面（入口页 / 课程 / 参考）；格式规则见上面「写作规则与教学页面格式」§2，页面骨架照 `docs/tutorial/index.html` 抄。
- **排错清单（compose / 中间件 / 代理 / 端口）** — 何时读：本地或远程把 compose 跑起来时出错。→ `agent/demo-app/NOTES-docker-stress.md`（第 13~16 条）与 `agent/demo-app/README-remote-verify.md`（远程 Linux 压测 runbook）
- **GitHub Actions（CI 缓存 / matrix / 镜像分发 / 排错顺序）** —— 何时读：改 `.github/workflows/*.yml`、CI 跑得慢或莫名变红、或想在动手前判断某个写法能不能用时。→ `docs/notes/2026-10-01-github-actions-ci-pitfalls.md`

## Task routing

- **小改** — 目标明确、可回滚，且不涉及 API、数据结构、并发或跨模块行为：直接实现，运行定向测试并复核 diff。
- **功能改动** — 行为变化或涉及多个文件：需求不清时先 grill；稳定后写 spec，多步骤再拆 tickets；分步实现并按 spec 评审。
- **大改 / 高风险** — 跨模块、架构取舍、迁移、兼容、安全或性能风险：先用 wayfinder / grill 收敛决策，再形成 spec / tickets；分步实现、独立评审，落地后记录 ADR。
- **按门槛触发** — grill 只用于需求或设计不清，tickets 只用于多步骤工作，ADR 只记录需要长期保留的架构决策。
- **统一收口** — 验证通过、验收项有证据、spec / ticket 状态回写、diff 不越界；commit / push 仅在用户明确要求时执行。
- **OMO 补短板** — 用于可并行的探索、竞争假设和独立复核；spec、领域语言与最终决策由 Matt Skills 主流程维护。
- **Skill 演化** — 先以内联规则运行；稳定使用一段时间后，再考虑抽成 /route-work skill。

## 验证节奏

- **短命令自己跑，长命令交给用户**：构建、compose 编排、压测、长跑一律给命令让用户跑；
  确需自己跑时拆成 **<60s 的短调用**。
- **后台起进程后立刻回报**：后台起 java / compose / 压测容器后，先告诉用户"在跑了、日志在哪"，再去轮询。
  工具调用挂到超时往往是**假死**（子进程持有 stdout），把"启动"与"检查"拆成两次调用即可 —— 不让用户干等。
- **失败即给结论与下一步**，不用"我继续"过渡。

## Agent tooling quirks (OpenCode + OMO)

- **工具怪癖排查** — 何时读：OpenCode/OMO 行为异常（发送失败、命令/agent 名不匹配）时。→ `docs/tutorial/index.html`
