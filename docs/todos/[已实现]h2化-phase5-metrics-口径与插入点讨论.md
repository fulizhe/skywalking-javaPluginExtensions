# H2 化 Phase 5：metrics 统计口径与插入点（讨论记录）

> **文档定位**：单体 + SW 采集背景下，"Glowroot 式统计"如何落地的**讨论记录**。与既有 `h2化-phase5-metrics讨论.md` / `h2化-phase5-metrics实现细化.md` 存在**口径分歧**（见 §7），待对齐；原讨论阶段标注为“未实现”，当前落地范围见下方备注。
>
> **状态**：**已采纳（2026-09-24）** —— a1 + **入口段插入点** 成为 Phase 5 的实现口径（配合 H2 内存模式 + 多分辨率）。权威 spec：`.scratch/h2-metrics/spec.md`；`duration` 取入口 span（本文倾向），非 `maxDurationMs`。
>
> **实现备注**：仅部分实现：a1 入口段统计、入口 span duration 与 `consume` 插入点已落地；本文其余 `refs` 拼链、链级完成判定等内容未全部实现。
>
> **相关**：`h2化-统一方案.md`（§4 Phase 5、§5.3、§11）、`h2化-phase5-metrics讨论.md`、`h2化-phase5-metrics实现细化.md`、`docs/reference/sw-glowroot-cat-metrics-implementations.md`。

---

## 0. 起点与两个担心

**背景**：单体应用；采集/存储选 **SkyWalking 式**（段流 + payload 封顶文件）；统计想要 **Glowroot 式**（进程内、按事务算、预聚合、含分位数）。

**担心**：
1. `mergeLogIntoStatMap` 是自研的，**完备性**存疑。
2. SW 的 `consume` 是**批量**处理，不像 Glowroot 那样"按 trace 归并为同一完整链"，担心统计不准。

---

## 1. 概念纠正：async **不会**并入同一条 TraceSegment

- SW 跨线程传播 = `TraceSegmentRef.refType = CROSS_THREAD`；跨进程 = `CROSS_PROCESS`。**两者都产新段**，traceId 相同、`ref` 指回父段的某个 `spanId`。
- 一个 segment = **一个线程在一次 trace 上下文中的执行片段**（span 集合）。同一线程先后处理多个请求 → 多条段；同线程内嵌套插桩（Service→DB→Cache）→ **共享**同一条段，靠 `parentSpanId` 串树。
- 因此"**把多段拼成一条链**"本来是 **OAP** 的活；本地无 OAP，必须自己补。

---

## 2. `mergeLogIntoStatMap` 实际干了什么

"拼成一条链"其实是两件事：

| | 内容 | 现状 |
|---|---|---|
| **归集（分组）** | 把同 `traceId` 的段放进同一个桶 `{ logs: [seg, seg, ...] }` | ✅ 已干 |
| **拼链（建结构）** | 用 `refs` 把段连成父子关系 / 树 | ❌ 未干 |

**`refs` 被丢弃**：`SegmentLogConverter.toLog`（`SegmentLogConverter.java:26`）只搬 `traceId/traceSegmentId/service/serviceInstance/isSizeLimited/spans`；`Log.toMap`（`Log.java:92`）也没有 refs。所以现在得到的是"同一 traceId 的段**并排列表**"，不是"有结构的链"——跨段父子归属缺失，消费方（`TraceEvaluator` / dashboard）只能靠时间或 `parentSpanId` 粗排。

**定位**：它是"**trace 视图 + 跨段告警**"的粗归集（OAP 的前半），**不是事务统计的事实底座**。

**已知不完备清单**：
1. `afterTraceMerged` **每段触发**（`AsyncTraceAlertDispatcher.java:97`），半链先到即评估，`notifiedFlags` 去重后**不再重评** → 后到的出错段被吞。
2. 有界 FIFO 淘汰（`KeyedLocalStore.java:37,61`，默认 1000）→ 中途淘汰会留下**孤儿段**。
3. DataCarrier 跨 channel **不保序**。
4. **没有"链完成"事件**。

---

## 3. 完成信号：Glowroot 有，SW 没有

- **Glowroot**：`Transaction.end()` —— 一个对象、一个时刻，事务即完整，**天然完成边界**。
- **SW**：一个 trace = N 段，"最后一段到齐"**无任何标记**；合并是流式、最终一致的，中间态可见。
- **但**：SW 只在段内所有 span 关闭后才产出段 → **段级有完成信号**（"段到达" = "这一段完成了"）；**链级没有**。

---

## 4. (a) 事务级 vs (b) 整链级

