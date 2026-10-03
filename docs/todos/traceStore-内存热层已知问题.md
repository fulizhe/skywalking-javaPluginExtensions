# 内存热层（`KeyedLocalStore traceStore`）的已知问题

> **状态（2026-10-03）**：**未修，仅记录**。这些问题在给 self-stat 页补「⑦ 内存热层」面板时
> 顺带查清，**当次刻意只做展示、没改存储实现**——`traceStore` 是历史实现，2.0.0 未发布、
> 正处过渡期，改它的收益/风险比现在不划算。
>
> **定位**：问题清单 + 判据，不是 spec。修哪条、怎么修见 §6。
>
> **为什么现在值得写下来**：热层是「H2 之外还有一份全量副本」这件事，第一次在页面上被
> 显式摊开（存活 trace 数 / 段数 / 覆盖时间段）。一旦看得见，这四个问题就会被人问。

---

## 1. 关切与结论先行

| # | 问题 | 后果 | 现在有多痛 |
| --- | --- | --- | --- |
| P1 | 容量按 **key 条数**限，不按字节限；单条 trace 的 `logs` 列表无上限 | **唯一能 OOM 的地方**；且每次 merge 全量复制列表，累积 O(n²) | 中——默认 1000 条 trace 短期无害，一条超长 trace 可涨到 GB |
| P2 | FIFO 按**插入序**淘汰，会把一条**还在写入**的 trace 切成残缺，且**无法识别** | 对账假 diff（内存源比 H2 少一截）；残缺 trace 与短 trace 长得一样 | **高**——只要开 `compare_debug` 就会持续产生假阳性 |
| P3 | 读一个 key 要 `snapshot()` 复制整个 store | 每次调用分配 N 个节点 + 一次全锁 | 低——现状调用频率低 |
| P4 | 淘汰/分裂**零埋点** | 回答不了「内存源少了多少、为什么」 | 中——排查时没有证据 |

**分界线的判据：谁拥有这个问题的信息、谁能改它。** P1~P4 全在 agent 侧——应用层看不到
这些语义，也改不了。唯一真属于应用层的是「一条 trace 允许多少段算多」：**agent 能给默认闸门，
但阈值只有业务知道**（见 §6）。

## 2. 容量口径的隐患（新发现，P5）

`traceStore` 的容量直接复用 `max_log_size`（默认 1000，`LogFileReporterPluginConfig.java:10`）：

```java
// LogFileTraceSegmentServiceClient.java:750-751
final int maxSize = this.maxLogSize;
traceStore = new KeyedLocalStore<String, Map<String, Object>>(maxSize);
```

而 `max_log_size` 的原意是「**日志/上报条数**上限」，在 `prepare()` 里被两个用途共用：

| 用途 | 真实需要的是 |
| --- | --- |
| 热层 trace 条数 | trace **条数**（一条 trace 含 1~3 个 segment） |
| 日志缓存条数 | **段**条数 |

两者量纲差 1~3 倍，当前恰好相等纯属巧合。调 `max_log_size` 想改的是哪个？说不清。
**这是过渡期遗留的口径复用，2.0.0 定版前应该拆开。**

## 3. P1：容量按条数限，单条无字节上限

`KeyedLocalStore` 的上限判据是 **key 数量**：

```java
// KeyedLocalStore.java:36-38
protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
    return size() > maxSize;
}
```

但每个 value 里的 `logs` 列表**只增不减**：

```java
// LogFileTraceSegmentServiceClient.java:1217-1219
final List<Map<String, Object>> appendedLogs = new ArrayList<Map<String, Object>>(logs);
appendedLogs.addAll(incomingLogs);
combined.put("logs", appendedLogs);
```

⇒ `max_log_size=1000` 的含义是「1000 **条 trace**」，不是「1000 **段**」。一条长事务 /
重试 / 死循环的 trace 可以在**一个 key** 里无限膨胀。

附带 O(n²)：每次 merge 都 `new ArrayList<>(logs)` 复制全表，一条 k 段的 trace 累计复制
k(k+1)/2 个元素。

> 注：`merge` 采用「只替换不原地修改」的发布方式（remapper 总是返回新值），
> 这让已发布快照不会被后续写入触及——**这个设计是对的**，代价就是每次复制。

## 4. P2：FIFO 静默把 trace 切成残缺（**最该修的一条**）

`LinkedHashMap` 是**插入序**（构造器第三参 `false`，`KeyedLocalStore.java:32`），
淘汰最旧**插入**的 key。场景：

1. trace T 的第 1 段进来 → key T 占位（在队首）；
2. 此刻 store 满了 → T 被淘汰；
3. T 的第 2 段进来 → `merge` 时 `existing == null` → **重新 `put`**（`:68-71`）
   → 新条目插到**队尾**；
