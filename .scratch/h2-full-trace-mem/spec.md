# h2-full-trace-mem：全量 trace 入 H2 mem + 双源读口 + 慢查询页 + QPS + 删 trace_level + metrics 慢 A

Status: ready-for-agent

## Problem Statement

插件的 trace 明细现在**只有两条半成品通路**，谁也还不满足"任意链路查询"：

- **内存热层 `KeyedLocalStore` 只留最近 1000 条 traceId**（`MAX_LOG_SIZE`），FIFO 一过就查不到；
- **H2 影子存储上限 2000 行**（`SHADOW_MAX_ROWS` 默认 2000），且被当作"影子"，不是查询的真相；
- 慢/错明细**只能靠告警**看到，没有一个"选 endpoint + 耗时阈值 → 点开看慢链路"的入口；
- 大屏**没有 QPS**，看不到每个 endpoint 与全局的吞吐；
- metrics 的慢判定**只用默认阈值**，像"正常耗时 10s"这类端点会被误判为慢。

结果：单体应用开发者拿不到"尽可能全量、可查、可下钻"的本地链路历史，也拿不到准确的整体吞吐与慢调用视图。

## Solution

把 H2 内存模式的 `trace_segment` 正式作为**全量 trace 持久层**（normal + error/slow **都写**），放大上限，并补齐查询与指标口径：

- **全量入 H2 + 放大容量**：`SHADOW_MAX_ROWS` 提到 **100000**（实测堆 ≈ 80 MB），环形载荷文件维持 **128 MB**（可容纳 ~20 万段，`F ≥ N×S` 有余量）。写入路径不变（`consume` 仍只调一次 `accept`），normal 不再被任何开关过滤。
- **删除 `trace_level` 列**：错误用 `is_error` 查、慢用 `latency >= :阈值` 查；写入无需再做 trace 级判定（顺带规避"写入早于合并"的时点问题）。
- **双源读口**：`getTraceView(traceId)` 保持 **H2 为准**；**新增** `getTraceViewFromMemory(traceId)` 读内存热层；demo 查询页提供**双源对照**（H2 视图 vs 内存视图 + 一致性/差异高亮）。内存热层降级为"过渡期交叉核对参照"。
- **慢查询页**：宿主工具类新增"按 `endpoint` + `latency` 阈值查慢段"的方法；demo 提供专门的慢查询页面（选 endpoint、填阈值 → 点开看链路）。
- **QPS 一等字段**：metrics 桶行对外契约新增 `qps`（= 请求数 / **该行桶跨度秒**，不写死 60）；大屏加 endpoint QPS 列与全局 QPS 卡片。
- **metrics 慢判定复用 `slow_rules`**（只读阈值解析器）：按 endpoint 取差异化阈值，消除"正常 10s 端点"误判；**不改告警包行为、不触发规则命中计数、不并入 `TraceEvaluator`**。

对使用者：不改任何既有对外输出（`statisticStatus` / 开关 / 告警行为 / 既有工具类方法契约），升级无感；H2 不可用时全部新读口降级返回空、不报错。

## User Stories

