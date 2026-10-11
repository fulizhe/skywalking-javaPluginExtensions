# [待办] RPC 调用排行 — 出站、按方法、进程累计

> 范围：`agent/logfile-reporter-plugin` + `agent/demo-app`
> 定位：本文件是 `[待办]可扩展空间-按可观测性价值排序.md` 中 **P2-2（指标维度单一）** 的落地项；对应 `[待办]覆盖DB与Threadpool监控-按SkyWalking实现.md` §1.3 **路径 B「TopN」** 的 RPC 兄弟项。
> 前提（硬约束）：**单 JVM、断 OAP 的本地探针**——只答"这一个进程往外调了哪些 RPC 方法"，不做跨实例 / 服务地图。
> 目的：监控本进程 RPC 调用情况——**审计优化自身调用**（如同步转异步）、并为对外沟通 / 对峙留存**可下钻的证据**。
> 状态：**待办（讨论中，今日不执行）**。方案已随本轮讨论收敛（见 §3 + §3.1），**UI 形态已用原型定稿（方案 A）**；spec 待转。

---

## 0. 一句话

把本 JVM **出站**的 RPC span（`spanLayer = RPCFramework`）按 **`componentId` + `operationName`** 进程累计，
出一个**独立页面** `rpc-topn.html`：调用次数 / 错误率 / P50·P95·P99 / 最大耗时 / 首末见，**默认 P95 降序**，排序键可切。
点某一行可下钻看该方法的**证据样本**（最慢 1 条 + 随机 4 条，各带 `traceId` → 链路）。

---

## 1. 现状与缺口（代码印证）

| 维度 | 现状 | 缺口 |
|---|---|---|
| 入口端点 | `TraceMetricsAggregator` 按入口端点累计，落 H2（分钟+小时） | —（已有：指标大屏 / 慢调用榜） |
| 依赖组件 | `EdgeMetricsAggregator` 边 = 入口端点 × 组件；`EdgeLifetimeRow` 组件级进程累计 | 只能答"调了 gRPC 多少次" |
| **RPC 方法** | 出口 `operationName` 仅作为**每条边上限 8 条的去重集合**存在，**无按方法计数/分位** | **答不出"哪个 RPC 方法最慢 / 最多 / 最错"** |
| **RPC 证据** | 端点级有 `extremeTraces`（每端点最大耗时那次的 traceId）；RPC 方法级**无任何实例留档** | **答不出"最慢那次调用长什么样、链在哪"** |

> 入站 RPC（本 JVM 当服务端）会以 Entry 形式进 endpoint 指标——**已在慢调用榜里**；本项补的是**出站**。

---

## 2. 落点判断（决定工作量量级）

- **不写拦截器**：上游 `apm-grpc-1.x` / Dubbo 等插件已产出口 span（`RPCFramework` 层）；本 fork 缺的只是**本地消费层**。
- **复用**：喂入点与依赖边聚合**并列**（同一段消费流程）；只读口 / 页面沿用既有套路（`SWMetricsUtils` + 拦截器 + `dashboards/`）。
- **新开聚合器**：不改 `EdgeMetricsAggregator` 的键与内存模型——RPC 方法基数远低于「端点 × 组件」交叉积，可给**更大的分位样本池**让分位更准。
- **独立但不过早抽象**：整块 RPC 分析独立成 `RpcMetricsAggregator`（平铺 `metrics/`，不加子包 / 接口）；证据保留先以**独立方法硬编码**，`RpcSampleSelector` 可插拔留作后续（Q13）。

---

## 3. 已对齐决策（grill 结论 + 本轮讨论）

| # | 决策 | 结论 |
|---|---|---|
| Q1 | 口径 | 只算**出站** `RPCFramework` span（GRPC / Dubbo / …） |
| Q2 | 粒度 | 一行 = `(componentId, operationName)`；带 `componentId` 防跨框架方法名撞车 |
| Q3 | 指标 | 次数 / 错误率 / **P50·P95·P99** / 最大耗时 / 首末见；**默认 P95 降序**，排序键可切（**分位保留**，见 §3.1） |
| Q4 | 时序 | **进程累计**（自启动以来），纯内存、**不落库**、重启即失 |
| Q6 | 性质 | **纯新增**，无 bug 要修 |
| Q7 | 版本线 | **不同步** `release/1.0.0`（纯 2.0.0 功能） |
| Q8 | 落点 | **新开独立聚合器**（不动既有依赖边键与语义） |
| Q9 | UI | **独立页** `rpc-topn.html`；形态 = **方案 A：排行表 + 右侧证据抽屉**（原型 `rpc-topn-prototype.html?variant=A` 定稿）；无时间窗档，标签写死"自启动以来" |
| Q10 | 读口 | **独立读口** `SWMetricsUtils.rpcTopn()`，**拆两动作**：列表（聚合行）/ 单行明细（带证据样本）；证据**按需拉**；宿主补组件名 + 累计起点 |
| Q11 | 上限 | 方法 **512**（超出并入 `(other)` + 计数）/ 分位样本池 **2048** / **证据集每方法 ≤5** / 组件名三级 fallback |
| Q12 | 证据下钻 | 每方法留 **最慢 1 条 + 随机 4 条**（`≤5`），各带 `duration / 时点 / traceId / isError`；点行才拉，页面下钻 `trace-view.html?traceId=` |
| Q13 | 抽象节奏 | 分析逻辑**独立成类**（`RpcMetricsAggregator`）；证据保留**先以独立方法硬编码**（不抽接口）；`RpcSampleSelector` 可插拔**记录为后续 TODO**，本期不做 |
| Q14 | 借鉴边界 | 借 SkyWalking slow-db-statement 的"**典型实例 + 下钻**"（见 §6）；**不取**其阈值门控 / 周期持久化 |