4. 结果：T 在 store 里**只剩第 2 段及以后**，第 1 段永久丢失，**没有任何标记**。

⇒ **残缺 trace 与一条天生就短的 trace 无法区分。** 对账页把它读成「内存源比 H2 少一截」
= **假 diff**。

自stat 页已在口径说明里写明这一点（面板 ⑦ 底部），但**只是说明，没有修复**。

## 5. P3 / P4：读放大与零埋点

**P3** 读一个 key 复制整个 store：

```java
// LogFileTraceSegmentServiceClient.java:287
final Map<String, Object> entry = traceStore.snapshot().get(traceId);
// KeyedLocalStore.java:92 → new LinkedHashMap<>(entries)
```

浅拷贝（value 按引用共享），但仍分配 N 个节点并抢一次全锁。`KeyedLocalStore` 没有
`get(key)`，也没有迭代器出口——**唯一读口就是 `snapshot()`**。给 self-stat 补面板时
本来就必须付这一次拷贝（见 §7），所以顺手加 `get(key)` 的收益不高。

**P4** `getSelfStat()` 里有 carrier drops / storage drops / orphan / backlog / capped 各项，
**唯独没有 traceStore 的淘汰或分裂计数**。结果是：能回答「H2 少了数据」，
回答不了「内存影子源少了多少、为什么」。

## 6. 如果要修，按这个顺序

| 序 | 修什么 | 改动面 | 判断 |
| --- | --- | --- | --- |
| 1 | **P4 埋点**（淘汰计数 + 分裂计数） | 只加计数，不改语义 | **先做**：没有度量就无法判断 P1/P2 值不值得修，也是 P2 的验收前提 |
| 2 | **P2 标记残缺**（淘汰时记 `truncated=true`） | 需 `KeyedLocalStore` 支持淘汰回调 | **其次**：先让假 diff 变成可解释的真 diff |
| 3 | **P1 单条闸门**（超 N 段截断并标记） | 写入路径 + 新配置 | 阈值需业务给（§1 末），**先问再定** |
| 4 | P5 拆开容量配置 | 配置层 | 2.0.0 定版前顺手做，避免继续误导 |
| 5 | P3 加 `get(key)` | `KeyedLocalStore` | **最后**：调用频率低，收益最小 |

**明确不建议**：动淘汰顺序（LRU / 按完成时间）。热层是「最近写入的原始数据」，
按插入序淘汰是**符合语义**的；改成 LRU 只会让「按 id 删」的类 H2 那套 staleness 问题
（见 `h2化-trace写路径分配与CPU优化.md` 记录的同类坑）在内存层重演。

## 7. 已完成的替代方案（本次实际做的）

**不动存储，只加展示。** self-stat 页新增面板 ⑦，把热层里存的东西摊开：
存活 trace 数 / 段数 / span 数 / 覆盖时间段 / 量级估算字节，并**与 H2 窗口并排对比**
（热层窗口短得多是 `max_log_size=1000` 的预期结果，不是故障）。

关键取舍：**这一层用「读时派生」，刻意不照搬 H2 那套增量水位。**

| | H2 | traceStore |
| --- | --- | --- |
| 取最老时间 | `MIN()` 中位 23ms / p90 44ms（无索引全表扫） | 数据已在堆里 |
| 实现 | 增量水位 + 定期主键 seek | **遍历一次快照**（约 1 万次 hash 查找，亚毫秒级） |
| 理由 | 遍历不可接受 | 遍历比扫表便宜得多 |

派生的额外好处：**快照即真相**，结构上不可能出现 H2 侧那个「增量 min 不带删除补偿、
残留指向已淘汰行」的 staleness bug。代价是读口要为 `hotLayer` 付一次 `snapshot()`
浅拷贝——已写进 `getSelfStat()` 的 javadoc（原注释声明「不加存储锁」，现在不再成立）。

口径差异已在页面写明：热层时间取自 `spans[i].startTime`（`Log` **没有**段级时间戳），
是「有 span 活动的时间范围」，与 H2 的「段起始时间范围」**不是同一口径**，两者不可互算。

## 8. 相关位置

- `LogFileTraceSegmentServiceClient.java:85`（字段）、`:750-751`（容量口径复用）、
  `:287`（读放大）、`:1205-1228`（merge + O(n²)）、`:431`（`getSelfStat`）、
  `hotLayerSnapshot()`（本次新增的静态纯函数）
- `KeyedLocalStore.java:32-39`（插入序 + key 数上限）、`:63-83`（merge）
- `LogFileReporterPluginConfig.java:10`（`MAX_LOG_SIZE`）
- `docs/todos/h2化-trace写路径分配与CPU优化.md`（H2 侧同类 staleness 坑）