1. 作为单体应用开发者，我想 H2 内存模式成为链路查询的**唯一真相来源**，以便不受"内存只留 1000 条"的限制查到更久以前的链路。
2. 作为单体应用开发者，我想 **normal 链路也进 H2**（不只是 error/slow），以便任意请求都能被追溯，而不是只有异常请求。
3. 作为单体应用开发者，我想可查链路数量提升到约 **10 万条**（`shadow_max_rows=100000`），以便从"仅前 1000 条"获得明显进步。
4. 作为维护者，我想环形载荷文件维持 **128 MB 硬封顶**且不小于行上限所需的段数，以便"每条还活着的 header 都能查到 payload"。
5. 作为单体应用开发者，我想按 `traceId` 从 H2 取回整条链路（`getTraceView`），以便查看完整调用树。
6. 作为单体应用开发者，我想**同时**能从内存热层按 `traceId` 取回链路（`getTraceViewFromMemory`），以便与 H2 结果对照。
7. 作为验证者，我想 demo 查询页并排展示 **H2 视图 vs 内存视图**，并高亮一致/差异，以便确认过渡期双源口径一致、可安心以 H2 为准。
8. 作为单体应用开发者，我想按 **`endpoint` + 耗时阈值**查询慢段，以便定位"哪个接口的哪些请求慢"。
9. 作为单体应用开发者，我想有一个**专门的慢查询页面**（填 endpoint、填阈值 → 列表 → 点开看链路），以便不写 SQL 就能做慢查询。
9b. 作为单体应用开发者，我想慢查询结果**直接是键值对行集合**（`latency` / `startTime` 等数值字段），以便前端无需额外转换即可图表化展示。
10. 作为单体应用开发者，我想错误查询直接走 **`is_error`**（不再依赖级别标签列），以便错误视图准确且实现简单。
11. 作为单体应用开发者，我想**删除 `trace_level` 列**（其信息可由 `is_error` 与 `latency` 表达），以便 schema 更简单、写入无需级别判定。
12. 作为单体应用开发者，我想 metrics 的**桶行与聚合行都带 `qps`**，以便直接看到吞吐而不用自己除。
13. 作为单体应用开发者，我想 QPS 的**分母按该行桶跨度**（分钟行 60s、小时行 3600s、跨范围聚合行用范围跨度），以便短暂/长跨查询都不被错误低估。
14. 作为单体应用开发者，我想大屏**加 endpoint QPS 列与全局 QPS 卡片**，以便一眼看到吞吐格局。
15. 作为单体应用开发者，我想 metrics 的慢判定**复用告警同一套 `slow_rules`**，以便"正常耗时 10s"这类端点用差异化阈值判定，不再被默认阈值误判。
16. 作为维护者，我想 metrics 的慢阈值解析**只读**（不触发 `recordRuleHit`、不并入 `TraceEvaluator`），以便告警行为零变化、规则命中计数不被双算。
17. 作为维护者，我想三处（告警、H2 明细慢查询、metrics 慢判定）对"慢"的口径**来源一致**（同一套 `slow_rules` + 默认阈值），以便不出现两套慢定义。
18. 作为单体应用开发者，我想**孤段（无 Entry 且无 ref）继续被过滤**，以便连接池/后台线程的伪段不白占 H2 行与 traceId 名额。
19. 作为维护者，我想过滤计数与样本继续在 `/inner/sw/trace-parity` 可见（`orphanSegments` / `lastOrphan` / `orphanKinds`），以便回溯来源。
20. 作为使用者，我想本次改动**零对外行为变化**（`statisticStatus` 的 `data[traceId].logs`、开关、告警行为、既有工具类方法签名与返回不变），以便升级无感。
21. 作为后续阶段作者，我想在 H2 **内存模式**下完成这一切（不落盘、不切 file、不引入 TTL 定时器），以便零磁盘副作用、可随时回退。
22. 作为后续阶段作者，我想保留 **`KeyedLocalStore` 与 `MAX_LOG_SIZE=1000`** 作为近期参照，以便过渡期双源核对，而非立刻退役。
23. 作为验证者，我想 `validate.ps1`（对外零行为变化）与 `validate-h2.ps1`（新增断言）全绿，以便确认改动可信。
24. 作为维护者，我想新增的读口**异常全部就地捕获、计数、绝不外抛**，以便"监控只能是助力，不是阻碍"。
25. 作为维护者，我想存储层仍只暴露 JDK 原生类型给宿主工具类（跨 ClassLoader 安全），以便不向 Business 暴露 Agent 自定义类型。
26. 作为维护者，我想 schema 变更在内存模式下**干净重建**（无残留旧列），以便删列即时生效、不污染测试。
27. 作为验证者，我想有单元测试覆盖"全量入 H2（含 normal）""按 endpoint+latency 查慢""QPS 分母正确""慢规则阈值被 metrics 采用且不记规则命中"，以便回归有据。

## Implementation Decisions

### 容量与全量写入（Phase 2）

- `SHADOW_MAX_ROWS` 默认值由 **2000 → 100000**（依据实测 `R≈804 B/行`，堆 ≈ 80 MB；见 `docs/notes/2026-09-27-h2-mem-capacity-estimate.md` §7.1）。
- `PAYLOAD_CAPPED_SIZE_MB` 维持 **128**（实测 `S≈662 B/段`，128 MiB 可容纳 ~20 万段 > N）。
- **全量写入**：不引入"只收 hits"的开关（不落地 `persist_only_hits` 概念）；normal 与 error/slow 一视同仁，唯一写入口仍为 `consume → accept`。
- **孤段过滤保持不变**：`isOrphanSegment`（无 Entry 且无 ref）判定的段照旧被过滤、不入 `KeyedLocalStore`/H2；计数与样本维持现状。

### Schema：删除 `trace_level`

