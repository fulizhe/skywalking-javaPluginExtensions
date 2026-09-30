# 小时 rollup 覆盖当前小时 + 消除 2000 行静默截断

Status: ready-for-agent

## Problem Statement

在 `172.16.1.108` 上实测（2026-09-30）出现"`近 24h` 请求总数 **大于** `近 7d`"的矛盾：24h = 8,013,218，7d = 7,856,565。两个窗口本应嵌套，7d 不可能更小。

**测量结论：rollup 本身精确，一个请求都没丢。** 逐小时比对全部 19 个小时桶，`分钟 == 小时`，`delta` 全为 0：

```
497406  324616 / 324616  delta=0     497416  416487 / 416487  delta=0
497407  416723 / 416723  delta=0     497417  420480 / 420480  delta=0
...（19/19 全部 delta=0）              sum: minute=7856565 hour=7856565
```

### 根因 1：两个视图的窗口不嵌套

| 视图 | 读哪张表 | 窗口右端 |
|---|---|---|
| 24h | **分钟表** `trace_metrics_minute` | 一直到**当前这一分钟** |
| 7d | **小时表** `trace_metrics_hour` | 只到**最后一个已结束的整小时** |

`rollupPreviousHour()` 只结算**上一小时**（`prevHourBucket = now / HOUR_MS - 1`）。当前这个未结束的小时**故意不进小时表**，于是 7d 天然少一个小时。

实测对账：7d 窗口分钟表 8,013,218 − 小时表 7,856,565 = **156,653**；当前小时桶 497425 的分钟表 `*` = **156,639**（14 之差 = 两次查询之间的实时增量）。**完全对得上。**

### 根因 2：小时 rollup 有 2000 行静默截断（潜在同类 bug，本轮一并修）

rollup 用 `queryMetricRows("minute", null, …, MAX_QUERY_POINTS=2000)` 取整点小时的分钟行，而该 SQL 是

```sql
… WHERE time_bucket BETWEEN ? AND ? ORDER BY endpoint ASC, time_bucket ASC LIMIT 2000
```

`queryMetricRows` 直接 `LIMIT`、**无分页**（`H2TraceSegmentStorage.java:750`）。一旦 `小时内不同 endpoint 数 × 有数据分钟数 > 2000` 就开始截断；又因排序是 **endpoint 名优先**，被截掉的是**字典序靠后的整个 endpoint**（不是随机分钟）。阈值 ≈ **34 endpoint/小时**。当前 demo 只有 13–17 个，安全；但 `MAX_ENDPOINTS_PER_BUCKET = 500`，任何真实业务必然踩中，且**症状与根因 1 完全一样**（7d 比 24h 少），只是更难查。

### 已排除

`*` 趋势序列是**降采样**（`TraceMetricsRollup.downsample` 合并相邻桶）而非截断，求和精确（序列和 8,013,220 vs 聚合 8,013,229，差 9 为调用间实时增量）——**不是 bug，保持现状**。

## Solution

1. 翻转周期每 30s 结算**两个**小时桶：上一小时（不变）+ **当前小时（滚动部分值）**。小时行是 `MERGE` 覆盖写，每 30s 重算会自然收敛到整点真值。
2. rollup 不再把逐桶分钟行拉进 Java，改用 SQL `GROUP BY endpoint` **预聚合**（`aggregateMetricRows("minute", …)`）：
   - 行数从 `endpoint × 分钟` 降到 `endpoint` → **截断根除**，内存也更省；
   - 其列口径（`SUM` 计数 / `MAX(max_latency)` / 分位按 `request_count` 加权）与 `TraceMetricsRollup.merge()` **逐项一致**（`H2SqlStatements.java:157-170` vs `TraceMetricsRollup.java:98-152`），口径不变。
3. 当前小时**尚未翻转的在飞内存分钟**按读口同一套 `distinctMetricBuckets` 去重后并入 → **`7d >= 24h` 恒成立**。
4. 页面注明 QPS 口径（见 Implementation Decisions 末条）。

## User Stories

