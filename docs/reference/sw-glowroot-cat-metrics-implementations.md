# CAT / Glowroot / SkyWalking：metrics 计算与存储实现对照（一手核实）

> **用途**：三家的 **metrics/指标**如何算、何时算、存哪里、如何取分位数——供本项目 Phase 5（trace-based metrics）选型对照。
> **核实基准**：CAT `dianping/cat` @ `e815e74`；Glowroot `glowroot/glowroot` @ `456b191`；SkyWalking `apache/skywalking` @ `3af86a1`（agent 侧 `skywalking-java`）。均逐文件打开源码确认。
> **证据类型**：`【源码】`；三家均无【实测】（本仓库未运行它们）。
> **姊妹篇**：trace 存储实现见 `skywalking-oap-trace-storage.md`、`glowroot-trace-storage.md`、`glowroot-capped-database.md`。
> **已核实**：各家的统计进程与时机、聚合管线、落库/降采样、分位数算法、原始 vs 预聚合的落点。
> **未核实**（见 §6）：三家各自的排期细节与实测指标（明确列出）。

---

## 0. 三栏对照（先看这张）

| | **在哪算** | **时机 / 窗口** | **原始 vs 预聚合** | **分位数** |
|---|---|---|---|---|
| **CAT** | **CAT 服务端**（独立 WAR 进程） | **收到 message tree 即算**：逐条进内存、按 `domain` 分派到 analyzer，实时累加到「分钟段 / 小时报」；日/周/月报由定时任务从小时候聚合 | 原始树 → 本地文件/HDFS；**聚合报表 → MySQL** | ✅ 直方图分桶**近似**（p50/90/95/99/99.9/99.99） |
| **Glowroot** | 被监控 **JVM 进程内**（embedded） | **事务完成即算**（ingest）：内存聚合，按 interval flush；`store()` 内**同步**算 1/5/30min/4hr rollup | **预聚合 → H2**（`aggregate_*_rollup_N`）；payload → capped 文件 | ✅ **HdrHistogram**（`LazyHistogram`，**无损可合并**） |
| **SkyWalking** | **OAP 服务端**（agent 只发原始值） | **非逐条实时**：L1 流式合并（~500ms flush）→ L2 定时（~25s）落 **1 分钟滚动桶** → 降采样 hour/day | **预聚合 metrics 表**（minute/hour/day）；trace 单独存 | ✅ `PercentileMetrics2` 分桶（p50/75/90/95/99）；另有 heatmap/histogram |

## 1. 共同点与关键差异

**共同点**：三家都在**采集流上算、存预聚合**，**没有一家**是"事后扫明细表重算"。

**差异**（决定取舍的三点）：
1. **算的进程**：Glowroot 在被监控 **JVM 内**；CAT / SkyWalking 在**独立服务端**（CAT 服务端 / OAP）。
2. **落库时机**：CAT **收到即入分钟报**、小时段落 MySQL；Glowroot **事务完成即 flush** H2；SkyWalking **定时批量**（L1 合并 + L2 定时落库）。
3. **分位数方案**：Glowroot 的 HdrHistogram 是唯一**无损可合并**；CAT / SkyWalking 都是**分桶近似**（SkyWalking 另有 heatmap）。

> 与 scrape 型（如 Prometheus）不同：三家都是 **push / ingest-time** 聚合，而非监控端定期拉取当前值。

---

## 2. CAT（大众点评）

### 2.1 在哪算 / 何时算

```
客户端 SDK（无聚合）
  └─ TCP:2280 ─► TcpSocketReceiver
        └─ DefaultMessageHandler.handle(tree)
              └─ RealtimeConsumer.consume(tree)
                    ├─ PeriodManager.findPeriod(timestamp)      // 按小时分段（duration=1h, EXTRATIME=3min）
                    └─ Period.distribute(tree)                  // 按 domain.hashCode()%N 分派到 PeriodTask
                          └─ PeriodTask.enqueue(tree) ─► 各 analyzer 逐条累加
```

- **统计在 CAT 服务端（独立进程）收到消息时发生**；客户端 SDK 只序列化/发送树，**零聚合**。
- analyzer（`components.xml` 注册）：`transaction` / `event` / `problem` / `heartbeat` / `top` / `dump` / `state` / `cross` / `matrix` / `dependency` / `storage` / `business`。
- **无独立 `MetricReportBuilder`**：CAT 语境"自定义业务指标"走 `BusinessAnalyzer` → `businessReport`。

