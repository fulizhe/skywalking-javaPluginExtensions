# H2 内存模式定为 trace 持久层终态，并留 file 模式复入场券

> **状态**：accepted（2026-10-02 定案）。**先于实现**记录——它把 ADR-04 决策 6「file 模式延后」从"暂缓"升级为"**当前明确不做**"，并把复入的**门槛与配方**钉死，避免将来被重新推一遍。
> **同日评审修订**：①背景第 2 条纠正的 `RETENTION_TIME` 默认值写错（实为 45 000 ms，非 0）；②决策 3 配方补 3 项（`start_time` 索引、`AUTO_COMPACT_FILL_RATE` 调参、锁内 compact+重连）；③决策 4 的前提"没有一把锁"**不成立**——现有 `synchronized (this)` 已串行化全部 H2 读写。决策 1、2 不受影响。

## 背景

### 起因

一个具体疑问：**Glowroot 是怎么控制 H2 文件持续增长的？**（<https://github.com/glowroot/glowroot/wiki/Administration-Storage>）——本项目一直把 H2 留在内存模式，理由是"顾及这个"。

### 核实结论：Glowroot 没有给 H2 文件设硬上限

它是**四层叠加**（源码锚点见 `docs/reference/glowroot-capped-database.md`）：

| 层 | 机制 | 我们有吗 |
| --- | --- | --- |
| 1 | payload 出 H2 → capped 文件固定大小（**硬封顶**） | 有（ADR-03） |
| 2 | Reaper 按 `capture_time` 定时删行（**单档** `traceExpirationHours`） | 无 timer；mem 下用行数上限代替 |
| 3 | **Analyze**：逐表 `disk_space_used` 算 live vs reclaimable | 无 |
| 4 | **Compact**：跑 `shutdown compact`（**手动**，由第 3 层判据触发） | 无 |

Glowroot 自己的 wiki 也承认这一点：*「Lowering days alone does not always shrink the file immediately」*、*「Capped files do **not** replace H2 retention」*。

### 由此，本项目的"mem 模式"并没有治 H2 增长，只是把它挪到了堆上

`jdbc:h2:mem:` **不产生任何文件**，磁盘占用恒为环形文件大小；而代价是堆 ~80 MB（`shadow_max_rows=100000`）+ **重启即失**。

### 同期纠正两处本仓库已有的错误论据

依据 H2 官方文档与 MVStore 文件格式：

1. **autocompact 本来就在跑**——*「H2 Database automatically reclaims empty space. By default, it compacts the database for up to 200 milliseconds when closing.」*
   → file 模式的磁盘风险是「**高水位**」，不是「无限增长」。compact 的作用不是"阻止增长"，而是"**把历史峰值缩回去**"。
2. **「空闲 chunk 需过 retention 期后才可能被回收」方向反了**——门槛不是 retention 期，而是**chunk 失去最后一个活页后 45 秒**：`FileStore.getDefaultRetentionTime()` 返回 **45 000 ms**。⚠️ `SET RETENTION_TIME 0` 才对应 *overwritten as early as possible*（尽早覆写），**而 0 不是默认值**（`MVStore.getRetentionTime()` 仅在 `fileStore == null` 时返回 0）——H2 issue #4170 正是讨论"要不要把默认改成 0"，截至核实仍为 45 s。MVStore 的真实机制是 copy-on-write chunk 失去活页后标为 free、**空间被文件自己复用**——**复用，不缩容**。

另有一条决定了决策 4 的 H2 语义：*「The SHUTDOWN command **closes all active connections** to the database and shuts down the database itself.」*

## 决策

1. **H2 内存模式是 trace 持久层的终态**。Phase 3（file 模式）**当前明确不做**——这不是"暂缓"，是"当前不需要跨进程重启持久化"。

2. **磁盘占用承诺**（显式、可验证）：mem 模式下磁盘占用 = **恒 `payload_capped_size_mb`（默认 128 MB）** 的环形载荷文件；H2 不产生文件；环形 `currIndex` 持久化在头部 16 B（`openAndInit` 读 / `writeHeader` 写），重启后从原游标续写、覆写最旧 → **自愈，不产生孤儿垃圾**。**零回收需求。**
   - **该承诺的前提是决策 1（不需要跨进程重启持久化），不是物理事实**——不得被引述为"mem 天然更省盘"。
   - nuance：环形 fsync 节流为「每 100 写或满 1 秒 `force()`」，崩溃后 `currIndex` 可能偏旧，已写未 fsync 的块读回 `null`（走 `id >= currIndex` 分支，语义是"不存在"而非"过期"）。mem 模式下无实际影响（H2 header 同时丢失），但**不得声称"绝不丢块"**。

