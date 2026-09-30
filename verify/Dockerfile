# verify 运行器镜像:一个以 Linux/Docker + bash 为第一路径的场景验证回路所用镜像。
# 与 agent/demo-app/Dockerfile(持续压测)不同,本镜像只用固定工具链跑"一个场景=构建+启动+断言",
# 由 verify/run-scenario.sh 作为入口(见 verify/README.md)。
#
# 刻意不写 `# syntax=docker/dockerfile:1`:本文件无需 BuildKit 专有语法,且该指令会强制拉取
# 前端镜像(在受限网络/镜像源下易失败);用 Docker 内置前端即可。

# JDK17 构建工具链(插件 release 8 需 JDK 9+ 编译器,见 adr-01);仅取 JDK,不留 maven。
FROM maven:3.8.6-eclipse-temurin-17 AS jdk17

# 运行器:JDK8 运行时 + Maven(宿主的 JAVA_HOME=bundled JDK8;JDK17 复制到 /opt/jdk17)。
FROM maven:3.8.6-eclipse-temurin-8 AS runner
COPY --from=jdk17 /opt/java/openjdk /opt/jdk17

# SkyWalking Java Agent 发行包(与插件锁定一致;口径同 agent/demo-app/Dockerfile)。
# 刻意用 curl + 重试而非 `ADD https://...`:ADD 的远端拉取经代理时易 flaky(unexpected EOF),
# curl 可重试/可诊断。tar 顶层目录即 skywalking-agent/(发行包内命名),解到 /opt 即得 /opt/skywalking-agent。
ARG SKYWALKING_AGENT_VERSION=9.4.0
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 \
      -o /tmp/skywalking-agent.tgz \
      "https://archive.apache.org/dist/skywalking/java-agent/${SKYWALKING_AGENT_VERSION}/apache-skywalking-java-agent-${SKYWALKING_AGENT_VERSION}.tgz" \
 && tar -xzf /tmp/skywalking-agent.tgz -C /opt \
 && rm /tmp/skywalking-agent.tgz

COPY verify/lib.sh /opt/verify/lib.sh
COPY verify/run-scenario.sh /opt/verify/run-scenario.sh
RUN chmod +x /opt/verify/*.sh

ENV JAVA17_HOME=/opt/jdk17
ENV JAVA8_HOME=/opt/java/openjdk
WORKDIR /src
ENTRYPOINT ["bash", "/opt/verify/run-scenario.sh"]
