# SkyWalking Agent 本地链路追踪插件方案评估 & 设计文档

> **实现备注**：部分实现：H2 存储/查询、宿主工具类与 Metrics 已落地；原 `TraceStorage` 抽象、H2 file 模式、normal/slow/error 分级 TTL 及部分 OAP 表复用方案未全部实现，文档已过期。
>
> 文档用途：内部Wiki持久化 
>
> 项目背景：基于 **SkyWalking Java Agent 9.4**，自定义 Reporter，借鉴 Druid 监控思路，实现单体应用进程内链路采集与查询；当前版本内存存储，计划迁移 H2（参考 Glowroot），**个人兴趣自研、公司无硬性任务，未给资源，内部产品自用，不依赖独立 SkyWalking OAP**。 
>
> 参考原型：https://github.com/fulizhe/skywalking-javaPluginExtensions/tree/master/agent/logfile-reporter-plugin 
>
> 核心约束：规避 ClassLoader 冲突；不内置 HTTP 服务；对外采用宿主工具类 + JDK Map 暴露数据；分阶段迭代，存量内存LRU逻辑不动。

## 1 方案评估

### 1.1 当前内存存储方案：优点

1. 零外部依赖：单体应用加载 agent 插件即可启用，无需部署 OAP 服务，适配内部单体、测试环境。
2. 进程内查询，无网络IO，接口响应快。
3. 复用 SkyWalking Agent 全套探针、字节码增强、Span 构建、上下文传播能力；仅替换数据输出层（自定义 Reporter），开发成本低。
4. 已有成熟LRU环形队列实现（`LogReportServiceLocalClient`），自带溢出保护，无需额外开发防溢出逻辑，存量基线稳定。

### 1.2 内存存储现存痛点 & 可扩展空间

1. **数据生命周期绑定JVM进程** 应用重启、OOM、容器销毁，链路数据全部丢失；故障场景下重启后无法复盘现场。 存储上限受JVM堆内存约束，高并发下Span/Trace持续堆积，依靠现有LRU环形队列做淘汰。

2. **查询能力受限** 基于内存环形队列，按traceId查询简单；时间范围、接口耗时、异常筛选等复杂查询，需要手写索引，复杂度高。仅能保留最近窗口数据，无法做历史回溯。

3. **并发写入风险** Agent Reporter为多线程投递Span，内存队列锁设计不当会产生锁竞争，影响业务线程性能。必须复用批量写入机制，禁止单Span同步写存储。

4. **缺少指标聚合能力** 仅存储原始Trace链路；缺少接口QPS、p50/p90/p99耗时、错误率等聚合统计，缺少本地告警能力。

   > 迭代约定：**内存模式不实现Metrics，指标聚合仅在H2存储模式开发**。

5. **适用边界限制** 仅适合单体应用；多服务分布式场景无法跨进程聚合Trace，不能替代标准SkyWalking分布式追踪。

> 内存版本可扩展清单（仅做最小维护，不新增复杂能力）：
>
> - 维持现有环形LRU内存队列，复用`LogReportServiceLocalClient`，不再重构
> - 采样策略：高并发链路采样，降低存储压力
> - Trace快照Dump：OOM预警/内存满时导出链路JSON，保存故障现场

### 1.3 迁移 H2 嵌入式数据库（参考Glowroot）收益

1. **持久化保存链路，应用重启数据不丢失**，崩溃现场可复盘，内部排障价值最高。
2. **复用SQL能力做复杂查询，不用自研内存索引**。基于trace_id、start_time、operation_name、耗时、异常标记建立索引，直接SQL实现时间段筛选、慢查询检索。
3. **存储不再严格受JVM堆限制**，数据落磁盘，堆只保留缓存；配合TTL自动清理，控制磁盘占用。
4. H2模式可开启增强能力：normal/slow/error分级存储、Metrics聚合。
5. 复用成熟嵌入式存储能力，减少自研LRU、并发、索引逻辑的bug。
6. **远期备选**：可直接使用H2内存模式替代独立内存实现，只维护一套存储实现，消除两套存储代码维护成本。

