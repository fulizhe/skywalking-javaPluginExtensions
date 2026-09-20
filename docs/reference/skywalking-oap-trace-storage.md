# SkyWalking 链路存储实现速览（Agent 上报 → OAP 落库 → 查询组装 → 告警）

> **用途**：本项目 H2 化的参考底稿 / wiki 沉淀。
> **核实基准**：
> - **OAP**：`apache/skywalking` @ `520d531fff8d2ba9687ff8b9086b7e27033a82bb`（= 镜像 `apache/skywalking-oap-server:9.4.0`）。
> - **Java Agent**：`apache/skywalking-java` @ `47ff1e82fad048c64cb73b83ee841941c279c482`（main）。
> **证据类型**：`【源码】`= 打开上述 commit 源码确认；`【实测】`= OAP 9.4 + H2 实验环境实测（`E:\gitRepository\_allScriptDir\skywalking-oap`，demo-tomcat 真实链路；原始记录见该目录 `OAP-TRACE-STORAGE.md`）。
> **已核实**：schema 定义机制、`segment` 表结构、H2 DDL/类型映射、TTL；Agent 上报路径；接收→落库写入管线；查询期组装；告警（alarm）实现。
> **未核实**（见 §10）：批量落库 worker 细节、写侧 Base64 编码层、告警规则/MQE 全貌、集群模式差异。
> **对照**：Glowroot 侧（本地 APM、内存优先）见 `glowroot-trace-storage.md`。
> **勿凭印象**：本文之外关于 SkyWalking 的说法，一律当未核实处理。

---

## 0. 一句话结论

1. **写入：一条 `SegmentObject` = 一行 `segment`**，整段（含 spans、含跨段 refs）序列化进 `data_binary`；**写入期不按 traceId 合并**。
2. **查询：按 `trace_id` 取出该 trace 的所有 `segment` 行，逐行反序列化 `data_binary`，用 span 内的 `refs` 拼父子**——组装发生在查询期（service 层），**没有 `segment_ref` 表**。
3. **孤 segment 不丢**：找不到父的 span 被当作"根"，多根按开始时间排序展示。
4. **Agent 侧内存队列只是传输缓冲**：`DataCarrier` 满或 gRPC 通道断开即丢弃，**不可查询**。
5. **OAP 没有"近期内存热层"**：按 traceId 的查询直接走存储；**告警是指标驱动**（聚合指标落库后评估），不是 trace 级判定。
6. OAP 的 H2 存储**不建索引**（演示级）；本项目要查询性能须**自己建索引**。

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
- **没有独立 ref 表（已核实，见 §7）**：`SegmentRecord` 中**只有 `segment` 与附加表 `segment_tag`**；跨 segment 的引用关系（`refs`）在 `data_binary` 载荷内部，查询期解析。

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

## 5. Agent 上报路径（`skywalking-java` @ `47ff1e8`）

```text
业务线程完成 segment
  │  TracingContext 通知 listener
  ▼
【投递】TraceSegmentServiceClient.afterFinished(TraceSegment)     ← apm-agent-core/remote
  │   isIgnore() 的 segment 直接跳过
  │   carrier.produce(segment)  ← DataCarrier 有界缓冲（IF_POSSIBLE）
  │       buffer 满 → 丢弃（debug 日志 "abandoned, cause by buffer is full"）
  ▼
【消费】carrier.consume(this, 1)  ← 单消费线程
  ▼
【发送】consume(List<TraceSegment> data)
  │   通道 CONNECTED → gRPC 流式 collect：本批 data 逐条 onNext，wait4Finish
  │   通道未连接     → segmentAbandonedCounter += data.size()   ← 直接丢弃，不落本地
  ▼
OAP gRPC: localhost:11800
```

要点（均来自源码）：

- 内存队列是 **`DataCarrier`**（`CHANNEL_SIZE` × `BUFFER_SIZE` 有界、策略 `IF_POSSIBLE`），**只有一个消费线程**；作用是削峰 + 批量网络发送，**不是存储**。
- **丢弃是设计内行为**：buffer 满、gRPC 未连接都直接丢，只有累计计数与日志（`segmentUplinkedCounter` / `segmentAbandonedCounter`）。**Agent 本地没有可查询的 trace 存储**。
- 与 Glowroot 的"内存可查询（active + pending）"形成鲜明对比（见 `glowroot-trace-storage.md`）。

