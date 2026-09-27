# 02 — 双源读口 + demo 双源对照页

**What to build:** 在 H2 为准之外，新增"从内存热层（`KeyedLocalStore`）按 traceId 取回链路"的读口，并在 demo 查询页把两份视图并排展示、高亮一致/差异，使过渡期可以交叉核对。主读路径不变（`getTraceView` 仍以 H2 为准）。

**Blocked by:** 01 — 全量 trace 持久化 + 放大容量 + 删 `trace_level`（对照须以 H2 为全量真相）。

**Status:** ready-for-agent

- [ ] 客户端新增"从内存热层取链路"的方法，返回与 H2 侧查询**同契约**（`{logs:[...]}`），`traceStore` 缺失时返回空 Map、不报错
- [ ] 宿主工具类新增对应静态方法，经既有拦截器 + instrumentation 反射跨 ClassLoader 取数，**只返回 JDK 原生类型**
- [ ] demo 新增读口 `/inner/sw/trace-memory`；`trace-query.html` 支持**双源对照**：同屏展示 H2 视图与内存视图，标注 `logs` 条数是否一致并高亮差异
- [ ] 不改既有宿主工具类方法签名与返回、不改既有读口契约
- [ ] 端到端：对一个已知 `traceId`，H2 与内存的 `logs` 条数/关键字段一致（或差异有明确解释）