### 2.2 周期模型

- **分钟段 / 小时报**：`DefaultReportManager` 以 `Map<hourStartTime, Map<domain, Report>>` 驻内存，**消息到达即更新**。
- **落库**：小时段结束写 MySQL（`hourlyreport` + `hourly_report_content`）。
- **日/周/月**：`TaskConsumer` 轮询 MySQL `task` 表 → `ReportFacade.builderReport` → `TaskBuilder`（如 `TransactionReportBuilder.buildDailyTask` 查 24 个小时报聚合成日报）。

### 2.3 指标与分位数（近似）

- 计数 `totalCount`/`failCount`；均值 `sum/count`；标准差由 `sum2`。
- **分位数＝直方图分桶近似**：先 `computeDuration()` 分桶（<20 原值；<200 取 5ms；<500 取 20ms；<2000 取 50ms；<20000 取 500ms；更大按 2 的幂），再 `computeLineValue()` 对桶降序扫描取 p50/90/95/99/99.9/99.99。

### 2.4 落点

| 数据 | 落点 |
|---|---|
| 原始 message tree | `DumpAnalyzer` → 本地文件 / HDFS |
| 小时报 | MySQL `hourlyreport` + `hourly_report_content` |
| 日/周/月报 | MySQL `dailyreport` / `weeklyreport` / `monthreport`（+ content 表） |
| 自定义业务指标 | MySQL `businessReport` |

聚合在 consumer 节点内存；同节点按 `domain.hashCode()%N` 分片；**CAT 服务端与业务应用是两个进程**。

---

## 3. Glowroot

### 3.1 计算链路（事务完成即算）

```
Transaction.end()
  └─ TransactionProcessor.processOnCompletion(transaction)
        └─ AggregateIntervalCollector.add(transaction)          // 按 transactionType 归组
              └─ AggregateCollector.mergeDataFrom(transaction)  // 增量合并：count/error/totalDuration、线程统计、durationNanosHistogram.add()
  └─（interval 到）AggregateIntervalCollector.flush(collector)
        └─ EmbeddedCollector.collectAggregates(reader)
              └─ AggregateDao.store(reader)                     // 写 H2 rollup_0 + 同步 rollup(1→5→30min→4hr)
```

- **完全进程内**（embedded），**事务完成即算**，**不依赖外部 metrics 后端**（唯一外部 HTTP 是可选 healthchecks.io ping）。

### 3.2 存储与 rollup

- 表（每档 rollup 一张，N=0..3）：`aggregate_tt_rollup_N`（按 type）、`aggregate_tn_rollup_N`（按 name）；gauge 为 `gauge_value_rollup_N`。
- 关键列：`transaction_type` / `capture_time` / `total_duration_nanos` / `transaction_count` / `error_count` / `main_thread_*`（cpu/blocked/waited/allocated）/ `duration_nanos_histogram`（protobuf）/ `queries_capped_id` / `service_calls_capped_id`。
- rollup 层级（默认）：1min / 5min / 30min / 4hr；视图阈值 15min / 1h / 8h / 3d。

### 3.3 分位数：HdrHistogram（无损可合并）

- `LazyHistogram`：≤ `MAX_VALUES=1024` 存**有序 `long[]`**（精确保留原值）；超过转 `org.HdrHistogram.Histogram`（5 位有效数字、微秒精度）。
- 序列化为 protobuf `Aggregate.Histogram`（小样本 `ordered_raw_value`、大样本 `encoded_bytes`），落 `duration_nanos_histogram`（`VARBINARY`）；rollup 合并用 `histogram.add(...)`。

### 3.4 Gauges（JVM/系统指标）

- `GaugeCollector`（`ScheduledRunnable`，按 `gaugeCollectionIntervalMillis`）读 JMX MBean：counter 型算**每秒速率**、gauge 型存**原值**（附 `weight`）。
- `GaugeValueDao.store()` → `gauge_value_rollup_N`（`gauge_id`/`capture_time`/`value`(double)/`weight`）；rollup 用 `sum(value*weight)/sum(weight)` **加权平均**。

---

## 4. SkyWalking

### 4.1 agent vs OAP