### 1.4 H2 引入的代价与风险

1. **磁盘IO开销**：大量Span写入会产生磁盘压力。解决方案：异步批量写入，业务线程只入内存队列，独立线程批量Batch插入H2，不阻塞业务。
2. **文件损坏风险**：`kill -9`、断电极端场景下H2文件损坏。推荐使用MVStore引擎；增加定时H2文件快照备份。
3. H2自带内存缓存，需要手动配置缓存上限，避免堆占用失控。
4. 查询性能相比纯内存下降；热点数据可内存缓存，老数据走H2，性能满足内部自用场景。
5. 依赖冲突风险：插件内置H2，业务应用如果自带H2会出现版本冲突。

### 1.5 推荐落地路线（分阶段迭代，SkyWalking9.4）

> 核心思想：**存储抽象先行，存量不动，最小改动，控制bug面，个人兴趣渐进开发**

1. **阶段1（当前基线，稳定优先）**
   - 抽取`TraceStorage`顶层抽象接口；**保留原有内存LRU环形队列实现完全不变**，复用现有`LogReportServiceLocalClient`，原有内存链路采集、缓存逻辑维持现状。
   - 不改动原有上报、采集链路；内存实现作为`TraceStorage`其中一个实现类。
   - 对外查询保持现有方案：**不引入HTTP Server**。复用宿主工具类模式，参考SkyWalking官方`TraceContext`设计。
   - Metrics：阶段1暂不实现，内存模式不做指标聚合。
2. **阶段2：新增H2存储实现（第二个TraceStorage实现）**
   - 在抽象存储接口之上新增H2实现，H2独立一套写入/查询逻辑；
   - H2上实现增强特性：链路分级存储（normal / slow / error）；
   - 模型约束：复用SkyWalking 9.4原生`TraceSegment`对象模型，**不自定义全新Trace实体类**，规避ClassLoader冲突风险。
3. **阶段3：H2模式新增Metrics能力**
   - Metrics仅在H2存储模式实现，内存模式不开发指标逻辑；远期甚至可直接移除独立内存实现，改用H2内存模式统一替代，减少两套存储代码维护成本。
   - Metrics对外暴露方式对齐现有工具类风格，新增配套工具类，和`SWLogfileReporterUtils`保持一致设计范式。
   - 只提供原始指标Map数据，**不内置可视化UI**，由调用方基于返回Map自行实现图表/大盘，降低项目复杂度。

> 定位约束：本系统**聚焦单体本地排障**，不用于多服务分布式追踪。

## 2 复用SkyWalking OAP原生表结构可行性评估（基于SkyWalking9.4）

### 核心结论

✅ **可以复用 OAP 的原生表结构，优先复用字段语义、枚举、TraceSegment模型**；物理表二选一。

> 说明：Agent上报的数据模型和OAP原生模型同源，`TraceSegment` 就是OAP接收对象；SkyWalking OAP 9.4 内置H2单机存储，原生支持MVStore。 重要约束：Agent插件自建本地存储，**不是完整OAP服务，需要裁剪冗余表**。

### 2.1 复用OAP表结构带来的收益

1. **模型完全对齐，不需要做模型转换** SkyWalking Agent产出 `TraceSegment`、`Span` 对象，和OAP接收的原始数据模型一模一样。

   - 不用自己定义trace/span字段映射、不用手动翻译字段含义；

   - 字段语义、枚举（SpanType、RefType、componentId等）直接沿用官方定义，减少踩坑。

     > 自定义Reporter拿到的 `TraceSegment` 就是OAP收到的同一份对象，OAP源码里有完整的序列化、转DB记录逻辑，可以直接参考。

2. **OAP内置H2已经经过官方验证，成熟稳定** SkyWalking OAP单机模式就是H2 MVStore，官方做过：批量写入、TTL过期清理、索引设计、存储引擎调优。

   - 索引策略、过期删除SQL、字段类型、长度限制都有现成参考，不需要从零设计；
   - 官方已经处理H2文件锁、MVStore崩溃恢复。

