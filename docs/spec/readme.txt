# Matt Skill 完整调度流程：`agent/logfile‑reporter‑plugin` JDK17兼容改造
> 前置现状
1. 仓库大部分模块 **archived，只处理 `agent/logfile‑reporter‑plugin`**
2. 已经有：`AGENTS.md`（模块维护状态）、`CONTEXT.md`、架构评审快照 `docs/review/architecture‑review‑20260808‑220621.html`
3. 同时正在做架构重构（Candidate‑A RulesEngine）：**JDK17兼容与架构重构两件事要分清，可以并行但验收标准独立**
4. 核心目标：**JDK8编译（`--release 8`），Class版本52，可在JDK8、JDK17 JVM运行；尽量消除 `--add‑opens` 临时参数**

> 原则：**先风险审计 → 分级 → 写spec → 拆工单 → 开发迭代 → ADR沉淀；不要直接上手改代码**

## 整体流水线顺序
`wayfinder读取代码` → `domain‑modeling（风险审计）` → `/triage风险分级` → `/grill‑with‑docs（确认取舍）` → `/to‑spec输出迁移规格` → `/to‑tickets拆小PR工单` → 循环代码评审校验 → 完成后写ADR → 更新CONTEXT.md

> ⚠️ 重要边界约束，每次会话开头先喂给Agent，防止扫描归档模块
```
Scope strictly limited to agent/logfile‑reporter‑plugin. Ignore all other archived modules in repository.
Two independent workstreams exist:
1. JDK17 compatibility migration
2. RulesEngine deep‑module refactor (Candidate‑A from architecture review)
Do not mix acceptance criteria between them.
```

---

## Step1：domain‑modeling：执行JDK迁移风险审计
> 目的：扫描代码产出风险清单：内部API、被删除JEE包、SecurityManager、ASM/CGLIB字节码库、反射、硬编码JVM参数
直接复制指令：
```
/domain-modeling
Target module: agent/logfile-reporter-plugin
Task: Full JDK8 → JDK17 compatibility risk audit.
Checklist items:
1. Usages of sun.* / com.sun.* JDK internal non‑public APIs
2. Removed JavaEE packages: javax.bind, javax.activation, javax.annotation etc.
3. SecurityManager related API calls
4. Byte‑manipulate dependencies: ASM, Javassist, CGLIB versions & usages
5. Reflection access that will trigger JPMS InaccessibleObjectException
6. Hard‑coded JVM arguments, GC options (CMS etc.)
7. Build configuration: maven compiler source/target/release settings
8. Unit test behaviour difference between JDK8 and JDK17

Output structured risk inventory.
Do not produce implementation tickets at this phase.
```
> 输出一份风险清单，记录每一处风险、文件位置、风险等级（启动崩溃 / 警告 / 不影响）

## Step2：/triage 风险分级
把上一步风险清单输入，划分P0/P1/P2
- **P0：JDK17直接启动崩溃（IllegalAccess、ClassNotFound）必须修复**
- **P1：运行告警、未来版本会移除，优先修复；短期可接受临时启动参数**
- **P2：优化项，不阻断JDK17运行，可以延后**

```
/triage
Input: JDK migration risk inventory from domain‑modeling output.
Priority rules:
P0: runtime crash under JDK17, block adoption
P1: deprecation warning / JPMS access warning, no immediate crash
P2: nice‑to‑have cleanup, not blocking JDK17 compatibility

All items are limited to agent/logfile‑reporter‑plugin.
```

## Step3：/grill‑with‑docs 做取舍决策（关键）
有些风险存在两种路径：重构彻底消除，或者短期用 `--add‑opens` 临时方案。这里要确认策略，明确临时参数的淘汰时间。
```
/grill-with-docs
Input: triage prioritized risk list.
Clarify these questions:
1. For each P0 risk: refactor fix or temporary JVM flag workaround?
2. If we keep any --add‑opens workaround: what is sunset roadmap to remove them?
3. Build requirement: enforce maven compiler <release>8</release>; forbid raw source/target 8.
4. CI test matrix requirement: build with JDK8 compiler; runtime test on JDK8 and JDK17.
5. Compatibility guarantee: no behavioural difference running on JDK8 vs JDK17.

Capture new domain terms into CONTEXT.md.
```

