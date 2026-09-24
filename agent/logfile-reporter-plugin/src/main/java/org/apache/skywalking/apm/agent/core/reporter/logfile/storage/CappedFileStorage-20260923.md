# CappedFileStorage 全景笔记

> 用途：H2 影子存储（`H2TraceSegmentStorage`）的**载荷环形文件**。header 行存 H2（内存表），
> payload 全量 JSON 存本文件（定长、环形、GZIP）。`H2.payload_id` 就是本类 `writeMessage` 返回的逻辑 id。
>
> 一句话：**16B 文件头（游标+尺寸） + 数据区里「8B 长度头 + GZIP 载荷」的块逻辑相连、物理取模绕圈覆盖**。

---

## 1. 设计来源

本类是 Glowroot `CappedDatabase.java` 的**最小形态移植**（去掉了 resize / isInTheFuture / 压缩率统计，载荷改为每块独立 GZIP）：

> https://github.com/glowroot/glowroot/blob/456b1910bbeeb152efd78103043d71c08b183975/agent/embedded/src/main/java/org/glowroot/agent/embedded/util/CappedDatabase.java
>
> 本项目内的姊妹篇：`docs/reference/glowroot-capped-database.md`（Glowroot 拆法的一手核实与"最小形态"依据）、`docs/adr/adr-03-capped-file-payload-for-trace-details.md`（本项目的决策与后果）。

---

## 2. 文件布局（以 `sizeBytes = 256` 为例）

```
物理偏移:  0                8               16                              255
          ┌────────────────┬────────────────┬───────────────────────────────────┐
文件头    │  currIndex(8B) │ sizeBytes(8B)  │           数据区 [16, 256)         │
          └────────────────┴────────────────┴  dataLen = 256-16 = 240 字节  ─────┘
                                               ↑ 物理位置 = 16 + (逻辑index % 240)
```

- **文件头 16B**（每次 `writeMessage` 都整体重写）：
  - `[0,8)`：`currIndex` — 单调递增的**逻辑写游标**，重开文件靠它续写（`openAndInit` 读取）。
  - `[8,16)`：`sizeBytes` — 冗余副本，`openAndInit` 读后即弃（以文件实际长度为准）。
- **数据区** = `sizeBytes - 16`，是块循环写入的空间。

---

## 3. 块格式

每块在**逻辑地址空间**连续占用 `8 + len` 字节：

```
逻辑地址 id:   [ id, id+8 )        [ id+8, id+8+len )
               ┌─────────────────┬─────────────────────────────┐
               │ len (8B, 大端)   │ gzip(payload) 共 len 字节     │
               └─────────────────┴─────────────────────────────┘
                ← writeMessage 返回的整体就是 payloadId（= 块起始逻辑 id）
```

- `len` 存的是 **`compressed.length`**（GZIP 后的字节数），不是原始 payload 长度。
- 单块占位 `8 + len` 必须 `≤ dataLen`，否则构造/写入抛 `IOException: payload too large`。

---

## 4. 逻辑地址 vs 物理地址（环形映射的核心）

两个地址系分离，是整个类最核心的决定：

| | 逻辑地址 | 物理地址 |
|---|---|---|
| 语义 | 「第几字节写过」的单调计数，**永不回绕** | 真实落盘位置 |
| 计算 | 写游标 `currIndex` 自己维护 | `pos(index) = 16 + (index % dataLen)` |
| 好处 | 过期判断变成一句算术：`id < currIndex - dataLen` 即已被覆盖 | 写满自然从数据区头覆盖最旧块 |

**示例**（`sizeBytes=256`，`dataLen=240`），三块先后写入：

```
逻辑:  [块A: 0..27] [块B: 28..75] [块C: 76..131] ...
物理:   A → 16..43   B → 44..91    C → 92..147
                              （都落在数据区内，尚未绕圈）
```

写满 240 字节后游标继续走，逻辑 240 → 物理 `16 + 240%240 = 16`，**新块从数据区头覆盖最旧块**：

```
currIndex = 268 时:
  最老幸存 id = max(0, 268-240) = 28   ← id < 28 的块已被新数据覆盖
  逻辑 240..267 的新块 → 物理 16..43    （原块A的位置）
```

### 4.1 把整个文件想象成一个环

**块起始逻辑 id 就是这块在整个"逻辑环"上占用的起始位置**（= 长度头所在位置，`writeMessage` 返回的 `payloadId`）：

```
currIndex（写游标）永远向前走，映射到物理是取模绕圈：
        逻辑 0          逻辑 dataLen=240
          │                │
  物理 16 ├──────────────→ ├──────────
         │   旧块（最老）    │ 新块覆盖到这里
         └────────────────┘  = 环绕回起点
```

每个块在逻辑环上占连续一段 `[id, id+8+len)`，`id` 就是这一段**起点的逻辑位置**。后续所有操作都拿这个 id 定位：`readMessage(id)` 从 `16 + (id % dataLen)` 起读 8 字节长度头，再读载荷。

