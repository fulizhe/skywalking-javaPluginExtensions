# GlowRoot 的 profile 思路引入本仓（评估 · 待做）

> **状态（2026-10-06）**：**未实现，仅评估记录**。结论是**可行、成本中等偏低**，但**今天不做**（原话："今天不做这个"）。本文存底，之后有时间再开工。
> **定位**：决策前的可行性 + 成本底稿，**不是 spec**。真做时先按 §7 跑一遍验证路径，据结果把成本档位定死，再拆 tickets。
> **一手底稿**：GlowRoot profile 的源码 / wiki 核实见 **`docs/reference/glowroot-profiling.md`**（本文只引用，不重复）。
> **非目标**（写死，见 §5）：**不碰 `oap/`**、不引入 native profiler、不改宿主工具类既有 API 契约。

---

## 0. 结论先行

- **可行**，而且本仓**已有约 70% 管线**（profile 快照的采集改道、本地触发、demo-app 读口与页面都已存在）。
- **要点**：GlowRoot **没有"采样引擎"可抄** —— 它的 profile 就是对慢请求的线程做周期栈采样（见 reference §1/§2），而 **SkyWalking 内置 profiler 用的就是同一套技术**（`Thread.getStackTrace()`）。所以"采样"这半边零创新。
- **成本花在三处**：① 调度模型不兼容；② 现有本地管线**丢数据**；③ 折叠栈 → 火焰图渲染与页面。
- **三档**：范围 1「点亮已有链路」**2~3 人天**；范围 2「GlowRoot 语义」（推荐）**5~8 人天**；范围 3「真 CPU/alloc profiling」**不做**（许可 + 收益，见 §4）。
- **一个必须先决的冲突**：本仓 ROI 文档已把「火焰图」列为**明确不做**，且其理由对本方案**不成立**（见 §6）。

---

## 1. GlowRoot 的 profile 到底是什么（要点，细节见 reference）

引自 `docs/reference/glowroot-profiling.md`（基准 `glowroot@a48cd05c`）：

1. **阈值门控**：只有超过慢阈值的请求才被 trace；**没有采样百分比**，要更多就调低阈值。
2. **挂在单条 trace 上**：Profile 是 trace 内的一个 tab（与 Breakdown / Entries / Queries / Service Calls 平级），采样发生在**该慢请求执行期间**，间隔可配（`profilingIntervalMillis`）。
3. **无 native profiler**：发行包第 3 方清单**没有 async-profiler**；线程 CPU/blocked/allocated 走 `com.sun.management.ThreadMXBean` 事务首尾差值；火焰图用 d3-flame-graph。
4. **密集采样标准手法**：`slowThresholdMillis=0` + `profilingIntervalMillis=50`（见 `agent/vt-profile-smoke/setup-agent.bat`）。

→ 一句话：**"慢 trace 驱动 + 线程栈周期采样 + 样本挂在 trace 上 + 折叠栈画火焰图"**。这就是要引入的"思路"。

---

## 2. SkyWalking 内置 profiler 的硬约束（源码级，决定成本）

基准：`apache/skywalking-java` @ `ea2fb09b0736cbbcc28b66ca57ca6513f6c9284f`（main），路径前缀
`apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/profile/`

| 事实 | 出处 |
| --- | --- |
| **采样技术同源**：`buildSnapshot()` 直接 `profilingThread.getStackTrace()`，倒序拼 `className.methodName:lineNumber`，带 `dumpSequence`，受 `DUMP_MAX_STACK_DEPTH` 截断 | `ThreadProfiler.java` |
| 阈值门控：`startProfilingIfNeed()` 只在 `now - firstSegmentCreateTime > minDurationThreshold` 后才开始 dump | `ThreadProfiler.java` |
| 采样循环：独立 `ProfileThread`，`maxSleepPeriod = task.getThreadDumpPeriod()`，逐 slot 调 `addProfilingSnapshot(snapshot)` | `ProfileThread.java` |
| **全局单任务**：`AtomicReference<ProfileTaskExecutionContext> taskExecutionContext`，`processProfileTask()` 会先 `stopCurrentProfileTask(上一个)` | `ProfileTaskExecutionService.java` |
| **端点精确匹配**：`attemptProfiling` 里 `Objects.equals(task.getFirstSpanOPName(), firstSpanOPName)` | `ProfileTaskExecutionContext.java` |
| 并发上限：`Config.Profile.MAX_PARALLEL`（每端点）、slot 数 `MAX_PARALLEL*(MAX_ACCEPT_SUB_PARALLEL+1)` | `ProfileTaskExecutionContext.java` |
| 任务校验：端点非空；`duration` 在 `TASK_DURATION_MIN_MINUTE~MAX_MINUTE` 之间；`threadDumpPeriod >= TASK_DUMP_PERIOD_MIN_MILLIS`；`maxSamplingCount > 0 且 < TASK_MAX_SAMPLING_COUNT`；**时间段不允许与在跑任务重叠** | `ProfileTaskExecutionService.checkProfileTaskSuccess()` |
| 触发通道：`addProfileTask(task)` 由 OAP 命令通道下发（本仓已 `NoOp` 掉） | `ProfileTaskExecutionService.java` |

