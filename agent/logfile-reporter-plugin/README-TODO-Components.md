

# 关注 GRPCChannelListener/IConsumer 实现类, 这个就是负责向OAP侧发送数据的

~~ServiceManagementClient     # HEARTBEAT上报OS和配置项信息~~

LogReportServiceClient      # 日志信息

EventReportServiceClient    # 上报启动/停止事件

~~JVMMetricsSender            # JVM Metrics~~


# TODO

ProfileTaskChannelService
ProfileSnapshotSender
MeterSender
AsyncProfilerTaskExecutionService   # 使用 AsyncProfilerDataSender 
AsyncProfilerDataSender