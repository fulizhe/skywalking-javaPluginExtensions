# 03 — 慢查询（行列表）+ 专门慢查询页

**What to build:** 按 `endpoint` + `latency` 阈值查询慢段，返回**键值对行集合**（`List<Map<String,Object>>`，与 `recentTraces` 同构，纵向零 DTO）；demo 提供专门的慢查询页面（填 endpoint、阈值、limit → 列表 → 点开看整条链路）。先解决"有无"，不做服务端聚合。

**Blocked by:** 01 — 全量 trace 持久化 + 放大容量 + 删 `trace_level`。

**Status:** ready-for-agent

- [ ] 存储新增"按 endpoint + 阈值查慢段"方法，返回 `List<Map<String,Object>>`，按 `latency` 降序、受 `limit` 截断
- [ ] 每行键含 `traceId / traceSegmentId / service / endpoint / startTime / latency / isError / hasPayload / payloadExpired`（`latency`、`startTime` 为数值，可直接喂图表）
- [ ] 宿主工具类新增对应静态方法 + 拦截器分发 + instrumentation 匹配；demo 读口 `/inner/sw/trace-slow`
- [ ] 新增 `trace-slow.html`：endpoint + 阈值 + limit → **表格列表** → 点行打开 `trace-view.html` 下钻；图表为前端可选、不新增服务端聚合返回
- [ ] 查询异常全部就地捕获、计数、绝不外抛
