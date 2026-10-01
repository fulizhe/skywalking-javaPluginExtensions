# demo-app:插件能力演示与验证台

> **第一次来本仓库?** 先读 [`verify/README-starter.md`](../../verify/README-starter.md) —— 那份按学习顺序
> 讲「本地跑起来」:一条命令让它留在运行状态、用浏览器看哪些页面、怎么造数据和亲手造一个告警、
> 出问题按症状怎么查。本 README 是完整参考(全部读口 / 参数 / 压测 / 术语)。

面向三个活跃插件的演示应用与验证台,单目录三块结构:

- **演示应用**:Spring Boot 应用,把插件的本地内存报告可视化——能力导览首页、六个数据流仪表盘、告警验证端点、webhook 接收器,以及两个 override 插件的出口触发端点(Hutool HttpClient / Apache HttpClient);
- **验证回路**:三个活跃插件各有一个容器化场景(`verify/scenarios/`,主路径),`scripts/` 下另有一套本地 `pwsh` 次选回路;
- **能力导览**:本 README + 导览首页(`/`,即 `src/main/resources/static/index.html`),讲清插件是什么、logfile 命名来历、怎么跑。

> 术语(本地内存报告、数据流、统计快照、Trace 告警、运行时开关等)以仓库根 `CONTEXT.md` 为准。
>
> **三种测试场景怎么跑**（本地 Windows pwsh / 本地 compose / 远程 Linux compose 三者并排对照），
> 看 [`README-verify-matrix.md`](README-verify-matrix.md)。
>
> **要在专用 Linux 服务器上做 compose 全面验证与压测**,看
> [`README-remote-verify.md`](README-remote-verify.md) —— 坑集中列在一处(别边跑边踩)。

## 初学者演示(迁移自旧 demo 工程)

本工程还承载一套面向 SkyWalking 初学者的 API 演示,迁移自旧 `sb-skywalking` demo 工程(包名从 `com.fulizhe.demo` 改为 `org.openskywalking.demo`),与上面的插件验证台共存:

- 入口页:<http://127.0.0.1:9600/9527.html>(各演示页导航)、<http://127.0.0.1:9600/doc.html>(knife4j 接口文档);
- 控制器:`HelloController`(hello/异步/@Trace 注解/MyBatis+JDBC 查询/性能剖析等)与 `FullSampleController`(`/fullSample`),见 `src/main/java/org/openskywalking/demo/controller/`;
- SQL 演示数据:内存 H2(`jdbc:h2:mem:dbtest`),启动时由 `schema/schema.sql` + `schema/data.sql` 建表灌数据,`/queryDbByMybatis`(MyBatis)与 `/queryDbByJdbc`(JdbcTemplate)双路可观测;H2 控制台在 <http://127.0.0.1:9600/h2>(JDBC URL `jdbc:h2:mem:dbtest`,用户 `sa`,密码 `123456`);
- 兼容性适配(相对旧工程):H2 2.x 保留字/函数差异(`USER` 表加引号、`sysdate` → `CURRENT_TIMESTAMP`),springfox 2.10 需要 `spring.mvc.pathmatch.matching-strategy=ant_path_matcher`。

## 插件是什么

`logfile-reporter-plugin` 是 SkyWalking Java Agent 的本地内存报告插件:默认情况下 agent 通过 gRPC 把监控数据上报给 OAP 后端;插件拦截并改写这条通路,把六类数据流(链路段 / JVM 指标 / meter 指标 / 应用日志 / 心跳·实例属性 / profile 快照)写入应用进程内的有界缓存,通过宿主工具类暴露的 JSON 接口实时可查,并提供 Trace 告警(HTTP webhook 通知)与运行时开关(不重启即启用/禁用写入)。

OAP 模式与本地内存报告模式的对比如下:

| 维度 | OAP 模式(默认) | 本地内存报告(本插件) |
| --- | --- | --- |
| 数据去向 | gRPC 上报 OAP,聚合存储 | 进程内有界缓存,JSON 接口自读 |
| 可视化 | SkyWalking UI | 应用自带仪表盘(`/dashboards/`) |
| 依赖 | 需部署 OAP 后端 | 无后端,OAP-less 单应用自足 |
| 适用 | 生产级全链路 | 开发调试 / 离线自查 / 能力演示 |

