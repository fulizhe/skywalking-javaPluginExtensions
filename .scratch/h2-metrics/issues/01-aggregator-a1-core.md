# 01 — a1 聚合核心（纯内存，单测验证）

**What to build:** 一个纯内存的 trace 指标聚合核心：喂入 Agent 采集到的原始 segment 后，按"入口段即一次事务"（a1）累计分钟桶，产出按 endpoint 与全局汇总（保留键 `"*"`）的聚合结果，并可导出为 JDK 原生快照。落库依赖抽成一个最小可注入接口，便于测试用假实现，不碰 H2。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [x] 段内含 Entry span（`spanType=Entry` 或 `parentSpanId=-1`）才计为一次事务；无 Entry 的子段/孤段不计，并计数。
- [x] 每个桶同时维护各 endpoint 与全局 `"*"` 两份累加；分位按最近秩：`n>=20` 出全分位、`1<=n<20` 仅 p50、尾分位置空。
- [x] 样本上限（5000/桶/键）触发蓄水池采样并计数；endpoint 基数超限按请求数归并。
- [x] 迟到段在保留窗口（默认 3 个整分）内重算覆盖、窗口外丢弃并计数。
- [x] 异常全部就地捕获 + 计数，绝不外抛。
- [x] 单测覆盖 spec「Testing Decisions」列出的聚合器行为（含全局 `"*"` 行分位等于该桶全样本口径）。