| | (a) 事务级 | (b) 整链级 |
|---|---|---|
| **统计对象** | 一条**含 Entry span 的段**（自包含） | 同 traceId 的**所有段** |
| **何时算** | 段被消费时（= 段完成信号） | 必须判定"链收齐"——**不可判定** |
| **完成推断** | **不需要** | 需要（入口段 + 宽限 Δ，或 `max(endTime) < now-Δ` 时间水位） |
| **代价** | 不含跨线程异步子段（范围界定，非偏差） | Δ 小→漏晚到段；Δ 大→延迟 + 内存 |
| **依赖 merge** | **不依赖** | **依赖**（继承其全部不完备） |
| **锚点** | = SW OAP 的 `service_resp_time`（按 entry span）；= Glowroot 事务口径 | = trace 视图 / 全链分析 |

结论：单体 + Glowroot 诉求 = **(a)**，天然精准且绕开 `mergeLogIntoStatMap`。

---

## 5. a1 vs a2（"什么算一次事务"）

| 判据 | a1 | a2 |
|---|---|---|
| **定义** | 段内含 Entry span（`spanType=Entry && parentSpanId=-1`） | 只要**根段**（segment `refs` 为空） |
| **等价** | Glowroot 进程入口口径 = SW service 指标口径 | 整条分布式链的起点 |
| HTTP 进单体 | ✅ | ✅ |
| `@Async` 子段（根 span=Local） | ❌ | ❌ |
| **MQ 消费（消息来自外部）** | ✅ | ❌（**丢数据**） |
| 定时 / 内部发起 | ✅ | ✅ |

**结论：单体下选 a1，a2 弃。** 理由：唯一分叉是 MQ 消费，而 a2 在那里是**丢数据**；a2 仅多服务（"链根是谁"）才有意义。a2 唯一名义价值（防异步子段根 span 为 Entry 导致 a1 多计）风险极低（标准跨线程传播子段根 span 是 `Local`）。

---

## 6. 插入点：语义在 `afterFinished`，落点在 `consume`

- **语义完成点**确实是 `afterFinished(TraceSegment)`（每个段完成回调），且段到这里已**不可变、完整**。
- **但不在 `afterFinished` 里算**：它跑在**业务线程**，任何计算/IO 直接加到请求延迟。Glowroot 在 `Transaction.end()` 只入队、聚合在 `TransactionProcessor`；SW 自己在此也只 `carrier.produce`（`LogFileTraceSegmentServiceClient.java:433`）。
- **落点 = `consume(List<TraceSegment>)`**（DataCarrier 异步批量消费线程），即现在 `mergeLogIntoStatMap` / `accept` 所在处：异步、已离开业务线程、段完整、逐段循环现成。

```text
consume(data):
  for seg in data:
     so  = seg.transform()
     log = toLog(so);  mergeLogIntoStatMap(log)   // 既有：trace 视图
     metricsAggregator.onSegment(so)              // ← (a1) 新增：事务指标
  traceSegmentStorage.accept(data)                // 既有：H2 持久化
```

`onSegment(so)`（**(a1)**）：判定入口 → 取 `duration = 入口 span (endTime - startTime)`、`error = 段内任意 span isError`、`endpoint = 入口 operationName`、`时间 = 段 startTime`（SW 单位**毫秒**）→ 并入内存分钟桶 + 直方图。**直接用 `SegmentObject`**（有 `refs`/`spanType`，不经被丢信息的 `Log`）。

---

## 7. 与既有讨论稿的关系（待对齐）

| 点 | 既有 `h2化-phase5-metrics讨论.md` | 本讨论稿 |
|---|---|---|
| 挂钩点 | **合并视图事件** `afterTraceMerged` | **入口段**（`consume` 内，(a1)） |
| 计数口径 | 同桶 `traceId` 去重（trace 一次/分钟桶） | 每个入口段一次（a1） |
| 耗时口径 | `maxDurationMs`（与告警同源） | 倾向**入口 span**（Glowroot/SW 口径） |
| 分位数 | v1 全样本排序 / v2 直方图 | 同 |
| 落库 | 内存分钟桶 + 整分 upsert + 复用写线程/TTL | 同 |

**分歧焦点**：既有稿把统计挂在**合并视图**上；本稿认为合并视图不完备（§2），会把"半链 / 淘汰 / 去重"的不确定性带进数字，主张改挂**入口段**。**duration 与挂钩点均待拍板。**

---

## 8. 待决 / 下一步

1. **定口径**：挂钩点 = 入口段（a1）？duration = 入口 span 还是 `maxDurationMs`？
2. 出 (a1) 的**落库表结构草案**（分钟桶 + 直方图）。
3. 是否保留 `refs`（补上"拼链"后半，供链路树展示）。
4. 与既有稿**合并 or 标注分歧**。