⚠️ **关键别混淆：逻辑 id ≠ 物理位置**。id 是"环上第几个字节"的单调计数——互不重叠、**永不回绕**；物理位置只是 `16 + (id % dataLen)` 的取模结果。新块会覆盖旧块占用的同一物理区，但它们的逻辑 id 完全不同。**看物理位置是绕圈的，但每个块的身份（id）永远是唯一的、不回绕的。**

---

## 5. 字段速查（"全局变量"其实都是私有实例字段）

| 字段 | 作用 | 归属 |
|---|---|---|
| `file / sizeBytes / dataLen` | 配置态，构造后不再变 | 环形几何 |
| `lock` | 读写共用一把锁 | 并发 |
| `raf` | `RandomAccessFile` 句柄 | IO |
| `currIndex` | 唯一的写进度状态，每次写刷回头部 16B | 持久化状态 |
| `dirty` | 是否有未 fsync 的数据 | fsync 节流 |
| `writesSinceFsync` / `lastFsyncTime` | 每 100 写或满 1 秒 `force()` 一次 | fsync 节流 |

所有字段全 `private`，每实例一份；读写共用 `lock`（环形覆盖下读写竞争同一物理区，分开加锁反而要处理绕回竞态）。

---

## 6. `writeMessage(payload)` 流程 — 源码 :96

```java
public long writeMessage(final byte[] payload) throws IOException {
    if (payload == null) return -1L;              // ① 哨兵：null → -1
    final byte[] compressed = gzip(payload);      // ② 压缩在锁外（CPU 操作不占锁）
    synchronized (lock) {
        if (compressed.length + 8 > dataLen) throw ...;  // ③ 容量检查
        final long blockStart = currIndex;        // ④ id = 当前逻辑游标
        writeAt(blockStart, longToBytes(len));    // ⑤ 写 8B 长度头
        writeAt(blockStart + 8, compressed);      // ⑥ 写 GZIP 载荷
        currIndex += 8 + compressed.length;       // ⑦ 游标前进
        writeHeader();                            // ⑧ 刷 16B 文件头（游标持久化）
        dirty = true; maybeFsync();               // ⑨ 节流落盘
        return blockStart;                        // ⑩ 返回块 id
    }
}
```

**① null → `-1`**：约定哨兵。调用方（H2 `storeLog`）见到 `<0` 就把 `payload_id` 写 `NULL`——"没有载荷"和"载荷写入失败"统一走这条路。

**② 压缩放锁外**：GZIP 是纯 CPU、不碰文件，挪到锁外让写线程持锁时间只剩实际 IO。这是写路径刻意的优化（读路径反而没做，见 §8 的不对称）。

**③ 容量检查的精确含义**：`8 + compressed.length ≤ dataLen`——单块**必须能整个塞进数据区**。环形只解决"物理上跨文件尾"，不解决"块比整个圈还大"；超了直接抛 `IOException`，调用方 `recordError` 后 `payloadId` 保持 -1。

**④→⑦ 返回的 id 是逻辑地址**：`blockStart = currIndex`，即长度头所在的位置，载荷在 `id + 8`。

**跨尾写**（`writeAt` :171，一次写最多切两段）：

```java
long p = 16 + (index % dataLen);
while (remaining > 0) {
    int chunk = min(remaining, sizeBytes - p);   // 先写到文件尾
    raf.seek(p); raf.write(bytes, offset, chunk);
    p = HEADER_SKIP_BYTES;                        // 再绕回数据区起点续写
}
```
因为块 ≤ dataLen，跨尾**最多断一次**，两段循环必然写完。

**⑧ 每次写都持久化游标**：这是 `close / 重开` 测试的支撑——`openAndInit` 读回头部接着游标写。代价是每条消息多一次 16B 小写；但**`writeHeader` 不单独 fsync**，真正落盘靠 ⑨。

**⑨ fsync 节流**：`dirty` + 每 100 写或满 1 秒 `force()`。含义：崩溃可能丢"最后一次 fsync 之后"的块**和游标前进**——重开后游标偏旧，那些 id 读回 `id >= currIndex → null`。对影子存储是合理取舍（换写入吞吐）。

---

## 7. `readMessage(id)` 流程 — 源码 :122

```java
public byte[] readMessage(final long id) throws IOException {
    synchronized (lock) {                                // 全程持锁
        if (id < 0 || id >= currIndex) return null;             // ① 从未写过
        if (id < max(0, currIndex - dataLen)) return null;      // ② 已被覆盖（过期）
        byte[] lenBytes = readAt(id, 8);
        if (lenBytes == null) return null;
        long len = bytesToLong(lenBytes);
        if (len <= 0 || len + 8 > dataLen) return null;         // ③ 荒谬长度兜底
        byte[] compressed = readAt(id + 8, (int) len);
        if (compressed == null) return null;
        return gunzip(compressed);                              // ④ 解压返回
    }
}
```

