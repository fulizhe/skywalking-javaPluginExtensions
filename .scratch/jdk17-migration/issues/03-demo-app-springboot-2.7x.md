# 03 — demo-app 升级 Spring Boot 2.7.x

**What to build:** demo-app 从 Spring Boot 2.5.4 升到 2.7.x（最后一条兼容 Java 8 的 Boot 2.x 线，官方支持至 Java 21），`java.version` 保持 1.8；在 JDK 8 与 JDK 17 两种演示运行时下都能构建、启动并通过验证回路；Micrometer 随 Boot 升至 1.9 仍与 `apm-toolkit-micrometer-registry` 兼容。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Boot parent 2.5.4 → 2.7.18，`java.version` 保持 1.8
- [x] demo-app 在 JDK 8 演示运行时下构建并启动（boot OK, HTTP 200）
- [x] demo-app 在 JDK 17 演示运行时下构建并启动（boot OK, HTTP 200）
- [x] Micrometer 1.9 下 meter 数据流仍可见（validate.ps1 断言 meter 指标有数据 PASS）
