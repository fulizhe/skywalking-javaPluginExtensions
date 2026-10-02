# 本机 TCP 端口池耗尽的诊断 —— 以及一个把我带进沟里的测量错误

- **何时读**：仪表盘页面白屏、控制台一片 `net::ERR_INVALID_ARGUMENT` / `Xxx is not defined`、
  压测时怀疑"某个组件在疯狂建连接"、或要用"打 N 次请求看连接涨多少"来定位问题时。
- **性质**：**经验沉淀 + 一份反面教材**。数字来自 2026-10-02 本机（Windows）实跑。
  **§3 是本篇最有价值的部分 —— 一个我自己犯下、并浪费了大量时间的测量错误。**
- **读者假设**：知道 TIME_WAIT 和 keep-alive 大概是什么。

---

## 零、一句话

> **用 `curl` 逐条打请求时，每一次 `curl` 自己就是一条新 TCP 连接。**
> 我用这个方法测了三次，三次都把**测量工具的连接**算成了被测方的，
> 进而得出"每个告警新建一条连接、告警打爆了端口池"这个**完全错误的结论**，
> 并围绕它做了一整轮修复（后已回退）。

---

## 一、诊断路径本身是有效的（这部分结论可信）

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

> **判据**：TIME_WAIT 数量稳定不降 = 已经在耗尽了。
> 正常情况下它应该每 2 分钟明显掉一截。

### 1.5 应急与清场

| 手段 | 命令 | 说明 |
| --- | --- | --- |
| 清场 / 应急 | `netsh int tcp reset` | 立刻清空，但**会断掉所有 TCP 连接**（压测、浏览器、应用自己的池化连接全断）。测端口类问题前必须先做，否则起点不干净、测不出东西 |
| 扩容（**不推荐当修复**） | `netsh int ipv4 set dynamicport tcp start=1024 num=16383` | 端口池翻倍。掩盖问题且依赖运维环境，JVM 侧无法保证别人机器也配了 |

---

## 二、压测档位的分档（独立结论，可信）

这次排查顺手把"哪个端点该放哪"定成了可核对的口径，写进 `stress.ps1` 头注释与 `stress-slow.ps1`：

| 档 | 范围 | 用途 |
| --- | --- | --- |
| `stress.ps1` 默认 | **只 ms 级** | 吞吐 / 稳定性 |
| `stress.ps1 -AllEndpoints` | **所有**（含慢端点与 5xx） | 覆盖度，**QPS 低是预期的** |
| `stress-slow.ps1` | **只慢的**，手动执行 | 讲解 / 演示 / slow 统计 |

判据是**实测单请求耗时**，不是功能分类。对账规则：
**`-AllEndpoints` 里凡是 >1s 的，`stress-slow.ps1` 里都要有一条对应项。**

> 💡 别按参数名估耗时：`?sleepMs=30` 实测 **3.0s**（H2 的 `SLEEP` 有秒级下限），
> 而"同族的" `/api/deps-demo/kafka?op=consume` 只有 **0.31s**。要判断就 curl 一下。

---

## 三、反面教材：我的测量错误

### 3.1 我做了什么

```powershell
# 出发点：面板 QPS 只有 25~38，压测客户端自己报 626 RPS —— 差 25 倍，不像性能问题
# 于是想：谁在打爆端口？先验证"告警 webhook 每条都新建连接"这个猜想

$b = (netstat -ano -p tcp | Select-String "127.0.0.1:9600\s" | Measure-Object).Count
1..200 | ForEach-Object { curl.exe -s -o NUL --noproxy "*" "http://127.0.0.1:9600/api/trace-alert-demo/error" }
$a = (netstat -ano -p tcp | Select-String "127.0.0.1:9600\s" | Measure-Object).Count
# → 新增连接 = 213
```

200 次 `curl.exe` 调用 = **200 条新 TCP 连接**。我测到的 213 里，**200 条是我自己的 curl**。

### 3.2 错误如何放大成三轮修复

| 步骤 | 我以为 | 实际 |
| --- | --- | --- |
| ① | 213 ≈ 1.07/告警 → "webhook 每条告警建一条连接" | **213 里 200 是 curl** |
| ② | 修 `disconnect()` + 补读 body → 失败率 27.8%→2.3%，**看起来有效** | 失败率下降是真的（那两处确实该修），但**与端口无关** |
| ③ | 仍打满 → 判定"JDK 的隐式 keep-alive 在 agent 运行时失效" → 换自带池化 HTTP 客户端 | **这个前提也不成立** |
| ④ | 池化后仍打满 → 换 JDK 17 复测、查系统属性、做隔离探针…… | 全在验证一个**不存在的因果链** |