3. **复入场券**：门槛 = **需要跨进程重启持久化**（沿用 ADR-04 决策 6 原文）。触发后的配方**已定，不再重开**：
   - **保留策略 = 单档时间留存**：按 `start_time` 水位线删行，截止时间在 **Java 侧**算好参数化传入。**不分档**——分档依赖已删的 `trace_level`，且会让"查得到的时间窗口随该链路是否出错而变"，比统一窗口更难解释。具体 TTL 值按实跑数据定，**不预设标准答案**。**必须同时给 `start_time` 建索引**（`trace_segment` 现有唯一索引是 `idx_trace_segment_trace_id`）：Glowroot 为此专门加了 `trace_capture_time_idx`，源码注释 *"for reaper, this is very important when trace table is huge"*；无索引时 Reaper 每轮都是 10 万行全表扫。
   - **磁盘论据 = 高水位，不是无限增长**（依据见背景第 1 条纠正）。`.mv.db` 涨到"活数据高水位"后**自动停住**——但**该自稳依赖 autocompact 的目标填充率**（见下一条），不是 H2 的无条件默认行为。
   - **autocompact 必须显式调参**：Glowroot 的 file URL 写死 `AUTO_COMPACT_FILL_RATE=30`（H2 原生 90），注释 *"to cut background rewriteChunks CPU/IO on shared-JVM embeds"*，可用 `-Dglowroot.internal.h2.autoCompactFillRate=N` 覆盖（0 = 关闭）。此项**是上面"自稳在高水位"的实际抓手**，不配就不能只引用结论。
   - **收缩手段 = compact**，判据照 Glowroot：先 **Analyze** 求 live vs reclaimable，**仅当 `reclaimable ≥ 64 MB` 或 `reclaimable / 文件 ≥ 10%`** 才值得跑。
   - `JDBC_URL` 需从写死常量（`H2TraceSegmentStorage.java:62`）改为配置项，且**一次带齐** `AUTO_COMPACT_FILL_RATE`、`compress=true`、`db_close_on_exit=false`（Glowroot `DataSource.buildFileUrl` 的全套）。**并须在同一个锁内完成 `SHUTDOWN COMPACT` + 重连**——`SHUTDOWN` 会关闭所有连接，当前 `connection` 字段随即失效（详见决策 4）。

4. **有意分歧：我们要无人值守的定期 compact，Glowroot 是手动 + Analyze 判据。**
   - 差距不是"少个按钮"，而是**缺一个前置条件**：Glowroot 的 `DataSource` 用一把 `synchronized (lock)` 覆盖全部读写，`shutdown compact` 在锁内执行并**重连**，于是"compact 期间不采集"是天然结果；连它的 Reaper 每次只删 100 行，源码注释即*"doesn't lock the single jdbc connection for one large chunk of time"*。
   - **修正一处此前的错误判断**：~~"我们的写侧与读侧不共享连接级锁"~~ **不成立**——`H2TraceSegmentStorage` 早已用一把 `synchronized (this)` 串行化**全部** H2 访问：写线程经 `storeSegment` → `storeLog`（`H2TraceSegmentStorage.java:316`）在锁内完成 insert + 水位清理；所有读口（`snapshot` / `size` / `queryTrace` / `recentTraces` / `querySlowTraces` / `storeMetricRows` / `queryMetricRows` / `aggregateMetricRows` / `activeBucketCounts` / `distinctMetricBuckets` / `deleteMetricsBefore` / `insertAuditRows` / `recentAuditRows` / `auditRowCount`）各自 `synchronized (this)`，共用同一个 `connection` 字段。**结构上与 Glowroot 同构**，"compact 期间无写入"天然成立，不需要新造机制。
   - **真正缺的两件**（比原判断小得多，但都还没做）：
     1. **锁内的 `SHUTDOWN COMPACT` + 重连临界区**——H2 `SHUTDOWN` *"closes all open connections… open transactions are rolled back"*，执行后 `connection` 字段随即失效；需要在锁内跑 shutdown 并调 `initConnection()`（`:140`）重连。**无需失效语句缓存**：本类每次调用都现 `connection.prepareStatement(...)`，不像 Glowroot 有 `preparedStatementCache`。
     2. **写队列的处置策略**——`accept` 把段投进 `writeQueue` 时对 compact 无感知，compact 期间队列继续堆积；"暂停消费 + 有界背压"还是"允许堆积、事后补写"属**尚未作出的决定**，直接决定 compact 窗口能开多大。
   - **因此本条仍记录为缺口，不是承诺**：待解项 = 上述 2 项，其中第 2 项是**待决策**而非待实现。落地前定期自动 compact **不可执行**——但工作量是小时级的临界区改造，**不是"需先造屏障"**。若将来只做**手动** compact（照 Glowroot），则只需第 1 项，无需队列策略。
   - "**存储连接级**屏障"这个名字仍然沿用，但含义已收窄：**不是要造一把新锁，而是要让 shutdown + 重连整体落在现有锁内**，且读口也必须在同一临界区内（否则它会拿到 shutdown 之后的死连接）。命名为"存储连接级"而非"写屏障"是刻意的：最初写作"写屏障"，核 H2 `SHUTDOWN` 语义后发现**读口也是阻塞项**。