- `trace_segment` 建表语句**移除 `trace_level VARCHAR(16)`**；`INSERT` 语句由 12 字段降为 11 字段；写入路径移除置空该列的代码。
- 错误用既有 `is_error` 列；慢用 `latency >= :阈值`（参数化，无需写时打标）。
- **内存模式 schema 干净重建**：由于 mem URL 固定且 `DB_CLOSE_DELAY=-1`（同 JVM 共享库），初始化时先 `DROP TABLE IF EXISTS` 相关表再 `CREATE`，保证删列生效、无残留旧 schema；测试对同一 mem 库的隔离依赖 `clear()`（沿用现状）。

### 存储层新增查询（storage seam）

- `H2TraceSegmentStorage` 新增**按 endpoint + 阈值查慢段**的方法 `querySlowTraces(endpoint, minLatencyMs, limit)`，**返回 `List<Map<String, Object>>`（JDK 原生键值对行集合，与 `recentTraces` 完全同构）**——不引入 `SlowRow` 之类 DTO，storage → 拦截器（反射跨 ClassLoader）→ 门面 → demo/图表全程零自定义类型，前端可直接消费/图表化：
  - SQL：`SELECT trace_id, segment_id, service, endpoint, start_time, latency, is_error, payload_id FROM trace_segment WHERE endpoint = ? AND latency >= ? ORDER BY latency DESC LIMIT ?`。
  - 每行键：`traceId / traceSegmentId / service / endpoint / startTime / latency / isError / hasPayload / payloadExpired`（其中 `startTime`、`latency` 为数值，可直接喂给图表）。
  - 行数被 `limit` 截断（`rows.size() == limit` 即表示可能还有更多）；如需精确 `truncated` 标记，由门面/页面按 `size == limit` 自行判定，不新增返回结构。
  - **本期只做行列表**（先解决有无）：不做服务端聚合返回（如"每 endpoint 慢次数 / 慢耗时分布"）；页面若需图表，由前端对同一行集合衍生。
  - `latency` 为**段级**（段内 `maxEnd-minStart`，`computeSegmentMetrics` 既有口径）；按 endpoint 过滤时通常即入口段，故一行约等于一个链路；**trace 级 `max(latency)` 折叠**列为可选增强，v1 不做。
  - `latency` 索引**按需**在实跑确认全表扫为瓶颈后再加（本期先不加）。
- `queryTrace` / `recentTraces` / `snapshot` / `size` 既有契约不变；`queryTrace` 保持"命中 / 不存在 / payload 过期"三态。

### 双源读口（facade seam）

- `LogFileTraceSegmentServiceClient` 新增 `getTraceViewFromMemory(traceId)`：读取 `traceStore`（`KeyedLocalStore`）的**只读快照**，返回与 `getTraceView` **同契约**（`{logs:[...]}`）；`traceStore` 缺失时返回空 Map。
- **宿主工具类沿用 `SWTraceParityUtils`**（不新建门面类）：新增静态方法
  - `getTraceViewFromMemory(String traceId)` —— 内存侧链路视图（`Map`，同 `queryTrace` 契约）；
  - `querySlowTraces(String endpoint, int minLatencyMs, int limit)` —— 慢段列表（`List<Map<String,Object>>`，键值对行集合）。
- 拦截器 `TraceParityStatusExposeInterceptor` 扩展分发：按方法名反射调用客户端 `getTraceViewFromMemory` / `querySlowTraces`；`TraceParityUtilsInstrumentation` 的方法匹配器加入新方法；返回**只含 JDK 原生类型**。
- **demo-app**：
  - 新增读口 `/inner/sw/trace-memory?traceId=...` 与 `/inner/sw/trace-slow?endpoint=...&minLatencyMs=...&limit=...`（不改既有端点）。
  - `trace-query.html` 增加**双源对照**：同屏展示 H2 视图与内存视图，标注 `logs` 条数是否一致、差异高亮。
  - 新增 `trace-slow.html`（**简单优先**）：endpoint 输入 + 阈值输入 + limit → **表格列表**（`startTime / latency / isError / payload` 状态）→ 点击行打开 `trace-view.html` 下钻。图表为**前端可选**：直接对同一行集合作图（`latency × startTime` 散点，或前端按 endpoint 计数柱状），**不新增服务端聚合返回**。
- 定位：这是 Phase 4"内存优先、miss 再 H2"读门面的进一步种子；本期**不切换**主读路径（`getTraceView` 仍以 H2 为准）。

### QPS 一等字段（metrics）

