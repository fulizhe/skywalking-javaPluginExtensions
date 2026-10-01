# 三种测试场景的操作矩阵

三种跑法，**跑的东西完全一样**（同一个 jar、同一套 HTTP 契约、同一批页面），差别只在"谁来编排"：

| 场景 | 谁跑应用 | 适合 | 一句话 |
| --- | --- | --- | --- |
| [A · 本地 Windows](#a-本地-windowspwsh-直跑-jar) | **你**（pwsh 直起 JVM） | 日常开发、改代码后快速验证、压测调参 | 改完代码就 `run-with-agent.ps1` + `stress.ps1` |
| [B · 本地 compose](#b-本地-composewindows--docker-desktop) | docker compose | 给同事演示、复现"部署形态"、跨平台一致 | 容器里跑应用与 stress，一条命令起 |
| [C · 远程 Linux compose](#c-远程-linux-服务器compose) | docker compose | 全面压测、长跑稳定性、正式验证 | Linux 无 Docker Desktop 那套代理问题，才是权威环境 |

> 相关文档：新手上手主线 [`../../verify/README-starter.md`](../../verify/README-starter.md)；
> C 场景专属细节与坑 [`README-remote-verify.md`](README-remote-verify.md)；
> 全部读口与参数 [`README.md`](README.md)。

---

## 0. 通用前提与三条硬规则

### 造数入口（三种场景都一样）

| 端点 | 作用 |
| --- | --- |
| `/fullSample` | **全貌入口**：一条请求把每种被监控的组件各打一次（tomcat / `@Trace` / MyBatis / JDBC / HttpClient 自调 / Redis / MySQL / Kafka / 外呼）。`?deps=false` 关掉后四层 |
| `/api/deps-demo/all` | 依赖面四层一次（Cache / Database / MQ / 外呼），逐层回成败与耗时 |
| `/api/deps-demo/{redis,mysql,kafka,http}` | 单层造数，能分别造慢边（`?sleepMs=`）、失败、分档 |
| `/api/trace-alert-demo/{error,http500,slow?ms=4000,ok}` | 告警造数 |

### 三条硬规则（踩过三次了）

| 规则 | 表现 | 记住这句 |
| --- | --- | --- |
| **依赖边是纯内存、分钟级窗口、重启即失** | 压测停了 / 应用重启后图变空 | 看图前重新造数，**4 分钟内**看完 |
| **中间件连不上 ≠ 红边** | Redis / MySQL **没有节点**；Kafka / 外呼**有边但 `errorCount=0`** | 缺席 = 连接没建起来（不产生出口 span）；**有边不代表成功** —— Kafka 靠异步 callback 回填错误，**线索在耗时 ≈ `max.block.ms`(3s)** |
| **默认混合路径集会触发告警自环** | 吞吐异常放大、计数暴涨 | 压纯指标用 `-NormalOnly`（或 `-AllEndpoints` 里的 readout 组），别混错误端点 |

### 两个压测开关约定

`stress.ps1` 的 `-NormalOnly` / `-WithDeps` / `-AllEndpoints` **互斥**，同时给直接报错退出 1：

| 开关 | 压什么 |
| --- | --- |
| `-AllEndpoints` | 全部页面接口（各仪表盘数据源 + 全部演示端点，含慢/错） |
| `-NormalOnly` | 只压业务正常端点（排除 `/error`、`/http500`） |
| `-WithDeps` | 只压依赖造数端点（Redis / MySQL / Kafka / 外呼） |

---

## A · 本地 Windows（pwsh 直跑 jar）

```powershell
cd D:\gitRepository\skywalking-javaPluginExtensions\agent\demo-app

# ① (可选) 中间件 —— docker 起，宿主端口是【映射后】的
docker compose --profile deps up -d          # 等价: pwsh .\scripts\deps.ps1 -Up

# ② 本机跑应用 → 连中间件必须用【宿主映射端口】，不是容器内端口
$env:DEPS_REDIS_PORT     = "16379"
$env:DEPS_MYSQL_URL      = "jdbc:mysql://localhost:13306/demo?connectTimeout=2000&socketTimeout=3000"
$env:DEPS_MYSQL_USER     = "root"
$env:DEPS_MYSQL_PASSWORD = ""
$env:DEPS_KAFKA_BOOTSTRAP = "localhost:19092"

# ③ 起应用（带 agent，保持运行）
pwsh .\scripts\run-with-agent.ps1 -SkipPluginBuild    # 想顺便拉中间件就去掉 -SkipPluginBuild 并加 -WithDeps

# ④ 压测（另开窗口）
pwsh .\scripts\stress.ps1 -AllEndpoints -Requests 2000 -Threads 16   # 全页面接口
pwsh .\scripts\stress.ps1 -NormalOnly    -Requests 2000 -Threads 16   # 只压指标聚合
pwsh .\scripts\stress.ps1 -WithDeps      -Requests 200  -Threads 8    # 只压依赖三层
pwsh .\scripts\stress.ps1 -AllEndpoints -Continuous -Threads 16        # 无限模式：10s 一行 [progress]

# ⑤ 造数 + 看图
pwsh .\scripts\deps.ps1 -Smoke                              # 八个造数端点 + 每层耗时/失败原因
pwsh .\scripts\deps.ps1 -Status                              # 三层中间件状态
# http://127.0.0.1:9600/dashboards/topology.html          （先点「停止刷新」再截图）
# http://127.0.0.1:9600/inner/sw/metrics                  （counters 段：丢数据就看这里）
```

**要点**

| 项 | 值 |
| --- | --- |
| 端口 | 应用 `9600`（`run-with-agent.ps1 -Port` 可改；不带 `-DWebPort` 时应用默认落 `9601`） |
| 中间件宿主端口 | redis `16379` / mysql `13306` / kafka `19092`（**避开 6379/3306/9092**，本机常被别的栈占） |
| 不起中间件也能跑 | 只是 Redis / MySQL 两层没有节点；其余照常 |
| 中间件缺席时 `/fullSample` | 默认带三层 → 约 8s/请求。压测清单里已用 `?deps=false`，手动调它时注意 |

---

## B · 本地 compose（Windows + Docker Desktop）

```bash
cd agent/demo-app

# ① 只看效果
docker compose up --build -d demo-app        # 首次 10~30 分钟构建；等 docker compose ps 显示 (healthy)

# ② 加三层中间件
docker compose --profile deps up -d           # redis / mysql / kafka

# ③ 压测（stress 容器：宿主机跑压测客户端 → 压容器里的 demo-app）
docker compose --profile stress up --build -d
docker compose logs -f stress                 # 每 10s 一行 [progress]:吞吐 / plugin= 计数 / heap=

# ④ 压测 + 依赖面（两个 profile 一起）
docker compose --profile stress --profile deps up --build -d

# ⑤ 造数看图 —— 容器里的应用已通过【服务名】连好中间件，无需你设 DEPS_*
curl "http://127.0.0.1:9600/fullSample"
curl "http://127.0.0.1:9600/api/deps-demo/all"
# http://127.0.0.1:9600/dashboards/topology.html

# ⑥ 收工
docker compose down
```

**要点**

| 项 | 说明 |
| --- | --- |
| **容器内连中间件用服务名** | `redis:6379` / `mysql:3306` / `kafka:9092`，**不是**映射端口（`DEPS_*` 已在 compose 里写好） |
| 映射端口只给宿主机用 | 宿主机上要 `redis-cli -p 16379` 或让本机应用连 `localhost:16379` 时才用映射端口 |
| 代理（本机 Docker 拉镜像） | daemon 只认 **Windows「Internet Settings」的 `ProxyServer`**，Docker Desktop 的 Proxies 设置对 daemon 无效；WSL2 内 `127.0.0.1` 无代理 → `proxyconnect ... connection refused`。见 [`NOTES-docker-stress.md`](NOTES-docker-stress.md) 第 13 条 |
| kafka 镜像 | 用 `apache/kafka:3.8.0`（**不是** `bitnami/kafka` —— Bitnami 改了分发策略，国内镜像源 403） |

---

## C · 远程 Linux 服务器（compose）

```bash
# ① 前置检查
docker compose version                        # 必须是 v2
ss -lntp | grep -E '9600|8092'                 # 应用与 H2 控制台端口必须空
free -g && df -h                               # 内存 ≥2.5g（全起）、磁盘 ≥10g

# ② clone 并起全套
git clone <repo> && cd skywalking-javaPluginExtensions/agent/demo-app
docker compose --profile stress --profile deps up --build -d
docker compose ps                              # 全部 (healthy) 才算就绪

# ③ 让压测覆盖依赖面：改 stress.command 的 loadtest.paths（见下），再重建 stress
docker compose --profile stress --profile deps up -d stress

# ④ 观测
docker compose logs -f stress
curl -s http://127.0.0.1:9600/inner/sw/metrics | head -c 400   # counters 应几乎全 0
# 浏览器 http://<服务器IP>:9600/dashboards/topology.html

# ⑤ 顺手跑断言回路（与压测互补：它给"没弄坏"的判据）
cd ../.. && bash verify/run.sh --scenario logfile-reporter

# ⑥ 收工
cd agent/demo-app && docker compose down
```

**`loadtest.paths` 改法**（stress 单独起时中间件不在，`/fullSample` 必须关掉三层）：

```yaml
# 默认（stress 单独起）：全貌入口关掉三层，避免每请求等 1~3s 超时
- -Dloadtest.paths=/hello,/fullSample?deps=false,/queryDbByMybatis,/queryDbByJdbc,/longTimeTask

# 压测 + 依赖面（两个 profile 一起）：去掉 ?deps=false，并显式补上可单独造慢边的 deps 路径
- -Dloadtest.paths=/hello,/fullSample,/api/deps-demo/redis?op=set,/api/deps-demo/mysql,/api/deps-demo/mysql?sleepMs=30,/api/deps-demo/kafka?op=produce,/api/deps-demo/http?site=httpbin,/queryDbByMybatis,/queryDbByJdbc,/longTimeTask
```

**Linux 上的优势**：没有 Docker Desktop 那一层代理问题，拉镜像直连即可；`6379/3306/9092` 一般也空着（本仓库仍用映射端口 `16379/13306/19092`，避免与同机其它栈撞）。坑的完整清单见 [`README-remote-verify.md`](README-remote-verify.md) §7。

---

## 复验时的两个检查点

| 检查 | 怎么看 | 期望 |
| --- | --- | --- |
| **依赖边的入口端点归属正确** | 明细档（`/dashboards/topology.html` → 明细） | `/api/deps-demo/redis` 名下**只有** Jedis 一类出口；`/fullSample` 名下挂它自己的 4~5 条出口。历史上这里出过错（内部调用别的 Controller 的 handler，把入口改写成 `redis`，一次请求的 7 个出口全被记到它名下），已修 |
| **三层是否真的分层** | 总览档 | 中间件在场 → `Jedis` / `Mysql` / `kafka-producer` / `Http(#128)` / `h2-jdbc-driver` 五个节点，前三个**绿边**；缺席的那个就是没连上 |