1. 作为运维/开发者，我想 `近 7d` 的请求总数**不小于** `近 24h`，以便两个数字不互相打架、能当同一个"总量"用。
2. 作为运维/开发者，我想 7d/30d 视图**包含进行中的当前小时**，以便刚发生的问题立刻可见，而不是等整点。
3. 作为运维/开发者，我想小时视图最后一柱在小时内**平滑爬升**到真值（而不是整点突然跳变），以便趋势图可读。
4. 作为运维/开发者，我想确认小时 rollup **一个请求都不丢**，以便 7d 的总量可信。
5. 作为插件作者，我想端点数超过 34 的真实业务上 7d 依然准，以便这个页面能脱离 demo 规模使用。
6. 作为插件作者，我想保留**迟到容忍 + 幂等覆盖**语义（保留窗口内改写分钟行后小时行随之收敛），以便 P50 尖峰不被漏掉。
7. 作为插件作者，我想 rollup 的**分位口径不变**（仍按 `request_count` 加权平均），以便不推翻已记录的 v1 已知偏差。
8. 作为插件作者，我想 rollup 的成本与"端点数"成正比（而非"端点数 × 分钟数"），以便每 30s 一次的开销可预期。
9. 作为新读者，我想页面写明"7d/30d 含进行中的当前小时"与"QPS 为活跃平均"，以便不误读这两个数字。
10. 作为维护者，我想这次修的是**一类**问题（窗口右端口径）而不是一个数字，以便同类偏差能被同一条规则挡住。
11. 作为验证者，我想有一条断言"`小时表 >= 分钟表`（同一窗口右端）"的回归守卫，以便这类问题下次当场暴露。
12. 作为维护者，我想把"小时表只含已结束小时"这个隐含约定变成显式代码，以便后来者不必从 bug 反推。

## Implementation Decisions

- **rollup 两个桶**：`rollupPreviousHour(now)` → `rollupHours(now)`，处理 `{now/HOUR_MS - 1, now/HOUR_MS}`（整点前后去重/跳过无效下界）。
- **每小时桶的处理**（新增私有方法拆出，一个动作一个方法）：
  1. `aggregateMetricRows("minute", fromMin, toMin, TraceMetricsQuery.LIMIT_MAX)` → 每端点一行（`time_bucket` 是占位 `0`）；
  2. `distinctMetricBuckets("minute", fromMin, toMin)` + `metricsAggregator.memoryRows()`：只补 H2 尚无的桶（复用读口 `aggregateMetrics` 的同一套去重，杜绝重复计数）；
  3. `TraceMetricsRollup.mergeByEndpoint(rows, hourBucket)` → `storeMetricRows("hour", …)`（`MERGE` 覆盖写）。
  - **不需要"回填桶"这一步**：`mergeByEndpoint` 的输出行本身就盖上传入的 `hourBucket`，SQL 预聚合行的占位 `0` 自然被覆盖（原计划的 `MetricsRow.withTimeBucket` 因此不需要新增）。
- **端点上限**：直接复用 `TraceMetricsQuery.LIMIT_MAX`（1000），**不新增常量**——语义从"行数上限"纠正为"端点数上限"。
- **在飞分钟并入只做当前小时**：上一小时的内存桶早已翻转入库，`distinctMetricBuckets` 会把它们全滤掉，故代码路径统一、不按桶特判。
- **`shutdown()` 路径同样改为 `rollupHours(now)`**（原先只结算上一小时）；关闭前的最后一次翻转 + rollup 会把在飞分钟一并落进小时行。
- **删除死代码 `TraceMetricsRollup.toHourRows`**：rollup 不再走它后它只剩测试引用，且本身只是一行 `mergeByEndpoint` 委托（同一操作两个名字）。其两个测试改为直接测 `mergeByEndpoint`，并**新增一条 rollup 形状用例**（SQL 预聚合行 + 在飞内存行 → 精确守恒 + 落在真实小时桶 + `worst*` 透传）。
- **不改表结构、不改分位口径、不改分钟写路径**。
- **QPS 口径（接受偏差 + 页面注明）**：`qps = requestCount / (有数据桶数 × 3600)`，当前小时只过了部分时间却按满 3600s 计入分母 → 数据量少时短暂低估（19 桶时 ≈3%，攒满 7 天 ≈0.2%，**自愈**）。在 `metrics.html` 的 KPI 副标题与口径脚注处补说明，**不改其图表与算法**。
- **残余滞后（实测后决定接受，不做读口实时 rollup）**：小时行是每 30s 重算一次的快照，而分钟读口每次并入最新在飞分钟 ⇒ 「近 7d/30d」比「近 24h」少**一个翻转周期内的流量**（≈114 rps 下 ≤3.4k）。
  - **实测代价对比（172.16.1.108，40 次取样）**：新增的"当前小时分钟 GROUP BY" `avg 25.5ms / p95 33.0ms`；页面每 5s 轮询发 2 个读口 ⇒ **持续多占约 1.02% 单核**，而现有两个读口本身已占约 0.7%（`hour aggregate avg 15.3ms`、`hour 逐桶 avg 18.7ms`）。
  - **决定：方案 2（接受 + 写清上界）**——收益是纯装饰性的 ≤30s 滞后，成本却是把大屏读开销抬近三倍，不划算。
  - **若将来确需归零**：正解不是读口补算，而是让 `TraceMetricsAggregator` **同时维护小时累加器**（rollup 变成 O(1) 落库、不扫分钟行），属独立一条线。
  - 页面已注明：7d/30d 最后一个桶是"进行中的当前小时"，最多落后最近一次翻转，故总量可能略小于 24h。
