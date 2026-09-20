# Reference（外部实现参考 / wiki）

一手核实的**外部系统实现参考**，用于支撑本仓库的迭代方案。
约定：每条结论附来源（permalink / commit），并显式标注 **已核实 / 未核实**；未核实项不得当结论使用。

- **SkyWalking 链路存储实现速览（Agent 上报 → OAP 落库 → 查询组装 → 告警）** —— 何时读：设计本项目 H2 表结构 / DDL / TTL / 写入与查询模型时。→ `skywalking-oap-trace-storage.md`
  （基准：OAP `520d531`、Agent `47ff1e8`；已核实：schema 定义机制、`segment` 表结构、H2 DDL/类型映射、TTL、Agent 上报路径、写入管线、查询期组装、告警；未核实：批量落库 worker 细节等，见 §10）
- **Glowroot 链路存储与查询实现速览（本地 APM / 内存优先）** —— 何时读：设计"内存热层 + 本地持久化 + 内存优先查询"（与本插件同构的范式）时。→ `glowroot-trace-storage.md`
  （基准：`456b191`；已核实：采集阈值、写入路径与背压、H2 + `CappedDatabase` 存储模型、读取路径、partial trace；未核实：默认 Reaper 过期时长 / capped 默认大小等，见 §7）
- **Glowroot CappedDatabase：H2 行 + 封顶文件存 payload** —— 何时读：评估"H2 只存索引/header、大 payload 走环形封顶文件"或磁盘硬上限方案时。→ `glowroot-capped-database.md`
  （基准：`456b191`；已核实：文件格式、块读写、覆盖判定、压缩/fsync/resize/统计、`TraceDao` 指针语义；未核实：capped 默认大小 / 配置键与 resize 入口，见 §6）