---

## 6. 写入管线：`SegmentObject` → `segment` 一行（OAP）

```text
【接收】TraceSegmentReportServiceHandler.collect(stream SegmentObject)   ← server-receiver-plugin
  │   onNext(segment) → segmentParserService.send(segment)
  ▼
【解析】SegmentParserServiceImpl.send(...) → TraceAnalyzer → SegmentListener.parseSegment(...)
  ▼
【组装一行】SegmentAnalysisListener
  │   parseSegment(): 遍历 spans 算 start/end/duration/isError、采样
  │   parseFirst():   setSegmentId / setTraceId / setServiceId(IDManager.ServiceID.buildId) /
  │                   setServiceInstanceId / setEndpointId / setLatency / setStartTime /
  │                   setTimeBucket / setIsError
  │                   ★ setDataBinary(segmentObject.toByteArray())   ← 整段 protobuf 进一列
  │   build():        sourceReceiver.receive(segment)
  ▼
【落库】… → 批量 IBatchDAO → JDBC DAO → INSERT INTO segment(...)   ← 一行一个 segment，不合并
```

- **A1/A2 证实**：一条 `SegmentObject` 对应一行 `segment`；**写入期不按 traceId 合并**。【实测】demo-tomcat 发 9 次请求 → `SEGMENT` 正好 9 行。
- **A3 证实（措辞需修正）**：payload = 整段 protobuf 字节（含 spans）；H2 里 `DATA_BINARY` 类型是 **`CHARACTER LARGE OBJECT`（`MEDIUMTEXT`）**，内容是 **Base64 文本**（如 `CjU3ODJlYTEx...`，解出即 protobuf），**不是原始 BLOB**。
- **多 segment（跨服务 / 跨线程）**：每个 segment **各写一行**，不合并；跨段关系（`refs`）留在各自的 `data_binary` 里，**查询时**才连起来。
- **未核实**：`sourceReceiver.receive(segment)` 之后的 record 持久化 worker 与 batch 事务边界（见 §10）。

**实测样本（本环境）**：

```text
-- 576 张表；SEGMENT 共 9 行（demo-tomcat 的 9 次请求）
SEGMENT_ID : 782ea11f783348c3b6e8273b6b71192c.54.17898314984990000
TRACE_ID   : 782ea11f783348c3b6e8273b6b71192c.54.17898314984990001   ← 与 SEGMENT_ID 不同
SERVICE_ID : ZGVtby10b21jYXQ=.1                       → base64 "demo-tomcat" + ".1"(layer)
ENDPOINT_ID: ZGVtby10b21jYXQ=.1_R0VUOi9ub3BlLTE=      → "..._GET:/nope-1"
START_TIME : 1789831498499    LATENCY: 0    IS_ERROR: 1
TIME_BUCKET: 20260919152500   ← yyyyMMddHHmmss
DATA_BINARY: CjU3ODJlYTEx...  ← Base64(protobuf)
```

> `SEGMENT_ID` 与 `TRACE_ID` 是**两个不同的值**（同一 trace 的每个 segment 各有一个 segmentId）。用 segmentId 当 traceId 去查会查到空——排查时注意别搞混。

---

## 7. 查询链路：`traceId` → 整条链路（查询期组装）

```text
GraphQL: queryTrace(traceId)
  ▼
【服务层】TraceQueryService.queryTrace(traceId)                   ← server-core/query
  │   segmentRecords = getTraceQueryDAO().queryByTraceId(traceId)
  │   为空 → 退化 doFlexibleTraceQuery（H2/JDBC 下返回空列表）
  │   否则逐行：
  │       SegmentObject.parseFrom(segment.getDataBinary())   ← 反序列化 payload
  │       buildSpanList() → 展开 spans，逐个 SpanObject → Span
  │       spanObject.getRefsList() → ★ refs 来自 payload 内部（无独立表）
  │       findRoot(spans) + findChildren(...) → 父子里顺
  ▼
【DAO 层】JDBCTraceQueryDAO.queryByTraceId(traceId)
  │   SELECT segment_id, trace_id, service_id, service_instance_id,
  │          start_time, latency, is_error, data_binary
  │   FROM segment WHERE trace_id = ?
  │   String dataBinaryBase64 = getString(DATA_BINARY)
  │   setDataBinary(Base64.getDecoder().decode(dataBinaryBase64))
  ▼
返回 Trace { List<Span> }（已按父子关系排序）
```

