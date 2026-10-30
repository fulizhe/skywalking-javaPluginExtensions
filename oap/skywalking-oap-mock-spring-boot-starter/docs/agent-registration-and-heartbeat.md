# SkyWalking Agent 的注册与心跳（9.4.0 源码链路）

本文说明原生 SkyWalking Java Agent 如何向 collector “注册自己”和发送心跳，以及 mock-OAP starter 当前对这两条调用的处理边界。
结论：注册和心跳是 `ServiceManagementClient` 同一个 30 秒定时任务里的两个 **unary RPC**，不是独立的注册流程，也没有握手或能力协商。
快速核对命令见文末「本机验证」。

## 结论

| 行为 | 实现 |
|---|---|
| 注册 | `ServiceManagementClient.run()` 调用 `reportInstanceProperties(...)` |
| 心跳 | 同一个 `run()` 调用 `keepAlive(...)` |
| 定时 | `boot()` 里 `scheduleAtFixedRate(..., 0, 30, SECONDS)` |
| 传输 | 普通 gRPC unary 调用，复用 `GRPCChannelManager` 的 channel |
| 长连接流 | 不在这里；trace 流在 `TraceSegmentServiceClient.collect(...)` |

版本前提：本仓库 `agent/pom.xml` 锁定 `skywalking.version = 9.4.0`。对应的核心包在 Maven 本地仓库：

```text
%USERPROFILE%\.m2\repository\org\apache\skywalking\apm-agent-core\9.4.0\apm-agent-core-9.4.0.jar
```

## 1. 启动链路

Agent 的入口是 `premain`，最后调用 `ServiceManager.boot()`：

```java
// apm-sniffer/apm-agent/src/main/java/org/apache/skywalking/apm/agent/SkyWalkingAgent.java:110-117
try {
    ServiceManager.INSTANCE.boot();
} catch (Exception e) {
    LOGGER.error(e, "Skywalking agent boot failure.");
}
```

`ServiceManager` 按优先级执行所有 `BootService` 的 `prepare()`、`boot()`、`onComplete()`：

```java
// apm-sniffer/apm-agent-core/.../boot/ServiceManager.java:41-47
public void boot() {
    bootedServices = loadAllServices();
    prepare();
    startup();
    onComplete();
}
```

`ServiceManagementClient.prepare()` 只做两件事：把自己注册成 channel 状态监听器，读取 instance 自定义属性；此时还没有发任何网络请求：

```java
// ServiceManagementClient.java:68-73
@Override
public void prepare() {
    ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
    SERVICE_INSTANCE_PROPERTIES = InstanceJsonPropertiesUtil.parseProperties();
}
```

真正启动定时任务的是 `boot()`：

```java
// ServiceManagementClient.java:75-86
@Override
public void boot() {
    heartbeatFuture = Executors.newSingleThreadScheduledExecutor(
        new DefaultNamedThreadFactory("ServiceManagementClient")
    ).scheduleAtFixedRate(
        new RunnableWithExceptionProtection(
            this,
            t -> LOGGER.error("unexpected exception.", t)
        ), 0, Config.Collector.HEARTBEAT_PERIOD,
        TimeUnit.SECONDS
    );
}
```

关键参数：

- `initialDelay = 0`：boot 后立即进入第一次调度。
- `period = Config.Collector.HEARTBEAT_PERIOD`：默认 `30` 秒。
- 线程名前缀是 `SkywalkingAgent-<序号>-ServiceManagementClient-`，可以用 `jstack` 直接看到。

对应的 `agent.config`：

```properties
collector.heartbeat_period=${SW_AGENT_COLLECTOR_HEARTBEAT_PERIOD:30}
collector.properties_report_period_factor=${SW_AGENT_COLLECTOR_PROPERTIES_REPORT_PERIOD_FACTOR:10}
collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:127.0.0.1:11800}
```

`collector.grpc_channel_check_interval` 默认也是 30 秒，但它控制的是 channel 重连检查，不是心跳，排查时不要和 `heartbeat_period` 混在一起。

## 2. “注册”实际是 `reportInstanceProperties`

`ServiceManagementClient.run()` 每 30 秒执行一次。只有 channel 处于 `CONNECTED` 且 stub 已创建时才发请求：

