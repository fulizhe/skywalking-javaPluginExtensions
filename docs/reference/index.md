# Reference（外部实现参考 / wiki）

一手核实的**外部系统实现参考**，用于支撑本仓库的迭代方案。
约定：每条结论附来源（permalink / commit），并显式标注 **已核实 / 未核实**；未核实项不得当结论使用。

- **SkyWalking OAP 链路存储实现速览** —— 何时读：设计本项目 H2 表结构 / DDL / TTL 时。→ `skywalking-oap-trace-storage.md`
  （基准 commit `520d531`；已核实：schema 定义机制、`segment` 表结构、H2 DDL/类型映射、TTL；未核实：接收管线、查询组装、告警）