3. **未来可双向兼容**

   - 当某条trace需要放到完整SkyWalking集群排查时：你的本地H2数据可以导出，直接导入标准OAP存储，模型无差异；
   - 代码层面可以复用OAP源码中 `TraceSegment -> DB Entity` 的转换思路，不用重复写转换逻辑。

4. **后续升级Agent版本友好**，Span/Trace字段变更，OAP表结构同步跟着升级，只需同步表结构。

### 2.2 关键限制：不能直接照搬OAP全套表

OAP存储分为两大类表，要区分对待

1. **Trace相关原始表（Segment表、SegmentRef表）👉 可以直接复用** SkyWalking OAP原始链路核心：

   - `skywalking_segment`：对应TraceSegment（一段segment，包含多个span）

   - `skywalking_segment_ref`：跨segment引用（分布式trace跨进程引用关系）

     > OAP内部把Span**序列化存到大BLOB字段**（不是一行一条Span）。 取舍对比：
     >
     > - OAP方案：一行segment，span打包BLOB；适合按traceId查整条链路；**无法SQL直接过滤span内部字段**
     > - 拆分行方案：一行一条span；可以建索引直接筛选span的异常、耗时、接口名，适合运维大盘检索慢请求、异常链路。

2. **OAP的Metrics指标表👉 谨慎复用** OAP指标表是为**流式L1/L2/L3分层聚合**设计，包含大量分桶、实体、metadata表：

   - `service_metric`、`service_instance_metric`、`endpoint_metric` 等

   - 还有 `service`、`service_instance`、`endpoint` 元数据表

     > 单体本地场景建议：**不引入完整OAP流式聚合引擎**，轻量自建分钟聚合表，降低插件复杂度。

### 2.3 两种存储物理表选型对比

#### 方案1：复用OAP segment表（一行segment，span打包BLOB）

✅ 优点

1. 完全原生模型，直接复用OAP的TraceSegment序列化逻辑；
2. 表数量少，只有 `skywalking_segment`、`skywalking_segment_ref`；
3. OAP H2原生支持，TTL清理脚本、索引官方现成；
4. 写入简单：一次batch插入一条segment，不需要循环插入多条span。

❌ 缺点

1. **查询不方便**：Span打包在BLOB里。按operation_name、span异常、span耗时筛选时，数据库无法直接索引span内部字段。不能直接SQL过滤span，必须读取blob反序列化后在代码里过滤。
2. 分页、按接口名检索span性能差，适合【根据traceId查整条链路】，**不适合大范围筛选span**。

#### 方案2：拆分为独立trace+span表（一行一条span）

✅ 优点

1. Span字段拆成独立列，可以建立索引，SQL直接筛选：接口名、异常标记、span耗时、组件类型；
2. 大盘筛选慢请求、异常链路非常方便，适合内部运维查询。

❌ 缺点

1. 需要自己写 `TraceSegment -> Trace+Span` 拆分行的转换代码；
2. 和原生OAP存储物理结构不一致（业务模型一致）；
3. 表行数会暴涨：1个segment对应N条span，数据量放大。

> 💡一句话取舍：
>
> - 核心场景：**输入traceId查看链路详情为主，很少大范围筛选span** → 复用OAP `skywalking_segment` 表（BLOB打包）
> - 核心场景：**经常按时间段、接口名、异常、慢请求检索span列表** → 业务模型对齐OAP，物理拆分为独立trace、span行表

### 2.4 OAP H2单机模式表定义参考（SkyWalking9.4）