- **`metrics-troubleshoot.html` 不改**：其"最差小时"口径为各小时桶分位取 MAX，多一个部分小时桶属于自然延伸。

## Testing Decisions

- **只测外部行为**，不锁 SQL 文本、不锁私有方法（沿用 metrics-troubleshoot spec 的原则）。
- **单元 seam = rollup 合并**（`TraceMetricsRollupTest` 先例）：给定"SQL 预聚合行 + 在飞内存行"，断言合并后小时行 `requestCount` 精确求和；给定"内存行与 H2 行同桶"，断言**不重复计数**。
- **存储 seam**（`H2TraceSegmentStorageMetricsTest` 先例）：断言 `aggregateMetricRows("minute", 整小时)` 每端点一行且 `SUM(request_count)` 精确。
- **主 seam = HTTP 读口**（`validate-h2.ps1` 已有 `/inner/sw/metrics/query` 断言先例）：在既有"五档范围路由"断言之后加 3 条**确定性**回归守卫——
  1. `小时表含进行中的当前小时（当前小时桶 * > 0）`：修复前该桶恒缺，**必然失败**；
  2. `上一小时 小时表 == 分钟表（精确守恒）`：右端取上一个整点小时末尾（已结算），排除在飞分钟的 30s  staleness；截断 / 漏合并会打破等式；
  3. `近 7d 窗口 小时表 >= 分钟表`：同一右端下用户可见口径，正是本次症状。
- 之所以**不**直接断言"任意时刻 `7d(含在飞) >= 24h(含在飞)`"：小时行每 30s 才重算、分钟读口每次都并入最新在飞分钟，突发流量下会出现 ≤30s 的瞬时倒挂——那条断言会 flaky。三条守卫已覆盖同一类缺陷且无竞态。
- **端到端对账**（本次线上实测脚本同款）：部署到 108 后重跑"逐小时 delta"扫描，要求全部 `delta = 0` **且** 小时表出现当前小时桶。

## Out of Scope

- **H2 落盘**（`jdbc:h2:file:` + volume）：用户明确暂缓评估。⇒ 7d/30d 仍只有**进程生命周期内**的数据（本轮实测为 19 小时，因为部署时刻清库）。
- **不把 7d/30d 档位下掉**，也不改 H2 保留期（分钟 48h / 小时 720 桶）。
- **不改** `metrics.html` 的图表与算法（只加口径说明文字）；不改 `metrics-troubleshoot.html`。
- **不改分位口径**：p50..p99 仍是"按 `request_count` 加权平均"的 v1 近似（小时分位现在是"SQL 小时聚合"与"在飞分钟"的加权平均，仍是同一近似类，已记录）。
- **不做历史回填**：修复上线后从此刻起满足不变量，过去的部分小时桶不补。
- **不引入 `MAX_QUERY_POINTS` 之外的新分页机制**（rollup 改走聚合后已无需分页；读口逐桶序列的 2000 行上限维持现状）。

## Further Notes

- **实测证据（2026-09-30，172.16.1.108）**：分钟表 7d 窗口 8,013,218 / 小时表 7,856,565 / 差 156,653 / 当前小时分钟 156,639；19 个小时桶 delta 全 0。
- **34 endpoint/小时阈值的算术**：`MAX_QUERY_POINTS = 2000`，`60 分钟 × N > 2000 ⇒ N ≥ 34`。
- **与 `metrics-troubleshoot` spec 不冲突**：那份只加只读字段，本轮改的是 rollup 写路径的桶集合与取数方式。
- **术语**：本轮把"小时表只含已结束小时"从隐含约定变为显式行为（当前小时为**滚动部分值**）；页面文案需与此一致。
- **数据起点说明**：H2 为 `jdbc:h2:mem:sw_trace_segment`（`H2TraceSegmentStorage.java:62`），容器重启即清库；本轮观测到的 19 小时起点 = 本次部署时刻，与本 spec 无关。
