# h2-payload-capped-file：明细载荷进环形封顶文件（H2 只留 header/索引 + 指针）

Status: done

## Problem Statement

Phase 1（`.scratch/h2-trace-storage/spec.md`，已 done）把整段 segment JSON 存进 H2 `trace_segment.data_binary`（`MEDIUMTEXT`）——对齐 OAP，实现最简，但**重新引入了 Glowroot 用 `CappedDatabase` 要治的病**：

- H2 MVStore 是日志结构：**行被 DELETE 后空间不还给 OS**（`.mv.db` 涨了不缩）；在线自动压缩只"复用空间、稳定在高水位"，**要真正缩容只能 `SHUTDOWN COMPACT`（停写窗口）**。
- `data_binary` 是可变长的大字段：单段 JSON 含全部 spans / tags / logs，体积不可控，**没有硬上限**。

收窄后（Phase 2）只有 error/slow 进 H2，量级远小于 Glowroot，未必复现 31G；但作者已决定**现在就把 payload 移出 H2**，换取**磁盘硬上限可预测**、H2 文件只留小 header。拆法照 `docs/reference/glowroot-capped-database.md` §5 的**"最小形态"**：H2 留 header/索引列 + 指针，payload 写**单个环形封顶文件**（去掉 resize / future / 统计）。

## Solution

在已提交的 Phase 1 之上，给**明细载荷**换存储形态，其余不动：

- **H2 行只留 header/索引**：`trace_segment` **删除 `data_binary` 列**，**新增 `payload_id BIGINT`**（可空）。
- **新增 `CappedFileStorage`（最小环形文件）**：单文件、固定大小（默认 **128MB**）、块 `⟨len:8B⟩⟨gzip(payload)⟩`、逻辑索引；写满**覆盖最旧**；`id < currIndex - sizeBytes` 即"过期"读回 `null`。**去掉** resize / `isInTheFuture` / 统计。
- **载荷 GZIP**：每段 payload 独立 `GZIP(JSON)`，块自包含，读侧 `GZIPInputStream` 解。
- **异步写**：`accept` 只入**有界队列**（默认 4096，满则丢弃 + 计数），**独立写线程**做 `transform → SegmentLogConverter → JSON → gzip → ring.writeMessage → H2 INSERT(payload_id)`（每段一行）；`close()` 排空 + flush + fsync。
- **H2 仍 `mem`** 模式（Phase 3 再切 file + 两档 TTL）。
- **storage 独立子包**：`...reporter.logfile.storage`。
- **使用侧查询门面**：宿主工具类范式新增"按 traceId 从 H2/环形取回整条链路"的方法，demo-app 提供入口与页面——供**人工查看整条链路**、验证 payload 拆分后功能齐全，并为日后 H2 完全替代内存读路径做准备。
- **双轨保留**：H2 行按时间（后续 TTL）/ 环形文件按空间（现在就有硬上限）；两套互不依赖。

> 目标：**磁盘上限 = H2(小 header) + 环形(硬封顶 128MB)**，可预测；旧路径与一切对外输出零变化。

## User Stories

