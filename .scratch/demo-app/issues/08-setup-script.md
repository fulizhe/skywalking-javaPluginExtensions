# 08 — setup 脚本

**What to build:** 环境自足脚本:检测本机 skywalking-java-agent(9.4.0),缺失则从官方发行包下载;构建并安装插件 jar 至 plugins 目录;产出可直接执行的启动命令——不依赖作者机器。

**Blocked by:** 02 — 运行底座:agent 装配与启动脚本(可与 03、04 并行)

**Status:** ready-for-agent

- [ ] 检测本机 agent 9.4.0,缺失时从官方发行包下载(版本锁定 9.4.0)
- [ ] 构建并安装插件 jar 至 plugins 目录
- [ ] 产出可直接执行的启动命令(与运行底座脚本同构)
- [ ] 重复执行幂等
