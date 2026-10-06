# Reference（外部实现参考 / wiki）

一手核实的**外部系统实现参考**，用于支撑本仓库的迭代方案。
约定：每条结论附来源（permalink / commit），并显式标注 **已核实 / 未核实**；未核实项不得当结论使用。

- **SkyWalking 链路存储实现速览（Agent 上报 → OAP 落库 → 查询组装 → 告警）** —— 何时读：设计本项目 H2 表结构 / DDL / TTL / 写入与查询模型时。→ `skywalking-oap-trace-storage.md`
  （基准：OAP `520d531`、Agent `47ff1e8`；已核实：schema 定义机制、`segment` 表结构、H2 DDL/类型映射、TTL、Agent 上报路径、写入管线、查询期组装、告警；未核实：批量落库 worker 细节等，见 §10）
- **SkyWalking refs 速览（TraceSegment 运行时对象 vs SegmentObject 上报对象）** —— 何时读：诊断本插件孤段过滤、或需搞清 segment 级 / span 级 refs 差异与"插件为何只见 span 级 refs"时。→ `skywalking-trace-refs.md`
  （基准：Java Agent `47ff1e8`、proto `apache/skywalking-data-collect-protocol` master；已核实：`TraceSegment.ref` 不序列化、`TraceSegmentRef`/`AbstractTracingSpan` 的 span 级 refs、proto `SegmentObject`/`SpanObject`/`SegmentReference` 字段；未核实：OAP 读取侧消费，见 §8）
- **Glowroot 链路存储与查询实现速览（本地 APM / 内存优先）** —— 何时读：设计"内存热层 + 本地持久化 + 内存优先查询"（与本插件同构的范式）时。→ `glowroot-trace-storage.md`
  （基准：`456b191`；已核实：采集阈值、写入路径与背压、H2 + `CappedDatabase` 存储模型、读取路径、partial trace；未核实：默认 Reaper 过期时长 / capped 默认大小等，见 §7）
- **Glowroot CappedDatabase：H2 行 + 封顶文件存 payload** —— 何时读：评估"H2 只存索引/header、大 payload 走环形封顶文件"或磁盘硬上限方案时。→ `glowroot-capped-database.md`
  （基准：`456b191`；已核实：文件格式、块读写、覆盖判定、压缩/fsync/resize/统计、`TraceDao` 指针语义、**一 JVM 多实例（1 trace + N rollup）**、`Existence` 三态；未核实：resize 触发入口，见 §6）
- **Glowroot Profiler：profile 采集与呈现实现速览** —— 何时读：评估"引入 Glowroot 的 profile / 火焰图思路"、或要搞清 Glowroot 的 profile 到底是"连续采样"还是"跟着慢 trace 走"时。→ `glowroot-profiling.md`
  （基准：`a48cd05c`（main，2026-10-04）；**已核实**：阈值门控 + 无采样百分比 + 间隔可配 + profile 挂单条 trace、线程 CPU/blocked/allocated 走 `ThreadMXBean` 差值、**发行包无 async-profiler**、火焰图用 d3-flame-graph、虚拟线程丢 profile 的复现参数；**未核实**：真正采样类/包、profile 存储 schema、"continuous profiling" 落点，见 §6。姊妹篇 `glowroot-trace-storage.md` / `glowroot-capped-database.md` 基准为 `456b191`，与本文不同）
- **SkyWalking 组件（品牌）图标在哪 / 能不能抄** —— 何时读：想把依赖拓扑页的组件图标换成官方那套 docker/go/postgres/nginx 品牌图标时。→ `skywalking-component-icons.md`
  （核实：本机 `sw-ui`（9.4.0）jar 内**零组件 svg**；`skywalking-booster-ui` 的 `src/assets/icons/` 是 96 个 Material 单色图标；`component-libraries.yml` 无 `icon` 字段。**结论：9.4.0 拿不到品牌图标**，见 §2）

**本项目落地（源码旁的实现笔记，非 docs/ 目录）**：

- **`CappedFileStorage` 全景笔记（Glowroot `CappedDatabase` 的最小形态移植）** —— 何时读：动环形封顶文件本身（文件布局 / 块格式 / 逻辑 vs 物理地址 / 读写流程 / 过期判定 / 与 H2 的联动）时。→ `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/CappedFileStorage-20260923.md`
  （含 §1.1 **与 Glowroot 的实例数量对照**：Glowroot = 1 trace 环 + N rollup 环，默认共 5 个；本项目 = 1 个。决策见 ADR-03；一手参照见上条 `glowroot-capped-database.md`）

**三家 metrics 实现对照（Phase 5 选型用，一手核实）**：均"在采集流上算、存预聚合"，差别在**算的进程**与**落库时机**。

- **CAT / Glowroot / SkyWalking：metrics 计算与存储实现对照** —— 何时读：设计 trace-based metrics（在哪算、何时落库、分位数方案）时；含 CAT 服务端"收到即算"、Glowroot 进程内 ingest + HdrHistogram、SkyWalking OAL + L1/L2 + 降采样。→ `sw-glowroot-cat-metrics-implementations.md`
  （基准：CAT `e815e74` / Glowroot `456b191` / SkyWalking `3af86a1`；已核实：统计进程与时机、聚合管线、落库/降采样、分位数算法、原始 vs 预聚合落点；未核实：各家排期细节与实测数据，见 §6）
