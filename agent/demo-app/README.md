# demo-app:插件能力演示与验证台

面向三个活跃插件(当前聚焦 `logfile-reporter-plugin`)的演示应用与验证台,单目录三块结构:

- **演示应用**:Spring Boot 应用,把插件的本地内存报告可视化——能力导览首页、六个数据流仪表盘、告警验证端点、webhook 接收器;
- **验证回路**:`scripts/` 下的一条命令完成 构建插件 → 安装进 agent → 启动 → 造数 → 断言 → 报告;
- **能力导览**:本 README + 导览首页(`/`,即 `src/main/resources/static/index.html`),讲清插件是什么、logfile 命名来历、怎么跑。

> 术语(本地内存报告、数据流、统计快照、Trace 告警、运行时开关等)以仓库根 `CONTEXT.md` 为准。

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

前置要求:JDK 8、Maven 3、本机已安装 SkyWalking Java Agent **9.4.0**(版本与插件锁定一致)。环境自足的 setup 脚本(自动检测/下载 agent、安装插件)见工单 08,尚未落地;当前请先准备 agent。

### 方式 A:一键验证(推荐)

```powershell
pwsh ./scripts/validate.ps1
```

该命令完成:构建插件 jar(`mvn clean package` 于 `agent/logfile-reporter-plugin`)→ 拷贝进 agent `plugins/` 目录 → 以 `-javaagent` + 全部 `-Dskywalking.*` 参数启动 demo → 等待就绪 → 造数 → 断言 → 报告。失败时以非零退出码结束。

常用参数:

| 参数 | 说明 |
| --- | --- |
| `-SkipPluginBuild` | 插件已构建,跳过 maven 直接安装启动 |
| `-SkipAppBuild` | demo 应用 jar 已存在,跳过构建 |
| `-SkipPluginInstall` | 负向模式:故意不安装插件,预期在加载检查处大声失败(exit 3) |
| `-Port <n>` | 应用端口,默认 9600(全仓库统一) |
| `-AgentDir <dir>` | agent 目录,默认 `D:\apps\apache-skywalking-java-agent-9.4.0`(或 `SKYWALKING_AGENT_DIR`) |
| `-JavaHome <dir>` | JDK 目录,默认扫描 `D:\apps\java` 下 JDK8 或取 `JAVA_HOME` |

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

## 结构

```
agent/demo-app/
├── pom.xml                     # 独立 pom,不进 agent reactor
├── src/main/
│   ├── java/org/openskywalking/demo/   # Spring Boot 应用、控制器、配置
│   ├── java/org/apache/skywalking/apm/toolkit/SWLogfileReporterUtils.java  # 宿主工具类桩(勿改名)
│   └── resources/
│       ├── static/index.html          # 能力导览首页(/)
│       ├── static/dashboards/         # 六数据流仪表盘(与验证脚本同源 JSON 契约)
│       ├── agent.trace-alert.config.sample
│       └── application.yml
└── scripts/
    ├── validate.ps1            # 验证回路(构建→安装→启动→造数→断言→报告)
    ├── run-with-agent.ps1      # IDE/手动模式(同参,保持运行)
    └── start-demo.ps1          # 纯应用启动(无 agent)
```

## 相关工单

见 `.scratch/demo-app/issues/`:`01` 骨架、`02` agent 装配、`03` 统计读口、`04` 告警表面、`05` 验证回路、`06` 仪表盘、`07` 本页与导览、`08` setup 脚本(待实现)、`09` 旧项目清理(待办)。
