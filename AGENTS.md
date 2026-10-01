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

- **Issue tracker** — 何时读：spec/issue 存放、publish/fetch ticket、wayfinding 操作。→ `docs/agents/index.md`
- **Triage labels** — 何时读：技能提到 triage 角色标签时。→ `docs/agents/index.md`
- **Domain docs** — 何时读：探索代码前读领域词表与 ADR。→ `docs/agents/index.md`
- **Review reports** — 何时读：`improve-codebase-architecture` 阅读/产出评审 HTML 时。→ `docs/review/index.md`
- **Module maintenance status** — 何时读：动任何模块、调用工具链（to-tickets / triage / to-spec / domain-modeling）前。→ `docs/repo/index.md`
- **Build & demo runtime convention (adr-01)** — 何时读：构建工具链 / 演示运行时约定。→ `docs/adr/index.md`
- **Known TODOs** — 何时读：规划 demo-app 验证范围时。→ `docs/repo/index.md`
- **Code style / 纯重构规范** — 何时读：写新 Java 代码、改既有代码、拆分大方法、对齐代码风格时。→ `docs/repo/index.md`
- **教学空间页面格式** — 何时读：新增或修改 `docs/tutorial/` 下的教学页面（入口页 / 课程 / 参考）。→ `docs/tutorial/index.html`
- **排错清单（compose / 中间件 / 代理 / 端口）** — 何时读：本地或远程把 compose 跑起来时出错。→ `agent/demo-app/NOTES-docker-stress.md`（第 13~16 条）与 `agent/demo-app/README-remote-verify.md`（远程 Linux 压测 runbook）

## Task routing

- **小改** — 目标明确、可回滚，且不涉及 API、数据结构、并发或跨模块行为：直接实现，运行定向测试并复核 diff。
- **功能改动** — 行为变化或涉及多个文件：需求不清时先 grill；稳定后写 spec，多步骤再拆 tickets；分步实现并按 spec 评审。
- **大改 / 高风险** — 跨模块、架构取舍、迁移、兼容、安全或性能风险：先用 wayfinder / grill 收敛决策，再形成 spec / tickets；分步实现、独立评审，落地后记录 ADR。
- **按门槛触发** — grill 只用于需求或设计不清，tickets 只用于多步骤工作，ADR 只记录需要长期保留的架构决策。
- **统一收口** — 验证通过、验收项有证据、spec / ticket 状态回写、diff 不越界；commit / push 仅在用户明确要求时执行。
- **OMO 补短板** — 用于可并行的探索、竞争假设和独立复核；spec、领域语言与最终决策由 Matt Skills 主流程维护。
- **Skill 演化** — 先以内联规则运行；稳定使用一段时间后，再考虑抽成 /route-work skill。

## 入口约定（demo-app）

- **日常只有两个脚本入口**：`scripts/run-with-agent.ps1`（起应用）与 `scripts/stress.ps1`（压测）。
  **新增第三个入口、或把这两者包一层新脚本之前先问用户** —— 入口一多，"该敲哪条"就变成日常摩擦。
  压测档位用 `-AllEndpoints` / `-NormalOnly` / `-WithDeps` 表达（三者互斥），不再另开脚本承载。
- **前置为零**：端口、凭据这类会变的默认值写进 `application.yml`（compose 用 `environment` 覆盖），
  用户跑 `run-with-agent.ps1` 时**不需要设任何环境变量**。
- **中间件与端口**：三层中间件在 compose 的 `deps` profile（默认不起，`run-with-agent.ps1 -WithDeps` 顺带拉起）；
  宿主映射避开常用端口（`16379/13306/19092`，本机常被别的栈占），容器内仍用服务名 + 标准端口。
  排错细节（端口占用、镜像 403、daemon 代理只认 Windows 系统代理）→ `agent/demo-app/NOTES-docker-stress.md` 第 13~16 条。
- **依赖面相关的坑**（连接失败不产生边 / 判成功≠调用成功 / 组件名按实测值 / 插件 support 范围 / fat jar 驱动注册 / kafka `close()` 无界等待）
  → 何时读：改 `DepsDemoService`、给依赖拓扑加组件、或给 `checks.sh` 写依赖面断言前。→ `docs/notes/2026-10-01-deps-demo-topology-probe.md`

## 验证节奏

- **短命令自己跑，长命令交给用户**：构建、compose 编排、压测、长跑一律给命令让用户跑；
  确需自己跑时拆成 **<60s 的短调用**。
- **后台起进程后立刻回报**：后台起 java / compose / 压测容器后，先告诉用户"在跑了、日志在哪"，再去轮询。
  工具调用挂到超时往往是**假死**（子进程持有 stdout），把"启动"与"检查"拆成两次调用即可 —— 不让用户干等。
