# 02 — 值类型折叠进 RulesEngine 嵌套类型

**What to build:** 告警规则这一个概念集中于 `RulesEngine` 一个文件：不再有独立演化的匹配维度枚举（OPERATION/URL）、规则类型枚举、规则绑定、状态码白名单与慢/忽略编译规则类型散落在包内，全部变为引擎的嵌套类型；包的类规模降为「1 个概念文件 + 4 个算法文件 + 1 个解析文件」。运行时行为不变。

**Blocked by:** 01

**Status:** done

- [x] 匹配维度、规则类型、规则绑定、状态码白名单与三种编译规则改为 RulesEngine 嵌套类型，包内无顶层引用残留
- [x] 有独立测试的算法文件（快速路径匹配、Ant 模式缓存、URL 提取、编译后模式）保持为独立文件，不折叠
- [x] `RulesEngine` 对外接口面（fromConfig、慢匹配、忽略规则匹配、计数与绑定读取）不变
- [x] 既有规则引擎测试覆盖折叠后匹配语义，行为断言不变
- [x] logfile-reporter-plugin 的 `mvn test` 全绿（JDK 17 toolchain，字节码基线 8）
