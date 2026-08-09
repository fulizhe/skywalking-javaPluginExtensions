# 09 — 旧项目清理

**What to build:** 迁移完成后,从 sb-skywalking 删除已迁走的验证资产(统计/告警/开关接口、webhook 接收器、仪表盘、宿主工具类桩、配置样例),只保留 SkyWalking 源码学习内容——消除跨仓库双份漂移,单一真源。

**Blocked by:** 06 — 静态仪表盘移植

**Status:** ready-for-agent

- [ ] 已迁移资产从 sb-skywalking 删除(接口、接收器、页面、桩类、配置样例)
- [ ] 源码学习内容保留(官方 toolkit 演示、sw8 header、DataCarrier、动态 appender 等)
- [ ] 删除后 sb-skywalking 可正常构建启动,无残留引用指向已删资产