```java
// ServiceManagementClient.java:97-134（精简）
@Override
public void run() {
    if (GRPCChannelStatus.CONNECTED.equals(status)) {
        try {
            if (managementServiceBlockingStub != null) {
                if (Math.abs(
                    sendPropertiesCounter.getAndAdd(1)) % Config.Collector.PROPERTIES_REPORT_PERIOD_FACTOR == 0) {

                    managementServiceBlockingStub
                        .withDeadlineAfter(GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS)
                        .reportInstanceProperties(InstanceProperties.newBuilder()
                            .setService(Config.Agent.SERVICE_NAME)
                            .setServiceInstance(Config.Agent.INSTANCE_NAME)
                            .addAllProperties(OSUtil.buildOSInfo(...))
                            .addAllProperties(SERVICE_INSTANCE_PROPERTIES)
                            .addAllProperties(LoadedLibraryCollector.buildJVMInfo())
                            .build());

                } else {
                    final Commands commands = managementServiceBlockingStub
                        .withDeadlineAfter(GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS)
                        .keepAlive(InstancePingPkg.newBuilder()
                            .setService(Config.Agent.SERVICE_NAME)
                            .setServiceInstance(Config.Agent.INSTANCE_NAME)
                            .build());

                    ServiceManager.INSTANCE.findService(CommandService.class)
                                  .receiveCommand(commands);
                }
            }
        } catch (Throwable t) {
            LOGGER.error(t, "ServiceManagementClient execute fail.");
            ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
        }
    }
}
```

这段代码说明：

- 没有单独的 `register` RPC；“注册”就是发送 `service`、`serviceInstance` 和一批属性。
- 请求是 unary，不是 stream。
- 每次调用都有 `GRPC_UPSTREAM_TIMEOUT`（默认 30 秒）的 deadline。
- `reportInstanceProperties` 的返回值在这个分支里被忽略。
- `keepAlive` 的返回值 `Commands` 会交给 `CommandService`，这是 OAP 下发命令的入口。

### 发送节奏

`sendPropertiesCounter` 从 0 开始，默认 `PROPERTIES_REPORT_PERIOD_FACTOR = 10`：

| 连上后的 tick | 发送内容 | 作用 |
|---:|---|---|
| 0 | `reportInstanceProperties` | 首次注册 + 上报 OS/JVM/自定义属性 |
| 1 ~ 9 | `keepAlive` | 心跳 |
| 10 | `reportInstanceProperties` | 重新上报属性 |
| 11 ~ 19 | `keepAlive` | 心跳 |
| 20 | `reportInstanceProperties` | 重新上报属性 |

也就是：

- 心跳周期默认 **30 秒**。
- 属性重报周期默认 **30 × 10 = 300 秒**。
- 第 10 个 tick 发的是属性上报，不是 `keepAlive`，但 OAP 侧同样会更新 instance，所以存活语义仍然成立。

严格说，“启动时注册”不是一个同步的启动步骤。更准确的说法是：

> `boot()` 之后，第一个 `status == CONNECTED` 的定时 tick 发出 `reportInstanceProperties`；如果启动时尚未连上，就跳过，等下一个 30 秒 tick。

### service / instance 从哪里来

`service` 直接来自配置：

```java
// Config.java:57
public static String SERVICE_NAME = "";
```

`instance` 如果未显式配置，会在启动时自动生成：

```java
// ServiceInstanceGenerator.java:30-38
public class ServiceInstanceGenerator implements BootService {
    @Override
    public void prepare() throws Throwable {
        if (!isEmpty(Config.Agent.INSTANCE_NAME)) {
            return;
        }
        Config.Agent.INSTANCE_NAME =
            UUID.randomUUID().toString().replaceAll("-", "") + "@" + OSUtil.getIPV4();
    }
}
```

对应配置：

```properties
agent.service_name=${SW_AGENT_NAME:Your_ApplicationName}
agent.instance_name=${SW_AGENT_INSTANCE_NAME:}
```

## 3. 心跳是 `keepAlive`

心跳复用同一个定时任务，只是走 `else` 分支：

```java
// ServiceManagementClient.java:119-126
final Commands commands = managementServiceBlockingStub.withDeadlineAfter(
    GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS
).keepAlive(InstancePingPkg.newBuilder()
    .setService(Config.Agent.SERVICE_NAME)
    .setServiceInstance(Config.Agent.INSTANCE_NAME)
    .build());

ServiceManager.INSTANCE.findService(CommandService.class).receiveCommand(commands);
```

