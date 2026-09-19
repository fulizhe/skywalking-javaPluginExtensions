# SkyWalking Java Agent 9.4 本地 Trace / Log 存储演进方案

> 本文用于记录 `skywalking-javaPluginExtensions` 项目中本地 Trace / Log 数据采集与存储能力的长期演进方案。
>
> 当前项目基于 **Apache SkyWalking Java Agent 9.4**，现有核心入口为 `SWLogfileReporterUtils`，内部通过 `LogReportServiceLocalClient` 管理本地报告数据。
>
> 本方案的核心目标不是重新实现一套 SkyWalking OAP，而是在**保持现有能力稳定、尽量少改代码**的前提下，逐步增加：
>
> - 本地 Memory Trace / Log 缓存
> - H2 持久化
> - Normal / Slow / Error 分类
> - 历史 Trace 查询
> - 数据 Retention
> - 后续 Metrics
>
> 整体设计强调：
>
> **小步演进、最少代码、Native Type 边界、避免 ClassLoader 风险、尽量复用 SkyWalking 9.4 已验证的设计。**

------

# 1. 项目定位

当前项目并不是一个独立的 APM Server，也不是 OAP 的替代实现。

当前更准确的定位是：

> **基于 SkyWalking Java Agent 的 Local / Embedded Observability 能力。**

目标是在应用内部提供：

```text
Agent
  │
  ├── Trace / Log 捕获
  ├── 本地 Memory 缓存
  ├── H2 历史存储
  ├── Error / Slow 分类
  ├── 本地查询
  └── 后续 Metrics
```

而不需要：

```text
OAP Server
Elasticsearch
BanyanDB
独立 HTTP Server
独立 Query Service
```

------

# 2. 当前真实架构

当前项目真正的 Business ↔ Agent 入口是：

```text
SWLogfileReporterUtils
```

而不是 `TraceContext`。

当前核心关系：

```text
Business Code
      │
      │ SWLogfileReporterUtils
      ▼
SkyWalking Java Agent 9.4
      │
      ▼
LogReportServiceLocalClient
      │
      ▼
CircularBlockingQueue
      │
      ▼
Memory
```

其中 `SWLogfileReporterUtils` 提供轻量级本地 API。

目前核心 API 包括：

```java
public static void enableReport(Map<String, Object> config)

public static void disableReport(Map<String, Object> config)

public static Map<String, Object> statisticStatus()
```

------

# 3. SWLogfileReporterUtils 的职责

`SWLogfileReporterUtils` 应继续保持为一个非常轻量的 Business-facing API。

当前 API 可以理解为两个方向：

```text
SWLogfileReporterUtils
        │
        ├── 控制面
        │     ├── enableReport()
        │     └── disableReport()
        │
        └── 数据面
              └── statisticStatus()
```

即：

### 控制

```java
enableReport(config)
disableReport(config)
```

负责：

- 开启本地报告
- 关闭本地报告
- 传递运行时配置

### 数据

```java
statisticStatus()
```

负责：

- 获取当前本地统计快照
- 获取缓存中的数据
- 将 Agent 内部数据以 Native 类型返回

------

# 4. 当前 API 的重要设计原则

`SWLogfileReporterUtils` 的 API 不应该直接暴露 Agent 内部对象。

例如不建议：

```java
Trace
Span
TraceRecord
SpanRecord
H2Record
LogStorage
```

直接作为返回值或参数。

优先使用：

```text
String
Long
Integer
Boolean
Map
List
Set
byte[]
```

等 JDK / Bootstrap ClassLoader 可见类型。

例如：

```java
Map<String, Object>
```

是合理的边界类型。

------

# 5. 为什么要严格控制 ClassLoader 边界

SkyWalking Java Agent 本身存在独立的 ClassLoader 体系。

如果 Agent 内部定义：

```java
class TraceRecord {
}
```

然后 Business Code 直接接收：

```java
TraceRecord record
```

就可能出现：

```text
ClassNotFoundException
NoClassDefFoundError
LinkageError
ClassCastException
```

等 ClassLoader 问题。

因此本项目必须遵循一个硬性原则：

> **Agent 自定义类型可以存在于 Agent 内部，但不能直接跨越 Business ↔ Agent 边界。**

------

# 6. Business ↔ Agent 边界

推荐保持：

