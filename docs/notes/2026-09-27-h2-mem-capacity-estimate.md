# 2026-09-27：H2 内存模式容量估算——header 行 vs 环形 payload 文件

何时读：讨论「H2 内存模式够不够用 / mem 与 file 取舍 / `shadow_max_rows` 与环形文件大小取值」时。

> 状态：**估算**（非实测），量级 ±2–3×；**决策已于 2026-09-27 grilling 定案**（见 §5、§9 与 `ADR-04`）。本笔记作证据与推导，`S/R` 待实测校准。
> 口径：**H2 只存 header 行**（payload 在堆外环形文件），对齐 `ADR-03`。

---

## 0. 背景与目的

Phase 1 起 H2 走**内存模式**（`jdbc:h2:mem:...`），payload 经 GZIP 落 `CappedFileStorage`（默认 128 MiB，磁盘硬封顶）。问题是：**内存模式能承载多少条链路、堆占用多大、环形文件该设多大**——即「mem 模式是否是一个自洽的长期形态」。

本文用"每单位字节成本"把三件事串起来，给出可复核的估算，并给出**实测校准步骤**。

---

## 1. 符号与口径

| 符号 | 含义 | 现状默认 |
|---|---|---|
| **S** | 单段 payload gzip 后字节（含 8B 块头） | 未实测，估 200B–2KB |
| **R** | H2 header 每行堆成本 | 未实测，估 0.6–1 KB |
| **F** | 环形文件大小 | `PAYLOAD_CAPPED_SIZE_MB = 128`（`LogFileReporterPluginConfig:124`） |
| **N** | H2 行上限（段数） | `SHADOW_MAX_ROWS = 2000`（`LogFileReporterPluginConfig:109`） |
| **K** | 每 trace 段数 | demo ≈1；单体+RPC ≈1–3 |
| **T** | 旧 `KeyedLocalStore` 上限（traceId 数） | `MAX_LOG_SIZE = 1000`（`LogFileReporterPluginConfig:11`） |

要点：**payload 不占堆**（在 `CappedFileStorage`）；H2 mem 只存 header 行。H2 侧行 = **段**，旧 store 侧键 = **traceId**。

---

## 2. 每单位成本（估算）

| 对象 | 每单位 | 说明 |
|---|---|---|
| H2 header 行（mem） | **~0.6–1.0 KB/行** | 5 个字符串（trace_id/segment_id/service_instance/endpoint/service ≈400B）+ H2 `Value`/`Row`/页/索引开销 |
| payload 块（磁盘） | **S ≈ 200B–2KB/段** | gzip(JSON)；轻段 200–500B，含异常堆栈的 error 段 1–2KB |
| 旧 `KeyedLocalStore`（堆） | **~6 KB/trace** | 完整 Java 对象图（span map + 每 tag 一个小 HashMap），含堆栈可 10–30 KB |

> 旧 store 贵在**对象图膨胀**（每 span 一个 12 项 HashMap、每 tag 一个 2 项 HashMap），与 H2 header 的"扁平列"成本结构不同。

---

## 3. 旧基线对齐：`shadow_max_rows` 放宽到多少 = 旧 store 的堆？

口径 = 旧 `KeyedLocalStore`（1000 trace）对齐**堆内存**。

| | 每单位 | 默认上限 | 占用 |
|---|---|---|---|
| 旧 `KeyedLocalStore` | ~6 KB/trace | 1000 | **~6 MB**（轻量）～10 MB+（含错误栈） |
| H2 header-only | ~0.7 KB/行 | 2000 | ~1.5 MB |

**同堆 ⇒ H2 行上限 ≈ 6–10 MB ÷ 0.7 KB ≈ 8,000–12,000 行**（取整**万级**，约为当前 2000 的 4–6×）。

---

## 4. 「用满 128 MiB 环形文件」需要多少行 / 多少堆？

要让文件里**每条还活着的 payload 都能被 header 查到**，H2 必须容纳文件里的全部段：

```
需要行数 = 128 MiB ÷ S
占用堆   ≈ 需要行数 × R
```

| S | 需记录行数 | 堆 @0.7KB/行 | 堆 @1KB/行 |
|---|---|---|---|
| 200B | ~671,000 | ~460 MB | ~660 MB |
| 300B | ~447,000 | ~306 MB | ~437 MB |
| **500B（中值）** | **~268,000** | **~184 MB** | **~262 MB** |
| 1KB | ~134,000 | ~92 MB | ~131 MB |
| 2KB（含堆栈） | ~67,000 | ~46 MB | ~66 MB |

**结论：用满 128 MiB 的 header 代价 ~50 MB–0.66 GB 堆**（典型 ~200 MB），远超旧 store 的 ~6–10 MB → **不经济**。本质关系：

```
用满文件所需堆 ≈ F × (R / S)
```

