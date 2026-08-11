# 02 — 运行底座:agent 装配与启动脚本

**What to build:** 手动模式运行脚本:构建插件 jar、拷入 agent plugins 目录、带 `-javaagent` 与 `-Dskywalking.*` 参数启动演示应用、就绪检查并验证插件已加载——让"在真实 agent 下跑起来"成为一条命令。

**Blocked by:** 01 — 演示应用骨架与宿主工具类桩

**Status:** done

- [x] 脚本完成插件构建(复用现有 maven 精确选择构建)并拷贝 jar 至 agent plugins 目录
- [x] 脚本以 `-javaagent` + `-Dskywalking.*` 参数启动演示应用,等待端口就绪
- [x] 插件加载验证:agent 日志确认插件已装载,脚本输出验证结果
- [x] 脚本退出码反映失败;agent 路径可配置(环境变量/参数),默认值与仓库文档一致

## Comments

- 2026-08-09 已实现并验证:`scripts/run-with-agent.ps1`。
  - 正向全流程实测:插件构建(JDK8 前置,复用 README-compile.md 命令)→ 装入 `plugins\` → `-javaagent` + `-Dskywalking.*` 启动 → 端口就绪 → agent 日志 `AgentClassLoader ... loaded.` 验证通过 → 手动模式保持运行。
  - 负向实测:端口占用→exit 1;无效 agent 目录→exit 1;插件加载失败(坏 jar)→exit 3。
  - agent 目录解析:`-AgentDir` > `SKYWALKING_AGENT_DIR` > 盘符探测 `\apps\apache-skywalking-java-agent-9.4.0`(与 README-compile.md 一致)。
  - code-review 双轴发现并修复:移除无效配置键 `-Dskywalking.plugin.logfilereporter.enabled`(插件无此键,死参数);插件构建环境显式前置 JDK8;agent 日志清理收窄为两个运行时日志文件(避免误删)。
  - 注意:运行用 `pwsh`(PowerShell 7)以规避 Windows PowerShell 5.1 的 UTF-8 解析/控制台编码问题;脚本文件带 UTF-8 BOM,PS 5.1 也可用。
