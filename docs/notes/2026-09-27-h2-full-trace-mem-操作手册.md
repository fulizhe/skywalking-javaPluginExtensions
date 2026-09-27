# 2026-09-27：h2-full-trace-mem 操作手册（全量 trace / 双源对照 / 慢查询 / QPS / 慢判定复用）

何时读：过一段时间忘了**这些功能在哪看、怎么开、怎么验证**时；或要重新构建/重启 demo 观察效果时。

> 决策与路线：`docs/adr/adr-04-h2-mem-as-full-trace-persistence-tier.md`、`docs/todos/h2化-统一方案.md`、`.scratch/h2-full-trace-mem/spec.md`。
> 容量实测：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`。

---

## 0. 一句话

H2 **内存模式**成为链路查询的**唯一真相来源**（normal 也全量入，`N=100000`）；配套**双源对照（H2 vs 内存热层）+ 逐段 diff**、**慢查询页**、metrics **QPS**、metrics **慢判定复用告警 `slow_rules`**。

---

## 1. 页面与读口一览

demo 端口用 `-Port`（默认 9600；**9600 常被用户占用，验证请换端口**，如 9605/9606）。

| 用途 | 页面 | 读口 |
| --- | --- | --- |
| Trace 指标大屏（QPS 列/卡片/详情） | `/dashboards/metrics.html` | `/inner/sw/metrics`、`/inner/sw/metrics/query`、`/inner/sw/metrics/extremes` |
| 双源对照 + 逐段 diff | `/dashboards/trace-query.html` | `/inner/sw/trace-query?traceId=`（H2）、`/inner/sw/trace-memory?traceId=`（内存热层）、`/inner/sw/trace-recent?limit=` |
| 慢查询页 | `/dashboards/trace-slow.html` | `/inner/sw/trace-slow?endpoint=&minLatencyMs=&limit=` |
| 链路可视化 | `/dashboards/trace-view.html?traceid=` | — |
| 影子对账/孤段 | — | `/inner/sw/trace-parity` |

---

## 2. 功能怎么看

### 2.1 全量 trace（H2 mem）
- `trace_segment` 单表，**normal + error/slow 都写**；行上限 `shadow_max_rows=100000`（堆 ≈ 80MB）。
- 看：`trace-query.html` 的「最近列表」会出现 normal 请求；`/inner/sw/trace-parity` 的 `h2Size` 随请求增长。
- `trace_level` 列**已删除**：错误查 `is_error`、慢查 `latency >= 阈值`。

### 2.2 双源对照 + 逐段对齐 diff（`trace-query.html`）
1. 「刷新列表」→ 点一行（填入 traceId）→ 点 **「双源对照」**。
2. 左 **H2（为准）**、右 **内存热层（参照）**；顶部提示条数是否一致。
3. 下方 **逐段对齐表**：按 `traceSegmentId` 对齐，逐段状态
   - `both`（绿）= 两侧都有且 span 数相同；
   - `span-diff`（黄）= 两侧都有但 span 数不同；
   - `h2-only`（红）/ `mem-only`（黄）= 仅一侧有。
4. 解读：**H2 有、内存空** 正常（内存热层 FIFO，上限 `max_log_size=1000` 个 traceId，老 trace 已淘汰）——**以 H2 为准**；两侧都有但条数不同，多为异步写时序，稍等再查通常收敛。

### 2.3 慢查询（`trace-slow.html`）
- 填 `endpoint`（如 `GET:/longTimeTask`，即入口 span 的 `operationName`）+ 阈值 `minLatencyMs` + `limit` → 列表按 **latency 降序**。
- `latency` 是**段级**（段内 `maxEnd-minStart`）；点行下钻 `trace-view.html`。
- 阈值可对齐告警 `slow_rules`（不同端点的差异化阈值）。
- 底层返回是**键值对行集合**（`List<Map>`），字段：`traceId/traceSegmentId/service/endpoint/startTime/latency/isError/hasPayload/payloadExpired`。

### 2.4 QPS（`metrics.html`）
- 表格 **QPS 列**（在「请求」右侧）、KPI 区 **「QPS」卡片**、点 endpoint 的**详情面板**里也有 `QPS` 单元格。
- **口径 = 活跃平均**：`qps = 总请求 / (有数据桶数 × 单桶秒)`（分钟桶 60s、小时桶 3600s），**忽略空闲窗口** → 反映运行时吞吐。
  - 例：`GET:/hello` 80 次集中在 1 个分钟桶 → `80/(1×60)=1.33`。
  - 对照：**区间平均**（含空闲）= `总请求 / 所选范围秒`（24h→86400），压测很短时会 <1（这就是它一度显示 0.48 的原因）。
- 服务端：`/inner/sw/metrics/query?...&aggregate=true` 每端点一行，`qps` 已按活跃桶算；存储层 `H2TraceSegmentStorage.activeBucketCounts()`（`COUNT(DISTINCT time_bucket) WHERE request_count>0`）提供活跃桶数。
- 前端：`metrics.html` 的 `aggSeries()` 用 `桶跨度 = requestCount / qps` 反推（兼容聚合行与逐桶行），避免把速率相加导致的量纲错误。
- ⚠️ demo 控制器 `StatisticController.metricsQuery` **必须转发 `aggregate` 参数**（否则服务端返回逐桶行、QPS 口径失真）。已修。

### 2.5 metrics 慢判定复用 `slow_rules`（A 方案）
- 指标慢判定用**只读解析器** `SlowRuleThresholdResolver`（包装 `RulesEngine.matchSlow`，**不** `recordRuleHit`、**不**并入 `TraceEvaluator`、不改告警行为）。
- 效果：命中规则的端点用其差异化阈值。例：配 `slow_rules=operation:GET:/api/trace-alert-demo/slow=8000` 时，该端点 4000ms 请求**不计慢**（默认阈值 3000 会误判）。
- 一致性：告警、H2 明细慢查询、metrics 三处对"慢"的阈值都源自 `slow_rules` + `default_slow_threshold_ms`。

---

## 3. 配置项（`plugin.logfilereporter.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `h2.enabled` | true | H2 持久层开关（false 时零开销） |
| `h2.shadow_max_rows` | 100000 | 明细行上限（堆 ≈ 80MB） |
| `h2.payload_capped_size_mb` | 128 | 环形载荷文件固定大小（MB，硬封顶） |
| `h2.compare_debug` | false | 开影子对账（比对前 `awaitIdle`） |
| `h2.console_enabled` / `h2.console_port` | false / 8092 | H2 Web Console（仅测试） |
| `metrics.enabled` | true | Trace 指标聚合开关 |
| `max_log_size` | 1000 | 内存热层 traceId 上限（维持不变） |
| `alert.slow_rules` | "" | 慢规则（metrics 也复用） |
| `alert.default_slow_threshold_ms` | 3000 | 默认慢阈值 |

