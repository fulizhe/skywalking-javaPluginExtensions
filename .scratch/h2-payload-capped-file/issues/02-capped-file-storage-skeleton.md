# 02 — CappedFileStorage 骨架（定长文件 + 头 + 单块写读）

**What to build:** 新增一个自包含的环形封顶载荷文件：按配置创建/打开**固定大小**文件，持久化文件头（写游标 + 文件字节数），支持**写单个块**并返回该块的逻辑 id、按 id **读回**同一块。本票不含跨尾、不含淘汰、不含压缩。类头注释指向设计来源（Glowroot `CappedDatabase.java` permalink，注明"最小形态移植"）。

**Blocked by:** 01 — storage 子包重构

**Status:** done

- [ ] 初始化创建/打开定长文件并写入文件头（游标 + 大小）
- [ ] `writeMessage(bytes)` 返回块起始逻辑 id；`readMessage(id)` 读回原字节
- [ ] 单块不得超过整个文件大小（超出记错、丢弃，不崩、不外抛）
- [ ] 单元测试覆盖：写读往返、文件头持久化、超限块的拒绝