- **agent 只产生原始值**：Meter toolkit（Counter/Gauge/Histogram/Timer，进程内累加器）经 gRPC `MeterSender` 送 OAP；**不算** avg/cpm/分位数/apdex。
- **OAP 做全部聚合**：OAL 生成类与 `MeterSystem`（MAL）都产出 `Metrics` 子类，进入同一 `MetricsStreamProcessor`。

### 4.2 流式管线（L1 → L2 → 降采样 → 落库）

```
SourceDispatcher.dispatch(src)
  └─ MetricsStreamProcessor.in(metrics)                    // 热路径
       └─ MetricsAggregateWorker (L1)                      内存合并；BatchQueue "METRICS_L1_AGGREGATION"，~500ms flush
              └─ MetricsRemoteWorker                        本地或 gRPC 路由到归属 OAP
                    └─ MetricsPersistentMinWorker (L2)      分钟聚合；BatchQueue "METRICS_L2_PERSISTENCE"
                          ├─ MetricsTransWorker ──► MetricsPersistentWorker (hour)
                          └─                     ──► MetricsPersistentWorker (day)
                                          ▲
                          PersistenceTimer（默认 25s）批量 flush
```

- **聚合窗口 = 整分钟桶**（`TimeBucket.getMinuteTimeBucket`）；`DownSampling` = `None/Second/Minute/Hour/Day`；**非滑动窗口**。
- **L1 flush** `l1FlushPeriod` 默认 **500ms**；**落库** `PersistenceTimer` 每 `persistentPeriod` 默认 **25s**；hour/day worker `persistentMod=4`（降频）。
- `MetricsPersistentWorker` 持有 `MetricsSessionCache` 热点缓存。

### 4.3 存储 / 降采样 / TTL

- 表按降采样加后缀：`service_resp_time-0/-1/-2`（minute/hour/day）；**无 `_15m`**。
- 配置：`downsampling: [Hour, Day]`、`recordDataTTL: 3`、`metricsDataTTL: 7`、`l1FlushPeriod: 500`、`persistentPeriod: 25`。

### 4.4 分位数与 heatmap

- `percentile2(precision)` → `PercentileMetrics2`（10.0.0 起）：`RANKS={50,75,90,95,99}`；按 `precision`（`core.oal` 用 10ms）分桶累积，`calculate()` 排序扫描取分位；覆盖 `service_percentile` / `endpoint_percentile` / `database_access_percentile` / `cache_*` / `mq_*` 等。
- **heatmap 另立**：`histogram(step, maxNumOfSteps)` → `HistogramMetrics`（如 `service_heatmap = from(Service.latency).histogram(100, 20)`）。

### 4.5 与 trace 的关系

- **Trace → metrics（OAP ingest 派生）**：`RPCAnalysisListener` 遍历 span → `RPCTrafficSourceBuilder` 构建 `Service`/`Endpoint`/`ServiceRelation` 等 → `SourceReceiver.receive` → OAL 生成的 `SourceDispatcher` → `Metrics` → `MetricsStreamProcessor.in()`。
- **Meter（agent 原始值）**：agent `MeterFactory` → gRPC → OAP `MeterSystem` + MAL → 同一管线。

| 来源 | 算在哪 | 例 |
|---|---|---|
| RPC / trace 派生 | OAP（analyzer → OAL） | `service_resp_time` / `endpoint_percentile` / 拓扑 |
| JVM / OS / 数据源 | agent meter → OAP MAL | `instance_jvm_cpu` / `instance_jvm_memory_*` |
| 自定义用户指标 | agent `MeterFactory` → OAP MAL | 任意 counter/gauge/histogram |

---

## 5. 源码锚点

### CAT（@ `e815e74`）