- 对外桶行 Map 新增 `qps`。**分母 = 该行的桶跨度秒**，由**已知分辨率的门面/客户端**写入，避免在 `MetricsRow` 内写死 60：
  - 分钟行：`qps = requestCount / 60`；
  - 小时行：`qps = requestCount / 3600`；
  - `aggregate=true` 聚合行：`qps = requestCount / ((toBucket - fromBucket + 1) * 60)`（小时同理）；即用**查询范围跨度**而非单桶。
- `MetricsRow` 可新增一个 `qps(long bucketSpanSeconds)` 取值方法（纯计算），但 `toMap()` 默认仍不含 qps；qps 由门面在分辨率已知处注入。`statisticMetrics()` 的当前窗口桶（分钟）同样注入 `qps=requestCount/60`。
- demo `metrics.html`：endpoint 表加 **QPS 列**，KPI 区加**全局 QPS 卡片**（由全局 `"*"` 行 / 近窗口计数与跨度算出）。

### metrics 慢判定复用 `slow_rules`（A 方案）

- 引入一个**只读阈值解析 seam**（公共接口，供 `metrics` 包使用），方法形如 `long thresholdMs(String operation, String url, long defaultThresholdMs)`。
- **实现**：在 `alert` 包提供只读包装器，内部调用既有 `RulesEngine.matchSlow(operation, url, default)`（纯函数）。**不调用 `recordRuleHit`、不并入 `TraceEvaluator`、不改告警包任何行为**。
- `TraceMetricsAggregator` 把构造入参从单一 `defaultSlowThresholdMs` 扩展为"**默认阈值 + 只读解析器**"（解析器可为 null → 退化为只用默认阈值）：`onSegment` 在算 `slow` 前先解析该入口的阈值。
- **url 取值**：从入口 span 的 url tag 取（复用既有 `TraceSpanUtils` 的只读取值；若可见性受限，将其暴露为包内/公共只读方法）。无 url 时 `url: ` 规则不命中、`operation:` 规则照常生效。
- **wiring**：客户端在启用 metrics 时，用与告警**相同的配置串**构建一份**独立的**只读解析器（不共享 evaluator 实例、不改告警包）；`slow_rules` 为空时解析器退化为默认阈值（与现状等价）。
- 一致性：告警（`TraceEvaluator`）、H2 明细慢查询（`latency >= :阈值`）、metrics（本解析器）三者对"慢"的阈值来源统一为 `slow_rules` + `DEFAULT_SLOW_THRESHOLD_MS`。H2 明细慢查询的阈值由**调用方按 endpoint 传入**（页面用同源规则预填/可改）。
- 配置：**不新增配置项**（复用 `Alert.SLOW_RULES` / `Alert.DEFAULT_SLOW_THRESHOLD_MS`）。

### 不改动

`TraceSegmentStorage` 既有接口方法（`accept/snapshot/size`）、`SegmentLogConverter` 转换逻辑、`TraceParityComparator`、告警判定与分发、其余 4 个追加式 sender、`KeyedLocalStore` 实现（含 `MAX_LOG_SIZE=1000`）、既有宿主工具类方法签名与返回、既有对外读口契约。

## Testing Decisions

**好测试 = 只测外部行为**：存储测试只经 "`accept` → `snapshot`/`size`/查询方法" 断言，不碰 SQL 文本或内部字段；聚合器测试只经"喂入 segment → 读快照/查询"断言，不碰定时器（翻转由测试显式触发）；门面契约由 E2E 验证回路覆盖。三处缝均为既有缝的延续，不新开缝。

- **storage 缝**（`H2TraceSegmentStorageTest` 扩展）：
  - 全量入 H2：normal（无 error/slow）与 error 段都出现在 `snapshot`，`size` 为 distinct trace 数；
  - schema 无 `trace_level`：写入与读取不依赖该列；
  - `querySlowTraces(endpoint, threshold, limit)`：只返回该 endpoint 且 `latency >=` 阈值的段，按 latency 降序，`payloadExpired`/`hasPayload` 正确；
  - `queryTrace` 三态与 `recentTraces` 行为保持；
  - 环形文件 ≥128MB 定长、写满覆盖不崩（沿用既有 `CappedFileStorageTest`）。
- **aggregator 缝**（`TraceMetricsAggregatorTest` 扩展）：
  - 注入**只读解析器**后，endpoint 命中 `slow_rules`（如 `/status/400=8000`）时：`duration=10000` 判为 slow、`duration=5000` 判为 normal（对照默认阈值 3000 会误判为 slow）；
  - 解析器**不产生规则命中副作用**（规则命中计数不变）；
  - `qps` 计算：分钟行 = count/60、小时行 = count/3600、聚合行 = count/范围跨度秒。
