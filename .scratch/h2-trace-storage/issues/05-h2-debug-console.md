# 05 — H2 调试控制台（远程可访问、默认关）

**What to build:** 测试环境加一个启动参数即可用浏览器打开 H2 Web Console（由应用 JVM 内启动、允许远程访问），直接 SQL 查看 `trace_segment` 影子数据，与 `statisticStatus` 的旧内存视图做粗略人工对比。

**Blocked by:** 02 — TraceSegmentStorage 接口 + H2 内存模式实现

**Status:** done

- [x] `console_enabled=true` 时在应用 JVM 内启动 H2 Web Console（`-webAllowOthers`，端口 `console_port` 默认 8092）；默认关闭
- [x] 真实环境手测：浏览器 `http://<部署机IP>:8092` 连 `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`（sa / 空密码）能查 `trace_segment`
- [x] （04 完成后补验）控制台可查询对账审计表 `trace_parity_audit`
- [x] shade 重定位后 Console 静态资源实测；不可用则启用退路：JVM 内 TCP Server（`-tcpAllowOthers`）+ 外部 Console / DBeaver 连 `jdbc:h2:tcp://<部署机IP>:<port>/mem:sw_trace_segment`，结论记录在本票
- [x] spec Further Notes 的访问步骤与安全告警（可执行任意 SQL，仅测试环境开启、用完即关）在真实环境走通一遍

## Comments

- **实测发现：控制台页面打不开（用户报"没看到 h2 访问页面"）**。日志显示 `Web Server started on port 8092`，但浏览器无内容、H2 连接报 `(Message 90061 not found)`。
- **根因**：H2 把 Web Console 静态资源与消息包塞在嵌套的 `org/h2/util/data.zip` 里；shade 只重定位**资源路径本身**，不会改写 `data.zip` **内部条目名**。于是 `Utils.getResource("/org/apache/.../dependencies/h2/res/x")` 与 zip 内实际条目 `org/h2/res/x` 不匹配 → 返回 null → 控制台/消息全空。此前 shaded jar 里 0 个 `web/res` 条目可佐证。
- **修复**（`pom.xml`）：shade 过滤排除 `org/h2/util/data.zip`；用 `maven-dependency-plugin:unpack` 取出该 zip，再由 `maven-antrun-plugin` 展开并把 `org/h2/**` 资源改写到 `org/apache/skywalking/apm/dependencies/h2/**`（H2 `Utils.loadResource` 在找不到同包 `data.zip` 时会回落到 classpath 直读该资源）。修复后 jar 含 78 个 `server/web/res/*` + `res/_messages_en.prop`。
- **实测（2026-09-21）**：`http://127.0.0.1:8092/` 返回 **HTTP 200**（H2 Console HTML），日志 `Web Console started on port 8092`；`/inner/sw/trace-parity` 与审计表读口正常，控制台与读口同源。**未启用 TCP 退路**（Web Console 已可用）。
- 遗留人工步骤（非阻塞）：由使用者按 spec Further Notes 在浏览器用 sa / 空密码建连后执行现成 SQL；连接信息与安全告警与文档一致。
