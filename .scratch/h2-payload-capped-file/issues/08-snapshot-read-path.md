# 08 — snapshot 读路径接入环形

**What to build:** 快照改为按指针从环形**回读解压**后组装，契约与旧 `data[traceId].logs` 完全一致；指针失效（payload 已过期）时跳过 payload 但**保留 header 语义**、不抛异常；新旧对账零差异。

**Blocked by:** 07 — H2 schema 改 + 写路径接入环形

**Status:** done

- [ ] `snapshot()` 经环形回读 + 解压组装，字段与旧契约逐字段一致
- [ ] payload 过期时返回 header、不抛异常（协议可行则标记过期）
- [ ] 开启对比后新旧对账零差异
- [ ] 单元测试覆盖命中与过期两态
