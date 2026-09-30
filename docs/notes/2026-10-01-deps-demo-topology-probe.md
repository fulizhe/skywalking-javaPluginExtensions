# 依赖面造数实跑探针（Redis / MySQL / Kafka / 外呼的出口 span 事实）

- **何时读**：改 `DepsDemoController`、给依赖拓扑加组件、给 `checks.sh` 写断言前 —— 这里的每条都是**实测**得来的，
  其中三条**推翻了动手前的预设**。
- **环境**：agent 9.4.0（`D:\apps\apache-skywalking-java-agent-9.4.0`）、demo-app（Spring Boot 2.7.18 / JDK 8 运行时）、
  Windows + PowerShell 7。**中间件未起**（本机 Docker daemon 当时不可用），下列"无边"结论都是在该状态下实测的。
- **对照**：[`2026-09-30-exit-span-runtime-probe.md`](2026-09-30-exit-span-runtime-probe.md)（上一轮探针，推翻了 operationName 基数与实例身份两处预设）。

## 一、推翻预设的三条

### 1. **连接建立失败不产生依赖边**（最重要的一条，直接改了断言设计）

动手时的设想是「中间件不在场也照调 → 红边 → 任何机器都能验组件识别 + 边级错误」。**实测不成立**：

| 通道 | 中间件未起时的实测结果 | 有边？ |
| --- | --- | --- |
| Redis（Jedis） | `JedisConnectionException: Failed to create socket`（1.0s） | **无边** |
| MySQL | `CommunicationsException: Communications link failure`（2.2s） | **无边** |
| Kafka（`send`） | `TimeoutException: Topic sw-demo not present in metadata after 3000 ms`（3.0s） | **有边**（`kafka-producer`） |
| Hutool 外呼 | httpbin 200（1.9s）；站点不可达时同理有边 | **有边**（`Http(#128)`） |

原因：出口 span 由 agent 挂在**被增强的方法**上（JDBC 的 `execute` / Jedis 的命令发送 / Kafka 的 `send` / Hutool 的 `execute`），
而**连接建立本身发生在这些方法之前**——`DriverManager.getConnection` / socket 建连失败时，根本没进到被增强的方法。

**三条推论**（都已写进页面 caveats、README 口径表与 `checks.sh` 断言 F）：

1. 某个组件**缺席**不能读成"没有这个调用"，只能读成"连接没建起来"；要确认失败现场得去链路视图。
2. `checks.sh` 里"无中间件时 Redis/MySQL 应有红边"这条断言被**删掉**——它永远红。
3. 必测的"无中间件也能验组件识别"改用**不需要连接成功的通道**（Kafka `send`、Hutool 外呼）。

### 2. **组件名要按实测值断言，不是按概念名**

demo-app 自带 `component-libraries.yml` 里的顶层键：

| 概念 | 库里的键（=页面显示的 `componentName`） | id |
| --- | --- | --- |
| H2 | `h2-jdbc-driver` | 4 附近另有 `H2` 条目，但不是它 |
| Kafka | `kafka-producer` | 27 |
| MySQL | `Mysql` | 5 |
| Redis | `Redis` / `Jedis` | 7 / 30 |
| Hutool HTTP | 不在库里 → 宿主 fallback `Http(#128)` | 128 |

`assert_jq '... select(.componentName == "Kafka")'` 会永远红；实测值是 `kafka-producer`。

### 3. **agent 插件的 support 范围会静默不生效**

读 `plugins/apm-*-plugin-*.jar` 里的 `plugin.def`（agent 9.4.0）：

| 库 | 支持范围 | 踩坑 |
| --- | --- | --- |
| jedis | `jedis-2.x-3.x`、`jedis-4.x` | 原本想用 Boot 2.7 管理的 lettuce —— **`lettuce-5.x` 只认 5.x**，Boot 管的 lettuce 是 6.x，**版本不匹配即静默不生效**（不报错、图上就是没那个节点）。改用 jedis 3.8.0 |
| kafka-clients | `kafka-0.11.x/1.x/2.x`（另有 `kafka-3.7.x` 一条） | 原本选 3.6.1 → **不在范围内**，会静默不生效。改钉 `2.8.2` |
| mysql-connector | `mysql-8.x` | Boot 2.7.18 管理的坐标是 `com.mysql:mysql-connector-j`（不是 `mysql:mysql-connector-java`） |

