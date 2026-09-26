# 2026-09-26：端点极端值 → traceId 追溯（Phase 5 追加，第一步）

何时读：改动 Trace 指标聚合、`/inner/sw/metrics*` 读口、或推进 "error/slow 明细持久化到 H2" 闭环时。

## 背景（本次会话状态）

- 昨日数次更新后**重启**，拉起最新插件 jar（含孤段过滤 + `orphanKinds`），随即持续压测至今；大屏数据为真实流量。
- 观察到的痛点：大屏能看到"最大耗时 5.60s""P99 41ms"等**极端值**，却**无法进一步追溯到具体链路**——指标与链路明细之间缺一根指针。

## 本次实现（第一步：记录极端情况下的 trace）

在指标计算时，按 endpoint 记下"最极端"那一次的 traceId 与现场；后续 slow/error 明细持久化到 H2 后，该 traceId 即成为**闭环追踪链条**入口。

- 口径：**每端点仅保留最大耗时那一条**（首次必记，严格更大才替换）。
- 存储：**内存**（聚合器内 `extremeByEndpoint`，端点数上限 500），随进程、不落库；进程重启即清空。
- 读口：`GET /inner/sw/metrics/extremes` → `{count, selector, rows:[{endpoint,service,traceId,durationMs,error,startTime}]}`（按耗时降序）。
- 大屏：`metrics.html` 端点详情("最大耗时"旁)新增 **极端值 traceId** 单元，点击经 `/inner/sw/trace-query?traceId=…` 取回链路（若已持久化）。
- 数据面另在 `/inner/sw/metrics` 快照里带 `extremeSelector` 与 `extremes`（便于脚本断言）。

### 预留（本次不做）

- **可配置阈值 / 自适应阈值**：例如截图里 `GET:/hello` 出现 800ms 即需关注（低于全局慢阈值 3s，但对该接口是离群）。
  判定策略抽成接口 `metrics.ExtremeTraceSelector`（默认实现 `MaxDurationExtremeTraceSelector`，`name()="max-duration"`），
  下一步以"固定阈值 / 相对 P99 基线自适应"新实现注入即可，聚合器与读口契约不变。
- **持久化**：极端 traceId 目前的"指向"在进程重启后失效；等 slow/error 明细落 H2 后，指向即可跨重启解析。

## 涉及文件

- `agent/logfile-reporter-plugin/src/main/java/.../metrics/ExtremeTrace.java`、`ExtremeTraceSelector.java`、`MaxDurationExtremeTraceSelector.java`（新增）
- `.../metrics/TraceMetricsAggregator.java`：`onSegment` 内 `recordExtreme`；`extremeTraces()` / `getExtremeSelectorName()`；`snapshot()` 增 `extremeSelector`/`extremes`
- `.../logfile/LogFileTraceSegmentServiceClient.java`：`getExtremeTraces()`
- `.../plugin/logfilereporter/MetricsExposeInterceptor.java` + `define/SWMetricsUtilsInstrumentation.java`：跨 ClassLoader 增 `extremeTraces()`
- `agent/demo-app/src/main/java/.../toolkit/SWMetricsUtils.java`（桩）+ `.../controller/StatisticController.java`（`/inner/sw/metrics/extremes`）
- `agent/demo-app/src/main/resources/static/dashboards/metrics.html`（详情挂链）
- `CONTEXT.md`：术语「极端值追溯」

## 验证

- `mvn -o -pl logfile-reporter-plugin test`：**149 tests, 0 failures**（新增 6 项：每端点取最大 / 端点间隔离 / 非入口段不计 / 错误标记 / 端点上限 / snapshot 暴露）。
- `mvn -o -f agent/demo-app/pom.xml compile`：通过。
- `metrics.html` 内联 JS 解析：通过（本次未做端到端浏览器验证；静态资源需重建/重启 demo-app 生效）。

## 下一步（形成闭环）

1. slow/error 明细持久化到 H2（见 `CONTEXT.md`「trace 持久层」），使极端 traceId 可跨重启解析。
2. 阈值化/自适应 `ExtremeTraceSelector`（配置项预留）。
3. 可视/API 完善：极端值列表页、按 endpoint 历史极端桶。
