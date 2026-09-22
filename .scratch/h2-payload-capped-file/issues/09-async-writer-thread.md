# 09 — 独立写线程 + 有界队列 + close 排空

**What to build:** 写入改为异步：`accept` 仅把 segment 放入**有界队列**即返回（零 H2/磁盘 I/O），由**独立单写线程**排空队列、落环形与 H2；`close()` 时停止接收、排空后收尾。

**Blocked by:** 07 — H2 schema 改 + 写路径接入环形

**Status:** done

- [ ] `accept` 只入队即返回，不进行任何 H2/磁盘 I/O
- [ ] 写线程单写者落环形 + H2；顺序为先 payload、后指针
- [ ] `close()` 停止并入队排空后收尾（flush + fsync）
- [ ] 端到端对账仍零差异