- **facade / E2E 缝**（`validate-h2.ps1` 扩展；`validate.ps1` 保持对外零行为变化）：
  - 全量：造数后 `/inner/sw/trace-recent` 与 H2 `traceId` 涵盖 normal 请求；
  - 双源：对已知 `traceId`，`/inner/sw/trace-query` 与 `/inner/sw/trace-memory` 的 `logs` 条数/关键字段一致；
  - 慢查询：`/inner/sw/trace-slow?endpoint=...&minLatencyMs=...` 返回命中段，点开链路 logs 一致；
  - QPS：`/inner/sw/metrics` 与 `/inner/sw/metrics/query` 的桶行含 `qps` 且 ≈ requestCount/桶跨度秒；
  - metrics 慢：配置 `slow_rules` 后，大屏该 endpoint 的 slow 计数反映差异化阈值；
  - 计数健康：`writeQueueDropped=0`、`h2ErrorCount=0`、`orphanSegments` 照常。
- **先例**：`H2TraceSegmentStorageTest`、`H2TraceSegmentStorageMetricsTest`、`TraceMetricsAggregatorTest`、`TraceEvaluatorTest`、`CappedFileStorageTest`、ADR-01 验证回路。

## Out of Scope

- **H2 切 `file` 模式、TTL/保留策略、跨进程重启持久化**（Phase 3）——本期维持 mem。
- **`KeyedLocalStore` 的 `MAX_LOG_SIZE` 行为调整**（维持 1000，仅作双源参照）。
- 退役/合并内存热层为 H2（真统一，需新写 ADR）。
- Phase 4 的其余查询面（时间范围 / service / 多条件过滤）与"内存优先、miss 再 H2"主读路径切换。
- trace 级 `max(latency)` 折叠、`latency` 索引（按实跑再定）。
- 慢查询的**服务端聚合/统计返回**（每 endpoint 慢次数、慢耗时分布等）——v1 仅出**行列表**，图表由前端对行集合衍生。
- 告警下沉设计与"合并视图下移进存储"。
- 第三层分辨率（日桶）、分位数 v2（直方图）、采样策略、Trace 快照 Dump。
- 新增内嵌 HTTP Server 或 Agent 内可视化框架（仅 demo-app 提供页面）。
- 变更既有对外输出、宿主工具类既有方法签名、告警行为、其它数据流。

## Further Notes

- **决策依据**：`docs/adr/adr-04-h2-mem-as-full-trace-persistence-tier.md`；容量与实测：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`（§7.1 实测 S/R/K、§7.2 QPS）；路线回写：`docs/todos/h2化-统一方案.md`（§4 Phase 2、§10 Phase 4 增强 / Phase 5 增强）。
- **后续增强（与 todos 双向关联）**：慢查询的**服务端聚合/分布/段级归因**（每 endpoint 慢次数/慢率/慢耗时分布直方图/时间趋势；按**段级** endpoint 定位"慢在下游哪一段"）已记入 `docs/todos/h2化-统一方案.md` §10「Phase 4 增强」。本 spec v1 仅**行列表**（见 Out of Scope）——行列表被 `limit` 截断只看 top-N（选择偏差），聚合才能看全量分布，且段级聚合可做 metrics 入口 a1 口径做不到的**依赖归因**；先有真实慢数据再评估。
- **接受的回退预案**：若放量后堆或 IO 表现超出预期，`shadow_max_rows` 为配置项，可即时回调；删列后如需回退，mem 模式重启即重建，无迁移负担。
- **窗口代价（承认并接受）**：`N=100000`、堆 ≈ 80 MB 时，mem 窗口 @10 QPS ≈ 2.8h、@100 QPS ≈ 17min；重启即失（跨重启留待 file）。
- **验证回路**：`pwsh ./scripts/validate.ps1`（对外零行为变化）与 `pwsh ./scripts/validate-h2.ps1`（H2/指标专项）。demo 读口：`/inner/sw/trace-parity`、`/inner/sw/trace-query`、`/inner/sw/trace-memory`、`/inner/sw/trace-recent`、`/inner/sw/trace-slow`、`/inner/sw/metrics`、`/inner/sw/metrics/query`。
- **环境**：agent `D:\apps\apache-skywalking-java-agent-9.4.0`；JDK8 `D:\apps\java\jdk1.8.0_92-64`；端口 9600 为用户压测占用，验证用其它端口。
- **术语**（`CONTEXT.md`）：trace 持久层 / trace 内存热层 / 链路分级 / Trace 指标 / 宿主工具类 / 有界数据存储 / 验证回路。
