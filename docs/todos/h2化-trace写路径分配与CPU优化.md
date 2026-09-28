# H2 化：trace 写线程的分配 / CPU 优化（讨论稿 · 待做）

> **状态（2026-09-27）**：**未实现，仅讨论记录**。起点是「gzip 会不会让 agent 抢 CPU 拖累业务」的质疑；结论是**暂不动 gzip**，先把整个写线程的 GC / CPU 开销按 ROI 排好，量化后再做。
>
> **定位**：性能优化候选清单 + 判据，不是 spec。真做时先量化（见 §6），再按 §5 顺序落。相关背景见 `docs/notes/2026-09-27-h2-mem-capacity-estimate.md`、`docs/adr/`（payload 落 `CappedFileStorage`）。
>
> **进度（2026-09-28）**：P0-B 已落；P2 仅保留 logs 循环、tags 已回退（见 §7）。**下一步：实现 B1（去重复 `transform()`/`toLog()`），细化见 §8**。

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
| **P0** | 复用 INSERT `PreparedStatement`（**A，待做**）；`enforceRowCap` 由"每行 `MAX(id)`"改节流（**B，已完成 2026-09-28**） | 每段一次 SQL 解析/规划 + 一次全表查询 + `ResultSet`/`Statement` | 低 / 低 |
| **B1（新增，下一步）** | 去每段重复的 `transform()`/`toLog()`：消费线程只做一次，H2 写线程不再转换（旧路径与 H2 共享同一 `Log`） | 写线程侧 1× protobuf `SegmentObject` + 1× `Log` 对象图；分配率净降 | 低-中 / 低（**不碰 JSON 契约**；细化见 §8） |
| **P1** | 去 `toMap()`→GSON String→`getBytes` 三段大副本，**流式直写进 gzip**（手工 JSON writer 或 `Gson.toJson(obj, Writer)` 包 gzip 流） | 一段 payload 级的对象图 + 大 String + byte[] + BAOS 扩容拷贝 + GSON 反射 | 中高 / 中（须保住 JSON→`Map` 回读兼容） |
| **P2** | logs 去 `stream().map().collect()`（**保留，已完成 2026-09-28**）；`tagsToTagList` 去"每 tag 一个 HashMap"（**已回退 2026-09-28**，见 §7） | 每段一条 stream 管道 | 低 / 低 |
| **P3** | gzip：单写线程复用 `Deflater`/buffer + level `BEST_SPEED`(1) + 小载荷阈值（<512B 裸存） | 每次新建 Deflater/BAOS 扩容；level 6 的多余压缩 | 中 / 中（`GZIPOutputStream.close()` 会 `end()` Deflater，需子类化/复用 Deflater） |
| **P4** | 去 `TextFormat.printToString` 每条 log 一个 String | 仅 error/slow 段显著 | 中 / 中（低频，暂不动） |

**备注**
- P1 若直接从 protobuf/`Log` 手工写 JSON，可顺带吃掉 §3 的 `toLog` 对象图与 P4。
- `PAYLOAD_CAPPED_ENABLED=true`（`LogFileReporterPluginConfig.java:118`，**默认即 true**）→ payload 落环形文件并 gzip 压缩；`false` → **完全不落 payload**（不是"落盘但不压缩"）。目前**没有"裸存但保留 payload"的开关**，P3 的裸存档需要新增块标志位。

## 5. 建议的进攻顺序

1. 先量（§6），确认 P0/P1/P2/P3 各自占比——很可能 **SQL 每段往返 + 序列化** 领先，gzip 中游。
2. 低风险先摘果：**P0**（SQL 复用 + cap 节流）、**P2**（去小对象）。
3. **B1**（去重复 `transform()`/`toLog()`，§8）——纯浪费、不碰 JSON 契约，作为下一步。
4. 再动 **P1**（流式序列化直写 gzip）——最结构性，一次性吃掉 String/`getBytes`/BAOS/对象图。
5. gzip 参数化放最后（**P3**），默认 level 1 即够；裸存仅在「本地/demo 且不在乎深度」时选。
6. 口径提醒：**A（常驻堆）当前可接受，暂放**；重点在 **B（分配率/GC）**；磁盘占用不在目标内。

## 6. 先量化（只读，不碰插件代码）

- JFR 或 async-profiler 采 **`H2Shadow-Writer`** 线程：拆出 gzip / GSON / H2 / fsync 的 CPU 与**分配热点**。
- GC 日志：对比写线程开启前后 Young GC 频率与暂停。
- `writeQueueDropped` 计数 + 队列水位（容量 `WRITE_QUEUE_CAPACITY=4096`，`:81`）：写线程是否真的跟不上。
- 可选微基准：同款 JSON 跑 GZIP level 6 / level 1 / 裸存 的 ns/段与分配。

## 7. 决策记录

