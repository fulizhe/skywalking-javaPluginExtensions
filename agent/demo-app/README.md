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
> 看 [`README-verify-scenarios.md`](README-verify-scenarios.md)。
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

> `-MaxRps` / `-NormalOnly` / `-WithDeps` / `-AllEndpoints` **四者互斥**:分别压
> "只 ms 级只 2xx 冲吞吐上限 / 业务正常端点 / 依赖造数端点 / 全部页面接口"。
> 同时给会直接报错退出(1),避免"以为压了 A 其实是 B"这种静默偏差。
>
> ⚠️ **`-AllEndpoints` 会触发告警自环**:它含返回 5xx 的端点,错误请求经插件 webhook
> **同步回打本机**,而回打本身又是一次请求 → 负载自我放大,并把
> `persistErrors`/`aggregateErrors` 计数推高。要干净的指标数字就用 `-NormalOnly`。
> `stress.ps1` 开跑前会把这个提示与命中的错误端点列出来。

> ⚠️⚠️ **含 5xx 这类状态码的端点，压之前必须先 `netsh int tcp reset`**(需管理员)。
> **Tomcat 在 `prepareResponse()` 里按状态码决定是否关连接** ——
> `Http11Processor.statusDropsConnection()`，8 个码
> `{400, 408, 411, 413, 414, 500, 501, 503}`(与 Apache httpd 同一份清单)。
> **这是协议层行为，与错误派发无关** —— 不抛异常、正常 return 也照关。
> 于是**每个这类响应泄漏一条 TCP 连接**;而 TIME_WAIT 在 Windows 上挂 120 秒、本机动态端口池
> 只有 13,977 个 → 临界速率 `13977/120 ≈ 116` 条/秒,**超过就打满**。
> 打满后**本机任何**新建连接都失败(`errno 10022`)，表现为**仪表盘页面白屏**
> (js/css 加载不出来)—— **那不是插件坏了，是压测自己把端口池打爆了**。
> ⚠️ 404 **不在**清单里(语义上"资源不存在"说明请求本身是好的，连接可复用)，
> 所以返回 404 的端点(如 `/api/exists/1`)**不会**泄漏。
>
> 实测(同机 16 线程 180 秒，只换路径集):
>
> | 路径集 | TIME_WAIT 峰值 | 结果 |
> | --- | --- | --- |
> | `-MaxRps` / `-NormalOnly`(无 5xx) | **465** | 自然回落，面板错误率 0.00% |
> | 全 5xx | **17,396** | **28 秒打满**，后续请求大量失败 |
>
> 默认档 5 条路径里 **2 条**、`-AllEndpoints` 60 条里 **6 条**会返回 500(全部 500)，
> 所以这两档开跑前都要清空。`stress.ps1` 现在会读 TIME_WAIT 水位，**≥3000 时直接提示先 reset**。
> 源码定位与可复现命令见
> [`docs/notes/2026-10-02-tcp-port-pool-exhaustion-and-a-measurement-trap.md`](../../docs/notes/2026-10-02-tcp-port-pool-exhaustion-and-a-measurement-trap.md) §2.2。

常用参数:`-BaseUrl`(默认 `http://127.0.0.1:9600`)、`-Requests`、`-Threads`、`-DurationSec`、`-Continuous`、`-ProgressSec`(持续模式打印间隔,默认 10s)、`-Paths`(逗号分隔,默认混合正常/错误/慢)、`-MaxRps`(只 ms 级只 2xx,冲吞吐上限)、`-NormalOnly`、`-WithDeps`(与 `run-with-agent.ps1 -WithDeps` 同名同义)、`-AllEndpoints`、`-KeepRunning`、`-SkipMetrics`。

### 慢端点演示(手动执行,`stress-slow.ps1`)

```powershell
pwsh ./scripts/stress-slow.ps1                          # 默认 25 次 / 4 线程
pwsh ./scripts/stress-slow.ps1 -Requests 10 -Threads 1  # 串行慢放,边讲边看
pwsh ./scripts/stress-slow.ps1 -DurationSec 300         # 打 5 分钟,让分位/趋势积累出形状
pwsh ./scripts/stress-slow.ps1 -Continuous              # 无限模式,Ctrl+C 停(默认 10s 一次进度)
pwsh ./scripts/stress-slow.ps1 -Continuous -ProgressSec 30
pwsh ./scripts/stress-slow.ps1 -All                    # 加告警演示端点 → 会触发告警自环
```

