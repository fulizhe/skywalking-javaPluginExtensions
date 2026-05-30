# Trace Alert 规则引擎：Ant 统一与性能优化

> 留作后续实现。详细使用说明见 [README-trace-alert.md](README-trace-alert.md)。

## 最终目标

1. **SLOW**（`slow_rules`）与 **ERROR 白名单**（`error_ignore_rules`）统一使用 **Ant 路径语法**。
2. **废弃**现有 `url:` Java 正则及 operation 简单 `*` 通配写法。
3. **性能**：pattern 在 Agent 启动期预编译并缓存，热路径（`TraceEvaluator.evaluate`）不做解析、不做重复编译。

---

## 现状（截至当前实现）

| 场景 | 配置键 | 当前语法 | 匹配方式 |
|------|--------|----------|----------|
| 慢阈值 | `slow_rules` | `operation:` + `*`；`url:` + 正则 | `TraceSpanUtils.matchesOperationPattern` / `matchesUrlPattern` |
| 错误白名单 | `error_ignore_rules` | `operation:` + `*`；`url:` + 正则 | `ErrorIgnoreRuleMatcher`（L1 `span.isError` + 状态码） |

**已知问题**

- 404 误告警多来自 HTTP 插件 `statusCode >= 400` → `span.isError`，而非 L2 状态码阈值。
- 正则对运维不友好（转义、`.`、`*` 语义与 Ant 不同）。
- Ant 若每次 `match()` 内 `pattern → regex → String.matches()`，会在热路径重复编译，需优化。

---

## 设计原则（开闭 + 分层）

```text
配置串
  ↓ 启动期解析一次（fromConfig）
RulesContext（规则列表 + 全局 ruleIndex + descriptor + 预编译 Matcher）
  ↓
TraceEvaluator（热路径：只读匹配）
  ↓
TraceAlertMetrics（按 ruleIndex 累计 hitCount）
```

- **解析 / 编译**：仅在 Agent 启动或 `TraceEvaluator.fromConfig` 时完成。如果失败则静默抛弃，在Metrics里体现即可，就像当前这种列出生效的配置项。
- **策略接口化**：`TracePatternMatcher` / `UrlPatternMatcher`，Ant 为最终唯一实现；Regex 仅迁移期保留。
- **组合而非修改**：`MatchingHub` / `RulesEngine` 编排；旧 Matcher 迁移完成后删除。
- **对外 API 不变**：SPI 仍用 `TraceAnomalyListener`、`TraceSnapshot`、`TraceAlertEvent`。

---

## 目标配置语法（建议）

与 Spring `AntPathMatcher` 语义对齐：

```text
# 慢阈值（毫秒）
operation-ant:GET:/api/order/**=8000
url-ant:/inner/probe/**=60000

# 错误白名单（HTTP 状态码，逗号分隔）
operation-ant:GET:/api/exists/**=404
url-ant:/inner/business-test/**=404,410
```

约定：

- `operation-ant:`：对 span 的 `operationName` 整串做 Ant 匹配。
- `url-ant:`：对 URL 的 **path** 匹配（`http://host:port/path?query` → 取 `/path`）。
- Ant 通配：`?` 单字符、`*` 单段、`**` 跨段。
- 优先级：`operation-*` 先于 `url-*`；同类型按配置顺序。
- ERROR 白名单：仅当 span 有 `http.status_code` 且命中允许列表时才豁免；无 status tag 的 `isError` 仍告警。

**迁移期（可选）**：保留 `url:` / `operation:`，启动日志打 `@Deprecated` 提示及等价 Ant 写法；一个版本后删除。

---

## 统一匹配内核（待实现）

```text
TracePatternMatcher              // boolean matches(String text)
  └── AntTracePatternMatcher     // 最终唯一实现
      └── CompiledAntPattern     // 启动期编译，不可变

CompiledRule                     // index, descriptor, matchKind, matcher, payload
  ├── SlowCompiledRule           // payload = thresholdMs
  └── ErrorIgnoreCompiledRule    // payload = allowedStatusCodes

RulesEngine                      // 启动 build；运行时 matchSlow / matchErrorIgnore
```

SLOW 与 ERROR 共用 `CompiledRule` + `AntTracePatternMatcher`，仅 payload 与判定逻辑不同。

---

## 性能设计（重点）

| 层级 | 做法 |
|------|------|
| 规则级 | 解析时将 pattern 编译为 `CompiledAntPattern`，存入 `CompiledRule`，生命周期 = 进程 |
| Pattern 去重 | `ConcurrentHashMap<String, CompiledAntPattern>`，相同 pattern 只编译一次 |
| 热路径 | `evaluate()` 仅循环 `rule.matcher.matches(text)`，无 split / parse / compile |
| 状态码集合 | ERROR 规则用 `IntSet` / 固定 `boolean[600]`，避免每次 `Set.contains` 装箱 |
| URL path | 可先每次 `extractPath(url)`；规则量大时再考虑轻量 LRU |
| 规则索引 | 无通配符的精确规则可 `HashMap` O(1) 前置，再扫 Ant 规则 |

