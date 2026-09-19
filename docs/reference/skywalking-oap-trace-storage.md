# SkyWalking OAP 链路存储实现速览（一手核实）

> **用途**：本项目 H2 化的参考底稿 / wiki 沉淀。
> **核实基准**：SkyWalking **commit `520d531fff8d2ba9687ff8b9086b7e27033a82bb`**，逐文件打开源码确认，附 permalink。
> **已核实**：表结构如何定义、DDL 如何生成、链路 `segment` 表结构、TTL 机制。
> **未核实**（见 §6）：接收→落库写入管线、查询期按 traceId 组装、告警（alarm）实现。
> **勿凭印象**：本文之外关于 OAP 的说法，一律当未核实处理。

---

## 0. 一句话结论

1. **OAP 没有 `.sql` 建表文件**。表结构 = **Java Model 类 + 注解**（`@Stream` / `@Column` / `@SQLDatabase.*` / …），DDL 由 **`ModelInstaller`**（H2 用 `H2TableInstaller`）在启动时**运行时生成**。
2. **链路表是 `SegmentRecord`，表名 `segment`**（`INDEX_NAME = "segment"`）——**不是 `skywalking_segment`**。span **不单独成行**，整段 segment（含 spans）序列化进 `data_binary`。
3. **`service_id` / `service_instance_id` / `endpoint_id` 都是 `String`**——不是整数 inventory ID，**也不依赖 `service_inventory` 表**。
4. TTL 由 **`DataTTLKeeperTimer`** 驱动：**仅集群首节点**执行，按 `time_bucket` 删除，TTL 分 **record / metrics** 两类全局值（不是按 trace 级别）。

---

## 1. Schema 定义机制（model-driven）

**数据模型类**（如 `SegmentRecord`）通过注解声明表结构：

```java
@Stream(name = SegmentRecord.INDEX_NAME /* "segment" */, scopeId = ..., builder = ..., processor = ...)
public class SegmentRecord extends Record {
    @Column(name = "trace_id", length = 150)
    private String traceId;
    @Column(name = "data_binary", storageOnly = true)
    private byte[] dataBinary;
    // ...
}
```

**从注解到 `Model` 的过程**（`StorageModels`）：

```text
@Stream(name) / @Column(...) 等注解
      │  StorageModels.add(Class, scopeId, Storage, record)
      │    └─ 反射遍历字段（含父类，递归 retrieval）
      ▼
List<ModelColumn>  ──►  Model（name / columns / scopeId / record / superDataset / ...）
      │
      │  ModelInstaller.whenCreating(Model)   ← ModelCreator.CreatingListener
      ▼
isExists(model) ? 跳过建表 : createTable(model)
```

要点（均来自源码）：

- **表名来自 `@Stream(name = ...)`**（经 `Storage.getModelName()`）；`Model.getName()` 即物理表名。
- **列来自 `@Column`**；`ModelColumn` 携带 `storageOnly` / `indexOnly` / `dataType` / `length` / 及 ES/BanyanDB/SQL 各扩展。
- **继承生效**：`retrieval` 递归读父类字段（这就是 `SegmentRecord` 得到 `time_bucket` 的原因——`Record` 基类里声明）。
- **`Model` 是 `record` 还是 `metrics`** 会影响 TTL 取值（见 §4）。
- **`whenCreating`**：非 no-init 模式 → 表不存在则 `createTable`；`-Dmode=no-init` 下则**轮询等待**表出现（`sleep 3s`）。

> 对本项目的启发：这套"**代码定义 schema + 启动期建表**"是 OAP 的通用机制。我们极简，**不必引入整套 Model 框架**，按同一思路手写 `CREATE TABLE IF NOT EXISTS` 即可；但要保留**`time_bucket` + TTL** 这条主线。

**相关文件（`server-core`）**：
`oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/model/`
（`StorageModels.java`、`Model.java`、`ModelColumn.java`、`ModelInstaller.java`、`SQLDatabaseModelExtension.java`、`ColumnName.java` …）

---

## 2. H2 的 DDL 生成与类型映射

`H2TableInstaller extends ModelInstaller`，`createTable(Model)` 用 `SQLBuilder` 拼：

```sql
CREATE TABLE IF NOT EXISTS <model.getName()> (
  id VARCHAR(512) PRIMARY KEY,
  <column1> <h2Type>,
  <column2> <h2Type>,
  ...
)
```

Java → H2 类型映射（`H2TableInstaller.transform`）：

