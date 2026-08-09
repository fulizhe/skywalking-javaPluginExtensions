# 01 — 演示应用骨架与宿主工具类桩

**What to build:** 独立的 Spring Boot 演示应用骨架(端口 9600),与插件 reactor 零构建耦合;应用侧可按契约静态引用宿主工具类桩,使后续一切验证内容有了落点。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 演示应用以独立 pom 构建(Spring Boot 2.5.4、Java 8),不挂 agent parent、不进 reactor;插件构建命令不受影响
- [ ] 应用可在 9600 端口启动,根路径返回 200;无 agent 时也能正常启动(骨架期独立可验证的前提)
- [ ] 宿主工具类同名桩(FQCN 与插件增强目标一致)随应用编译,应用侧代码可静态引用
- [ ] 基本启动脚本/命令可复现(应用层面,不含 agent 装配)
