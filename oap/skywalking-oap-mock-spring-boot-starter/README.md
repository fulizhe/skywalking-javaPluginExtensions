# skywalking-oap-mock-spring-boot-starter

进程内 mock-OAP collector，接收 SkyWalking 原生 agent 上报，并兼容标准 OTLP/gRPC trace。
用于本地联调、演示和测试，不替代真实 OAP。

## 构建

```bash
cd oap/skywalking-oap-mock-spring-boot-starter
mvn -B clean test
mvn -B package
```

产物：

```text
target/skywalking-oap-mock-spring-boot-starter-2.0.0.jar
```

模块不继承任何 demo 父 POM，也不依赖本机 SkyWalking agent 文件；构建只需要 JDK 17（产物为 Java 8 字节码）和 Maven。

## 依赖

```xml
<dependency>
  <groupId>com.fulizhe.observability</groupId>
  <artifactId>skywalking-oap-mock-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

Starter 默认 **不启用**，避免业务应用引入后意外占用端口。需要显式开启：

```yaml
mock-oap:
  enabled: true
  port: 11800
```

## 运行前提

应用必须用 SkyWalking Java agent 启动，版本与 starter 编译时使用的
`skywalking.version`（当前 `9.4.0`）一致：

```bash
java -javaagent:/path/to/skywalking-agent.jar -jar app.jar
```

`apm-agent` 在 starter 中是 `provided`：

- 不会传递给业务应用；
- 不会进入业务 fat jar；
- 运行时由 `-javaagent` 提供 shaded gRPC 与 SkyWalking 协议类。

`mock-oap.enabled=true` 但未挂 agent 时，collector 会打印 WARN 并跳过监听，但应用仍能正常启动。
把 `skywalking-agent.jar` 只放进应用 classpath 不算挂载，collector 也会跳过
（否则 agent 会把 jar 所在目录当成自己的 home，加载 0 个插件）。

## 端口与协议

默认 `11800` 同时提供：

- SkyWalking native gRPC：trace segment、log、JVM metrics、configuration discovery；
- OTLP/gRPC `opentelemetry.proto.collector.trace.v1.TraceService/Export`。

collector 使用 agent 自带的 shaded gRPC 栈，避免被 agent 的 `grpc-1.x` 插件再次埋点。
业务侧未 shaded 的 `io.grpc.*` 调用仍会正常生成 span。

## 可用 Bean

自动配置注册以下单例，均可用自定义 Bean 覆盖：

- `MockOapProperties`
- `TraceSegmentBuffer`
- `LogBuffer`
- `JVMMetricBuffer`
- `OtlpTraceConverter`
- `MockOapGrpcServer`

Starter 不包含 Controller、HTML 页面或 Web 依赖。需要观测页面时，由接入方自行读取
上述 buffer，或额外引入 UI 模块。

## 主要配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `mock-oap.enabled` | `false` | 是否启动 collector |
| `mock-oap.port` | `11800` | gRPC 监听端口 |
| `mock-oap.max-traces` | `200` | 链路段缓存条数 |
| `mock-oap.max-logs` | `2000` | 日志缓存条数 |
| `mock-oap.max-jvm-metrics` | `2000` | JVM 指标批次缓存条数 |
| `mock-oap.ingest-queue-capacity` | `2048` | 单信号 ingress 队列条数上限 |
| `mock-oap.ingest-queue-max-bytes` | `67108864` | 单信号 ingress 队列字节上限 |
| `mock-oap.max-otlp-inbound-message-bytes` | `16777216` | OTLP 单次入站上限 |

## 自动配置

Spring Boot 2.5 兼容入口：

```text
META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.fulizhe.observability.mockoap.autoconfigure.MockOapAutoConfiguration
```

`opentelemetry-proto` 是编译期依赖，会随 starter 传递；`apm-agent` 是 `provided`，不会传递。