## Considered Options

- **照搬 Glowroot 四层（file 模式 + 手动 Analyze/Compact UI）**：拒绝。当前不需要跨重启持久化，为未触发的门槛预建运维机制是零收益的 churn；且决策 4 说明"照搬"本身也不完整——还缺屏障。
- **只保留 ADR-04 决策 6 的"延后"、不写本 ADR**：拒绝。"延后"没写门槛条件与配方；下次会重新推一遍 file 模式，并可能把"重建表"误当首选（漏掉 autocompact 已自稳这一层）。
- **把"我们要定期自动 compact"写成承诺**：拒绝。①那是把一个**当前做不出来**的方案写成已决定，ADR 会说谎，且"设计缺口"会被后来者误读为"活没干完"；②承诺意味着反悔需要写新 ADR（推翻决策本身是决策），而**尚未做出的决定不该带决策级的不可撤销性**。
- **ADR 里只写屏障前置、不表态要不要自动 compact**：拒绝。会丢掉已作出的取舍（要无人值守），下一个人要重新推一遍手动 vs 自动之争。
- **降级 `shadow_max_rows` 来省堆**：拒绝。mem 下堆由行数上限封顶（~80 MB），属已接受的代价；降级会把窗口压到分钟级，与"尽可能多存 trace"直接冲突。

## Consequences

- **Phase 3 关闭，但带着一张写好的入场券**；`docs/todos/h2化-统一方案.md` 的 Phase 3 条目改为"门槛触发式"。
- **磁盘承诺可验证**：mem 模式磁盘占用恒 128 MB、无增长；环形文件重启自愈。
- **`.mv.db` 风险定性从「中·无限膨胀」改为「高水位·自稳，但自稳依赖 autocompact 调参」**——降低将来 file 模式的评估成本，同时纠正 `docs/todos/h2化-统一方案.md` 风险表里的错误论据。**限定条件**：H2 侧生效的是 `AUTO_COMPACT_FILL_RATE`（Glowroot 取 30）而非无条件默认行为，复入时必须显式配置（决策 3）。
- **定期自动 compact 在上述 2 项落地前不可执行**；若将来只做**手动** compact（照 Glowroot），则**只需锁内临界区**、无需队列策略——这是缺口未闭合时的退路。
- mem 下淘汰仍按**行数**：`enforceRowCap` 每 1024 次 insert 校一次，执行 `DELETE FROM trace_segment WHERE id <= MAX(id) - N`（稳态每次约删 1024 行）。**窗口长度随 QPS 漂移**（@10 QPS ≈ 2.8 h、@100 QPS ≈ 17 min），这是已接受的代价；**时间维度留存只在 file 阶段引入**。
- 文档同步：补 `docs/reference/glowroot-capped-database.md` 的 Analyze / compact / 阈值 / H2 cache / Reaper 锚点并纠正三处；纠 `docs/todos/h2化-统一方案.md` 的 `SET AUTOCOMPACT`、`需过 retention 期`、`定期`、`分级 TTL`。**本轮代码零改动。**
- **留给下一轮的低优先项**（本轮未改，不阻塞）：Glowroot 的 "Defrag 保留两个按钮是为照顾运维找 defrag 这个词" 一句在 wiki / `storage.js` / `storage.html` 均查无出处，应删或补出处；`storage.html` 的停采集文案真实措辞是 *"during which time Glowroot **will not** capture any new data"*（按钮 confirm-body，非 CTA）；H2 的 200ms 引文真实出处是 `features.html`；`§4.1` 减 1 天的表是 **`gauge_id`** 而非 `gauge_name`；新增锚点应与既有表一致地 pin 到 commit 而非 `main`。

## 参考

- Glowroot Storage wiki（Analyze / Compact / capped MB / H2 cache 的官方表述）：<https://github.com/glowroot/glowroot/wiki/Administration-Storage>
- Glowroot 拆法一手核实与源码锚点：`docs/reference/glowroot-capped-database.md`
- 载荷拆法（header + 环形封顶文件）：`docs/adr/adr-03-capped-file-payload-for-trace-details.md`
- 全量入 mem / 门槛原文（决策 6）：`docs/adr/adr-04-h2-mem-as-full-trace-persistence-tier.md`
- 保留策略与空间归还原文：`docs/todos/h2化-统一方案.md` §5.4
- 容量与实测：`docs/notes/2026-09-27-h2-mem-capacity-estimate.md`