- **2026-09-27**：讨论后**先不动**——没有证据表明 gzip 是主因；优先量化，再做 P0/P2 低风险项，P1 次之，gzip 参数化最后。
- **2026-09-28**：落地 **P0-B**——`enforceRowCap` 按行数节流（写死每 1024 行校验一次），省掉逐行的 `MAX(id)` 查询与 `Statement`/`ResultSet` 分配；代价是表最多短暂超水位 1024 行（软上限）。选择硬编码不引入配置项，TODO 已留在 `enforceRowCap`。**P0-A（复用 INSERT `PreparedStatement`）暂缓**：其收益需实测（H2 有 per-session 编译缓存），且要额外守住"锁内使用 / `clearParameters` / 出错重建"三条纪律。
- **2026-09-28**：落地 **P2**。①logs：`stream().map().collect()` 改手工预分配 `ArrayList` 循环（`TextFormat.printToString` 仍在，P4 另说）——**保留**。②tags：曾改用轻量 POJO `Log.Tag` + `@SerializedName("tag-key"/"tag-value")`（Gson 直写字段、不走 `entrySet`）；**同日回退**。回退原因：`@SerializedName` 是 Gson 专属，内存热层读口 `/statistic`（Spring `@RestController`）走 **Jackson**，按 getter 名序列化成 `{key,value}`，破坏 `tag-key/tag-value` 契约（`trace-view.html` 的 Span 详情显示 `undefined: undefined`）；H2 读口走 Gson 所以表现为"H2 正常、内存异常"。结论：**对外 JSON 契约不能用序列化器专属注解承载**；tags 仍用 `List<Map>`（键名天然固定），放弃该小收益。经评估自定义 2 槽 `Map` 也会引入更差的瞬时 entry 分配，不走。

## 8. B1 细化（待实现 · 2026-09-28 讨论定）

**目标**：每段 `transform()` / `toLog()` 各只做 **1 次**（消费线程），产物 `Log` 同时喂旧路径与 H2；**H2 写线程不再 `transform()` / `toLog()`**。H2 落库内容不变（同一 `Log.toMap()` + 同一 Gson）→ **不碰 JSON 契约**。

**现状重复（每段 2×）**

```
consume:
  772: data.stream().map(TraceSegment::transform)   → SegmentObject（旧路径用）
  801: SegmentLogConverter.toLog(segment)            → Log（旧 stat map）
  815: traceSegmentStorage.accept(keptRaw)           → 队列里放原始 TraceSegment
H2 writer storeSegment:
  segment.transform() 再一次；SegmentLogConverter.toLog 再一次
```

**已核实的支撑点**

- `LogFileTraceSegmentServiceClient.afterFinished`（`:1000-1003`）已对 `isIgnore()` 提前 `return` → 忽略段进不了 `consume`，**`consume` 侧无需再补 ignore 过滤**。
- `.accept(` 全仓仅 `LogFileTraceSegmentServiceClient:815` 一处（无测试/其它 caller）；字段是具体类 `H2TraceSegmentStorage`（`:87`）→ 加 `acceptLogs` **不用动 `TraceSegmentStorage` 接口**。
- `mergeLogIntoStatMap`（`:943`）只读 `Log`（`getTraceId()` + `toMap()`），**不原地修改** → 旧路径与 H2 共享同一 `Log` 安全；发布安全由 `ArrayBlockingQueue` 保证。

**改动清单**

1. `H2TraceSegmentStorage`：`writeQueue` 元素 `TraceSegment` → `Log`（`:78`/`:118`）；新增 `acceptLogs(List<Log>)`（offer/丢弃语义同现有 `accept`）；`drainLoop`/`drainQueue`/`awaitIdle` 内 `storeSegment(seg)` → `storeLog(log)`；删 `storeSegment(TraceSegment)`；`accept(List<TraceSegment>)` 保留（内部改为 `transform + toLog` 后 `storeLog`）。
2. `LogFileTraceSegmentServiceClient.consume`：`:801` 建一次 `Log log = SegmentLogConverter.toLog(segment)`，`mergeLogIntoStatMap(log)` 后 `keptLogs.add(log)`；用 `keptLogs` 取代 `keptRaw`（`keptRaw` 删；`keptObjs` 仍供 `runParityCheck`）；`:815` 改 `acceptLogs(keptLogs)`；`metricsAggregator.onSegment(segment)`（`:806`）不动。
3. 护栏：复用 `compare_debug` 旧/H2 对账证明落库内容不变；新增 `acceptLogs` 入队→落库单测；现有测试走 `storeLog(Log)` 不受影响。

**实现前待确认**

- 传 `Log`（`transform`+`toLog` 各 1×，**推荐**）还是只传 `SegmentObject`（只去掉写线程的 `transform`，`toLog` 仍 2×）。

**风险 / 口径**

- 队列元素由 `TraceSegment`（含 span 对象树）变 `Log`（含 `TextFormat` 字符串）：对分配率净降；对常驻堆方向不定（队列上限 4096 有界），需观察。
- 溢出阈值 / 丢弃计数 / `awaitIdle` 语义不变。

**验证**：`cd agent; mvn -pl logfile-reporter-plugin -am test`（166 全绿 + 新用例）；可选 JFR 确认写线程热点里 `transform`/`toLog` 消失。

## 9. 参考

- `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/H2TraceSegmentStorage.java`（`:81`/`:176-187`/`:206`/`:213-224`/`:227`/`:308-321`/`:356-366`/`:368-388`/`:623-643`）
- `.../storage/CappedFileStorage.java`（`writeMessage:105`、`gzip:264`、`gunzip:272`）
- `.../reporter/logfile/Log.java`（`toMap:92-123`）
- `.../reporter/logfile/SegmentLogConverter.java`（`toLog:26`、`toSpanInfo:41`、`tagsToTagList:57`）
- `.../reporter/logfile/LogFileReporterPluginConfig.java`（`PAYLOAD_CAPPED_*` `:117-124`）
- 容量/内存估算：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`
