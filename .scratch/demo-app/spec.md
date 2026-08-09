# demo-app: 插件验证演示应用

Status: ready-for-agent

## Problem Statement

插件的效果验证目前依赖仓库外部的 Spring Boot 项目(sb-skywalking),该项目同时承载 SkyWalking 源码层面的学习验证与插件效果验证,两类关注点相互纠缠(大泥球)。由此产生三个问题:

1. **他人无法快速建立认知**:插件能力没有与本仓库绑定的可视化演示,新读者需要在两个仓库之间来回跳转,且插件名"logfile-reporter-plugin"具有误导性——它并不读取日志文件,而是把监控数据流改写为进程内本地缓存(本地内存报告)。
2. **验证回路慢且脆弱**:每次插件变更需手动完成"构建 → 拷贝 jar → 重启应用 → 打流量 → 观察",依赖 IDE run config 与人工核对,反复风险高、错误修复成本大。
3. **资产双份漂移**:同一份验证资产存在于两个仓库,谁是真源无人能答。

## Solution

在 `skywalking-javaPluginExtensions` 仓库内新增 `agent/demo-app`——一个面向三个活跃插件(当前内容聚焦 logfile-reporter-plugin)的演示应用与验证台,单目录三块结构:

- **演示应用**:Spring Boot 应用,移植现有插件验证资产(统计接口、告警验证接口、webhook 接收、静态仪表盘),供他人快速理解插件能力;
- **验证回路**:一条命令完成"构建 → 安装 → 启动 → 造数 → 断言 → 报告",使插件改动尽快被验证;
- **能力导览**:README 与导览首页,讲清"插件是什么、logfile 命名来历、怎么跑"。

迁移完成后,对应资产从 sb-skywalking 中删除,避免双份漂移。

## User Stories