与上面三种**稳定性压测是两种目的**,故独立成脚本:慢端点单请求 0.3s~8.5s,混进稳定性压测
会把吞吐拖成没有参考价值的平均数。开跑前它会打印端点清单与单请求量级,便于讲解对照:

| 类别 | 端点与量级 |
| --- | --- |
| 依赖侧(秒级) | `mysql?sleepMs=1000`(依赖侧造慢边)、`kafka?op=produce`(broker 不可达约 3s)、`http?site=httpbin`(1~2s)、`grpc`(0.3s,首次建链约 4s) |
| 业务侧(秒级) | `trace-alert-demo/slow?ms=4000`(触发 SLOW)、`/api/order/1`(8.5s,命中 Ant 规则)、`/api/export/report`(**每请求随机 1~4s**,见下)、`/longTimeTask`(1s)、`/fullSample`(约 2~5s) |
| 毫秒级对照 | `queryDbByMybatis`、hutool 出口自调 —— 同一进程内对比"不同依赖的差距" |
| 告警演示(默认不含) | `trace-alert-demo/error`、`/http500` → 报错 → webhook 自环,故要显式 `-All` |

**`-Continuous` 无限模式看的是"数据在积累",不是吞吐**:这一档 req/s 天花板极低
(单请求本身就要等几秒),看着像"卡住"是正常的 —— 它在等慢端点。想看吞吐/堆/插件计数
是否健康,用 `stress.ps1 -Continuous`。

**`/api/export/report` 为什么用 `{rand:1000,4000}`**:该端点默认 `sleep(65000)`
(Ant 规则 `/api/export/**` 拿它演示极端慢)。压测里若照搬 65s,单个请求就吃满整个循环、
必然超时;写死一个值又只能压出一条固定耗时的线。所以路径里放占位符 `{rand:min,max}`,
由 `HttpLoadTest` **每个请求各替换一次** → 一条路径压出连续的耗时分布。
(占位符不替换的话 Spring 绑 int 会失败返回 400,压测断言会挂 —— 也就是"忘了替换"不可能悄悄通过。)
演示要"65s 那一版"就直接开 `/api/export/report`(不带参数)。

> **`-TimeoutMs` 默认给到 30000**(而不是 `stress.ps1` 的 10000):本档最慢端点 8.5s,
> 并发下尾延迟会超过 10s 而攒出"网络异常"并让断言非零退出 —— 慢端点压测里超时是预期现象,不是缺陷。

> **客户端超时不等于服务端没记**:客户端在 30s 放弃后,服务端仍会睡完并把**完整耗时**记进指标
> (排查时看到 65s 的孤点,先想想是不是有脚本裸调了该端点)。


> 稳定性观测:`-Continuous` 下每轮 `[progress]` 行含 `plugin=` 段,即插件 `/inner/sw/metrics` 的计数
> (`rowsUpserted`/`lateDropped`/`sampleOverflow`/`endpointOverflow`/`persistErrors`/`aggregateErrors`)——
> 持续观察这些计数是否异常增长、以及 `heap=` 是否持续攀升，即可判断插件在长跑下的稳定性。

压测后浏览器打开 `http://127.0.0.1:9600/dashboards/metrics.html`。

## 依赖拓扑演示（依赖面三层 + 真实外呼）

`/dashboards/topology.html` 画的是「我调了哪些外部依赖、哪条路慢」。**视图**有三档：
总览（左侧只有一个**本服务**节点，窗口内全部入口端点的聚合：合计调用 / 合计错误 / 覆盖端点数）、
明细（左列入口端点、右列按组件 + 出口操作名）、
**清单**（一行一个外部依赖的**进程累计**清单，见下）。端点维度在明细档与页面边列表里。

起齐三层中间件（**默认 profile 不起**，避免拖慢只想看效果的人）——Windows 上两条命令闭环：

