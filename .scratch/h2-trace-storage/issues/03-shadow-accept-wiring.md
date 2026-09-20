# 03 — 接线：影子 accept + 开关 + 异常隔离

**What to build:** 真实流量下 H2 开始积累影子数据，而旧路径与一切对外输出零变化：宿主客户端在既有合并 / 告警逻辑之后新增一次 `accept(data)` 调用；开关关闭时零开销；H2 侧任何故障只留下计数与日志。

**Blocked by:** 02 — TraceSegmentStorage 接口 + H2 内存模式实现

**Status:** ready-for-agent

- [ ] `consume` 既有逻辑之后新增一次 `accept`；`h2.enabled=false` 时完全不执行（无任何 H2 相关开销）
- [ ] H2 初始化 / 建表 / insert 的任何异常不外抛：计数 + 限速日志
- [ ] 验证回路现有断言全绿（对外零行为变化）
- [ ] 既有告警行为与去重不变（影子路径绝不触发告警）

## Comments