```text
┌─────────────────────────────────┐
│          Business Code          │
│                                 │
│     SWLogfileReporterUtils      │
└───────────────┬─────────────────┘
                │
                │ JDK / Native Types
                │
                ▼
┌─────────────────────────────────┐
│      SkyWalking Agent 9.4       │
│                                 │
│ LogReportServiceLocalClient     │
└───────────────┬─────────────────┘
                │
                │ Agent Internal Types
                ▼
┌─────────────────────────────────┐
│        Storage Layer            │
│                                 │
│ Memory / H2 / Repository        │
│ TraceRecord / SpanRecord        │
└─────────────────────────────────┘
```

因此：

```text
Business
    │
    │ Map / List / String / Long
    ▼
Agent
    │
    │ Agent internal model
    ▼
Storage
```

这是后续所有设计的基础约束。

------

# 7. 当前 LogReportServiceLocalClient

当前真正承担本地数据管理职责的是：

```text
LogReportServiceLocalClient
```

它位于：

```text
SWLogfileReporterUtils
        │
        ▼
LogReportServiceLocalClient
        │
        ▼
CircularBlockingQueue
```

也就是说：

> `SWLogfileReporterUtils` 是外部入口，`LogReportServiceLocalClient` 是内部实现核心。

后续 Storage 抽象应该放在：

```text
LogReportServiceLocalClient
        │
        ▼
LogStorage
```

而不是改变 `SWLogfileReporterUtils` 的 API。

------

# 8. 当前 Memory 实现

当前已有：

```text
CircularBlockingQueue<Map<String, Object>>
```

用于保存本地数据。

这种设计实际上已经解决了一个非常重要的问题：

> **限制 Memory 数据规模，避免无限增长。**

例如容量为：

```text
N
```

当超过 N 条以后：

```text
旧数据
   ↓
被新数据覆盖
```

因此当前 Memory 本质上是：

> **Recent Data Cache**

而不是长期数据库。

------

# 9. 为什么第一阶段不应该改变 Memory

当前 Memory 已经稳定运行。

因此第一阶段不要做：

```text
CircularBlockingQueue
        ↓
删除
        ↓
重新设计 Memory
```

而应该：

```text
CircularBlockingQueue
        ↓
保持不变
        ↓
外面增加 LogStorage
```

也就是：

```text
原来：

LogReportServiceLocalClient
        │
        ▼
CircularBlockingQueue


第一阶段：

LogReportServiceLocalClient
        │
        ▼
LogStorage
        │
        ▼
MemoryLogStorage
        │
        ▼
CircularBlockingQueue
```

核心目标：

> **只增加抽象，不改变行为。**

------

# 10. 第一阶段：LogStorage

第一阶段只引入一个非常小的接口：

```java
public interface LogStorage {

    void add(Map<String, Object> data);

    List<Map<String, Object>> getAll();
}
```

当前阶段只需要两个能力：

```text
add()
getAll()
```

不要提前加入：

```text
initialize()
close()
clear()
delete()
query()
queryByTraceId()
statistics()
aggregate()
```

等未来真正出现需求以后再增加。

------

# 11. MemoryLogStorage

现有：

```text
CircularBlockingQueue<Map<String, Object>>
```

封装成：

```text
MemoryLogStorage
```

概念实现：

```java
public class MemoryLogStorage implements LogStorage {

    private final CircularBlockingQueue<Map<String, Object>> cache;

    public MemoryLogStorage(int capacity) {
        this.cache = new CircularBlockingQueue<>(capacity);
    }

    @Override
    public void add(Map<String, Object> data) {
        cache.add(data);
    }

    @Override
    public List<Map<String, Object>> getAll() {
        return new ArrayList<>(cache);
    }
}
```

实际实施时：

> **优先复用现有 `CircularBlockingQueue` 的创建方式和行为，不为了抽象而重写已经稳定的代码。**

------

# 12. LogReportServiceLocalClient 的最小改造

当前可能是：

```java
CircularBlockingQueue<Map<String, Object>> logDataCache;
```

改为：

```java
LogStorage storage;
```

写入：

```diff
- logDataCache.add(logDataMap);
+ storage.add(logDataMap);
```

读取：

```diff
- new ArrayList<>(logDataCache);
+ storage.getAll();
```

整体变为：

```text
SWLogfileReporterUtils
          │
          ▼
LogReportServiceLocalClient
          │
          ▼
      LogStorage
          │
          ▼
MemoryLogStorage
          │
          ▼
CircularBlockingQueue
```