- 段越轻（S 小），header 越贵：S=200B 时堆 ≈ payload 的 3.5×；
- 段越重（S 大），header 越便宜：S=2KB 时 ≈ payload 的 0.35×。

---

## 5. 定案：全量 trace 入 H2 mem（方案 (B)）

**决策（2026-09-27 grilling）**：H2 内存模式的 `trace_segment` 作为**全量 trace 持久层**（normal + error/slow 都写，**不收窄**）；payload 全量 GZIP 落环形文件（`ADR-03`）。`KeyedLocalStore` 降为**兼容保留 + 过渡期交叉核对参照**（主从反转：查询以 H2 为准）。file 模式延后到"需要跨进程重启持久化"时再做。

**参数定案**：

| 参数 | 取值 | 依据 |
|---|---|---|
| `N`（行上限） | **100,000** | 旧 store 1000 的 ~100×；堆 ~70MB（§3 口径） |
| `F`（环形文件） | **128 MB** | `F ≥ N×S` 确保容纳 10 万段；128MB 覆盖到 S≈1.28KB/段；定长文件多占磁盘、不占堆 |
| 表 | 单表 `trace_segment`（**删 `trace_level` 列**） | 不单独建 error/slow 表；错误查 `is_error`、慢查 `latency >= :阈值` |
| `persist_only_hits` | 默认 `false` | (B) 下不收窄，保留供回退/对比 |

**窗口代价（承认并接受）**：`N=10万`、堆 ~70MB 时，mem 窗口 @10 QPS ≈ 2.8h、@100 QPS ≈ 17min；**重启即失**（跨重启持久化留待 file）。

> 与 `ADR-03` 的既有语义一致：header 与 payload **年龄不同步**时，读到"过期/缺失"就地降级（`payloadExpired`），**无需新机制**。

---

## 6. 假设与偏差来源

- JVM：HotSpot 64 位、压缩指针；Java 8（demo 运行环境）。
- 代表 trace 形状：约 1 段、2 span、3 tag/span、无堆栈（轻量）。
- gzip 比例按经验 4–8×（JSON 键名重复高）。
- H2 mem 内部结构在**几十万行**档位可能非线性（页/B 树），故大档位偏差放大。
- 主要摆动项：**每 trace 段数 K**、**tag/span 数量**、**错误率（堆栈）**、**真实 S 与 R**。
  - 注：在「同堆对齐」口径下，K 对**比值**影响小（旧 store 与 H2 行数都随 K 线性上涨）。

---

## 7. 实测校准步骤（把估算钉死）

目标：拿到真实的 **S**、**R**、**K**。

1. **真实 S（单段 payload 字节）**
   - 取真实链路：`curl --noproxy * http://127.0.0.1:<port>/inner/sw/trace-query?traceId=<id>`（返回同 `data[traceId].logs` 契约的 JSON），或 `/inner/sw/trace-recent?limit=20` 选若干 `traceId` 后逐个取回。
   - 对返回的**每段** JSON 本地跑同款 GZIP（`GZIPOutputStream`），字节数即该段 payload；多段取均值 → **S**（加 8B 块头）。
   - 若希望直接读运行值：为 `CappedFileStorage.getCurrIndex()` 增加一个只读暴露（现 `/inner/sw/trace-parity` 只回 `h2Size`、`writeQueueDropped`，见 `LogFileTraceSegmentServiceClient:191-192`），`平均块 = ΔcurrIndex / Δ段数`。
2. **真实 R（H2 header 每行堆）**
   - 同一实例读 `/inner/sw/trace-parity` 得 `h2Size`；
   - `jmap -histo:live <pid>`（触发一次 Full GC）后取 H2 相关存活字节，除以 `h2Size`；或跑两个不同 `shadow_max_rows` 的实例，比较 GC 后存活堆差 ÷ 行数差。
3. **真实 K（每 trace 段数）**
   - 由 `/inner/sw/trace-query` 返回的 `logs.length` 得单 trace 段数；多点取样求均值。
4. 用实测 S/R/K 重算第 3–5 节各表，确定最终 `F` 与 `N`。

复核命令参考：

```powershell
curl.exe -s --noproxy "*" http://127.0.0.1:<port>/inner/sw/trace-parity
curl.exe -s --noproxy "*" http://127.0.0.1:<port>/inner/sw/trace-recent?limit=20
curl.exe -s --noproxy "*" http://127.0.0.1:<port>/inner/sw/trace-query?traceId=<id>
& "D:\apps\java\jdk1.8.0_92-64\bin\jmap.exe" -histo:live <pid>
```

> ⚠️ 运行实例端口 9600 为用户压测实例，**勿重启**；校准时用其它端口（9605/9606/9608）起实例。

---

## 7.1 首轮实测结果（2026-09-27）

