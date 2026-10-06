# Glowroot Profiler：profile 采集与呈现实现速览（一手核实）

> **用途**：评估"把 Glowroot 的 profile 思路引入本仓"时的**外部实现底稿** —— 回答"Glowroot 的 profile 到底是什么、怎么采、挂在哪"。
> **核实基准**：`glowroot/glowroot` @ `a48cd05cfeb20ff66f760211450b3b7bc21fab7a`（main，2026-10-04，"Ref move Jboss module check in AgentModule"）。**另**引用 GitHub wiki（独立 wiki 仓，**无法 pin commit**，见 §6.4）。
> **证据类型**：`【源码】`= 打开该 commit 的源码 / 随仓文档确认；`【Wiki】`= 官方 wiki 页面（按 **2026-10-06 检索**采信）；`【Issue】`= issue 正文。**无【实测】** —— 本仓库未运行 Glowroot。
> **已核实**：profile 的采集语义（阈值门控 / 无采样百分比 / 间隔可配 / 挂在单条 trace）、线程 CPU·blocked·allocated 的来源、agent 发行包**不含** async-profiler、火焰图渲染库、虚拟线程下丢 profile 的现场与复现参数。
> **未核实**（见 §6）：真正执行栈采样的类 / 包；profile 的 H2 / Cassandra 存储 schema 与保留策略；"continuous profiling" 在现行代码里的落点。
> **姊妹篇**：链路存储 `glowroot-trace-storage.md`、封顶文件 `glowroot-capped-database.md` —— 两者基准为 `456b191`，**与本文基准不同**。本仓侧对照与引入评估见 `docs/todos/glowroot-profile思路-引入评估.md`。

---

## 0. 一句话结论

1. Glowroot 的 profile **不是"全 JVM 连续采样"**，而是**跟着慢 trace 走的线程栈采样**：只有超过慢阈值的请求才被 trace，采到的样本作为**那一条 trace 的 Profile tab** 呈现。
2. **没有采样百分比**（官方原话 "no sampling percentage"）；想要更多 trace 就**调低慢阈值**，不是概率抽样。
3. 采样**间隔可配**（`profilingIntervalMillis`），越小 Profile 越细、对被 trace 请求的开销越高。
4. agent 发行包**不引入任何 native profiler**：第 3 方清单里**没有 async-profiler**；实现靠 JDK 自带能力（栈采样 + `com.sun.management.ThreadMXBean`）。
5. 火焰图渲染用 **d3-flame-graph**（第 3 方清单）。
6. 虚拟线程上会**丢 profile**（issue #1125）；其自带 smoke 用例用 `slowThresholdMillis=0` + `profilingIntervalMillis=50` 逼出"每条请求都有样本" —— **这组参数就是"要密集采样"的标准手法**。

---

## 1. 采集语义：阈值门控 + 挂在单条 trace

【Wiki】`Transaction-configuration`

- **慢阈值**（`slowThresholdMillis`，默认常见 **2000ms**，可按 type / name / user 覆盖）：超过才成为 active trace，完成后落库、出现在 **Traces** 列表。
- **没有采样百分比** —— "To reduce volume, raise the threshold or tighten per-transaction overrides rather than expecting probabilistic sampling."
- **Profiling interval**："Controls how often CPU stack samples are taken **during traced (slow) requests**. Lower interval = finer Profile tab detail and higher overhead."
- 有 per-transaction override，可只针对目标端点单独调阈值。
- 官方排障手法 **"Profiling workflow (temporary threshold)"**：临时调低阈值（或加单端点 override）→ 复现 → 看该 trace 的 Profile → **复原**阈值。

【Wiki】`How-to-read-a-Trace`

- 一条 trace 内的分区：Breakdown（timer 树）/ Entries（时间线）/ Queries（JDBC weave）/ Service Calls（出站）/ **Profile（栈样本，填 timer 之外的空白）**。
- Profile 定义："a flattened view of stack samples taken while the slow request was being traced"。
- 用途："Use it when Breakdown does not account for wall time: look for hot methods, lock wait, GC-related frames, or code with no timer."

【源码】`README.md`（features 段）

- 同时写了 "Trace capture for slow requests and errors" 与 "Continuous profiling (with filtering) and flame graphs"。
- ⚠️ **口径提醒**："continuous" 是产品措辞；**本页 §1/§2 描述的现行形态是 per-trace 采样**（样本挂在 trace 上）。两词别混用。

---

## 2. 采样技术：无 native、无 async-profiler

【源码】`docs/jvm-thread-stats-allocated-memory.md`

- 线程 CPU / blocked / waited / **allocated bytes** 来自 **`com.sun.management.ThreadMXBean`**：事务开始时记 `getThreadAllocatedBytes(threadId)`，结束时再记一次，UI 展示**差值**（累计分配，非当前占用）；JVM 不支持或未开启则省略该行（`-1`/unavailable）。
- 开关是独立的："Configuration → Transaction → Capture JVM thread stats"。
- ⚠️ 这是**线程统计**（计数），与 Profile 的**栈样本**（采样）是**两条不同的采集**，别混为一谈。

【源码】`Third-Party Software`（wiki 页，同表亦见发行包 `NOTICE` / `LICENSE`）

- agent 发行包第 3 方清单里有：ASM、Guava、H2、HdrHistogram、gRPC、Jackson、Netty、Logback、**d3-flame-graph** 等。
- **没有 async-profiler、没有 JFR 解析库**。
- 结论：**Glowroot 不靠 native profiler 实现火焰图。**