```
-- OAP 原生 segment 表，存储TraceSegment，span序列化存入BLOB
CREATE TABLE IF NOT EXISTS skywalking_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    segment_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    service_id VARCHAR(512) NOT NULL,
    service_instance_id VARCHAR(512) NOT NULL,
    endpoint_id VARCHAR(512),
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    latency INT NOT NULL,
    is_error BOOLEAN NOT NULL,
    data_type INT NOT NULL,
    data_binary BLOB NOT NULL, -- TraceSegment序列化二进制，包含全部Span
    time_bucket BIGINT NOT NULL
);
CREATE INDEX idx_sw_segment_trace_id ON skywalking_segment(trace_id);
CREATE INDEX idx_sw_segment_time_bucket ON skywalking_segment(time_bucket);
CREATE INDEX idx_sw_segment_is_error ON skywalking_segment(is_error);

-- segment引用表，跨segment引用（分布式trace）
CREATE TABLE IF NOT EXISTS skywalking_segment_ref (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    segment_id VARCHAR(128) NOT NULL,
    ref_segment_id VARCHAR(128) NOT NULL,
    ref_trace_id VARCHAR(128) NOT NULL,
    time_bucket BIGINT NOT NULL
);
CREATE INDEX idx_sw_ref_trace_id ON skywalking_segment_ref(trace_id);
CREATE INDEX idx_sw_ref_time_bucket ON skywalking_segment_ref(time_bucket);
```

> OAP的time_bucket：按分钟/小时规整的时间桶，用于TTL删除，官方标准设计。

### 2.5 依赖&打包注意点

1. 若复用OAP的entity、序列化代码：会引入OAP核心依赖，包体积变大。

   > 解决方案：maven shade，把用到的OAP相关类shade重命名，避免和业务应用冲突。

2. OAP的H2存储代码在OAP服务端，**Agent侧不能直接import OAP存储类**，只能抄表定义和转换逻辑，不能直接依赖完整OAP。

3. H2版本对齐：H2版本锁定，和SkyWalking OAP 9.4配套版本保持一致，否则H2文件不兼容。

## 3 H2表设计方案（二选一）

> 方案A：复用OAP原生segment表（BLOB打包），见上一节SQL 方案B：拆分trace/span独立行表

### 3.1 trace_info 链路主表（一条TraceSegment对应一条记录）

```
CREATE TABLE IF NOT EXISTS trace_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    segment_id VARCHAR(128) NOT NULL,
    service_name VARCHAR(256) NOT NULL,
    service_instance VARCHAR(256),
    start_time BIGINT NOT NULL, -- 时间戳 ms
    end_time BIGINT NOT NULL,
    duration BIGINT NOT NULL, -- 耗时 ms
    is_error BOOLEAN NOT NULL,
    trace_level VARCHAR(16), -- normal / slow / error 分级存储标识
    tags CLOB, -- 全局标签，JSON存储
    create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 索引
CREATE INDEX idx_trace_trace_id ON trace_info(trace_id);
CREATE INDEX idx_trace_start_time ON trace_info(start_time);
CREATE INDEX idx_trace_is_error ON trace_info(is_error);
CREATE INDEX idx_trace_level ON trace_info(trace_level);
```

### 3.2 span_info 子表（一个TraceSegment包含多条Span）

```
CREATE TABLE IF NOT EXISTS span_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    segment_id VARCHAR(128) NOT NULL,
    span_id INT NOT NULL,
    parent_span_id INT,
    operation_name VARCHAR(512) NOT NULL,
    span_type VARCHAR(32), -- ENTRY / LOCAL / EXIT
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    duration BIGINT NOT NULL,
    is_error BOOLEAN NOT NULL,
    component VARCHAR(128), -- 组件名称，如Tomcat, Druid
    tags CLOB, -- span标签，JSON
    logs CLOB, -- span事件日志，异常堆栈JSON
    create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 索引
CREATE INDEX idx_span_trace_id ON span_info(trace_id);
CREATE INDEX idx_span_operation ON span_info(operation_name);
CREATE INDEX idx_span_start_time ON span_info(start_time);
```

### 3.3 metrics_minute 分钟级聚合指标表（仅H2模式启用）

> 预聚合，用于大盘统计，避免每次查询扫描全量span原始数据

