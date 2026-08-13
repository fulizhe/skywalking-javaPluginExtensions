# deep-module-refactor: logfile-reporter-plugin 深模块重构（RulesEngine + KeyedLocalStore）

Status: done

## Problem Statement

架构评审（`docs/review/architecture-review-20260808-220621.html`）针对 `logfile-reporter-plugin` 给出两个 Strong 候选，本 spec 承接其落地：

**A · 规则链浅跳转**：告警规则这一个概念（"规则 = 一条已解析的配置串，匹配 operation/url 并携带载荷"）散落在 alert 包 18 个文件，其中 3 个纯透传：`PatternMatcherFactory`（仅 `new AntTracePatternMatcher`）、`AntTracePatternMatcher`（仅 `AntPatternCache.get`）、`TracePatternMatcher` 接口（单实现）。真实逻辑实际集中在 `FastPathAntMatcher`（~100 行，有独立测试）与 `AntPatternCache`（去重 + 编译计数）。另有 `RuleSyntax` 枚举只有 `ANT` 一个值、从未被分支读取。

**B · 有界缓存五处分叉**：五种存储语义各写一遍——4 个追加式环形队列（JVM 1000 / Meter 300 / Log 1000 / Profile 500，均 `CircularBlockingQueue`）与 1 个键式 trace 存储（同步 `LinkedHashMap<traceId, Map>`）。trace 存储被称为"LRU"实为 FIFO（`accessOrder=false`），其合并/淘汰/快照/告警分发逻辑全部内嵌在 `LogFileTraceSegmentServiceClient.mergeLogIntoStatMap`，无独立测试。

## Solution

### A · 深化 RulesEngine（alert 包）

- **删除**：`PatternMatcherFactory`、`AntTracePatternMatcher`、`TracePatternMatcher`（delete 3 透传）、`RuleSyntax`（单值枚举）。
- **保留 RulesAggregateParser 为 package-private 文件**：解析逻辑拆分，不折叠进 RulesEngine；仅控制类访问为 package 级。
- **折叠进 `RulesEngine.java` 作嵌套类型**：`MatchKind`、`AlertRuleType`、`AlertRuleBinding`、`StatusCodeAllowlist`、`CompiledRule` + `SlowCompiledRule` + `ErrorIgnoreCompiledRule`。
- **保留为 package-private 文件**：`FastPathAntMatcher`、`AntPatternCache`、`UrlPathExtractor`、`CompiledAntPattern`（真实逻辑，各有独立测试）。
- **缓存改为引擎实例字段**：`AntPatternCache` 从静态全局改为 `RulesEngine` 持有；`getCompiledPatternCount()` 暴露编译条数；`TraceAlertMetrics.bindRules(rulesEngine)` 改持引擎引用，`snapshot()` 经引擎读 `getCompiledPatternCount()`（替换静态 `AntPatternCache.cacheSize()`）。
- **接口面**：`fromConfig(...)`、`matchSlow`、`matchErrorIgnoreRuleIndex`、计数/getRuleBindings/getCompiledPatternCount。`TraceEvaluator` 是消费者，不改。

### B · 键式有界数据存储（仅 trace 流）

- **保持现状不动**：`CircularBlockingQueue` 及其公开接口、4 个环形 sender、`CircularBlockingQueueTest`。仅做文档纠正（README/注释"LRU"→"FIFO"）。
- **新增通用 `KeyedLocalStore<K,V>`**（放 `org.apache.skywalking.apm.agent.core.reporter.logfile` 包）：
  - `put(K, V)`、`merge(K, BiFunction<V,V,V>) → V`（返回更新后的值）、`snapshot(): Map<K,V>`、`size()`
  - FIFO 淘汰（最旧插入先出）、单锁守护写 + 快照读
  - 本次实例化 `K=traceId`、`V=Map<String,Object>`；`snapshot()` 保持 host 形状 `data[traceId].logs` 不变（demo-app `StatisticController` 依赖此形状）。
- **`LogFileTraceSegmentServiceClient` 改造**：同步 `LinkedHashMap` 替换为 `KeyedLocalStore`；`mergeLogIntoStatMap` 改为 `store.merge(traceId, 合并函数)`，合并采用"只替换不原地修改"（remapper 返回新构造值，已发布值不再被改写），返回的更新值在**锁外**直接调 `afterTraceMerged`（无需再拷贝），webhook 分发不占存储锁。

## User Stories