## 二、fat jar 下 MySQL 驱动**不会**被 `DriverManager` 自动注册

`DriverManager.getConnection("jdbc:mysql://...")` 在 Spring Boot fat jar 形态下抛
`SQLException: No suitable driver found`——嵌套 jar 里的 `META-INF/services/java.sql.Driver`
没被 `DriverManager` 的 SPI 扫描到（`com/mysql/cj/jdbc/Driver.class` 与 services 文件**都在** jar 里，已核）。

解法：`Class.forName("com.mysql.cj.jdbc.Driver")` 显式加载（`DepsDemoController.loadMysqlDriver()`）。
**不用** `spring.datasource` 自动配置——那会让 MySQL 顶掉 H2 演示库（`schema.sql` 灌数据与 `/h2` 控制台都依赖它）。

修完的实测：`CommunicationsException: Communications link failure`，2.2s（= 配置的 `connectTimeout=2000`），
驱动加载问题消失。

## 三、Kafka 三个坑（`consume` 从 40s+ → 0.3s）

| 坑 | 现象 | 处置 |
| --- | --- | --- |
| `close()` 无界等待 | broker 不可达时 `KafkaConsumer.close()` 一直不返回。**实测 > 40s 仍未返回**（try-with-resources 退出时卡住） | 改成 `assign` + **不 close**（consumer 由 GC 回收）。`api.close.request.timeout.ms` 在 2.8.2 **不存在**，加了也无效（已删） |
| `seekToBeginning` 拖时间 | 位点 reset 要先取元数据 + offset，broker 不可达时按 `request.timeout.ms` 阻塞 → 单独这一句把 consume 从 ~2s 拖到 **8s** | 改用 `auto.offset.reset=earliest`，重置挪进 `poll()` 内部、受 poll 时长约束 |
| `ensureTopic` 每次都试 | AdminClient 在 broker 不可达时耗满 `default.api.timeout.ms` | 进程内**只试一次**（`topicEnsured`），之后直接走真正的 produce/consume |

最终实测：`op=consume` **1.97s**（首次，含 ensureTopic）/ **0.30s**（后续）；`op=produce` 3.0s（`max.block.ms` 兜住）。

**顺带一条发现**：`op=produce` 超时那条边 `errorCount = 0` —— agent 的 kafka producer span **不会**因为
`send()` 的 `max.block.ms` 超时被判错。原因：它靠 `send()` 的**异步 callback**（`onCompletion`）回填错误，
而 broker 不可达时 `send()` 同步等满 `max.block.ms` 就抛了，**根本没有 callback 回来**。

所以要分清两件事：

| 图上读到的 | 实际含义 |
| --- | --- |
| 有边 + `errorCount = 0` + 耗时 ≈ `max.block.ms`（实测 3.02s） | **调用失败了**，只是 span 没被判错 |
| 有边 + `errorCount = 0` + 耗时很小 | 调用大概率真成功 |

「有边」与「红边」是两件事；**反过来"判成功"也不等于"调用成功"**。判断成功与否要看端点自己的
响应体（`{"ok":false,"error":"...TimeoutException..."}`）或业务日志，不能只看颜色。

## 四、各通道超时的**实际生效值**（无中间件状态）

| 通道 | 配置 | 实测 | 判定 |
| --- | --- | --- | --- |
| Redis（Jedis） | `new Jedis(host, port, 1000)` | 1.0~1.2s | 生效（Jedis 的 timeout 同时覆盖连接与命令） |
| MySQL | JDBC URL `connectTimeout=2000&socketTimeout=3000` | 2.2s | 生效（URL 没带时由 `withFallbackTimeout` 补上） |
| Kafka produce | `request 3000` / `delivery 5000` / `max.block 3000` | 3.0s | 生效（`max.block.ms` 兜住 send 阻塞） |
| Kafka consume | `poll 300` / `default.api.timeout 1500` / `socket setup 1000` | 0.3s（首次 1.97s） | 生效 |
| Hutool 外呼 | `HttpRequest.timeout(3000)` | 1.9s（httpbin 实际耗时） | 生效（上限 3s，未触顶） |