"logfile" 命名的来历:最初设想把监控数据写入日志文件,实现中受 Alibaba Druid 内存缓存启发改为内存存储,名字沿用至今——它并不读取日志文件。

## 快速上手

前置要求:JDK 8(演示运行时默认)或 JDK 17(经 `-JavaHome`);插件构建工具链需 JDK 17(见 `logfile-reporter-plugin/README-compile.md`)、Maven 3。SkyWalking Java Agent **9.4.0** 可由 setup 脚本自动安装(见方式一);版本与插件锁定一致。

### 方式零:环境自足(setup,推荐首跑)

```powershell
pwsh ./scripts/setup.ps1
```

在任意 Windows 机器上跑一次即可备齐环境:检测本机 agent 9.4.0,缺失时从 Apache 官方发行包下载(`.tgz`,校验 SHA512,解压到默认 `D:\apps\apache-skywalking-java-agent-9.4.0`,可用 `-AgentDir` 指定);构建并安装插件 jar 至 `plugins/`;构建 demo-app;最后产出与 `run-with-agent.ps1` 同构的启动命令。重复执行幂等——agent 已存在则跳过下载。

### 方式 A:一键验证(推荐)

```powershell
pwsh ./scripts/validate.ps1
```

该命令完成:构建插件 jar(`mvn clean package` 于 `agent/logfile-reporter-plugin`)→ 拷贝进 agent `plugins/` 目录 → 以 `-javaagent` + 全部 `-Dskywalking.*` 参数启动 demo → 等待就绪 → 造数 → 断言 → 报告。失败时以非零退出码结束。

常用参数:

| 参数 | 说明 |
| --- | --- |
| `-SkipPluginBuild` | 插件已构建,跳过 maven 直接安装启动 |
| `-SkipAppBuild` | 复用已有 demo-app jar,跳过重建(快速路径;源码变更不会生效,自担过期风险) |
| `-SkipPluginInstall` | 负向模式:故意不安装插件,预期在加载检查处大声失败(exit 3) |
| `-Port <n>` | 应用端口,默认 9600(全仓库统一) |
| `-AgentDir <dir>` | agent 目录,默认 `D:\apps\apache-skywalking-java-agent-9.4.0`(或 `SKYWALKING_AGENT_DIR`) |
| `-JavaHome <dir>` | 演示运行时 JDK,默认扫描 `D:\apps\java` 下 JDK8,或取 `JAVA_HOME`;JDK 17 显式传入(如 `D:\apps\java\jdk-17.0.8`) |
| `-BuildJavaHome <dir>` | 插件构建工具链 JDK,默认扫描本机 `jdk-17*`,或取演示运行时 JDK;产物字节码恒为 8 |

退出码:`0` 全绿;`1` 前置失败(路径/构建/安装);`2` 应用未就绪;`3` 插件未安装/未加载;`5` 断言失败。

### 方式 B:手动调试模式

```powershell
pwsh ./scripts/run-with-agent.ps1 -SkipPluginBuild
```

与脚本模式同参,以同样的 `-javaagent`/`-Dskywalking.*` 参数启动应用并保持运行;启动完成后浏览器打开 <http://127.0.0.1:9600/> 查看导览首页,或 `/dashboards/index.html` 进入各仪表盘。

### 方式 C:纯应用启动(不含 agent)

```powershell
pwsh ./scripts/start-demo.ps1
```

只启动 demo 应用本身(无 javaagent),用于单独调试页面与 JSON 契约。

## 验证回路

> **主路径(容器化 bash)**:`bash verify/run.sh` —— Linux/Docker + bash 场景回路,在任意环境(含 CI)一条命令完成"构建插件 → 构建 demo → 装配 agent → 启动 → 断言 → 报告"。见仓库根 [`verify/README.md`](../../verify/README.md)。
> 下面的 `scripts/*.ps1` 是**次选**(本地 Windows 调试/快速排查),与 verify 断言同一批 HTTP 契约。