1. 作为插件作者,我想执行一条命令完成"构建插件 jar → 安装进 agent → 启动 demo → 造数 → 断言 → 出报告",以便插件改动在几分钟内得到端到端验证。
2. 作为插件作者,我想在验证回路中看到 trace 缓存合并的断言(traceId 关联、条目数、字段齐全),以便确认链路段数据流正确落缓存。
3. 作为插件作者,我想在验证回路中看到告警链路的断言(slow/error/ignore-rule 命中、webhook 收讫计数),以便确认 Trace 告警端到端可用。
4. 作为插件作者,我想在验证回路中看到运行时开关的断言(enable/disable 前后统计变化),以便确认运行时开关生效。
5. 作为插件作者,我想断言失败时脚本以非零退出码结束并给出明确失败报告,以便在自动化或 CI 中捕获回归。
6. 作为插件作者,我想在脚本模式下由脚本全权负责应用生命周期(含 `-javaagent` 与全部 `-Dskywalking.*` 参数),以便回路与 IDE 解耦。
7. 作为插件作者,我想保留 IDE 手动启动模式(同样的参数,手工点运行),以便调试仪表盘或观察实时数据。
8. 作为新读者,我想打开一个"能力导览"首页,看到插件是什么、六类数据流(链路段/JVM 指标/meter/应用日志/心跳/快照)的能力地图,以便在 5 分钟内建立对插件的整体认知。
9. 作为新读者,我想在导览页读到"logfile"命名的来历(最初设想写入日志文件,受 Druid 内存缓存启发改为内存存储),以便不被插件名误导。
10. 作为新读者,我想看到无 OAP 模式下全链路数据仍可自查的演示,以便理解本地内存报告与 OAP 模式的差别。
11. 作为新读者,我想打开统计仪表盘看到 trace 缓存表与时间线、JVM 图表、meter 图表、实例属性、告警指标与 profile 快照,以便直观确认各数据流被缓存。
12. 作为新读者,我想通过一条 setup 脚本完成环境准备(检测/下载 agent 9.4.0 发行包、构建并安装插件 jar、生成启动命令),以便不依赖作者的机器也能跑起来。
13. 作为插件使用方,我想在 demo 内通过告警验证端点触发慢/错/忽略规则,并看到 webhook 事件被记录,以便评估告警能力是否满足需求。
14. 作为插件使用方,我想通过运行时开关观察统计接口数据的有无变化,以便评估开关的实用性。
15. 作为未来插件作者,我想 demo 的目录结构按插件分区、多插件就绪,以便后续新增插件验证时只是往格子里填内容。
16. 作为维护者,我想 demo-app 拥有独立 pom 且不进 agent reactor,以便插件构建命令与 CI 路径不被连带影响。
17. 作为维护者,我想演示应用与验证脚本读取同一批 JSON 契约(仪表盘与断言同源),以便两者不会漂移。
18. 作为维护者,我想端口在全仓库统一为 9600(应用端口、webhook 默认端口一致),以便消除"85/9600"不一致的隐患。
19. 作为维护者,我想迁移资产从 sb-skywalking 删除后只保留源码学习部分,以便消除跨仓库双份漂移。
20. 作为维护者,我想保留插件模块现有单测(alert/* 等)不动,以便单元层接缝与端到端接缝各司其职。

## Implementation Decisions

- **新模块 `agent/demo-app`**:Spring Boot 2.5.4、Java 8,独立 pom,不挂 `agent/pom.xml` 的 parent,不加入 agent reactor;以自身 maven 命令构建。理由(已记入 AGENTS.md):demo 是插件的消费者,其构建生命周期不应与插件 reactor 耦合。
- **单目录三块结构**:演示应用、`scripts/` 验证回路、README 能力导览,同一版本号、无第二套交付物。
- **宿主工具类契约**:demo 内保留与应用侧同 FQCN 的桩类(`org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils`),插件按类名增强;不得改名、不得换成带后缀的类。
- **迁移的验证表面**:统计接口(trace/JVM/meter/实例属性/日志)、运行时开关接口、告警验证端点(slow/error/ignore-rule 触发)、webhook 接收器与进程内事件存储(recent/clear)、组件名解析(随带组件库映射)、静态仪表盘(统计/JVM/实例/meter/告警/profile + 导航)、agent 告警配置样例。
- **不迁移**三个 Java 手动测试助手(HttpLoadTest / StatisticRequester / SWStatisticMeterManualTests),流量驱动由验证脚本承担。
- **验证回路脚本**:拥有完整应用生命周期——构建插件、拷贝 jar 至 agent plugins 目录、以 `-javaagent` + `-Dskywalking.*` 启动、等待就绪、打流量、断言、输出报告,失败以非零退出码结束;另提供 IDE 手动模式参数供调试。
- **断言范围(核心三件)**:trace 缓存合并断言、告警链路断言(含 webhook 收讫)、运行时开关断言。JVM/meter/profile 数值不做时序敏感断言,由仪表盘人工观察。
- **端口规范**:全仓库统一 9600;webhook 默认地址指向 demo 自身;遵循 `WebPort` 环境变量约定。
- **setup 脚本**:检测本机 `skywalking-java-agent` 9.4.0 发行包,缺失则从官方发行包下载;构建插件 jar 并安装进 `plugins/`;生成启动命令。agent 版本与插件版本强一致(9.4.0)。
- **能力导览首页**:文案三件事——插件是什么(本地内存报告 vs OAP 模式、六类数据流地图)、logfile 命名来历、怎么跑(一条命令)。
- **多插件就绪结构**:验证内容按插件分区,初始只含 logfile-reporter-plugin 内容;扩展 override 插件验证记录为 TODO(已记入 AGENTS.md)。
- **旧项目清理**:迁移资产从 sb-skywalking 删除,仅保留 SkyWalking 源码学习内容。

## Testing Decisions

- 好测试的定义:只测外部可见行为,不测实现细节——通过 HTTP 契约断言,不触碰插件内部类。
- 接缝(唯一):验证回路脚本对 demo-app HTTP 契约的黑盒断言层,覆盖 `GET /statistic`、`GET /statisticTraceAlert`、`POST /toggle`、`POST /inner/sw/trace-alert`(收讫)与 `/inner/sw/trace-alert/recent`(事件)等端点。
- 验证回路脚本自身即演示应用的验收测试:断言失败 → 非零退出码 + 明确失败报告。
- 既有单测接缝保持不变:插件模块 `alert/*` 等 JUnit 测试照旧运行,不与本接缝重复。
- 先例:README-trace-alert.md 中手写的 curl 验证配方将被脚本化并断言化;插件模块已有 `alert/*` 纯单测先例。

## Out of Scope

- agent-boot 集成测试(apm-test-tools):列为后续可选项,不触碰插件模块现有 surefire 配置。
- override-httpclient / override-hutool 验证资产的迁移:记为 TODO,结构已预留。
- SkyWalking 源码层面的学习验证内容:留在 sb-skywalking,不迁入。
- agent 发行包本身:外部下载,锁定 9.4.0。
- CI 工作流修复(引用不存在的 mvnw 等历史问题)。
- ADR 编写:按仓库约定,实现落定后补写(计划记录 demo-app 独立于 agent reactor 的决策)。
- Docker 容器化方案。

## Further Notes

- 插件为 OAP-less 设计(gRPC 通道被禁用),可视化只能自建"JSON 接口 + 页面",无法接入真实 SkyWalking UI——这是设计约束,不是可选项。
- 术语已固化于仓库根 `CONTEXT.md`:本地内存报告、数据流、统计快照、宿主工具类、Trace 告警、运行时开关、演示应用、验证回路。
- "logfile" 命名来历是能力导览首页的必备叙事素材。
- 旧项目端口不一致(85 vs 9600)已裁决为统一 9600。
- 本 spec 遵循 `.scratch/` 约定;实现阶段请对照 AGENTS.md 的模块维护状态与 Known TODOs。
