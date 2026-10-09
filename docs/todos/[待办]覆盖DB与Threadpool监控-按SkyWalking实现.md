# [待办] 覆盖 DB 与 Threadpool 监控 — 按 SkyWalking 实现

> 范围：`agent/logfile-reporter-plugin`
> 定位：本文件是 `[待办]可扩展空间-按可观测性价值排序.md` 中 **P0-1 的落地方案**（并溢出到其 P0-2 线程池饱和度 sink、P2-2 指标维度）。
> 背景：`README-TODO-Components.md` 自陈「数据库监控 / Threadpool 监控暂未覆盖」。
> 前提（硬约束）：**单 JVM、断 OAP 的本地探针**；不引中心化，所有消费在本地完成。
> 核心认知：最重的拦截器上游 SkyWalking 已写好，本 fork 的缺口在**本地消费层**（上游把慢 SQL 判定与线程池指标落盘都委托给了 OAP，而本 fork 没有 OAP）。
> 状态：待办。先讨论后落地，落地前需对齐文末 Q1–Q5。

---

## 0. 落点判断（决定工作量量级）

| 步骤 | 内容 | 本 fork 现状 |
|---|---|---|
| 1 | 启用上游 `jdbc-*` / `thread-pool` 插件（打包 + 激活） | 待确认是否已在发行包内（见 Q1） |
| 2 | 补本地消费层：慢 SQL 本地判定 + 线程池指标本地 sink | **这是真正的工程缺口** |

> 关键：SkyWalking 的 `jdbc-*` 与 `thread-pool-plugin` 已 instrument 了 `Connection`/`Statement`/`PreparedStatement` 及 `ThreadPoolExecutor`/`ScheduledThreadPoolExecutor`。本 fork 不必重写拦截器，只需「启用 + 接住本地消费」。

---

## 1. DB 监控覆盖

### 1.1 上游做法
- agent 只产 span（带 `db.type` / `db.instance` / `db.statement` 标签的 ExitSpan）。
- 慢 SQL 是 **OAP 侧**用 `slowDBAccessThreshold`（默认 `1s`）从 db 类型 span 算出——本 fork 无 OAP，需本地化。

### 1.2 本 fork 可复用资产
- DB 调用自然流经已 override 的 `LogFileTraceSegmentServiceClient` → 落本地 segment 存储，**segment 里已有 db 类型 span**。
- 已有 `SlowThresholdResolver` / `ExtremeTraceSelector` / `TraceAlertMetrics` 这套「按 span 判慢/判极端」machinery——慢 SQL 本质是同构的 db 类型 span duration 阈值判定。

### 1.3 两条路径
| 路径 | 做法 | 成本 | 产出 | 状态 |
|---|---|---|---|---|
| **A（推荐起步）** | 复用 `SlowThresholdResolver` 思路，新增 db 类型慢判定（阈值对齐 `slowDBAccessThreshold`，可配），挂进现有告警引擎 | 小 | 慢 SQL 告警，与现有 trace 告警同管道 | ☐ |
| B（分析向） | 在 `TraceMetricsAggregator` 加「按 db 操作类型/实例」维度，出 TopN 慢语句、按语句类型计数 | 中 | SQL 级分析指标（P2 指标维度增强兄弟项） | ☐ |

### 1.4 决策点（落地前锁）
- [ ] 是否抓 SQL 文本：建议只抓**参数化后的 statement（不带参数值）**，与「避免明文令牌/PII」红线一致。
- [ ] 慢 SQL 阈值：默认 `1s` 还是按业务调；做成可配。

---

## 2. Threadpool 监控覆盖

### 2.1 上游做法
`thread-pool-plugin` 做两件事：
1. 跨池边界传播 trace 上下文（异步任务不断链）；
2. 经 meter 管线报 `running / queued / rejected / completed` 四个 gauge。

### 2.2 本 fork 映射
| 能力 | 做法 | 状态 |
|---|---|---|
| 链路不断裂（上下文传播） | 启用 `thread-pool-plugin`，异步链路连续，喂「trace parity 只在本 JVM」故事 | ☐ |
| 指标本地 sink | 照 `JVMMetricsLocalSender` 的「本地有界缓存」套路 override meter sender（BootService），把线程池 gauge 缓存本地，并入 `TraceMetricsAggregator` 当**饱和度信号**（P0-2 项） | ☐ |

