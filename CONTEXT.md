# SkyWalking Java 插件扩展

本仓库为 SkyWalking Java Agent 的扩展插件集。当前有效模块:`logfile-reporter-plugin`(活跃迭代)与两个 override 插件(`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`,同为活跃维护)。

## Language

**logfile-reporter-plugin**:
一个 SkyWalking Java Agent 的可选报告器插件:将 agent 采集的各类监控数据流改写为进程内本地缓存,使应用在无 OAP 后端的情况下也能自查全链路与指标数据。命名源于最初"写入日志文件"的设想,实现中受 Alibaba Druid 内存缓存启发改为内存存储,名字沿用至今。
_Avoid_: 日志文件读取插件、log 文件报告器

**本地内存报告 (local in-memory reporting)**:
插件的能力本质——把原本发往 OAP 的数据改存到应用进程内的有界缓存,并提供运行时查询与开关。
_Avoid_: 落盘日志、文件缓存

**数据流 (data streams)**:
插件接管并缓存的六类 agent→OAP 数据:链路段(trace segment)、JVM 指标、meter 指标、应用日志、心跳/实例信息、profile 快照。
_Avoid_: 上报、上报通道

**统计快照 (statistic snapshot)**:
应用侧通过宿主工具类获取的插件状态全貌,按数据流分类组织,是仪表盘与验证断言的数据来源。

**宿主工具类 (toolkit facade)**:
应用 classpath 中的同名桩类,由插件按类名增强,对宿主暴露运行时开关与统计快照查询等能力。

**Trace 告警 (trace alert)**:
基于已合并链路的慢/错识别规则,命中后经 HTTP webhook(或 SPI 监听器)通知外部系统;默认关闭。

**运行时开关 (runtime toggle)**:
不重启应用即可启用/禁用数据流写入本地缓存的能力,用于对比验证。

**演示应用 (demo app)**:
仓库内的 Spring Boot 样例应用,面向"快速理解插件能力"的读者,提供可视化仪表盘与一键启动。
_Avoid_: 样例项目(与验证回路混为一谈)

**验证回路 (validation loop)**:
面向插件作者的快速反馈通道:一条命令完成构建、安装、启动、造数、断言与报告,使改动尽快被验证。
_Avoid_: 手动验证流程