```powershell
# 1) 起应用，-WithDeps 先拉起 redis / mysql / kafka（Docker 没起也不报错，只是三层没有边）
pwsh ./scripts/run-with-agent.ps1 -WithDeps

# 2) 压测造数，-WithDeps 让压测路径切到依赖造数端点，边与分位持续累积
pwsh ./scripts/stress.ps1 -WithDeps -Requests 50 -Threads 8
```

中间件用 `run-with-agent.ps1 -WithDeps` 顺带拉起（内部就是 `docker compose --profile deps up -d`，见 `agent/demo-app/docker-compose.yaml`）。

> **本机跑应用时连中间件不用设任何环境变量**：宿主映射端口（redis `16379` / mysql `13306` / kafka `19092`）
> 已经写进 `application.yml` 的默认值。之所以不用 6379/3306/9092，是因为宿主上这些端口
> 常被别的栈占着（本机的 RuoYi 就自带 redis/mysql）。

| 造数端点 | 层 | 说明 |
| --- | --- | --- |
| **`/api/deps-demo/all`** | **四层一次** | **演示与截图的单一入口**：顺序打 Cache/Database/MQ/外呼，逐层回成败与耗时。`?sleepMs=` 传给 MySQL 造慢边、`?site=` 选外呼站点。中间件未起约 8s、都在时约 1s（**每层有超时，是有界不是挂住**） |
| `/api/deps-demo/redis?op=get\|set\|del` | Cache | 三种 op 在明细档落成三个节点（节点身份 = 组件 + 出口操作名） |
| `/api/deps-demo/mysql?sleepMs=0` | Database | 只 `SELECT 1` / `SELECT SLEEP(?)`，**不建表**；`sleepMs` 上限 3000，用来看分位与四档着色 |
| `/api/deps-demo/kafka?op=produce\|consume` | MQ | KRaft 单节点；topic 由端点内 AdminClient 首次调用时建 |
| `/api/deps-demo/grpc` | RPC | 进程内起一个最小 gRPC server 并自调用（**不引外部中间件**），组件名 `GRPC`、层 `RPCFramework`，客户端 2s deadline |
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
| **清单档不看时间窗** | 该档恒为**进程累计**，且关系图收起、表格占满宽度 —— 不与窗口边的图并排，免得累计数字被当成窗口读 |
| **只做段内配对** | 同一链路段内的 Entry × Exit；`@Async` 出口不进图（跨段需父段索引，父段可能已淘汰） |
| **每条出网调用都显式超时** | Redis 1s / MySQL 2s+3s / Kafka 3s+5s+3s / Hutool 3s。造数端点会被 16 线程压测打、也被页面 5s 轮询读 |
| **中间件客户端版本要对齐 agent 插件** | 版本落在 support 范围外会**静默不生效**（不报错、图上就是没那个节点）。见 `NOTES-docker-stress.md` 第 16 条 |

依赖边是**纯内存、分钟级窗口、重启即失**；单边样本 <20 时 `p90/p95/p99` 显示 `-1` 属正常口径。

### 依赖清单（「谁在调我」视角）

拓扑页第三个视图档。**总览/明细回答"此刻谁在调谁"（窗口内的边），清单回答"这个进程伸出去过哪些手"** ——
后者是给资源申请、拆单体决策当论据用的（"对外依赖 5 类，其中 Cache/MQ 是新引入的"），
所以它**恒为进程累计口径、不跟时间窗走**。

数据源就是 `/inner/sw/topology` 无条件附带的 `dependencies`（插件侧 `EdgeLifetimeRow`），
**没有新增读口、没有新增存储、没有碰插件**：

| 列 | 字段 | 说明 |
| --- | --- | --- |
| 依赖 | `componentName` | 组件名；插件只透出 `componentId`，名字由宿主侧按组件库 → `spanLayer` → `component-<id>` 三级 fallback 翻译 |
| 层 | `spanLayer` | Cache / Database / MQ / RPC / Http … |
| 累计调用 · 累计错误 · 错误率 | `requestCount` / `errorCount` / `errorRate` | 错误率同时驱动行首的 SLA 色点 |
| 首次出现 · 最近出现 | `firstSeenBucket` / `lastSeenBucket` | 分钟桶。首次出现额外标**距进程启动多久**（`起点` / `+5m` / `+16h40m`），用来区分「一直都有」与「压测才开始碰」 |
| 出口操作名 | `operations` | 去重后的操作名，显示前 3 个、其余 `+N`；完整列表在单元格 tooltip。插件侧每组件上限 8 个，超出标「已截断」 |

