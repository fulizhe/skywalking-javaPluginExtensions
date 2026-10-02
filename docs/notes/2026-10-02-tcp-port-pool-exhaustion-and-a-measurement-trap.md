# 本机 TCP 端口池耗尽的诊断 —— 以及两个把我带进沟里的错误

- **何时读**：仪表盘页面白屏、控制台一片 `net::ERR_INVALID_ARGUMENT` / `Xxx is not defined`、
  压测时怀疑"某个组件在疯狂建连接"、或要用"打 N 次请求看连接涨多少"来定位问题时。
- **性质**：**经验沉淀 + 一份反面教材**。数字来自 2026-10-02 本机（Windows）实跑。
  **§3 是本篇最有价值的部分 —— 两个我自己犯下、并浪费了大量时间的测量/归因错误。**
- **读者假设**：知道 TIME_WAIT 和 keep-alive 大概是什么。

---

## 零、一句话

> **Tomcat 对 4xx/5xx 响应（404 除外）的 ERROR 派发会强制发 `Connection: close`，
> 于是每个失败请求泄漏一条 TCP 连接。** 压测路径集里 5xx 占比高（默认档 2/5、
> `-AllEndpoints` 8/60），数百 rps 下即产生上百条连接/秒，远超
> `13977/120 ≈ 116` 条/秒的填池阈值。
>
> 实测：含 5xx 的档 **28 秒打满**端口池；`-NormalOnly` 峰值 **465**、零错误。
>
> 真凶不是监控，是**压测把 5xx 打成了连接泄漏**。应用层无法修复
> （Tomcat 是**追加**自己的 `Connection: close`，盖不住），所以约束落在**压测档位选择**上。

---

## 一、诊断路径（这部分可信）

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
| 清场 / 应急 | `netsh int tcp reset` | 立刻清空，但**会断掉所有 TCP 连接**（压测、浏览器、应用自己的池化连接全断）。测端口类问题前必须先做，否则起点不干净、测不出东西 |
| 扩容（**不推荐当修复**） | `netsh int ipv4 set dynamicport tcp start=1024 num=16383` | 端口池翻倍。掩盖问题且依赖运维环境，JVM 侧无法保证别人机器也配了 |

---

## 二、真因：5xx 响应的 `Connection: close`

### 2.1 一步到位的对照实验

**负载生成器用"单线程 + 池大小 1"的进程内探针**（它自身最多占 1 条连接，可忽略），
各打 200 次请求：

| 端点 | 状态码 | 是否产生告警 | 新增连接 |
| --- | --- | --- | --- |
| `/hello` | 200 | 否 | **2** |
| `/status/500` | 500 | **否**（命中 `error_ignore_rules`） | **252** |
| `/api/trace-alert-demo/error` | 500 | 是 | **235** |

**②与③几乎相同，而②根本没有告警** —— 这一条就否掉了"告警 webhook 建连接"。

### 2.2 看响应头，一眼定案

```powershell
curl.exe -s -D - -o NUL --noproxy "*" "http://127.0.0.1:9600/hello"
curl.exe -s -D - -o NUL --noproxy "*" "http://127.0.0.1:9600/status/500"
```

```
HTTP/1.1 200
Content-Length: 24                       ← 正常，可复用

HTTP/1.1 500
Transfer-Encoding: chunked
Connection: close                         ← 强制关闭
```

**不是"走 `/error` 错误派发"那么简单** —— `/status/500` 是 `setStatus()` + 正常 return，
本来就没有错误派发，**照样关连接**。逐个端点测下来，真正的规律是**状态码**：

| 状态 | Connection | 编码 |
| --- | --- | --- |
| 200 / 204 / 301 | — | CL / 无 / chunked |
| **404** | **—**（不关） | 视实现 |
| **400 / 500 / 503** | **close** | chunked 或 CL |

**404/410 是特例**（不做 ERROR 派发），其余错误码都关 —— 与是否有 `Content-Length`
**无关**（`/api/trace-alert-demo/http500` 带了 CL 照样关），也与是否抛异常**无关**。

> 试过在应用层显式 `response.setHeader("Connection", "keep-alive")` 覆盖 —— **无效**。
> Tomcat 不替换、而是**追加**自己的头，最终响应里两个 `Connection` 并存
> （`keep-alive` + `close`），按 HTTP 语义 `close` 胜出。**这条路已放弃**，
> 应用层无法阻止（除绕开容器语义的提前 commit 之外，不值得）。

