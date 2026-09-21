# h2-trace-storage Phase 1：影子 accept + H2 内存模式 + 新旧一致性比对

Status: done

## Problem Statement

插件的链路数据当前只活在进程内存（有界 FIFO 的 trace 内存热层）：应用重启 / OOM / 容器销毁即全部丢失，故障现场无法复盘，也无法做历史回溯与审计。要把它升级为"可跨重启、可查询、可审计"的本地历史数据，需要引入嵌入式 H2。

直接切换风险高：新存储的 segment 合并 / 字段转换口径若与现有内存路径不一致（少 logs、字段缺失、排序不同），排查时会看到"数据不对"却不自知。因此在切换前，需要一条**影子路径**：既有流程完全不动，新增一条写入 H2 的并行路径，用同一份真实流量持续对账，确认两者数据一致后，才进入收窄与切换。

## Solution

插件在 **H2 内存模式**（`jdbc:h2:mem`，不落盘）下多写一份链路数据，且**不改变任何对外输出**：

- `consume` 里只新增一次 `accept(data)` 调用；既有 `segmentToLog` / `mergeLogIntoStatMap` / `KeyedLocalStore` / 告警 / 暴露机制零改动；
- 新路径按"一条 segment 一行、`data_binary` 存该段 JSON"落 H2，查询时按 `trace_id` 取行、按 `start_time` 排序组装；
- 可开启 debug 比对：以现有内存 store 的当前 key 集为基准，逐 traceId 与 H2 数据对账（logs 条数 / span 数 / 关键字段），差异打日志并**落 H2 对账审计表**；
- 提供宿主工具类范式的调试入口（新桩类，工作名 `SWTraceParityUtils`），使用侧界面可直接展示对账结果，快速交叉验证；
- 可选开启 **H2 调试控制台**（浏览器页面、支持测试环境远程访问、默认关闭），供人工 SQL 查看、粗略对比；
- H2 影子写入带行数水位上限（默认 2000），长跑 / 压测下内存有界，可安全用于生产观察。

比对通过后，后续阶段再做"只收 error/slow"（Phase 2）、"切 file 模式 + TTL"（Phase 3）、"读门面（内存优先、miss 再 H2）+ Query"（Phase 4）、"trace-based metrics"（Phase 5）。

## User Stories

1. 作为插件作者，我想在 `consume` 里只新增一次 `accept(data)` 调用，以便既有合并 / 告警 / 暴露逻辑零改动、零行为变化。
2. 作为插件作者，我想影子路径直接写 H2 内存模式，以便在无磁盘副作用的情况下跑通 schema / SQL / 写入 / 读取全链路。
3. 作为插件作者，我想 H2 表按"一条 segment 一行、`data_binary` 存该段 JSON"落库，以便与 OAP 的 segment 行语义对齐、后续可按 traceId 查询组装。
4. 作为插件作者，我想查询组装按 `trace_id` 取行 + `start_time` 排序即可（插件未保留 refs），以便不引入 OAP 的 refs 父子算法。
5. 作为插件作者，我想开启 debug 比对后以现有内存 store 的当前 key 集为基准逐 traceId 对账，以便尽早发现新路径口径差异。
6. 作为插件作者，我想比对差异只走日志且限速、且可开关，以便不对正常流程产生可感知开销。
7. 作为插件作者，我想 H2 初始化 / 建表 / insert 异常全部被捕获并计数，绝不向上抛，以便"监控只能是助力，不是阻碍"。
8. 作为插件作者，我想影子写入有行数水位上限（默认 2000），以便长跑 / 压测下内存有界。
9. 作为插件作者，我想 `h2.enabled`（默认 true）与 `h2.compare_debug`（默认 false）可用启动参数控制，以便验证期随时开关、压测对比。
10. 作为验证者，我想在验证回路里跑出"旧断言全绿 + 新旧比对零差异（或差异有明确解释）"，以便确认影子路径可信。
11. 作为验证者，我想可选地启动 H2 调试控制台（浏览器页面、支持测试环境远程访问、默认关闭），以便部署到测试环境后不写代码就能 SQL 查看影子数据。
12. 作为验证者，我想在控制台里执行现成 SQL（总行数、按 traceId 聚合、最近 N 条、指定 trace 明细），以便和 `statisticStatus` 的旧数据做粗略人工对比。
13. 作为使用者，我想 Phase 1 不改任何现有对外输出（`statisticStatus` / 开关 / 告警行为 / 宿主工具类契约），以便升级插件无感。
14. 作为使用者，我想影子路径不产生磁盘文件，以便试运行零成本、可随时回退。
15. 作为维护者，我想新存储接口只有 `accept` / `snapshot` / `size` 三个方法，以便接口最小、后续读门面再扩。
16. 作为维护者，我想 H2 依赖被 shade 重定位，以便不与业务自带 H2 / 其它组件冲突。
17. 作为维护者，我想测试缝收敛在"存储接口（单元）+ 既有验证回路（端到端）"两个，以便测试不侵入实现细节。
18. 作为后续阶段作者，我想 Phase 1 的同步写与水位清理留下明确标记（file 阶段必须换异步写线程），以便不在生产上长期使用临时方案。
19. 作为验证者，我想每轮对账结果落 H2 审计表（差异明细 + 期望 / 实际样例），以便事后审计而不是翻日志。
20. 作为使用者，我想通过宿主工具类范式的调试入口（类似 `SWLogfileReporterUtils` 的桩类）拿到对账快照，以便在使用侧界面直接展示对比验证结果。
21. 作为维护者，我想审计表带行数水位上限并明确标注"临时"，以便 Phase 2 收窄后重新评估去留。

