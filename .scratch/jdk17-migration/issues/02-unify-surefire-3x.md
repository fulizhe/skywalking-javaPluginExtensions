# 02 — 测试栈统一 Surefire 3.x

**What to build:** 三个插件用统一现代 Surefire 跑测试，消除 2.22.2 与 Maven 默认 3.1.2 混用；`logfile-reporter-plugin` 的 `forkCount=0` 与 alert 测试 includes 语义保留；全部现有测试在 JDK 17 下通过（测试兼容）。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 父 pom 以 pluginManagement 统一 `maven-surefire-plugin` 到 3.2.5
- [x] `logfile-reporter-plugin` 保留 `forkCount=0`（同进程运行）与 alert 测试 includes
- [x] 三个模块全部现有测试在 JDK 17 下通过（logfile 45 + httpclient 15 + hutool 13 = 73 例，全绿）
- [x] Mockito/JUnit 版本不升级（测试栈最小化）
