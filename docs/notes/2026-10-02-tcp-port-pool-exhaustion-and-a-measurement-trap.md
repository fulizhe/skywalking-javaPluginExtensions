# 本机 TCP 端口池耗尽的诊断

- **何时读**：仪表盘页面白屏、控制台一片 `net::ERR_INVALID_ARGUMENT` / `Xxx is not defined`、
  要回答"这应用到底能跑多快 RPS"、或压测时怀疑"某个组件在疯狂建连接"时。
- **性质**：**经验沉淀**。数字来自 2026-10-02 本机（Windows）实跑，机制已定位到 **Tomcat 源码行号**。
  走过的弯路集中在**附录 A**，正文不掺。
- **读者假设**：知道 TIME_WAIT 和 keep-alive 大概是什么。

---

## 零、三个结论

> **① 端口池真因**：Tomcat 在 `prepareResponse()` 里按**状态码**决定是否关连接
> （`statusDropsConnection()`，8 个码 `{400,408,411,413,414,500,501,503}`）——
> **协议层行为**，于是每个这样的请求泄漏一条 TCP 连接。
> 压测路径集里这类端点占比高（默认档 2/5、`-AllEndpoints` 6/60），
> 数百 rps 下即产生上百条连接/秒，远超 `13977/120 ≈ 116` 条/秒的填池阈值。
> 实测：含这类端点的档 **28 秒打满**；`-NormalOnly` 峰值 **465**、零错误。
> **与告警 webhook 无关；应用层修不了**（Tomcat 自己写这个头）→ 约束落在压测档位上。
> 源码定位见 §2.2。
>
> **② 告警链路自身的问题（已修）**：每条告警投递完都 `disconnect()`，等于每次都声明
> "这条连接不再复用"，把 JDK 的 keep-alive 缓存清空 → 告警连接数**无上界**。
> 去掉后复用生效：200 条告警共用 **4~5** 条连接（= `http.maxConnections` 默认值），
> 投递 215/215 全成功。详见 §四。
>
> **③ 要"应用能跑多快"用 `stress.ps1 -MaxRps`**：唯一不含慢端点与泄漏型状态码的档。
> 其他档的天花板都由别的东西决定（`-NormalOnly` 被 1.5s 的 `/longTimeTask` 压到 ~50 RPS、
> `-AllEndpoints` 被 65s 端点压到 ~40 QPS）。

---

## 一、诊断路径

### 1.1 症状：`.html` 能开，`.js`/`.css` 全挂

```
GET http://localhost:9600/dashboards/range.js     net::ERR_INVALID_ARGUMENT
GET http://localhost:9600/lib/echarts.min.js     net::ERR_INVALID_ARGUMENT
Uncaught ReferenceError: SRange is not defined   at metrics.html:227
```

**这个组合本身就是判据**：HTML 是第一个请求、抢到了资源；后面那些是子资源，等它们发起时已经抢不到了。
`SRange is not defined` 只是症状 —— 真正没加载的是它依赖的 `range.js`。

### 1.2 决定性的一步：分清"服务端坏了"和"客户端建不了连"

```powershell
curl.exe -sv --noproxy "*" http://127.0.0.1:9600/dashboards/range.js
```

```
*   Trying 127.0.0.1:9600...
* getsockname() failed with errno 10022: Invalid arguments
* Immediate connect fail for 127.0.0.1: Invalid arguments
```

`getsockname()` 是**客户端本地**调用。它失败说明**本机连 socket 都建不出来**，
服务端根本没机会参与。`errno 10022` = Winsock `EINVAL`。

### 1.3 量化：端口池确实满了

```powershell
(Get-NetTCPConnection -State TimeWait).Count     # 13,662
netsh int ipv4 show dynamicport tcp             # 端口数 = 13,977   → 98%
```

TIME_WAIT 每条在 Windows 上挂 **120 秒**，所以新建速率超过
`13977 / 120 ≈ 116 条/秒`，池子就会被填满。

### 1.4 一个可靠信号：耗尽后 TIME_WAIT 会**停止衰减**

实测卡在 **17,288** 十分钟不动（TTL 只有 120s）—— 池子满了连回收都停摆，
**它自己恢复不了**，必须 `netsh int tcp reset`。

> **判据**：TIME_WAIT 数量稳定不降 = 已经在耗尽了。正常情况下它应该每 2 分钟明显掉一截。

### 1.5 应急与清场