1. 作为插件作者，我想告警规则语义集中于 RulesEngine 一处（删 3 透传、非法回溯），以便读 1 个概念文件 + 几个算法文件即懂逻辑。
2. 作为插件作者，我想 RuleSyntax 单值枚举被删除，以便消除"假的扩展点"。
3. 作为插件作者，我想 AntPatternCache 归 RulesEngine 持有、编译计数经引擎读，以便缓存生命周期与引擎一致、去掉静态全局。
4. 作为插件作者，我想 trace 流的合并/淘汰/快照/告警分发有独立模块（KeyedLocalStore）与独立测试，以便该最复杂存储不再内嵌在 service 客户端里。
5. 作为插件作者，我想 trace 快照的 host 形状 `data[traceId].logs` 完全不变，以便 demo-app 仪表盘与验证回路零改动。
6. 作为插件作者，我想 4 个环形队列与 KeyedLocalStore 的锁行为差异被 ADR 记录，以便未来读者理解"为何不统一成单锁"。
7. 作为维护者，我想 alert 包测试保持细粒度（`FastPathAntMatcherTest`/`AntPatternCacheTest`/`UrlPathExtractorTest` 保留），以便算法逻辑仍然独立验证。
8. 作为维护者，我想文档中"有界缓存"统一为 FIFO 语义（不再有 LRU 旧称），以便语义与实现一致。

## Implementation Decisions

- 折叠进 RulesEngine 的仅限"无独立测试、无独立演化的值类型/枚举"；凡有独立测试算法文件一律保留 package-private 文件（deletion test：仅供内部实现细节且逻辑会集中）。
- KeyedLocalStore 用泛型而非 trace 专用类：合并语义是存储职责（锁内原子读改写），adapter 只做 segment→Map 转换与容量声明，保持职责收敛。
- 告警分发在存储锁外执行：`merge` 返回更新值，合并采用"只替换不原地修改"，客户端在锁外直接调用 `afterTraceMerged`，避免 webhook 网络 I/O 占用存储临界区。
- 接口保持 package 可见（同包消费：TraceEvaluator/TraceAlertMetrics/LogFileTraceSegmentServiceClient 均在 `agent.core...reporter.logfile[.alert]` 内），不扩大为 public API。
- 决策记录：`docs/adr/adr-02-two-shapes-for-bounded-local-storage.md`；`CONTEXT.md` 已新增词条（告警规则/规则引擎/匹配维度/有界数据存储）。

## Testing Decisions

- 好测试的定义：只测外部可见行为，不测实现细节。
- **删除即通过**：`PatternMatcherFactory`/`AntTracePatternMatcher`/`TracePatternMatcher`/`RuleSyntax` 删除后，若存在针对它们的测试则一并删除，不得为新"内部细节"编造测试。
- **保留（A）**：`FastPathAntMatcherTest`、`AntPatternCacheTest`、`UrlPathExtractorTest`、`RulesAggregateParserTest`（待删 `RuleSyntax.ANT` 断言）、`RulesEngineTest`（覆盖折叠后 match 语义）、`TraceEvaluatorTest`。
- **变更（A）**：`TraceAlertMetricsTest` 中 `bindRules`/`snapshot` 改为经引擎读编译计数。
- **新增（B）**：`KeyedLocalStoreTest` —— FIFO 淘汰边界、`merge` 原子性与返回更新值、`snapshot` 容器独立拷贝、并发写读（单锁）。
- **保持现状（B）**：`CircularBlockingQueueTest`（接口不变）。
- 验证入口：`agent/logfile-reporter-plugin` 的 `mvn test`（JDK 17 toolchain，字节码基线 8）；端到端回归走 demo-app 验证回路（trace 合并 + `data[traceId].logs` 形状 + 告警 webhook 仍工作）。

## Out of Scope

- 评审候选 C（status-expose 编译化契约）与候选 D（dispatcher 线程生命周期拆分）——本 spec 不承接。
- 统一 4 个环形队列与 KeyedLocalStore 的锁/快照行为（ADR-02 明确为有意取舍）。
- 折叠 `FastPathAntMatcher`/`AntPatternCache`/`UrlPathExtractor`/`CompiledAntPattern` 进 RulesEngine。
- `CircularBlockingQueue` 公开接口缩减与 `LogFileTraceSegmentServiceClient` 之外的 sender 改造。

## Further Notes

- 术语见 `CONTEXT.md` 的 Trace 告警/有界数据存储词条；A/B 决策取舍见 `docs/adr/adr-02-two-shapes-for-bounded-local-storage.md`。
- 实现时 Alert 包与 `reporter.logfile` 包内文件均为 package 可见，确认无跨包引用（`WebhookUrlResolver` 等在 `alert` 包内自洽）。
- 改动集中在 alert 包的删除/嵌套与 `LogFileTraceSegmentServiceClient` 一处；先做 A（规则链，评审 top 推荐、活跃热点），再做 B。