### 2.3 端到端对照（同机 16 线程 180 秒，只换路径集）

| 组 | 路径集 | 告警 | 客户端 RPS | TIME_WAIT 峰值 | 结果 |
| --- | --- | --- | --- | --- | --- |
| A | 全 2xx（4 条） | 0 | 1283.5 | 2,868 | ✅ 自然回落 |
| **A'** | **`-NormalOnly`（5 条）** | **0** | — | **465** | ✅ 自然回落 |
| B | 全 5xx（2 条） | 大量 | 425.9 | **17,396** | ❌ **28 秒打满** |

A' 与 B 的差别是**数量级级别**的。B 组随后 19,802 个请求因
`Invalid argument: connect` 失败、客户端开始重试；A' 组面板错误率 0.00%。

### 2.4 为什么会打满

| 路径集 | 5xx 占比 |
| --- | --- |
| `stress.ps1` 默认档 | **2 / 5** |
| `stress.ps1 -AllEndpoints` | **8 / 60** |

16 线程 ~400 rps 下，40% 是 5xx → **约 160 条新连接/秒** ≫ 116 条/秒阈值。
**只需一次连续压测就能填满端口池** —— 与告警无关，与插件无关。

### 2.5 该修在哪一层

| 方案 | 结论 |
| --- | --- |
| 改 demo-app 让 5xx 可复用连接 | ❌ **已试，不可行** —— Tomcat 追加自己的 `Connection: close`，应用层盖不住（§2.2） |
| 客户端换池化实现 | ❌ **已试，无差别** —— `HttpURLConnection` 三种写法与显式池化分别是 202 / 207 / 207 条连接 |
| **压测避开 5xx**（`-NormalOnly` / `-WithDeps`） | ✅ **采纳** —— 实测峰值 465，零错误 |
| 压 5xx 前先 `netsh int tcp reset` | ✅ 配合上一条使用；`stress.ps1` 已在开跑前检测水位并提示 |

> `stress.ps1` 现在的行为：含 5xx 的档开跑前会读 TIME_WAIT 水位，
> **≥3000 时明确提示先 `netsh int tcp reset`**，并说明后果（仪表盘白屏而非插件故障）。
> 注释里也写了这段硬约束 —— 因为"压测把端口池打爆"这件事**没有任何报错**，
> 只表现为"仪表盘坏了"，不写下来的话每个人都会先怀疑插件。

---

## 三、反面教材：两个错误

### 3.1 错误一：用 `curl` 逐条打，把测量工具算成被测方

```powershell
# 我以为在测"每条告警建几条连接"
1..200 | ForEach-Object { curl.exe -s -o NUL "http://127.0.0.1:9600/api/trace-alert-demo/error" }
# 测得：新增连接 213  →  推断 1.07 连接/告警
```

**200 次 `curl.exe` 调用本身就是 200 条新 TCP 连接。** 那 213 里 200 条是测量工具的。

> **通用教训**：测量工具的副作用必须可忽略。逐条 `curl` / `wget` / 单发请求的脚本，
> **每次调用都是一条新连接**。要么用进程内池化客户端，要么把工具的连接数单独测出来扣掉。

### 3.2 错误二：被错误结论带着跑，还去"证伪"无关的假设

"每条告警一条连接"这个错误结论，导致我为它做了两轮代码改动（换自带池化 HTTP 客户端 →
回退 → 再恢复 → 再回退），并先后**证伪了四个假设**：

| 假设 | 结论 |
| --- | --- |
| JDK 8 的 keep-alive 有缺陷 | 证伪（JDK 17 同样） |
| `http.*` 系统属性被改 | 证伪（未设置） |
| 负载压力导致复用失效 | 证伪（进程外满负载仍复用） |
| 响应体被 64KB 上限截断 | 证伪（响应体仅 15 字节） |

**四个假设全部无关** —— 真正的证据是响应头那两行，`curl -D -` 一次就能看到，
我却一次都没去读。

> **通用教训**：当"某组件在疯狂建连接"这个猜想出现时，**第一件事是换一个不污染的
> 负载生成器去测它**（进程内池化客户端，自身连接数可忽略），而不是立刻开始修。
> 而当连续多个假设被证伪时，**该被质疑的是最初那个观测**。

### 3.3 也顺带证伪过的（这些是真的，结论有效）

| 观测 | 结论 |
| --- | --- |
| `disconnect()` 是元凶？ | ❌ 去掉它后连接数没变（207 vs 202） |
| 显式池化客户端能解决？ | ❌ 三种写法分别 202 / 207 / 207，**无差别** |
| 响应体 64KB 上限？ | ❌ webhook 响应仅 15 字节 |

