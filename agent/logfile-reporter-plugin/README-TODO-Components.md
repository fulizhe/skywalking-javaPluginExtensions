

# 关注 GRPCChannelListener/IConsumer 实现类, 这个就是负责向OAP侧发送数据的

~~ServiceManagementClient     # HEARTBEAT上报OS和配置项信息~~

LogReportServiceClient      # 日志信息

EventReportServiceClient    # 上报启动/停止事件

MeterSender                 # send Metrics data of meter system

~~JVMMetricsSender            # JVM Metrics~~

# 参考
可以通过查看`kafka-reporter-plugin`这个组件下的类型来快速确定哪些类型可以被考虑
似乎基本都覆盖了

# TODO

ProfileTaskChannelService
ProfileSnapshotSender               # 
AsyncProfilerTaskExecutionService   # 使用 AsyncProfilerDataSender 
AsyncProfilerDataSender