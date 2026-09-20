# 05 — H2 调试控制台（远程可访问、默认关）

**What to build:** 测试环境加一个启动参数即可用浏览器打开 H2 Web Console（由应用 JVM 内启动、允许远程访问），直接 SQL 查看 `trace_segment` 影子数据，与 `statisticStatus` 的旧内存视图做粗略人工对比。

**Blocked by:** 02 — TraceSegmentStorage 接口 + H2 内存模式实现

**Status:** ready-for-agent

- [ ] `console_enabled=true` 时在应用 JVM 内启动 H2 Web Console（`-webAllowOthers`，端口 `console_port` 默认 8092）；默认关闭
- [ ] 真实环境手测：浏览器 `http://<部署机IP>:8092` 连 `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`（sa / 空密码）能查 `trace_segment`
- [ ] （04 完成后补验）控制台可查询对账审计表 `trace_parity_audit`
- [ ] shade 重定位后 Console 静态资源实测；不可用则启用退路：JVM 内 TCP Server（`-tcpAllowOthers`）+ 外部 Console / DBeaver 连 `jdbc:h2:tcp://<部署机IP>:<port>/mem:sw_trace_segment`，结论记录在本票
- [ ] spec Further Notes 的访问步骤与安全告警（可执行任意 SQL，仅测试环境开启、用完即关）在真实环境走通一遍

## Comments