## Implementation Decisions

- **新增内部接口 `TraceSegmentStorage`**（插件包内，不越 Business 边界）：`void accept(List<TraceSegment>)`、`Map<String, Map<String, Object>> snapshot()`、`int size()`；`accept` 返回 `void`（告警下沉留后期）。
- **新增 `H2TraceSegmentStorage`（仅内存模式）**：JDBC URL `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`；启动时 `CREATE TABLE IF NOT EXISTS trace_segment (...)`，字段 / 索引按统一方案 §5.2（`trace_level` 本期可空）。
- **接线**：`LogFileTraceSegmentServiceClient.consume` 在既有逻辑之后新增一次 `accept(data)`；旧路径代码零改动；`accept` 内部异常全部捕获（计数 + 限速日志）。
- **写入模型（Phase 1 临时）**：`accept` 内同步批量 `addBatch/executeBatch`（单连接、单消费线程）；**file 模式阶段必须切换为独立写线程**（方案 §6.1 B），此为本阶段已知临时简化。
- **行数水位上限**：新增 `h2.shadow_max_rows`（默认 2000，建议 ≥ `max_log_size`）；按 `id` 水位节流执行 `DELETE FROM trace_segment WHERE id <= currentMaxId - cap`（每 N 批或计数阈值触发一次）。
- **比对器（纯函数）**：以旧 store 当前 key 集为基准，逐 traceId 从新 `snapshot()` 取回，比较 logs 条数 / span 数 / 关键字段；返回差异报告；仅 `compare_debug=true` 时由客户端限速打日志（差异计数 + 样例）。
- **对账审计表（临时）**：`trace_parity_audit`，每条差异一行（check_time / trace_id / diff_type / 期望 / 实际 / detail），随对账轮次写入，行数水位上限固定 1000（不新增配置，Phase 2 收窄后重估去留）。
- **宿主工具类调试入口**：新增桩类（工作名 `SWTraceParityUtils`，`statisticParity()` 返回 JDK 原生 Map），沿用既有拦截器范式跨 ClassLoader 取数；demo-app 增加对账展示面（读口 + 页面区块）。
- **配置**（`plugin.logfilereporter.h2.*`）：`enabled`（默认 true）、`compare_debug`（默认 false）、`shadow_max_rows`（默认 2000）、`console_enabled`（默认 false）、`console_port`（默认 8092）。
- **H2 调试控制台（可选，默认关闭）**：启用时在应用 JVM 内启动 H2 `org.h2.tools.Server` Web Console，**允许远程访问**（`-webAllowOthers`），测试环境部署后可直接浏览器对比；属 H2 自带调试工具，不是方案"不做内置 HTTP Server"约束（非业务面、默认关闭、用完即关，不建议生产常开）。
- **依赖与打包**：新增 `com.h2database:h2:2.1.212`（与 OAP 9.4 / 实验环境一致），maven-shade 重定位 `org.h2` 前缀；字节码基线 `release 8` 不变。
- **测试基础设施**：surefire include 从 `**/alert/*Test.java` 扩为 `**/*Test.java`（既有 `KeyedLocalStoreTest` / 队列测试同时恢复运行）。
- **不改**：`KeyedLocalStore`、告警、暴露机制、其余 4 个 sender、宿主工具类契约。

## Testing Decisions

