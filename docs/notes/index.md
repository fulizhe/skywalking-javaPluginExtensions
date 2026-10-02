# Notes（开发笔记）

临时性 / 经验性沉淀，非规范。

- **2026-08-29：OpenCode 插件 + Oh-My-OpenAgent 配置** —— 何时读：配置 / 排查 OpenCode 与 OMO 插件时。→ `2026-08-29-opencode-plugins-omo-setup.md`
- **2026-08-30：AGENTS.md 下沉经验——环境性知识该内联** —— 何时读：再往 AGENTS.md 里下沉/精简内容、判断某条约定该内联还是走 index 时。→ `2026-08-30-agents-md-inline-ambient-knowledge.md`
- **2026-09-20：模型路由经验——设计讨论 vs 代码实现的国内模型选型** —— 何时读：调整 .omo 模型路由、新增 agents/categories、或换大版本模型需要重新选型时。→ `2026-09-20-model-routing-design-vs-implementation.md`
 - **2026-09-25：持续压测下的插件内存分析——1G→3.4G 是健康的高水位** —— 何时读：压测/长跑后判断 JVM 内存是否泄漏、或需要调堆参数时。→ `2026-09-25-trace-metrics-stress-memory-analysis.md`
  - **2026-09-26：端点极端值 → traceId 追溯（Phase 5 追加第一步）** —— 何时读：改动 Trace 指标聚合、`/inner/sw/metrics*` 读口、或推进 error/slow 明细持久化闭环时。→ `2026-09-26-endpoint-extreme-trace-recording.md`
  - **2026-09-27：h2-full-trace-mem 操作手册（全量 trace / 双源对照 / 慢查询 / QPS / 慢判定复用）** —— 何时读：忘了这些功能在哪看、怎么开、怎么验证，或要重建/重启 demo 观察效果时。→ `2026-09-27-h2-full-trace-mem-操作手册.md`
  - **2026-09-29：跨 ClassLoader 传自定义类为何不 CNFE——炸的是语义，不是链接** —— 何时读：改动"要被宿主持有的数据结构"（如用 POJO 替代 Map）、或排查宿主读口 JSON 契约键名时。→ `2026-09-29-cross-classloader-tag-json-semantics.md`
- **2026-09-29：孤段过滤入门——为什么会"凭空"出现一段、两种典型 trace 原型** —— 何时读：新手入门 trace/segment/refs，或判断某条 trace 是否为孤段、会不会被插件过滤时。→ `2026-09-29-孤段过滤入门-两种典型trace原型.md`
- **2026-09-30：出口 span 实跑探针（operationName / 组件身份 / spanLayer 事实）** —— 何时读：改依赖边、出口标签、组件名解析，或要判断"实例级依赖"能不能零成本解锁时。→ `2026-09-30-exit-span-runtime-probe.md`
- **2026-09-30：依赖边聚合的三个取舍（只段内配对 / 双维上限 / 小样本池）** —— 何时读：有人想把异步出口纳进依赖图、想调边数上限、或问"为什么边不落库"时。→ `2026-09-30-edge-aggregator-tradeoffs.md`
- **2026-10-01：依赖面造数实跑探针（连接失败不产生边 / 组件名按实测值 / 插件 support 范围 / Kafka 三个坑）** —— 何时读：改 `DepsDemoController`、给依赖拓扑加组件、或给 `checks.sh` 写依赖面断言前。→ `2026-10-01-deps-demo-topology-probe.md`
- **2026-10-01：GitHub Actions 实跑经验（缓存 / matrix / 镜像分发 / paths 白名单 / 排错顺序）** —— 何时读：改 `.github/workflows/*.yml`、CI 跑得慢或莫名变红、想在动手前先判断某个写法能不能用时。→ `2026-10-01-github-actions-ci-pitfalls.md`
- **2026-10-02：本机 TCP 端口池耗尽的诊断 + 一个测量错误（逐条 curl 把自己算成被测方）** —— 何时读：页面白屏 / 控制台 `ERR_INVALID_ARGUMENT` / 怀疑某组件疯狂建连接 / 要用"打 N 次请求看连接涨多少"定位时。→ `2026-10-02-tcp-port-pool-exhaustion-and-a-measurement-trap.md`