`validate.ps1` 即 demo-app 的验收测试,断言范围(与 spec User Stories 对应):

- **A. trace 缓存合并**:同一 traceId 下入口+出口多 segment 合并在一个条目,span 字段齐全;
- **B. 告警链端到端**:慢(默认阈值 + Ant 规则 `/api/order/*=8000`)/ 错(isError + HTTP 500)命中 → webhook 收讫计数;豁免规则(ignore-rule)命中与对照请求 → 无事件;
- **C. 运行时开关**:`POST /toggle?enable=false` 后统计停止增长,`enable=true` 后恢复;
- **附**:插件加载验证(agent 日志)、五类数据流读口冒烟(JVM / meter / 日志 / 实例属性 / 告警运行态)。

手动触发告警验证端点(浏览器或 curl,应用于方式 B 启动后):

- `GET /api/trace-alert-demo/slow?ms=4000` — 慢请求(默认阈值)
- `GET /api/order/1` — 慢请求(Ant 规则 8000ms)
- `GET /api/trace-alert-demo/error` — 未捕获异常
- `GET /api/trace-alert-demo/http500` — HTTP 500
- `GET /inner/sw/trace-alert/recent` — 查看已收讫的 webhook 事件

> 本地 HTTP 调用均建议用 `curl.exe --noproxy "*"` 直连(见 ticket 05 记录:交互式 profile 注入的代理默认参数会把回环请求误送外部代理而得到 502)。

### override 插件的验证读口

两个 override 插件(`override-httpclient-4.x`、`override-hutool-http-5.x`)自身不提供数据读口,
span/tag 一律由 `logfile-reporter-plugin` 捕获后经 `/statistic` 读取;运行时开关共用
`SWHttpClientCollectUtils`,读口是 `SWHttpClientCollectController`。对应场景
`verify/scenarios/override-httpclient`、`verify/scenarios/override-hutool`。

Apache HttpClient 出口触发端点:

- `GET /api/trace-alert-demo/httpclient-post?value=X` — 经 HttpClient 向自身 POST 表单体
  (期望出口 span tag `http.request.params=form=value=X`)。

Hutool 出口触发端点(`HutoolHttpDemoController`,插件增强目标 `cn.hutool.http.HttpRequest#execute`;
全部回环自调用,并在响应里回显**业务自己读到的响应体** `businessBody`):

| 端点 | 触发什么 | 期望采到的 tag |
| --- | --- | --- |
| `GET /api/hutool-demo/get-query?marker=X` | hutool GET + query string | `http.request.params=query=marker=X`(官方开关) |
| `GET /api/hutool-demo/post-form?value=X&phase=P` | hutool POST 表单体 | `http.request.params=form=value=X`(override 开关) |
| `GET /api/hutool-demo/post-json?value=X&phase=P` | hutool POST `body(...)` 原始体 | `http.request.params=body={...}` |
| `GET /api/hutool-demo/post-multipart?value=X` | hutool POST multipart(文本字段 + 文件) | `http.request.params=value=X` 与 `http.request.files=file={filename/size/contentType}` |
| `GET /api/hutool-demo/error-call` | hutool GET 目标 500 | 出口 span `isError=true` |

`phase` 会进回环目标路径(`/api/hutool-demo/echo/<phase>`),因此断言可按 `operationName` 精确区分
不同阶段的出口 span。响应体采集开关关断后(`POST /httpclient/collect/toggle?enable=false`),
出口 span 仍在、但 `http.request.params` / `http.response.body` tag 消失 —— 即"监控不改变链路结构,
关断也不影响业务读 body"。

### 压测(可选)

用 `scripts/stress.ps1` 对 demo-app 接口打负载,顺带打印 `Trace 指标`读口摘要,便于填充指标/告警/大屏数据。压测实现是测试源码里自包含的 Java 类 `org.openskywalking.demo.load.HttpLoadTest`(默认不参与 `mvn test`)。

