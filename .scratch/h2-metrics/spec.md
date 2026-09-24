# h2-metrics Phase 5：trace-based Metrics（H2 内存模式 + 入口段口径）

Status: ready-for-agent

## Problem Statement

插件已能把链路数据缓存在进程内（trace 内存热层）并试写 H2（影子路径），但**没有任何聚合指标**：单体应用的开发者无法回答"最近一小时哪些 endpoint 慢、错误率多少、P95/P99 多少、有没有突增"。

当前只有告警自身的**运行计数**（`TraceAlertMetrics`：dispatch/http/规则命中次数），不是业务指标；且按既定决策，normal 链路的明细不进入持久层（只留内存热层），**因此指标不能事后从 H2 明细补算**。

同时，`normal` 明明占了绝大多数流量，却是最容易被忽略的部分——只看 slow/error 明细，看不到整体口径（总请求数、整体错误率、整体耗时分布）。

演示应用里已有一个 trace 指标大屏的**原型**：头部提供 `1h / 6h / 24h / 7d` 四档时间范围（默认 24h），且未来要扩到 **30d**。当前它全是页面内模拟，需要尽快用**真实指标**跑起来（对标 Glowroot 的单应用仪表盘），并让四档范围都能出图。

## Solution

在链路数据流上**流式聚合** Trace 指标，按**多分辨率**落 H2 内存模式表，并通过宿主工具类对外只读暴露：

- 在采集流 `consume` 上**逐段**聚合，口径为"**入口段即一次事务**"（段内含 Entry span）；normal 天然计入，不需要单独分支。
- 聚合先在进程内累积为**分钟桶**；整分翻转时计算分位数，批量 `MERGE` 写入 H2 **内存模式**表 `trace_metrics_minute`（`jdbc:h2:mem`，随进程存活、不落盘）。除每个 endpoint 各一行外，**每个桶另写一行全局汇总**（保留键 `"*"`），使"全站分位趋势"精确可还原，而非由各 endpoint 分位相加。
- **多分辨率**：小时整点时把分钟行 rollup 进 `trace_metrics_hour`（同结构，含 `"*"` 行），使 `7d` 乃至未来的 `30d` 只返回百量级点而非上万点。分位数不可相加，小时分位由该小时的分钟行按请求数**加权平均**近似（计数 / 总耗时 / 最大耗时精确合并）；这是 v1 已知偏差，v2 换直方图可消除。
- **范围路由**：查询按跨度选分辨率——`≤ 24h` 走分钟表（图表再降采样到点数上限），`> 24h` 走小时表。头部四档 `1h / 6h / 24h / 7d` 由此全部可出图；`30d` 只要小时表保留期够即可覆盖（本期保留 30d）。
- 新增宿主工具类 `SWMetricsUtils`（与 `SWLogfileReporterUtils` / `SWTraceParityUtils` 同范式），暴露 `statisticMetrics()`（当前窗口快照）与 `queryMetrics(condition)`（条件查询），只返回 JDK 原生 `Map`。
- 演示应用新增读口，并新增**真实的 `metrics.html` 仪表盘**（沿用原型 A 方案：左侧 endpoint 导航 + KPI + 分位趋势 + endpoint 表 + 链路下钻，头部四档范围），数据来自真实指标而非模拟。

路线口径（相对既有 Phase 5 细化稿）：**本阶段只做内存模式**——不切 H2 file、不做 TTL 定时器、不做表重建/空间归还；统计挂钩点用**入口段**而非合并视图事件。指标定位为"尽力而为"：进程重启即丢，可接受（跨重启的历史留待 file 模式）。

## User Stories