---

## 9. 实证补充：跨线程 ⇒ 新段（`@CrossThread` / servlet async）

**问题**：`afterFinished(TraceSegment)` 里的段，是否包含"整条链路"？—— **否**。每次回调只给**一条** segment，且只含产生它的那个线程在一次 trace 上下文里的 span；一条 trace 有 N 条段 ⇒ 触发 N 次回调，任何一次都拿不到整链。

**实证（本机 agent 9.4.0 反编译）**：跨线程包装（`@TraceCrossThread` / `RunnableWrapper` / `CallableWrapper`）由 `CallableOrRunnableInvokeInterceptor` 实现，其 `beforeMethod` 调用：

- `ContextManager.createLocalSpan(...)`
- `ContextManager.continued(ContextSnapshot)` —— 在**工作线程**上续接父上下文 ⇒ **新建 segment**（带 ref 指回父段的 `spanId`）。

| 回调 | 段内 span |
|---|---|
| 入口段（Tomcat 线程）`afterFinished` | 入口 span + 该线程**同步**工作；**无**异步线程 span |
| 异步段（线程池线程）`afterFinished` | 异步 Local span（**独立**触发，可能晚很多） |

**Servlet 3 async 的特殊点（待实测）**：agent 里存在 `ContextManager.awaitFinishAsync(AbstractSpan)` ⇒ SW 对 servlet 异步**有专门支持**，**入口 span 保持到异步完成才结束**，故**入口段 duration 可覆盖整个异步请求**。但"异步线程新产生的 span 落在哪条段"取决于容器重新 dispatch 时上下文如何恢复——**本稿不断言，需实测确认**。

**对 (a1) 的影响**：

- 这是 (a1) 的论据：回调本就**逐段**，就应在**入口段**上算事务指标，而非指望某次回调拿到整链。
- 待确认：**入口 span 的 duration 是否覆盖异步等待**——servlet-async（`awaitFinishAsync`）覆盖；纯线程池 fire-and-forget **不覆盖**（入口段随 HTTP 线程返回即结束），与 Glowroot 把 async 单独归属同义，非误差。
- "整链"只有**合并后**才存在；merge 可归集段，但跨段父子结构**缺 `refs`**（§2）。

**自验方法（用现有工具，不靠文档）**：跑 `/helloAsync3`（`HelloService.java:172-180` 的 `@TraceCrossThread`）与 **`/helloAsyncServlet`**（Servlet 3 `request.startAsync()` + 子线程 `@TraceCrossThread`，`HelloController.java:131-139` 与 `:223-247`；本次新增）→ `queryTrace(traceId).logs` 看**段条数**、看各段 span 的 `spanType`（异步 Local span 是**在入口段内**还是**自成一段**）。

> **demo 样例**：`HelloController` 原有 `/helloAsync`（Spring `@Async`）、`/helloAsync2`（`RunnableWrapper`）、`/helloAsync3`（`@TraceCrossThread` + 裸线程），**缺 Servlet 3 async**；本次补 `/helloAsyncServlet`，代表"Servlet 3 async + 跨线程续接"这条链路。
>
> **复现 / 调试步骤**：见 [异步链路调试说明](<[已实现]h2化-phase5-metrics-异步链路调试说明.md>)。

---

## 附：本讨论涉及的关键代码锚点

| 主题 | 位置 |
|---|---|
| 段完成 → 入队 | `LogFileTraceSegmentServiceClient.java:433` |
| 批量消费 → 逐段归并 | `LogFileTraceSegmentServiceClient.java:294-308` |
| 按 traceId 归并（自研） | `LogFileTraceSegmentServiceClient.java:376-398` |
| 每段触发告警评估 | `AsyncTraceAlertDispatcher.java:97` |
| `refs` 丢弃 | `SegmentLogConverter.java:26` · `Log.java:92` |
| 有界 FIFO 淘汰 | `KeyedLocalStore.java:37,61` |
| H2 按 traceId 聚回整链 | `H2TraceSegmentStorage.java:498` |
| 跨线程续接 → 新段（`continued`） | agent `activations/apm-toolkit-trace-activation-9.4.0.jar` · `CallableOrRunnableInvokeInterceptor` |
| servlet-async 保持入口 span | agent `skywalking-agent.jar` · `ContextManager.awaitFinishAsync` |
| `@TraceCrossThread` demo 用法 | `HelloService.java:172-180` |
| Servlet 3 async demo 样例（新增） | `HelloController.java:131-139`（`/helloAsyncServlet`）· `:223-247`（`AsyncServletTask`） |