- **失败即给结论与下一步**，不用"我继续"过渡。

## 文档写作（图 > 表 > 文）

写 `docs/`、NOTES、README、spec 等任何**给人读**的文档时：**开头直达目的**（见核心准则 3），然后**先图、再表、最后才写段落** —— 扫一眼能懂 > 逐句读懂。

| 载体 | 何时用 | 本仓示例 |
| --- | --- | --- |
| 图 | 有流程、分支、层级或时间线；读者要的是"整体形状" | Mermaid 流程图（GitHub 的 `.md` 直接渲染；自建 HTML 需自己引 `mermaid.js`） |
| 表 | 并列项 ≥ 3，且每项有 ≥ 2 个属性（文件/作用、坑/原因/做法、字段/含义/默认值） | `agent/demo-app/NOTES-docker-stress.md` 的"踩坑与约定" |
| 文 | 只剩 1–2 句因果、命令、结论 | "启动前先 `docker compose up -d demo-app`" |

- 表格一格只放一个意思；一格要分点用 `<br>`，内容多了就拆成两行。
- 命令、配置片段、原样输出（报错、`[progress]` 字段）进代码块，不塞进表格。
- 图与表重复时留图、细节回落到表；两者都别在正文里再复述一遍。
- 改既有文档时顺手把已成清单的段落改成表格 —— 这条对旧文档同样生效。

## 教学空间页面格式（docs/tutorial）

教学页面（`docs/tutorial/`）的 HTML 有固定骨架。**照 `docs/tutorial/index.html` 抄，不要另起炉灶。**

| 页面类型 | 骨架 | 必须有 |
| --- | --- | --- |
| 入口页 / hub | `<body class="hub">` → `.hub-grid` → `nav.outline` + `main` | **左侧课程线一览**（见下）—— 唯一能一眼掌控有哪几条线的地方 |
| 课程页 lesson | 单栏 `<body>` → 正文 → `.foot` | 底部返回 hub 链接 + 一手资料链接 + 「有疑问问 agent」 |
| 参考页 reference | 同 lesson | 返回 hub 链接（速查/避坑页还要能被 `AGENTS.md` 直接指向） |

### 左侧大纲目录树（入口页强制）

- 左栏 `nav.outline` **只列课程线，不列具体页面**。它的职责是「一眼看清有哪几条线、每条线是干什么的」，**不是导航菜单**。用扁平 `<ul>`，每条线一个 `.branch`。
- 每条线给**一行定位句**（`.kind`），说清这条线的方法论与形态，中英对照 —— 例如「可移植集成测试模式 · 含实跑」「工具使用 · 已完结」。
- 具体页面清单放**右栏正文**，不要复制进左栏 —— 重复即噪音。
- **新增课程线时必须同步更新大纲**；大纲里没有的线等于不存在。新增页面则不需要动左栏。
- 语义标记只有三个：`.branch` 课程线名、`.kind` 定位句、`.cur` 当前页。
- 窄屏自动降级为单栏、打印时隐藏 —— `.outline` 组件已实现，**不要在页面里重写这套行为**。

### 组件复用

- 样式**一律**链接 `assets/course.css`。已内置：`.callout(.tip/.warn)`、`.quiz`（配 `assets/quiz.js`，即时判分 + 重答）、`.tree`、`.compare`、`.path`、`.tag`、`.hub-grid` + `.outline`。
- **可复用的不许内联**：第二页要用的样式/脚本，先落成 `assets/` 里的组件再链接；内联是最后手段。
- 页面只允许内联**该页独有**的规则（如某页特有的卡片网格），并在该 `<style>` 上注明「仅本页特有」。
- `assets/omo.css` 是 `course.css` 的 `@import` 薄壳，保留只为旧链接不失效；**新页面直接链 `course.css`**。
- 每页 `.foot` 声明样式与组件来源。

### 硬性要求

- 中文回退字体保留 `Songti SC` / `Noto Serif SC`，正文衬线 —— 与本仓 Tufte 风格一致。
- `@media print` 下：大纲隐藏、`pre` / `.tree` 允许换行、`h2` 避免分页断开。
- quiz 各选项**长度尽量一致**（同字数），不得用排版或措辞暗示答案；答错要亮出正确项并解释**为什么**。

## Agent tooling quirks (OpenCode + OMO)

- **工具怪癖排查** — 何时读：OpenCode/OMO 行为异常（发送失败、命令/agent 名不匹配）时。→ `docs/tutorial/index.html`