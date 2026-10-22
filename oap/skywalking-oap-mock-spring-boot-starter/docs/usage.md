# 接入与构建指南

在业务应用中引入 starter、使用原生 SkyWalking Agent 启动，并显式开启 `mock-oap`。不想本地构建时，可从 [GitHub 2.0.0 Release](https://github.com/fulizhe/skywalking-javaPluginExtensions/releases#release-2.0.0) 下载 `skywalking-oap-mock-spring-boot-starter-2.0.0.jar`；本文也说明本地构建、依赖、运行前提、协议和配置。

```bash
cd oap/skywalking-oap-mock-spring-boot-starter
mvn -B clean test
mvn -B package
```

## 构建

产物：

```text
target/skywalking-oap-mock-spring-boot-starter-2.0.0.jar
```

模块不继承任何 demo 父 POM，也不依赖本机 SkyWalking agent 文件；构建需要 JDK 17（产物为 Java 8 字节码）和 Maven。

## 依赖与启用

在业务应用的 Maven 项目中添加：

```xml
<dependency>
  <groupId>com.fulizhe.observability</groupId>
  <artifactId>skywalking-oap-mock-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

Starter 默认不启用。接入方需要显式开启，并按现场网络配置监听端口：

```yaml
mock-oap:
  enabled: true
  port: 11800
```

## 运行前提

宿主及其他参与链路的 Java 业务应用使用原生 SkyWalking Java Agent；版本应与 starter 编译时使用的 `skywalking.version`（当前 `9.4.0`）一致。宿主进程启动示例：

```bash
java -javaagent:/path/to/skywalking-agent.jar -jar app.jar
```

`apm-agent` 在 starter 中是 `provided`：不会传递给业务应用，也不会进入业务 fat jar；运行时由 `-javaagent` 提供 shaded gRPC 与 SkyWalking 协议类。

`mock-oap.enabled=true` 但未挂 agent 时，collector 会打印 WARN 并跳过监听，业务应用仍可正常启动。仅把 `skywalking-agent.jar` 放进应用 classpath 不算挂载；否则 Agent 会把 jar 所在目录当成自己的 home，可能导致插件加载异常。

## Agent 上报目标与协议

将每个原生 Agent 的 `collector.backend_service` 配置为宿主进程可达的地址与端口。默认 gRPC 端口同时提供：

- SkyWalking native gRPC：trace segment、log、JVM metrics、configuration discovery；
- OTLP/gRPC `opentelemetry.proto.collector.trace.v1.TraceService/Export`。

collector 使用 Agent 自带的 shaded gRPC 栈，避免被 Agent 的 `grpc-1.x` 插件再次埋点。业务侧未 shaded 的 `io.grpc.*` 调用仍会正常生成 span。

## 可用 Bean 与展示边界

自动配置注册以下单例，均可由自定义 Bean 覆盖：

- `MockOapProperties`
- `TraceSegmentBuffer`
- `LogBuffer`
- `JVMMetricBuffer`
- `OtlpTraceConverter`
- `MockOapGrpcServer`

Starter 不包含 Controller、HTML 页面或 Web 依赖。接入方可自行读取上述 buffer，或提供独立 UI 模块来渲染 Trace 与 Log。

## 主要配置

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `mock-oap.enabled` | `false` | 是否启动 collector |
| `mock-oap.port` | `11800` | gRPC 监听端口 |
| `mock-oap.max-traces` | `200` | 链路段缓存条数 |
| `mock-oap.max-logs` | `2000` | 日志缓存条数 |
| `mock-oap.max-jvm-metrics` | `2000` | JVM 指标批次缓存条数 |
| `mock-oap.ingest-queue-capacity` | `2048` | 单信号 ingress 队列条数上限 |
| `mock-oap.ingest-queue-max-bytes` | `67108864` | 单信号 ingress 队列字节上限 |
| `mock-oap.max-otlp-inbound-message-bytes` | `16777216` | OTLP 单次入站上限 |

## 自动配置与依赖范围

Spring Boot 2.5 使用以下自动配置入口：

```text
META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.fulizhe.observability.mockoap.autoconfigure.MockOapAutoConfiguration
```

`opentelemetry-proto` 是编译期依赖，会随 starter 传递；`apm-agent` 是 `provided`，不会传递。
