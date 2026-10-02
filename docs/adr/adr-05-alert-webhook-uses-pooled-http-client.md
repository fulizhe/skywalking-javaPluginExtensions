# 告警 webhook 改用自带池化 HTTP 客户端（不复用 HttpURLConnection）

告警 webhook 原本用 `java.net.HttpURLConnection` 逐条 POST。这份实现的连接复用**依赖 JDK 内部的 `KeepAliveCache`**（隐式、无显式上界），而该缓存在 agent 的 `PluginClassLoader` 运行时里**实测不生效**：

> 空闲应用、串行 200 个错误请求（各触发 1 条 ERROR 告警）→ **213 条新 TCP 连接**；
> 而**完全相同的代码在进程外复用率 100%**（50 次顺序 POST，到目标端口的连接数 10 → 10）。

高频错误下（本机 3 分钟 1.2 万条告警 ≈ 67 连接/秒）新建连接会填满 Windows 默认的
**13,977** 个动态端口（TIME_WAIT 每条挂 120s，稳态占用 = 速率 × 120）。打满后**本机任何**
新建连接都失败（`errno 10022` / `"Invalid argument: connect"`），表现为业务页面加载不出
js/css —— **监控把业务打挂了**，违背插件自己的「监控只能是助力，而不是阻碍」。

**决策**：`HttpTraceAnomalyListener` 改用**插件自带 shade 的 Apache HttpClient 4.5**，
静态单例 + `PoolingHttpClientConnectionManager(maxTotal=4)`。让"连接数有硬上界"成为
**可验证的性质**，而不是依赖对端行为或 JVM 隐式实现。

## 背景：这个功能为什么必须先有

告警在本项目的定位是**监控完备性的先手**，不是日常功能 —— 使用者是自己的测试/告警人员，
日常用得少，但**问题到来时必须能第一时间抓住**。因此对它的要求不是"能发出去"就够了，
而是：

| 要求 | 含义 |
| --- | --- |
| 平时完全隐形 | 不产生可观测的副作用（连接、线程、内存） |
| 触发时可靠 | 一次都不能因为自身缺陷丢告警 |
| **任何情况下不拖累业务** | 这是本次事故的唯一红线 |

第三条此前没有落实 —— 告警路径自己把业务打挂了。

## Considered Options

- **只修 `HttpURLConnection` 的用法（排空 body + 不调 `disconnect()`）**：**已试，不够**。
  修完确实改善了投递可靠性（失败率 27.8% → 2.3%，队列拒绝 55% → 19.8%），
  但**连接复用仍未发生**，TIME_WAIT 照样打到 17,182（超池容量）。写法本身经隔离探针验证
  正确（进程外 0 新增连接，响应头 `Connection: keep-alive`）—— 失效点在运行时，不在代码形态。
- **只调大 `dispatch_queue_size`（当前 512）**：拒绝。队列小是**症状**不是病 ——
  消费端被端口耗尽拖慢（每条 POST 等满 5s readTimeout 或直接抛连接失败），单线程消费速率
  塌到个位数/秒，生产速率几百/秒必然填满队列。治本是让消费速率恢复，不是让丢弃变成堆积。
- **调大本机动态端口范围**（`netsh int ipv4 set dynamicport tcp ...`）：**拒绝作为方案**。
  这是止血 —— 掩盖"连接数无上界"这个真问题，且依赖运维环境（JVM 侧无法保证）。
- **手写 HTTP/1.1 裸 Socket 保住零依赖**：拒绝。省下 1MB，但要在插件里手搓 chunked /
  Content-Length 缺失 / 超时 / 重连 —— 用第二个 bug 温床换一个体积数字，不划算。
- **`java.net.http.HttpClient`（JDK 11+）**：拒绝。插件字节码基线是 **Java 8**（ADR-01），
  演示运行时默认也是 JDK 8，引入即破坏兼容性。

## 已排除的假设（都实测过，别再重查）

| 假设 | 结论 |
| --- | --- |
| JDK 8 的 keep-alive 实现有缺陷 | **证伪** —— 换 JDK 17 起应用，TIME_WAIT 照样打到 18,077 |
| 有人改过 `http.maxConnections` 等系统属性 | **证伪** —— `jcmd VM.system_properties` 无任何 `http.*` / `sun.net.*` |
| 负载压力导致复用失效 | **证伪** —— 进程外探针在满负载（4 线程持续压）下依然 0 新增连接 |
| `drainBody` 的 64KB 上限截断了响应体 | **证伪** —— webhook 响应体实测仅 15 字节 |

> ⚠️ **具体机制仍未查清**。能确定的只是"该行为发生在 agent 运行时内、进程外不复现"。
> 这不影响决策：**显式池化不依赖任何隐式行为**，机制不清也照样成立。

## Consequences

- **连接数有硬上界（4）**。池满时 `PoolingHttpClientConnectionManager` 阻塞
  `connectionRequestTimeout`（= connectTimeout，默认 3s）后抛
  `ConnectionPoolTimeoutException`，被 catch 计入失败 —— **宁可少投一条，也不给端口池加压**。
- **jar 体积 2.83 MB → 3.94 MB（+1.12 MB）**。含 httpclient + httpcore + commons-logging。
- **必须 shade 重定位** `org.apache.http` 与 `org.apache.commons`（已加到插件 pom 的
  `artifactSet` + `relocations`）。不重定位会与业务 classpath 里的 httpclient 争版本 ——
  agent `plugins/` 与应用 classpath 是两个 ClassLoader，但类名相同仍会撞。
- **commons-logging 是运行期硬依赖**，不是可选传递依赖：漏掉会在 `HttpClients.custom()`
  时抛 `NoClassDefFoundError: org/apache/commons/logging/LogFactory`
  （`org.apache.http.conn.ssl.AbstractVerifier` 静态初始化就要 `LogFactory`）。
  这个坑由 `HttpTraceAnomalyListenerTest` 抓到 —— 单测里跑 `HttpTraceAnomalyListener`
  类初始化就会暴露，正是它该存在的位置。
- **关闭自动重试**（`disableAutomaticRetries`）。默认重试对 POST 危险：业务端点可能已经
  收到并处理了请求，重试会造成重复告警。宁可不重试。
- **响应体用 `EntityUtils.consumeQuietly` 读干净**。只 `close` 会让连接在池里变成半死状态。
  这是 A 方案里唯一被保留的结论 —— 它是对的，只是**单独不够**。
- **超时配置沿用既有 config key**（`webhook_connect_timeout_ms` / `webhook_read_timeout_ms`），
  不新增开关；另新增 `connectionRequestTimeout`（取 connect 值）——"取连接"这一步也必须有界。

## 验证

- `mvn -pl logfile-reporter-plugin test` —— **195/195 通过**。
- jar `3.94 MB`；`org/apache/http/**` 原始路径残留 **0** 条（全部重定位到
  `org/apache/skywalking/apm/dependencies/http/**`）。
- **待补**：重启应用 + 压测，确认 TIME_WAIT 随告警数保持平坦（连接数不随告警量增长）。
  验收命令见 `docs/notes/2026-10-02-alert-webhook-port-exhaustion.md` §5。

## 参考

- 实跑排查全过程与实测数据：`docs/notes/2026-10-02-alert-webhook-port-exhaustion.md`
- 字节码基线 Java 8 / 构建工具链 JDK 17：ADR-01
- 插件定位与"监控不得成为业务负担"：仓库根 `AGENTS.md` 的 AI PROMPT 段
