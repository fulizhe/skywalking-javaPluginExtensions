# 本地跑起来:新同学的第一小时

这份文档按**学习顺序**带你从零跑通本仓库,不按功能模块罗列。读完你应该能:
看懂插件在干什么、自己在浏览器里点出效果、改完代码知道用哪条命令验证。

> 只想深入某一块时:[`verify/README.md`](README.md)(回路与场景机制)、
> [`agent/demo-app/README.md`](../agent/demo-app/README.md)(应用与全部读口)。

---

## 0. 你要证明的两件事

这个仓库的本地测试其实是两件不同的事,别混:

| | 问的问题 | 手段 | 适合什么时候 |
|---|---|---|---|
| **路线 A** | "这东西**真的在工作**吗?" | 容器常驻 + 浏览器点 | 第一次来、想理解它、给同事演示 |
| **路线 B** | "我的改动**没弄坏**它吗?" | `bash verify/run.sh` 跑断言 | 改完代码、要提交前 |

新人建议**先 A 后 B**:先眼见为实,再理解"没弄坏"的判据长什么样。
只想快速确认没坏,直接跳到[路线 B](#路线-b一条命令跑断言)。

---

## 1. 前置

只需要一样东西:**Docker**(Linux 引擎)。Docker Desktop(Win/mac)或原生 Docker(Linux)都行。

不需要在本机装 JDK、Maven、SkyWalking Agent——镜像里全都有。

先确认 Docker 能拉镜像(拉不动就是网络/代理问题,见[§5](#5-出问题了怎么查)):

```bash
docker pull maven:3.8.6-eclipse-temurin-8
```

命令在 **bash**(Linux / WSL / Git-Bash)里敲。Windows 上建议用 WSL 的 bash。

---

## 2. 路线 A:留在运行状态,浏览器里看

### 2.1 一条命令起来

```bash
cd agent/demo-app
docker compose up --build -d demo-app
```

第一次会构建镜像(装插件 + agent + demo 应用),**10~30 分钟**;之后有缓存就快得多。
注意命令末尾的 `demo-app`——压测容器是可选的(见 §2.6),只带着看效果不用它。

### 2.2 等它 ready

```bash
docker compose ps          # STATUS 列出现 (healthy) 才是真就绪
```

首次启动要 1~2 分钟(JVM 起来 + agent 增强)。`starting` 不代表失败,继续等。

### 2.3 造点数据(跳过这步图表会是空的)

刚启动时几乎没有流量,仪表盘是空的。想看到曲线,先打个底:

```bash
# bash
for i in $(seq 1 20); do
  curl -s -o /dev/null http://127.0.0.1:9600/hello
  curl -s -o /dev/null http://127.0.0.1:9600/fullSample
  curl -s -o /dev/null http://127.0.0.1:9600/queryDbByMybatis
done
```

```powershell
# PowerShell
1..20 | ForEach-Object {
  "hello", "fullSample", "queryDbByMybatis" | ForEach-Object {
    curl.exe -s -o NUL --noproxy "*" "http://127.0.0.1:9600/$_"
  }
}
```

> **本机有代理的话,`curl` 一定要加 `--noproxy "*"`**(PowerShell 写法如上)。
> 否则回环请求会被送进外部代理,拿到莫名其妙的 502。bash 版的 `curl -s` 在
> `no_proxy` 已含 `127.0.0.1` 的情况下不用加;不确定就加上,无害。

### 2.4 打开哪些页面

浏览器从 <http://127.0.0.1:9600/> 进(能力导览首页,讲清这是什么)。

> 仪表盘入口是 <http://127.0.0.1:9600/dashboards/index.html>,
> **必须带 `index.html`**。`/dashboards/` 是 404,而 404 会被插件判成错误、
> 真的发一条 ERROR 告警,凭空多出一条假事件(新手最容易自我怀疑的地方)。

| 页面 | 地址 | 你该看到什么 | 这个页面在证明 |
|---|---|---|---|
| 能力导览 | `/` | 插件是什么 + 六类数据流地图 | 总览 |
| 仪表盘导航 | `/dashboards/index.html` | 所有页面入口 | — |
| **Trace 告警** | `/dashboards/dashboard.html?p=alert` | 慢/错事件列表(先按 §2.5 造一个) | **最直观,优先看这个** |
| Trace 数据统计 | `/dashboards/dashboard.html?p=statistic` | 链路合并情况、span 数 | 链路段数据流 |
| JVM 指标 | `/dashboards/dashboard.html?p=jvm` | 堆/GC/线程 | JVM 数据流 |
| Meter 指标 | `/dashboards/dashboard.html?p=meter` | 自定义 meter | meter 数据流 |
| 实例属性 | `/dashboards/dashboard.html?p=instance` | 实例心跳与属性 | 心跳数据流 |
| Profile 采样 | `/dashboards/dashboard.html?p=profile` | 采样快照(需先点面板里的"开始采样") | profile 数据流 |
| H2 影子对照 | `/dashboards/dashboard.html?p=parity` | 内存热层 vs H2 落盘 | 双层存储一致性 |
| 指标大屏 | `/dashboards/metrics.html` | QPS / 错误率 / 延迟分位,可切时间档 | 指标聚合 |
| 指标排障 | `/dashboards/metrics-troubleshoot.html` | 最差分钟分位 | 定位"最坏那一分钟" |
| Trace 查询 | `/dashboards/trace-query.html` | 按 traceId 查整条链路 | 单条链路下钻 |
| 慢查询 | `/dashboards/trace-slow.html` | 慢 span 列表 | 慢在哪 |
| 链路视图 | `/dashboards/trace-view.html` | 完整链路拓扑 | 跨 span 串起来 |

各仪表盘是**自动轮询**的(3~6 秒一次),不用手动刷新。

### 2.5 亲手造一个告警(最值得做的一步)

告警是插件最显眼的可见效果。**直接点地址栏就行**,在下面这一行后面回车:

| 打开这个地址 | 会发生什么 | 耗时 |
|---|---|---|
| `/api/trace-alert-demo/error` | 页面报 500 → 几秒后"Trace 告警"面板出现一条 `ERROR` | 立即 |
| `/api/trace-alert-demo/http500` | 同上,走 HTTP 状态码判定 | 立即 |
| `/api/trace-alert-demo/slow?ms=4000` | 故意睡 4 秒(超过默认 3000ms 阈值)→ 出现一条 `SLOW` | 约 4 秒 |
| `/api/order/1` | 睡 8.5 秒,命中 Ant 规则(`/api/order/*=8000`)→ `SLOW` | 约 9 秒 |
| `/api/trace-alert-demo/ok` | 正常请求,**不**告警(对照组) | 立即 |

看到 500 报错页面**是预期的**,不是环境坏了。

原理:插件识别出慢/错请求 → 通过 HTTP webhook 回打本机 →
demo 应用把它接住并展示在告警面板上。所以告警面板能出事件,
说明"识别 → 通知 → 接收 → 展示"整条链都通了。

想清空面板重来一遍:

```bash
curl -s -X POST http://127.0.0.1:9600/inner/sw/trace-alert/clear
```

注意是 **POST**。用 GET 会拿到 405,而 405 本身又会变成一条新的 ERROR 告警。

### 2.6 压测(可选,想看长期稳定性时才用)

```bash
docker compose --profile stress up --build -d
docker compose logs -f stress      # 每 10s 一行 [progress]
```

`stress` 是一个持续打负载的容器,收在可选 profile 里,**默认不启动**。
观察它日志里的 `plugin=` 计数(`lateDropped` / `persistErrors` / `aggregateErrors`)
是否异常增长、`heap=` 是否持续攀升。

> 压测只打正常端点,不和告警耦合——因为错误请求会触发插件**同步 webhook 回打自身**
> 形成放大回路,恰好污染这里要看的计数。

---

## 3. 路线 B:一条命令跑断言

上面是"眼看"。这条是"机器判":把关键行为写成断言,一条命令跑完给退出码。
**它跑完就把应用停掉**,所以想边看边跑请用路线 A。

```bash
# 在仓库根目录
bash verify/run.sh --list                          # 先看有哪些场景
bash verify/run.sh --scenario logfile-reporter    # 主场景
bash verify/run.sh --scenario override-httpclient --matrix   # 附带跑 httpclient 版本矩阵
```

看到这样就是成功:

```
场景[logfile-reporter]全绿: PASS=24 (exit 0)
```

**退出码**:`0` 全绿;`1` 构建失败;`2` 应用未就绪/场景缺失;`3` 插件没加载;`5` 断言失败。

场景与断言范围的细节见 [`verify/README.md`](README.md)。

> Windows 也能跑:在 WSL 的 bash 里执行即可(Docker Desktop 的 WSL 集成)。
> 老的 PowerShell 脚本(`agent/demo-app/scripts/*.ps1`)仍保留,
> 但只作为本地调试次选,默认用这条。

---

## 4. 什么时候用哪条

| 你在做什么 | 用哪条 |
|---|---|
| 第一次来 / 想搞懂它 / 给别人演示 | 路线 A |
| 只改了插件里的 Java 代码 | 路线 B(快,够用) |
| 只改了 `agent/demo-app` 里的页面或端点 | 路线 A(断言不覆盖页面) |
| 改了 Docker 构建、启动参数、agent 装配 | 两条都跑 |
| 提交前 | 路线 B 必跑;改了页面则路线 A 至少手点一遍 |

---

## 5. 出问题了怎么查

先按**症状**定位,别通读日志。

| 症状 | 多半是 | 怎么办 |
|---|---|---|
| `docker pull` 报 `proxyconnect ... 127.0.0.1:7897` | Docker Desktop 残留代理 | Docker Desktop → Settings → Resources → Proxies 关掉代理,**Apply & Restart**,再拉 |
| 构建卡在拉 `docker/dockerfile:1` | 同上(拉前端镜像失败) | 同上。本仓库两个 Dockerfile 都已刻意不写 `# syntax` 行,若又出现请检查是否被改回 |
| 构建报 `unexpected EOF` | 拉 agent 发行包时网络抖 | 直接重跑 `docker compose up --build -d demo-app`(下载已带重试) |
| `docker compose ps` 一直 `starting`,或变 `unhealthy` | 应用没起来 | `docker compose logs demo-app` 看最后 30 行;最常见是 9600 端口被占 |
| 页面打得开但图表全空 | 还没造数据 | 回 §2.3;仪表盘 3~6 秒轮询一次,稍等 |
| 告警面板永远是空的 | 没造告警事件 | 回 §2.5 打开 `/api/trace-alert-demo/error` |
| **告警面板里有莫名其妙的 ERROR** | 多半是你手打错路径导致 404 / 405 | 看事件里的 `url` 字段。任何 ≥500 的响应(含 404 路径不存在、405 方法不对)都会被判为错误并真的发一条告警。仪表盘入口要用 `/dashboards/index.html`;清空告警要用 `POST /inner/sw/trace-alert/clear`(GET 会 405) |
| 本机 `curl` 返回 502 而浏览器正常 | 请求被代理带走 | 加 `--noproxy "*"`(见 §2.3) |
| 路线 B 退出码 3 | 插件没被 agent 加载 | 看容器内 `/opt/skywalking-agent/logs/skywalking-api.log` 有没有插件加载记录 |
| 路线 B 退出码 5 | 某条断言不满足 | 输出里会写明哪条断言、期望什么、实际什么 |

**两个日志位置**(排查时最常看):

```bash
docker compose logs demo-app                        # 应用自己的日志
docker compose exec demo-app \
  tail -n 50 /opt/skywalking-agent/logs/skywalking-api.log   # agent 侧日志(插件加载、告警判定)
```

**确认插件内部计数器是否健康**(应该全是 0,`rowsUpserted` 除外):

<http://127.0.0.1:9600/inner/sw/metrics> —— 看 `counters` 段。
`lateDropped` / `sampleOverflow` / `endpointOverflow` / `persistErrors` / `aggregateErrors`
持续增长就说明有数据在被丢弃或写失败。

---

## 6. 收工

```bash
cd agent/demo-app
docker compose down            # 停容器并删除(volume 保留,下次启动更快)
```

想把缓存也清掉(下次就是全新 10~30 分钟构建):

```bash
docker compose down -v
docker image rm demo-app-agent:9.4.0
```

跑过路线 B 的话,它的 Maven 依赖缓存在 Docker 卷 `skywalking-verify_verify-m2`,
删掉它会失去这层缓存。

---

## 7. 接下来读什么

按需要挑,不用一次读完:

| 想知道 | 读 |
|---|---|
| 场景怎么声明、断言怎么写、加新插件场景 | [`verify/README.md`](README.md) |
| 应用的全部读口、参数、术语表 | [`agent/demo-app/README.md`](../agent/demo-app/README.md) |
| Trace 告警的配置项与判定规则 | `agent/logfile-reporter-plugin/README-trace-alert.md` |
| 插件构建为什么用 JDK17、运行用 JDK8 | `docs/adr/adr-01*.md` |
| 六类数据流与本仓库的专有名词 | 仓库根 `CONTEXT.md` |
| SkyWalking 本身是什么、OAP 怎么部署 | [skywalking.apache.org](https://skywalking.apache.org/) |
