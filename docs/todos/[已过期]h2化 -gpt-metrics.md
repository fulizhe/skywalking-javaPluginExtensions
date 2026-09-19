> **Metrics 本身不是由 Agent 强制提供一个固定 UI，而是通过类似 `SWLogfileReporterUtils` 的辅助工具类，把标准化数据暴露给外部，由使用方自行实现可视化。**

# Metrics 与数据对外暴露设计

## 1. 基本原则

未来增加 Metrics 后，仍然不考虑在 Agent 内部实现完整的可视化系统。

Agent 的职责是：

```text
采集
  ↓
计算 / 聚合
  ↓
提供标准化数据
```

而不是：

```text
采集
  ↓
计算
  ↓
Web Server
  ↓
Dashboard
  ↓
图表
```

因此整体设计遵循：

> **Agent 负责提供数据能力，外部系统负责数据消费和可视化。**

------

# 2. 对外暴露方式

Metrics 对外暴露方式继续参考现有：

```text
SWLogfileReporterUtils
```

而不是增加 HTTP Server 或其他独立服务。

未来可以增加独立的辅助工具类型，例如：

```text
SWMetricsUtils
```

或者：

```text
SWLocalMetricsUtils
```

具体名称后续再确定。

整体结构：

```text
Business / External Consumer
            │
            ▼
      SWMetricsUtils
            │
            ▼
        Agent 9.4
            │
            ▼
        H2 Storage
            │
            ▼
       Metrics Data
```

与当前：

```text
SWLogfileReporterUtils
```

保持相同的设计思想。

------

# 3. 辅助工具类的职责

未来 Metrics 辅助工具类只负责：

> **向外部提供 Metrics 数据。**

例如可以提供：

```java
public static Map<String, Object> statisticMetrics()
```

或者根据实际需求提供：

```java
public static Map<String, Object> queryMetrics(
        Map<String, Object> condition)
```

具体 API 暂不固定。

核心原则是：

```text
Agent
    │
    ▼
标准化数据
    │
    ▼
辅助工具类
    │
    ▼
外部调用方
```

而不是让辅助工具类承担：

```text
Web
Chart
Dashboard
HTTP
HTML
Vue
```

等职责。

------

# 4. 为什么采用辅助工具类方式

当前已经存在：

```text
SWLogfileReporterUtils
```

这种模式。

它证明了本项目可以采用：

```text
Agent 内部能力
       ↓
Utility API
       ↓
外部代码直接调用
```

因此未来 Metrics 可以沿用同一思路。

例如：

```text
SWLogfileReporterUtils
        │
        └── statisticStatus()
```

负责：

```text
Trace / Log / Local Status
```

未来：

```text
SWMetricsUtils
        │
        └── statisticMetrics()
```

负责：

```text
Metrics
```

形成不同职责的辅助工具类。

------

# 5. 数据与可视化彻底解耦

最终应该形成：

```text
                 Agent
                   │
        ┌──────────┴──────────┐
        │                     │
        ▼                     ▼
   Trace / Log             Metrics
        │                     │
        ▼                     ▼
SWLogfileReporterUtils   SWMetricsUtils
        │                     │
        └──────────┬──────────┘
                   │
                   ▼
             External Consumer
                   │
          ┌────────┼────────┐
          │        │        │
          ▼        ▼        ▼
       Console   Web UI    AI
```

外部消费者可以自行决定：

```text
命令行
Web 页面
IDE 插件
监控平台
Grafana 类系统
AI Agent
其他 Java 程序
```

Agent 不需要关心最终如何展示。

------

# 6. Agent 只提供数据，不提供 UI

例如 Agent 返回：

```java
Map<String, Object>
```

其中可以包含：

```text
requestCount
errorCount
errorRate
avgDuration
p50
p90
p95
p99
```

外部调用方可以自行转换为：

```text
折线图
柱状图
表格
Dashboard
告警
AI 分析
```

因此：

```text
Agent
    │
    │ Data
    ▼
External Consumer
    │
    ├── Visualization
    ├── Alert
    ├── Analysis
    └── AI
```

------

# 7. Metrics 数据边界