**禁止**：在 `TraceEvaluator.hasError()` / `isSlow()` 热路径调用 `Pattern.compile` 或 `antPatternToRegex`。

---

## 实施阶段

### P0 — Ant 预编译与缓存（优先）

- [ ] 新增 `CompiledAntPattern`（启动期 `compile(pattern)`，内部持有 `java.util.regex.Pattern` 或自研 Ant 自动机）。
- [ ] 新增 `AntPatternCache`（`computeIfAbsent` 复用实例）。
- [ ] 将现有 `url-ant` 热路径改为使用 `CompiledAntPattern`，去掉每次 match 的重复编译。
- [ ] 单测：同 pattern 多次 match 只编译一次（可通过 compile 计数 mock 验证）。

### P1 — SLOW 支持 Ant

- [ ] `slow_rules` 增加 `operation-ant:`、`url-ant:` 前缀（Parser 扩展或新 `SlowRulesFactory`）。
- [ ] `TraceSpanUtils.resolveSlowThresholdMs` 改为委托 `RulesEngine` / Ant matcher。
- [ ] 与旧 `operation:` / `url:` 并存，文档给出对照表。
- [ ] 单测：Ant 慢规则优先级、默认阈值回退。

### P2 — ERROR / SLOW 统一 RulesEngine

- [ ] 合并 `ErrorIgnoreRulesContext` + Slow 规则为统一 `RulesEngine` / `AlertRulesContext`。
- [ ] 单一 `RulesAggregateParser` 按配置顺序分配 `ruleIndex`（metrics 对齐）。
- [ ] `TraceEvaluator` 只依赖 `RulesEngine`，删除分散的 Matcher 调用。

### P3 — 废弃正则

- [ ] 删除 `url:` 正则分支、`TraceSpanUtils.matchesUrlPattern`（若无其他引用）。
- [ ] 删除 `RegexErrorIgnoreUrlPatternMatcher` 及迁移期 Hub 分支。
- [ ] 更新 README-trace-alert.md、配置样例（含 README.md 中的 JVM 示例）。

### P4 — 观测与运维

- [ ] `traceAlert.rules[]` 统一结构：`ruleIndex`、`rule`、`type`（SLOW/ERROR_IGNORE）、`syntax`（ant）、`hitCount`。
- [ ] 启动日志输出解析规则数、Ant pattern 缓存大小。
- [ ] 补充正则 → Ant 迁移对照文档。

---

## 评估热路径（目标形态）

```text
segment 合并完成
  → AsyncTraceAlertDispatcher.afterTraceMerged
  → TraceEvaluator.evaluate(snapshot)
       ├─ hasError:  遍历 spans → RulesEngine.matchErrorIgnore(span)
       └─ isSlow:    RulesEngine.matchSlow(entryOperation, url, durationMs)
  → 命中则 dispatch（NOTIFIED_CACHE_TTL 去重不变）
```

---

## 测试清单

- [ ] Ant 边界：`**`、`/*`、`?`、空 path、带 query 的 URL。
- [ ] operation-ant / url-ant 优先级与配置顺序。
- [ ] ERROR：404 豁免 / 500 仍告警 / 无 `http.status_code` 不豁免。
- [ ] SLOW：命中规则阈值 vs 默认阈值。
- [ ] 混合规则 metrics：`hitCount` 与 `ruleIndex` 与配置顺序一致。
- [ ] 性能：热路径无 compile（静态分析或 micro-benchmark 可选）。

---

## 正则 → Ant 迁移对照（示例）

| 正则（旧 `url:`） | Ant（新 `url-ant:`） |
|-------------------|----------------------|
| `.*/inner/business-test/.*` | `/inner/business-test/**` |
| `.*/api/exists/[0-9]+` | `/api/exists/*` |
| `operation:GET:/api/exists/*` | `operation-ant:GET:/api/exists/**` |

---

## 相关类（当前 / 规划）

| 类 | 状态 | 说明 |
|----|------|------|
| `ErrorIgnoreRuleMatcher` | 现有 | 正则 operation/url，迁移后删除 |
| `ErrorIgnoreRuleMatchingHub` | 现有 | 组合 Matcher，迁移后由 `RulesEngine` 替代 |
| `AntPathPatternMatcher` | 现有 | 需改为 `CompiledAntPattern` + 缓存 |
| `ErrorIgnoreRulesFactory` | 现有 | 可演进为 `RulesAggregateParser` |
| `CompiledAntPattern` | 待建 | 预编译 Ant pattern |
| `RulesEngine` | 待建 | SLOW + ERROR 统一入口 |
| `TraceEvaluator` | 现有 | 最终只依赖 `RulesEngine` |

---

## 参考

- [README-trace-alert.md](README-trace-alert.md) — 当前配置与 webhook 说明
- Spring `AntPathMatcher` 语义（`**` / `*` / `?`）
