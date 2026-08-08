# 组件覆盖状态

本插件把原本发往 OAP 的各类数据截留在本地内存。覆盖面以 `src/main/resources/META-INF/services/org.apache.skywalking.apm.agent.core.boot.BootService` 的注册清单为准。

## 关注点

关注 **GRPCChannelListener / IConsumer 实现类**，它们就是负责向 OAP 侧发送数据的。

## 已覆盖

| 原组件（发往 OAP） | 本地实现 | 方式 | 数据去向 / 备注 |
|--------------------|----------|------|-----------------|
| ~~ServiceManagementClient~~ | `ServiceManagementLocalClient` | @OverrideImplementor | HEARTBEAT 上报 OS 和配置项信息 |
| ~~LogReportServiceClient~~ | `LogReportServiceLocalClient` | @OverrideImplementor | 日志信息 |
| ~~MeterSender~~ | `MeterLocalSender` | @OverrideImplementor | send Metrics data of meter system |
| ~~JVMMetricsSender~~ | `JVMMetricsLocalSender` | @OverrideImplementor | JVM Metrics |
| EventReportServiceClient | `NoOpEventReportServiceClient` | @OverrideImplementor | 上报启动/停止事件 |
| TraceSegmentServiceClient | `LogFileTraceSegmentServiceClient` | @OverrideImplementor | 内存 LRU 缓存（供日志上报） |
| ProfileSnapshotSender | `ProfileSnapshotLocalSender` | @OverrideImplementor | profile 快照（原任务发往 OAP） |
| ProfileTaskChannelService | `NoOpProfileTaskChannelService` | @OverrideImplementor | 将 profiling task result data 发送到 OAP |
| AsyncProfilerTaskChannelService | `NoOpAsyncProfilerTaskChannelService` | @OverrideImplementor | 异步，AsyncProfiler 任务通道 |
| CommandService | `NoOpCommandService` | @OverrideImplementor | 无（无命令下发） |
| ConfigurationDiscoveryService | `NoOpConfigurationDiscoveryService` | @OverrideImplementor | 无（无配置拉取） |
| GRPCChannelManager | `MemoryModeGRPCChannelManager` | @OverrideImplementor | 无（无 gRPC 通道） |

## Profile 发送链路

- `AsyncProfilerDataSender`（异步）
- `ProfileSnapshotSender`（同步，在 `ProfileTaskChannelService` 中使用）
- `AsyncProfilerTaskChannelService` / `ProfileTaskChannelService`（任务通道，均已覆盖为 NoOp）
- `ProfileTaskChannelService`             # 将 profiling task result data 发送到OAP

## 手动触发 command 执行

- `CommandExecutorService.execute(final BaseCommand command)`
- 或 `CommandService.receiveCommand(Commands commands)`

示例：
1. `ProfileTaskCommand`
2. `ProfileTaskCommandExecutor`

## 待办

- **数据库监控**：暂未覆盖。
- **Threadpool 监控**：暂未覆盖。

## 参考

可以通过查看 [`kafka-reporter-plugin`](https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaJVMMetricsSender.java) 组件下的类型来快速确定哪些类型可以被考虑。似乎基本都覆盖了。
