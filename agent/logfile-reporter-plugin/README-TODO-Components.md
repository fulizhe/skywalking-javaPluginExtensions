

# 关注 GRPCChannelListener/IConsumer 实现类, 这个就是负责向OAP侧发送数据的

~~ServiceManagementClient     # HEARTBEAT上报OS和配置项信息~~

~~LogReportServiceClient      # 日志信息~~

EventReportServiceClient    # 上报启动/停止事件

~~MeterSender                 # send Metrics data of meter system~~

~~JVMMetricsSender            # JVM Metrics~~

# 参考
可以通过查看[`kafka-reporter-plugin`](https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaJVMMetricsSender.java)这个组件下的类型来快速确定哪些类型可以被考虑
似乎基本都覆盖了

# profile

ProfileTaskCommand  
ProfileTaskCommandExecutor

# TODO
1
ProfileTaskChannelService             # 将 profiling task result data 发送到OAP
~~ProfileSnapshotSender               #~~
AsyncProfilerTaskExecutionService   # 使用 AsyncProfilerDataSender 

AsyncProfilerDataSender   # 异步
ProfileSnapshotSender     # 同步， ProfileTaskChannelService中使用

AsyncProfilerTaskChannelService
ProfileTaskChannelService

数据库监控
Threadpool监控


手动触发command执行的方式： 手动触发command执行的方式：  CommandExecutorService.execute(final BaseCommand command); 或者：CommandService.receiveCommand(Commands commands)
	1. ProfileTaskCommand
	2. 