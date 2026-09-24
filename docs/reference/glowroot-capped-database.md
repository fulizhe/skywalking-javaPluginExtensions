# Glowroot CappedDatabase：H2 行 + 封顶文件存 payload（一手核实）

> **用途**：记录 Glowroot "**索引/元数据进 H2、大 payload 进环形封顶文件**" 的存储拆法，供本项目 H2 化在**磁盘膨胀 / 硬上限**问题上的备选参考。
> **核实基准**：`glowroot/glowroot` @ `456b1910bbeeb152efd78103043d71c08b183975`（main，2026-09-08），逐文件打开源码确认。
> **证据类型**：`【源码】`；无【实测】（本仓库未运行 Glowroot）。
> **姊妹篇**：整体范式（采集/写入/查询/保留）见 `glowroot-trace-storage.md`；本文聚焦**存储拆分与 CappedDatabase 文件机制**。
> **本项目的落地（ADR-03）**：已按本文 §5 的"最小形态"实现（去掉 resize/future/统计，载荷改为每块 GZIP）——见实现笔记 `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/CappedFileStorage-20260923.md` 与决策记录 `docs/adr/adr-03-capped-file-payload-for-trace-details.md`。
> **已核实**：文件格式、块写入/读取、覆盖判定、压缩、fsync、resize、统计指标、`TraceDao` 的读写与 `Existence` 语义。
> **未核实**（见 §6）：capped 文件默认大小/配置键与默认值、resize 的触发入口、rollup capped db（非 trace）的用途细节。

---

## 0. 一句话结论

1. **拆法**：H2 `trace` 行只放**可索引的小字段**（时间 / 耗时 / 类型 / 错误 + header protobuf）；大 payload（entries / queries / shared query texts / profiles）写进**环形封顶文件**（`*-capped.db`），H2 行里只存 `*_capped_id` **指针**。
2. **两套保留机制互补**：H2 行按**时间**删（Reaper `deleteBefore(capture_time)`）；capped 文件按**空间**自淘汰（写满覆盖最旧块）。磁盘上限 = H2 大小 + capped 固定大小，**可预测**。
3. **`cappedId` 不是文件偏移**，而是"逻辑块起始索引"；文件头含 `currIndex` / `sizeKb` / `lastResizeBaseIndex`，物理位置按 `sizeKb` 取模，块可跨文件尾环绕。
4. **先写 payload、后写指针**：`writeMessages` 拿到 id 后才 `merge into trace`；读到已被覆盖的 id → `Existence.EXPIRED`，只少 payload，不会脏读。
5. 解决的核心问题是 **H2 大 CLOB 膨胀且删了不缩**（issue #755：曾出现 `data.h2.db` 涨到 31G）。

---

## 1. 为什么值得拆（问题背景）

把大 payload 直接塞 H2（`CLOB`/`MEDIUMTEXT`）的代价：

- **文件只涨不缩**：行被删后空闲页未必归还给 OS，`data.h2.db` 会长期保持峰值体积（issue #755 即此类）。
- **读写放大**：每次查询 header 都可能触碰含大列的页；备份/复制整个库的成本高。
- **没有硬上限**：增长由数据量决定，边缘场景（异常大 JSON / 长 SQL / 堆栈）不可控。

Glowroot 的需求组合：**按 traceId 点读全量 payload**（无 SQL 过滤需求）+ **磁盘必须有硬上限**（本地 APM、无人运维）→ 于是把"索引进 DB、blob 进自管文件"。

---

## 2. 拆法总览（`TraceDao` 视角）

`agent/embedded/.../repo/TraceDao.java`：

```text
store(TraceReader)
  ├─ TraceMerge(trace) 构造：
  │    entries        → writeMessages(...) → entries_capped_id
  │    queries        → writeMessages(...) → queries_capped_id
  │    sharedQueryTexts → writeMessages(...) → shared_query_texts_capped_id
  │    main/aux profile → writeMessage(...)  → *_profile_capped_id
  └─ dataSource.update(TraceMerge)
       → merge into trace (…, entries_capped_id, queries_capped_id, …) key(id)
```

要点：

- **payload 先落 capped 文件、id 后写 H2**：H2 行存在时指针指向的 payload 必已写入（除随后被空间覆盖/整体过期）。
- 空 payload → 指针为 `NULL`，读侧返回 `null`（区分"没有"与"过期"）。
- 读取时用 `RowMappers.getExistence(resultSet, i, cappedDatabase)` 得到 `Existence.YES / EXPIRED / NO`：**header 在 H2**，所以即使 payload 过期，基础信息仍可见（UI 表现为"已过期"）。

H2 行侧仅有**指针**，真正的块数据在 capped 文件里：

```text
trace 表（H2）                                        trace-capped.db（一个环形文件）
┌───────────────────────────────────────┐            ┌─────────────────────────────┐
│ id, slow, error, start_time, …        │            │ block: entries（protobuf…） │ ← entries_capped_id
│ header (VARBINARY, protobuf)          │            │ block: queries              │ ← queries_capped_id
│ entries_capped_id        ─────────────┼───────────▶│ block: shared query texts   │ ← …
│ queries_capped_id        ─────────────┼───────────▶│ block: profile              │
│ main_thread_profile_capped_id ────────┼───────────▶│ …（写满后从头覆盖最旧块）    │
└───────────────────────────────────────┘            └─────────────────────────────┘
```