【Issue】#1125 +【源码】`agent/vt-profile-smoke/README.md`、`setup-agent.bat`、`pom.xml`

- 现象：Jetty（Dropwizard）+ 启用虚拟线程、JDK 21 后，"web 请求不再有 thread profiles"；修复后 Profile 能看到 `java.lang.VirtualThread.run` 与业务帧。
- 复现参数（写在该用例的 `setup-agent.bat`）：`slowThresholdMillis=0`（每条请求都算慢）、`profilingIntervalMillis=50`（每 50ms 一个样本）。
- 该用例备注：虚拟线程上 **Thread CPU / blocked / waited 保持 N/A**（预期内，不在该 issue 范围）。
- **推论（明确标为推测，非结论）**：该现象与"在目标线程上调 `Thread.getStackTrace()` 采样"的形态吻合 —— 但**采样类未打开**，故不进 §「已核实」（见 §6.1）。

---

## 3. 呈现与存储

【源码】`Third-Party Software`

- 火焰图用 **`d3-flame-graph`**（Apache 2.0）+ D3（BSD 3-Clause）。

【Wiki】`How-to-read-a-Trace`

- Profile 是 **trace 内**的一个 tab，与 Breakdown / Entries / Queries / Service Calls 平级；不是独立的"Profiler 大屏"。

【未核实】

- profile 在 H2（embedded）/ Cassandra（central）里的**表结构与保留策略**。姊妹篇 `glowroot-trace-storage.md` §0 提到 profiles 走 `CappedDatabase`（payload 入封顶文件），但本文**未就 profile 单独打开 DAO / DDL 核对**。

---

## 4. 澄清一个常见误解："需要额外部署"

【Wiki】安装 / 模式页（`Choosing Embedded vs Central`、`Agent Installation (with Embedded Collector)`）

- **embedded**：`-javaagent` + 同进程 H2 + 内置 UI **在同一 JVM**；**不需要**额外部署任何采样服务。
- **central** 才需要独立进程 + Cassandra。
- → 把"Glowroot 的 profile"说成"需要额外部署 Pyroscope"是**另一条路线**（Pyroscope 的侧车 / 服务端形态）。本仓 ROI 文档里"需额外部署 + 采样配置"的理由对 **Pyroscope** 成立，对 **Glowroot 思路**不成立。

---

## 5. 对本仓：什么便宜可抄、什么别抄

**便宜可抄**

- 采集语义：**阈值门控 + profile 挂单条 trace**；密集采样用 `slowThresholdMillis=0` + 小 `profilingIntervalMillis`。
- 呈现：**trace 详情 → 火焰图页**的跳转与一个折叠栈渲染页。
- 线程统计用 `ThreadMXBean` 差值（JDK 自带，零依赖）。

**别抄**

- **async-profiler 路线**：GPL-2.0，与 Apache-2.0 的 agent 插件存在许可冲突；且 **Glowroot 自己也没用**。
- **"全 JVM 连续采样 + rollup"（Pyroscope 式）**：是另一条产品线，与本仓"OAP-less 单体、内存优先"的定位正交。

---

## 6. 未核实清单（不得当结论用）

1. **真正执行栈采样的类 / 包**。已确认 `agent/core/src/main/java/org/glowroot/agent/` 下**没有** `profiler` 包（子包为 `central` / `collector` / `config` / `impl` / `init` / `jul` / `live` / `model` / `util` / `weaving`）；采样实现应在 `collector` / `util` / `impl` 之一，**未逐层打开**。
2. profile 的**存储 schema 与保留策略**（H2 / Cassandra 侧）。
3. **"continuous profiling (with filtering)"** 在现行代码里的具体落点（是否为独立的 rollup 采样器）。
4. **wiki 类结论无法 pin commit**：GitHub wiki 是独立仓，`raw.githubusercontent.com/wiki/<org>/<repo>/<Page>.md` 不带 commit；本页 wiki 结论按 **2026-10-06 检索**采信。

---

## 附：来源清单

| 结论 | 类型 | 位置 |
| --- | --- | --- |
| 阈值门控 / 无采样百分比 / profiling interval | Wiki | `raw.githubusercontent.com/wiki/glowroot/glowroot/Transaction-configuration.md` |
| Profile 定义（trace 内、flattened stack samples） | Wiki | `.../wiki/glowroot/glowroot/How-to-read-a-Trace.md` |
| 明确不做 sampling %、临时阈值排障手法 | Wiki | 同上两页 |
| features 措辞（slow traces / continuous profiling / flame graphs） | 源码 | `README.md` |
| ThreadMXBean 差值（CPU/blocked/waited/allocated） | 源码 | `docs/jvm-thread-stats-allocated-memory.md` |
| 发行包**无** async-profiler；有 d3-flame-graph | Wiki/发行包 | `.../wiki/glowroot/glowroot/Third-Party-Software.md`、dist `NOTICE` |
| 虚拟线程丢 profile + `slowThresholdMillis=0` / `profilingIntervalMillis=50` | 源码 + Issue | `agent/vt-profile-smoke/README.md`、`agent/vt-profile-smoke/setup-agent.bat`、issue #1125 |
| embedded 无需额外部署 | Wiki | `.../wiki/glowroot/glowroot/Choosing-Embedded-vs-Central.md` |