- **A4 判定：前半证实 / 后半（依赖 ref 表）证伪**。【源码】`queryByTraceId` 逐行 `SegmentObject.parseFrom(...)`，用 payload 内 `spanObject.getRefsList()` 组父子；【实测】H2 `information_schema` 查无 `SEGMENT_REF` 表，`queryTrace` 能正确返回 span → refs 来自载荷而非独立表。
- **组装发生在 service 层**；DAO 只负责"按 trace_id 取原始行 + Base64 解码"。
- **孤 segment 兜底**（`TraceQueryService.findRoot` L237-265）：对每个 span 检查结果集内是否存在 `segmentSpanId == 本 span 的 segmentParentSpanId` 的 span；找不到父 → 标记 root。**多条根按 `startTime` 排序**，显示为"多根链路"。源码注释原文（节选）：

  > "In some cases, there are segment fragments, which could not be linked by Ref, because of two kinds of reasons. 1. Multiple leaf segments have no particular order in the storage. 2. Lost in sampling, agent fail safe, segment lost, even bug. Sorting the segments makes the trace view more readable."

  → **孤 segment（无 ref、无 entry）不会被丢弃**；这正是 A4 里 refs 缺失时的兜底。

---

## 8. 告警（alarm）：指标驱动，非链路驱动

| 环节 | 事实 | 证据 |
| --- | --- | --- |
| 触发时机 | **聚合指标落库之后** | 【源码】`AlarmNotifyWorker extends AbstractWorker<Metrics>`，类注释：*"do a simple route to alarm core after the aggregation persistence"* |
| 入口 | `NotifyHandler implements MetricsNotify` → `AlarmCore` | 【源码】`server-alarm-plugin/.../provider/NotifyHandler.java` |
| 规则/窗口 | `RunningRule`：按 `period`（分钟）开窗，用表达式（MQE）在窗口上求值，命中 `AlarmRule`（threshold/op/count/silencePeriod...）才触发 | 【源码】`server-alarm-plugin/.../provider/RunningRule.java` |
| 通知 | `AlarmCallback` → 各 notify handler（webhook / slack / 微信 / 钉钉 / gRPC 等） | 【源码】`server-alarm-plugin` |

**结论**：OAP 告警的**触发源是"指标"**（如 `service_resp_time`、`service_sla` 等聚合指标），**不读 `segment` 明细、不做 trace 级判定**。这与本项目"对单条 trace 判慢/错并告警"的目标**不同构**，**不能照搬**。

**对本项目 `accept(...)` 返回值的启示**（对应 `docs/todos/h2化-统一方案.md` §11 #13）：

- OAP 既不是从链路存储驱动告警，就**没有"必须把告警下移到存储层"的依据**；告警可继续留在客户端（现状）。
- 但本项目告警依赖"受影响 trace 的视图"。切流后若 `consume` 里的内存合并逻辑被移除，客户端就没有现成视图可喂给告警了。因此**推荐 `accept(...)` 回传本批受影响 trace 的视图**（`Map<traceId, 视图>`）；内存实现与 H2 实现都容易返回该视图。
- 备选：若切流后仍保留 `MemoryTraceSegmentStorage` 的内存合并视图专门供告警读取，则 `accept(...)` 保持 `void` 也可。**取舍点 = 切流后是否还保留一份内存合并视图。**

---

## 9. 与本项目（H2 化）的对照