交互：**点列头排序**（组件/层/调用/错误/错误率/首见/最近/操作数，默认按累计调用降序）、**点行回到总览档**看图。
该档下关系图收起、表格占满宽度 —— 清单是汇报视角，不是第三种画法。

> **行数 = 读口 `dependencies` 长度**，不分页、不过滤：这一页要能当"清单"用，缺行比多列严重得多。
> 组件数硬上限 512（插件侧 `MAX_LIFETIME_COMPONENTS`），超出计入 `counters.lifetimeDropped`，
> 一旦非 0 页面会在表头提示，不静默隐藏。

## 慢调用 TopN 榜（独立页 `slow-topn.html`）

指标大屏那张 Endpoint 表按**请求数**排序 —— 排名是隐含的，看不出名次，也看不出名次怎么变。
要按"慢"排序看名次，去**独立一页** <http://127.0.0.1:9600/dashboards/slow-topn.html>
（指标大屏的 Endpoint 指标卡右上角有「慢调用 TopN 榜 ›」跳转）。

它回答的是 *"哪个端点慢、慢到什么量级"*，数据源 `/inner/sw/metrics/query?aggregate=true`
（真聚合、落 H2、**跨重启保留**），一页只发这一个请求。

| 列 | 口径 |
| --- | --- |
| 名次（含 ▲▼ 变化） | 按所选依据降序；同分按请求数兜底，避免名次在两次刷新间随机互换 |
| 请求 / 错误率 / 慢率 | 窗口内全量调用的计数与比率（插件 `slowCount` 口径） |
| P50 / P95 | 窗口内**加权平均**分位（按请求数加权） |
| **最差 P95** | 窗口内**最差那个桶**的 P95。加这一列是因为平均值会把尖峰抹平，而汇报要问的正是"最坏的时候有多坏" |
| 桶数 | 该端点在窗口内落到几个桶 —— 判断样本够不够（长窗口下早期桶可能被清理） |
| 逐条慢调用 | 按**该端点自己的 P95** 作阈值跳 `trace-slow.html?endpoint=…`，那里是逐条慢段、traceId 可点进链路图 |

**排序依据**可切 7 种（P95 / 最差 P95 / P99 / 平均耗时 / 慢率 / 错误率 / 请求数），**前 N** 可切 10 / 20 / 50。

### 名次变化怎么"看得出变化"

`▲n` / `▼n` / `—` / `新` 是**和上一次换档时的名次比**，hint 里写明比的是哪一档
（如 `近 1h · P95 降序 · 前 10 / 共 42 个 · 名次变化 vs 近 24h`），页内有图例。

> 基准**只在换档（换时间窗或换排序依据）时推进**：同一档内每 10s 轮询一次，
> 拿上一次渲染当基准的话箭头会一直抖 —— 那是噪声不是趋势。

### 榜不是"慢调用次数榜"（重要口径）

榜排的是**窗口内全量调用**的分位，**不是**慢调用的次数。后者只能从 `/inner/sw/trace-slow` 拿，
而那个读口的 SQL 是 `WHERE endpoint = ?`（**单 endpoint 精确匹配**）、按耗时降序、受 `limit` 截断 ——
拿它排名得到的是"最近 N 条慢段样本里的排名"，不是"这个端点有多慢"。所以：

- **榜**给量级与名次（真聚合、落 H2、跨重启保留）；
- **慢查询页**给逐条证据（内存热层，`payloadExpired` 可能过期）；
- 两边数字**不要相加**，页内也不并排混读。

### 时间窗口径是共享的

两页查同一批分钟桶的公式在 `dashboards/range.js`（`SWRanges.buckets()`），指标大屏与本页**同源**。
抄两份迟早漂，而漂了没人查得出来 —— 只会变成"两张页同一时间窗数字不一样"这种查不清的问题。

### 回归测试

```powershell
node verify/frontend/slow-topn-render.test.js
```

