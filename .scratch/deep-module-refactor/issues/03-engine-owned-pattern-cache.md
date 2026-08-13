# 03 — AntPatternCache 归引擎持有，编译计数经引擎读取

**What to build:** 相同 pattern 只编译一次的去重缓存从进程级静态单例改为 `RulesEngine` 实例字段：缓存生命周期与引擎一致、单测天然隔离（不再依赖全局重置）。启动摘要与状态快照中的「已编译 pattern 数」改为经引擎的编译计数接口读取；状态接口（antPatternCacheSize 键）与启动日志照常输出。

**Blocked by:** 02

**Status:** done

- [x] 去重缓存从静态全局改为引擎实例字段，包内不再有静态缓存访问路径
- [x] 编译计数经引擎接口暴露，启动摘要与状态快照同键继续输出该计数
- [x] 测试不再调用全局重置；单测经引擎实例相互隔离
- [x] 算法文件级的缓存独立测试改为实例化后验证，断言不变
- [x] logfile-reporter-plugin 的 `mvn test` 全绿（JDK 17 toolchain，字节码基线 8）
