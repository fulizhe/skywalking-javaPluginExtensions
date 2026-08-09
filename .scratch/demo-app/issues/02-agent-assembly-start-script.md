# 02 — 运行底座:agent 装配与启动脚本

**What to build:** 手动模式运行脚本:构建插件 jar、拷入 agent plugins 目录、带 `-javaagent` 与 `-Dskywalking.*` 参数启动演示应用、就绪检查并验证插件已加载——让"在真实 agent 下跑起来"成为一条命令。

**Blocked by:** 01 — 演示应用骨架与宿主工具类桩

**Status:** ready-for-agent

- [ ] 脚本完成插件构建(复用现有 maven 精确选择构建)并拷贝 jar 至 agent plugins 目录
- [ ] 脚本以 `-javaagent` + `-Dskywalking.*` 参数启动演示应用,等待端口就绪
- [ ] 插件加载验证:agent 日志确认插件已装载,脚本输出验证结果
- [ ] 脚本退出码反映失败;agent 路径可配置(环境变量/参数),默认值与仓库文档一致
