# Notes（开发笔记）

临时性 / 经验性沉淀，非规范。

- **2026-08-29：OpenCode 插件 + Oh-My-OpenAgent 配置** —— 何时读：配置 / 排查 OpenCode 与 OMO 插件时。→ `2026-08-29-opencode-plugins-omo-setup.md`
- **2026-08-30：AGENTS.md 下沉经验——环境性知识该内联** —— 何时读：再往 AGENTS.md 里下沉/精简内容、判断某条约定该内联还是走 index 时。→ `2026-08-30-agents-md-inline-ambient-knowledge.md`
- **2026-09-20：模型路由经验——设计讨论 vs 代码实现的国内模型选型** —— 何时读：调整 .omo 模型路由、新增 agents/categories、或换大版本模型需要重新选型时。→ `2026-09-20-model-routing-design-vs-implementation.md`
 - **2026-09-25：持续压测下的插件内存分析——1G→3.4G 是健康的高水位** —— 何时读：压测/长跑后判断 JVM 内存是否泄漏、或需要调堆参数时。→ `2026-09-25-trace-metrics-stress-memory-analysis.md`
  - **2026-09-26：端点极端值 → traceId 追溯（Phase 5 追加第一步）** —— 何时读：改动 Trace 指标聚合、`/inner/sw/metrics*` 读口、或推进 error/slow 明细持久化闭环时。→ `2026-09-26-endpoint-extreme-trace-recording.md`
  - **2026-09-27：h2-full-trace-mem 操作手册（全量 trace / 双源对照 / 慢查询 / QPS / 慢判定复用）** —— 何时读：忘了这些功能在哪看、怎么开、怎么验证，或要重建/重启 demo 观察效果时。→ `2026-09-27-h2-full-trace-mem-操作手册.md`
  - **2026-09-27：功能交接与宣传博客素材** —— 何时读：要把 2026-08–09 的功能写成宣传博客、或向他人交接本次工作全貌时。→ `2026-09-27-功能交接-宣传博客素材.md`
