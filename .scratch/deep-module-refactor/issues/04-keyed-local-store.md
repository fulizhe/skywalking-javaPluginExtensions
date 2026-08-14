# 04 — 新增通用有界键式数据存储 KeyedLocalStore<K,V> 及独立测试

**What to build:** 一个可独立选用的通用键式有界存储：按 key 合并、FIFO 淘汰、单锁守护、快照容器独立。接口为 `put` / `merge(key, 合并函数)→更新后的值` / `snapshot():Map` / `size()`，语义与既有追加式环形队列并行存在但不强行统一接口。纯新增，不触碰现网路径。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 键式有界存储类提供 put/merge（返回更新后的值）/snapshot/size，FIFO 淘汰最旧插入
- [x] 写操作与快照读由单锁守护；快照返回独立容器拷贝，值对象以"只替换不原地修改"方式发布（merge 总是返回新构造值），快照中的值不再被后续写入改写
- [x] 容量与淘汰边界、merge 原子读改写与返回更新值、快照拷贝、并发写读均有独立测试
- [x] 新增存储不改变既有环形队列公开接口与行为
- [x] 新增模块测试 + 既有模块 `mvn test` 全绿（JDK 17 toolchain，字节码基线 8）