环境：demo-app（JDK 8、agent 9.4.0）、H2 mem、`shadow_max_rows=200000`、环形 128MB、**无告警**；负载 mixed ~90 req/s（含 28% 错误）。

| 量 | 实测 | 依据 |
|---|---|---|
| **S** | **≈ 662 B/段** | `currIndex=17,488,865 B ÷ h2Size=26,419`（两次负载一致） |
| **K** | **≈ 1** | h2Size ≈ 请求数（demo 单段/请求） |
| **R** | **≈ 804 B/行（0.78 KB）** | 同实例两点差分：`Δheap 18.07 MB ÷ Δrows 23,587` |
| 写队列/错误 | `writeQueueDropped=0`、`h2ErrorCount=0` | `/inner/sw/trace-parity` |

**据实测重算**：
- `N=100,000` 行 → 堆 ≈ 100k × 804B ≈ **80 MB**（+ H2 引擎开销）。
- `F=128 MiB` → 容纳 `134.2e6 ÷ 662B ≈ 203,000` 段 **> N** → **F 有余量，选择成立**（normal 为主时 S 更低、更宽裕）。
- "用满 128 MiB 文件"的 header 堆 ≈ `134.2e6 × (804/662) ≈ 163 MB`（较 §4 估算 ~200MB 略低）。

> R 用同实例两点差分（其余缓存有界稳定），略含 metrics 行增长 → 属**偏上估计**。S 的偏高来自 28% 错误段（真实 normal 为主会更低）。

### 7.2 QPS（关键指标，实测）

QPS 目前**非一等字段**，只有 `requestCount`/分钟桶可反推。本轮分 endpoint（负载 31.5s、2831 请求）：

| endpoint | count | QPS(count/活跃31.5s) |
|---|---:|---:|
| GET:/hello | 444 | ~14.1 |
| GET:/fullSample | 414 | ~13.1 |
| GET:/longTimeTask | 411 | ~13.0 |
| GET:/api/trace-alert-demo/error | 405 | ~12.9 |
| GET:/api/trace-alert-demo/http500 | 392 | ~12.4 |
| GET:/queryDbByJdbc | 392 | ~12.4 |
| GET:/queryDbByMybatis | 368 | ~11.7 |
| **全局** | 2831 | **~89.9** |

⚠️ 分母：整分钟桶才 `/60`；不满一分钟或跨分钟要用**桶内活跃秒数**（否则低估，如上表按 `/60` 只得 ~7/s，实际 ~13/s）。

### 7.3 第二轮实测：≈87k 行时的堆占用拆分（2026-09-27）

用户实例（demo-app / JDK8 / H2 mem，`shadow_max_rows=100000`；`/inner/sw/trace-parity` 报 `h2Size=87,461`，`KeyedLocalStore` 顶格 **1000**）。工具：`jmap -heap` / `jmap -histo:live`（触发一次 Full GC）。

| 对象 | 实测（浅和） | 备注 |
|---|---:|---|
| live heap（老年代，GC 后） | **124.66 MB** | `-Xmx` = 8118 MB（默认，未显式设） |
| **H2 mem（插件）** | **30.2 MB** | `…apm.dependencies.h2.*` 全类；925,095 实例（`ValueBigint` 317k / `ValueVarchar` 198k / `DefaultRow` 100k / `SimpleRowValue` 100k / `Value[]` 103k） |
| demo 业务 H2 | 0.06 MB | `org.h2.*`（未 shade），可忽略 |
| **KeyedLocalStore** | 对象本身 88 B | 内容 = 1000 条 trace 的 **Map 对象图**（非 `Log` 对象） |
| `[C` / `String` | 41.98 / 7.43 MB | 共享：H2 varchar + store 字符串 |

**估算**（浅和低估；retained 需 heap dump + MAT）：
- **H2**：浅和 30.2 MB；含其引用的 `ValueVarchar→String/char[]` 后 retained ≈ **~50 MB**——与 §7.1 校准 `R≈0.8KB/行 × 87k ≈ 70MB` 同量级（后者含 metrics 行增长，偏上）。
- **KeyedLocalStore**：1000 条 trace 顶格，`/statistic` 全量 JSON = **6.70 M 字符** → Java retained ≈ **15–25 MB**（UTF-16 字符 ~13MB + Map/List 头）；受 `max_log_size=1000` 硬限制。
- **其余 ~50 MB**：JVM/meter/profile 本地缓存、metrics 桶、Tomcat/框架/缓冲。

> 方法学：`jmap -histo` 是**浅和**，只有 H2 能按类名精确归因；`KeyedLocalStore` 的底层 `HashMap`/`ArrayList`/`String` 全应用共享，无法按类名拆分。复测：
> ```powershell
> & "D:\apps\java\jdk1.8.0_92-64\bin\jmap.exe" -heap <pid>
> & "D:\apps\java\jdk1.8.0_92-64\bin\jmap.exe" -histo:live <pid>   # 触发 Full GC
> curl.exe -s --noproxy "*" http://127.0.0.1:9600/inner/sw/trace-parity
> ```