```
CREATE TABLE IF NOT EXISTS metrics_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_name VARCHAR(256) NOT NULL,
    operation_name VARCHAR(512) NOT NULL,
    time_bucket BIGINT NOT NULL, -- 分钟时间戳（整分钟）
    count BIGINT NOT NULL, -- 请求总数
    error_count BIGINT NOT NULL, -- 异常请求数
    total_duration BIGINT NOT NULL, -- 总耗时
    max_duration BIGINT NOT NULL,
    min_duration BIGINT NOT NULL,
    p50 BIGINT,
    p90 BIGINT,
    p99 BIGINT,
    create_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 联合索引，按接口+时间查询
CREATE UNIQUE INDEX idx_metrics_op_time ON metrics_minute(service_name, operation_name, time_bucket);
```

### 3.4 定时清理SQL示例（TTL，分级TTL策略）

```
-- normal普通链路 保留较短时间，slow中等，error故障链路保留最长，可配置
DELETE FROM trace_info WHERE trace_level = 'normal' AND start_time < UNIX_TIMESTAMP(DATEADD('DAY', -1, NOW())) * 1000;
DELETE FROM trace_info WHERE trace_level = 'slow' AND start_time < UNIX_TIMESTAMP(DATEADD('DAY', -3, NOW())) * 1000;
DELETE FROM trace_info WHERE trace_level = 'error' AND start_time < UNIX_TIMESTAMP(DATEADD('DAY', -7, NOW())) * 1000;

DELETE FROM span_info WHERE start_time < UNIX_TIMESTAMP(DATEADD('DAY', -7, NOW())) * 1000;
DELETE FROM metrics_minute WHERE time_bucket < UNIX_TIMESTAMP(DATEADD('DAY', -30, NOW())) * 1000;
```

## 4 自定义 SkyWalking Reporter 关键代码骨架（SkyWalking9.4）

> 依赖：skywalking-agent-core，实现 `org.apache.skywalking.apm.agent.core.report.Reporter` 说明：Reporter 负责接收 `TraceSegment`（SkyWalking一段链路载体，包含多个Span）

```
package com.company.skywalking.plugin.reporter;

import org.apache.skywalking.apm.agent.core.report.Reporter;
import org.apache.skywalking.apm.network.trace.component.command.BaseCommand;
import org.apache.skywalking.apm.trace.TraceSegment;

import java.util.List;
import java.util.concurrent.*;

/**
 * 自定义本地Reporter：接收TraceSegment，异步投递到存储队列
 * 支持切换：内存存储 / H2持久化存储
 */
public class LocalTraceReporter implements Reporter {

    // 阻塞队列：业务线程投递TraceSegment，异步消费，避免阻塞业务
    private final BlockingQueue<TraceSegment> segmentQueue;
    // 消费线程池
    private final ExecutorService consumerExecutor;
    // 存储层接口，实现内存存储 / H2存储两种实现
    private final TraceStorage traceStorage;
    // 是否关闭标记
    private volatile boolean isRunning;

    public LocalTraceReporter(TraceStorage traceStorage, int queueCapacity) {
        this.traceStorage = traceStorage;
        this.segmentQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.consumerExecutor = Executors.newSingleThreadExecutor();
        this.isRunning = true;
        startConsumer();
    }

    /**
     * Agent 回调：采集完成一段TraceSegment后，调用report方法
     * 【重要】该方法在业务线程执行，禁止阻塞、禁止同步写磁盘/DB
     */
    @Override
    public void report(TraceSegment traceSegment) {
        if (!isRunning || traceSegment == null) {
            return;
        }
        // 入队，队列满则丢弃（可增加采样策略在这里做过滤）
        boolean offer = segmentQueue.offer(traceSegment);
        if (!offer) {
            // 队列满，丢弃链路，可增加日志打点
        }
    }

    /**
     * 启动异步消费线程，批量写入存储
     */
    private void startConsumer() {
        consumerExecutor.submit(() -> {
            // 批量大小，参考SkyWalking原生批量策略
            final int batchSize = 50;
            while (isRunning) {
                try {
                    TraceSegment segment = segmentQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (segment == null) {
                        continue;
                    }
                    // 批量收集
                    List<TraceSegment> batchList = new ArrayList<>(batchSize);
                    batchList.add(segment);
                    segmentQueue.drainTo(batchList, batchSize - 1);
                    // 交给存储层：内存 / H2
                    traceStorage.saveBatch(batchList);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    // 存储异常捕获，不能中断消费线程
                }
            }
        });
    }

    /**
     * SkyWalking 命令回调，本项目不需要OAP下发命令，空实现
     */
    @Override
    public void command(List<BaseCommand> commands) {
        // no-op
    }

    /**
     * Agent关闭时销毁资源
     */
    @Override
    public void shutdown() {
        isRunning = false;
        consumerExecutor.shutdown();
        traceStorage.shutdown();
    }

    /**
     * 存储抽象层，解耦内存实现和H2实现
     */
    public interface TraceStorage {
        /**
         * 批量保存TraceSegment，由Reporter异步批量调用
         * @param segments SkyWalking 9.4原生TraceSegment列表
         */
        void saveBatch(List<TraceSegment> segments);

        /**
         * 对外查询原始缓存快照，返回纯JDK Map，不暴露Agent自定义实体，规避类加载问题
         * @return 快照Map，结构对齐 SWLogfileReporterUtils.statisticStatus()
         */
        Map<String, Object> getSnapshot();

        /**
         * 存储层资源关闭，Agent shutdown时调用
         */
        void shutdown();
    }
}
```