---

## 3. CappedDatabase 文件格式与算法（源码级）

类：`agent/embedded/.../util/CappedDatabase.java`、`CappedDatabaseOutputStream.java`

### 3.1 文件布局

```text
文件头 20B（HEADER_SKIP_BYTES）                     块（可变长）
┌────────────┬────────┬──────────────────┐   ┌──────────┬────────────────────┐
│ currIndex  │ sizeKb │ lastResizeBase   │   │ len(8B)  │ payload（LZF 流）   │
│ long(8B)   │ int    │ Index long(8B)   │   │          │                    │
└────────────┴────────┴──────────────────┘   └──────────┴────────────────────┘
 偏移 0        偏移 8    偏移 12              块头 8B（BLOCK_HEADER_SKIP_BYTES）
```

- `currIndex`：**只增不减的逻辑游标**（跨多轮覆盖也不回绕；源码注释：以每秒 2.9G 写 100 年才会溢出 long）。
- `sizeKb`：封顶大小（字节数 = `sizeKb * 1024`）。
- `lastResizeBaseIndex`：resize 后的新基线（见 3.5）。
- **物理位置 = `(index - lastResizeBaseIndex) % sizeBytes`**；逻辑索引到物理偏移的映射即此。

### 3.2 写块（`startBlock → write → endBlock`）

```text
startBlock():
    若当前位置距文件尾不足 8B（放不下块头）→ 跳到下一轮起点
    blockStartIndex = currIndex;  currIndex += 8   ← 预留长度头
write(bytes):
    单个块不允许超过整个文件大小（否则 IOException）
    写到文件尾若不足 → 分两段写（尾部 + 从文件头继续）
    每次 write 后把 currIndex 写回文件头
endBlock():
    回到块头写 blockLength = currIndex - blockStartIndex - 8
    fsyncNeeded = true；返回 blockStartIndex（即 cappedId）
```

- `writeMessage` 写单个 protobuf；`writeMessages` 用 `writeDelimitedTo` 连续写多个，读侧 `parseDelimitedFrom` 直到流尾。
- **单写锁**：`CappedDatabase.write` 用 `synchronized (lock)` 包住 startBlock/write/endBlock；读也复用同一把锁（`CappedBlockInputStream.read` 内 `synchronized (lock)`）。

### 3.3 覆盖判定（"过期"语义）

```text
smallestNonOverwrittenId = max(lastResizeBaseIndex, currIndex - sizeBytes)
isOverwritten(id) = id <  smallestNonOverwrittenId    ← 已被新数据覆盖
isInTheFuture(id) = id >= currIndex                   ← 尚未写入（复制目录等场景）
```

- 读接口（`readMessage` / `readMessages`）命中上述任一 → 返回 `null` / 空列表（`Existence.EXPIRED` 的依据）。
- **capped 文件没有按条 TTL**：只按"空间"淘汰；H2 侧的 Reaper 删行与它互不依赖。

### 3.4 读块

- `CappedBlockInputStream`：按 `cappedId` seek 到块头读 `blockLength`，再按需分段读；**每次读都重算取模位置**，因此跨文件尾的块可自然读通；读到中途被覆盖 → 抛 `CappedBlockRolledOverMidReadException`（外层按过期处理）。
- 总是包一层 **`BufferedInputStream`（32KB，源码注释强调避免大量小读）**，再套 **LZF 解压流**。
- 压缩：`com.ning.compress.lzf`；在非对齐访问架构或 J9+Java6 上用 **safe encoder/decoder**。

### 3.5 fsync / 关闭 / resize

- **fsync**：调度器每 **100ms**（`FSYNC_INTERVAL_MILLIS`）检查一次；写时置 `fsyncNeeded`，真正 `fd.sync()` 发生在写锁之外；若距上次 fsync 超过 **2 秒**（调度被挤占）→ 写路径主动强刷（`fsyncIfReallyNeeded`）。源码注释：*"aggressive fsync interval to minimize chance of invalid trace records on abrupt JVM stop"*。
- **shutdown hook**：注册 `ShutdownHookThread`，关闭输出/输入文件（保证正常停机收尾）。
- **resize（原地）**：先尝试"易 resize"（只改文件头 `sizeKb`，条件是还没写到会产生歧义的位置）；否则保留 `min(旧大小, 新大小)` 字节，重写到 `*.resizing.tmp` 再 rename，并把 `lastResizeBaseIndex` 重设为 `currIndex - 保留字节数`。`CappedDatabase.resize` 会关闭/重开读句柄。

### 3.6 统计与观测

- `CappedDatabaseStats`：`totalBytesBeforeCompression` / `totalBytesAfterCompression` / `totalNanos` / `writeCount`，按 type 分桶。
- trace 侧四个 type：`trace entries` / `trace queries` / `trace shared query texts` / `trace profiles`（`TraceCappedDatabaseStats`）。
- 通过 MBean 暴露：`org.glowroot:type=TraceCappedDatabase`、`org.glowroot:type=RollupCappedDatabase{n}`、`org.glowroot:type=H2Database`（`SimpleRepoModule`）。压缩率与写入耗时可直接观测。