把页面里的排序/名次/渲染逻辑**原样切出来**跑（不手抄一份），38 条断言。
它存在的原因：`node --check` 只查语法，查不出**运行时** `ReferenceError` —— 首屏就曾因
行模板里用了已删除的 `rank` 变量而抛 `rank is not defined`，且它在渲染主表**之前**，
一抛错整条链断掉、KPI 与所有表格一起空白。纯逻辑断言也不会碰模板字符串里的变量引用，两者都漏。

## 刷新节奏（统一控件）

指标大屏、排障页、依赖拓扑、`dashboard.html` 七个子页原本各自 `setInterval`（3s~6s 不等），
频率写死在代码里；而开着标签页不管时后台仍在按秒级读口 —— 监控反过来打扰业务系统。
现统一由 `static/dashboards/poll.js` 提供一个**左下角控件**（自带样式，引入一个脚本即可）：

| 能力 | 说明 |
| --- | --- |
| **间隔可选** | 3s / 5s / 10s / 30s / 60s。给档位而不是输入框 —— 手输能把 `5000` 敲成 `500`，轮询直接 10 倍 |
| **暂停 / 继续** | 一键停。**页面顶栏原有的「停止刷新 / 停止轮询 / 刷新」按钮已删** —— 两处控制只会互相打架，能力全部收在这里 |
| **立即刷新** | 手动取一次数据（顶栏那个「刷新」按钮被删后，能力搬到这里） |
| **切后台自动暂停** | 标签页不在前台就停，切回自动恢复（不用做任何事，也关不掉 —— 它只会省事） |
| **演示模式自动冻结** | 进入演示态停轮询、退出恢复。讲解时数字不跳、行内展开的详情不抖，截图也稳 |
| **状态持久化** | 间隔与暂停存 localStorage，跨页一致 |

默认间隔仍是各页原值（未改），只是变成可调。

> 三个页面顶栏原有的刷新/暂停按钮都已删除（`topology.html` 的「停止刷新」、`metrics.html` 与 `metrics-troubleshoot.html` 的「停止轮询」、`dashboard.html` 右上角的「刷新」），刷新相关的操作只剩左下角这一个入口。

> 设计上 `poll.js` **不接管页面定时器**：它只持有"策略"（间隔多长、现在该不该刷）与控件 UI，
> 定时器仍归各页（`start`/`stop` 由页面实现，间隔从 `SWPoll.intervalMs()` 取）。这样每页只改三行，
> 且脚本没加载时各页仍按原逻辑工作。

## 演示模式（Presenter Mode）

四页右下角共用一个开关 **「演示模式」**：`metrics.html`（指标大屏）、`slow-topn.html`（慢调用榜）、
`topology.html`（依赖拓扑）、`dashboard.html?p=alert`（告警面板）。
逻辑与样式都在 `presenter.js` / `presenter.css` 两个共用文件里。

告警面板的每条事件都**可点开看详情**，与 `?p=statistic` 同一套做法：点行在原位展开这条事件的
**span 时间线 + span 明细表**（operationName / 层 / 组件 / 耗时 / 错误 / 标签），点「查看」直接开
链路图弹窗。事件自带的 `logs` 与 statistic 的 `logs` 同构，所以复用同一份 `expandRow()`。
展开态按 traceId 记住并跨 3s 自动刷新保留 —— 否则演示模式（轮询按钮已收起）下只能看 3 秒。

打开后：

| 变化 | 说明 |
| --- | --- |
| 运维控件收起来 | 时间窗 / 视图切换 / 停止刷新 / 轮询状态 / 诊断 chips 隐藏，图与字号放大一档 |
| 顶部**结论卡** | 一行数字结论，全部读自 `/inner/sw/*`，**无手写常量**：慢调用榜 = 最慢端点 / 其 P95 / 其最差 P95；拓扑 = 外部依赖类型 / 累计调用 / 累计错误率 / 最慢依赖；告警 = 事件数 / SLOW / ERROR / webhook 投递 |
| 口径说明收成一行 | 默认折叠，悬停展开（汇报时看不见，被追问能立刻答） |
| 四页串起来 | `‹` `›` 翻页，键盘 `←` `→` 翻页、`P` 切模式；顺序 指标 → **慢调用榜** → 拓扑 → 告警（按叙事排：总量水位 → 哪里慢 → 慢在哪条路上 → 有没有出事） |
| **外壳自己藏到右边缘** | 演示态下右下角外壳只留一条窄边（截图干净），鼠标划过去或键盘聚焦即滑回；快捷键 `P`/`←`/`→` 不受影响 |
| **刷新被冻结** | 进入演示态即停轮询（见「刷新节奏」），数字不再跳动；退出演示恢复用户原本的节奏 |