| Java 类型 | H2 类型 |
| --- | --- |
| `Integer` / `int` / `Layer` | `INT` |
| `Long` / `long` | `BIGINT` |
| `Double` / `double` | `DOUBLE` |
| `String` | `VARCHAR(length)` |
| `StorageDataComplexObject` | `VARCHAR(20000)` |
| **`byte[]`** | **`MEDIUMTEXT`** |
| `JsonObject` | `VARCHAR(length)` |
| `List<E>` | 递归按元素 `E` 映射 |

两个**关键**点：

- **`byte[]` → `MEDIUMTEXT`**（不是 BLOB）。所以 OAP 的 `data_binary` 在 H2 里是文本列。
- **H2 建表不建索引**：`H2TableInstaller.createTableIndexes(...)` 是**空实现**（源码注释明说 "H2 is for the demonstration only, so keep the logic as simple as possible"）。索引由 MySQL/PostgreSQL 等其它 dialect 的 installer 负责。
- `start()` 里做了列名覆盖：`overrideColumnName("value", "value_")`。

> 对本项目的启发：本项目若自建 H2 表并需要查询性能，**索引要自己加**（OAP H2 不管）；`data_binary` 用 `MEDIUMTEXT` 即可对齐。

**源码**：`storage-jdbc-hikaricp-plugin/.../jdbc/h2/H2TableInstaller.java`（同包另有 `H2StorageProvider.java`）。资源目录 `storage-jdbc-hikaricp-plugin/src/main/resources/` 下**只有 `META-INF`，没有任何 `.sql`**。

---

## 3. 链路表 `SegmentRecord`（逐字段）

```java
@SuperDataset
@Stream(name = SegmentRecord.INDEX_NAME /* "segment" */, scopeId = DefaultScopeDefine.SEGMENT,
        builder = SegmentRecord.Builder.class, processor = RecordStreamProcessor.class)
@SQLDatabase.ExtraColumn4AdditionalEntity(additionalTable = "segment_tag", parentColumn = Record.TIME_BUCKET)
@SQLDatabase.Sharding(shardingAlgorithm = TIME_SEC_RANGE_SHARDING_ALGORITHM,
        dataSourceShardingColumn = "service_id", tableShardingColumn = "time_bucket")
@BanyanDB.TimestampColumn(SegmentRecord.START_TIME)
public class SegmentRecord extends Record { ... }
```

**列（含基类 `Record`）**：

| 常量 | 列名 | Java 类型 | `@Column` 关键属性 | H2 类型 |
| --- | --- | --- | --- | --- |
| `SEGMENT_ID` | `segment_id` | `String` | `length = 150` | `VARCHAR(150)` |
| `TRACE_ID` | `trace_id` | `String` | `length = 150`，`@ElasticSearch.Routing` | `VARCHAR(150)` |
| `SERVICE_ID` | `service_id` | `String` | （默认长度） | `VARCHAR(默认)` |
| `SERVICE_INSTANCE_ID` | `service_instance_id` | `String` | `length = 512` | `VARCHAR(512)` |
| `ENDPOINT_ID` | `endpoint_id` | `String` | `length = 512` | `VARCHAR(512)` |
| `START_TIME` | `start_time` | `long` | — | `BIGINT` |
| `LATENCY` | `latency` | `int` | — | `INT` |
| `IS_ERROR` | `is_error` | `int` | — | `INT` |
| `DATA_BINARY` | `data_binary` | `byte[]` | **`storageOnly = true`** | **`MEDIUMTEXT`** |
| `TAGS` | `tags` | `List<String>` | `indexOnly = true`，`length = Tag.TAG_LENGTH` | `VARCHAR(...)` |
| （基类 `Record`） | `time_bucket` | `long` | — | `BIGINT` |

**其它要点**：

- **`StorageID id() = segment_id`**：实体的主键 id 值取 `segment_id`。
- **span 打包**：`data_binary` 是 `storageOnly` 的 `byte[]`，装的是**整段 segment 的序列化二进制**（含其 spans）——所以"按 traceId 查整条链路"时，span 不是行，**无法直接用 SQL 过滤 span 内部字段**。
- **附加表 `segment_tag`**：`service_id` 与 `tags` 通过 `@SQLDatabase.AdditionalEntity(additionalTables = {segment_tag})` 落到独立表 `segment_tag`（`service_id` 处 `reserveOriginalColumns = true`，即原表仍保留该列）；类级 `ExtraColumn4AdditionalEntity` 把 `time_bucket` 带入该附加表。
- **分片**：`@SQLDatabase.Sharding(... TIME_SEC_RANGE_SHARDING_ALGORITHM ... dataSource=service_id, table=time_bucket)`——按时间范围分表/分库的算法。
- **未见独立 ref 表**：`SegmentRecord` 中**只有 `segment` 与附加表 `segment_tag`**；**没有 `skywalking_segment_ref` 之类的引用表**。跨 segment 的引用关系**应在 `data_binary` 载荷内部**（**待进一步核实**序列化内容）。