### 配套：Agent插件注册说明

需要在 `skywalking-plugin.def` 注册自定义Reporter，替换默认GRPC Reporter； 通过 `BootService` 初始化并注册 `LocalTraceReporter` 到 Agent 核心上报管理器。

> TraceStorage实现说明
>
> - MemoryTraceStorage：实现TraceStorage，底层复用已有`LogReportServiceLocalClient`，原有LRU环形队列逻辑完全保留，不做重构。
> - H2TraceStorage：后续新增实现，实现批量写入、分级存储（normal/slow/error）、TTL清理、指标采集。

## 5 宿主工具类：SWLogfileReporterUtils 设计（对外暴露入口，无HTTP）

> 编译期业务依赖此类；运行期实现由Agent拦截器接管，做到编译无Agent依赖；对外只返回原生Map容器，不传递Agent内部对象。

```
public class SWLogfileReporterUtils {
    /**
     * 运行时开关:开启本地内存报告。
     * <p>
     * 设计意图(config 保留自旧项目 sb-skywalking 的 TODO 注):
     * 1. 对于哪些请求进行捕获——基于 request 的 SpEL 表达式判断;
     * 2. 日志存放根目录——先直接存入内存缓存,借鉴 druid 的缓存思路(后续可落盘);
     * 3. (预留)其它采集范围配置项。
     */
    public static void enableReport(Map<String, Object> config) { }

    /**
     * 运行时开关:关闭本地内存报告
     */
    public static void disableReport(Map<String, Object> config) { }

    /**
     * 统计快照:链路段缓存、JVM、meter、实例属性、应用日志、profile 快照、告警指标。
     * <p>
     * 设计意图:
     * 1. 缓存里的配置键值对(用户过往通过 enableReport 传入的);
     * 2. 缓存里记录的链路(根据用户配置,由 SW 插件捕获的);
     * 3. 返回纯JDK原生Map，便于外部直接读取/操作，规避classloader问题。
     */
    public static Map<String, Object> statisticStatus() {
        return Collections.emptyMap();
    }
}
```

> 设计要点：
>
> 1. 对外API只使用JDK原生`Map<String,Object>`，不返回Agent侧自定义POJO实例；
> 2. 后续H2模式的Metrics，新增配套工具类，保持完全相同范式；
> 3. 不启动内嵌HTTP服务；调用方拿到Map后自行做可视化、解析、渲染。

## 6 ClassLoader 风险规避方案（重点）

> 风险：如果自己新建Trace/Span自定义POJO，该类由Agent ClassLoader加载；业务代码拿到对象时，业务App ClassLoader识别不到该类型，会出现`ClassCastException` / 类找不到。 ✅ 规避策略：

1. 全程复用SkyWalking 9.4 Agent原生`TraceSegment`、`Span`等原生模型对象，**不新增自定义Trace实体**；

