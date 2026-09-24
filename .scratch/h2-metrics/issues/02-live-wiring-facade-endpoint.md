# 02 — 实时打通：consume 挂钩 + SWMetricsUtils facade + /inner/sw/metrics

**What to build:** 让真实运行中的应用产出并可读到指标：客户端在批量消费时把每条 segment 喂给聚合核心；新增宿主工具类 `SWMetricsUtils`（桩 + 拦截器 + instrumentation 定义 + `.def` 注册），暴露 `statisticMetrics()` 只读快照；演示应用新增读口，访问业务接口后即可看到实时指标（本票仅内存快照，暂无历史）。

**Blocked by:** 01.

**Status:** ready-for-agent

- [x] 挂钩只新增一次喂入调用，既有合并 / 告警 / 存储路径零改动、对外行为不变。
- [x] `SWMetricsUtils.statisticMetrics()` 经反射跨 ClassLoader 取值，只返回 JDK 原生 Map。
- [x] 演示应用读口返回契约字段（enabled / storageEnabled / currentBucket / buckets / counters…）。
- [x] 手工验证：造流量后读口数值与预期一致（请求数 / 错误 / 慢 / 分位随流量增长）。
- [x] H2 不可用时不影响实时快照（降级无损）。