我甚至先后**证伪了四个假设**（JDK 版本、系统属性、负载压力、64KB 上限），
却从没回头质疑**第一个测量本身**。这是最要命的地方 ——
**当多个假设接连被证伪时，该被质疑的是最初那个观测，而不是最新的猜想。**

### 3.3 正确的做法

**用进程内的池化客户端发压，让测量工具自身的连接数可忽略。**

```java
// 一个 JVM、一个池、顺序发 N 次 —— 它自己的连接数是个位数量级，可忽略
PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
cm.setMaxTotal(4);
try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(cm).build()) {
    for (int i = 0; i < 200; i++) {
        HttpPost post = new HttpPost(url);
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = client.execute(post)) {
            EntityUtils.consumeQuietly(resp.getEntity());
        }
    }
}
```

实测：**200 次 POST → 新增 3 条连接**（= 池容量）。这时候数字才有意义。

### 3.4 三条通用教训

| 教训 | 说明 |
| --- | --- |
| **测量工具的副作用必须可忽略** | 逐条 `curl` / `wget` / 单发请求的脚本，**每次调用都是一条新连接**。要么用池化客户端，要么把工具的连接数单独测出来扣掉 |
| **比例吻合不是证据** | 我曾用 `17,061 ≈ 22,700 × 0.75` 这种比例吻合去支持"每条告警一条连接"。**比例吻合可以是巧合**，尤其当分母本身就不干净时 |
| **多次假设被证伪时，回头质疑最初观测** | 连续排除四个假设却没动第一个测量，是本篇最贵的教训 |

### 3.5 顺带暴露的其他问题（与端口无关，保留记录）

排查过程中确实发现两处**独立成立**的问题，都已修：

| 问题 | 修法 |
| --- | --- |
| 告警 webhook 只调 `getResponseCode()`、**从不读响应体**，且 `finally` 里无条件 `disconnect()` | 补 `drainBody()`（按 2xx/非 2xx 分流读 input/error stream）。**理由与端口无关**：body 不排空则残留字节，连接关闭不干净 |
| `stress.ps1 -AllEndpoints` 的默认路径集含 `/fullSample` 裸调（5.5s）等秒级端点，16 线程只有 ~40 QPS | 确立三档语义（§2），默认档收敛为 5 条 ms 级 → **795 RPS** |

> ⚠️ 上表第一项**曾被我错误地归因到"连接复用失效"**。它的修复本身是对的，
> 但**不要**把它和端口耗尽绑在一起叙述 —— 那部分因果是错的。

---

## 四、PowerShell 踩坑（顺手记下）

| 坑 | 表现 | 绕法 |
| --- | --- | --- |
| `Get-NetTCPConnection` 在两万连接下**每次调用 1~2 秒** | 放进采样循环必然超时（我写过 40 次循环，直接挂） | 循环里只用 `netstat -ano`（原生、亚秒） |
| `$pid` 是 PowerShell **保留变量**（当前进程 ID） | 赋值报 `Cannot overwrite variable PID`，统计出的 PID 全错 | 换个名字，如 `$ownerPid` |
| `netstat` 输出**列位会错位** | 把 `LocalPort` 当成 `State`，统计出"13,873 条 ESTABLISHED"这种荒唐数 | 按 `Trim() -split '\s+'` 取 `$f[3]`=状态、`$f[4]`=PID，别按固定位置猜 |
| TIME_WAIT 行的 PID 恒为 0 | 想靠它找"谁建的连接"，永远是 0 | TIME_WAIT 只能看**总数**；要找活证据只能看 ESTABLISHED |

---

## 五、现在的问题状态（诚实记录）

**端口池仍会被打满，但归因未完成。** 已排除：JDK 版本、系统属性、负载压力、
`drainBody` 上限、**以及"webhook 每条告警建一条连接"这个最初的假设**。

仍存的合理嫌疑（**均未证实**）：

| 嫌疑 | 怎么验 |
| --- | --- |
| `stress.ps1 -Continuous` 长时间压测本身 | 起压测 → 记 TIME_WAIT 增速；停压测 → 看是否掉头向下。实测停压后 TIME_WAIT 从 16,398 掉到 9,212（80 秒），**指向压测侧** |
| 排查过程中反复执行的 `mvn test` | 每次启一个 JVM、十几条池化连接、退出时全部进 TIME_WAIT。跑几十次累计可观 |
| 排查过程中上百次单发 `curl` | 每次一条连接 |

> **待补**：用 §3.3 的池化探针 + 单一变量（只开压测、不开告警）做一次干净归因。
> 在那之前，不要再基于"某个组件在疯狂建连接"下结论。
