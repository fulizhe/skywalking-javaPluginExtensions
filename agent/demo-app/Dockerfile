# syntax=docker/dockerfile:1

# 持续压测镜像:logfile-reporter-plugin(构建工具链 JDK17 / release 8)
#   + demo-app(JDK 8) + SkyWalking Java Agent 9.4.0
# 由同目录 docker-compose.yaml 编排:demo-app 常驻,stress 侧持续压测观测插件稳定性。

# ---- 1. 构建 logfile-reporter-plugin(JDK 17 工具链,产物字节码 8;见 docs/adr/adr-01)----
FROM maven:3.8.6-eclipse-temurin-17 AS plugin-build
WORKDIR /src
# agent/pom.xml 的 <modules> 列出了全部同级插件,Maven 解析聚合 pom 时要求这些目录存在,
# 即便 -pl 只构建 logfile-reporter-plugin;因此整棵 agent 树都要拷进来。
COPY agent agent
RUN mvn -s agent/settings.xml -f agent/pom.xml clean package \
        -pl logfile-reporter-plugin -am -Dmaven.test.skip=true -T 2C

# ---- 2. 构建 demo-app(JDK 8),并预热压测测试类与依赖 ----
FROM maven:3.8.6-eclipse-temurin-8 AS app-build
WORKDIR /src
COPY agent/settings.xml agent/settings.xml
COPY agent/demo-app agent/demo-app
RUN mvn -s agent/settings.xml -f agent/demo-app/pom.xml clean package -DskipTests
# 预跑一次(目标不可达会断言失败,但会把 surefire provider 等依赖拉进本地仓库,便于离线复跑)
RUN mvn -s agent/settings.xml -f agent/demo-app/pom.xml \
        -Dtest=HttpLoadTest -Dloadtest=true -Dloadtest.baseUrl=http://127.0.0.1:1 \
        -Dloadtest.durationSec=1 -Dloadtest.threads=1 test || true

# ---- 3. 运行时:JDK 8 + Maven + agent + demo-app + 压测源码 ----
FROM maven:3.8.6-eclipse-temurin-8 AS runtime

ARG SKYWALKING_AGENT_VERSION=9.4.0
ADD https://archive.apache.org/dist/skywalking/java-agent/${SKYWALKING_AGENT_VERSION}/apache-skywalking-java-agent-${SKYWALKING_AGENT_VERSION}.tgz /tmp/skywalking-agent.tgz
RUN tar -xzf /tmp/skywalking-agent.tgz -C /opt && rm /tmp/skywalking-agent.tgz && \
    apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=plugin-build /src/agent/logfile-reporter-plugin/target/logfile-reporter-plugin-2.0.0.jar /opt/skywalking-agent/plugins/
COPY --from=app-build /src/agent/demo-app /opt/demo-app
COPY agent/settings.xml /opt/demo-app/settings.xml
COPY --from=app-build /root/.m2 /root/.m2

WORKDIR /opt/demo-app
ENV WebPort=9600
EXPOSE 9600
CMD ["java", "-javaagent:/opt/skywalking-agent/skywalking-agent.jar", "-jar", "/opt/demo-app/target/demo-app-1.0.0.jar"]
