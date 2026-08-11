# 08 — setup 脚本

**What to build:** 环境自足脚本:检测本机 skywalking-java-agent(9.4.0),缺失则从官方发行包下载;构建并安装插件 jar 至 plugins 目录;产出可直接执行的启动命令——不依赖作者机器。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本(可与 03、04 并行)

**Status:** done

- [x] 检测本机 agent 9.4.0,缺失时从官方发行包下载(版本锁定 9.4.0)
- [x] 构建并安装插件 jar 至 plugins 目录
- [x] 产出可直接执行的启动命令(与运行底座脚本同构)
- [x] 重复执行幂等

实现:
- `scripts/setup.ps1`:流程 检测 agent(`skywalking-agent.jar` 存在即跳过)→ 缺失则从 Apache 官方发行包 `archive.apache.org/dist/skywalking/java-agent/9.4.0/` 下载 `.tgz`(约 33MB)→ 下载 `.sha512` 并校验 → `tar.exe -xzf` 解压(顶层目录 `skywalking-agent/`)→ 移动为 agent 目录(残留目录先清) → JDK/Maven 检查 → 构建插件(JDK8 前置 PATH)并 `Copy-Item -Force` 进 `plugins/` → demo-app jar 缺失则构建 → 产出与 run-with-agent 同构的启动命令(同 $swArgs)。
- 下载实现:优先 `curl.exe -L --fail --retry`(不读系统代理,适合本机直连),失败兜底 `Invoke-WebRequest`(走系统/IE 代理)。
- 幂等验证:agent 已存在时跳过下载;重复执行无副作用。
- 端到端验证:① 本机已有 agent 时幂等跑通;② 全新临时目录触发真实下载 33MB → SHA512 通过 → 解压安装 → 插件装入 → 用**该全新 agent** 真实启动 demo,agent 日志确认 `logfile-reporter-plugin-1.0.0.jar loaded`;③ 临时目录清理。
- README 与导览首页补 setup 小节(工单 07 预留位)。