```powershell
# 投屏 / 截图链接直接带参，不依赖本机 localStorage
start http://127.0.0.1:9600/dashboards/topology.html?presenter=1
```

三条设计约束：

- **未开启时零影响**：所有规则挂在 `body.presenter-on` 下，普通浏览的页面与改动前完全一样。
- **结论卡只排版、不算数据**：数字由各页从读口算好后传进 `SWPresenter.conclusion([...])`。
  指标大屏**不另加卡** —— 它顶部 KPI 行本来就是那个结论（QPS / 错误率 / P95），只是被放大。
  卡片用 `textContent` 填，不用 `innerHTML`，所以本文件不必再抄一份转义函数。
- **口径必须写清**：拓扑结论卡用**进程累计**（`dependencies`），不随时间窗跳变，适合放进汇报材料；
  而页内边列表是**窗口内**口径（插件只留 ≈4 分钟的桶），两者数字本就该不同，别混读。

> 演示态**没有**改 `#chart` 的高度：拓扑图节点坐标是按 560px 画布设计的固定跨度
> （`yAt` = `60 + i*420/(n-1)`，`layout: none` 不自动重排），只改 CSS 高度会让节点挤到底边、
> 或 canvas 与容器不同步而溢出到图例上。要"图更大"得改 `yAt` 的跨度，而不是只改高度。
>
> 控件收起后，「视图 / 时间窗」的**按钮**没了但**档位信息**不能跟着消失 —— 顶栏会补一个只在
> 演示态显示的只读标签（`总览 · 自启动以来`），否则观众无从判断那是 5 分钟窗口还是进程累计。

> ⚠️ **改了页面 JS/CSS 后对方浏览器可能还拿着旧版**：静态资源只带 `Last-Modified`、没有
> `Cache-Control`，浏览器会启发式缓存。挂载点 id 在 HTML 里、挂载逻辑在 `presenter.js` 里，
> 两份资源被缓存成不同版本时，结论卡会**静默消失**（前两页的挂载代码在 HTML 内联，不会错位，
> 所以往往只有告警面板中招）。`mount()` 已兜底：找不到挂载点就临时兜一个并在 console 里说清楚，
> 但**硬刷新（Ctrl+F5）**才是正解。


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
| 总览档 / 明细档 / 清单档 | 依赖拓扑页的三档:总览档左列是**本服务单点**、右列按组件类型(节点少而稳定,可截图对比);明细档左列是入口端点、右列按组件 + 出口操作名;**清单档**一行一个外部依赖、恒为进程累计(不看时间窗)、关系图收起 |

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
│       ├── static/dashboards/presenter.{js,css}  # 演示模式共用外壳(指标/慢调用榜/拓扑/告警四页)
│       ├── static/dashboards/poll.js             # 刷新节奏统一控件(间隔/暂停/立即刷新/后台停/演示冻结)
│       ├── static/dashboards/range.js            # 时间窗→分钟桶范围的**口径单点源**(指标大屏与慢调用榜共用)
│       ├── static/SW*.html            # 各演示大屏页(迁移自旧 demo 工程)
│       ├── schema/schema.sql + data.sql  # H2 内存库建表与演示数据
│       ├── agent.trace-alert.config.sample
│       └── application.yml
└── scripts/
    ├── setup.ps1              # 环境自足:检测/下载 agent、装插件、产出启动命令
    ├── validate.ps1           # 验证回路(构建→安装→启动→造数→断言→报告)
    ├── validate-h2.ps1        # H2 影子存储 + Trace 指标专项验证
    ├── stress.ps1             # 压测辅助(起应用/压测/指标摘要/停应用;委托 HttpLoadTest)
    ├── stress-slow.ps1        # 慢端点演示压测(手动执行;默认不含告警端点,避免自环)
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