1. 作为插件作者，我想 H2 只存 header/索引 + `payload_id` 指针，以便 `.mv.db` 不再被大 CLOB 撑大。
2. 作为插件作者，我想 payload 写进固定大小的环形文件，以便**磁盘有硬上限**、可预测。
3. 作为维护者，我想环形文件只有"写块 / 按 id 读块 / 覆盖判定"三件事（无 resize、无 future、无统计），以便实现与维护最简。
4. 作为维护者，我想载荷 GZIP 后落块，以便 JSON 体积可控、读写自包含。
5. 作为插件作者，我想 `accept` 只入队、由独立写线程落盘落库，以便**不阻塞 DataCarrier 消费线程**（承接 Phase 2+3 的异步写要求）。
6. 作为插件作者，我想写队列有界、满则丢弃并计数，以便高并发下内存有界、行为可观测。
7. 作为维护者，我想环形文件写满后自动覆盖最旧块，以便无需定时清理也能封顶。
8. 作为使用者，我想 payload 被覆盖后读回表现为"过期"（header 仍在），以便与 Glowroot 一致地降级展示。
9. 作为维护者，我想 storage 相关类收敛进 `...reporter.logfile.storage` 子包，以便存储边界清晰。
10. 作为验证者，我想确定环形文件大小恒定 ≤ 配置上限、写满后旧 id 读不回，以便证明硬上限成立。
11. 作为验证者，我想 `validate.ps1` 与 `validate-h2.ps1` 全绿，以便证明对外零行为变化。
12. 作为后续阶段作者，我想本次留下"H2 mem + 环形"的清晰形态，以便 Phase 3 只切 H2 file + 加 TTL，不动环形。
13. 作为使用者，我想通过宿主工具类按 traceId 从 H2 取回整条链路（与 `data[traceId].logs` 同契约），以便**人工查看一条完整链路**、确认 payload 拆分后功能齐全。
14. 作为使用者，我想 demo-app 提供查询入口与页面（输入 traceId → 展示整条链路），以便不写代码即可人工验证。
15. 作为验证者，我想查询结果能标明"payload 已过期"（header 在读得到、payload 不在），以便区分"没有这条"与"过期了"。
16. 作为后续阶段作者，我想这条查询门面成为 Phase 4（内存优先、miss 再 H2）的种子，以便日后用 H2 完全替代内存读路径。

## Implementation Decisions

### 环形文件 `CappedFileStorage`（最小形态 + GZIP）

- 文件：`<base>/trace-payload.capped.db`（配置 `h2.payload_capped_file`），固定大小 `h2.payload_capped_size_mb`（默认 **128**）。
- 文件头 **16B**：`currIndex(long 8B)` + `sizeBytes(long 8B)`（Glowroot 为 20B 含 `lastResizeBaseIndex`，本形态**去掉 resize**）。
- 块：`⟨len:8B⟩⟨gzip(payload)⟩`；物理位置 = `currIndex % sizeBytes`；**跨文件尾分段写/读**。
- `writeMessage(byte[]) -> long id`：`id` = 块起始逻辑索引；先占 8B 长度位 → 写 gzip 字节 → 回填 `len` → `currIndex` 前移（跨轮不回绕）。
- `readMessage(long id) -> byte[]`：`id < smallestNonOverwrittenId`（= `currIndex - sizeBytes`）→ `null`（过期）；否则按取模位置读。
- **单块不得超过整个文件大小**（超出 → 记错、丢弃该 payload，不崩）。
- **读/写共用一把锁**（Glowroot 即单锁）。
- **fsync**：`close()` 必做；运行期**每 N 次写或每 T 毫秒** `FileChannel.force(false)`（N/T 用常量，先不配置）。
- **类头注释（必加）**：`CappedFileStorage` 类注释须指向本类的设计来源——Glowroot `CappedDatabase.java` 的 permalink，并注明"本类为其**最小形态移植**（去掉 resize / isInTheFuture / 统计）"：
  <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java>

### H2 schema

- `CREATE TABLE trace_segment`：**删 `data_binary`**，**加 `payload_id BIGINT`**；`INSERT`/`SELECT` 同步改。
- `mem` 模式每次启动重建表，删列无历史负担。
- `snapshot()` 改为：读 header 行 → `payload_id` → `CappedFileStorage.readMessage` → 解 gzip → JSON → 组装；`null` 视为过期（跳过 payload，header 语义保留）。

### 异步写线程

```
accept(List<TraceSegment>)   ← DataCarrier 消费线程：仅入队，零 I/O
      │  ArrayBlockingQueue（容量常量，默认 4096）；满 → 丢弃 + writeQueueDropped++
      ▼
【写线程 H2Shadow-Writer】(唯一写者)
   take/drain 批 → 每段: transform → SegmentLogConverter.toLog → GSON → gzip → ring.writeMessage
                → INSERT(..., payload_id)（每段一行）→ enforceRowCap
close(): 置停止 → 排空(超时) → ring flush + fsync → 关 H2 / console
snapshot(): 调用线程读 H2 + ring（锁保护）
```

- 单写者 ⇒ H2 与环形无并发写；环形锁只服务"读 vs 写"。
- 丢弃计数进 `getParityStatus()`：新增 `writeQueueDropped`。

