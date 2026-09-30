# 持续压测 / 本地浏览镜像:logfile-reporter-plugin(构建工具链 JDK17 / release 8)
#   + demo-app(JDK 8) + SkyWalking Java Agent 9.4.0
# 由同目录 docker-compose.yaml 编排:demo-app 常驻(本地浏览入口),stress 侧持续压测观测插件稳定性。
#
# 刻意不写 `# syntax=docker/dockerfile:1`:本文件无需 BuildKit 专有语法,而该指令会强制拉取
# 前端镜像(受限网络/镜像源下易失败);用 Docker 内置前端即可(同 verify/Dockerfile)。

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
# 刻意用 curl + 重试而非 `ADD https://...`:ADD 的远端拉取经代理时易 flaky(unexpected EOF),
# curl 可重试/可诊断(与 verify/Dockerfile 同口径)。tar 顶层目录即 skywalking-agent/,解到 /opt 即得。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 \
         -o /tmp/skywalking-agent.tgz \
         "https://archive.apache.org/dist/skywalking/java-agent/${SKYWALKING_AGENT_VERSION}/apache-skywalking-java-agent-${SKYWALKING_AGENT_VERSION}.tgz" \
    && tar -xzf /tmp/skywalking-agent.tgz -C /opt \
    && rm /tmp/skywalking-agent.tgz

# 用通配符而非写死版本号(原为 -2.0.0.jar):插件版本随发布线变动,写死会在升版后静默失效。
# 通配符只匹配 logfile-reporter-plugin-*.jar,不会误中 original-logfile-reporter-plugin-*.jar。
COPY --from=plugin-build /src/agent/logfile-reporter-plugin/target/logfile-reporter-plugin-*.jar /opt/skywalking-agent/plugins/
COPY --from=app-build /src/agent/demo-app /opt/demo-app
COPY agent/settings.xml /opt/demo-app/settings.xml
COPY --from=app-build /root/.m2 /root/.m2

WORKDIR /opt/demo-app
ENV WebPort=9600
EXPOSE 9600
CMD ["java", "-javaagent:/opt/skywalking-agent/skywalking-agent.jar", "-jar", "/opt/demo-app/target/demo-app-1.0.0.jar"]
