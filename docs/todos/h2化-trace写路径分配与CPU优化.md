# H2 化：trace 写线程的分配 / CPU 优化（讨论稿 · 待做）

> **状态（2026-09-27）**：**未实现，仅讨论记录**。起点是「gzip 会不会让 agent 抢 CPU 拖累业务」的质疑；结论是**暂不动 gzip**，先把整个写线程的 GC / CPU 开销按 ROI 排好，量化后再做。
>
> **定位**：性能优化候选清单 + 判据，不是 spec。真做时先量化（见 §6），再按 §5 顺序落。相关背景见 `docs/notes/2026-09-27-h2-mem-capacity-estimate.md`、`docs/adr/`（payload 落 `CappedFileStorage`）。

---

## 1. 关切与结论先行

- **关切**：agent 占用 CPU 会不会影响业务线程。
- **真实传导通道不是"CPU 抢占"，而是 GC**：写路径每段 trace 产生大量短命对象与"payload 级"大副本，高 QPS 下抬高年轻代分配率 → Young GC 更频繁 → STW 暂停影响**所有**线程（含业务）。
- **隔离性其实不错**：写线程是单条 daemon（`H2Shadow-Writer`，`H2TraceSegmentStorage.java:176-187`），业务线程只 `offer`；队列满即丢段（`:206`），**不会阻塞业务**——符合本仓库「监控只能是助力，不是阻碍，宁可采集不到」的取向。
- **gzip 不是首要矛盾**：gzip 只是把"开始丢段的 QPS 上限"压低，换来环形文件 5× 深度（`F=128MiB` → 约 20.3 万段 vs 裸存约 3.8 万段，见容量估算笔记）。**不建议在未量化前整块删除 gzip。**

## 2. 影响业务的通道（按主次）

| 通道 | 机制 | 何时才痛 |
| --- | --- | --- |
| **GC（主）** | 写线程分配率是全局的；大副本进年轻代 → Young GC 频繁 → STW | 一直存在，随 QPS/段大小线性上升 |
| CPU 调度争抢 | 写线程固定 1 条，抢一个核 | 仅当核被打满（业务 CPU 密集 / 单核） |
| 磁盘 IO / fsync | `CappedFileStorage.force()` 同步刷盘 | 造成系统级 IO 压力时 |
| 锁 | 业务只 `offer` 队列锁；`synchronized(this)` 仅写线程进 | 可忽略 |

**监控该看**：Young GC 频率/暂停、`writeQueueDropped` 计数、队列水位——而不是笼统的"CPU 压力"。

## 3. 写路径分配全景（每段 trace，写线程）

调用链：`accept`（业务，只 offer）→ `drainLoop` `:227` → `storeSegment` `:213` → `storeLog` `:308`。

| 环节 | 位置 | 分配/开销 |
| --- | --- | --- |
| `segment.transform()` | `:218` | protobuf `SegmentObject` 构建 |
| `SegmentLogConverter.toLog` | `SegmentLogConverter.java:26-55` | `Log` + `ArrayList<SpanInfo>` + 每 span 一个 `SpanInfo` |
| 每 span 的 tags | `:57-68` | **每个 tag 一个 `HashMap`**（2 键却有完整 table） |
| 每 span 的 logs | `:52` | `getLogsList().stream().map(TextFormat::printToString).collect()`：每 log 一个 String + 管道 + 收集器 |
| `Log.toMap()` | `Log.java:92-123` | 顶部 `HashMap` + `ArrayList` + 每 span 一个 `HashMap` |
| `GSON.toJson(map)` | `H2TraceSegmentStorage.java:361` | 反射序列化 → 大 String（char[]，UTF-16 2B/char） |
| `.getBytes(UTF_8)` | `:361` | payload 大小的一份 byte[] 拷贝 |
| `writeMessage` → `gzip` | `CappedFileStorage.java:105` / `:264-270` | `ByteArrayOutputStream`（`data.length/2` 起，扩容再拷）+ `new GZIPOutputStream`（**每次 `new Deflater`**，native+包装）+ `toByteArray()` 再拷 |
| `insertSegmentRow` | `H2TraceSegmentStorage.java:368-388` | **每行 `connection.prepareStatement`**（H2 解析/规划 SQL） |
| `enforceRowCap` | `:623-643` | **每次插入都 `MAX(id)` 查询**（+ `ResultSet`）；超水位才 DELETE |

