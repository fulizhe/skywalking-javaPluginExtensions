# 01 — 删除规则链透传层（RuleSyntax + 三个透传）

**What to build:** 告警规则从「配置串 → 匹配器」不再经过 3 个空壳跳转与一个假扩展点。单值枚举 `RuleSyntax`、仅做转发的 `PatternMatcherFactory`、`AntTracePatternMatcher`、`TracePatternMatcher` 接口全部移除；规则对象构造不再携带语法枚举与接口类型，直接装配编译后的匹配算法。包内规则链变成「解析 → 编译规则」直连，语义零跳转。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 单值枚举与三个透传类/接口被删除，alert 包内无残留引用
- [x] 慢规则、忽略规则构造函数去掉语法枚举与匹配器接口参数，直接持有编译后的匹配算法
- [x] 针对已删内部细节的测试一律删除，不为新内部细节编造测试
- [x] 依赖透传层的既有测试改为直接装配匹配算法，行为断言不变
- [x] logfile-reporter-plugin 的 `mvn test` 全绿（JDK 17 toolchain，字节码基线 8）
