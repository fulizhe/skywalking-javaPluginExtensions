# 09 — 旧项目清理

**What to build:** 迁移完成后,从 sb-skywalking 删除已迁走的验证资产(统计/告警/开关接口、webhook 接收器、仪表盘、宿主工具类桩、配置样例),只保留 SkyWalking 源码学习内容——消除跨仓库双份漂移,单一真源。

**Blocked by:** 06 — 静态仪表盘移植

**Status:** ready-for-agent

- [ ] 已迁移资产从 sb-skywalking 删除(接口、接收器、页面、桩类、配置样例)
- [ ] 源码学习内容保留(官方 toolkit 演示、sw8 header、DataCarrier、动态 appender 等)
- [ ] 删除后 sb-skywalking 可正常构建启动,无残留引用指向已删资产

执行约束(用户 2026-08-11 明确):sb-skywalking 的注释很珍贵,清理必须先迁移注释、后删除重复。

## Comments

- 注释迁移(已提交 e605174):SWLogfileReporterUtils 的 `Refer To TraceContext`/enableReport(SpEL、借鉴 druid)/statisticStatus(缓存结构)设计注补回新桩;仪表盘 meter 指标语义词典(优先前缀/分组依据/忽略建议/约 45 条含义)补回 dashboard.js。
- 边界决策(用户 2026-08-11):demo-app 承担全部活跃扩展插件的集中演示。SWHttpClientCollectController + SWHttpClientCollectUtils 桩**迁移进 demo-app**(controller 加 ASF 头与 javadoc,桩与 override-httpclient-4.x 插件内同名桩一致),随删除清单从旧仓库移除。
- 保留清单:blade/mybatis/swagger、cross-thread、multipart(httpclient/hutool)、DataCarrier 测试、client-js、dynamic appender、MetricsDemoController(自定义 demo_*/user.login 指标未迁移,属学习内容)。
- 删除后需同步改 sb-skywalking 的 SwaggerConfiguration(去掉对 5 个已删 controller 的引用)。