```powershell
# A) 应用已在运行(run-with-agent.ps1 保持运行):直接压测
pwsh ./scripts/stress.ps1 -Requests 2000 -Threads 16

# B) 一条命令:起应用(带 agent) + 压测 + 指标摘要,压测后自动停
pwsh ./scripts/stress.ps1 -StartApp -Requests 2000 -Threads 16

# C) 持续时长模式:压 1 小时
pwsh ./scripts/stress.ps1 -DurationSec 3600 -Threads 16

# D) 无限模式(插件稳定性观测):死循环压测,每 10s 打印吞吐 + 插件计数 + JVM 堆,直到 Ctrl+C
pwsh ./scripts/stress.ps1 -Continuous -Threads 16
pwsh ./scripts/stress.ps1 -StartApp -Continuous -KeepRunning   # 同时起应用
```

**指标稳定性 vs 告警稳定性(分开压)**:告警开关属于"应用启动"这一层,由 `run-with-agent.ps1` 控制:

- **纯指标稳定性**:`pwsh ./scripts/run-with-agent.ps1 -NoAlert` 起实例 + `pwsh ./scripts/stress.ps1 -NormalOnly`(只压正常端点,排除 `/error`、`/http500`)。
- **指标 + 告警耦合**:`run-with-agent.ps1`(默认 alert on) + `stress.ps1`(默认混合路径,含 error);错误请求会触发插件**同步 webhook 回打自身**,形成放大回路。
- **依赖面观测**:`run-with-agent.ps1 -WithDeps` + `stress.ps1 -WithDeps`(与前两条互斥,见下)。
- `stress.ps1 -StartApp` 自身起的应用**不带 alert**(纯指标)。

> `-NormalOnly` 与 `-WithDeps` **互斥**:前者压业务正常端点,后者压依赖造数端点。同时给会直接报错退出(1),
> 避免"以为压了正常端点、实际压的是依赖端点"这种静默偏差。

常用参数:`-BaseUrl`(默认 `http://127.0.0.1:9600`)、`-Requests`、`-Threads`、`-DurationSec`、`-Continuous`、`-ProgressSec`(持续模式打印间隔,默认 10s)、`-Paths`(逗号分隔,默认混合正常/错误/慢)、`-NormalOnly`(只压正常端点)、`-WithDeps`(只压依赖造数端点,与 `run-with-agent.ps1 -WithDeps` 同名同义)、`-KeepRunning`、`-SkipMetrics`。

> 稳定性观测:`-Continuous` 下每轮 `[progress]` 行含 `plugin=` 段,即插件 `/inner/sw/metrics` 的计数
> (`rowsUpserted`/`lateDropped`/`sampleOverflow`/`endpointOverflow`/`persistErrors`/`aggregateErrors`)——
> 持续观察这些计数是否异常增长、以及 `heap=` 是否持续攀升，即可判断插件在长跑下的稳定性。

压测后浏览器打开 `http://127.0.0.1:9600/dashboards/metrics.html`。

## 依赖拓扑演示（依赖面三层 + 真实外呼）

`/dashboards/topology.html` 画的是「我调了哪些外部依赖、哪条路慢」。总览档左侧只有一个
**本服务**节点（窗口内全部入口端点的聚合：合计调用 / 合计错误 / 覆盖端点数），
右侧按**组件类型**排开依赖；端点维度在明细档与页面边列表里。

起齐三层中间件（**默认 profile 不起**，避免拖慢只想看效果的人）——Windows 上两条命令闭环：

```powershell
# 1) 起应用，-WithDeps 先拉起 redis / mysql / kafka（Docker 没起也不报错，只是三层没有边）
pwsh ./scripts/run-with-agent.ps1 -WithDeps

# 2) 压测造数，-WithDeps 让压测路径切到依赖造数端点，边与分位持续累积
pwsh ./scripts/stress.ps1 -WithDeps -Requests 50 -Threads 8
```

只要中间件不想经脚本拉（例如已经在 compose 里起着），用 `pwsh ./scripts/deps.ps1 -Up`；
`-Status` 看状态、`-Smoke` 打一遍造数端点、`-Down` 停。跨平台等价写法是
`docker compose --profile deps up -d`（`agent/demo-app/docker-compose.yaml`）。