### 3.4 顺带暴露的 PowerShell 坑

| 坑 | 表现 | 绕法 |
| --- | --- | --- |
| `Get-NetTCPConnection` 在两万连接下**每次调用 1~2 秒** | 放进采样循环必然超时（我写过 40 次循环，直接挂） | 循环里只用 `netstat -ano`（原生、亚秒） |
| `$pid` 是 PowerShell **保留变量** | 赋值报 `Cannot overwrite variable PID`，统计出的 PID 全错 | 换个名字，如 `$ownerPid` |
| `netstat` 输出**列位会错位** | 把 `LocalPort` 当成 `State`，统计出"13,873 条 ESTABLISHED"这种荒唐数 | 按 `Trim() -split '\s+'` 取 `$f[3]`=状态、`$f[4]`=PID |
| TIME_WAIT 行的 PID 恒为 0 | 想靠它找"谁建的连接"，永远是 0 | TIME_WAIT 只能看**总数**；找活证据只能看 ESTABLISHED |

### 3.5 唯一查实并已落地的问题（与端口无关）

`stress.ps1 -AllEndpoints` 的默认路径集含 `/fullSample` 裸调（`deps=true`，单请求 **5.5s**）
等秒级端点，16 线程只能跑 **~40 QPS**，看起来像"插件性能退化"。

默认档收敛为 5 条 ms 级后 → **795 RPS**。详见 §四。


---

## 四、压测档位的分档（独立结论，可信）

排查顺手把"哪个端点该放哪"定成了可核对的口径，写进 `stress.ps1` 头注释与 `stress-slow.ps1`：

| 档 | 范围 | 用途 |
| --- | --- | --- |
| `stress.ps1` 默认 | **只 ms 级**，5 条 | 吞吐 / 稳定性 |
| `stress.ps1 -AllEndpoints` | **所有** 60 条（含慢端点与 5xx） | 覆盖度，**QPS 低是预期的** |
| `stress-slow.ps1` | **只慢的**，手动执行 | 讲解 / 演示 / slow 统计 |

判据是**实测单请求耗时**，不是功能分类。对账规则：
**`-AllEndpoints` 里凡是 >1s 的，`stress-slow.ps1` 里都要有一条对应项。**

> 💡 别按参数名估耗时：`?sleepMs=30` 实测 **3.0s**（H2 的 `SLEEP` 有秒级下限），
> 而"同族的" `/api/deps-demo/kafka?op=consume` 只有 **0.31s**。要判断就 curl 一下。

> ⚠️ **默认档含 2/5 条 5xx** —— 那是它的**设计意图**（压"指标 + 告警耦合"），
> 但按 §2 也会带来连接泄漏。纯测吞吐请用 `-NormalOnly`（不含 5xx）。

---

## 五、现在的问题状态

**归因已完成（§2），已按"压测避开 5xx"落地（§2.5）。**

| 状态 | 项 |
| --- | --- |
| ✅ 归因完成 | 4xx/5xx 响应（404 除外）带 `Connection: close` → 每失败请求一条新连接 |
| ✅ 已排除 | 告警 webhook、JDK 版本、系统属性、负载压力、64KB 上限、`disconnect()`、显式池化、**应用层显式 `Connection: keep-alive`** |
| ✅ 已落地 | `stress.ps1` 开跑前检测 TIME_WAIT 水位并提示；三档语义；默认档去慢端点（8.8 → 795 RPS）；`splitPaths` 修 `{rand:min,max}` 被切坏 |
| ❌ 无法修复 | Tomcat 强制 `Connection: close` 应用层盖不住。**这是容器行为，不是缺陷** |

**若压测中仪表盘白屏，按这个顺序查**：

```powershell
# 1) 是不是端口池满了（最高概率）
(netstat -ano -p tcp | Select-String 'TIME_WAIT' | Measure-Object).Count    # >= 3000 基本就是它
# 2) 清空并重跑
netsh int tcp reset
# 3) 用不含 5xx 的档
pwsh ./scripts/stress.ps1 -NormalOnly -Continuous -Threads 16
```

> ⚠️ 池子耗尽后 **TIME_WAIT 会停止衰减**（实测卡在 17,288 十分钟不动，尽管 TTL 只有 120s），
> **它自己恢复不了** —— 这是"已经耗尽"的可靠信号，也是必须 `reset` 的原因。