它和注册共享同一个 channel，目标地址来自：

```properties
collector.backend_service=127.0.0.1:11800
```

多个地址时由 `GRPCChannelManager` 随机选一个建 channel，失败后重连：

```java
// GRPCChannelManager.java:132-151（精简）
int index = Math.abs(random.nextInt()) % grpcServers.size();
...
managedChannel = GRPCChannel.newBuilder(ipAndPort[0], Integer.parseInt(ipAndPort[1]))
    .addManagedChannelBuilder(new StandardChannelBuilder())
    .addManagedChannelBuilder(new TLSChannelBuilder())
    .addChannelDecorator(new AgentIDDecorator())
    .addChannelDecorator(new AuthenticationDecorator())
    .build();
reconnect = false;
notify(GRPCChannelStatus.CONNECTED);
```

注意：`notify(CONNECTED)` 发生在 channel 刚 `build()` 之后，并没有等待 gRPC 真正进入 `READY`。后面的 RPC 如果失败，会走 `reportError` 再重连。

## 4. 为什么说“不握手、不校验身份、不做能力协商”

### 4.1 连接成功只创建 stub

```java
// ServiceManagementClient.java:57-66
@Override
public void statusChanged(GRPCChannelStatus status) {
    if (GRPCChannelStatus.CONNECTED.equals(status)) {
        Channel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
        managementServiceBlockingStub = ManagementServiceGrpc.newBlockingStub(channel);
    } else {
        managementServiceBlockingStub = null;
    }
    this.status = status;
}
```

没有 `hello`、`version`、`capabilities` 之类的调用。状态 `CONNECTED` 只表示“channel 对象已经建好”。

### 4.2 身份校验只有可选的 token header

```java
// AuthenticationDecorator.java:39-42
@Override
public Channel build(Channel channel) {
    if (StringUtil.isEmpty(Config.Agent.AUTHENTICATION)) {
        return channel;
    }
    ...
}
```

配置了 `agent.authentication` 才加 header：

```java
// AuthenticationDecorator.java:50-52
headers.put(AUTH_HEAD_HEADER_NAME, Config.Agent.AUTHENTICATION);
super.start(responseListener, headers);
```

这是应用层 token。传输层 TLS 是另一条独立配置（`agent.force_tls`、`agent.ssl_trusted_ca_path`），不在 `ServiceManagementClient` 里做服务端身份判断。

### 4.3 proto 只有两个 unary 方法

```proto
// skywalking-data-collect-protocol .../management/Management.proto:32-43
service ManagementService {
    // Report custom properties of a service instance.
    rpc reportInstanceProperties (InstanceProperties) returns (Commands) {
    }

    // Keep the instance alive in the backend analysis.
    rpc keepAlive (InstancePingPkg) returns (Commands) {
    }
}
```

没有版本字段、没有能力字段、没有握手 RPC。任何能说这套 proto 的 gRPC 服务端，Agent 都会往里发。

## 5. OAP 侧如何“登记”

OAP 的 gRPC handler 只是把请求转给 `ManagementServiceHandler`：

```java
// skywalking v9.4.0
// .../ManagementServiceGRPCHandler.java:37-48
@Override
public void reportInstanceProperties(final InstanceProperties request,
                                     final StreamObserver<Commands> responseObserver) {
    responseObserver.onNext(handler.reportInstanceProperties(request));
    responseObserver.onCompleted();
}

@Override
public void keepAlive(final InstancePingPkg request,
                      final StreamObserver<Commands> responseObserver) {
    responseObserver.onNext(handler.keepAlive(request));
    responseObserver.onCompleted();
}
```

`reportInstanceProperties` 构造 `ServiceInstanceUpdate` 并交给 `SourceReceiver`：

```java
// .../ManagementServiceHandler.java:62-84（精简）
public Commands reportInstanceProperties(final InstanceProperties request) {
    ServiceInstanceUpdate serviceInstanceUpdate = new ServiceInstanceUpdate();
    final String serviceName = namingControl.formatServiceName(request.getService());
    final String instanceName = namingControl.formatInstanceName(request.getServiceInstance());
    serviceInstanceUpdate.setServiceId(IDManager.ServiceID.buildId(serviceName, true));
    serviceInstanceUpdate.setName(instanceName);

    // properties / timeBucket ...

    sourceReceiver.receive(serviceInstanceUpdate);
    return Commands.newBuilder().build();
}
```