> **本机跑应用时连中间件要用映射后的宿主端口**（容器内仍是标准端口）：
> `DEPS_REDIS_PORT=16379`、`DEPS_MYSQL_URL=jdbc:mysql://localhost:13306/demo?...`、
> `DEPS_KAFKA_BOOTSTRAP=localhost:19092`。之所以不用 6379/3306/9092，是因为宿主上这些端口
> 常被别的栈占着（本机的 RuoYi 就自带 redis/mysql）。`deps.ps1 -Status` 会把这几条打出来。

| 造数端点 | 层 | 说明 |
| --- | --- | --- |
| **`/api/deps-demo/all`** | **四层一次** | **演示与截图的单一入口**：顺序打 Cache/Database/MQ/外呼，逐层回成败与耗时。`?sleepMs=` 传给 MySQL 造慢边、`?site=` 选外呼站点。中间件未起约 8s、都在时约 1s（**每层有超时，是有界不是挂住**） |
| `/api/deps-demo/redis?op=get\|set\|del` | Cache | 三种 op 在明细档落成三个节点（节点身份 = 组件 + 出口操作名） |
| `/api/deps-demo/mysql?sleepMs=0` | Database | 只 `SELECT 1` / `SELECT SLEEP(?)`，**不建表**；`sleepMs` 上限 3000，用来看分位与四档着色 |
| `/api/deps-demo/kafka?op=produce\|consume` | MQ | KRaft 单节点；topic 由端点内 AdminClient 首次调用时建 |
| `/api/deps-demo/http?site=httpbin\|baidu\|google` | 外呼 | **白名单枚举**（不接受任意 URL）；三个站点同属 Http 组件 → 仍是**一个**节点 |

> **与 `/fullSample` 的分工**：`/fullSample` 是**全貌入口** —— 一条请求把**所有被监控的组件类型各打一次**
> （tomcat Entry + `@Trace` + MyBatis + JDBC + HttpClient 自调用 + Redis / MySQL / Kafka / 真实外呼），
> 用来看"全貌"；`?deps=false` 可关掉最后四层。`/api/deps-demo/*` 是依赖面的**专用**入口
> （能分别造慢边、失败、分档），压测走 `stress.ps1 -WithDeps`。两者刻意分开：全貌入口求覆盖度，专用入口求可控。

读图口径（页内 caveats 同款）：

| 口径 | 含义 |
| --- | --- |
| **连接没建起来的依赖不进图** | 出口 span 建立在"连接已建立之后的调用"上，`connection refused` / 主机名解析失败发生在它**之前** → **没有依赖边**。所以 Redis/MySQL 连不上时图上该组件**缺席**，缺席**不能**读成"没有这个调用"（确认请看链路视图） |
| **有边 ≠ 一定是红的** | Kafka `send` 超时那种"调用发生了但客户端只是超时"的情况，边会存在而 `errorCount` 仍可能是 0（出口 span 不由该异常置 `isError`）。**红边只表示 span 被判错** |
| **判成功 ≠ 调用真的成功** | 反过来也不成立：Kafka 在 broker 不可达时 `send()` 等满 `max.block.ms` 抛超时，而 agent 的 producer span 靠**异步 callback** 回填错误、同步超时没有 callback → 该边 `errorCount = 0`，**只有耗时涨到超时上限**（如 3.02s）是线索。判断成功与否以端点响应体 / 业务日志为准 |
| **节点只到组件类型** | 不按实例地址、不按外呼站点拆分（3 个站点 = 1 个 Http 节点） |
| **只做段内配对** | 同一链路段内的 Entry × Exit；`@Async` 出口不进图（跨段需父段索引，父段可能已淘汰） |
| **每条出网调用都显式超时** | Redis 1s / MySQL 2s+3s / Kafka 3s+5s+3s / Hutool 3s。造数端点会被 16 线程压测打、也被页面 5s 轮询读 |
| **中间件客户端版本要对齐 agent 插件** | 版本落在 support 范围外会**静默不生效**（不报错、图上就是没那个节点）。见 `NOTES-docker-stress.md` 第 16 条 |