| 主题 | 文件 |
| --- | --- |
| TCP 入口（:2280） | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/analysis/TcpSocketReceiver.java> |
| 消息处理 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/analysis/DefaultMessageHandler.java> |
| 实时消费 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/analysis/RealtimeConsumer.java> |
| 周期管理（小时段） | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/analysis/PeriodManager.java> |
| domain 哈希分派 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/analysis/Period.java> |
| transaction analyzer | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-consumer/src/main/java/com/dianping/cat/consumer/transaction/TransactionAnalyzer.java> |
| 分位数计算 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-consumer/src/main/java/com/dianping/cat/consumer/transaction/TransactionStatisticsComputer.java> |
| analyzer 注册 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-consumer/src/main/resources/META-INF/plexus/components.xml> |
| 小时报落库 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-core/src/main/java/com/dianping/cat/report/DefaultReportManager.java> |
| 日/周/月报定时任务 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-home/src/main/java/com/dianping/cat/report/task/TaskConsumer.java> |
| 报表构建门面 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-home/src/main/java/com/dianping/cat/report/task/ReportFacade.java> |
| 原始树落盘 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/cat-consumer/src/main/java/com/dianping/cat/consumer/dump/DumpAnalyzer.java> |
| MySQL 表结构 | <https://github.com/dianping/cat/blob/e815e74d4c2dd74edac831241f1253fcc7d25381/script/CatApplication.sql> |

### Glowroot（@ `456b191`）

| 主题 | 文件 |
| --- | --- |
| 完成后处理（入口） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/impl/TransactionProcessor.java> |
| interval 聚合 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/impl/AggregateIntervalCollector.java> |
| 增量合并 / 直方图记录 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/impl/AggregateCollector.java> |
| 聚合落库 + rollup | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/AggregateDao.java> |
| LazyHistogram（HdrHistogram） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/common/src/main/java/org/glowroot/common/model/LazyHistogram.java> |
| 直方图 protobuf | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/wire-api/src/main/protobuf/Aggregate.proto> |
| rollup 层级配置 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/common2/src/main/java/org/glowroot/common2/repo/ConfigRepository.java> |
| rollup 时间计算 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/common2/src/main/java/org/glowroot/common2/repo/util/RollupLevelService.java> |
| gauge 采集 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/core/src/main/java/org/glowroot/agent/init/GaugeCollector.java> |
| gauge 存储 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/GaugeValueDao.java> |
| embedded collector | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/init/EmbeddedCollector.java> |

### SkyWalking（@ `3af86a1`；agent 侧 `skywalking-java`）

| 主题 | 文件 |
| --- | --- |
| OAL 脚本 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-starter/src/main/resources/oal/core.oal> |
| OAL 代码生成 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/oal-rt/src/main/java/org/apache/skywalking/oal/v2/generator/OALClassGeneratorV2.java> |
| `@Stream` 注解 / 监听 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/Stream.java> · <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/StreamAnnotationListener.java> |
| 流处理器 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/MetricsStreamProcessor.java> |
| L1 聚合 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/MetricsAggregateWorker.java> |
| L2 分钟 worker | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/MetricsPersistentMinWorker.java> |
| 持久 worker 基类 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/MetricsPersistentWorker.java> |
| 降采样 worker | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/MetricsTransWorker.java> |
| 落库定时器 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/PersistenceTimer.java> |
| 降采样枚举 | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/DownSampling.java> |
| 分位数（新） | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/metrics/PercentileMetrics2.java> |
| heatmap / histogram | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/metrics/HistogramMetrics.java> |
| MeterSystem（MAL） | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/meter/MeterSystem.java> |
| SourceReceiver | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/source/SourceReceiver.java> |
| trace → source | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/analyzer/agent-analyzer/src/main/java/org/apache/skywalking/oap/server/analyzer/provider/trace/parser/listener/RPCAnalysisListener.java> |
| 配置（TTL/降采样/周期） | <https://github.com/apache/skywalking/blob/3af86a1ec4c455e2fddb879ae51ad661112d9c78/oap-server/server-starter/src/main/resources/application.yml> |
| agent MeterSender | <https://github.com/apache/skywalking-java/blob/main/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/meter/MeterSender.java> |

---

## 6. 未核实 / 边界

1. **CAT**：日/周/月报定时任务的精确调度时点（`TaskConsumer.checkTime()`）未逐行核实；各 analyzer 完整字段口径未展开。
2. **Glowroot**：各 rollup **过期时长的默认值**（`StorageConfig.rollupExpirationHours()` 为接口）未确认；线程统计字段完整口径未展开。
3. **SkyWalking**：查询期"`Step → 后缀表`"的 resolver 代码未逐行核实（属文档化行为）；`PercentileMetrics`（旧）与 `PercentileMetrics2`（新）差异仅知版本分界（10.0.0）。
4. **三家均未实测**：本仓库未运行 CAT / Glowroot / OAP，故无写入开销、延迟、分位数误差的实测数据。
