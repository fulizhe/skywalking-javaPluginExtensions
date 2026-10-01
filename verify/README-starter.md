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
| 依赖拓扑 | `/dashboards/topology.html` | 左=**本服务**单点（合计调用/错误/端点数），右=外部依赖（按组件类型着色，红=有失败调用） | 我调了哪些外部依赖、哪条路慢 |

> 依赖拓扑页的边是**纯内存、分钟级窗口、重启即失** —— 看图前先造数（点一次
> `/queryDbByMybatis`、`/api/hutool-demo/post-json`、`/api/deps-demo/kafka?op=produce` 等）。
> 想看 Cache/Database/MQ 三层：`pwsh ./agent/demo-app/scripts/run-with-agent.ps1 -WithDeps` 起应用与中间件，
> `pwsh ./agent/demo-app/scripts/stress.ps1 -WithDeps -Requests 50 -Threads 8` 造数
> （细节见 [`agent/demo-app/README.md`](../agent/demo-app/README.md) 的「依赖拓扑演示」）。

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

压测有**两条路**:本机 `pwsh` 脚本(Windows 上最常用,自己控制节奏)与容器 `stress` profile(跨平台、能长跑)。
两条打的是同一个东西——demo-app 的接口 —— 只是编排方式不同。

#### 2.6.1 先选压测档位(三个开关**互斥**,同时给会直接报错退出)

| 开关 | 压什么 | 什么时候选它 |
|---|---|---|
| `-AllEndpoints` | **全部页面接口**:各仪表盘数据源 + 全部演示端点(含慢端点与错误端点) | 想知道"覆盖面"—— 每个页面读口都被打到 |
| `-NormalOnly` | 只压业务正常端点(排除 `/error`、`/http500`) | 压**指标聚合**本身(端点级 QPS / 分位) |
| `-WithDeps` | 只压依赖造数端点(Redis / MySQL / Kafka / 外呼) | 看**依赖拓扑**的边与分位怎么随压力变 |
| 都不给 | HttpLoadTest 内置混合集(正常 + 错误 + 慢) | 想连告警链路一起压(**会**触发 webhook 自环,见下) |

#### 2.6.2 本机压测(Windows / pwsh)

```powershell
cd <仓库根>\agent\demo-app

# 起应用(带 agent)。要连三层依赖就加 -WithDeps(会先拉起 redis/mysql/kafka)
pwsh .\scripts\run-with-agent.ps1
pwsh .\scripts\run-with-agent.ps1 -WithDeps

# 压测:在另一个窗口
pwsh .\scripts\stress.ps1 -AllEndpoints -Requests 2000 -Threads 16   # 全页面接口
pwsh .\scripts\stress.ps1 -NormalOnly  -Requests 2000 -Threads 16   # 只压指标聚合
pwsh .\scripts\stress.ps1 -WithDeps    -Requests 200  -Threads 8    # 只压依赖三层
pwsh .\scripts\stress.ps1 -AllEndpoints -Continuous -Threads 16      # 无限模式,10s 一行 [progress]
pwsh .\scripts\stress-slow.ps1 -Requests 10 -Threads 1              # 慢端点演示(手动执行)
```

> ⚠️ **`-AllEndpoints` 会触发告警自环**：它含返回 5xx 的端点，错误请求经插件 webhook
> **同步回打本机**，回打本身又是一次请求 → 负载自我放大，并把 `persistErrors`/`aggregateErrors`
> 计数推高。要干净的指标数字用 `-NormalOnly`。`stress.ps1` 开跑前会把这个提示与命中的错误端点列出来。
>
> `stress-slow.ps1` 是**演示用**的独立脚本（慢端点单请求 0.3s~8.5s，混进稳定性压测会把吞吐拖成
> 没有参考价值的平均数）。它默认**不含**告警端点，要演示告警才加 `-All`。

`-Continuous` 下每行 `[progress]` 里的 `plugin=` 段就是插件自己的计数
(`rowsUpserted` / `lateDropped` / `sampleOverflow` / `persistErrors` / `aggregateErrors`),
`heap=` 看堆。**判断长跑稳不稳**:只看 `persistErrors` / `aggregateErrors` / `endpointOverflow`
是否恒 0,以及 `heap=` 是否在 GC 回落区间震荡。`sampleOverflow` 增长是正常的(蓄水池抽样,见 NOTES 第 11 条)。

常用参数:`-BaseUrl`(默认 `http://127.0.0.1:9600`)、`-Requests`、`-Threads`、`-Paths`
(自己给逗号分隔路径集,给了就覆盖上面三个开关)、`-DurationSec`(按时长)、
`-SkipAppBuild`(复用已有 jar)、`-SkipMetrics`(不打印指标摘要)。

#### 2.6.3 容器压测(跨平台 / 能长跑)

```bash
cd agent/demo-app
docker compose --profile stress up --build -d
docker compose logs -f stress      # 每 10s 一行 [progress]
```

