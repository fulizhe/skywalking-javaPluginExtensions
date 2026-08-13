# 05 — trace 流接入 KeyedLocalStore，webhook 分发移出存储锁

**What to build:** trace 分段落地缓存从同步 LinkedHashMap 换为键式有界存储（traceId → 映射）：同一 traceId 的按段合并、容量上限淘汰与对外快照形状（data[traceId].logs）与之前完全一致；合并接口返回更新后的值，合并采用"只替换不原地修改"（remapper 返回新构造值，已发布值不再被改写），客户端在锁外直接触发告警分发，webhook 网络 I/O 不再占用存储临界区。demo-app 验证回路整体回归。

**Blocked by:** 04

**Status:** done

- [x] trace 落地缓存替换为键式存储实例，容量沿用现配置与默认值
- [x] 同一 traceId 并发/先后分段合并不丢段、结构不变；对外快照形状 data[traceId].logs 保持
- [x] 告警分发在存储锁外执行（合并值即新构造值，锁外直接分发、不占临界区）
- [x] trace 无独立测试的既有缓存逻辑改为经存储单元覆盖，消费侧行为断言不变
- [x] demo-app 验证回路回归：trace 合并、快照形状、告警 webhook 均正常；插件 `mvn test` 全绿
