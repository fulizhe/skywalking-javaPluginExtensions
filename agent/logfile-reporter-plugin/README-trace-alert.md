# 慢/错链路筛选与异步扩展通知

## 功能说明

在单 JVM 内，当同一 `traceId` 的 Segment 合并进 `logfileStatMap` 后，自动评估是否为**慢请求**或**错误链路**，并通过异步方式通知外部扩展（SPI 或 HTTP webhook），由外部决定后续操作（如入库）。

评估范围：本进程内已合并的全部 Segment，**不包含**其他微服务实例的数据。

## 配置项

通过 JVM 参数或 `agent.config` 配置（键名遵循 SkyWalking 插件配置规则，`Alert` 嵌套在 `LogFileReporter` 下）：

JVM 示例：

```bash
-Dskywalking.plugin.logfilereporter.alert.enabled=true
```

| 配置键 | 说明 | 默认值 |
|--------|------|--------|
| `plugin.logfilereporter.alert.enabled` | 是否启用告警 | `false` |
| `plugin.logfilereporter.alert.default_slow_threshold_ms` | 默认慢请求阈值（毫秒） | `3000` |
| `plugin.logfilereporter.alert.http_error_status_min` | HTTP 状态码 ≥ 该值视为错误 | `500` |
| `plugin.logfilereporter.alert.enable_span_is_error` | 启用 `span.isError` 判定 | `true` |
| `plugin.logfilereporter.alert.enable_http_status_error` | 启用 `http.status_code` 判定 | `true` |
| `plugin.logfilereporter.alert.slow_rules` | 差异化慢请求规则 | 空 |
| `plugin.logfilereporter.alert.webhook_url` | HTTP 回调完整地址，支持 `${WebPort:9600}` | 空 |
| `plugin.logfilereporter.alert.webhook_path` | 仅配置路径时与 host + 环境变量端口拼装 | 空 |
| `plugin.logfilereporter.alert.webhook_host` | 拼装用主机 | `127.0.0.1` |
| `plugin.logfilereporter.alert.webhook_port_env` | 读取端口的系统环境变量名 | `WebPort` |
| `plugin.logfilereporter.alert.webhook_port_default` | 环境变量未设置时的默认端口 | `9600` |
| `plugin.logfilereporter.alert.webhook_connect_timeout_ms` | Webhook 连接超时 | `3000` |
| `plugin.logfilereporter.alert.webhook_read_timeout_ms` | Webhook 读取超时 | `5000` |
| `plugin.logfilereporter.alert.listener_class` | 可选 Listener 全限定类名 | 空 |

### slow_rules 格式

```text
operation:GET:/api/order/*=8000;url:.*/export/.*=60000
```

- `operation:` 前缀 + 通配符（`*`）匹配 Entry Span 的 `operationName`
- `url:` 前缀 + 正则匹配 Entry Span 的 `url` tag
- 优先级：`operation` > `url` > 默认阈值

## 错误判定

1. **L1**：任一 Span 的 `isError == true`
2. **L2**：任一 Span 的 tag `http.status_code >= 500`（可配置下限）
3. **L3**：`TraceAnomalyListener#isError` 自定义 OR 组合

## 扩展方式

### 方式一：HTTP 回调（推荐，业务逻辑放 Spring Boot）

```properties
plugin.logfilereporter.alert.enabled=true
# 方式 A：完整 URL，端口来自启动时注入的 WebPort（未设置则用 9600）
plugin.logfilereporter.alert.webhook_url=http://127.0.0.1:${WebPort:9600}/internal/trace-alert

# 方式 B：只配路径，host/port 由插件按环境变量拼装（适合端口常变）
plugin.logfilereporter.alert.webhook_path=/internal/trace-alert
plugin.logfilereporter.alert.webhook_port_env=WebPort
plugin.logfilereporter.alert.webhook_port_default=9600
```

业务启动前注入端口：

```bash
export WebPort=9600
java -javaagent:skywalking-agent.jar -jar app.jar
```

或在 `agent.config` 中桥接：

```properties
plugin.logfilereporter.alert.webhook_url=http://127.0.0.1:${WebPort:9600}/internal/trace-alert
```

Agent POST JSON 示例：