`stress` 是一个持续打负载的容器,收在可选 profile 里,**默认不启动**。它只打正常端点
(所以不和告警耦合——错误请求会触发插件**同步 webhook 回打自身**形成放大回路,
恰好污染这里要看的计数)。要连依赖面一起压,加 `--profile deps` 并把
`/api/deps-demo/*` 加进 compose 里 `stress.command` 的 `loadtest.paths`。

#### 2.6.4 压测之外,手动造数(截图/演示用)

```bash
curl -s "http://127.0.0.1:9600/fullSample"          # 全貌入口:每种被监控的组件各打一次
curl -s "http://127.0.0.1:9600/api/deps-demo/all"   # 依赖面四层一次(Cache/Database/MQ/外呼)
```

```powershell
pwsh .\scripts\run-with-agent.ps1 -WithDeps    # 起应用时顺带拉起 redis/mysql/kafka
docker compose --profile deps ps              # 看三层中间件状态
```

`/fullSample` 默认**带**依赖三层(约 8s/请求,中间件未起时),压测里用 `?deps=false` 关掉。

#### 2.6.5 压测中看什么

| 看什么 | 打开 |
|---|---|
| 插件内部计数是否健康 | <http://127.0.0.1:9600/inner/sw/metrics>(`counters` 段,应该全是 0,`rowsUpserted` 除外) |
| 端点级指标 / 分位 | `/dashboards/metrics.html`、排障视角 `/dashboards/metrics-troubleshoot.html` |
| 依赖拓扑(self 单点 + 组件节点) | `/dashboards/topology.html`(先点「停止刷新」再截图,否则 5s 轮询会把图刷掉) |
| 告警事件 | `/dashboards/dashboard.html?p=alert` |

> **要给领导看 / 投屏**：上面三页右下角都有 **「演示模式」** 开关（或链接直接加 `?presenter=1`）——
> 运维控件收起、顶部出一行结论卡（数字读自 `/inner/sw/*`）、口径说明折叠待展开，
> `‹` `›` 或 `←` `→` 在 指标 → 拓扑 → 告警 三页间翻，`P` 切模式。
> 详见 [`../agent/demo-app/README.md`](../agent/demo-app/README.md) 的「演示模式」。

> **三个必须知道的坑**:
> 1. **依赖边是纯内存、分钟级窗口、重启即失** —— 压测停了或应用重启后,图会空,重新造数再看。
> 2. **中间件缺席 = 没有边,不是红边** —— Redis / MySQL 连不上时图上**没有**这两个节点
>    (连接都没建起来,不产生出口 span);Kafka / 外呼则有边但 `errorCount` 可能仍是 0。
>    详见 [`agent/demo-app/NOTES-docker-stress.md`](../agent/demo-app/NOTES-docker-stress.md) 第 15 条。
> 3. **默认混合路径集会触发告警自环** —— 错误请求 → 插件同步 webhook 回打自身,负载会放大。
>    压"纯指标"时用 `-NormalOnly`,或者用 `run-with-agent.ps1 -NoAlert` 起一个关告警的实例。

---

## 3. 路线 B:一条命令跑断言

上面是"眼看"。这条是"机器判":把关键行为写成断言,一条命令跑完给退出码。
**它跑完就把应用停掉**,所以想边看边跑请用路线 A。

```bash
# 在仓库根目录
bash verify/run.sh --list                          # 先看有哪些场景(三个活跃插件各一个)
bash verify/run.sh                                  # 一条命令跑全部场景
bash verify/run.sh --scenario logfile-reporter    # 主场景
bash verify/run.sh --matrix                        # 附带跑各场景的依赖版本矩阵
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
| 想看覆盖面(每个页面读口都被压到) | 路线 A + `stress.ps1 -AllEndpoints` |
| 压测中怀疑插件计数不对 | `stress.ps1 -Continuous` + 看 `/inner/sw/metrics` 的 `counters` |
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
| 压测跑起来了但依赖拓扑图上只有两三个节点 | 中间件没起 / 或本机端口上是别的服务 | `docker compose --profile deps ps`;中间件缺席时 Redis / MySQL **没有边**(不是红边),见 §2.6 的坑 2 |
| 压测吞吐低得离谱、每行 `[progress]` 间隔很久 | 路径集里混进了慢端点 | 慢端点有 `/api/order/1`(8.5s)、`/api/trace-alert-demo/slow`、`/longTimeTask`、`/api/deps-demo/kafka`(broker 不可达时 3s);压"纯指标"用 `-NormalOnly`,压测里 `/fullSample` 要带 `?deps=false` |
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
| 远程 Linux 服务器 compose 压测的坑清单 | [`agent/demo-app/README-remote-verify.md`](../agent/demo-app/README-remote-verify.md) |
| Trace 告警的配置项与判定规则 | `agent/logfile-reporter-plugin/README-trace-alert.md` |
| 插件构建为什么用 JDK17、运行用 JDK8 | `docs/adr/adr-01*.md` |
| 六类数据流与本仓库的专有名词 | 仓库根 `CONTEXT.md` |
| SkyWalking 本身是什么、OAP 怎么部署 | [skywalking.apache.org](https://skywalking.apache.org/) |
