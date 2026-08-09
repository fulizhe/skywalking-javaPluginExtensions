# 04 — Trace 告警验证面移植

**What to build:** 移植 Trace 告警验证面:慢/错/忽略规则触发端点、webhook 接收器与进程内事件存储(recent/clear),随附 agent 告警配置样例——告警开 → 事件落存储,端到端可见。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本

**Status:** ready-for-agent

- [ ] 慢/错/忽略规则触发端点可用(阈值与 Ant 规则可配置)
- [ ] webhook 接收器收讫告警事件,recent 可见、clear 可清空
- [ ] agent 告警配置样例随附,与启动参数约定一致;webhook 默认地址指向演示应用自身(9600 契约)
- [ ] 无 agent/插件时端点行为明确(提示或空数据),不抛异常
