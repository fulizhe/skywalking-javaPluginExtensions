# 04 — 一致性比对器（纯函数）+ debug 对账日志

**What to build:** 开启 `compare_debug` 后，影子数据自动与旧内存视图对账：以旧 store 当前数据为基准，逐 traceId 比对 logs 条数 / span 数 / 关键字段，差异只进日志（计数 + 限速明细），让人尽早发现新路径口径问题。

**Blocked by:** 03 — 接线：影子 accept + 开关 + 异常隔离

**Status:** ready-for-agent

- [ ] 比对器为纯函数：输入旧快照、新快照、待比 traceIds，输出差异报告（缺失 / logs 数 / span 数 / 关键字段 / 解析失败各类）
- [ ] 触发机制：`consume` 批次末尾（旧路径与影子 `accept` 都完成后）、仅 `compare_debug=true`；只比**本批涉及的 traceIds**（迟到 segment 由其所属后续批次再次覆盖，自愈）
- [ ] 限频：默认 ≥5s 一轮，期间累积 traceIds，到点一次取两份快照比对（封顶消费线程上的 debug 成本）
- [ ] 输出：累计计数（checked / 各类差异数）+ 限速明细（同类差异 30s 最多一条，带样例 traceId）；比对自身异常吞掉
- [ ] 单元测试覆盖各差异类型与"零差异"路径
- [ ] 票内备注：周期全量对账为后续备选（两边淘汰时序差会造假差异），本期不做

## Comments