`keepAlive` 除了写 `ServiceInstanceUpdate`，还会写 `ServiceMeta`：

```java
// .../ManagementServiceHandler.java:87-105（精简）
public Commands keepAlive(final InstancePingPkg request) {
    ...
    ServiceInstanceUpdate serviceInstanceUpdate = new ServiceInstanceUpdate();
    serviceInstanceUpdate.setServiceId(IDManager.ServiceID.buildId(serviceName, true));
    serviceInstanceUpdate.setName(instanceName);
    serviceInstanceUpdate.setTimeBucket(timeBucket);
    sourceReceiver.receive(serviceInstanceUpdate);

    ServiceMeta serviceMeta = new ServiceMeta();
    serviceMeta.setName(serviceName);
    serviceMeta.setTimeBucket(timeBucket);
    sourceReceiver.receive(serviceMeta);

    return Commands.newBuilder().build();
}
```

所以“注册”并不是 OAP 里的一个独立注册接口，而是 Agent 上报 `service + serviceInstance`，OAP 侧把它当成 `ServiceInstanceUpdate` 做 upsert。

## 6. mock-OAP starter 的实现

mock-OAP 在 `AgentShadedGrpcLauncher` 里注册的 SkyWalking native 服务如下（OTLP trace 通过 `additionalServices` 追加）：

```java
// oap/skywalking-oap-mock-spring-boot-starter/src/main/java/.../AgentShadedGrpcLauncher.java
NettyServerBuilder builder = NettyServerBuilder.forPort(configuredPort)
    .maxInboundMessageSize(maxInboundMessageSize)
    .addService(trace)
    .addService(logs)
    .addService(new ConfigurationDiscoveryServiceImpl())
    .addService(new ManagementServiceImpl())
    .addService(new MeterReportServiceImpl())
    .addService(jvm);
```

- `ManagementServiceImpl` 实现 `reportInstanceProperties` 与 `keepAlive`，返回空 `Commands`。未安装 `logfile-reporter-plugin` 的 Agent 不会再收到 `UNIMPLEMENTED`，`ServiceManagementClient` 也不会每 30 秒打印 `ServiceManagementClient execute fail.`。
- `MeterReportServiceImpl` 实现 `collect` / `collectBatch`，把客户端流读完后返回空 `Commands`；Go 等 Agent 的 meter 上报不再报 `Method not found`。
- 两者都只做确认，不保存数据：没有实例注册表、最后心跳时间，也没有 meter 缓存。需要“在线实例”或 meter 曲线时，要在此基础上补存储。
- `UNIMPLEMENTED` 不在 `GRPCChannelManager.isNetworkError(...)` 的重连集合里；这条结论仍然成立，只是现在不会再触发。

## 7. 本仓库定制 Agent 的例外

本仓库的 `logfile-reporter-plugin` 会把核心类整个替换掉：

```java
// agent/logfile-reporter-plugin/.../override/ServiceManagementLocalClient.java:29-30
@OverrideImplementor(ServiceManagementClient.class)
public class ServiceManagementLocalClient extends ServiceManagementClient implements BootService {
```

它的 `boot()` 和 `run()` 都是空实现：

```java
// :50-54
@Override
public void boot() {
    LOGGER.debug("ServiceManagementLocalClient running. do not super 【boot】 method");
    //super.boot();
}

// :88-91
@Override
public void run() {
    LOGGER.debug("ServiceManagementLocalClient running. do not super 【run】 method");
}
```

同时 `MemoryModeGRPCChannelManager` 关闭了 channel manager：

```java
// agent/logfile-reporter-plugin/.../override/MemoryModeGRPCChannelManager.java:13-14
@OverrideImplementor(GRPCChannelManager.class)
public class MemoryModeGRPCChannelManager extends GRPCChannelManager {

// :23-41
@Override
public void boot() {
    LOGGER.info("### [MemoryMode] GRPCChannelManager has been disabled. No network threads will be created.");
    modify();
}

// :81-87
@Override
public Channel getChannel() {
    return null;
}
```

