# H2 化 Phase 5：端点基数（`MAX_ENDPOINTS_PER_BUCKET`）治理方案

> **文档定位**：针对 Trace 指标聚合中「端点基数上限」的**方案讨论稿**（未实现、未落代码），由一次 8 小时持续压测的内存分析暴露，待 §5 拍板。
>
> **相关**：问题来源与实测数据见 [`docs/notes/2026-09-25-trace-metrics-stress-memory-analysis.md`](../notes/2026-09-25-trace-metrics-stress-memory-analysis.md)（8h 压测内存分析）。
>
> **权威 spec**：`.scratch/h2-metrics/spec.md`。本方案落地后需回写其「实现决策 / 已知偏差」。

---

## 0. 触发

8h 持续压测（`scripts/stress.ps1 -Continuous`）后做内存分析，列出插件侧需盯的两个真实增长点，其一即「**端点基数 × 蓄水池**」：

> 真实应用 URL 带 path 变量时，端点会冲到 `MAX_ENDPOINTS_PER_BUCKET=500`（`endpointOverflow` 此时才开始计数）；则 `4×501×40KB≈80MB` 仅为样本数组，且分钟表行数 ×500。

本方案把该问题拆开、给出可选项与推荐路径。

## 1. 影响面：影响什么、不影响什么

- **全局保留键 `"*"` 独立累加、精确** → **全站请求数 / 错误率 / 分位永远准**，不因端点上限失真。
- 受影响的是 **per-endpoint 明细**：超过上限的端点并入 `(other)`，明细不可查。
- 结论：这是「**明细保真度 trade-off**」，不是 KPI 正确性问题。

## 2. 现状与两个子问题

代码锚点：`agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/metrics/TraceMetricsAggregator.java`
（`MAX_ENDPOINTS_PER_BUCKET=500`、`MAX_SAMPLES_PER_KEY=5000`、`Accumulator.samples = new long[5000]`、`accumulate()` 溢出逻辑、`flushClosedBuckets()`、`toHourRows()`）。

### 2.1 保真：是"先到先归并"，不是"top-N"

`accumulate()` 中一旦桶内端点满 500，**后到的新端点**一律进 `(other)`。后果：早期出现一次的冷端点占住名额，之后的高频热点反被并走 → 明细张冠李戴。

> spec 原文要求「按 request_count 归并（top-N）」，实现为"先到先"，已记为 v1 已知偏差。

### 2.2 规模：样本数组与 H2 行数才是大头

- **内存**：每 `Accumulator` 预分配 `long[5000]`＝**40KB** → 4 个桶 ×（500 端点 + `*` + `(other)`）× 40KB ≈ **最坏 80MB**。
- **H2**：分钟表 `2880 × 501 ≈ 144 万行`（48h）、小时表 `720 × 501 ≈ 36 万行`（30d）——在 H2 内存模式 + 索引下可能比 80MB 更贵。

> 真问题优先级：**2.2 规模 > 2.1 保真**（保真是"取哪 500 个"，规模才是内存/存储成本）。

## 3. 方案选项

### 轴 A — 保真（取哪 500 个）

| 选项 | 做法 | 收益 | 代价/风险 |
|---|---|---|---|
| A1 Top-N 最小堆 | 桶内按请求数维护最小堆，满员时新端点与堆顶比较，大则替换、被踢者并入 `(other)` | 精确保 top-N，O(log N) | 需维护堆与"被踢再出现"的语义 |
| A2 Space-Saving | 固定 K 计数器 + 误差上界，近似 heavy-hitter | 内存固定、实现成熟 | 计数为近似值（有误差上界） |
| A3 先到先 + 观察区 | 冷启动端点先只计数、达阈值才占名额 | 实现最简 | 仍非严格 top-N |

### 轴 B — 规模（内存）