```json
{
  "traceId": "...",
  "service": "demo-service",
  "serviceInstance": "demo-instance",
  "alertTypes": ["SLOW", "ERROR"],
  "entryOperation": "GET:/api/order/1",
  "url": "http://localhost:8080/api/order/1",
  "durationMs": 9200,
  "errorSpanCount": 1,
  "logs": [ ... ]
}
```

Spring Boot 接收示例：

```java
@RestController
public class TraceAlertController {
    @PostMapping("/internal/trace-alert")
    public void onTraceAlert(@RequestBody Map<String, Object> payload) {
        // 入库、发消息等
    }
}
```

### 方式二：Java SPI（独立 agent 插件 jar）

**不能**只把 SPI 实现打进业务 Spring Boot fat jar——Agent ClassLoader 看不到业务类。详见 [`readme-classloader.md`](readme-classloader.md)。

步骤：

1. 新建 Maven 模块，依赖 `logfile-reporter-plugin`（provided）
2. 实现 `TraceAnomalyListener`
3. 注册 `META-INF/services/org.apache.skywalking.apm.agent.core.reporter.logfile.alert.TraceAnomalyListener`
4. 打瘦 jar 放到 `skywalking-agent/plugins/`

```java
public class DbTraceAnomalyListener implements TraceAnomalyListener {
    @Override
    public void onTraceAlert(TraceAlertEvent event) {
        // 入库
    }

    @Override
    public boolean isError(TraceSnapshot snapshot) {
        return false; // 可选自定义
    }
}
```

## 运行指标查询（运维）

业务侧通过 toolkit 已有接口拉取 agent 本地状态（与 trace/jvm 数据同一入口）：

```java
Map<String, Object> status = SWLogfileReporterUtils.statisticStatus();
Map<String, Object> traceAlert = (Map<String, Object>) status.get("traceAlert");
```

`traceAlert` 结构示例：

```json
{
  "config": {
    "enabled": true,
    "defaultSlowThresholdMs": 3000,
    "webhookResolvedUrl": "http://127.0.0.1:9600/inner/sw/trace-alert",
    "webhookPlaceholderUnresolved": false
  },
  "dispatcher": {
    "enabled": true,
    "initialized": true,
    "dispatchSubmitted": 12,
    "dispatchSkippedDuplicate": 3,
    "listenerInvocationFailed": 0
  },
  "httpWebhook": {
    "totalAttempts": 12,
    "successCount": 11,
    "failureCount": 1,
    "skippedEmptyUrl": 0,
    "successRatePercent": "91.67",
    "lastSuccessTimeMs": 1710000000000,
    "lastFailureTimeMs": 1710000001000,
    "lastHttpStatus": 500,
    "lastFailureReason": "HTTP status 500",
    "lastTargetUrl": "http://127.0.0.1:9600/inner/sw/trace-alert",
    "lastTraceId": "abc123"
  }
}
```

| 字段 | 含义 |
|------|------|
| `httpWebhook.totalAttempts` | HTTP 回调尝试次数（每次 `onTraceAlert` 计 1） |
| `httpWebhook.successCount` / `failureCount` | 2xx 成功 / 非 2xx 或 IO 异常 |
| `httpWebhook.skippedEmptyUrl` | URL 为空跳过 |
| `httpWebhook.successRatePercent` | 成功率（基于 totalAttempts） |
| `dispatcher.dispatchSubmitted` | 通过去重后提交异步分发的次数 |
| `dispatcher.dispatchSkippedDuplicate` | 同 trace 重复告警被跳过次数 |
| `config.*` | 当前生效配置（含实时解析的 `webhookResolvedUrl`） |

实现类：`TraceAlertMetrics`；挂载点：`LogfileReporterStatusExposeInterceptor` → `resultMap.traceAlert`。

## 去重与线程模型

- 消费线程合并 segment 后提交评估，**不阻塞** DataCarrier
- 单线程 `LogfileTraceAlert-*` 执行 listener / webhook
- 同一 `traceId` 的 ERROR / SLOW 各只通知一次

## 相关类

| 类 | 包 |
|----|-----|
| `TraceEvaluator` | `...reporter.logfile.alert` |
| `AsyncTraceAlertDispatcher` | `...reporter.logfile.alert` |
| `TraceAnomalyListener` | `...reporter.logfile.alert` |
| `LogFileTraceSegmentServiceClient` | 合并后触发评估 |
| `TraceAlertMetrics` | 运行指标与配置快照 |
| `HttpTraceAnomalyListener` | HTTP webhook 发送与指标累计 |