> 关键观察：**同一段 payload 被复制 2~3 遍**（Map → String → bytes → BAOS → toByteArray），且 SQL 侧**每段固定几次往返/分配**，与 payload 大小无关。

## 4. ROI 排序（待验证）

判据：`ROI ≈ 省下的「每段副本字节 × QPS」或「每段新建重对象数」÷ 改动风险`。

| 优先级 | 优化点 | 省掉什么 | 工作量/风险 |
| --- | --- | --- | --- |
| **P0** | 复用 INSERT `PreparedStatement`；`enforceRowCap` 由"每行 `MAX(id)`"改节流 | 每段一次 SQL 解析/规划 + 一次全表查询 + `ResultSet`/`Statement` | 低 / 低 |
| **P1** | 去 `toMap()`→GSON String→`getBytes` 三段大副本，**流式直写进 gzip**（手工 JSON writer 或 `Gson.toJson(obj, Writer)` 包 gzip 流） | 一段 payload 级的对象图 + 大 String + byte[] + BAOS 扩容拷贝 + GSON 反射 | 中高 / 中（须保住 JSON→`Map` 回读兼容） |
| **P2** | `tagsToTagList` 去"每 tag 一个 HashMap"；logs 去 `stream().map().collect()` | 按 tag 数线性增长的 HashMap；每段一条 stream 管道 | 低 / 低 |
| **P3** | gzip：单写线程复用 `Deflater`/buffer + level `BEST_SPEED`(1) + 小载荷阈值（<512B 裸存） | 每次新建 Deflater/BAOS 扩容；level 6 的多余压缩 | 中 / 中（`GZIPOutputStream.close()` 会 `end()` Deflater，需子类化/复用 Deflater） |
| **P4** | 去 `TextFormat.printToString` 每条 log 一个 String | 仅 error/slow 段显著 | 中 / 中（低频，暂不动） |

**备注**
- P1 若直接从 protobuf/`Log` 手工写 JSON，可顺带吃掉 §3 的 `toLog` 对象图与 P4。
- `PAYLOAD_CAPPED_ENABLED=false`（`LogFileReporterPluginConfig.java:117`）是**完全落盘 payload**，**不是"落盘但不压缩"**；目前**没有"裸存但保留 payload"的开关**，P3 的裸存档需要新增块标志位。

## 5. 建议的进攻顺序

1. 先量（§6），确认 P0/P1/P2/P3 各自占比——很可能 **SQL 每段往返 + 序列化** 领先，gzip 中游。
2. 低风险先摘果：**P0**（SQL 复用 + cap 节流）、**P2**（去小对象）。
3. 再动 **P1**（流式序列化直写 gzip）——最结构性，一次性吃掉 String/`getBytes`/BAOS/对象图。
4. gzip 参数化放最后（**P3**），默认 level 1 即够；裸存仅在「本地/demo 且不在乎深度」时选。

## 6. 先量化（只读，不碰插件代码）

- JFR 或 async-profiler 采 **`H2Shadow-Writer`** 线程：拆出 gzip / GSON / H2 / fsync 的 CPU 与**分配热点**。
- GC 日志：对比写线程开启前后 Young GC 频率与暂停。
- `writeQueueDropped` 计数 + 队列水位（容量 `WRITE_QUEUE_CAPACITY=4096`，`:81`）：写线程是否真的跟不上。
- 可选微基准：同款 JSON 跑 GZIP level 6 / level 1 / 裸存 的 ns/段与分配。

## 7. 决策记录

- **2026-09-27**：讨论后**先不动**——没有证据表明 gzip 是主因；优先量化，再做 P0/P2 低风险项，P1 次之，gzip 参数化最后。

## 8. 参考

- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/H2TraceSegmentStorage.java`（`:81`/`:176-187`/`:206`/`:213-224`/`:227`/`:308-321`/`:356-366`/`:368-388`/`:623-643`）
- `.../storage/CappedFileStorage.java`（`writeMessage:105`、`gzip:264`、`gunzip:272`）
- `.../reporter/logfile/Log.java`（`toMap:92-123`）
- `.../reporter/logfile/SegmentLogConverter.java`（`toLog:26`、`toSpanInfo:41`、`tagsToTagList:57`）
- `.../reporter/logfile/LogFileReporterPluginConfig.java`（`PAYLOAD_CAPPED_*` `:117-124`）
- 容量/内存估算：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`
