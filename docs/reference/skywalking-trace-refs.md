# SkyWalking refs 速览（TraceSegment 运行时对象 vs SegmentObject 上报对象）

> **用途**：讲清 SkyWalking 的 `refs` 到底是什么——分别针对**运行时的 `TraceSegment`**（segment 级父引用）与**序列化上报的 `SegmentObject`/`SpanObject`**（span 级 refs）。用于本插件（logfile-reporter）孤段过滤的诊断与"为什么插件只能看到 span 级 refs"的溯源。
> **核实基准**：
> - **Java Agent**：`apache/skywalking-java` @ `47ff1e82fad048c64cb73b83ee841941c279c482`（本地 checkout `8a1dae3` 同源，已逐文件比对）。
> - **proto**：`apache/skywalking-data-collect-protocol` @ master `language-agent/Tracing.proto`（本地 `apache/skywalking` checkout `b96cd37` 同源，字段一致；仅 master 多了 `SpanLayer.GenAI=7`，与本主题无关）。
> **证据类型**：`【源码】`= 打开上述 commit 源码逐行确认；本主题**无【实测】**。
> **已核实**：`TraceSegment.ref` 运行时语义与"不序列化"；`TraceSegmentRef.transform()`；`AbstractTracingSpan` 的 span 级 `refs` 与上限；proto `SegmentObject` / `SpanObject` / `SegmentReference` / `RefType` 字段。
> **未核实**（见 §8）：OAP 读取侧如何消费这些 refs（可参见 `skywalking-oap-trace-storage.md` §7 的查询期组装）。
> **勿凭印象**：本文之外关于 SkyWalking refs 的说法，一律当未核实处理。

---

## 0. 一句话结论

1. **refs 分两层**：**segment 级**（运行时 `TraceSegment.ref`，指向父 segment）与 **span 级**（每个 span 的 `refs`，跨进程/跨线程时承载父子关系）。
2. **segment 级 ref 不序列化**：`TraceSegment.transform()` 里明确写着 `// Don't serialize TraceSegmentReference`；proto `SegmentObject` **根本没有 ref 字段**。
3. 因此，**消费 `SegmentObject` 的插件（含本 logfile-reporter）只能看到 span 级 refs**：`SpanObject.getRefsList()` → `SegmentReference`。
4. span 级 refs 由 `AbstractTracingSpan.refs` 在 `transform()` 时逐个写进 `SpanObject.refs`（有去重与上限）。
5. `SegmentReference` 字段口径：`refType, traceId, parentTraceSegmentId, parentSpanId, parentService, parentServiceInstance, parentEndpoint, networkAddressUsedAtPeer`。

---

## 1. 两层 refs（总览）

| 层级 | 运行时类型 | 归属 | 进 `SegmentObject`？ | 插件可见性 |
| --- | --- | --- | --- | --- |
| **segment 级** | `TraceSegmentRef`（`TraceSegment.ref`） | 挂在 `TraceSegment` 上，指向**父 segment**（跨段） | **否**（`transform` 显式跳过） | **不可见**（transform 后丢失） |
| **span 级** | `List<TraceSegmentRef>`（`AbstractTracingSpan.refs`） | 挂在**每个 span** 上 | **是**（`SpanObject.refs`） | **可见**（`SpanObject.getRefsList()`） |

> 直觉：**segment 级 ref** = "我这个请求段是从哪个父段延续来的"；**span 级 refs** = "我这个 span 的上下文来自父段的哪个 span"。前者是运行时快捷字段，后者才是上报后用于拼父子链的数据。

---

## 2. 运行时对象：segment 级 `TraceSegment.ref`

类：`apm-agent-core/.../context/trace/TraceSegment.java`

```java
/**
 * The refs of parent trace segments, except the primary one. For most RPC call, {@link #ref} contains only one
 * element, but if this segment is a start span of batch process, the segment faces multi parents, at this moment,
 * we only cache the first parent segment reference.
 *
 * This field will not be serialized. Keeping this field is only for quick accessing.
 */
private TraceSegmentRef ref;

public void ref(TraceSegmentRef refSegment) {
    if (null == ref) {          // 只保留**第一个**父段引用
        this.ref = refSegment;
    }
}
```

要点（均来自源码）：

- **单值**：`ref` 是 `TraceSegmentRef` 而非列表；批量消费 MQ 时同一段可能有多个父，**只缓存第一个**（早期版本保留全部）。
- 注释原文即写明 **`This field will not be serialized`**——运行时快捷访问字段，不减负序列化。
- `relatedGlobalTraceId` 同理：批处理场景也只保留第一个（对应 traceId）。

---

## 3. 运行时对象：span 级 `AbstractTracingSpan.refs`

类：`apm-agent-core/.../context/trace/AbstractTracingSpan.java`