---

## 4. 构建 / 安装 / 重启（Windows + pwsh）

前置：插件构建需 **JDK17**（产物字节码基线 8）；demo 运行 JDK8 或 17。agent 默认 `D:\apps\apache-skywalking-java-agent-9.4.0`，JDK8 `D:\apps\java\jdk1.8.0_92-64`。

```powershell
# 1) 构建插件 jar（在 agent 目录；reactor 在 agent/pom.xml）
cd agent
$env:JAVA_HOME='D:\apps\java\jdk-17.0.8'; $env:PATH="D:\apps\java\jdk-17.0.8\bin;$env:PATH"
mvn -o clean package -Dmaven.test.skip=true -pl logfile-reporter-plugin -am

# 2) 安装进 agent plugins（注意：若实例在运行，jar 被占用，需先停）
Copy-Item "logfile-reporter-plugin\target\logfile-reporter-plugin-2.0.0.jar" `
          "D:\apps\apache-skywalking-java-agent-9.4.0\plugins\logfile-reporter-plugin-2.0.0.jar" -Force

# 3) 构建 demo（改了后端/页面才需要）
cd demo-app
mvn -o clean package -DskipTests

# 4) 重启观察（复用已构建产物）
cd ../..
pwsh ./agent/demo-app/scripts/run-with-agent.ps1 -SkipPluginBuild -SkipAppBuild
```

一键验证（会构建+安装+启动+造数+断言）：`pwsh ./agent/demo-app/scripts/validate-h2.ps1 -Port 9605`。

---

## 5. 验证

```powershell
# 单元测试（插件）
cd agent; mvn -o -pl logfile-reporter-plugin test        # 期望全绿（当前 162）