2. 对外暴露数据统一使用**原生JDK集合类型（Map、List）**，不传递Agent内部自定义POJO实例；

3. 宿主工具类`SWLogfileReporterUtils`：编译期业务代码仅依赖这个工具类接口签名；**运行期由Agent拦截器接管实现**，实现「编译无Agent强依赖」。

   > 设计对齐SkyWalking官方 `org.apache.skywalking.apm.toolkit.TraceContext` 的宿主工具类模式。

## 7 H2 模式增强特性规划（仅H2启用，内存模式不开发）

1. **链路分级存储**：按请求特征区分 normal / slow / error 三类Trace，独立TTL策略。
   - error链路：最长保留时间；优先保留故障现场
   - slow慢链路：中等保留时间
   - normal普通链路：较短TTL，优先清理，节省磁盘
2. **Metrics指标**：仅H2存储模式开启；参考SkyWalking OAP指标模型，复用OAP指标统计逻辑思路；
   - 对外新增配套工具类（和SWLogfileReporterUtils同风格），返回指标Map；
   - 不内置可视化，上层消费方基于返回Map自行实现大盘。

## 8 风险清单（Wiki归档）

| 风险                                                         | 等级 | 缓解方案                                                     |
| ------------------------------------------------------------ | ---- | ------------------------------------------------------------ |
| H2进程被kill，数据库文件损坏                                 | 中   | 使用MVStore；定时备份h2文件                                  |
| 高并发Span写入导致磁盘IO高                                   | 中   | 异步批量写入 + 采样策略                                      |
| H2依赖与业务应用包冲突                                       | 中   | Shade打包H2，重命名包名隔离                                  |
| 内存队列打满丢Trace                                          | 低   | 队列容量配置+采样；日志打点记录丢弃数量                      |
| 数据无限膨胀                                                 | 中   | TTL定时清理任务，配置保留窗口，分级TTL                       |
| OAP原生segment表中Span存BLOB，无法SQL索引span内部字段，大范围筛选性能差 | 中   | 评估查询场景；如果需要按span条件检索，则放弃BLOB方案，拆分行存储 |
| OAP H2版本与插件H2版本不一致，H2文件无法打开                 | 中   | H2版本锁定，和对应SkyWalking OAP版本保持一致                 |
| 直接引入OAP实体类会带来大量多余依赖，包膨胀、类冲突          | 中   | 只复制表定义与转换逻辑，不直接依赖OAP；必要时shade重命名     |
| 自定义Trace实体POJO跨类加载器，业务代码读取时ClassCast       | 高   | 不新增自定义Trace DTO；全程复用SkyWalking9.4原生TraceSegment；对外只返回JDK原生Map |
| 两套存储（memory + H2）长期维护带来重复代码、逻辑不一致      | 低   | 远期可移除独立内存实现，使用H2内存模式统一，仅维护一套存储   |
| 工具类静态方法在多类加载器场景下实现替换失效                 | 中   | 遵循SkyWalking Toolkit TraceContext宿主类设计模式，Agent字节码拦截接管工具类实现 |

## 9 架构取舍总结（Wiki归档）

1. ✅ 存量优先：现有内存LRU环`LogReportServiceLocalClient`不动，阶段1只做抽象层封装，保证现有功能稳定，最小改动。
2. ✅ 模型约束：全程复用SkyWalking9.4原生TraceSegment/Span，不自定义Trace实体，规避ClassLoader问题；对外只返回JDK原生Map集合。
3. ✅ 对外API范式：宿主工具类（参考TraceContext），编译期依赖、运行期Agent接管；**无内置HTTP服务**，返回Map交由外部做可视化。
4. ✅ 功能隔离：内存模式只保留链路采集；分级存储、metrics等高级特性仅在H2模式实现；远期可统一为H2内存模式，减少维护负担。
5. ✅ 个人维护定位：个人兴趣项目，无公司硬性任务；控制代码量，减少bug，逐步迭代，打造单体本地追踪能力。
