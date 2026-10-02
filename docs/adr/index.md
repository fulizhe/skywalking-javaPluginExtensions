# 架构决策记录（Architecture Decision Records）

本目录存放 active 模块（`agent/logfile-reporter-plugin`、`agent/override-httpclient-4.x-plugin`、`agent/override-hutool-http-5.x-plugin`）的 ADR。归档模块不在范围。

命名约定：`adr-NN-short-title.md`

本目录内容：

- **ADR-01：JDK 17 迁移** —— 何时读：碰到构建工具链 / 字节码基线 / 演示运行时（JDK 8 vs 17）约定时。→ `adr-01-jdk17-migration.md`
- **ADR-02：有界本地存储的两种形态** —— 何时读：理解 trace 流键式存储与 JVM/meter/log/profile 追加式环形队列的分叉时。→ `adr-02-two-shapes-for-bounded-local-storage.md`
- **ADR-03：trace 明细载荷拆为 H2 header + 环形封顶文件** —— 何时读：动 trace 持久层存储形态（payload 落盘、磁盘上限、H2 膨胀）时。→ `adr-03-capped-file-payload-for-trace-details.md`
- **ADR-04：H2 内存模式作为全量 trace 持久层（含 normal），KeyedLocalStore 降为参照** —— 何时读：动"H2 收不收窄 / 存多少 trace / 内存热层与新持久层关系 / 双源核对"时。→ `adr-04-h2-mem-as-full-trace-persistence-tier.md`
- **ADR-05：告警 webhook 改用自带池化 HTTP 客户端** —— 何时读：动告警 webhook 投递、`HttpTraceAnomalyListener`、插件 shade 依赖/重定位、或排查"高频错误下本机端口耗尽 / 仪表盘白屏"时。→ `adr-05-alert-webhook-uses-pooled-http-client.md`

规则：

1. ADR 在实现落地后再写。
2. 不改旧 ADR；决策变化时新写 ADR 并引用旧的。
3. Matt-skill 工具会自动读取本目录。