| 选项 | 做法 | 收益 | 代价/风险 |
|---|---|---|---|
| B1 准入阈值 | 新端点在同桶内先只计数，达 K 次（如 ≥5）才分配样本，否则进 `(other)` | 长尾 singleton 不再分配 40KB | 需有界的 pending 计数结构 |
| B2 懒分配样本 | `long[]` 首次用到才 new | 对极短尾有效 | 对 500 热点无效 |
| B3 降样本上限 | `MAX_SAMPLES_PER_KEY` 5000→1024 | 40KB→8KB，最坏 80MB→16MB | p99 估计精度略降 |
| B4 v2 直方图 | `long[5000]` 换对数分桶（HdrHistogram/自研 `int[~200]`≈0.8KB） | 最坏 **80MB→~1.6MB**，与基数解耦 | 属 spec 预留的 v2 seam，表结构不变 |

### 轴 C — 源头降基数

| 选项 | 做法 | 收益 | 代价/风险 |
|---|---|---|---|
| C1 端点归一化 | 把 path 中**纯数字/UUID 段**折叠为 `{id}` | 从源头压缩基数 | 过猛会误并（`/order/abc` vs `/order/123`）；Spring MVC 的 `operationName` 已是映射模式，收益主要在未映射/通配/静态/探针路径 |

### 轴 D — 存储（H2 行数）

| 选项 | 做法 | 收益 | 代价/风险 |
|---|---|---|---|
| D1 只落 top-N | 长尾并成单条 `(other)` 落库 | 行数受 cap 约束 | 已近似此形状，只是"先到先" |
| D2 cap 可配/下调 | `MAX_ENDPOINTS_PER_BUCKET` 常量→配置，或默认降到 100–200 | H2 行数按比例降 | 明细变少；与 spec「常量防配置蔓延」冲突 |
| D3 长尾不落明细 | 长尾只计入 `*`，不落分钟明细 | 行数最少 | 口径需文档化 |

## 4. 推荐分阶段

### v1.1（小改，先止血；集中在 `TraceMetricsAggregator` 一个类）

- **A1**（top-N 最小堆）修保真：热点不再被误并。
- **B1**（准入阈值）长尾不分配样本。
- **B3**（样本上限 5000→1024）削峰值。
- **D2**（cap 可配，默认降到 ~200）压 H2 行数。

单测可覆盖：热点不被误并、长尾不分配数组、溢出按请求数、cap 生效。

### v2（结构优化）

- **B4** 直方图替换 `long[]` → 内存与端点基数基本解耦，cap 可放宽到 1000+。
- H2 行数再单独决策（D1/D3）：是否只保 top-N、长尾是否落明细。

## 5. 待拍板

1. **优先轴**：先修**保真**（A）还是先省**内存/存储**（B/D）？
2. **cap 值**：保留 500、降到 100–200、还是改可配？
3. **top-N 精确 vs 近似**：A1 精确小堆 vs A2 Space-Saving？
4. **样本策略**：先 B3 降样本数，还是直接上 B4 直方图？
5. **H2 长尾**：`(other)` 明细要不要落库，还是长尾只计入 `*`？
6. **归一化**：是否做 C1（只折叠纯数字/UUID）？

## 6. 验收与测试（落地时）

- 单测：`TraceMetricsAggregatorTest` 扩「top-N 保真 / 准入阈值 / 懒分配 / 溢出归并」四组。
- 端到端：造 >cap 个端点的流量，断言 `endpointOverflow>0`、`(other)` 行存在且请求数守恒、全局 `*` 不变；观测 RSS/jmap 与 H2 行数。
- 回写：`.scratch/h2-metrics/spec.md` 的「实现决策 / 已知偏差」与本文件 §4 决策。

## 7. 相关文档（双向）

- 本问题的**来源与实测**：[`docs/notes/2026-09-25-trace-metrics-stress-memory-analysis.md`](../notes/2026-09-25-trace-metrics-stress-memory-analysis.md)（§「两个真实增长点（详情）」）。
- 权威口径：`.scratch/h2-metrics/spec.md`；历史口径：`h2化-phase5-metrics-口径与插入点讨论.md`、`h2化-phase5-metrics实现细化.md`（原 top-N 说法）。