⚠️ **未核实**：`ProfileConstants` 各常量的**字面数值**（在 `apm-network` 常量模块，本文未打开）。本仓既有代码注释观察到 `maxSamplingCount` **必须 < 10**（`LocalPorfileCallInterceptor.java` 第 92 行附近引用）。

**结论**：SkyWalking 的调度模型是"**一次一个端点、整分钟、≤9 条、任务不重叠**"，与 GlowRoot 的"**每条超阈值 trace 都采**"**模型不兼容**。这是真正的成本来源，不是采样本身。

---

## 3. 本仓现有零件与坏点

### 3.1 已有零件（不是零起点）

- `ProfileSnapshotLocalSender`（`@OverrideImplementor(ProfileSnapshotSender.class)`，`CircularBlockingQueue` 500 条）—— `agent/logfile-reporter-plugin/.../plugin/dynamic/override/ProfileSnapshotLocalSender.java`
- `NoOpProfileTaskChannelService` / `NoOpAsyncProfilerTaskChannelService` —— 已断开 OAP 下发
- `LocalPorfileCallInterceptor` / `LocalProfileTrigger` —— 本地构造 `ProfileTaskCommand` 并从 `CommandExecutorService` 触发（`startTime = now + 5000`，延后 5 秒）
- demo-app 读口与页面：`ProfileController.java`（`GET /profileData2`、`GET /profileData`、`POST /profile`、`GET /longTimeTask`）、`static/profile-result.html`（`fetch('/profileData2')` 后读 `snapshot.stack.codeSignatures`）
- 端口：`agent/demo-app/src/main/resources/application.yml:3` = `${WebPort:9601}`

### 3.2 坏点（必须先修）

1. **丢数据**：`LocalProfileStatusExposeInterceptor.java:82-84` 自认 —— agent 侧 `ProfileSendSnapshotService` 线程会周期性清空队列，"所以这里面的数据总是空的"。
2. **覆盖语义**：`ProfileSnapshotLocalSender.java:104` 注释自认 —— "对于同一个 Task, key 相同, 于是出现覆盖效果"（append 式环形队列被当键存储用）。
3. **无聚合、无渲染**：`profile-result.html` 只是把 `codeSignatures` 列出来，没有"按 traceId 折叠成火焰图"这一步。
4. **无 verify 覆盖**：`verify/scenarios/*` 目前**没有任何** profile 断言（两侧插件都没有）。

### 3.3 现有链路的取舍

SkyWalking 内置 profiler 的**采样半边可直接复用**，但其**调度半边**（单任务 / 整分钟 / ≤9 条）必须**绕过**（本仓已用 `NoOp` + 本地触发绕过一半）。范围 1 与范围 2 的差别就在这里：**范围 1 接受这个调度模型，范围 2 自己写采样调度**。

---

## 4. 成本三档

> 粗估，按"熟悉本仓的一个人"计；真做时先跑 §7 定档。

| 档 | 范围 | 做什么 | 估时 | 代价 / 限制 |
| --- | --- | --- | --- | --- |
| **范围 1** | 点亮已有链路 | 修丢数据（自己持有缓冲、别依赖 agent 的消费线程）+ 按 traceId 分组 + 折叠栈聚合 + 火焰图页 + 1 个 verify scenario | **2~3 人天** | 受 SkyWalking 调度约束：单端点、≤9 条/任务、分钟粒度；演示时得"先触发再压测" |
| **范围 2**（推荐） | GlowRoot 语义 | 自写采样调度，挂在既有 `TraceEvaluator` / `ExtremeTraceSelector` 的慢判定上（**只为慢 trace 采样 → 健康时零成本**）；按 traceId 存有界（H2 mem 表，必要时复用 `CappedFileStorage`）；`trace-view.html` 加"看火焰图"入口 | **5~8 人天** | 要自己管限流（同时 N 条、每 trace 样本上限）与存储淘汰 |
| **范围 3** | 真 CPU/alloc profiling | async-profiler 或 JFR（`jdk.ExecutionSample`/`jdk.ObjectAllocationSample`） | **不做** | async-profiler 是 **GPL-2.0**，与 Apache-2.0 的 agent 插件许可冲突（**GlowRoot 自己也没用**）；JFR 路线在本仓 JDK 17 目标下可行但要 1~2 周且 JDK 8 不可用，收益窄 |