| 维度 | SkyWalking `520d531` / `47ff1e8` 实际 | 本项目取舍 |
| --- | --- | --- |
| Schema 定义 | 代码注解 + 运行时建表 | 极简，手写 DDL（不必引入 Model 框架） |
| 表名 | `segment`（+ `segment_tag`） | 自建库，命名自定；**不再用 `skywalking_segment`** |
| service/instance/endpoint | **String** | 用 String → **与 OAP 一致，不是偏离** |
| span 存储 | 整段进 `data_binary`（`byte[]`→`MEDIUMTEXT`） | 同思路；载荷用本项目 `Log.toMap()` JSON（OAP 用 protobuf） |
| 写入粒度 | 一条 segment 一行，写入期不合并 | 与"抽象前移到 `accept(List<TraceSegment>)`、存储实现决定粒度"一致 |
| 查询组装 | 查询期按 traceId 取行 + payload 内 refs 组装；孤 segment 当根 | 可复用同语义；内存实现可直接给组装视图 |
| 近期/内存层 | Agent 队列仅传输缓冲、不可查；OAP 无内存热层 | 本项目保留 `MemoryTraceSegmentStorage` 作近期热层（参考 Glowroot） |
| TTL | record/metrics 两档，`time_bucket` 删除，首节点执行 | 按 `trace_level` 分级（增强）；定时器骨架借鉴 |
| 索引 | H2 **不建**（演示级） | 需查询性能 → **自己建索引**（trace_id / time_bucket / trace_level） |
| 告警 | 指标驱动，不读链路明细 | 保留 trace 级告警（`TraceEvaluator`）；落库打标复用同一判定 |

---

## 10. 未核实 / 边界

1. **批量落库 worker**：写入链路里"分析管线 → 批量 JDBC INSERT"这一段，只核到 `sourceReceiver.receive(segment)` 与 `SegmentRecord` 的 `@Stream` 声明；中间 record 持久化 worker 与 batch 实现未逐类展开（不影响 A1–A5 结论）。
2. **写侧 Base64 编码层**：【实测】确认"库里存 Base64、DAO 读取时 `Base64.getDecoder().decode`"；写侧在哪一层编码未逐行确认。
3. **告警规则完整字段 / MQE 语法**：只核到"指标驱动 + `RunningRule` 窗口评估 + notify handler"，未展开全部规则字段。
4. **H2 与集群模式差异**：H2 仅 `standalone`（本实验环境）；未对比集群存储差异。
5. **UI trace 列表查询**（按服务/耗时/错误过滤）的表与 SQL（`sampled_*_trace_record` 等）未展开。

---

## 11. 源码锚点

### OAP（@ `520d531`）

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
- gRPC 接收 handler：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-receiver-plugin/skywalking-trace-receiver-plugin/src/main/java/org/apache/skywalking/oap/server/receiver/trace/provider/handler/v8/grpc/TraceSegmentReportServiceHandler.java>
- 解析服务 `SegmentParserServiceImpl`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/analyzer/agent-analyzer/src/main/java/org/apache/skywalking/oap/server/analyzer/provider/trace/parser/SegmentParserServiceImpl.java>
- 分析器 `TraceAnalyzer`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/analyzer/agent-analyzer/src/main/java/org/apache/skywalking/oap/server/analyzer/provider/trace/parser/TraceAnalyzer.java>
- 落库前组装 `SegmentAnalysisListener`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/analyzer/agent-analyzer/src/main/java/org/apache/skywalking/oap/server/analyzer/provider/trace/parser/listener/SegmentAnalysisListener.java>
- 查询组装 `TraceQueryService`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/query/TraceQueryService.java>
- JDBC trace DAO `JDBCTraceQueryDAO`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-storage-plugin/storage-jdbc-hikaricp-plugin/src/main/java/org/apache/skywalking/oap/server/storage/plugin/jdbc/common/dao/JDBCTraceQueryDAO.java>
- 告警触发 worker `AlarmNotifyWorker`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-core/src/main/java/org/apache/skywalking/oap/server/core/analysis/worker/AlarmNotifyWorker.java>
- 告警入口 `NotifyHandler`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-alarm-plugin/src/main/java/org/apache/skywalking/oap/server/core/alarm/provider/NotifyHandler.java>
- 告警规则窗口 `RunningRule`：
  <https://github.com/apache/skywalking/blob/520d531fff8d2ba9687ff8b9086b7e27033a82bb/oap-server/server-alarm-plugin/src/main/java/org/apache/skywalking/oap/server/core/alarm/provider/RunningRule.java>

### Java Agent（@ `47ff1e8`）

- Agent 上报客户端 `TraceSegmentServiceClient`：
  <https://github.com/apache/skywalking-java/blob/47ff1e82fad048c64cb73b83ee841941c279c482/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/TraceSegmentServiceClient.java>