------

# 13. 第一阶段完成后的状态

完成后应该达到：

```text
SWLogfileReporterUtils
          │
          ▼
LogReportServiceLocalClient
          │
          ▼
      LogStorage
          │
          ▼
MemoryLogStorage
          │
          ▼
CircularBlockingQueue
```

同时：

```text
Business API
```

完全不需要变化。

即：

```java
SWLogfileReporterUtils.enableReport(config);

SWLogfileReporterUtils.disableReport(config);

SWLogfileReporterUtils.statisticStatus();
```

仍然保持原有调用方式。

------

# 14. 第一阶段验收标准

第一阶段最重要的不是新增功能，而是：

> **重构以后什么都没有改变。**

重点验证：

```text
enableReport()
        ↓
数据正常采集
        ↓
Memory 正常写入
        ↓
statisticStatus()
        ↓
数据正常返回
```

同时验证：

```text
disableReport()
```

行为没有变化。

如果全部正常，那么：

```text
Storage Abstraction
```

就可以认为落地成功。

------

# 15. 第二阶段：H2LogStorage

第一阶段稳定以后，再增加：

```text
H2LogStorage
```

最终：

```text
                LogStorage
                /        \
               /          \
              ▼            ▼
     MemoryLogStorage   H2LogStorage
```

此时：

### Memory

负责：

```text
Recent Data
Temporary Cache
Low Overhead
```

### H2

负责：

```text
Historical Data
Persistence
Query
Classification
Retention
Future Metrics
```

------

# 16. H2 不应该暴露给 Business

Business Code 永远不需要知道：

```text
H2
JDBC
SQL
Table
Repository
Transaction
```

Business 仍然只知道：

```text
SWLogfileReporterUtils
```

因此：

```text
Business
   │
   ▼
SWLogfileReporterUtils
   │
   ▼
LogReportServiceLocalClient
   │
   ▼
LogStorage
   │
   ├── Memory
   │
   └── H2
```

H2 完全属于 Agent 内部实现。

------

# 17. H2 的设计参考 SkyWalking 9.4 OAP

SkyWalking 9.4 本身已经存在：

```text
OAP
 └── Storage
      └── JDBC / H2
```

因此本项目后续设计 H2 时，应优先研究 SkyWalking 9.4 已有的：

```text
Schema
Table
Field
Index
SQL
Storage Model
Lifecycle
```

避免完全从零设计。

但是：

> **参考 OAP ≠ 引入 OAP。**

------

# 18. 正确的 OAP 复用方式

正确：

```text
SkyWalking OAP 9.4
        │
        │ 参考
        ▼
Agent H2 Storage
```

错误：

```text
Agent
  │
  ▼
OAP Server
  │
  ├── Query
  ├── Storage
  ├── Cluster
  └── ...
```

本项目只需要借鉴：

```text
数据模型
Schema
SQL
Storage 思路
```

不需要把 OAP Server 放进 Agent。

------

# 19. H2 内部可以逐步建立自己的 Model

Business 边界仍然保持：

```text
Map
List
String
Long
```

但 H2 内部未来可以使用：

```text
TraceRecord
SpanRecord
```

或者参考 SkyWalking OAP 的内部模型。

结构：

```text
Business
   │
   │ Map / List
   ▼
Agent
   │
   ▼
Internal Model
   │
   ▼
H2
```

必须保证：

```text
Internal Model
```

不会重新出现在：

```text
SWLogfileReporterUtils
```

的公开 API 中。

------

# 20. Memory 与 H2 的能力定位

| 能力         | Memory   | H2         |
| ------------ | -------- | ---------- |
| 当前数据     | ✅        | ✅          |
| 最近数据     | ✅        | ✅          |
| 限制内存增长 | ✅        | -          |
| 重启后保留   | ❌        | ✅          |
| 历史 Trace   | ❌        | ✅          |
| Error 分类   | -        | ✅          |
| Slow 分类    | -        | ✅          |
| Retention    | 容量限制 | TTL / 清理 |
| Query        | 简单读取 | ✅          |
| Metrics      | ❌        | ✅（未来）  |

这里的核心不是让两个 Storage 实现完全一样，而是：

> **共同能力保持最小，H2 的高级能力留在 H2 内部。**

------

# 21. 不要过早扩大 LogStorage 接口

例如未来 H2 需要：