1. 作为单体应用的开发者，我想按 endpoint 看到请求数、错误数、错误率、慢调用数，以便知道哪个接口在被调用、哪个在出错。
2. 作为单体应用的开发者，我想按 endpoint 看到 P50/P90/P95/P99 与平均/最大耗时，以便判断延迟分布而不只是平均值。
3. 作为单体应用的开发者，我想把 normal 流量也计入总量与耗时分布，以便看到整体口径（而不是只看 slow/error）。
4. 作为单体应用的开发者，我想按 1h / 6h / 24h / 7d 四档时间范围看趋势，以便从近到远定位突增与劣化。
5. 作为后续阶段作者，我想预留 30d 档位（小时分辨率足够覆盖），以便未来只调保留期就能上线更长范围。
6. 作为插件作者，我想统计挂在**入口段**（含 Entry span 的段）上，以便口径等于 Glowroot 事务 / SkyWalking service 指标，而不是依赖不完备的合并视图。
7. 作为插件作者，我想**不修改告警包**（不注入 evaluator、不改 `evaluate` 签名），以便告警行为零变化、规则命中计数不被双算。
8. 作为插件作者，我想聚合发生在**已离开业务线程**的批量消费线程上，以便统计不增加请求延迟。
9. 作为插件作者，我想聚合的异常全部就地捕获并计数，绝不向上抛，以便"监控只能是助力，不是阻碍"。
10. 作为插件作者，我想 **normal 不单独建模**（不建 normal 维度、不落 trace_level），以便模型最小。
11. 作为插件作者，我想**跨线程异步子段不被计入**（其根 span 非 Entry），以便一次事务只计一次、不因异步重复计数。
12. 作为插件作者，我想迟到的后续段落在保留窗口内被吸收、超出窗口则丢弃并计数，以便分钟桶不被无限期改写。
13. 作为插件作者，我想分位数采用**最近秩**算法、样本上限内用蓄水池采样，以便零依赖、精确且内存有界。
14. 作为插件作者，我想**小样本**只出 p50、尾分位置空，以便低流量 endpoint 不被失真的 P99 误导。
15. 作为插件作者，我想 endpoint 基数有上限、超出按请求数归并，以便分钟桶数量可控。
16. 作为插件作者，我想写入复用**已有的存储写锁**（不新增第二套队列/线程），以便少一套异步骨架。
17. 作为插件作者，我想小时 rollup 在整点随翻转顺带完成、且长范围查询点数可控，以便不引入聚合服务与大数据量传输。
18. 作为插件作者，我想有一个总开关（`metrics.enabled` 默认 true），以便随时关闭聚合。
19. 作为使用者，我想通过宿主工具类拿到只读的 JDK 原生 `Map` 统计快照，以便在我的 Console / Web / IDE 里消费，而不被 Agent 自定义类型污染 classpath。
20. 作为使用者，我想 `queryMetrics` 支持按 endpoint、时间桶范围与**分辨率**查询、结果集有上限并标记截断，以便查询不会失控。
21. 作为使用者，我想指标存储在 H2 **内存模式**表里（不产生磁盘文件），以便试运行零磁盘副作用、可随时回退。
22. 作为使用者，我想插件关闭上报时不再产生新指标（受运行时开关约束），以便与既有开关语义一致。
23. 作为验证者，我想在验证回路里断言"指标行存在、含全局行、口径自洽（error+slow ≤ request、p50 ≤ p95 ≤ p99、sample_count ≤ request_count）"，以便确认聚合正确。
24. 作为验证者，我想在演示应用里直接打开一个真实指标仪表盘，以便端到端确认"有流量 → 有指标 → 有大屏"。
25. 作为验证者，我想该仪表盘能下钻到具体链路明细（复用既有链路视图），以便从指标定位到现场。
26. 作为维护者，我想**已知偏差被显式文档化**（v1 慢判定用默认阈值；小时分位为加权平均近似），以便后续按需统一口径而不以为是 bug。
27. 作为维护者，我想 H2 不可用（初始化失败 / `h2.enabled=false`）时聚合器**继续内存累计**、`queryMetrics` 返回空、不报错，以便降级无损。
28. 作为维护者，我想关闭插件时最后一次冻结保留桶并落库，以便尽量少丢最后的窗口。
29. 作为后续阶段作者，我想本阶段的分桶与落库逻辑**留出换 file 模式的位置**（模式只改连接、schema 不变），以便后续平滑升级并让 7d/30d 跨重启保留。
30. 作为后续阶段作者，我想明确定义本阶段的取代关系（相对既有细化稿的挂钩点与告警包改造），以便文档不自相矛盾。
31. 作为演示应用的读者，我想大屏的 KPI、分位趋势、吞吐/错误图与 endpoint 表都可由真实指标契约直接填充（含**全局**分位，而非各 endpoint 分位相加），以便成图与原型 A 一致。

## Implementation Decisions