**基类 `Record`**（`.../core/analysis/record/Record.java`）只做一件事：

```java
public abstract class Record implements StorageData {
    @Getter @Setter @Column(name = TIME_BUCKET)
    private long timeBucket;
}
```

---

## 4. TTL 机制（`DataTTLKeeperTimer`）

`public enum DataTTLKeeperTimer { INSTANCE; ... }` —— 单例定时器：

- **调度**：`start(moduleManager, config)` 建单线程 `ScheduledExecutorService`，`scheduleAtFixedRate(delete, period, period, MINUTES)`，`period = CoreModuleConfig.dataKeeperExecutePeriod`。
- **仅首节点执行**：每个 OAP 节点都会启动该定时器，但 `delete()` 里用 `ClusterNodesQuery.queryRemoteNodes()` 排序后判断——**只有节点列表中的第一个（且 `isSelf()`）才真正删除**，其余直接 `return`（分布式下的单写者）。
- **删除逻辑**：取 `IModelManager.allModels()`，逐个：
  - 非时间序列（`!model.isTimeSeries()`）→ 跳过；
  - 调 `IHistoryDeleteDAO.deleteHistory(model, Metrics.TIME_BUCKET, ttl)`；
  - `ttl = model.isRecord() ? recordDataTTL : metricsDataTTL`（两个值来自 `CoreModuleConfig`）。
- **语义**：TTL 是**两类全局值**（record / metrics），**按 `time_bucket` 列**删除；**不是**按 trace 级别（normal/slow/error）区分。ES 等存储可提供自己的 TTL 实现，但都由这个定时器驱动。

> 对本项目的启发：我们计划按 `trace_level` 分级 TTL，**是有意的增强/偏离**——OAP 没有这种分级。可借鉴的是"**定时器 + `time_bucket` 水位线删除**"这条骨架；实现时按我们的 `trace_level` 拆分 SQL 即可（注意 H2 不支持 MySQL 的 `DATEADD`，截止时间在 Java 侧算好参数化传入）。

---

## 5. 与本项目（H2 化）的对照

| 维度 | OAP `520d531` 实际 | 本项目取舍 |
| --- | --- | --- |
| Schema 定义 | 代码注解 + 运行时建表 | 极简，手写 DDL（不必引入 Model 框架） |
| 表名 | `segment`（+ `segment_tag`） | 自建库，命名自定；**不再用 `skywalking_segment`** |
| service/instance/endpoint | **String** | 用 String → **与 OAP 一致，不是偏离** |
| span 存储 | 整段进 `data_binary`（`byte[]`→`MEDIUMTEXT`） | 同思路；载荷用本项目 `Log.toMap()` JSON（OAP 用 protobuf） |
| `data_binary` H2 类型 | `MEDIUMTEXT` | 对齐 `MEDIUMTEXT`（非 BLOB） |
| TTL | record/metrics 两档，`time_bucket` 删除，首节点执行 | 按 `trace_level` 分级（增强）；定时器骨架借鉴 |
| 索引 | H2 **不建**（演示级） | 需查询性能 → **自己建索引** |

---

## 6. 未核实 / 待补（下一轮研究）

1. **接收→落库写入管线**：`SegmentObject` 经 gRPC handler → 内存队列/`DataCarrier` → 哪个 DAO、怎样 batch 写 `segment` 行？（对应本项目 `consume` 的"异步 + 批量"）
2. **查询期组装**：`queryByTraceId` 如何把多条 `segment` 拼成整条链路？是否用 `data_binary` 内的 refs？**孤 segment**（无父引用）如何展示/处理？
3. **告警（alarm）实现**：触发源是**指标**还是**链路**？规则 / 通知如何组织？（决定本项目告警是否整体下移、`accept(...)` 返回值）

---

## 7. 源码锚点（@ `520d531`）

- 链路模型 `SegmentRecord`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/manual/segment/SegmentRecord.java>
- 基类 `Record`（`time_bucket`）：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/record/Record.java>
- `StorageModels`（注解→Model）：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/model/StorageModels.java>
- `ModelInstaller`（建表基类）：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/model/ModelInstaller.java>
- `H2TableInstaller`（H2 DDL + 类型映射）：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-storage-plugin/storage-jdbc-hikaricp-plugin/src/main/java/org/apache/skywalking/oap/server/storage/plugin/jdbc/h2/H2TableInstaller.java>
- `DataTTLKeeperTimer`（TTL 定时器）：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/ttl/DataTTLKeeperTimer.java>
- `storage/model` 目录：
  <https://github.com/apache/skywalking/tree/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/storage/model>