**① 与 ② = 两级淘汰判定，返回 null 有不同语义**（调用方不区分，但值得知道）：
- `id >= currIndex`：**不存在**——id 没写过 / fsync 丢失后游标偏旧。
- `id < currIndex - dataLen`：**已过期**——环里只保留最近 `dataLen` 字节，更老的物理区已被新块覆盖。`isOverwritten()`（:155）用同一条算术，`queryTrace` 据此置 `payloadExpired=true`。
- ③ 长度荒谬：**防脏读兜底**——读到非本类写入的字节时 `len<=0` 或 `8+len > dataLen` 直接拒读，避免 `(int)len` 崩溃或读出巨量数据。

**② 的算术就是环形缓冲的全部**：`currIndex - dataLen` = 最老幸存块的逻辑起点，一次减法代替遍历。

---

## 8. 读写的不对称

| | write | read |
|---|---|---|
| 压/解压 | **锁外**（`gzip` 在 `synchronized` 前） | **锁内**（`gunzip` 在锁里返回） |
| 地址映射 | `writeAt`：跨尾切两段 | `readAt`：同样切两段（镜像实现） |
| 一致性边界 | 块写入+游标+文件头在**同一锁内原子** | 读方永远看不到"载荷写了、游标没走"的撕裂态 |

**留意**：读路径解压持锁，大载荷查询会阻塞写线程的 `storeLog`。当前读方是对账/查询（低频），影响可忽略；若将来读变热，把 `gunzip` 挪出锁是现成的优化位（和写侧 `gzip` 对齐）。

> 附注：`readAt` 实际**从不返回 null**（`readFully` 遇 EOF 抛异常），§7 里 ③⑤ 前的 null 检查是防御性死代码——无害，但严格说不可达。

---

## 9. 边角问题一览（"怎么解决的"索引）

| 边角问题 | 解法 | 源码 |
|---|---|---|
| 单块比整个数据区还大 | 容量检查，直接抛 IOException | :102 |
| 块碰到文件尾 | 物理切两段，绕回 `HEADER_SKIP_BYTES` 续写 | `writeAt`/`readAt` |
| 写满后旧数据怎么办 | 环形覆盖；过期判定 `id < currIndex - dataLen` | :127, :160 |
| 崩溃丢数据 | fsync 节流（100 写 / 1s），丢最近块换吞吐 | `maybeFsync`/`force` |
| 重开后从哪续写 | 头部 16B 持久化 `currIndex` | `writeHeader`/`openAndInit` |
| 读到外部/半初始化字节 | 长度合法性兜底，拒读返回 null | :135 |
| 读写并发 | 共用一把 `lock`，块写入原子 | `synchronized (lock)` |

---

## 10. 最终效果

- 单个定长文件，物理大小**恒定**（`getSizeBytes`，被覆盖的只是内容）；
- H2 侧永远只存"header 行"，大 JSON 载荷（GZIP 后）落在环形文件；
- 载荷被环淘汰 = 过期，但 header 仍在 → H2 侧 `payloadExpired=true`，**不脏读、不返回残缺数据**；
- 失败全部就地捕获（null / IOException → `recordError`），绝不向上抛——"监控只能是助力，不是阻碍"。

---

## 11. 与 H2 的联动（`H2TraceSegmentStorage`）

**写路径**（`storeLog` :302-310）：

```
H2 写 header 行前，先写载荷：
    payloadId = cappedStorage.writeMessage(GSON.toJson(log.toMap()).getBytes(UTF_8))
    成功 → ps.setLong(payload_id, payloadId)
    IOException → recordError("cappedWrite")，payloadId 保持 -1 → ps.setNull(payload_id)
```

**读路径**：

| H2 方法 | 用途 | 载荷过期/缺失的表现 |
|---|---|---|
| `snapshot()` :338 | 对账快照 | payload 缺失/过期 → **跳过该行，不脏读** |
| `queryTrace()` :418 | 按 traceId 取回链路 | `isOverwritten`/`readMessage==null` → `payloadExpired=true`，该段不出现 |
| `recentTraces()` :469 | 最近 N 条 header | `hasPayload && isOverwritten` → `payloadExpired=true` |
| `close()` :686 | 关停 | 先 `cappedStorage.close()`（含最后 `force()`）再关连接 |

**生命周期**：
- `H2TraceSegmentStorage` 构造时创建 `CappedFileStorage(cappedFile, cappedSizeBytes)`（失败 → `recordError("cappedInit")`，`cappedStorage=null`，H2 仍可用，只是所有 payload_id 为 NULL）；
- H2 `close()` 依序：`writerRunning=false` → join 写线程 → 排空队列 → 停 console → `cappedStorage.close()` → 关 H2 连接。

---

## 附：源码行号索引（文件可能演进，以类内注释为准）

| 部位 | 行号（写此文时） |
|---|---|
| `writeMessage` | :96 |
| `readMessage` | :122 |
| `getCurrIndex` / `isOverwritten` / `getSizeBytes` | :148 / :155 / :165 |
| `writeAt` / `readAt` 跨尾 IO | :171 / :185 |
| `maybeFsync` / `force` | :201 / :209 |
| `close` | :219 |
| `pos()` 逻辑→物理 | :83 |
| `writeHeader` / `openAndInit` | :76 / :61 |