- **挂钩点（入口段 a1）**：客户端在批量消费 `consume` 里，对每条原始 segment（`SegmentObject`）调用聚合器的一个"喂入"方法。判定为入口段的条件：段内存在 `spanType = Entry` 或 `parentSpanId = -1` 的 span；否则该段视为子段/孤段，**不计入**。
- **判定口径（v1，锁定）**：
  - `endpoint` = 入口 span 的 `operationName`；
  - `duration` = 入口 span 的 `endTime - startTime`（退化时取段内最长 span）；
  - `error` = 段内任意 span `isError`；
  - `slow` = `duration >= default_slow_threshold_ms`（复用既有慢阈值默认值）；
  - **不复用 `TraceEvaluator`**：不注入 evaluator、不改其可见性/签名。Ant 差异化慢规则、`http.status_code` 错误、`error_ignore_rules` 白名单**本期不参与指标判定**，记为已知偏差。
- **分桶**：分钟桶 `time_bucket` = 入口 span `startTime / 60000`；小时桶 = `startTime / 3600000`（整点，UTC 纪元）；`service` = segment 的 service（单体下恒为 `Config.Agent.SERVICE_NAME`），保留列以对齐 OAP 形状。
- **去重**：a1 口径下**无按 traceId 去重**——每个入口段即一次事务；异步子段天然被排除。
- **聚合表（两张，同结构）**：`trace_metrics_minute` 与 `trace_metrics_hour`，列 `service / endpoint / time_bucket / request_count / error_count / slow_count / total_latency / max_latency / p50 / p90 / p95 / p99 / sample_count / create_at / update_at`；各自唯一索引 `(service, endpoint, time_bucket)`，另有 `time_bucket` 索引；写入用 H2 原生 `MERGE ... KEY(...)`（幂等，迟到段在窗口内重算覆盖）。每张表每个桶除各 endpoint 行外，另有一行**全局汇总**（`endpoint` = 保留常量 `"*"`），其分位数按该桶**全部**入口段样本计算。
- **多分辨率 rollup**：小时整点触发一次——把该小时的分钟行合并为小时行，`request/error/slow/total_latency` 求和、`max_latency` 取最大（精确）；`p50/p90/p95/p99` 按 `request_count` **加权平均**（近似，见已知偏差）；`sample_count` 求和。全局 `"*"` 行同样合并。仅对**完整结束**的小时做 rollup。
- **保留期（常量）**：分钟表 `MINUTE_RETENTION_BUCKETS = 2880`（48h）；小时表 `HOUR_RETENTION_BUCKETS = 720`（30d）。本期仅内存模式，超期行由翻转线程顺带清理（`DELETE ... WHERE time_bucket < ?`，截止值 Java 侧算好）。
- **内存结构**：`Map<timeBucket, Map<endpoint|"*", Accumulator>>`（每桶含 `"*"` 全局累加器）；Accumulator 持有计数、总/最大耗时与耗时样本列表（蓄水池上限 5000/桶/键）。分钟保留窗口 `BUCKET_RETENTION`（默认 3 个整分）仅用于**吸收迟到段并重算**，超出即停止改写该桶（已翻转的行留在 H2 内存表，供仪表盘历史查询）。小时 rollup 不需要保留原始样本（由分钟行近似合并）。
- **分位数**：翻转时对该桶内目标键（每个 endpoint 与全局 `"*"`）的样本按最近秩计算；`n ≥ 20` 出 p50/p90/p95/p99，`1 ≤ n < 20` 仅出 p50、尾分位置空；`sample_count` 记录实际参与样本数。
- **翻转与落库**：聚合器自持单守护定时线程，周期 30s（小于整分，保证每个桶在被逐出前至少翻转一次）；翻转线程直接调用存储的落库方法（复用现有写锁），不新增队列/线程；整点时顺带做上一小时的 rollup 与保留期清理；异常吞掉并计数。
- **范围路由与点数上限**：查询接口按 `[fromBucket, toBucket]` 的跨度选分辨率（`≤ 24h` 用分钟、否则用小时），并保证返回点数不超过 `MAX_QUERY_POINTS`（默认 2000；超出则按等距聚合降采样）。三层以上分辨率（日）本期不做，仅预留 seam。
- **存储接口增量**（不碰既有 `accept/snapshot/size`）：`H2TraceSegmentStorage` 新增"批量落指标行（指定分辨率）"与"条件查询指标行（指定分辨率）"方法；建表/MERGE/查询/清理 SQL 常量统一进 `H2SqlStatements`。
- **宿主工具类 `SWMetricsUtils`**：新增桩类 + 新拦截器 + 新 instrumentation 定义 + `.def` 追加一行；客户端新增委托方法（取指标快照 / 查询指标，经反射跨 ClassLoader 取数）。只返回 `Map/List/String/Long/Integer/Boolean`。
- **对外契约（锁定）**：
  - `statisticMetrics()` → `{ enabled, storageEnabled, flipIntervalMs, currentBucket, lateWindowBuckets, minuteRetentionBuckets, hourRetentionBuckets, buckets:[ {service, endpoint, timeBucket, bucketStart, requestCount, errorCount, slowCount, errorRate, slowRate, avgLatency, maxLatency, p50, p90, p95, p99, sampleCount} ], counters:{ lateDropped, bucketOverflow, sampleOverflow, noEntrySpan, persistErrors, rowsUpserted, rollupRows } }`。
  - `queryMetrics(condition)` → `{ resolution:"minute"|"hour", rows:[...], count, truncated }`；`condition` 支持 `endpoint`（含保留键 `"*"`=全局）/ `fromBucket` / `toBucket` / `resolution`（可选，缺省按范围自动选）/ `limit`（默认 200、上限 1000）。仪表盘的分位/吞吐序列与 endpoint 表即由此接口按范围取数（含全局行）。