```java
/**
 * The refs of parent trace segments, except the primary one. For most RPC call, {@link #refs} contains only one
 * element, but if this segment is a start span of batch process, the segment faces multi parents, at this moment,
 * we use this {@link #refs} to link them.
 */
protected List<TraceSegmentRef> refs;

@Override
public void ref(TraceSegmentRef ref) {
    if (refs == null) {
        refs = new LinkedList<>();
    }
    // Provide the OOM protection if the entry span hosts too many references.
    if (refs.size() == Config.Agent.TRACE_SEGMENT_REF_LIMIT_PER_SPAN) {   // 默认 500
        return;
    }
    if (!refs.contains(ref)) {
        refs.add(ref);
    }
}
```

- span 级是**列表**，可多个（批处理 = 多父）；有**去重**与**上限** `TRACE_SEGMENT_REF_LIMIT_PER_SPAN`（`Config.java` 默认 `500`，防 OOM）。
- 序列化时逐个写入：`AbstractTracingSpan.transform()` 中

  ```java
  if (this.refs != null) {
      for (TraceSegmentRef ref : this.refs) {
          spanBuilder.addRefs(ref.transform());   // → SpanObject.refs
      }
  }
  ```

---

## 4. 序列化：`transform()` 故意丢弃 segment 级 ref

类：`TraceSegment.java`

```java
public SegmentObject transform() {
    SegmentObject.Builder traceSegmentBuilder = SegmentObject.newBuilder();
    traceSegmentBuilder.setTraceId(getRelatedGlobalTrace().getId());
    traceSegmentBuilder.setTraceSegmentId(this.traceSegmentId);
    // Don't serialize TraceSegmentReference          ← 关键：segment 级 ref 不进 SegmentObject

    for (AbstractTracingSpan span : this.spans) {
        traceSegmentBuilder.addSpans(span.transform());   // span 级 refs 随 span 进入
    }
    traceSegmentBuilder.setService(Config.Agent.SERVICE_NAME);
    traceSegmentBuilder.setServiceInstance(Config.Agent.INSTANCE_NAME);
    traceSegmentBuilder.setIsSizeLimited(this.isSizeLimited);
    return traceSegmentBuilder.build();
}
```

- **proto `SegmentObject` 也没有任何 ref 字段**（见 §5），即"丢弃"不只是实现选择，协议层也放不下 segment 级 ref。
- 结论：**任何只拿到 `SegmentObject` 的消费方，都拿不到 segment 级 ref**；span 级 refs 是唯一的跨段链路信息。

---

## 5. 上报对象：proto 字段（`Tracing.proto`）

### 5.1 `SegmentObject`（无 ref 字段）

| 字段 | 类型 | 编号 |
| --- | --- | --- |
| `traceId` | `string` | 1 |
| `traceSegmentId` | `string` | 2 |
| `spans` | `repeated SpanObject` | 3 |
| `service` | `string` | 4 |
| `serviceInstance` | `string` | 5 |
| `isSizeLimited` | `bool` | 6 |

### 5.2 `SpanObject.refs`（span 级 ref 的唯一入口）

```proto
repeated SegmentReference refs = 5;   // 跨线程/跨进程指向父段；通常 1 个，批量消费可多个
```

### 5.3 `SegmentReference`（字段口径）

| 字段 | 类型 | 编号 | 语义 |
| --- | --- | --- | --- |
| `refType` | `RefType` | 1 | `CrossProcess` / `CrossThread` |
| `traceId` | `string` | 2 | 整条 trace 的 id |
| `parentTraceSegmentId` | `string` | 3 | 父 segment id |
| `parentSpanId` | `int32` | 4 | 父段内的 span id |
| `parentService` | `string` | 5 | 父段服务名（CrossThread 时同本段） |
| `parentServiceInstance` | `string` | 6 | 父段实例名（CrossThread 时同本段） |
| `parentEndpoint` | `string` | 7 | 父段 endpoint（= 父段首个 entry span 名） |
| `networkAddressUsedAtPeer` | `string` | 8 | 客户端侧网络地址（STAM 拓扑分析用） |

### 5.4 `RefType` 与来源

- `RefType`：`CrossProcess = 0`（跨 OS 进程，`SpanObject.spanType` 通常为 Entry）、`CrossThread = 1`（同进程跨线程）。
- `TraceSegmentRef` 有两个构造器决定类型：`TraceSegmentRef(ContextCarrier)` → `CROSS_PROCESS`；`TraceSegmentRef(ContextSnapshot)` → `CROSS_THREAD`。
- `TraceSegmentRef.transform()` 生成 `SegmentReference`：`addressUsedAtClient != null` 才写 `networkAddressUsedAtPeer`（其余必填）。

---

## 6. 进入本插件的路径（为什么只看得到 span 级）

