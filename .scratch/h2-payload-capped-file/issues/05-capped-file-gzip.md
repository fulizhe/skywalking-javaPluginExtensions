# 05 — 环形文件·GZIP 编解码

**What to build:** 写块时对载荷 GZIP 压缩、读块时解压；往返与原始字节完全一致，块保持自包含。

**Blocked by:** 03 — 跨文件尾读写

**Status:** done

- [ ] `writeMessage` 内部压缩载荷；`readMessage` 返回解压后的原始字节
- [ ] 压缩往返一致（含较大与高压缩比 payload）
- [ ] 单元测试覆盖压缩往返
