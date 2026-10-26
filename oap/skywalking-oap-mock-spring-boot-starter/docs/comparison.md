# 同类方案对比

> 检索时间：2026-10-24　｜　检索方式：GitHub Search / Repos API
> 活跃判定：`pushed_at` 在 2026-04-24 之后（近半年内有推送）
> 用途：说明本 starter 与既有方案的关系与边界，便于使用者判断该选哪个。

---

## 一、最接近的一个：SkyWalking 官方 mock-collector

**[apache/skywalking-agent-test-tool](https://github.com/apache/skywalking-agent-test-tool)**

| 项 | 值 |
|---|---|
| Stars | 36 |
| 许可证 | Apache-2.0 |
| 最后推送 | 2026-08-01 |
| 语言 | Java |

它的 `mock-collector` 模块，官方描述是：

> The `mock collector` is a SkyWalking receiver, **like OAP server**. It receives data from agent through `GRPC`.

也就是说，**"用 SkyWalking 协议实现一个 OAP 接收端"这件事，官方自己做过了**。本 starter 不是这个思路的首创者，这一点应当说清楚。

### 但它和本 starter 不是一回事

| 维度 | 官方 mock-collector | 本 starter |
|---|---|---|
| 运行位置 | 独立进程（`bin/collector-startup.sh`） | 寄居在既有业务 JVM 内 |
| 目的 | Agent 端到端测试 | 生产/现场排障 |
| 数据归宿 | 导出 YAML → 交给 validator 断言 | 进程内有界缓存 → 供宿主 UI 查询 |
| gRPC 实现 | 标准 `io.grpc` | Agent 自带的 shaded gRPC |
| 示例端口 | `19876`（测试专用口） | `11800`（对齐 Agent 默认值） |
| 链路拼装 | 不拼，按 service 归堆 | 按 `traceId` + Reference 拼跨进程树 |
| 查询 UI | 无 | 由宿主应用提供 |
| 数据保留 | 全量收集后导出 | 有界滚动窗口，超出即淘汰 |

**分水岭在运行位置，不在协议。**

官方 collector 跑在业务进程之外，Agent 的插件不会埋点它，因此可以放心使用标准 `io.grpc`。

本 starter 跑在业务进程之内，如果使用标准 `io.grpc`，SkyWalking Java Agent 的 `grpc-1.x` 插件会把接收端自身也埋点，形成"收一个段 → 生成一个 span → 组成新段再发回来"的自激循环，真实链路会被挤出缓冲。这就是本 starter 必须依赖 Agent shaded gRPC（`org.apache.skywalking.apm.dependencies.io.grpc.*`）的原因。

一句话概括：

> **同样的协议接收端，官方那个跑在进程外做验证，本 starter 跑在进程内做排障。位置不同，技术栈就是反的。**

### 官方实现覆盖了更多 service

官方 mock-collector 实现了 `TraceSegment` / `Log` / `JVMMetric` / `CLRMetric` / `Meter` / `Event` / `Management` 七个上报服务，并带有一套旧协议 `compat` 实现，覆盖面比本 starter 更广。

本 starter 只实现四个：`TraceSegmentReportService`、`LogReportService`、`JVMMetricReportService`、`ConfigurationDiscoveryService`。这是刻意的边界——多实现一个，就多一份内存占用与兼容负担，而"看现场"并不需要 Meter / Event / CLR。

### 可直接借鉴的一处修复

官方在 **2026-08-01** 修复了：

> `fix: segments/logs lost when two processes first report for the same service`

即**多个进程首次上报同一 service 时的注册竞态**。本 starter 目前不实现 `Management` / 注册服务，只依赖 `SegmentObject` 自带的 `service` 字段，因此暂时绕开了这个问题；若后续需要按服务聚合统计，这处修复值得先读。

---

## 二、嵌入式 APM：形态相同，协议不同

| 项目 | Stars | 最后推送 | 许可证 |
|---|---|---|---|
| [glowroot/glowroot](https://github.com/glowroot/glowroot) | 1360 | 2026-10-19 | Apache-2.0 |
| [javamelody/javamelody](https://github.com/javamelody/javamelody) | 3043 | 2026-10-10 | Apache-2.0 |

两者都是"嵌进业务进程、零外部依赖"的成熟方案，与本 starter 的部署形态一致。

差别在于**采集来源**：它们使用各自的内置采集器与 Agent，只能看到 Java 一侧。如果团队已经在多个语言上统一使用 SkyWalking Agent，换成它们意味着放弃既有的跨语言探针，重新引入一套独立的 Java 专用采集。

本 starter 不采集，只接收。它复用已有的 SkyWalking 探针，因此多语言体系不需要任何改造。

---

## 三、嵌入式 OTLP collector：设计哲学高度一致

**[risingwavelabs/otlp-embedded](https://github.com/risingwavelabs/otlp-embedded)**

| 项 | 值 |
|---|---|
| Stars | 6 |
| 许可证 | Apache-2.0 |
| 最后推送 | 2026-02-09（已超出半年活跃线） |
| 语言 | Rust |

描述：*A simple in-memory implementation of the OpenTelemetry trace collector with a Web UI for visualizing the traces that can be embedded into other Rust applications.*

它的配置项与本 starter 的限额设计几乎同构：

```rust
State::new(Config {
    max_length: 100,              // 条数上限
    max_memory_usage: 1 << 27,    // 128 MiB 内存上限
})
```

**条数 + 字节双限额、可嵌入宿主进程、自带 Web UI** —— 三件事与本 starter 的选择一致，只是它面向 OTLP、使用 Rust，且目前已不再活跃。

值得参考的点：它证明"嵌入式 + 内存有界 + 自带 UI"这组设计是被独立验证过的；也说明维持这类项目的长期活跃并不容易。

---

## 四、重型对照：DataBuff

**[databufflabs/databuff](https://github.com/databufflabs/databuff)** — 711★，2026-10-06 推送，Java。

Docker 一键部署的完整 APM 后端（OTLP 摄取 + 前端 + 拓扑 + AI 分析），与本 starter 的定位正好相反：它要替代 OAP，本 starter 要避开 OAP。

**许可证需自行核实**：仓库 `LICENSE` 文件内容为 **AGPL-3.0**，而 README 顶部的 badge 标注为 **Apache-2.0**，两者不一致。AGPL-3.0 对通过网络提供服务的使用方式有较强的开源义务要求，集成前请以 `LICENSE` 原文为准。

> 附带一条通用经验：核实开源许可证要读 `LICENSE` 文件本身，README 的 badge 可能过时或标注错误。

---

## 五、位置总结

| 能力 | 官方 mock-collector | otlp-embedded | Glowroot | JavaMelody | 本 starter |
|---|:--:|:--:|:--:|:--:|:--:|
| 复用原生 SkyWalking 探针（Agent 零改动） | ✅ | ❌ | ❌ | ❌ | ✅ |
| 寄居业务进程内 | ❌ | ✅ | ✅ | ✅ | ✅ |
| 有界内存缓存 | ❌（全量攒后导出） | ✅ | ✅ | ✅ | ✅ |
| 跨进程链路拼装 | ❌ | ❌（单协议） | ❌ | ❌ | ✅ |
| 自带查询 UI | ❌ | ✅ | ✅ | ✅ | ❌（宿主提供） |
| 近半年活跃 | ✅ | ❌ | ✅ | ✅ | — |

同时满足「复用原生探针 + 寄居进程内 + 跨进程拼装」三个条件的公开项目，在本次检索范围内没有找到第二个。

这个空白存在的原因不是"没人想到 Mock OAP"，而是"把接收端塞进被监控的进程里，还要它不自己监控自己"——这一层是真正的门槛。

---

## 六、检索留痕

搜索关键词：`skywalking embedded`、`skywalking local collector`、`mock oap`、`skywalking otlp`、`embedded apm java`、`in-memory trace collector`、`lightweight apm`、`local trace storage`。

其中 `skywalking embedded` 与 `skywalking local collector` 的返回结果为 **0**；`mock oap` 仅 3 条，除官方 mock-collector 外均不相关。

说明"SkyWalking + 嵌入式接收端"这一组合在 GitHub 上尚无命名沉淀，本项目的命名（`oap-mock` / `mock-oap`）在检索上具备一定可辨识度。

数据核实方式：GitHub Repos API 取 `pushed_at`、`license`、`stargazers_count`；许可证以仓库 `LICENSE` 文件原文为准。