| 手段 | 命令 | 说明 |
| --- | --- | --- |
| 清场 / 应急 | `netsh int tcp reset` | 立刻清空，但**会断掉所有 TCP 连接**（压测、浏览器、应用自己的 keep-alive 连接全断）。测端口类问题前必须先做，否则起点不干净、测不出东西 |
| 扩容（**不推荐当修复**） | `netsh int ipv4 set dynamicport tcp start=1024 num=16383` | 端口池翻倍。掩盖问题且依赖运维环境，JVM 侧无法保证别人机器也配了 |

---

## 二、真因：Tomcat 按状态码强制关连接

### 2.1 对照实验：先排掉"告警"

**负载生成器用"单线程 + 池大小 1"的进程内探针**（它自身最多占 1 条连接），
各打 200 次请求：

| 端点 | 状态码 | 是否产生告警 | 新增连接 |
| --- | --- | --- | --- |
| `/hello` | 200 | 否 | **2** |
| `/status/500` | 500 | **否**（命中 `error_ignore_rules`） | **252** |
| `/api/trace-alert-demo/error` | 500 | 是 | **235** |

**②与③几乎相同，而②根本没有告警** —— 这一条就否掉了"告警 webhook 建连接"。

### 2.2 源码定位

实测用的 tomcat-embed-core 版本是 **9.0.83**（Spring Boot 2.7.18 内嵌）。
判据在 `Http11Processor` 的 [`statusDropsConnection()`](https://github.com/apache/tomcat/blob/9.0.83/java/org/apache/coyote/http11/Http11Processor.java#L190-L199)：

```java
// org/apache/coyote/http11/Http11Processor.java:194-199（Tomcat 9.0.83）
/**
 * Determine if we must drop the connection because of the HTTP status code. Use the same list of codes as
 * Apache/httpd.
 */
private static boolean statusDropsConnection(int status) {
    return status == 400 /* SC_BAD_REQUEST */ || status == 408 /* SC_REQUEST_TIMEOUT */ ||
            status == 411 /* SC_LENGTH_REQUIRED */ || status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
            status == 414 /* SC_REQUEST_URI_TOO_LONG */ || status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
            status == 503 /* SC_SERVICE_UNAVAILABLE */ || status == 501 /* SC_NOT_IMPLEMENTED */;
}
```

主调用点在 `prepareResponse()` 内、写响应头之前 ——
[`L989-996`](https://github.com/apache/tomcat/blob/9.0.83/java/org/apache/coyote/http11/Http11Processor.java#L989-L996)：

```java
// Http11Processor.java:989
if (keepAlive && statusDropsConnection(statusCode)) {
    keepAlive = false;
}
if (!keepAlive) {
    if (!connectionClosePresent) {                              // ← L994
        headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
    }
}
```

| 项 | 值 |
| --- | --- |
| 码集合 | **400, 408, 411, 413, 414, 500, 501, 503**（8 个） |
| 清单来源 | 与 **Apache httpd 同一份**（注释原文）—— 这是它反常的原因 |
| 版本核对 | 逐行读的是 **9.0.83** tag 源码；**行为经本机 9.0.83 运行时实测交叉验证**（不是"读 A 版、测 B 版"） |
| 验证 | 35 个状态码扫描 + 补测 411/414 = **8/8 吻合**；`-AllEndpoints` 60 条逐条核对 = **0 处不一致** |

> 行号会随 Tomcat 版本漂移（9.0.52 上同一段是 L195-204 / L957）。上面给的是 **9.0.83** 的
> permalink，换版本请按方法名 `statusDropsConnection` 搜，别直接沿用行号。

**三个推论**：

1. **不是错误派发。** 这是协议层连接管理，只看 `statusCode`，与是否抛异常、是否派发、
   body 是否提交**全都无关**。实证：`/status/500` 是 `setStatus()` + 正常 return，
   响应体仍是 controller 自己的 Map（不是 Spring Boot 错误 JSON），**照样关连接**。
2. **404 为什么不关** —— 它根本不在 httpd 那份清单里。语义上"资源不存在"意味着
   **请求本身是好的**，连接可以继续复用。
3. **应用层为什么盖不住** —— [`L994`](https://github.com/apache/tomcat/blob/9.0.83/java/org/apache/coyote/http11/Http11Processor.java#L994)
   是 `if (!connectionClosePresent)`，Tomcat 在 `prepareResponse()` 里**自己 `headers.addValue()`**，
   应用层预先设的头挡不住（实测响应里两个 `Connection` 头并存，按 HTTP 语义 `close` 胜出）。

**次要调用点**：[`service()` L396-398`](https://github.com/apache/tomcat/blob/9.0.83/java/org/apache/coyote/http11/Http11Processor.java#L396-L398)
—— 请求处理抛 `ServletException` 时的兜底 `setErrorState(ErrorState.CLOSE_CLEAN, null)`。

**两个"看着像错误但不是"的**（算比例时容易算错）：

| 端点 | 实际状态码 | 会泄漏吗 |
| --- | --- | --- |
| `/api/hutool-demo/error-call` | **200**（错误在**出站**那次） | 否 |
| `/api/exists/1` | **404**（不在清单） | 否 |

### 2.3 端到端对照（同机 16 线程 180 秒，只换路径集）

| 组 | 路径集 | 告警 | 客户端 RPS | TIME_WAIT 峰值 | 结果 |
| --- | --- | --- | --- | --- | --- |
| A | 全 2xx（4 条） | 0 | 1283.5 | 2,868 | ✅ 自然回落 |
| **A'** | **`-NormalOnly`（5 条）** | **0** | — | **465** | ✅ 自然回落 |
| B | 全 5xx（2 条） | 大量 | 425.9 | **17,396** | ❌ **28 秒打满** |

A' 与 B 的差别是**数量级级别**的。B 组随后 19,802 个请求因
`Invalid argument: connect` 失败、客户端开始重试；A' 组面板错误率 0.00%。

### 2.4 为什么会打满

逐条实测各档里**会泄漏连接**（返回清单内状态码）的路径数：

| 路径集 | 泄漏路径数 | 明细（全部 500） |
| --- | --- | --- |
| `stress.ps1` 默认档 | **2 / 5** | `trace-alert-demo/error`、`trace-alert-demo/http500` |
| `stress.ps1 -AllEndpoints` | **6 / 60** | `/helloException`、`/logError`、`trace-alert-demo/error`、`trace-alert-demo/http500`、`/status/500`、`hutool-demo/status/500` |
| `-NormalOnly` / `-MaxRps` | **0** | — |

16 线程 ~400 rps 下，默认档 40% 是这类端点 → **约 160 条新连接/秒** ≫ 116 条/秒阈值。
**只需一次连续压测就能填满端口池** —— 与告警无关，与插件无关。

### 2.5 该修在哪一层

| 方案 | 结论 |
| --- | --- |
| 改 demo-app 让这些端点可复用连接 | ❌ **不可行** —— Tomcat 在 `prepareResponse()` 里自己写头，应用层盖不住（§2.2 推论③） |
| 客户端换池化实现 | ❌ **无效** —— `HttpURLConnection` 三种写法与显式 `PoolingHttpClientConnectionManager` 实测无差别 |
| **压测避开这类端点**（`-MaxRps` / `-NormalOnly` / `-WithDeps`） | ✅ **采纳** —— `-NormalOnly` 实测峰值 465、零错误 |
| 压之前先 `netsh int tcp reset` | ✅ 配合上一条；`stress.ps1` 已在开跑前检测水位并提示（≥3000 时提醒） |

> `stress.ps1` 的注释里也写了这段硬约束 —— "压测把端口池打爆"这件事**没有任何报错**，
> 只表现为"仪表盘坏了"，不写下来的话每个人都会先怀疑插件。

---

## 三、压测档位的分档

分工按**实测单请求耗时**与**有无泄漏型状态码**划分，写进 `stress.ps1` 头注释与 `stress-slow.ps1`：

| 档 | 范围 | 用途 | 天花板由什么决定 |
| --- | --- | --- | --- |
| `stress.ps1 -MaxRps` | **只 ms 级、只 2xx**，5 条 | **冲吞吐上限** | 服务端本身 |
| `stress.ps1` 默认 | ms 级 + **2/5 条 5xx** | 指标 + 告警耦合 | 告警自环 |
| `stress.ps1 -NormalOnly` | 业务正常端点（含 `/longTimeTask`） | 指标聚合 | `/longTimeTask` 的 1.5s sleep |
| `stress.ps1 -AllEndpoints` | **所有** 60 条 | 覆盖度 | 65s 慢端点 |
| `stress-slow.ps1` | **只慢的**，手动执行 | 讲解 / 演示 / slow 统计 | — |

对账规则：**`-AllEndpoints` 里凡是 >1s 的，`stress-slow.ps1` 里都要有一条对应项。**

### 3.1 `-MaxRps` 的两处取舍

之前每档都压不出上限，因为都掺了慢东西。`-MaxRps` 是**唯一不受慢端点与泄漏型状态码干扰**的档：

```
/hello  /queryDbByMybatis  /queryDbByJdbc  /statisticJVM  /statisticLogs
```

- **不含 `/fullSample?deps=false`**（虽然只要 4.5ms）—— 它内部自调一次
  `/api/trace-alert-demo/ok`，等于每个业务请求变**两个 HTTP 往返**，压出来的是
  "回环放大"的吞吐，不是应用吞吐。
- **不含 `/longTimeTask`** —— 那正是 `-NormalOnly` 只有 50 RPS 的原因。

未显式给 `-Threads` 时用 32（`$PSBoundParameters.ContainsKey('Threads')` 判断）——
**显式给了就尊重用户，不猜意图**。

> 💡 别按参数名估耗时：`?sleepMs=30` 实测 **3.0s**（H2 的 `SLEEP` 有秒级下限），
> 而"同族的" `/api/deps-demo/kafka?op=consume` 只有 **0.31s**。要判断就 curl 一下。

---

## 四、告警 webhook 自身的连接数问题（与端口池正交，已修）

`HttpTraceAnomalyListener` 每条告警投递完都 `connection.disconnect()`。

JDK 对 `disconnect()` 的契约是"调用它即表示这条 Connection 不会再被复用"，而
`HttpURLConnection` 的隐式 keep-alive（`sun.net.www.http.KeepAliveCache`）正是靠连接用完
**留在缓存里**给下一次取用 —— 逐条 disconnect 等于每次都把缓存清空，**告警一多连接数就线性增长**。

### 4.1 实测

判据用附录 A.1 的二值法：**数 app 手里有几条 ESTABLISHED**（不是"新增连接总数"）。

| 变体 | 200 条告警打完后 app 的 ESTABLISHED | 投递 |
| --- | --- | --- |
| 保留 `disconnect()` | **0**（每条一条新建，用完即毁） | 215/215 成功 |
| 去掉 `disconnect()` | **4~5**，发告警前后纹丝不动 | 215/215 成功，**100%** |

**4~5 恰为 JDK `http.maxConnections` 的默认值** —— 连接确实被缓存复用并握着。

### 4.2 改法与代价

- 去掉 `disconnect()`，同时**加回 `drainBody()`** —— 它与去掉 `disconnect()` 是
  **同一件事的两半**：连接要留在缓存，前提是 body 已读完（socket 里不能有未消费的字节）。
  `drainBody` 另按状态分流（成功读 `getInputStream()`、非 2xx 读 `getErrorStream()`，
  因为 4xx/5xx 时前者直接抛 `IOException`）—— 错误响应的 body 也需要排空。
- **收益**：告警链路连接数从**无上界**变成**有上界（JDK `http.maxConnections`，默认 5）**，
  与告警量无关。这正是「监控只能是助力，而不是阻碍」在这个场景要的性质。
- **代价**：最多多留 5 条 socket 到 `http.keepAlive.timeout` 后由缓存回收，对进程无感。
- **不需要池化客户端**：显式 `PoolingHttpClientConnectionManager` 是为了绕开
  `HttpURLConnection` 的隐式行为，而**隐式行为本来就是正常的** —— 问题只在于
  `disconnect()` 主动把它关掉了。为此加 httpclient 依赖（+1.12 MB）是白付代价。

---

## 五、仪表盘白屏了怎么查

```powershell
# 1) 是不是端口池满了（最高概率）
(netstat -ano -p tcp | Select-String 'TIME_WAIT' | Measure-Object).Count    # >= 3000 基本就是它
# 2) 清空并重跑
netsh int tcp reset
# 3) 用不含泄漏型状态码的档
pwsh ./scripts/stress.ps1 -MaxRps -Continuous -Threads 16
```

> ⚠️ 池子耗尽后 **TIME_WAIT 会停止衰减**（实测卡在 17,288 十分钟不动，尽管 TTL 只有 120s），
> **它自己恢复不了** —— 这是"已经耗尽"的可靠信号，也是必须 `reset` 的原因。

---

## 附录 A、走过的弯路

三个错误代价很大：绕了一圈、改了两轮代码、还把错误机制写进了三处文档。
**记在这里，正文不掺。**

### A.1 测量污染：把测量工具算成了被测方

```powershell
# 我以为在测"每条告警建几条连接"
1..200 | ForEach-Object { curl.exe -s -o NUL "http://127.0.0.1:9600/api/trace-alert-demo/error" }
# 测得：新增连接 213  →  推断 1.07 连接/告警
```

**200 次 `curl.exe` 调用本身就是 200 条新 TCP 连接。** 那 213 里 200 条是测量工具的。

> **教训**：测量工具的副作用必须可忽略。逐条 `curl` / `wget` / 单发请求的脚本，
> **每次调用都是一条新连接**。要么用进程内池化客户端，要么把工具的连接数单独测出来扣掉。

**判据换成二值法**（这是终结 A.1 的工具，本身不是错误）——
不看"新建了多少"，看**被测方手里握着几条 ESTABLISHED**：

```powershell
$appPid = (Get-NetTCPConnection -LocalPort 9600 -State Listen).OwningProcess
@((netstat -ano -p tcp) | Select-String "127.0.0.1:9600\s" |
   Where-Object { $_ -match 'ESTABLISHED' -and ($_ -split '\s+')[-1] -eq "$appPid" }).Count
```

连接用完只有两种归宿 —— **被销毁**（→ 0 条）或**被缓存持有**（→ N 条），必居其一。
这个信号**不会被测量工具污染**（工具的连接在它自己进程名下）、**是二值的**、
**能直接对应到上界**（§4.1 一次就测准了"去不去 disconnect"）。

### A.2 归因草率：被错误结论带着跑，还去证伪无关的假设

被 A.1 的结论带着，为它做了两轮代码改动（换池化客户端 → 回退 → 恢复 → 再回退），
并先后"证伪"了四个假设：

| 假设 | 结论 |
| --- | --- |
| JDK 8 的 keep-alive 有缺陷 | 证伪（JDK 17 同样） |
| `http.*` 系统属性被改 | 证伪（未设置） |
| 负载压力导致复用失效 | 证伪（进程外满负载仍复用） |
| 响应体被 64KB 上限截断 | 证伪（响应体仅 15 字节） |

**四个全部无关** —— 真正的证据是 `curl -D -` 一次就能看到的响应头，我一次都没去读。

> **教训**：当"某组件在疯狂建连接"出现时，**第一件事是换一个不污染的负载生成器去测它**，
> 而不是立刻开始修。而当连续多个假设被证伪时，**该被质疑的是最初那个观测**。

### A.3 机制想当然：编了一个"看起来很合理"的机制

我一度断定"Tomcat 对走 `/error` 错误派发的 4xx/5xx 响应强制发 `Connection: close`"，
并把这个说法写进了 note、`stress.ps1` 注释、README 三处。

**被两件事推翻**：

1. `/status/500` 是 `setStatus()` + 正常 return，**没有错误派发**，照样关连接；
2. 401/403/404/405/429/502/504 **都不关** —— "4xx/5xx"这个描述本身就不成立。

真因是 `Http11Processor.statusDropsConnection()` 的** 8 个特定状态码**（§2.2），
和错误派发毫无关系。

> **教训**：机制别猜。响应头一次 `curl -D -`，机制一次读源码 —— 都比"编一个合理的故事"便宜。

---

## 附录 B、PowerShell 踩坑

| 坑 | 表现 | 绕法 |
| --- | --- | --- |
| `Get-NetTCPConnection` 在两万连接下**每次调用 1~2 秒** | 放进采样循环必然超时（写过 40 次循环，直接挂） | 循环里只用 `netstat -ano`（原生、亚秒） |
| `$pid` 是 PowerShell **保留变量** | 赋值报 `Cannot overwrite variable PID`，统计出的 PID 全错 | 换个名字，如 `$ownerPid` |
| `netstat` 输出**列位会错位** | 把 `LocalPort` 当成 `State`，统计出"13,873 条 ESTABLISHED"这种荒唐数 | 按 `Trim() -split '\s+'` 取 `$f[3]`=状态、`$f[4]`=PID |
| TIME_WAIT 行的 PID 恒为 0 | 想靠它找"谁建的连接"，永远是 0 | TIME_WAIT 只能看**总数**；找活证据只能看 ESTABLISHED |

---

## 附录 C、查实并已落地的三个问题

| 问题 | 修法 |
| --- | --- |
| `stress.ps1 -AllEndpoints` 含 65s 端点 → 16 线程只有 40 QPS | 确立分档 + 默认档收敛为 ms 级 → **795 RPS** |
| `-Paths` 用 `split(",")` 切坏 `{rand:min,max}` → 400 | 改 `splitPaths()`，判据"后继非空白是 `/`"，+9 条测试（`HttpLoadTest`） |
| **告警 webhook 连接数无上界** | 去掉逐条 `disconnect()` → **4~5 条封顶**（§四） |
