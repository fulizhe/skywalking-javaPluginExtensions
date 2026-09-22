# trace 明细载荷拆为「H2 header/索引 + 环形封顶文件（指针）」

Phase 1（`.scratch/h2-trace-storage/spec.md`）把整段 segment JSON 存进 H2 `trace_segment.data_binary`（`MEDIUMTEXT`），对齐 OAP、实现最简。但 H2 MVStore 是日志结构：**行被 DELETE 后空间不还给 OS**（`.mv.db` 涨了不缩；在线自动压缩只复用空间、稳定在高水位；真正缩容需 `SHUTDOWN COMPACT`，有停写窗口），且 `data_binary` 是可变长大字段、**没有硬上限**。这与 Glowroot 用 `CappedDatabase` 要治的病同源（issue #755：`data.h2.db` 涨到 31G，见 `docs/reference/glowroot-capped-database.md`）。

**决策**：把明细 payload 移出 H2，照 Glowroot 拆法的**最小形态**：

- H2 `trace_segment` **删除 `data_binary`、新增 `payload_id BIGINT`**，只留 header/索引列（trace_id / segment_id / service / service_instance / endpoint / start_time / end_time / latency / is_error / trace_level / time_bucket）。
- 新增 `CappedFileStorage`：**单个固定大小环形封顶文件**（默认 **128MB**），块格式 `⟨len:8B⟩⟨gzip(payload)⟩`，逻辑索引 `currIndex`，写满**覆盖最旧**；逻辑 id 被覆盖后读回 `null`（过期）。**去掉** Glowroot 的 resize / `isInTheFuture` / 压缩率统计（类头注释指向其设计来源）。
- **先写 payload、后写指针**：payload 落环形拿到 id，再 INSERT H2 行存 `payload_id`；读侧按指针回读解压，过期则跳过 payload 但 header 语义保留。
- 写入改为**异步**：`accept` 只入有界队列（满则丢弃 + 计数），**独立写线程**落环形与 H2；`close()` 排空 + fsync。
- **双轨保留**：H2 行按时间（后续 TTL）/ 环形文件按空间（硬封顶）；两套互不依赖。
- storage 相关类收敛进独立子包 `...reporter.logfile.storage`。

## Considered Options

- **维持 H2 单列 payload + 低频 `SHUTDOWN COMPACT`**：拒绝。无硬上限，且真正缩容需停写窗口；收窄后量级虽小，但作者明确要"磁盘可预测"。
- **全盘照搬 Glowroot `CappedDatabase`（含 resize / future / 统计 / LZF 压缩）**：拒绝。最小形态已满足"硬上限 + 按 id 点读"；少搬即少维护（个人项目，简单优先）。
- **复用作者自研的 `cat-client-local`（本地 CAT 存储）承接明细**：拒绝。它**无 retention/上限**（需自己补）、数据模型是 CAT message tree（需从 SkyWalking segment 转换、引入双模型），集成与维护成本高于最小环形文件。
- **指标改用 rrd4j**：不在本 ADR 范围（属 Phase 5 metrics；且 RRD 无法出真分位数）。capped 文件不用 rrrd。

## Consequences

- 磁盘上限 = H2（小 header，可变）+ 环形（**硬封顶**），可预测；H2 不再被大 CLOB 撑大。
- **payload 与 header 两段分家**：payload 可能比 H2 行更早被覆盖 → 读到"过期"（header 在、payload 不在），读侧降级而非报错。
- 环形**无按条 TTL**：只按空间淘汰；H2 行的 TTL 与它互不依赖。
- `mem` 模式下 H2 进程结束即空、环形落盘：重启后可能出现"H2 无行、环形有孤儿数据"，属预期；Phase 3 切 `file` 后消失。
- 写队列满会**丢弃**（计数可观测 `writeQueueDropped`），承接 Phase 1 的"同步写"临时简化。
- 使用侧新增查询门面（`queryTrace` / `recentTraces`，与旧 `data[traceId].logs` 同契约），是 Phase 4「内存优先、miss 再 H2」的种子。

## 参考

- 规格：`.scratch/h2-payload-capped-file/spec.md`
- 一手拆法参考：`docs/reference/glowroot-capped-database.md`（§5 最小形态 + 照抄的坑）
- 背景设计：`docs/todos/h2化-统一方案.md` §4/§5
- Phase 1（被本 ADR 演进）：`.scratch/h2-trace-storage/spec.md`