---

## 4. 两套淘汰如何配合

| | H2 `trace` 行 | capped 文件 |
| --- | --- | --- |
| 淘汰维度 | **时间**（`capture_time`，Reaper `deleteBefore`） | **空间**（写满即覆盖最旧块） |
| 触发 | 定时任务 | 每次写入自然发生 |
| 粒度 | 单行（可精确删） | 块（无法单条删，只能整块被覆盖） |
| 上限 | 由保留时长 × 数据量决定 | **文件大小硬上限**（配置） |
| 读失效表现 | 行不存在 | 指针仍在但 payload `EXPIRED`（header 可读） |

设计含义：

- **H2 保证"能查到什么"（索引/时间范围）**；capped 文件保证"磁盘最坏情况可预测"。
- 若写入量极大，payload 可能比 H2 行**更早**被覆盖（空间先满）——读到 `EXPIRED` 属于预期行为，UI 降级展示。
- 反之 H2 行先被 Reaper 删掉后，capped 块仍占空间直到被覆盖（不会立即释放）。

---

## 5. 对本项目（H2 化）的启示

**现状**：统一方案 §5.2 把整段 JSON 放 H2 `data_binary MEDIUMTEXT`（对齐 OAP）——实现最简，且 `trace_id` 等查询都不受影响。

**何时值得考虑拆**（触发条件，而非现在）：

- `data_binary` 体积大（大 JSON / 长 SQL / 长堆栈）：H2 文件增长快、TTL 删除后 **`.mv.db` 不缩**；
- 需要对监控插件承诺**磁盘硬上限**（边缘部署、客户现场）；
- payload 只按 traceId 点读，**不需要 SQL 过滤其内部字段**（否则拆出去反而麻烦）。

**可借鉴的最小形态**（都比 Glowroot 简化）：

1. H2 只留 header/索引列，payload 写**单个环形文件**（可参考同样的 `<8B len><payload>` 块 + 逻辑索引 + 覆盖判定，但去掉 resize/future/统计）；
2. 或按级别分治：**error/slow 的 payload 进 H2（量小、要审计）**，normal 不回 H2（只进内存 + metrics）——本项目当前决策方向，磁盘压力天然小；
3. 定期 `SHUTDOWN COMPACT` / 重建 H2 文件来回收空间（对极简实现更友好，但有停写窗口）。

**照抄时的坑**（Glowroot 已处理、自研需自担）：

- 块跨文件尾的读写、单块不得超过文件大小；
- 并发：写块期间必须互斥（Glowroot 用单锁），读也要参与该锁；
- 覆盖中途读（rolled-over mid-read）要按过期降级，不能抛到调用方；
- 进程 `kill -9` 时的 fsync 策略与停机 hook；
- 文件损坏/复制错位（`isInTheFuture` 场景）要能容错为"读不到"而不是崩；
- resize 与"保留 min(旧,新)"的基线重算。

**结论（已演进，2026-09-22）**：本项目已按上述**最小形态**落地——H2 只留 header/索引 + `payload_id` 指针，payload 经 GZIP 写**单个环形封顶文件**（`CappedFileStorage`，默认 128MB，写满覆盖最旧），写入异步化、payload 过期只降级。决策与后果见 ADR-03 `docs/adr/adr-03-capped-file-payload-for-trace-details.md`；实现细节与逐段解读见 `agent/logfile-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/reporter/logfile/storage/CappedFileStorage-20260923.md`。（下文原有的"先全进 H2、把本文当逃生方案"结论已被取代，保留 §5 上文仅为记录当时的取舍。）

---

## 6. 未核实 / 边界

1. capped 文件**默认大小 / 配置键名**：配置入口为 Administration → Storage 的 capped sizes（issue #1060 维护者说明）；源码里 `requestedSizeKb` 的最终来源未逐处确认。
2. `resize` 的**触发入口**（配置保存 → RepoAdmin → resize？）未逐行核实。
3. `RollupCappedDatabase`（rollup 明细）的用途与 trace 侧的差异未展开（非 trace）。
4. central（Cassandra）侧没有这个机制：trace 直接进 Cassandra（TTL 由 `USING TTL` 控制），仅在 embedded 模式使用 capped 文件。
5. 未运行 Glowroot 实测压缩率 / 实际读写延迟。

---

## 7. 源码锚点（@ `456b191`）

| 主题 | 文件 |
| --- | --- |
| capped 文件读写 / 过期判定 / resize / 统计 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java> |
| 文件格式 / 块写 / fsync / resize 实现 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabaseOutputStream.java> |
| 统计数据结构 | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabaseStats.java> |
| trace 侧 type 与 MBean | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/TraceCappedDatabaseStats.java> |
| H2 行 ↔ capped 指针（读写/Existence） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/TraceDao.java> |
| MBean 注册（TraceCappedDatabase / H2Database） | <https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/repo/SimpleRepoModule.java> |