### 2.3 四个信号与来源
| 信号 | gauge 来源 | 重要性 |
|---|---|---|
| running_threads | `getActiveCount()` | 饱和度基础 |
| queued_tasks | `getQueue().size()` | 积压，背压前兆 |
| **rejected_tasks** | 自定义 `RejectedExecutionHandler` 计数 | **真正的背压/过载信号，最该告警** |
| completed_tasks | `getCompletedTaskCount()` | 吞吐趋势 |

### 2.4 现实坑（待决策）
- [ ] 无名线程池命名策略：很多框架 `new ThreadPoolExecutor(...)` 不设名，上游命名会退化。兜底用：调用方类 / toolkit 注解 / 线程名三选一或组合。

---

## 3. 诊断卡前置（避免「为采集而采集」）

> 每加一个指标都要问谁会看。

- [ ] DB 目标：只想要**慢 SQL 告警**（延迟，路径 A），还是也要 **SQL 级分析**（路径 B）？
- [ ] Threadpool 目标：**链路传播** / **饱和度指标** / **两者都要**？三者拦截深度不同。

---

## 4. 待对齐问题（Q1–Q5，落地前必须拍）

- [ ] **Q1（最关键）**：fork 的 `agent` 发行包里，现在**有没有打包 `jdbc-*` 与 `thread-pool` 插件**？有则第 1 步近乎免费；没有则需先纳入构建。
- [ ] **Q2**：慢 SQL 阈值默认 `1s` 还是按业务调？要不要抓参数化 statement（不带参数值）？
- [ ] **Q3**：线程池要「链路传播」「饱和度指标」还是「都要」？
- [ ] **Q4**：无名线程池命名兜底策略（调用方类 / toolkit 注解 / 线程名）？
- [ ] **Q5**：DB/线程池结果**复用**现有 `TraceMetricsAggregator` + 告警引擎，还是另开本地存储？（建议复用，避免新竖孤岛）

---

## 5. 建议落地顺序（Q1–Q5 拍后）

1. 确认 Q1 → 启用上游插件（如有缺失先补构建）。
2. 先动 DB 本地慢 SQL 判定（路径 A）——复用 `SlowThresholdResolver`，改动小、价值直给。
3. 再动线程池 meter 本地 sink + rejected 告警——补饱和度信号（P0-2）。
4. 路径 B / 分析向增强按需后置（P2 指标维度）。

---

## 6. 参考

- `agent/logfile-reporter-plugin/README-TODO-Components.md`（DB / 线程池未覆盖自述）
- `agent/logfile-reporter-plugin/src/main/java/.../reporter/logfile/LogFileTraceSegmentServiceClient.java`（segment 本地落地）
- `agent/logfile-reporter-plugin/src/main/java/.../reporter/logfile/alert/SlowThresholdResolver.java`（慢判定复用）
- `agent/logfile-reporter-plugin/src/main/java/.../reporter/logfile/alert/ExtremeTraceSelector.java`
- `agent/logfile-reporter-plugin/src/main/java/.../reporter/logfile/metrics/TraceMetricsAggregator.java`（并入饱和度/维度）
- `agent/logfile-reporter-plugin/src/main/java/.../reporter/logfile/JVMMetricsLocalSender.java`（本地有界缓存套路，线程池 sink 照此）
- 上游 SkyWalking：`apm-sdk-plugin/jdbc-*`（ExitSpan + `db.*` 标签）、`apm-sdk-plugin/thread-pool-plugin`（上下文传播 + meter 四 gauge）、OAP `slowDBAccessThreshold`（默认 `1s`）
- 上级（本文件的落点）：`[待办]可扩展空间-按可观测性价值排序.md` —— 本文件是其 P0-1 落地方案（并覆盖其 P0-2 线程池 sink、P2-2 指标维度）
- 关联：`[已实现]hutool-json-依赖错位分析.md`