```text
queryError()
querySlow()
queryTrace()
statistics()
aggregate()
```

也不要马上把这些全部加入：

```java
LogStorage
```

否则 Memory 也被迫实现：

```text
queryError()
querySlow()
aggregate()
```

然后出现大量没有意义的代码。

更合理的是：

```text
LogStorage
     │
     ├── add()
     └── getAll()
```

高级能力在真正需要时再单独设计。

例如未来：

```text
H2LogStorage
    ├── queryTrace()
    ├── queryError()
    ├── querySlow()
    └── ...
```

具体是否需要进一步抽象，等实际需求出现后再决定。

------

# 22. 第三阶段：Trace 分类

H2 稳定以后，再增加：

```text
NORMAL
SLOW
ERROR
```

基本模型：

```text
                 Trace
                   │
          ┌────────┼────────┐
          │        │        │
          ▼        ▼        ▼
        ERROR     SLOW    NORMAL
```

分类优先级：

```text
ERROR
  >
SLOW
  >
NORMAL
```

即：

```text
if error:
    ERROR
else if duration >= slowThreshold:
    SLOW
else:
    NORMAL
```

------

# 23. Slow Threshold

不要在代码中硬编码：

```java
if (duration > 1000)
```

应该逐步配置化：

```text
slowThreshold
```

例如：

```text
slowThreshold = 1000ms
```

这里只是示例。

实际值应该根据：

```text
业务场景
Trace 数量
平均耗时
Agent 开销
实际运行数据
```

逐步确定。

------

# 24. Error / Slow / Normal 的价值

分类以后，H2 的价值开始明显超过 Memory。

可以形成：

```text
Trace
  │
  ├── ERROR
  │
  ├── SLOW
  │
  └── NORMAL
```

然后针对不同类别设计不同的：

```text
Retention
Query
Analysis
```

例如概念上：

```text
NORMAL → 短期保存
SLOW   → 中期保存
ERROR  → 长期保存
```

具体 TTL 暂时不固定。

------

# 25. Retention

H2 引入以后必须考虑：

> 数据不能无限增长。

因此后续需要：

```text
Retention
```

概念：

```text
NORMAL
   ↓
Short TTL

SLOW
   ↓
Medium TTL

ERROR
   ↓
Long TTL
```

真正的 TTL 应根据实际运行情况确定：

```text
每日 Trace 数量
平均数据大小
H2 文件增长速度
磁盘空间
查询需求
```

不要现在预设一个“标准答案”。

------

# 26. Query

H2 稳定以后，再增加查询能力。

初期可以非常简单：

```text
Trace ID
Time Range
Error
Slow
```

以后再考虑：

```text
Service
Endpoint
Component
Duration
Exception
```

推荐顺序：

```text
Trace ID
   ↓
时间范围
   ↓
Error / Slow
   ↓
Service / Endpoint
   ↓
更多条件
```

仍然遵循：

> **需要什么再增加什么。**

------

# 27. Metrics：当前明确不做

当前阶段明确：

> **Metrics 不属于当前实施范围。**

原因：

Metrics 更依赖：

```text
历史数据
时间窗口
聚合
统计
持久化
```

而当前 Memory 的定位是：

```text
Recent Data Cache
```

所以 Memory 不适合承担 Metrics。

------

# 28. Metrics 未来只建立在 H2 之上

未来如果增加 Metrics：

```text
Trace
   │
   ▼
H2
   │
   ▼
Aggregation
   │
   ▼
Metrics
```

而不是：

```text
Memory
   │
   ▼
Metrics
```

未来可以参考 SkyWalking OAP 已有的指标设计：

```text
Request Count
Error Count
Error Rate
Average Duration
P50
P90
P95
P99
Endpoint Statistics
Component Statistics
Exception Statistics
```

但只实现真正需要的指标。

------

# 29. 未来是否可以让 H2 完全替代 Memory

可以考虑，但不应该现在做。

H2 本身可以有：

```text
File Mode
```

和：

```text
In-Memory Mode
```

因此未来可能形成：

```text
             H2LogStorage
              /         \
             /           \
            ▼             ▼
       H2 File        H2 Memory
```

这样最终甚至可以取消：

```text
MemoryLogStorage
```

形成：

```text
LogStorage
    │
    ▼
H2LogStorage
    │
    ├── File Mode
    └── Memory Mode
```

