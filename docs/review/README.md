\# 不要修改这份 HTML

1. 除非是诸如mermaid这类渲染格式问题, 否则不允许直接编辑HTML文件。
2. 它是一次性导出快照产物，后续不编辑这个 html。
3. 如果后续想法发生变化、方案调整：修改对应的 spec.md / ADR；不要改动这份 html 快照。
4. html 只代表评审当时那一瞬间的分析结论。



\## 如何使用这份html



整体流水线顺序：\*\*wayfinder阅读评审文档 → /grill‑with‑docs 澄清 → /triage 分级 → /to‑spec 写规格 → /to‑tickets 拆工单 → 迭代编码评审 → ADR记录架构决策 → 更新CONTEXT.md\*\*



\### 步骤0：锁定执行边界（先执行，防止Agent越界修改归档模块）

直接发送这条指令：

```

Read the architecture‑review‑20260808‑220621.html review document.

All work is strictly scoped to agent/logfile‑reporter‑plugin.

Do NOT touch or suggest changes for any archived modules outside this component.

Respect the review priority: Candidate‑A highest priority, then Candidate‑B. Candidate‑C and Candidate‑D require grill session first, do not generate implementation tickets for them now.

```



\### 步骤1：Grill澄清（/grill‑with‑docs）——消除模糊点

> Candidate‑A虽然标记Strong，但依然有需要确认的边界：哪些类彻底内联、哪些对外保留接口、兼容旧外部调用、迁移策略（重构是原地重构还是逐步迁移）、测试策略。

```

/grill-with-docs

Source material: architecture‑review‑20260808‑220621.html Candidate‑A.

Focus questions:

1\. Refactor strategy: incremental refactor or rewrite‑in‑place for RulesEngine?

2\. Which existing public APIs must remain compatible for external caller?

3\. What are the acceptance criteria for "deep RulesEngine" — what interfaces should be public, what must become package‑private / internal implementation?

4\. Test strategy: keep existing test cases; add new module‑level test for RulesEngine; retire old scattered unit tests for pass‑through classes.

Capture confirmed terms into CONTEXT.md.

```

> 会话中产生新领域概念（deep module、pass‑through hop等）会询问是否写入`CONTEXT.md`，确认保存。



\### 步骤2：生成重构规格文档 /to‑spec

输出可评审的 spec 文件，作为后续编码、评审的依据，文件输出到 `docs/spec/rulesengine‑deep‑refactor‑spec.md`

```

/to-spec

Subject: agent/logfile-reporter-plugin Candidate‑A: deep RulesEngine refactoring specification.

Inputs: architecture‑review‑20260808‑220621.html, grill‑with‑docs conclusion.



Requirements:

1\. Merge scattered rule‑parsing, compile, pattern‑matching logic into deep RulesEngine module.

2\. Public surface: only RulesEngine.fromConfig(), matchSlow(), matchErrorIgnoreRuleIndex() are public; factory/cache/matcher classes become internal implementation detail.

3\. Keep runtime behaviour 100% equivalent before/after refactor; no functional change.

4\. Acceptance criteria: all existing tests pass; new module‑level test suite for RulesEngine; deleted pass‑through shallow wrapper classes.

5\. Compatibility: preserve required external public APIs.

6\. Explicitly list files to move / inline / delete as per Candidate‑A.



Output spec file to docs/spec/rulesengine-deep-refactor-spec.md

```



\### 步骤3：把Spec拆解成小的可执行工单 /to‑tickets

> 重点：拆成分步工单，不要一次性巨大变更；标签带上 `refactor‑rulesengine`

```

/to-tickets

Input: docs/spec/rulesengine-deep-refactor-spec.md.

Split into incremental, review‑able tickets for Candidate‑A RulesEngine refactor.

Tag: refactor‑rulesengine, agent/logfile‑reporter-plugin.



Rules for ticket splitting:

\- Each ticket is small enough for single PR review.

\- Ticket must include acceptance criteria referencing spec.

\- Do NOT generate tickets for Candidate‑B / C / D for now.

```



\### 步骤4：迭代编码评审循环（开发阶段反复使用）

每次改完代码，执行校验指令：

```

Review current changes against docs/spec/rulesengine-deep-refactor-spec.md.

Check:

1\. Are we keeping behaviour identical?

2\. Are implementation classes properly hidden (package‑private) inside deep RulesEngine module?

3\. Any leftover pass‑through shallow hops not yet eliminated?

4\. Are tests updated accordingly?

Flag deviations from spec.

```



\### 步骤5：A记录架构决策（重构完成后）

重构落地完毕，产出ADR，记录取舍，放到 `docs/adr/`

```

Produce ADR for Candidate‑A RulesEngine deep‑module refactor.

Record:

\- Reasoning from architecture review document.

\- Public API kept / hidden implementation details.

\- Refactor strategy chosen (in‑place / incremental).

\- Known trade‑offs.

Save to docs/adr/adr‑xxx‑deep‑rulesengine‑refactor.md

```



\### 步骤6（完成A之后，再处理Candidate‑B）

> A做完再启动B，不要并行；复用同样一套流程：`/grill‑with‑docs` → `/to‑spec` → `/to‑tickets`

```

/grill-with-docs

Source: architecture‑review‑20260808‑220621.html Candidate‑B LocalStore<T>.

Clarify: capacity mapping for each sender, concurrency contract, snapshot semantics, migration path from existing CircularBlockingQueue / LinkedHashMap.

```



\### Candidate‑C / Candidate‑D（当前阶段不要生成实现工单）

这两个标记为 `Worth exploring`，存在未解决约束（C的类加载器边界；D线程生命周期拆分细节），只做调研grill，\*\*不生成spec和工单\*\*。

> 调研指令示例（未来需要的时候执行）：

```

/grill-with-docs

Source: Candidate‑C and Candidate‑D in architecture review document.

Goal: explore constraints and risks; do NOT output implementation spec or tickets, only risk inventory and open questions.

```



\### 关键避坑点（结合你之前JDK17迁移背景）

1\. 重构Candidate‑A/B的同时，\*\*需要兼顾JDK17兼容性审计\*\*；在spec/ticket的验收标准加上：

> “变更产物仍然满足JDK8编译、可在JDK17运行，不引入新JPMS/内部API依赖”

2\. 所有产出物（spec、ADR）路径遵循你现有的 matt‑skill 目录约定，不要散落；

3\. 全程禁止触碰仓库中 archived 的其他模块。