## 五、复现命令

```powershell
# 起应用（无中间件也行）
pwsh ./agent/demo-app/scripts/run-with-agent.ps1 -SkipPluginBuild

# 冒烟八个造数端点 + 计时
pwsh ./agent/demo-app/scripts/deps.ps1 -Smoke -BaseUrl http://127.0.0.1:9600

# 读边（组件名按实测值：h2-jdbc-driver / kafka-producer / Http(#128)）
curl.exe -s --noproxy "*" "http://127.0.0.1:9600/inner/sw/topology?view=summary"
```

## 六、最严重的一条：`/fullSample` 直接调 handler 方法 → **Entry 被改写、依赖边归因错位**

压测（`stress.ps1 -AllEndpoints`）时在明细档看到：`/api/deps-demo/redis` 名下挂着 **4 条出口**
（`Jedis/set`、`/api/trace-alert-demo/ok`、`Kafka/sw-demo/Producer`、`/get`），而且**调用量整整齐齐
都是同一个数字**；`/queryDbByMybatis` 名下还有两条 H2 边。

在干净实例（9601）上**单次** `/fullSample` 即稳定复现，`trace-recent` 显示：

```
GET:/api/deps-demo/redis     isError=True   latency=3885   ← 这其实是 /fullSample 的段
GET:/api/trace-alert-demo/ok isError=False  latency=2      ← HttpClient 自调产生的第二个段(真 entry)
GET:/api/deps-demo/redis     isError=True   latency=4508
GET:/api/deps-demo/redis     isError=True   latency=5616   ← latency 一条比一条长
```

**根因**：那四层逻辑原先写在 `DepsDemoController` 里，而 `/fullSample` 为了"全貌"直接
`depsDemo.redis("set")` 这样**调用另一个 Controller 的 handler 方法**。agent 的 Spring MVC 增强
把那些 handler 的 operationName 当成了本次请求的 Entry，于是**一次请求产生的 7 个出口**
（JDBC ×2 / HttpClient 自调 / Jedis / MySQL / Kafka / 外呼）全被归到 `GET:/api/deps-demo/redis` 名下。

**不是并发问题**：单次调用即复现，稳定。

**修法**：四层逻辑搬进普通 `@Service`（`org.openskywalking.demo.service.DepsDemoService`），
`DepsDemoController` 退化成 HTTP 薄壳，`FullSampleController` 注入 service。搬完之后
**Entry 只来自真实 HTTP 入口**（Tomcat 插件），内部复用不再改写入口名。

**回归断言**（加在 `checks.sh` 断言 F 里）：

| 断言 | 作用 |
| --- | --- |
| `[.edges[]? \| select(.endpoint == "GET:/fullSample")] \| length >= 2` | 全貌入口的出口必须归在它自己名下 |
| `[.edges[]? \| select(.endpoint \| test("^GET:/api/deps-demo/")) \| select(.spanLayer == "MQ" or .spanLayer == "Http")] \| length == 0` | deps 端点名下**不许**出现跨层出口 —— 正是这个 bug 的照妖镜 |

**教训**：凡是要给"外部入口"复用的逻辑，别放在 handler 方法里 —— 内部调用会被 web 插件
当成一次新请求入口，污染依赖图的端点维度。

## 七、还没验的（留给下一次）

- `Redis` / `Jedis` 与 `Mysql` 的 `componentName` **实测值**（需中间件真的在跑；断言写的是 `test("Redis|Jedis")` / `test("MySQL|Mysql")` 双形态匹配）。
- 中间件在场时是否三条边都为绿、`mysql?sleepMs=300` 的 `maxLatency` 是否 ≥ 300ms。
- 外呼**不可达**时 `Http(#128)` 边是否变红（本机 httpbin 可达，验证不了这一支）。