### 包结构（storage 独立子包）

```
.../reporter/logfile/
├── LogFileTraceSegmentServiceClient.java   (改：import storage；异步 accept 调用不变；对账)
├── SegmentLogConverter.java                (改：package-private → public)
├── TraceParityComparator.java              (留；已 public)
├── Log / LogCollection / KeyedLocalStore / LogFileReporterPluginConfig
├── storage/                                ★ 新子包
│   ├── TraceSegmentStorage.java            (移动)
│   ├── H2TraceSegmentStorage.java          (移动 + 异步写 + 环形接入)
│   └── CappedFileStorage.java              (新增)
└── alert/...                               (不动)

src/test/.../reporter/logfile/storage/
├── H2TraceSegmentStorageTest.java          (移动)
└── CappedFileStorageTest.java              (新增)
```

- `SegmentLogConverter` → **public**（`.logfile` 与 `.storage` 均用）。
- `H2TraceSegmentStorage` 被 `.logfile` 的 client 调用的 4 个包私有方法 → **public**：`auditRowCount()` / `auditWaterLevel()` / `recentAuditRows(int)` / `insertAuditRows(...)`；`storeLog` / `clear` 保持包私有（测试同包迁入）。
- `storage` 子包对 `.logfile.TraceParityComparator` 为单向依赖（其已 public，可接受）。

### 配置（`plugin.logfilereporter.h2.*` 新增）

- `payload_capped_enabled`（默认 **true**）
- `payload_capped_file`（默认工作目录下 `trace-payload.capped.db`）
- `payload_capped_size_mb`（默认 **128**）
- 队列容量 / fsync 阈值先用代码常量（本期不新增配置）。

### 使用侧查询门面（H2 → 链路数据）

- **宿主工具类**：沿用现有 `SWTraceParityUtils` 与拦截器范式（`TraceParityStatusExposeInterceptor` + `TraceParityUtilsInstrumentation`）新增静态方法：
  - `Map<String, Object> queryTrace(String traceId)` —— 返回与旧契约同构的 `{ "logs": [ ... ] }`；无此 trace 返回空/null；payload 已过期时不报错，返回 header + 过期标记（如 `payloadExpired=true`）。
  - `List<Map<String, Object>> recentTraces(int limit)` —— 最近 N 条 header（`trace_id` / `trace_segment_id` / `service` / `endpoint` / `start_time` / `latency` / `is_error` / payload 是否过期），供挑选 traceId。
  - 拦截器需把方法入参（`traceId` / `limit`）透传给 Agent 内部实现；返回**只含 JDK 原生类型**，不越 Business 边界。
- **存储侧**：`H2TraceSegmentStorage` 新增 `queryTrace(traceId)`（`SELECT ... WHERE trace_id = ? ORDER BY start_time`，逐行按 `payload_id` 回读环形、解 gzip）与 `recentTraces(limit)`；异常就地捕获、计数，绝不外抛。
- **demo-app**：新增读口 `/inner/sw/trace-query`（`?traceId=...` 或 `?limit=N`）与页面：列出最近 traces + 输入 traceId 展示整条链路；不改变既有对外契约与既有端点。
- **定位**：这是 Phase 4 读门面的**最小种子**——本期只做"按 traceId 取全链路 + 最近列表"，不做时间范围 / 多维过滤，也不切换"内存优先、miss 再 H2"的读路径（当前内存读路径原样保留）。

### 不改动

`TraceSegmentStorage` 接口（`accept` / `snapshot` / `size`）、`SegmentLogConverter` 转换逻辑、`TraceParityComparator` 逻辑、告警、暴露机制、其余 4 个 sender、既有宿主工具类方法契约（查询门面**只新增**方法，不改既有方法签名与返回）。

## Testing Decisions