### 3.1 两个存储：分位与证据分开

| 存储 | 粒度 | 内容 | 用途 | 成本 |
|---|---|---|---|---|
| **分位样本池** | 每方法 2048 条 | 仅 `duration` | 算 P50 / P95 / P99 / 最大（Q3） | 512 × 2048 × 8B ≈ 8MB 上界 |
| **证据集** | 每方法 ≤5 条 | `duration / 时点 / traceId / isError` | 点行下钻（Q12） | 可忽略 |

> 刻意**不合并**：把 `traceId` 塞进 2048 条池子会让每方法多出数十 KB × 512，代价不成比例。
>
> 本轮（2026-10-11）讨论补充：曾评估"用 1-5 条典型实例**取代**分位池"，结论是**不取代**——分位（排行）与证据（下钻）是两种产物，两份都留。

---

## 4. 口径边界（须写进页面与读口，不可静默）

| 边界 | 含义 |
|---|---|
| 孤段不计 | 喂入在段消费的**孤段过滤之后**——无 Entry 且无 ref 的"无上下文 RPC"（后台 / 保活）**不出现**在榜里 |
| 只做段内配对 | **不跨段**（继承依赖边的取舍，理由见 `2026-09-30-edge-aggregator-tradeoffs.md`） |
| 入站不计 | 本 JVM 当 RPC 服务端的 Entry 不进这张榜（已在 endpoint 指标里） |
| 累计口径 | 不跟时间窗走；标签写死"自启动以来" |
| 证据为抽样 | 证据集 = "最慢 1 + 随机 4"的**抽样**，非全量；读口如实给样本，页内不得当全量读 |

---

## 5. 落地顺序（草案）

1. **数据链路**：`RpcMetricsAggregator`（方法键 / 分位池 2048 / 证据集 ≤5 硬编码 / 上限 512 / 健康计数）+ 喂入点 + 只读口（列表）+ 聚合器单测；跑通到页面（次数 / 错误率 / 分位）。
2. **证据下钻**：证据集（最慢 1 + 随机 4）+ 单行明细读口 + 前端**方案 A**（表 + 抽屉）+ 下钻 `trace-view` + 前端纯逻辑冒烟。
3. **口径收口**：首末见（含"距启动多久"）、组件名三级 fallback、空态、页内口径文案、导航入口、演示动线、`checks.sh` 端到端断言。

---

## 6. 参考

- `docs/todos/[待办]可扩展空间-按可观测性价值排序.md`（上级：P2-2 指标维度单一）
- `docs/todos/[待办]覆盖DB与Threadpool监控-按SkyWalking实现.md`（§1.3 路径 B，RPC 兄弟项）
- `docs/notes/2026-09-30-edge-aggregator-tradeoffs.md`（只做段内配对 / 组件名留宿主侧）
- `docs/notes/2026-09-30-exit-span-runtime-probe.md`（`operationName` 已归一化，不需自写规则）
- `docs/reference/skywalking-component-icons.md`（gRPC 实测名 = 大写 `GRPC` / 层 `RPCFramework`）
- UI 定稿原型：`agent/demo-app/src/main/resources/static/dashboards/rpc-topn-prototype.html`（方案 A；**抛砖件，定稿后删**）
- SkyWalking `Slow Database Statement`（借鉴"典型实例 + 下钻"，不取阈值门控 / 周期持久化）：https://skywalking.apache.org/docs/main/next/en/setup/backend/slow-db-statement/
- 代码：`metrics/TraceMetricsAggregator.java`、`metrics/EdgeMetricsAggregator.java`、`metrics/EdgeLifetimeRow.java`、`LogFileTraceSegmentServiceClient.java`、`plugin/logfilereporter/MetricsExposeInterceptor.java`、`agent/demo-app/.../dashboards/slow-topn.html`
