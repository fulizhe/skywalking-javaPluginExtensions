# 06 — 环形文件·fsync / close / 并发

**What to build:** 运行期按阈值 fsync、`close()` 时 flush + fsync；读与写共用一把锁，读线程与写线程并发时不产生错误数据。

**Blocked by:** 04 — 覆盖淘汰与过期；05 — GZIP 编解码

**Status:** done

- [ ] `close()` 前 flush + fsync；close 后未覆盖的 id 仍可读（重开文件）
- [ ] 运行期按"每 N 次写或每 T 毫秒"fsync（阈值用常量）
- [ ] 并发读写测试无异常、无脏读
- [ ] 单元测试覆盖 close 与并发
