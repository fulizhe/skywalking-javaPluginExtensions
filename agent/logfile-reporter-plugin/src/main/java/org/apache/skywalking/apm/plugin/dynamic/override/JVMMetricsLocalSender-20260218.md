# JVMMetricsLocalSender 优化总结

本文档记录针对 `JVMMetricsLocalSender` 及关联配置、文档所做的优化，便于后续维护与 Code Review 回溯。

---

## 1. 文案与注释

- **类注释**  
  - 明确说明：本地实现、数据不发给 OAP、转为结构化数据后写入本地有界 LRU 缓存、供日志上报/前端等按需读取。  
  - 修正 “Refer To” 为 “Refer to”，并规范 `</p>` 闭合。
- **异常日志**  
  - 将 “send JVM metrics to Collector fail.” 改为 “collect JVM metrics to local cache fail.”，与实际行为（写本地缓存）一致。
- **字段与方法注释**  
  - `maxMetricsDataSize`：注明由 `LogFileReporterPluginConfig.Plugin.JvmMetricsLocal` 配置及默认值。  
  - `jvmMetricsDataCache`：注明借鉴 Druid、同步包装保证并发安全。  
  - `getMetrics()`：注明返回快照，避免调用方遍历时与写线程并发修改导致异常。

---

## 2. 线程安全（jvmMetricsDataCache）

- **缓存容器**  
  - 使用 `Collections.synchronizedMap(LinkedHashMap(...))` 包装，保证多线程读写安全。  
  - 成员类型由 `LinkedHashMap` 改为 `Map`，仅暴露接口。
- **getMetrics()**  
  - 在 `synchronized (jvmMetricsDataCache)` 内构造 `new ArrayList<>(jvmMetricsDataCache.values())` 并返回，避免调用方遍历时发生 `ConcurrentModificationException` 或读到半写状态。
- **匿名类**  
  - `removeEldestEntry` 增加 `@Override`；容量上限通过 `final int maxSize` 传入，避免闭包持有可变字段。

---

## 3. 拆分 run() 逻辑

将原先集中在 `run()` 中的“拉取队列 → 构建 Collection → 转 Map → 写缓存”拆成小方法，便于阅读与单测：

| 方法 | 职责 |
|------|------|
| `run()` | 流程编排：drain → build collection → build map → cache；外层 try/catch 与空 buffer 判断。 |
| `drainQueueToBuffer()` | 从队列一次性 drain 到 `LinkedList`，返回 buffer。 |
| `buildJVMMetricCollection(List)` | 将 metric 列表封装为 `JVMMetricCollection`，补全 service/instance。 |
| `buildMetricCollectionMap(JVMMetricCollection)` | 将整份 collection 转为顶层 Map（service、serviceInstance、metrics 列表）。 |
| `buildSingleMetricMap(JVMMetric)` | 单条 JVMMetric → Map（time、cpu、memory、memoryPool、gc、thread、clazz）。 |
| `buildMemoryList` / `buildMemoryPoolList` / `buildGcList` | 各子列表的 protobuf → Map 列表。 |
| `buildThreadMap(Thread)` / `buildClassMap(Class)` | 线程、类相关指标 → Map。 |
| `cacheMetrics(collection, map)` | 按 “serviceInstance_timestamp” 生成 key，写入 `jvmMetricsDataCache`。 |

转换逻辑集中在上述方法内，`buildSingleMetricMap` 及以下均可单独做单元测试。

---

## 4. maxMetricsDataSize 配置化

- **集中配置**  
  - 在 `LogFileReporterPluginConfig` 中新增 `Plugin.JvmMetricsLocal`，内含 `MAX_METRICS_DATA_SIZE`（默认 1000），并加注释说明用途与 LRU 淘汰。
- **使用方式**  
  - 在 `JVMMetricsLocalSender.prepare()` 中读取 `LogFileReporterPluginConfig.Plugin.JvmMetricsLocal.MAX_METRICS_DATA_SIZE`。  
  - 若配置为 `null` 或 ≤0，则使用默认 1000。  
  - 使用 `final int maxSize` 传入匿名 `LinkedHashMap`，避免在 `removeEldestEntry` 中引用可变实例字段。
- **效果**  
  - 去除 Magic Number，与插件其他配置（如 `MAX_LOG_SIZE`）一起在 `LogFileReporterPluginConfig` 中集中管理。

---

## 5. 文档与配置说明（README）

- **项目说明**  
  - 补充“监控数据缓存”与“JVM 指标本地化”两条，说明参考 Druid、线程安全、以及由 `JVMMetricsLocalSender` 写本地缓存的用途。
- **配置项**  
  - 说明配置由 `LogFileReporterPluginConfig` 统一管理；用表格列出 `max_log_size` 与 `max_metrics_data_size` 的说明、配置键示例、默认值；增加 JVM 指标缓存条数配置示例。
- **相关注释与代码位置**  
  - 新增小节，说明 `JVMMetricsLocalSender` 类注释、`maxMetricsDataSize` 与配置的对应关系、`LogFileReporterPluginConfig` 中 `JvmMetricsLocal` 的注释、以及 `getMetrics()` 快照的注释位置，便于从 README 反查代码。

---

## 后续可做（未在本轮实现）

- Map 的 key 常量化或使用 DTO 替代 `Map<String, Object>`，提升类型安全与可读性。  
- 对 protobuf 消息字段做空值/默认值防护（若运行环境存在旧版本或异常数据）。  
- 为 `buildSingleMetricMap`、`buildMetricCollectionMap` 等编写单元测试。
