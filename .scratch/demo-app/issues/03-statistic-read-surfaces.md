# 03 — 统计快照读口移植

**What to build:** 移植统计快照读口家族:五类数据流(链路段/JVM 指标/meter 指标/应用日志/心跳实例信息)的查询端点、profile 快照接口、组件名解析,以及运行时开关端点——实跑造数后能查到有数据、开关能改变数据流。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本

**Status:** done

- [x] 五类数据流读口在实跑(agent + 插件)下返回有数据
- [x] 组件名解析生效(componentId → 名称)
- [x] profile 快照读口可用
- [x] 运行时开关生效:关闭后统计快照停止增长,开启后恢复
- [x] 无插件时读口返回明确提示而非异常

## Comments

- 2026-08-09 已实现并验证(移植自 sb-skywalking 的 SWLogfileReporterController / SWProfileController):
  - 读口:链路段 `GET /statistic`(span 按 endTime 倒序、startTimeReadable/endTimeReadable、componentName)、`GET /statisticJVM`、`GET /statisticMeter`、`GET /statisticLogs`(新端点,旧项目无独立日志读口)、`GET /statisticInstanceProperties`、`POST /toggle?enable=true|false`;profile 家族 `POST /profile` + `GET /profileData` + `GET /profileData2` + `GET /longTimeTask`(工作负载)。
  - 无插件提示契约:读口返回 map 含 `plugin:"absent"` + `hint` 文案(统一在 `StatisticStatus` 助手类),200 而非异常;无 agent 启动本身也正常(toolkit appender / meter registry 均空操作降级)。
  - 实跑验证(agent 9.4.0 + 插件):五流均有数据(链路段 traces>0 且 componentName/可读时间出现;JVM 120+ 采样点;meter 6+ 键;日志 15+ 条;实例属性 13 键);profile 采样后 profileData2 含带方法栈的快照;toggle 关闭后仅自身请求噪声入库(+1),开启后恢复增长(+4=3 命中+噪声)。
  - 关键发现:插件拦截器运行时引用 hutool(core 经 PluginClassLoader 回落应用类路径解析),demo-app 已按插件 hutool.version=5.4.1 补 `cn.hutool:hutool-json`(与旧项目做法一致);插件侧剥离 hutool 依赖已列于 `agent/TODO.txt`。
  - meter 流造数:移植 micrometer 桥接(SkywalkingMeterRegistry 9.4.0 toolkit + JVM binder,micrometer-core 用 spring-boot 管理版本 1.7.2,排除 toolkit 自带 1.5.0);应用日志流经 logback toolkit appender(GRPCLogClientAppender)进入 agent LogReportServiceClient。
  - 注意:run-with-agent.ps1 默认开 alert(-Dskywalking.plugin.logfilereporter.alert.enabled=true),webhook 端点(04 工单)未就绪前 agent 日志会出现 404 告警噪音,属预期。