- **与原型 A 的字段对齐（契约按 A 反推设计）**：
  - Endpoint 指标表（Endpoint / 请求 / 错误率 / P50 / P95 / P99 / 慢 / 趋势 / 分级）← 桶行的 `requestCount / errorRate / p50 / p95 / p99 / slowCount`；趋势 = 该 endpoint 的分钟序列（按需 `queryMetrics(endpoint=…)`）；分级 = 前端按 `errorRate/slowRate` 阈值派生。
  - KPI（近 1h 请求数 / 错误率、P95/P99、慢调用数、活跃 endpoint）← 近 1h 桶计数求和 + 全局行分位；活跃 endpoint = 范围内 endpoint 去重数。
  - 分位趋势图（P50/P90/P95/P99 分位带）与吞吐 / 错误图 ← 全局行序列：`≤ 24h` 用分钟行、`7d/30d` 用小时行（同一契约，靠 `resolution` 区分）。
  - 头部四档 `1h / 6h / 24h / 7d`：`1h/6h/24h` → 分钟分辨率，`7d`（及未来 `30d`）→ 小时分辨率；点数已由路由与上限控制。
  - 数据面卡片（H2 状态/行数、环形载荷占用、影子对账差异）← 既有对账读口；标签按内存模式改为"H2 内存"；环形载荷占用若未暴露则补统计字段或该行留空（实现时定，不新增查询面）。
  - 链路下钻复用既有链路读口（`trace-recent` + 链路视图）；指标契约只提供"指标 → endpoint / 时间"，不提供 traceId 列表。
- **演示应用**：新增 `/inner/sw/metrics` 读口；新增真实 `metrics.html`（原型 A 布局，轮询 `statisticMetrics()`/`queryMetrics()`，头部四档范围，下钻复用既有链路视图）；原型文件保留不动。
- **配置**：仅新增 `plugin.logfilereporter.metrics.enabled`（默认 true）；其余为类内常量（翻转间隔 30s、迟到窗口、分钟/小时保留期、样本上限、尾分位阈值、查询上限等）。
- **测试缝（seams）**：收敛为两个——(1) 聚合器以纯内存运行、落库依赖抽成可注入的最小接口（单测不碰 H2）；(2) 端到端走既有验证回路 + 新读口。不新增更多缝。
- **不改**：告警包、既有 `consume` 旧路径（只新增一次喂入调用）、4 个追加式 sender、`SWLogfileReporterUtils` / `SWTraceParityUtils` 契约、存储既有接口。

## Testing Decisions