# 端到端（H2/指标专项；端口别用 9600）
pwsh ./agent/demo-app/scripts/validate-h2.ps1 -Port 9605
```

`validate-h2.ps1` 已断言：H2 初始化、`h2Size>0`、`h2ErrorCount=0`、环形文件定长 128MB、按 traceId 查询、**双源一致**、**慢查询降序命中**、指标全局行/口径自洽、**QPS=requestCount/桶跨度**、**差异化慢阈值**（4000ms < 8000ms 规则 → 不计慢）。

---

## 6. 常见坑与排障

1. **`mvn clean`/`repackage` 被运行中的实例锁住**：实例会占用 `demo-app/target/run-with-agent-out.log` 与 `demo-app-1.0.0.jar`。
   - 症状：`Failed to delete ...run-with-agent-out.log`；或 `spring-boot repackage ... Unable to rename 'demo-app-1.0.0.jar'`。
   - ⚠️ **repackage 失败会把 jar 留成"瘦 jar"**（缺 BOOT-INF，重启即失败）。**先停实例再构建**；或把源码复制到临时目录构建、再拷回 fat jar。
   - 临时目录法：复制 `agent/demo-app`（排除 `target/`）到临时目录 → `mvn clean package -DskipTests` → `[System.IO.File]::Copy($fat,$repoJar,$true)` 拷回。
2. **9600 是用户实例**：验证用 9605/9606；换端口用 `-Port` 或 `-Dserver.port`。
3. **控制台 `jquery.min.js: <path> attribute d: Expected number, "MNaN ..."`**：趋势 sparkline 在**只有 1 个数据点**时 `i/(len-1)=0/0` 导致（**历史遗留**，不影响功能；可选修 `sparkLine` 加 `len<2` 保护）。
4. **慢查询/指标的 endpoint 形态**：是入口 span 的 `operationName`，形如 `GET:/api/xxx`（**不含查询串**）；用 `trace-recent` 里的 `endpoint` 值最稳妥。
5. **QPS 看起来变大/变小**：先确认口径（活跃平均 vs 区间平均）与所选范围；活跃平均依赖"有数据桶数"，空桶不计。
6. **构建工具链**：插件必须 JDK17 构建（`release 8`）；demo 构建 JDK17 亦可。`mvn -o` 为离线，首次可能需联网拉依赖。

---

## 7. 关键文件与提交

**关键文件**
- 存储：`.../reporter/logfile/storage/H2SqlStatements.java`、`H2TraceSegmentStorage.java`（`querySlowTraces`、`activeBucketCounts`）
- 客户端：`.../reporter/logfile/LogFileTraceSegmentServiceClient.java`（`getTraceViewFromMemory`、`getSlowTraces`、`aggregateMetrics` QPS）
- 指标：`.../reporter/logfile/metrics/{MetricsRow,TraceMetricsQuery,TraceMetricsAggregator}.java`
- 慢 A：`.../metrics/SlowThresholdResolver.java`、`.../alert/SlowRuleThresholdResolver.java`
- 门面/拦截器：`.../plugin/logfilereporter/{TraceParityStatusExposeInterceptor.java,define/TraceParityUtilsInstrumentation.java}`、`agent/demo-app/.../toolkit/SWTraceParityUtils.java`
- demo：`.../controller/StatisticController.java`、`src/main/resources/static/dashboards/{metrics,trace-query,trace-slow}.html`
- 脚本：`agent/demo-app/scripts/validate-h2.ps1`

**提交（截至本手册）**
- `25e38fb` feat：全量 trace 入 H2 + 双源读口/慢查询页 + QPS & slow_rules 复用
- `4d4c01e` fix：转发 `aggregate` + 前端按桶跨度算 QPS
- 后续（活跃平均 / 详情面板 QPS / 逐段 diff）：见最新提交。