依赖边是**纯内存、分钟级窗口、重启即失**；单边样本 <20 时 `p90/p95/p99` 显示 `-1` 属正常口径。

## 术语指引

| 术语 | 含义 |
| --- | --- |
| 本地内存报告 | 把原发往 OAP 的数据改存到应用进程内有界缓存,并提供运行时查询与开关 |
| 数据流 | 插件接管并缓存的六类 agent→OAP 数据(链路段/JVM/meter/日志/实例/profile) |
| 统计快照 | 宿主工具类获取的插件状态全貌,按数据流分类,是仪表盘与断言的数据来源 |
| 宿主工具类 | 应用 classpath 中的同名桩类,由插件按类名增强,暴露开关与快照查询 |
| Trace 告警 | 基于已合并链路的慢/错识别规则,命中后经 HTTP webhook 通知外部;默认关闭 |
| 运行时开关 | 不重启应用即启用/禁用数据流写入本地缓存,用于对比验证 |
| 演示应用 / 验证回路 | 面向读者的可视化样例 / 面向作者的快速反馈通道(一条命令全流程) |
| 依赖边 | 一次链路段内「入口端点 × 外部依赖」的调用关系;纯内存、分钟级窗口、重启即失 |
| 依赖拓扑 | 以自身服务为中心、向外画出「调了哪些外部依赖」的视图;节点只到**组件类型** |
| 总览档 / 明细档 | 依赖拓扑页的两档:总览档左列是**本服务单点**、右列按组件类型(节点少而稳定,可截图对比);明细档左列是入口端点、右列按组件 + 出口操作名 |

## 结构

```
agent/demo-app/
├── pom.xml                     # 独立 pom,不进 agent reactor
├── src/main/
│   ├── java/org/openskywalking/demo/   # Spring Boot 应用、控制器、配置(含初学者演示 HelloController/FullSampleController)
│   ├── java/org/apache/skywalking/apm/toolkit/SWLogfileReporterUtils.java  # 宿主工具类桩(勿改名)
│   └── resources/
│       ├── static/index.html          # 能力导览首页(/)
│       ├── static/9527.html           # 初学者演示导航(HelloController 等页面)
│       ├── static/dashboards/         # 六数据流仪表盘(与验证脚本同源 JSON 契约)
│       ├── static/SW*.html            # 各演示大屏页(迁移自旧 demo 工程)
│       ├── schema/schema.sql + data.sql  # H2 内存库建表与演示数据
│       ├── agent.trace-alert.config.sample
│       └── application.yml
└── scripts/
    ├── setup.ps1              # 环境自足:检测/下载 agent、装插件、产出启动命令
    ├── validate.ps1           # 验证回路(构建→安装→启动→造数→断言→报告)
    ├── validate-h2.ps1        # H2 影子存储 + Trace 指标专项验证
    ├── deps.ps1               # 依赖拓扑演示的中间件编排(-Up/-Down/-Status/-Smoke/-All)
    ├── stress.ps1             # 压测辅助(起应用/压测/指标摘要/停应用;委托 HttpLoadTest)
    ├── run-with-agent.ps1     # IDE/手动模式(同参,保持运行)
    └── start-demo.ps1         # 纯应用启动(无 agent)
```

## 指标分位口径

三种口径并列,勿混用:

- **加权平均分位** —— `dashboards/metrics.html`;小时 rollup 与聚合表格对每桶分位按 `request_count` 加权平均(近似,v1 已知偏差)。
- **最差分钟分位** —— `dashboards/metrics-troubleshoot.html`;范围内各桶同一分位取**最大值**,用于排障("最坏的那一分钟有多坏")。不掩盖尖峰,但受断流/小样本桶放大,须与桶数、中位分钟并看。
- **池化真值** —— 合并全部样本后重算分位;当前表结构不可得(需直方图/schema,见 spec out-of-scope)。

## 相关工单

见 `.scratch/demo-app/issues/`:`01` 骨架、`02` agent 装配、`03` 统计读口、`04` 告警表面、`05` 验证回路、`06` 仪表盘、`07` 导览首页与 README、`08` setup 脚本、`09` 旧项目清理(待办)。