```text
OAP/上游：业务线程完成 segment
  │  TraceSegmentServiceClient.afterFinished(TraceSegment)
  ▼
本插件 LogFileTraceSegmentServiceClient.consume(List<TraceSegment> data)
  │  final List<SegmentObject> collect =
  │        data.stream().map(TraceSegment::transform).collect(...);   ← segment 级 ref 在此步丢失
  │  ... 孤段过滤（见 §7）...
  │  SegmentLogConverter.toLog(segment)
  ▼
Log.SpanInfo（字段：spanId / parentSpanId / operationName / startTime / endTime /
              spanType / spanLayer / componentId / isError / logsList / tagList / refs）
```

- segment 级 `raw.getRef()` 只在 `transform` **之前**、且只对运行时 `TraceSegment` 可用（`isOrphanSegment` 就用了它，见 §7）。
- 转换后仅剩 span 级：`SegmentLogConverter.toSpanInfo` 读 `span.getRefsList()`。
- **1.0.0 线的字段口径来源**：`git show 5d64e70` 的 `refsToRefList`（把 `SegmentReference` 摊平成 Map）：
  `refType / traceId / parentTraceSegmentId / parentSpanId / parentService / parentServiceInstance / parentEndpoint / networkAddressUsedAtPeer`。
  其中 `refType` 取 `ref.getRefType().name()`（即 `"CrossProcess"` / `"CrossThread"`）。
- **master（2.0.0）对应位置**：`SegmentLogConverter.toSpanInfo` + `Log.SpanInfo.refs`（本 note 落地后对齐 1.0.0 口径）。

---

## 7. 用途：孤段过滤是否"误伤"的判据

本插件 master 侧在 `consume` 里对 `SegmentObject` 做**孤段过滤**（`LogFileTraceSegmentServiceClient.isOrphanSegment(raw, obj)`）：

| 根 span 情况 | 判定 | 依据 |
| --- | --- | --- |
| 根 spanType = **Entry** | 合法事务，**不过滤** | 有 entry 段 |
| 根 Local/Exit 且 **有 ref** | 异步/跨进程子段，**不过滤** | `raw.getRef() != null`（段级），可并回父请求 |
| 根 Local/Exit 且 **无 ref** | 真孤段，**过滤** | 无 trace 上下文自建段，白占 traceId / H2 行 |

- 过滤判定用的是**运行时 `raw.getRef()`**（segment 级），不是 span 级；span 级 refs 是**过滤之后落库/缓存**用于**人工复核**的数据——把 refs 展示出来，才能事后确认某段到底是不是"真孤段"。
- 注意二者**语义相关但不同源**：段级 ref 在 `transform` 后消失；span 级 refs 才进 `SegmentObject`。诊断时看的是后者。

---

## 8. 未核实 / 边界

1. **OAP 读取侧消费**：本文只核到 agent 侧与 proto；OAP 用 `spanObject.getRefsList()` 在查询期组装父子（详见 `skywalking-oap-trace-storage.md` §7），未在本文重复核实。
2. **`networkAddressUsedAtPeer` 的 STAM 用法**：仅据 proto 注释，未展开。
3. **本地 checkout 版本**：Java Agent 本地 `8a1dae3`（2022-01-14）、proto 本地 `b96cd37`（2022-01-13）；已与上述上游 commit/仓库比对字段一致，但本地副本非最新。
4. **无实测**：全部结论来自源码，未在本环境抓取真实 `SegmentObject` 验证 refs 内容。

---

## 9. 源码锚点

### Java Agent（`apache/skywalking-java` @ `47ff1e8`）

| 主题 | 文件 |
| --- | --- |
| `TraceSegment`（segment 级 ref、`transform` 不序列化） | <https://github.com/apache/skywalking-java/blob/47ff1e82fad048c64cb73b83ee841941c279c482/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/context/trace/TraceSegment.java> |
| `TraceSegmentRef`（`transform()` → `SegmentReference`） | <https://github.com/apache/skywalking-java/blob/47ff1e82fad048c64cb73b83ee841941c279c482/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/context/trace/TraceSegmentRef.java> |
| `AbstractTracingSpan`（span 级 `refs`、`transform`、上限） | <https://github.com/apache/skywalking-java/blob/47ff1e82fad048c64cb73b83ee841941c279c482/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/context/trace/AbstractTracingSpan.java> |
| 上限默认值 `TRACE_SEGMENT_REF_LIMIT_PER_SPAN = 500` | <https://github.com/apache/skywalking-java/blob/47ff1e82fad048c64cb73b83ee841941c279c482/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/conf/Config.java> |

### proto（`apache/skywalking-data-collect-protocol` @ master）

- `language-agent/Tracing.proto`（`SegmentObject` / `SpanObject.refs` / `SegmentReference` / `RefType`）：
  <https://github.com/apache/skywalking-data-collect-protocol/blob/master/language-agent/Tracing.proto>

### 本项目（master）

- 转换入口：`agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/SegmentLogConverter.java`
- 展示对象：`.../logfile/Log.java`（`SpanInfo`）
- 孤段过滤：`.../logfile/LogFileTraceSegmentServiceClient.java`（`isOrphanSegment`、`consume`）
- 1.0.0 参考实现：`git show 5d64e70`