这两个类都在 `META-INF/services/org.apache.skywalking.apm.agent.core.boot.BootService` 里注册。只要 `logfile-reporter-plugin` 的 jar 被放进 agent 的 `plugins/`，ServiceManager 就会用它们替换默认实现，结果是：

- 不创建 `ServiceManagementClient` 的心跳线程；
- 不创建 `GRPCChannelManager` 的重连线程；
- 不向 collector 发注册或心跳；
- trace / log / JVM 指标改由本地 reporter 处理。

典型安装位置（把 `<agent-home>` 换成实际 agent 目录）：

```text
<agent-home>\plugins\logfile-reporter-plugin-2.0.0.jar
```

如果排查“为什么没有心跳”，先检查这个 jar 是否在 `plugins/` 下。

## 8. 本机验证

### 8.1 确认核心类签名

```powershell
javap -p -classpath "$env:USERPROFILE\.m2\repository\org\apache\skywalking\apm-agent-core\9.4.0\apm-agent-core-9.4.0.jar" `
  org.apache.skywalking.apm.agent.core.remote.ServiceManagementClient
```

预期能看到：

```text
implements ...BootService, java.lang.Runnable, ...GRPCChannelListener
private volatile java.util.concurrent.ScheduledFuture<?> heartbeatFuture;
private volatile java.util.concurrent.atomic.AtomicInteger sendPropertiesCounter;
public void boot();
public void run();
```

### 8.2 看运行时线程

```powershell
jstack <pid> | findstr /I "ServiceManagementClient GRPCChannelManager"
```

原生实现应该能看到：

```text
SkywalkingAgent-*-ServiceManagementClient-0
SkywalkingAgent-*-GRPCChannelManager-0
```

如果两个都看不到，可能是 `logfile-reporter-plugin` 的 override 生效了，也可能是 agent 没启动、没连上 collector 或 `collector.backend_service` 为空。

### 8.3 看每次 tick 的日志

临时把 agent 日志级别调到 DEBUG：

```properties
logging.level=DEBUG
```

然后观察：

```text
ServiceManagementClient running, status:CONNECTED.
```

默认每 30 秒一条。把 `collector.heartbeat_period` 调成 10 可以更快看到。

### 8.4 确认 override 是否生效

先把 `$AGENT_HOME` 换成实际 agent 目录：

```powershell
$AGENT_HOME = "<agent-home>"
javap -p -c -classpath "$AGENT_HOME\plugins\logfile-reporter-plugin-2.0.0.jar" `
  org.apache.skywalking.apm.plugin.dynamic.override.ServiceManagementLocalClient
```

`boot()` / `run()` 的字节码如果只有日志和 `return`，说明心跳和注册已经被这个插件关闭。

## 9. 源码索引

- [SkyWalkingAgent.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent/src/main/java/org/apache/skywalking/apm/agent/SkyWalkingAgent.java#L110-L117)
- [ServiceManager.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/boot/ServiceManager.java#L41-L47)
- [ServiceManagementClient.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/ServiceManagementClient.java#L57-L134)
- [Config.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/conf/Config.java#L186-L207)
- [ServiceInstanceGenerator.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/ServiceInstanceGenerator.java#L30-L38)
- [GRPCChannelManager.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/GRPCChannelManager.java#L128-L160)
- [AuthenticationDecorator.java](https://github.com/apache/skywalking-java/blob/v9.4.0/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/AuthenticationDecorator.java#L35-L58)
- [OAP ManagementServiceGRPCHandler.java](https://github.com/apache/skywalking/blob/v9.4.0/oap-server/server-receiver-plugin/skywalking-management-receiver-plugin/src/main/java/org/apache/skywalking/oap/server/receiver/register/provider/handler/v8/grpc/ManagementServiceGRPCHandler.java#L37-L48)
- [OAP ManagementServiceHandler.java](https://github.com/apache/skywalking/blob/v9.4.0/oap-server/server-receiver-plugin/skywalking-management-receiver-plugin/src/main/java/org/apache/skywalking/oap/server/receiver/register/provider/handler/v8/ManagementServiceHandler.java#L62-L106)
- [Management.proto](https://github.com/apache/skywalking-data-collect-protocol/blob/bd1f91f7e1cb4de9d9b5ccb71f36ce6b1c7c97f5/management/Management.proto#L32-L43)
