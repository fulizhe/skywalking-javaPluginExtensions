# 三种测试场景：怎么跑

同一套东西（同一个 jar、同一批 HTTP 契约、同一批页面），三种跑法，差别只在**谁来编排**。

| 场景 | 谁起应用 | 什么时候选它 |
| --- | --- | --- |
| [A · 本地 Windows](#a--本地-windowspwsh-直跑-jar) | 你，`run-with-agent.ps1` 直起 JVM | 日常开发：改完代码立刻验证、压测调参 |
| [B · 本地 compose](#b--本地-compos-windows--docker-desktop) | `docker compose` | 演示、复现部署形态、跨平台一致 |
| [C · 远程 Linux compose](#c--远程-linux-服务器compose) | `docker compose` | 全面压测、长跑稳定性、正式验证（**权威环境**） |

> 坑与排错不在这里：compose/端口/代理/镜像见 [`README-remote-verify.md`](README-remote-verify.md)；
> 压测参数与观测点见 [`../../verify/README-starter.md`](../../verify/README-starter.md) §2.6。

---

## A · 本地 Windows（pwsh 直跑 jar）

```powershell
cd D:\gitRepository\skywalking-javaPluginExtensions\agent\demo-app

pwsh .\scripts\run-with-agent.ps1 -WithDeps      # 起 redis/mysql/kafka + 起应用（带 agent，保持运行）
```

另开一个窗口压测（三个档位互斥）：

```powershell
pwsh .\scripts\stress.ps1 -WithDeps      -Requests 2000 -Threads 16   # 只压依赖三层
pwsh .\scripts\stress.ps1 -AllEndpoints  -Requests 2000 -Threads 16   # 压全部页面接口
pwsh .\scripts\stress.ps1 -NormalOnly    -Requests 2000 -Threads 16   # 只压业务正常端点（排除 error）
pwsh .\scripts\stress.ps1 -AllEndpoints  -Continuous -Threads 16       # 无限模式：10s 一行 [progress]
pwsh .\scripts\stress-slow.ps1 -Requests 10 -Threads 1                 # 慢端点演示（手动执行，见下）
```

**慢端点演示（`stress-slow.ps1`）**：与上面三种"稳定性压测"是**两种目的**，故分开成独立脚本 ——
慢端点单请求 0.3s~8.5s，混进稳定性压测会把吞吐数字拖成没有参考价值的平均数。

```powershell
pwsh .\scripts\stress-slow.ps1                        # 默认 25 次 / 4 线程
pwsh .\scripts\stress-slow.ps1 -Requests 10 -Threads 1 # 串行慢放，适合边讲边看
pwsh .\scripts\stress-slow.ps1 -All                   # 加上告警演示端点 → **会触发告警自环**
```

它开跑前会打印端点清单与单请求量级（便于讲解对照），并说明这一档的吞吐**不要**和上面三种比。

| 事实 | 说明 |
| --- | --- |
| **默认不含告警端点** | `/api/trace-alert-demo/error`、`/http500` 会真的报错 → 插件 webhook **同步回打本机** → 回打又是一次请求 → **负载自我放大**。要演示告警才显式加 `-All` |
| 慢端点分两类 | **依赖侧**：`/api/deps-demo/mysql?sleepMs=1000`、`kafka?op=produce`(broker 不可达约 3s)、`http?site=httpbin`(1~2s)、`grpc`(0.3s，首次建链约 4s)；**业务侧**：`trace-alert-demo/slow?ms=4000`、`/api/order/1`(8.5s)、`/api/export/report`、`/longTimeTask`、`/fullSample`(约 5s) |
| 另有毫秒级对照 | `/queryDbByMybatis`、hutool 出口自调 —— 用来在同一进程内对比"不同依赖的差距" |

造数与看图：

```powershell
curl.exe -s -o NUL --noproxy "*" "http://127.0.0.1:9600/fullSample"        # 全貌入口：每种被监控组件各一次
curl.exe -s -o NUL --noproxy "*" "http://127.0.0.1:9600/api/deps-demo/all" # 依赖面四层一次
```

| 看什么 | 打开 |
| --- | --- |
| 依赖拓扑（总览 = self 单点；明细 = endpoint 左列） | <http://127.0.0.1:9600/dashboards/topology.html>（先点「停止刷新」再截图） |
| 端点级 QPS / 分位 | <http://127.0.0.1:9600/dashboards/metrics.html> |
| 告警事件 | <http://127.0.0.1:9600/dashboards/dashboard.html?p=alert> |
| 插件内部计数（丢数据看这里） | <http://127.0.0.1:9600/inner/sw/metrics> |

### 演示模式（给领导 / 投屏看的那一面）

三页右下角都有同一个开关 **「演示模式」**（指标大屏 / 依赖拓扑 / 告警面板）。打开后：

| 变化 | 说明 |
| --- | --- |
| **运维控件收起来** | 时间窗 / 视图切换 / 停止刷新 / 轮询状态 / 诊断 chips 全部隐藏，图和表放大一档 |
| **顶部结论卡** | 一行数字结论，取自 `/inner/sw/*` 读口，**不是手写常量**：拓扑 = 外部依赖类型 / 累计调用 / 累计错误率 / 最慢依赖；告警 = 事件数 / SLOW / ERROR / webhook 投递。指标大屏**不另加卡** —— 它顶部的 KPI 行本来就是那个结论（QPS / 错误率 / P95…），只是被放大 |
| **口径说明收成一行** | 「口径说明」默认折叠，鼠标悬停展开 —— 汇报时看不见，被追问时能立刻答 |
| **三页串起来** | 右下角 `‹` `›` 翻页；或键盘 `←` `→`；`P` 切换演示模式。顺序：指标大屏 → 依赖拓扑 → 告警面板 |

状态存在浏览器 localStorage，**翻页后仍是演示态**；截图/投屏链接可直接带 `?presenter=1`，不依赖本机缓存。

> **两页口径不同，别当成 bug**：依赖边只存在**进程内存**里，重启即清零；指标数字落 **H2** 跨重启保留。
> 所以刚重启时"拓扑图空、指标图有数据"是正常的 —— 先造点流量（`/api/deps-demo/all` 或 `-WithDeps` 压测）。

- 不带 `-WithDeps` 也能跑，只是 Redis / MySQL 两层**没有节点**（连不上不出边，见下"三条规则"）。
- **不需要设任何环境变量**：中间件的宿主映射端口（redis `16379` / mysql `13306` / kafka `19092`）已写进 `application.yml` 默认值。
- 本机脚本常用参数：`-Port` / `-SkipAppBuild` / `-SkipPluginBuild` / `-NoAlert` / `-Continuous` / `-Paths`。
- **`-AllEndpoints` 会触发告警自环**：它含返回 5xx 的端点，错误请求经插件 webhook 同步回打本机、回打又是一次请求。开跑前脚本会把这个提示与命中的错误端点列出来；只要指标数字请用 `-NormalOnly`。
- 拓扑页的「**视图**」切换在「自启动以来」档会**置灰**：该档是组件级累计，只有一种画法，没有"明细"一说。

---

## B · 本地 compose（Windows + Docker Desktop）

```bash
cd agent/demo-app

docker compose up --build -d demo-app                      # 只看效果（首次构建 10~30 分钟，等 (healthy)）
docker compose --profile deps up -d                         # 加三层中间件
docker compose --profile stress up --build -d               # 压测（stress 容器）
docker compose logs -f stress                              # 每 10s 一行 [progress]
docker compose --profile stress --profile deps up --build -d  # 压测 + 依赖面
docker compose down
```

| 要点 | 说明 |
| --- | --- |
| **容器里的应用连中间件用服务名** | `redis:6379` / `mysql:3306` / `kafka:9092`（compose 已注入 `DEPS_*`）。`16379/13306/19092` 是**宿主映射**，只给宿主机上跑的东西用 |
| 造数 | 与 A 相同：`curl "http://127.0.0.1:9600/fullSample"`、`curl "http://127.0.0.1:9600/api/deps-demo/all"` |
| 压测档位 | 改 `stress.command` 的 `loadtest.paths`，见 C 的写法 |
| 中间件不在场 | 加 `--profile deps` 才有；不加也能跑，Cache/Database 两层没有节点 |

---

## C · 远程 Linux 服务器（compose）

前置：`docker compose version` 是 v2、9600/8092 空闲、内存 ≥2.5g（全起）、磁盘 ≥10g、能拉基础镜像。

```bash
git clone <repo> && cd skywalking-javaPluginExtensions/agent/demo-app

docker compose --profile stress --profile deps up --build -d   # 全套
docker compose ps                                              # 全部 (healthy) 才算就绪
```

**要让压测覆盖依赖面**，改 `stress.command` 的 `-Dloadtest.paths` 后重建 stress：

```bash
# 默认（stress 单独起、中间件不在）：全貌入口关掉三层，避免每请求等 1~3s 超时
-Dloadtest.paths=/hello,/fullSample?deps=false,/queryDbByMybatis,/queryDbByJdbc,/longTimeTask

# 连依赖面（两个 profile 一起）：去掉 ?deps=false，并显式补上可单独造慢边的 deps 路径
-Dloadtest.paths=/hello,/fullSample,/api/deps-demo/redis?op=set,/api/deps-demo/mysql,/api/deps-demo/mysql?sleepMs=30,/api/deps-demo/kafka?op=produce,/api/deps-demo/http?site=httpbin,/queryDbByMybatis,/queryDbByJdbc,/longTimeTask

docker compose --profile stress --profile deps up -d stress
```

观测与收工：

```bash
docker compose logs -f stress                                 # 吞吐 / plugin= 计数 / heap=
curl -s http://127.0.0.1:9600/inner/sw/metrics | head -c 400  # counters 应几乎全 0
cd ../.. && bash verify/run.sh --scenario logfile-reporter     # 断言回路（可选，与压测互补：它给"没弄坏"的判据）
cd agent/demo-app && docker compose down                      # 收工（-v 连缓存卷一起删）
```

Linux 的优势：没有 Docker Desktop 那一层代理问题，拉镜像直连即可。

---

## 三条规则（三场景通用）

| 规则 | 表现 |
| --- | --- |
| 依赖边是**纯内存、分钟级窗口、重启即失** | 压测停了 / 应用重启后图变空 → 重新造数再看 |
| **中间件连不上 ≠ 红边** | Redis / MySQL **没有节点**（连接未建立，不产生出口 span）；Kafka / 外呼**有边但 `errorCount=0`**（线索在耗时 ≈ 超时上限） |
| 不带档位开关的混合路径集**会触发告警自环** | 压纯指标显式给 `-NormalOnly` / `-AllEndpoints` |

细节与证据 → `agent/demo-app/NOTES-docker-stress.md` 第 14~16 条。

---

## 复验时的两个检查点

| 检查 | 期望 |
| --- | --- |
| 明细档里 `/api/deps-demo/redis` 名下的出口 | **只有** Jedis 一类。（历史上出过一次归因错位：`/fullSample` 内部直接调了别的 Controller 的 handler 方法，agent 把那个 operationName 当成 Entry，于是一次请求的 7 个出口全被记到它名下。已修，逻辑搬进了普通 `@Service`） |
| 总览档右侧的组件节点 | 中间件在场 → `Jedis` / `Mysql` / `kafka-producer` / `Http(#128)` / `h2-jdbc-driver` 五个节点，前三个**绿边**；缺席那个就是没连上 |

---

**相关文档**：新手上手主线 [`../../verify/README-starter.md`](../../verify/README-starter.md)；
远程 compose 的坑清单 [`README-remote-verify.md`](README-remote-verify.md)；
应用与全部读口 [`README.md`](README.md)。