Metrics 也必须遵守与 `SWLogfileReporterUtils` 相同的 ClassLoader 原则。

对外优先使用：

```text
String
Long
Integer
Double
Boolean
Map
List
```

例如：

```java
Map<String, Object>
```

而不要让外部直接依赖 Agent 内部：

```text
Metric
MetricValue
MetricSeries
MetricSnapshot
```

等自定义类型。

内部可以存在这些模型，但不能作为 Business / External API 的强类型返回值。

------

# 8. Metrics 数据来源

Metrics 最终主要建立在 H2 历史数据之上。

整体：

```text
Trace / Span
      │
      ▼
     H2
      │
      ▼
Metrics Aggregation
      │
      ▼
SWMetricsUtils
      │
      ▼
External Consumer
```

因此 Metrics 与当前 Memory Cache 解耦。

Memory 主要负责：

```text
最近数据
```

H2 负责：

```text
历史数据
```

Metrics 负责：

```text
历史数据的统计结果
```

------

# 9. Metrics 辅助工具不负责复杂计算

这里需要进一步控制代码复杂度。

不建议让：

```text
SWMetricsUtils
```

自己承担大量：

```text
SQL
Aggregation
Statistics
Retention
```

逻辑。

更合理的是：

```text
SWMetricsUtils
        │
        ▼
Metrics Service / H2
        │
        ▼
Aggregated Data
```

其中辅助工具类只是一个轻量的对外入口。

即：

```text
SWMetricsUtils
```

类似于：

```text
Facade / Entry Point
```

而不是完整的 Metrics Engine。

------

# 10. 不提供固定 Dashboard

本项目不计划直接提供：

```text
Agent Dashboard
```

也不强制引入：

```text
Vue
React
Spring Boot
HTTP Server
```

原因是：

> 不同使用方对 Metrics 的消费方式可能完全不同。

例如：

```text
用户 A
→ 自己写 Java 程序

用户 B
→ 接入现有监控系统

用户 C
→ 做一个 Web Dashboard

用户 D
→ 交给 AI 分析
```

只要 Agent 提供稳定的数据 API，上层实现可以完全独立。

------

# 11. 最终形成“数据能力 + 消费能力”分层

整体架构：

```text
┌─────────────────────────────────────────┐
│              Agent Layer                │
│                                         │
│  Trace / Log / Metrics                  │
│                                         │
│  H2 Storage                             │
│  Aggregation                            │
└───────────────────┬─────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│             Utility API Layer           │
│                                         │
│  SWLogfileReporterUtils                 │
│  SWMetricsUtils                         │
└───────────────────┬─────────────────────┘
                    │
                    │ Native Types
                    ▼
┌─────────────────────────────────────────┐
│          External Consumer Layer        │
│                                         │
│  CLI / Web / IDE / Monitor / AI         │
└─────────────────────────────────────────┘
```

这样 Agent 本身只负责：

> **把数据可靠地采集、存储、统计并暴露出来。**

------

# 12. 长期设计目标

最终可以形成一组非常轻量的 Agent 辅助工具：

```text
SWLogfileReporterUtils
        │
        ├── enableReport()
        ├── disableReport()
        └── statisticStatus()


SWMetricsUtils
        │
        ├── statisticMetrics()
        ├── queryMetrics()
        └── ...
```

两者保持相同的设计哲学：

```text
简单 API
+
Native Type
+
Agent 内部实现隐藏
+
外部自行消费
+
不绑定 UI
```

------

# 13. 最终原则

未来 Metrics 的核心原则可以归纳为：

> **Agent 提供 Metrics 数据能力，不负责 Metrics 展示。**

进一步明确：

```text
Agent
├── 采集
├── 存储
├── 聚合
└── 数据 API
        │
        ▼
External Consumer
├── 可视化
├── Dashboard
├── 告警
├── 分析
└── AI
```

这样既可以保持 Agent 本身轻量，也可以避免为了可视化引入 HTTP Server、Web 框架和前端工程。

同时，外部使用方可以完全按照自己的需求选择展示方式，而不被 Agent 内部实现绑定。
