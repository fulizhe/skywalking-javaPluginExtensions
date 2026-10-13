# 架构决策记录（Architecture Decision Records）

本目录存放活跃模块（`docs/repo/module-status.md`）的 ADR。归档/冻结模块不在范围。

命名约定：`adr-NN-short-title.md`

本目录内容：

- **ADR-01：JDK 17 迁移** —— 何时读：碰到构建工具链 / 字节码基线 / 演示运行时（JDK 8 vs 17）约定时。→ `adr-01-jdk17-migration.md`
- **ADR-02：有界本地存储的两种形态** —— 何时读：理解 trace 流键式存储与 JVM/meter/log/profile 追加式环形队列的分叉时。→ `adr-02-two-shapes-for-bounded-local-storage.md`
- **ADR-03：trace 明细载荷拆为 H2 header + 环形封顶文件** —— 何时读：动 trace 持久层存储形态（payload 落盘、磁盘上限、H2 膨胀）时。→ `adr-03-capped-file-payload-for-trace-details.md`
- **ADR-04：H2 内存模式作为全量 trace 持久层（含 normal），KeyedLocalStore 降为参照** —— 何时读：动"H2 收不收窄 / 存多少 trace / 内存热层与新持久层关系 / 双源核对"时。→ `adr-04-h2-mem-as-full-trace-persistence-tier.md`
- **ADR-05：H2 内存模式定为 trace 持久层终态，并留 file 模式复入场券** —— 何时读：问"为什么不用 H2 file 模式 / `.mv.db` 会不会一直涨 / 磁盘占用到底是多少 / 什么时候该切 file"时；**mem 为终态 + 复入门槛与配方已写死**。→ `adr-05-h2-mem-as-terminal-with-file-mode-reentry.md`
- **ADR-06：激活 dynamic-debug-runtime-9.x-plugin，活跃模块由三个扩为四个** —— 何时读：问"这个模块为什么进了 CI / 发布资产""活跃模块到底几个""冻结线为什么少一个 jar""冻结线怎么同步"时。→ `adr-06-activate-dynamic-debug-runtime.md`（其中「发布资产按线而异」一条已被 ADR-07 修订）
- **ADR-07：冻结线也回移 dynamic-debug-runtime-9.x-plugin，两线资产同为四个** —— 何时读：问"冻结线为什么也有这个模块 / 也有四个 jar""ADR-06 的按线而异还算不算数""冻结线 CI 怎么变成四模块"时。→ `adr-07-frozen-line-backports-dynamic-debug-runtime.md`

规则：

1. ADR 在实现落地后再写。
2. 不改旧 ADR；决策变化时新写 ADR 并引用旧的。
3. Matt-skill 工具会自动读取本目录。
