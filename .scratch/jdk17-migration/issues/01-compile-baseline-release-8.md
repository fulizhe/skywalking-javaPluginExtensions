# 01 — 编译基线切到 release 8

**What to build:** 用 JDK 17 工具链编译三个活跃插件时不再出现 `-source 8 已过时` 告警，且 API 边界被强制（误用 JDK 17 独有 API 会编译失败），产物字节码保持基线 8，使插件在 JDK 8 应用上继续安全运行。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 父 pom 编译配置由 `source/target 1.8` 改为 `release 8`（构建兼容）
- [x] JDK 17 下 `mvn compile` 不再输出 source 8 过时告警
- [x] API 边界生效：故意引用 JDK 17 独有 API 会编译失败（`java.util.HexFormat` 实测报错）
- [x] 产物 class 文件版本仍为 8（三模块实测 major=52）
- [x] 三个活跃插件在 JDK 17 下编译通过
