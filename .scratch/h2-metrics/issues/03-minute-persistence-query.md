# 03 — 分钟表落库与历史查询

**What to build:** 把翻转后的分钟桶持久化到 H2 内存模式表（各 endpoint 与全局 `"*"` 行），并提供按 endpoint / 时间桶范围的查询；写入幂等（重复翻转同键覆盖，不重复计数）。

**Blocked by:** 02.

**Status:** ready-for-agent

- [x] 建表 / 唯一索引 / `MERGE` / 查询语句落地（结构见统一方案 §5.3，含 `sample_count` / `update_at`）。
- [x] `storeMetrics` 批量幂等 upsert；`queryMetrics(condition)` 支持 endpoint（含 `"*"`）/ fromBucket / toBucket / limit，超限截断并标记。
- [x] 单测：MERGE 幂等、条件查询、桶范围、limit 截断。
- [x] H2 关闭 / 初始化失败时 `queryMetrics` 返回空、不报错。