## Step4：/to‑spec 生成迁移规格文档
输出活的权威文档：`docs/spec/jdk17‑compatibility‑spec.md`，后续编码、PR评审全部以此为准。
```
/to-spec
Subject: agent/logfile-reporter‑plugin JDK17 Compatibility Specification.

Inputs: domain‑modeling risk inventory, triage result, grill‑with‑docs conclusion.

Requirements:
1. Build strategy: compile with --release 8, produce class version 52. Can run on JDK8 and JDK17 JVM.
2. Resolve all P0 blocking risks.
3. Document any temporary --add‑opens JVM arguments and sunset plan. Permanent reliance on add‑opens is forbidden.
4. Upgrade byte‑code libraries to JDK17‑compatible versions where needed.
5. CI matrix: compile artifact, test runtime under both JDK8 and JDK17.
6. Acceptance criteria: identical functional behaviour across both JVM versions.
7. Explicit note: this spec is independent from RulesEngine refactor workstream.

Output file: docs/spec/jdk17-compatibility-spec.md
```

## Step5：/to‑tickets 拆分成可执行小工单
> 重点：工单粒度要小，每个PR解决一类风险；标签 `jdk17‑migration`；不要和RulesEngine重构工单混在一起。
```
/to-tickets
Input: docs/spec/jdk17-compatibility-spec.md
Split into incremental, review‑friendly tickets.
Tags: jdk17‑migration, agent/logfile‑reporter-plugin.

Rules:
- One ticket addresses one category of risk.
- Every ticket must include acceptance criteria referencing jdk17‑compatibility‑spec.md.
- Do NOT merge jdk17 migration work with RulesEngine refactor tickets.
```

## Step6：开发迭代：每次修改后校验（反复执行）
每次提交/PR评审，调用下面指令校验改动是否符合spec：
```
Review code changes against docs/spec/jdk17-compatibility-spec.md.
Check:
1. Does change preserve JDK8 runtime compatibility?
2. Are we avoiding new dependencies on JDK internal APIs?
3. Any new need for --add‑opens? If yes, flag it and require sunset note.
4. Check maven compiler configuration: confirm using release=8 not raw source/target.
Flag deviations from specification.
```

## Step7：迁移全部完成，输出ADR
迁移完成，把架构决策沉淀，写入 `docs/adr/`，记录哪些风险怎么处理、保留了哪些临时参数、淘汰计划。
```
Produce ADR for agent/logfile‑reporter‑plugin JDK17 compatibility migration.
Record:
‑ Key risks discovered during audit
‑ Fix strategy for each P0/P1 item
‑ Any temporary JVM flags and sunset plan
‑ Build & CI configuration decisions (--release 8)
Save to docs/adr/adr‑jdk17‑migration.md
```

## Step8：更新 CONTEXT.md
把 JPMS、`--add‑opens`、`class file version 52`、`release flag` 这类新概念更新术语表：
```
Scan docs/spec/jdk17-compatibility-spec.md and docs/adr/adr‑jdk17‑migration.md, extract new domain‑specific terms, add entries into CONTEXT.md following standard format.
```

---

# 非常关键：两套工作流如何协同（JDK17迁移 VS RulesEngine重构Candidate‑A）
> 你手上两件工作：
1. RulesEngine 架构重构（Candidate‑A）
2. JDK17兼容迁移

**两种实操选择**
1. 方案一：**先做完JDK17迁移，再做RulesEngine重构**
    - 优势：重构时已经消除全部JDK17风险，重构变更不会混入兼容修复；测试简单。
2. 方案二：并行开展（更常见）
    - JDK17迁移只做兼容性修复，**不改动业务架构**；
    - RulesEngine重构只做结构收拢，**不能顺带修复JDK兼容问题**；
    - **两套spec、两套工单，两套验收标准，绝对不要混在同一个PR。**

> 在给Matt Skill指令里，一定要反复强调：`Do not mix acceptance criteria between them`，防止Agent把两类需求揉到同一个ticket/spec。

## 一键总启动指令（直接复制，一次性拉起前4个阶段）
```
Start full matt‑skill workflow for agent/logfile‑reporter‑plugin JDK17 compatibility migration.
Scope: only agent/logfile‑reporter‑plugin; skip archived modules.
Workstreams: separate JDK17 migration from RulesEngine refactor(Candidate‑A).
Sequence: domain‑modeling risk audit → triage risk prioritization → grill‑with‑docs decision making → to‑spec produce docs/spec/jdk17‑compatibility‑spec.md.
Do not generate implementation tickets in this invocation.
```

如果你需要，我可以给你一份 `jdk17‑compatibility‑spec.md` 的初始草稿文本。