- **好测试 = 只测外部行为**：聚合器测试只经"喂入若干 segment → 读快照/查询结果"断言，不碰内部字段与定时器（翻转可由测试显式触发或注入时钟）；存储测试只经"落指标行 → 条件查询"断言，不碰 SQL 文本。
- **单元（聚合器）**：多段同 trace 只计一次 a1 事务；子段/孤段（无 Entry）不计；normal 只贡献计数与耗时、不贡献 error/slow；error/slow 各自累加；分位数最近秩正确；**全局 `"*"` 行分位等于该桶全样本口径**；小样本只出 p50；样本上限触发采样；保留窗口内迟到段覆盖、窗口外丢弃计数；endpoint 基数上限归并；**整点 rollup 计数/总耗时/最大耗时精确、分位为加权平均**。
- **单元（存储增量）**：`MERGE` 幂等（同键重复写不重复计数、覆盖生效）；条件查询（endpoint / 含 `"*"` / 分辨率 / 桶范围 / limit 截断）；范围路由选分辨率正确。
- **端到端（既有验证回路）**：`validate-h2.ps1` 增指标断言（行存在、含全局行、口径自洽、四档范围可查、查询上限截断）；`validate.ps1` 全绿（对外零行为变化）。
- **先例**：`TraceEvaluatorTest`（夹具构造 + 口径断言）、`H2TraceSegmentStorageTest`（同包存储语义）。
- 备注：segment 夹具若构造困难，可抽包内可见的"segment → 判定输入"转换点；**不为测试改旧路径行为**。

## Out of Scope

- H2 file 模式、TTL 定时器、表重建 / 空间归还（既有方案 §5.4 补充内容）；跨进程重启保留历史（需 file 模式）。
- 第三层分辨率（日桶）与面向超长历史的归档/降采样。
- 合并视图事件挂钩、告警包改造、复用 `TraceEvaluator` 与"口径统一"（Ant 慢规则 / http 状态码 / 错误白名单参与指标判定）。
- 分位数 v2（对数分桶直方图）——本期只预留"换桶内实现"的 seam。
- 采样策略（高并发降压力）、Trace 快照 Dump。
- 与 SkyWalking 自带 JVM / meter 指标的合并。
- 原型 B / C 方案的生产化；不改动原型文件本身。
- 新增内嵌 HTTP Server 或 Agent 内可视化框架（仅演示应用提供页面）。
- 告警下沉与"合并视图下移进存储"。

## Further Notes

- **路线调整（取代关系）**：本 spec 取代既有 Phase 5 细化稿中"合并视图事件挂钩 + 告警包改造（evaluator 注入 / `evaluate(snapshot,bool)` / `entryStartTimeMs`）+ file/TTL"的部分；保留其分钟桶、endpoint 维度、分位数分层、facade 只出原生 `Map`、normal 计入。统计口径以"入口段"讨论稿为准；多分辨率 rollup 为本 spec 新增（对齐 SkyWalking OAP 的 L1/L2 降采样思路，参考 `docs/reference/sw-glowroot-cat-metrics-implementations.md`）。
- **已知偏差（文档化，不修）**：v1 慢判定用默认阈值（不含差异化规则）；error 不含 http 状态码与白名单；**小时分位为分钟分位的请求数加权平均（近似）**；跨分钟边界迟到段在保留窗口外被丢弃；进程重启丢全部指标（内存模式）；分位数为估计值（采样/小样本）；endpoint 表与左栏的"范围分位"由桶分位聚合近似（与原型 A 的模拟口径一致）。
- **降级语义**：H2 不可用时聚合仍在内存；`queryMetrics` 返回空；`statisticMetrics` 照常。
- **参考**：`docs/todos/h2化-统一方案.md`（§4 Phase 5、§5.3、§11）、`docs/todos/h2化-phase5-metrics实现细化.md`、`docs/todos/h2化-phase5-metrics讨论.md`、`docs/todos/h2化-phase5-metrics-口径与插入点讨论.md`、`docs/reference/sw-glowroot-cat-metrics-implementations.md`；原型 `agent/demo-app/src/main/resources/static/dashboards/metrics-prototype.html`（A 方案，头部 `1h/6h/24h/7d`）。
- **领域词表**：使用 `CONTEXT.md` 的 `Trace 指标`、`数据流`、`统计快照`、`宿主工具类`、`trace 内存热层`、`验证回路` 等术语。