## 8. `payload_id` 只增不减，会溢出吗？（结论：无实际风险）

`payload_id` 不是"行号计数器"，而是**环形载荷文件的逻辑字节偏移**（`CappedFileStorage.currIndex`）：
从文件头 16B（`currIndex` + `sizeBytes`）恢复、只增不减；`writeMessage` 返回块起始偏移，
每次写让 `currIndex += (8B 块头 + gzip 载荷) ≈ S+8 ≈ 670 B/段`。H2 列为 `payload_id **BIGINT**`（64bit，与 Java `long` 一致）。

- 溢出需写入 **2^63−1 ≈ 9.22×10^18 字节 ≈ 9.2 EB** → `9.22e18 / 670 ≈ 1.4×10^16` 段。
- 量级：6.7 MB/s（≈1 万段/s）需 **~4.3 万年**；1 GB/s 也需 **~292 年**。
- mem 模式进程重启即 `currIndex=0`；只有复用同一 capped 文件跨重启才累积，仍为 EB 级。
- 真溢出（不现实）的后果：`currIndex` 转负 → `isOverwritten`（`id < currIndex - sizeBytes`）失效，
  且读侧 `payloadId < 0` 被当"空/过期"跳过 → **静默丢 payload**（非崩溃）。

**结论**：无需处理。若要防御性，可在 `writeMessage` 加天文阈值金丝雀（如 `currIndex > 2^62` 时重置环形 + 告警）。

---

## 9. 与既有文档/决策的关系

- `ADR-03` — header/索引与 payload 分离、payload 过期降级：本笔记沿用其语义，不新增机制。
- `docs/todos/h2化-统一方案.md` §1.4 / §5 / Phase 3 — 原路线「Phase 3 切 file + 两档 TTL」；本笔记为「mem 模式是否可作形态、file 是否可延后」提供依据。
- `docs/notes/2026-09-25-trace-metrics-stress-memory-analysis.md` — 实测里 `H2≈4.67MB`、`h2Size=2000` 可作 R 的旁证。
- `CONTEXT.md` — 术语「trace 持久层」「trace 内存热层」。

---

## 10. 定案清单（2026-09-27 grilling）

| # | 决策 |
|---|---|
| 1 | H2 mem 作**全量 trace 持久层**（含 normal）；**不收窄**；file 延后 |
| 2 | `KeyedLocalStore` 降为**兼容保留 + 交叉核对参照**；查询以 H2 为准 |
| 3 | 单表 `trace_segment`（**不**单独建 error/slow 表）；**删除 `trace_level` 列**——错误查 `is_error`、慢查 `latency >= :阈值`（查询参数化） |
| 4 | `N = 100,000` 行；`F = 128 MB`；`persist_only_hits` 保留、默认 `false` |
| 5 | 读口：`getTraceView` 保持 H2 为准；**新增** `getTraceViewFromMemory(traceId)` 读 `KeyedLocalStore`（宿主桩 + 拦截器） |
| 6 | demo trace 查询页加**双源对照**（H2 为准 vs 内存参照 + 一致性/差异高亮） |
| 7 | 原"Phase 2 收窄"改写为"**全量 trace 入 H2 + 过渡期双源核对**"；查询门面并入 Phase 4 |
| 8 | `S/R/K` 待实测校准（第 7 节），据以复核 `N/F` |
| 9 | **slow 判定**：告警走 `TraceEvaluator`；**metrics 复用同一套 `slow_rules`**（只读阈值解析器，Phase 5 增强）；均不依赖 `trace_level` |
| 10 | **孤段过滤保持**（`isOrphanSegment`，无 ref 且无 Entry）；`KeyedLocalStore` 的 `MAX_LOG_SIZE` 维持 **1000** |
| 11 | 慢查询 = **`endpoint` + `latency` 阈值**，提供**专门页面**（点开看慢链路）；`latency` 段级，多段按 traceId 取 `max`；阈值按 endpoint 传入；`latency` 索引按需 |

> 决策记录：`docs/adr/adr-04-h2-mem-as-full-trace-persistence-tier.md`；路线回写：`docs/todos/h2化-统一方案.md`。

---

## 一句话

**payload 在堆外、header 行 ~0.7KB/行**。定案采 **(B) 全量 trace 入 H2 mem**：`N=10万`（堆 ~70MB，≈ 旧 store 的 100×）、`F=128MB`（确保容纳 10 万段）、单表（**删 `trace_level`**；错误查 `is_error`、慢查 `latency`）、`KeyedLocalStore` 降为参照/双源核对；mem 窗口分钟~小时级（已知并接受），file 延后。`S/R/K` 待实测钉死。
