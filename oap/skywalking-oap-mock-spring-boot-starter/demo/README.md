# mock-oap-demo

可直接运行的 mock OAP 演示应用：本进程既是业务服务，也是接收端，不需要额外部署 OAP / Elasticsearch，
也不依赖 go / node 等外部服务。页面、演示流量都自带。

## 最快体验

把 agent 解压到任意目录，然后一条命令：

```bat
run.cmd D:\path\to\skywalking-agent\skywalking-agent.jar
```

```bash
bash run.sh /path/to/skywalking-agent/skywalking-agent.jar
```

脚本会：定位 agent → jar 不存在时先构建 starter 和 demo → 挂 agent 启动。
启动完成后控制台最后会打印访问地址；打开 <http://localhost:9600/> 是演示导航（黄页），
先点「生成链路」，再从「采集总览」点 traceId 看链路图。

agent 路径也可以放进环境变量 `SKYWALKING_AGENT_JAR`，或直接把 agent 目录放到 `demo/skywalking-agent/`。

改了 demo 或 starter 源码、或拉了新版本后，强制重建一次：

```bat
set MOCK_OAP_REBUILD=1
run.cmd D:\path\to\skywalking-agent\skywalking-agent.jar
```

```bash
MOCK_OAP_REBUILD=1 bash run.sh /path/to/skywalking-agent/skywalking-agent.jar
```

`run.sh` 会自动比较源码时间戳；Windows 下用上面的环境变量，或直接删掉 `demo/target`。

## 手动构建与启动

前置：JDK 17、Maven、SkyWalking Java Agent **9.4.0**（[下载](https://skywalking.apache.org/downloads/)）。

```bash
# 1. 构建 starter 并装进本地仓库（第一次，或改了 starter 之后）
cd oap/skywalking-oap-mock-spring-boot-starter
mvn -B install -DskipTests

# 2. 构建 demo
cd demo
mvn -B clean package

# 3. 挂 agent 启动（路径换成你自己的 agent）
java -javaagent:/path/to/skywalking-agent/skywalking-agent.jar \
  -jar target/mock-oap-demo-2.0.0.jar
```

Windows PowerShell 下启动：

```powershell
java -javaagent:E:\path\to\skywalking-agent\skywalking-agent.jar -jar target\mock-oap-demo-2.0.0.jar
```

端口：应用 `9600`，collector `11800`。`11800` 正是 agent 默认的 `collector.backend_service`，
所以 agent 侧不需要改配置。

## 页面

| 页面 | 用途 |
|---|---|
| `/` | 演示导航（黄页）：生成演示流量、跳转各页面 |
| `/mock-oap.html` | 采集状态、链路清单、最近日志，演示入口 |
| `/mock-oap-trace.html?traceId=...` | 单条链路的时序 / 树 / 表格 / 列表视图 |
| `/mock-oap-logs.html` | 日志检索（traceId / level / 关键字） |
| `/mock-oap-jvm.html` | JVM 指标曲线 |

## 演示接口

| 接口 | 作用 |
|---|---|
| `GET /demo/chain?steps=3&delayMs=20` | 本进程用 Apache HttpClient 调自己的 `/demo/leaf`，生成多段链路 |
| `GET /demo/slow?delayMs=500` | 慢调用 |
| `GET /demo/error` | 故意抛异常，生成 ERROR span |

每次调用都会打一条带 traceId 的业务日志，因此日志页和链路页的「日志」页签都能看到。

## 数据怎么流动

```text
本进程的 SkyWalking agent
    │  trace / log / JVM metrics(原生 gRPC)
    ▼
127.0.0.1:11800  进程内 collector(starter)
    │
    ▼
有界内存缓存(TraceSegmentBuffer / LogBuffer / JVMMetricBuffer)
    │
    ▼
/mock-oap/* 读接口  →  演示导航(/)与各页面
```

- 数据只在内存，进程重启即清空；容量由 `mock-oap.max-traces`、`max-logs`、`max-jvm-metrics` 控制。
- 没挂 agent 时应用照常启动，status 里 `running=false`，`skipReason` 会说明原因。
- 采集器会拒收正文带 `[mock-oap]` 或 `com.fulizhe.observability.mockoap` 的日志（防止采集器日志形成放大回路），
  所以 demo 的业务日志统一用 `[demo]` 前缀。

## 排错

| 现象 | 处理 |
|---|---|
| `running=false`，`skipReason` 提到 `-javaagent` | 启动命令没加 `-javaagent`，或只是把 agent jar 放进了 classpath |
| `11800` 端口被占用 | 改 `mock-oap.port`，同时把 agent 的 `collector.backend_service` 指到新端口 |
| 有链路、没有日志 | `logback-spring.xml` 的 `SW_GRPC` appender 未加载，或日志正文命中了上面的拒收标记 |
| 中文日志乱码 | 不要给 GRPC appender 的 encoder 设与平台默认不同的 charset；保持缺省即可（本仓库未设置），toolkit 按平台默认解码 |
| 页面能开、清单为空 | 先点「生成链路」；或 agent 没有把段上报到 `127.0.0.1:11800` |

## 说明

demo 里的 Controller 与页面只用于演示；starter 本身不含 Controller、HTML 或 Web 依赖。
接入真实业务时，可参考 `MockOapObservabilityController` 的读接口，或直接注入 buffer Bean 自行展示。
字段含义、Bean 列表与配置项见 [../docs/usage.md](../docs/usage.md)。

## 部署

- 本地起一套：`mvn -B -DskipTests clean package && docker compose up -d` —— 四语言链路
  (Java → Go → C++ → Node)，见 [`docker-compose.yaml`](docker-compose.yaml)。
- 远端 Linux 服务器(镜像搬运、端口、排错表)：
  [`README-remoteDeploy.md`](README-remoteDeploy.md)。
