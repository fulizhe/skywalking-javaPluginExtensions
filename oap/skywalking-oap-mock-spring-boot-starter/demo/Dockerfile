# mock-oap-demo 运行镜像:JDK 17 + SkyWalking agent 9.4.0 + demo fat jar。
#
# agent 不下载,直接从本机已有的 demo-app-agent:9.4.0 里拷 —— 版本与
# demo/pom.xml 的 <skywalking.version> 一致(必须一致,否则插件与后端协议可能不匹配)。
#
# 构建前置:
#   1. demo/target/mock-oap-demo-2.0.0.jar 已存在(mvn -B package)
#   2. 本机有 demo-app-agent:9.4.0 与 openjdk:17-jdk-slim 两个镜像
FROM demo-app-agent:9.4.0 AS agent-src

FROM openjdk:17-jdk-slim

COPY --from=agent-src /opt/skywalking-agent /opt/skywalking-agent

# demo-app 那个 agent 镜像里装了 logfile-reporter-plugin,它用
#   @OverrideImplementor(TraceSegmentServiceClient.class)
# 把 trace(以及 JVM 指标)的上报**替换成写本地文件**,于是 11800 永远收不到 segment
# —— 表现为 status.running=true、日志能收到(走的是另一个客户端)、但一条链路都没有。
# 本 demo 要的就是原生 gRPC 上报,所以把那个插件 jar 摘掉,恢复原版行为。
RUN rm -f /opt/skywalking-agent/plugins/logfile-reporter-plugin-*.jar

COPY target/mock-oap-demo-2.0.0.jar /app/app.jar

WORKDIR /app
# 9600 = 页面/读口,11800 = 进程内 mock-OAP(gRPC collector,跨容器可达)
EXPOSE 9600 11800

ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx384m", \
  "-javaagent:/opt/skywalking-agent/skywalking-agent.jar", \
  "-jar", "/app/app.jar"]