这样长期维护的代码可能更少。

------

# 30. 但当前不要直接用 H2 替换 Memory

当前 Memory：

```text
简单
稳定
低开销
已有实现
```

H2：

```text
新依赖
新 Schema
新 SQL
新生命周期
新性能问题
新维护成本
```

因此正确路线：

```text
现有 Memory
      │
      ▼
LogStorage
      │
      ▼
Memory 稳定
      │
      ▼
增加 H2
      │
      ▼
H2 验证
      │
      ▼
长期运行观察
      │
      ▼
再考虑统一
```

------

# 31. 推荐整体演进路线

## Phase 1：Storage 抽象

```text
SWLogfileReporterUtils
        ↓
LogReportServiceLocalClient
        ↓
LogStorage
        ↓
MemoryLogStorage
        ↓
CircularBlockingQueue
```

只做：

```text
add()
getAll()
```

目标：

> 零行为变化。

------

## Phase 2：H2

```text
LogStorage
   ├── Memory
   └── H2
```

重点验证：

```text
Schema
JDBC
写入
读取
生命周期
性能
磁盘增长
```

------

## Phase 3：分类

```text
NORMAL
SLOW
ERROR
```

优先级：

```text
ERROR > SLOW > NORMAL
```

------

## Phase 4：Retention

```text
NORMAL → Short
SLOW   → Medium
ERROR  → Long
```

------

## Phase 5：Query

逐步增加：

```text
Trace ID
Time Range
Error
Slow
Service
Endpoint
```

------

## Phase 6：Metrics

最后考虑：

```text
Request Count
Error Rate
Latency
Percentile
Endpoint Statistics
```

并且：

> Metrics 只建立在 H2 历史数据基础上。

------

# 32. 推荐代码结构

## Phase 1

尽量简单：

```text
logfile-reporter-plugin
│
├── SWLogfileReporterUtils.java
├── LogReportServiceLocalClient.java
│
└── storage
    ├── LogStorage.java
    └── MemoryLogStorage.java
```

------

## Phase 2

增加：

```text
storage
├── LogStorage.java
├── MemoryLogStorage.java
└── H2LogStorage.java
```

只有 H2 真的变复杂时，再继续：

```text
storage
├── LogStorage.java
├── MemoryLogStorage.java
└── h2
    ├── H2LogStorage.java
    ├── H2Repository.java
    └── ...
```

不要提前创建大量空层。

------

# 33. 明确不做的事情

当前不做：

### HTTP Server

不增加：

```text
Spring Boot
Tomcat
Netty
REST API
```

------

### OAP Server

不把：

```text
OAP Server
```

嵌入 Agent。

只参考其：

```text
Storage
Schema
Model
Metrics
```

------

### 复杂 DTO

Business API 不增加：

```text
Trace
Span
TraceRecord
SpanTag
```

等 Agent 自定义对象。

------

### 完整 APM

不以：

```text
复制 SkyWalking
```

为目标。

------

### Metrics

当前暂不实现。 参见 [h2化 -gpt-metrics.md](h2化 -gpt-metrics.md)

------

# 34. 代码复杂度原则

这是一个个人维护的项目，因此必须把：

```text
代码量
Bug 数量
维护成本
```

作为重要约束。

设计优先级：

```text
简单
  ↓
可维护
  ↓
满足当前需求
  ↓
再考虑扩展
```

而不是：

```text
架构完整
  ↓
设计模式完整
  ↓
提前抽象
  ↓
最后才实现需求
```

------

# 35. 核心设计原则

后续开发始终遵循以下原则。

## 原则 1：不改变现有 Business API

保持：

```java
SWLogfileReporterUtils.enableReport(config);

SWLogfileReporterUtils.disableReport(config);

SWLogfileReporterUtils.statisticStatus();
```

------

## 原则 2：Storage 只藏在 Agent 内部

```text
Business
   │
   ▼
SWLogfileReporterUtils
   │
   ▼
LogReportServiceLocalClient
   │
   ▼
LogStorage
```

Business 不感知 Storage。

------

## 原则 3：Business ↔ Agent 只使用 Native / JDK Types

优先：

```text
Map
List
String
Long
Integer
Boolean
byte[]
```

------

## 原则 4：Memory 先不动

现有：

```text
CircularBlockingQueue
```

保持稳定。

------

## 原则 5：需求驱动抽象

不要为未来可能存在的功能提前设计几十个接口。