**复用与不新增**：范围 2 可复用本仓已建的 H2 mem 持久层与 `CappedFileStorage`（ADR-02 / ADR-03），**不新增存储技术选型**。

---

## 5. 非目标（写死）

1. **不碰 `oap/`**。本仓 `oap/` 只有一个 H2 snapshot storage provider（3 个 Java 文件）；要在 OAP 侧加 profile GraphQL 查询等于 fork OAP，与"OAP-less 单体、内存优先"定位相反。**profile 全程留在应用进程内。**
2. **不引入 native profiler / async-profiler**（许可 + 与定位正交）。
3. **不改宿主工具类既有 API 契约**：`enableReport` / `disableReport` / `statisticStatus` / `startProfile` / `getProfileDatas` 的**调用方式与返回契约不变**（同一约束见 `docs/todos/h2化-统一方案.md`）。
4. **不追"全 JVM 连续采样 + rollup"**（Pyroscope 式），那是另一条产品线。

---

## 6. 待决：与既有 ROI 结论的冲突（需你裁决）

- `docs/todos/依赖拓扑-对标skywalking的ROI排序.md:16` 把**火焰图**列进「❌ 明确不做（得不偿失）」。
- 同文件 `:137` 的理由是：「需额外部署 + 采样配置，改动面大、收益窄」。
- **该理由对本方案不成立**：见 `docs/reference/glowroot-profiling.md` §4 —— "需额外部署"是 **Pyroscope**（侧车/服务端）的形态；**GlowRoot 思路是纯进程内**，embedded 模式与本仓同构，不需要额外部署。
- **本文不替该结论翻案**，只记录冲突。真要开工，需要先把 `:16` / `:137` 改掉（或注明"GlowRoot 路线例外"），并明确这次做的**真实理由**是哪一个：
  - (a) 给对外汇报补一块能力演示（本仓 dashboard 的主要用途，见该文件 §背景）；
  - (b) 真要排查慢请求（实际自用价值）。

---

## 7. 验证路径（**先跑这个再定档**）

现成通路已存在，只是没被 verify 覆盖。手工确认"采样半边是通的"：

```powershell
# 1) 起 demo-app（带 agent），默认端口 9601
pwsh -NoProfile -File agent/demo-app/scripts/start-demo.ps1

# 2) 反复打慢端点（每次随机睡 200~2000ms）
curl "http://127.0.0.1:9601/longTimeTask"

# 3) 触发本地 profile 任务（agent 侧延后 5s 启动，最多 9 条）
curl -X POST http://127.0.0.1:9601/profile -H "Content-Type: application/json" -d '{"endpointName":"/longTimeTask","minDurationThreshold":1,"maxSamplingCount":5,"dumpPeriod":100}'

# 4) 看采样结果
curl http://127.0.0.1:9601/profileData2
```

**判据**

- **能看到 `codeSignatures` 栈序列** → 采样半边是好的，剩下只是"按 traceId 折叠成火焰图 + 一个页面"，**纯本地加工、风险极低** → 按 §4 范围 1 或 2 走。
- **这一步就是空的** → 说明要重写采样通路，**范围 2 从 5~8 人天上调到约 1.5~2 周**，需重新评估。

---

## 8. 源码锚点（求证用）

**GlowRoot**（基准 `a48cd05cfeb20ff66f760211450b3b7bc21fab7a`）
- 逐条已核实 / 未核实 + permalink 清单 → `docs/reference/glowroot-profiling.md`
- 注意：wiki 来源**无法 pin commit**（独立 wiki 仓），reference §6.4 已标注。

**SkyWalking**（基准 `ea2fb09b0736cbbcc28b66ca57ca6513f6c9284f`）
- `apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/profile/ProfileTask.java`
- 同目录 `ProfileTaskExecutionContext.java`、`ThreadProfiler.java`、`ProfileThread.java`、`ProfileTaskExecutionService.java`

**本仓**
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/plugin/dynamic/override/ProfileSnapshotLocalSender.java`（:104 覆盖语义）
- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/plugin/localprofile/LocalProfileStatusExposeInterceptor.java`（:82-84 丢数据）
- 同目录 `LocalPorfileCallInterceptor.java`、`LocalProfileTrigger.java`；`plugin/dynamic/override/NoOpProfileTaskChannelService.java`、`NoOpAsyncProfilerTaskChannelService.java`
- `agent/demo-app/src/main/java/org/openskywalking/demo/controller/ProfileController.java`、`agent/demo-app/src/main/resources/static/profile-result.html`
- `agent/README.md`（AI PROMPT：监控只能是助力，宁可采集不到）
- `docs/reference/glowroot-profiling.md`、`docs/todos/依赖拓扑-对标skywalking的ROI排序.md:16,137`