- 好测试 = 只测外部行为：存储测试只经 `accept → snapshot/size` 断言，不碰 SQL / 内部字段。
- **单元（存储接口）**：`H2TraceSegmentStorage` —— traceId 合并、logs / span 计数、按 `start_time` 排序、`data_binary` JSON 往返、`size` 与水位上限行为；比对器为纯函数，直接喂两份 Map 断言差异报告。
- **端到端（既有验证回路）**：`validate.ps1` 现有断言全绿 = 对外零行为变化；影子比对以 debug 开关观察，不作为自动断言。
- 先例：`KeyedLocalStoreTest`（同包边界存储语义）、`TraceEvaluatorTest`（夹具构造）；ADR-01 验证回路。
- 备注：`TraceSegment` 夹具若构造困难，先抽包内可见的 `segment → Log` 转换点供测试使用，**不为测试改旧路径行为**。

## Out of Scope

- 收窄（只收 error/slow）、`trace_level` 打标与两档 TTL（Phase 2/3）。
- H2 file 模式、异步写线程、磁盘风险验证（Phase 3）。
- Query / 读门面、trace-based metrics（Phase 4/5）。
- 泛化的对外 H2 任意查询接口（Phase 4 候选；对账审计的定点读取 `statisticParity()` 在本期实现，见 Implementation Decisions）。
- 告警下沉设计（后期）。
- 变更对外输出、宿主工具类契约、`KeyedLocalStore` 与其它数据流。

## Further Notes

- **H2 页面端访问（手动对比用）**：
  1. 启动参数加 `-Dskywalking.plugin.logfilereporter.h2.console_enabled=true`（可选 `-Dskywalking.plugin.logfilereporter.h2.console_port=8092`）；
  2. 浏览器打开 `http://<部署机IP>:8092`（H2 Console 由应用 JVM 内启动；**支持远程访问**，测试环境部署后可直接打开。⚠️ 控制台可对库执行任意 SQL，仅测试环境开启、用完即关）；
  3. 连接信息：JDBC URL `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`，User `sa`，Password 空；
  4. 常用 SQL：
     - 总行数：`SELECT COUNT(*) FROM trace_segment;`
     - 按 trace 聚合：`SELECT trace_id, COUNT(*) AS logs_cnt, MIN(start_time) AS st, MAX(start_time) AS et FROM trace_segment GROUP BY trace_id ORDER BY et DESC LIMIT 20;`
     - 最近 N 条：`SELECT id, trace_id, segment_id, service, endpoint, start_time, latency, is_error FROM trace_segment ORDER BY id DESC LIMIT 20;`
     - 指定 trace：`SELECT * FROM trace_segment WHERE trace_id = '<从 statisticStatus 拿到的 traceId>';`
  5. 人工对比步骤：从 `statisticStatus()`（旧内存视图）取一个近期 traceId → 控制台查 H2 同 trace 的 segment 行数与 JSON 内容 → 与旧视图 `data[traceId].logs` 对齐检查（条数 / span 数 / 关键字段）。
- 审计表：控制台可直接查询 `trace_parity_audit`（对账差异明细，临时表），与 `statisticParity()` 读口同源；Phase 2 收窄后重估去留。
- **DBeaver 等外部客户端**：Phase 1（mem）通过应用内 Web Console 访问；TCP 远程连接留待 file 模式或后续需要时再加开关。
- **风险备案**：H2 经 shade 重定位后，Web Console 静态资源路径需实测；若不可用，退路为"在 JVM 内启动 H2 TCP Server（`-tcpAllowOthers`）+ 外部 H2 Console / DBeaver 连接 `jdbc:h2:tcp://<部署机IP>:<port>/mem:sw_trace_segment`"（同为默认关闭的调试开关）。
- **后续方向（本期不做）**：沿用宿主工具类范式（`SWLogfileReporterUtils` 思路）对外提供 H2 数据查询接口（按 traceId / 时间范围查影子数据），让客户端无需 SQL 控制台即可快速交叉验证——与 Phase 4（读门面 + Query）合并评估。
- **术语**：`trace 内存热层`、`trace 持久层`、`链路分级`、`Trace 指标` 已固化于 `CONTEXT.md`。
- **ADR**：按仓库约定"实现落地后再写"，计划在 Phase 1 / 2 落定后补 ADR-03（H2 定位：只存 metrics + slow/error；normal 仅内存热层）。
- **后续里程碑**：Phase 2+3（收窄 + file/TTL）、Phase 4（Query + 读门面）、Phase 5（metrics）各自单独开 spec；写时机与届时待决清单见 `docs/todos/h2化-统一方案.md` §4「Spec 切分策略」。
- **已知临时简化**：同步写 + 无异步队列；Phase 3 切 file 时必须替换为独立写线程。
- 背景设计：`docs/todos/h2化-统一方案.md` §4；外部参考：`docs/reference/glowroot-trace-storage.md`、`docs/reference/skywalking-oap-trace-storage.md`。