- 好测试 = 只测外部行为；存储测试经 `accept → snapshot/size` 断言，不碰 SQL / 内部字段；环形文件测试只经 `writeMessage / readMessage` + 文件大小断言。
- **单元 `CappedFileStorageTest`（新增）**：gzip 往返、跨文件尾块读写、写满覆盖 → 旧 id 读回 `null`、**单块 > 文件大小**的拒绝/丢弃、读写并发、写满后**文件大小恒定**。
- **单元 `H2TraceSegmentStorageTest`（迁移 + 改）**：payload 经环形文件往返后 `snapshot` 与旧契约一致；`payload_id` 指针；`size` / 水位上限行为保留（测试随类迁入 `storage` 包）。
- **端到端**：扩 `validate-h2.ps1`——增加"环形文件存在且大小 ≤ 上限""对账零差异""`h2ErrorCount=0`、`writeQueueDropped` 可读"；`validate.ps1` 现有断言全绿 = 对外零行为变化。
- **查询门面**：单元测 `queryTrace` 三态（命中 / 不存在 / payload 过期）与 `recentTraces(limit)`；端到端在 `validate-h2.ps1` 加"取一个已知 traceId → `/inner/sw/trace-query` 返回整条链路、logs 条数与旧视图一致"。
- 先例：`H2TraceSegmentStorageTest`、`TraceParityComparatorTest`、ADR-01 验证回路。

## Out of Scope

- 收窄（只收 error/slow）与 `trace_level` 打标（Phase 2）。
- H2 切 `file` 模式与两档 TTL（Phase 3）。
- Query / 读门面（Phase 4）中**除**"按 traceId 取全链路 + 最近列表"以外的部分：时间范围 / service·endpoint / span 内部字段过滤、以及"内存优先、miss 再 H2"读路径的切换。
- trace-based metrics / 分位数（Phase 5）。
- 告警下沉设计。
- 环形文件的 resize、`isInTheFuture`、压缩率统计、LZF（用 GZIP）。
- 变更既有对外输出、既有宿主工具类方法签名、`KeyedLocalStore` 与其它数据流。

## Further Notes

- **背景设计**：`docs/todos/h2化-统一方案.md` §4/§5/§6；拆法一手参考：`docs/reference/glowroot-capped-database.md`（尤其 §5"最小形态"与"照抄时的坑"）。
- **照抄的坑需自担**（reference §5）：跨尾块读写、单块 ≤ 文件大小、并发单锁、**读中途被覆盖 → 按过期**、`kill -9` 下 fsync 策略、文件损坏 → 读作 miss 而非崩。
- **mem 模式的已知现象**：H2 是内存库、进程结束即空，而环形文件落盘；重启后可能出现"H2 无行、环形有孤儿数据"。本期只求跑通，属预期；Phase 3 切 file 后消失。
- **payload 实测事实仍缺**：Phase 1 验收（`.scratch/h2-trace-storage/issues/07-phase1-e2e-acceptance.md`）明确"本轮无大 payload 压测数据"，故 128MB 为保守初值，Phase 3 按实跑调整。
- **异步写的定位**：本步引入独立写线程，正为偿还 Phase 1 的"同步写"临时简化（票 07 已点名 file 阶段必须做）。
- **ADR**：按仓库约定"实现落地后再写"，计划在此 spec 与 Phase 2 落定后补 ADR-03（H2 定位：只存 metrics + slow/error；normal 仅内存热层）。
- **使用侧查询门面 = 内存替代的起点**：`queryTrace` 与旧 `data[traceId].logs` 同契约，因此 demo-app 页面可**同一套渲染**展示"内存视图 vs H2 视图"，人工比对；日后 Phase 4 只需把读路径改成"内存优先、miss 再走这条查询"。
- **术语**：沿用 `CONTEXT.md`（本地内存报告、有界数据存储、追踪快照 等）。

## Acceptance

- **单元**：`mvn -pl logfile-reporter-plugin test` = 102/102 全绿（含环形文件 7 项）。
- **端到端**：`validate-h2.ps1` exit 0（对账 `totalDiffs=0`、环形文件定长 128MB、查询门面返回整条链路）；`validate.ps1` exit 0（对外零行为变化）。
- **修复**：异步写 parity 竞态 → 比对前 `awaitIdle`；首块 `payload_id=0` 误判 → 改 `>=0`。
- ADR-03 已落地；统一方案 §5.2 已回写。详情见 `.scratch/h2-payload-capped-file/issues/13-validation-and-docs.md` 的 Comments。
