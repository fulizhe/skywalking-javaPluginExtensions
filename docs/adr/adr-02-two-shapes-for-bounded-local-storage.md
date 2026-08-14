# 有界本地存储采用"两种形态"，而非评审建议的单一深模块

架构评审（`docs/review/architecture-review-20260808-220621.html`）的 Strong 候选 B 建议"一个深 `LocalStore` 统一 5 个 sender 背后的有界缓存"。逐文件核对代码后发现：4 个 sender（JVM/Meter/Log/Profile）是**追加式**环形队列（`CircularBlockingQueue`，生产代码只用 `add` + 快照读），而第 5 个（trace）是**键式**结构——按 traceId 合并、按 traceId 淘汰、`snapshot()` 返回 `Map<traceId, Map>`（host JSON 契约 `data[traceId].logs`），还承担给告警分发器的防御性拷贝。键式语义无法塞进 `add/snapshot/size`。

**决策**：不做"一个深存储"，改做**两种形态**。追加式形态维持 `CircularBlockingQueue` 现状（公开接口、插入加锁 + 弱一致快照读全部不变，仅纠正文档中"LRU"旧称）；键式形态新增通用 `KeyedLocalStore<K,V>`（`put`/`merge(key,fn)→V`/`snapshot():Map`/`size()`，FIFO 淘汰，单锁），供 trace 流按 traceId 合并存储。二者共享"有界 + FIFO 淘汰 + 防御性快照"的语义（见 `CONTEXT.md` 的有界数据存储），但接口不强行统一。

## Considered Options

- **评审方案——单一深 `LocalStore<T>`（`add`/`snapshot`/`size`）统一全部 5 个存储**：拒绝。trace 流是键式合并、快照是 `Map<traceId,...>`、还要给告警分发器做防御性拷贝，与追加式 `add/snapshot/size` 形态不符；硬套会让 `merge` 语义浮到 adapter 层，偏移恰好是评审想消除的"语义散落"。且 4 个环形队列已在正常工作、不在活跃变更路径，重写为零收益的 churn。
- **保留 trace 的同步 `LinkedHashMap`、仅建追加式存储**：拒绝。trace 流就是唯一真正分叉的存储（README 称 LRU 实为 FIFO、合并/淘汰/快照逻辑全在 service 客户端里），不建键式存储等于问题原样保留。

## Consequences

- 4 个环形 sender 的快照读仍为弱一致（如 `getMetrics()` 的 `new ArrayList<>(queue)` 不加锁），与键式存储的单锁快照行为不一致——这是有意的取舍，不归入本次范围。
- `LogFileTraceSegmentServiceClient` 的 `mergeLogIntoStatMap`（合并 + `snapshotForAlert` 防御拷贝 + `afterTraceMerged` 分发）重构为"`KeyedLocalStore.merge` 返回更新后的 V，客户端在锁外做防御式拷贝并分发告警"，webhook 分发不再占用存储锁。
- 评审的候选 B 交付面收窄为"仅键式 trace 存储 + LRU→FIFO 文档纠正"，这是对评审建议的范围修订。