------

## 原则 6：H2 后置

先：

```text
Storage
```

再：

```text
H2
```

再：

```text
Error / Slow
```

最后：

```text
Metrics
```

------

## 原则 7：优先参考 SkyWalking 9.4

尤其是：

```text
OAP Storage
JDBC
H2
Schema
Model
Metrics
```

但只借鉴需要的部分。

------

# 36. 最终架构

长期目标：

```text
                         Business Code
                              │
                              │
                    SWLogfileReporterUtils
                              │
                              ▼
                 SkyWalking Java Agent 9.4
                              │
                              ▼
                LogReportServiceLocalClient
                              │
                              ▼
                         LogStorage
                              │
                 ┌────────────┴────────────┐
                 │                         │
                 ▼                         ▼
              Memory                      H2
                 │                         │
                 │                         ├── Trace
                 │                         ├── Error
                 │                         ├── Slow
                 │                         ├── Retention
                 │                         └── Query
                 │
                 ▼
          Recent Data Cache
                                           │
                                           ▼
                                      Future Metrics
```

------

# 37. 最终的数据边界

必须始终保持：

```text
┌─────────────────────────────────────┐
│            Business                 │
│                                     │
│     SWLogfileReporterUtils         │
│                                     │
│ Map / List / String / Long / ...   │
└──────────────────┬──────────────────┘
                   │
                   │ Boundary
                   ▼
┌─────────────────────────────────────┐
│          Agent Internal             │
│                                     │
│ LogReportServiceLocalClient         │
│ LogStorage                          │
│ Memory                              │
│ H2                                  │
│ TraceRecord / SpanRecord / Model    │
└─────────────────────────────────────┘
```

这条边界应该视为本项目的**硬性架构约束**。

------

# 38. 当前下一步

按照本方案，目前真正应该做的事情只有：

### Step 1

增加：

```java
public interface LogStorage {

    void add(Map<String, Object> data);

    List<Map<String, Object>> getAll();
}
```

### Step 2

增加：

```text
MemoryLogStorage
```

包装现有：

```text
CircularBlockingQueue
```

------

### Step 3

修改：

```text
LogReportServiceLocalClient
```

从：

```text
CircularBlockingQueue
```

切换到：

```text
LogStorage
```

------

### Step 4

保持：

```text
SWLogfileReporterUtils
```

不变。

------

### Step 5

完整验证：

```text
enableReport()
      ↓
LogReportServiceLocalClient
      ↓
MemoryLogStorage
      ↓
CircularBlockingQueue
      ↓
statisticStatus()
```

------

### Step 6

独立提交 Git Commit。

建议：

```text
refactor: introduce LogStorage abstraction
```

完成以后暂停。

不要在同一个 Commit 中继续加入 H2。

------

# 39. 后续落地顺序

最终明确为：

```text
┌──────────────────────────────┐
│ Phase 1                      │
│ LogStorage                   │
│ Memory 不变                  │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ Phase 2                      │
│ H2LogStorage                 │
│ 参考 SkyWalking 9.4 OAP      │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ Phase 3                      │
│ NORMAL / SLOW / ERROR        │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ Phase 4                      │
│ Retention                    │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ Phase 5                      │
│ Query                        │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ Phase 6                      │
│ Metrics                      │
│ H2 Only                      │
└──────────────────────────────┘
```

------

# 40. 总结

本项目后续不是重新设计一套 APM，而是围绕现有：

```text
SWLogfileReporterUtils
        ↓
LogReportServiceLocalClient
        ↓
CircularBlockingQueue
```

进行渐进式增强。

第一步只做：

```text
CircularBlockingQueue
        ↓
MemoryLogStorage
        ↓
LogStorage
```

然后再：

```text
LogStorage
   ├── Memory
   └── H2
```

之后才逐步增加：

```text
Error
Slow
Retention
Query
Metrics
```

整个过程中始终保持：

```text
Business
   │
   ▼
SWLogfileReporterUtils
   │
   ▼
Agent Internal
   │
   ▼
Storage
```

并严格保证：

> **Business ↔ Agent 边界不暴露 Agent 自定义 Java 类型。**

最终希望得到的不是一个庞大的 APM 系统，而是一个：

> **轻量、低侵入、无需 OAP、无需外部服务、可本地持久化、能够逐步演进的 SkyWalking Agent Local Observability 能力。**
