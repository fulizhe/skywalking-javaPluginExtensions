# 架构决策记录（Architecture Decision Records）

本目录存放 active 模块（`agent/logfile-reporter-plugin`、`agent/override-httpclient-4.x-plugin`、`agent/override-hutool-http-5.x-plugin`）的 ADR。归档模块不在范围。

命名约定：`adr-NN-short-title.md`

本目录内容：

- **ADR-01：JDK 17 迁移** —— 何时读：碰到构建工具链 / 字节码基线 / 演示运行时（JDK 8 vs 17）约定时。→ `adr-01-jdk17-migration.md`
- **ADR-02：有界本地存储的两种形态** —— 何时读：理解 trace 流键式存储与 JVM/meter/log/profile 追加式环形队列的分叉时。→ `adr-02-two-shapes-for-bounded-local-storage.md`

规则：

1. ADR 在实现落地后再写。
2. 不改旧 ADR；决策变化时新写 ADR 并引用旧的。
3. Matt-skill 工具会自动读取本目录。
