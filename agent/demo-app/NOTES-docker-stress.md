# Docker 持续压测 notes

本批新增：把 demo-app + logfile-reporter-plugin + SkyWalking agent 打进一个镜像，
用 compose 在 Linux 上长期跑压测，观测插件在长跑下的稳定性。

> 该镜像同时也是**本地浏览入口**（`docker compose up -d demo-app` 后用浏览器看
> 仪表盘），面向新人的完整说明见 [`verify/README-starter.md`](../../verify/README-starter.md)。

## 新增/涉及文件

| 文件 | 作用 |
| --- | --- |
| `agent/demo-app/Dockerfile` | 多阶段构建：插件(JDK17) → demo-app(JDK8) → 运行时(JDK8 + Maven + agent) |
| `agent/demo-app/docker-compose.yaml` | 编排 `demo-app`(常驻) + `stress`(无限压测,收在 `stress` profile 内,默认不起) |
| `agent/demo-app/deploy/deploy_remote.py` | Paramiko 推到远端 Linux 并启动 |
| `.dockerignore`(仓库根) | 缩小构建上下文（排除 `.git` / `target` / `.codegraph` 等） |

## 镜像结构

三个 stage（`maven:3.8.6-eclipse-temurin-17` / `-8`）：

1. `plugin-build`：`COPY agent agent` → `mvn -pl logfile-reporter-plugin -am`
   产出 `logfile-reporter-plugin-2.0.0.jar`（字节码 8）。
2. `app-build`：JDK8 构建 `demo-app-1.0.0.jar`，并预跑一次压测把 surefire provider
   等依赖预热进 `/root/.m2`（`|| true`，目标不可达的失败可忽略）。
3. `runtime`：下载 agent 9.4.0（Apache archive，tgz）、把插件拷进 `plugins/`，
   带上 app jar、压测源码与预热 `~/.m2`，`EXPOSE 9600`。

单镜像即同时用于两个服务：`demo-app` 跑 `java -javaagent ... -jar`，`stress` 跑 `mvn test`。

## 本地用法

压测收在 compose profile 里，**默认不启动**（只想看效果就别带它）。分**两档、各自一个 profile**：

| profile | 服务 | 目的 | 压什么 | 跑法 |
| --- | --- | --- | --- | --- |
| `stress` | `stress` | **冲高 QPS + 看长跑稳定性**（堆、插件计数是否异常） | 5 条 **ms 级、只 2xx** | `up -d` 后 `logs -f`，无限循环 |
| `stress-slow` | `stress-slow` | **核对指标准确性**（分位是否落在已知耗时上） | 6 条**慢端点**，每条耗时已知 | 240 次请求，打完即退 |

```bash
cd agent/demo-app
# A) 只起 demo-app，用浏览器看仪表盘（告警默认已开）
docker compose up --build -d demo-app
#    浏览器 http://127.0.0.1:9600/  与  /dashboards/index.html

# B) 常规压测（显式带 profile）：高 QPS + 长跑稳定性
docker compose --profile stress up --build -d
docker compose logs -f stress      # 每 10s 一行 [progress]

# C) 慢接口压测：核对分位准确性（有限请求数，打完退出，不用 -d）
docker compose --profile stress-slow up --build stress-slow
docker compose logs stress-slow
#    打开 /dashboards/metrics.html 与 slow-topn.html 对照：
#      /api/order/1  8.5s · /api/trace-alert-demo/slow?ms=4000  4.0s
#      /api/export/report?ms={rand:1000,4000} 每请求随机 1~4s
#      /debug 3.1s · /longTimeTask 1.5s · /api/deps-demo/grpc 0.3s（毫秒级对照）

docker compose down
```

⚠️ **两档互斥，不要一起起**：慢端点档单请求 1~8.5s、req/s 天花板约 1~2，
和常规档同跑会把那边的吞吐数字彻底压垮，两边观测都失真。同理**别拿两档的 req/s 互比**。

路径集与线程数都取自 Windows 侧 `scripts/stress.ps1 -MaxRps` 与 `scripts/stress-slow.ps1`
的 2026-10-02 实测值，两处刻意保持一致（免得同一台机器/container 里两套数字对不上）。
**每档为什么"刻意不含"某些端点**，写在 `docker-compose.yaml` 里对应服务的注释上 ——
改路径集前先读那段，别把慢端点或 5xx 混进常规档。

`[progress]` 关键字段：`rps` / `2xx` / `non2xx` / `exc` / `heap` +
`plugin=rowsUpserted, lateDropped, sampleOverflow, endpointOverflow, persistErrors, aggregateErrors`。

**判读要点：并非所有计数"增长"都是异常。** 插件计数分两类：

| 计数 | 期望 | 含义 |
| --- | --- | --- |
| `persistErrors` / `aggregateErrors` | **恒 0** | 落库抛异常 / 聚合器内部异常。真出问题就看它 |
| `lateDropped` | 恒 0 或极少 | 发生时间早于「当前分钟 − 3」的迟到段被丢；持续增长说明有长耗时跨分钟请求 |
| `endpointOverflow` | 恒 0 | 单分钟端点数超 500 被并入 `(other)` |
| `sampleOverflow` | **会持续增长，正常** | 每 (桶,端点) 的蓄水池满 5000 条后，后续样本随机顶替旧样本（等概率抽样）。长跑必然增长，不是错误 |
| `rowsUpserted` | 会持续增长，正常 | 落库行数累计；长时间不动才说明翻转没在干活 |

配套看容器本身：`docker inspect --format '{{.RestartCount}}' <stress容器>` 应恒 0；
`heap` 应在 GC 回落区间**来回震荡**（本镜像压测看到 40–130MB），一路单调攀升才是泄漏信号。

**告警默认已开**（`alert.enabled=true` + `slow_rules=…/api/order/*=8000`）：
这是为了让人手路径开箱即见告警面板的效果。因此 `stress` 侧显式收窄了
`loadtest.paths` 为**正常端点**——错误请求会触发插件**同步 webhook 回打自身**
形成放大回路，恰好污染这里要看的 `persistErrors` / `aggregateErrors` 与 heap 趋势。
**远端默认沿用 compose 的路径集**（即 `stress` 档那 5 条 ms 级 2xx），脚本不再覆盖；
只有明确要连告警链路一起压时才传 `--stress-paths full`（见下文「远端部署」）。

## 远端部署（Paramiko）

```powershell
$env:DEPLOY_SSH_PASSWORD='***'
python agent/demo-app/deploy/deploy_remote.py `
  --host 172.16.1.108 --user root --remote-dir /root/_demo_app_sw_stress
```

脚本流程：`docker save <image> | gzip`(本地) → `mkdir -p` → SFTP 上传
`*.tar.gz` + `docker-compose.yaml` + `settings.xml` → 远端 `docker load` →
`docker compose --profile … up -d` → 轮询 `healthy` → 逐个 tail 日志。

### 两种部署方式，先想清楚再敲

| 方式 | 命令 | 镜像传输 | 容器 | 进程内 H2 指标历史 |
| --- | --- | --- | --- | --- |
| **完整部署** | 默认 | save + 上传 ~436MB + `docker load` | **两个容器都重建**（同 tag 镜像 ID 变了 ⇒ compose 重建） | **清零**（见坑 9） |
| **只更新编排** | 加 `--skip-load` | 不传 | 只有配置变了的 service 重建；仅改压测路径时 **demo-app 不动** | **保留** |

改了压测路径、或只想换个 compose 参数时，**一律用 `--skip-load`**：省掉 436MB 上传，
也不会把已经攒了几小时的指标清零。

### 压测路径：本地与远端刻意不同

| 位置 | 路径集合 | 原因 |
| --- | --- | --- |
| 本地 compose `stress` 档 | 5 条 **ms 级、只 2xx**（`/hello` + 两个 SQL + 两个 `/statistic*` 读口） | 错误路径会同步 webhook 回打自身形成放大环路、污染稳定性观测；慢端点（`/longTimeTask` 1.5s、`/fullSample` 自调回环）会把上限从千级压到几十 RPS |
| 本地 compose `stress-slow` 档 | 6 条**慢端点**（耗时 0.3s~8.5s，均不含 5xx） | 目的是**核对分位准确性** —— 慢端点耗时已知，面板上的 P50/P95 有真值可对；常规档的 ms 级端点分位差异淹没在噪声里 |
| 远端（脚本默认） | **沿用 compose**：`stress` 档 5 条 ms 级 2xx，`stress-slow` 档它的 6 条慢端点 | 远端是长期压力机，稳定性口径要与本机一致；要"指标 + 告警"全压才显式传 `--stress-paths full` |

- 覆盖发生在 `render_remote_compose()`：`--stress-paths` 默认 `compose`（**不覆盖**）；
  传 `full` 用脚本内置的 7 条全压列表（含 `/api/trace-alert-demo/error`、`/http500`），
  或直接传逗号分隔的路径列表。**只作用于 `stress` 服务**，`stress-slow` 的慢端点清单
  是它自己的设计，不受影响。
- **远端 compose 是脚本渲染出来的**，直接 SSH 上去手改会被下一次部署覆盖。
- 108 上**两档有意并存**（compose 注释里那句"别一起起"针对的是本地手动核对）：
  `stress` 常驻冲高，`stress-slow` 由 `--stress-slow-interval` 包成周期循环。
  代价：大盘的全局 P50/P95 与"慢调用"占比会包含慢端点，不再是纯 ms 级口径。

### 远端 profile 与选项

`demo-app` 无常驻 profile；`stress` / `stress-slow` / `deps` 各收在独立 profile 里，
由 `--profile` 指定（可重复，默认 `stress`）。

| 选项 | 默认 | 作用 |
| --- | --- | --- |
| `--profile NAME` | `stress` | 要启动的 profile，可重复。远端已改成给 `up -d` 传 `--profile`（不再改 compose 字段）。传 `none` 只起 `demo-app` |
| `--stress-slow-interval SEC` | `0`（只跑一次） | 把远端 `stress-slow` 包成 `while true; do <原命令>; sleep SEC; done` + `restart: unless-stopped`。会自动补上 `stress-slow` profile |
| `--stress-paths` | `compose` | `compose` / `full` / 逗号分隔列表；只作用于 `stress` 服务 |
| `--stress-threads N` | `0`（沿用 compose 的 32） | 只作用于 `stress` 服务。**线程越多对 demo-app 内存压力越大** —— 108 用 `16`（见坑 14） |
| `--deps-ports` | 关（**远端剥掉**） | deps（redis/mysql/kafka）默认只走 compose 内网，不占宿主端口；加此参数才对外发布 |

108 的推荐命令（压测常驻 + 依赖三层 + 慢档约 10min 一轮）：

```powershell
$env:DEPLOY_SSH_PASSWORD='***'
python agent/demo-app/deploy/deploy_remote.py `
  --host 172.16.1.108 --user root --remote-dir /root/_demo_app_sw_stress `
  --profile stress --profile deps --stress-slow-interval 420 --stress-threads 16
```

⚠️ `stress-slow` **一轮约 3.3 分钟**（240 请求 / 4 线程 / 端点均耗时 3.3s），
周期必须大于一轮时长，否则会背靠背连跑。`420`（7min 间隔）实测约 10min 一轮、
每端点 40 个样本 —— 这是分位可用的下限，再减样本 P95 就成噪声了。

### 切换压测路径后，错误率要等一个窗口

读口的窗口是"最近 N 分钟"，切换前是纯正常流量、切换后才有错误，混在一起会被大幅稀释。
**刚切完看到错误率只有百分之几，别当成指标坏了。** 逐端点查才准：

```bash
curl -s "http://127.0.0.1:9600/inner/sw/metrics/query?aggregate=true&limit=1000"
# 期望：GET:/api/trace-alert-demo/error 与 .../http500 的 errorRate=100%，正常端点 0%
```

确认远端压测真的换成了全压，看日志里那行路径清单（`docker compose logs stress` 的
`paths = [...]`），并确认 `[progress]` 的 `non2xx` 已非 0。

远端产物（本次已部署）：`/root/_demo_app_sw_stress/`
- `docker-compose.yaml`（脚本生成的**部署版**，见下）
- `settings.xml`
- `demo-app-agent-9.4.0.tar.gz`（保留，便于重复 `docker load`）

远端运维：
```bash
cd /root/_demo_app_sw_stress
docker compose logs -f stress
docker compose ps
docker compose down
```

## 踩坑与约定

编号沿用历史列表（5b/5c/5d 是 5 的子条目），便于回溯。

| # | 坑 / 约定 | 原因（Why） | 做法（Do） |
| --- | --- | --- | --- |
| 1 | 聚合 pom 的 module 目录必须存在 | `agent/pom.xml` 的 `<modules>` 列了全部同级插件，Maven 解析聚合 pom 时要求目录存在，哪怕 `-pl` 只构建一个 | `plugin-build` 阶段 `COPY agent agent`，不要只拷插件目录 |
| 2 | 构建/运行 mirror 要一致 | 镜像内 `.m2` 用 `agent/settings.xml`(aliyun) 预热；运行时 `mvn` 不带 `-s` 会因 `_remote.repositories` 仓库 id 不匹配而整包重下 | compose 里 bind mount `../settings.xml:/opt/demo-app/settings.xml:ro`，运行 `mvn` 必须也带 `-s` |
| 3 | 远端无构建上下文 | 远端只有镜像 tar + compose，没有源码树 | `deploy_remote.py` 生成**部署版** compose：删掉 `build:` 段、settings 挂载由 `../settings.xml` 改成 `./settings.xml`；仓库里那份仍保留 `build:` |
| 4 | 镜像已内置 settings.xml | `COPY agent/settings.xml` 早已在 Dockerfile 里；重建曾被本机代理阻过、现已能通过 | compose 里对 `../settings.xml` 的 bind mount 变成冗余但无害；远端部署版仍需要它（见 3） |
| 5 | 偶发 `BUILD FAILURE` 是环境问题 | Windows 宿主代理 `127.0.0.1:7897` 套接字耗尽 → aliyun 返回 500（`Only one usage of each socket address`），与 Dockerfile 无关 | 重试即可，别改 Dockerfile |
| 5b | 拉 agent 发行包不要用 `ADD https://…` | 远端 ADD 经代理易 flaky（`unexpected EOF`） | 改用 `curl --retry 5 --retry-all-errors`（同 `verify/Dockerfile`） |
| 5c | 刻意不写 `# syntax=docker/dockerfile:1` | 该指令会强制拉前端镜像，受限网络下易失败 | 保持 Dockerfile 无 syntax 指令 |
| 5d | 插件 jar 用通配符拷入 | 写死版本号会在升版后静默失效 | `logfile-reporter-plugin-*.jar`（不会误中 `original-logfile-reporter-plugin-*.jar`） |
| 6 | 资源上限 | 固定堆更容易看出内存趋势 | demo-app `-Xms128m -Xmx512m` + `mem_limit: 2g`（原为 `-Xms512m` + `1g`，实测会被 cgroup OOM，见坑 14） |
| 7 | H2 需要显式开启 | 否则 `status/storage` 相关读口可能为空 | `-Dskywalking.plugin.logfilereporter.h2.enabled=true` |
| 8 | H2 Web Console 远程访问受 Host 白名单限制 | compose 已开内置控制台（`h2.console_enabled=true`，端口 8092），本地 `http://127.0.0.1:8092` 正常；用宿主 IP（如 `http://172.16.1.108:8092`）返回 `HTTP 404` + 响应体 `Host 172.16.1.108 not found`。根因：H2 2.1.212 的 `WebThread.checkHost` 只放行 server 自身地址、`localhost`/`127.0.0.1` 与 `webExternalNames` 列表；插件传的 `-webAllowOthers` 只管 socket 层是否接受非本机连接，**不**解除该 Host 校验（容器内自身地址是容器 IP，故宿主 IP 不在白名单） | **临时绕过（无需重建）**：SSH 本地端口转发 `ssh -L 8092:127.0.0.1:8092 root@172.16.1.108`，浏览器访问 `http://localhost:8092`（Host 为 localhost，放行）；命令行验证用 `curl -H 'Host: localhost' http://172.16.1.108:8092`（应 200）。<br>**根治**：给控制台传 `-webExternalNames=<host>[,<host>...]`；当前插件 `startConsole` 写死参数、未暴露该项，需新增配置（如 `h2.console_external_names`）后重建镜像 |
| 8b | 影子库 JDBC URL / 控制台登录凭据 | 影子库地址是 `H2TraceSegmentStorage.java:62` 的 `JDBC_URL` **写死常量**，没有对应的 `h2.*` 配置项（`h2.*` 只管 `enabled` / `shadow_max_rows` / `payload_capped_size_mb` / `console_enabled`）；`DB_CLOSE_DELAY=-1` 表示"最后一条连接断了也保留内存库"，进程退出才丢，所以控制台断开重连还能查到数据 | 8092 控制台登录填 URL `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`、User `sa`、Password **留空**（H2 建库时给的是空密码）。<br>别和 demo-app 自己的库混淆：`jdbc:h2:mem:dbtest`（9600 的 `/h2` 控制台）密码是 `123456` |
| 9 | **完整部署会清空指标历史** | 影子库是 `jdbc:h2:mem:`（纯内存，进程退出即丢，见 8b）。完整部署 `docker load` 同 tag 的新镜像后镜像 ID 变化 ⇒ `docker compose up -d` 重建容器 ⇒ 进程重启 ⇒ 指标归零。实测：一次全量部署把攒了 19 小时的 7d 视图打回原点 | 只改 compose/压测路径时用 `--skip-load`；需要保留历史就别重建 demo-app 容器。部署后想确认起点，看指标序列最早那分钟是否等于容器启动时间 |
| 10 | `stress` 容器重建 ≠ `demo-app` 重建 | `docker compose up -d` 只重建**配置发生变化**的 service。改 `stress.command` 的压测路径只重建 stress，demo-app 继续跑、指标不断 | 想只换压测路径就 `--skip-load`；别为了改压测路径去动 demo-app 的镜像或命令行 |
| 11 | `sampleOverflow` 增长不是故障 | 每 (分钟桶, endpoint) 的蓄水池满 5000 条后，后续样本按 `floorMod(random.nextLong(), seen)` 随机顶替旧样本（等概率抽样，见 `TraceMetricsAggregator` 的 `MAX_SAMPLES_PER_KEY`）。持续压测必然增长 | 判稳定性只看 `persistErrors` / `aggregateErrors` / `endpointOverflow` 是否为 0，以及 `heap` 是否在 GC 回落区间震荡、`RestartCount` 是否恒 0 |
| 12 | stress 容器 OOM 重启循环 | `mvn` JVM 与 surefire fork（`HttpLoadTest` 的子 JVM）**都不带 `-Xmx`**，在 `mem_limit` 下很容易被 cgroup OOM kill；`restart: unless-stopped` 把它变成重启循环（实测 `RestartCount` 一度到 348），表现为指标出现分钟级空洞 | compose 里 `stress` 显式限两处堆：`MAVEN_OPTS: -Xmx256m`（maven 自身）+ `-DargLine=-Xmx512m`（surefire fork），并把 `mem_limit` 提到 `2g` 留余量。验证：`docker inspect --format '{{.RestartCount}}' <stress容器>` 应恒 0 |
| 13 | 本机 Docker 拉不动镜像（三种表现） | **① daemon 的代理来自 Windows 系统代理**：`settings-store.json` 里没有 `proxy` 段（=未启用 Manual 覆盖）时，Docker Desktop 直接继承 `Internet Settings` 的 `ProxyServer`。而 Linux 引擎跑在 WSL2 的 `docker-desktop` 发行版里，它看到的 `127.0.0.1` 是**虚拟机自己的回环**，那里没有代理在监听 → `proxyconnect tcp: dial tcp 127.0.0.1:7897: connect: connection refused`（宿主侧 `Test-NetConnection 127.0.0.1 -Port 7897` 是通的，别被误导）。**②** Docker Hub 直连超时。**③** 走代理时 socket 耗尽（`Only one usage of each socket address`） | **先看 daemon 到底用的谁**：`docker info` 会打印生效的 `HTTP Proxy / HTTPS Proxy`——那才是真值来源。三条修法：**(a)** Docker Desktop → Resources → Proxies → 开 Manual，两栏填 `http://host.docker.internal:7897`（**不要清空**，清空=不走代理，直连 Docker Hub 通常拉不动）→ Apply & restart；**(b)** 保持 System proxy，把 Windows `ProxyServer` 改成 `host.docker.internal:7897` 后彻底重启 Docker——有效但属 **OS 级**改动，浏览器等走系统代理的应用会一起被改；**(c)** Docker Engine 的 `registry-mirrors`（本机已配 daocloud）配 (a) 使用通常就够。验证：`docker pull redis:7` 能出层即好。另：alpine 变体在镜像源下更容易失败，演示用 `redis:7` 即可（只大 30MB） |
| 14 | **demo-app 自己也会被 cgroup OOM**（不只是 stress 容器，见 12） | 实测 RSS 稳定在 **~960MB 且不增长**（105s 采样波动 <1MB，**非泄漏**）。构成：`-Xms512m` 启动即提交 491MB 堆，而堆 `used` 只有 **~42MB**（92% 是空占的 committed 页）；再加 ~440MB native（126 线程栈 / netty / agent 字节码）。旧的 `mem_limit: 1g` 只剩 ~60MB 余量，32 线程压测（5860 rps，比老档高约 50 倍）的瞬时尖峰就顶出去：`dmesg` 见 `Memory cgroup out of memory ... anon-rss:1038776kB`，`RestartCount=2`（10:44 / 11:20 各一次）。**每次重启都清空内存 H2 ⇒ 指标归零**，表现为"7d 视图又变回几分钟" | `-Xms512m → -Xms128m`（砍掉纯浪费的 ~380MB 常驻）+ `mem_limit: 1g → 2g`；108 的 stress 另降到 `--stress-threads 16`（压测负载减半）。诊断三步：`docker stats --no-stream`（看 `MemPerc`）→ `docker inspect --format '{{.RestartCount}}' <demo-app容器>` → `dmesg -T \| grep -i "out of memory"`。⚠️ **别把"压测时应用不可达"直接归因给压测**：先看 `metrics 读口` 是不是**从第一条进度行就**不可达——那是应用已经死了（`ConnectTimeoutException`），压测只是撞上了它。⚠️ 另需盯的：`mysql` 512m 常年 ~70%、`stress` 2g 用到 ~40%，都还没到但离上限不远 |
| 14 | **依赖拓扑页的依赖面靠 `deps` profile 撑起来** | demo-app 的进程内依赖只有 H2（`h2-jdbc-driver`）与回环自调的 Hutool HTTP（`componentId=128`，**不在组件库里**故显示为 `Http(#128)`）。Cache / Database(真库) / MQ 三层由 compose 的 `deps` profile 提供（`redis:7` / `mysql:8.0` / `apache/kafka:3.8.0`），**默认不起**；另有白名单真实外呼 `httpbin` / `baidu` / `google`（`/api/deps-demo/http?site=…`） | Windows 手工台：`pwsh ./scripts/run-with-agent.ps1 -WithDeps` 起应用+中间件，`pwsh ./scripts/stress.ps1 -WithDeps -Requests 50 -Threads 8` 造数（`-WithDeps` 两个脚本同名同义：一个管中间件在不在、一个管压哪些路径），看中间件状态。看图前先造数——依赖边**纯内存、分钟级窗口、重启即失**（同坑见 8b / 9）。尾巴分位需单边样本 ≥20 才出值，少样本时 `p90/p95/p99` 为 `-1` 属正常口径不是故障。组件名按**实测值**断言（库里 H2 的键是 `h2-jdbc-driver`、Kafka 是 `kafka-producer`，不是 "H2" / "Kafka"） |
| 15 | **中间件没起 = Redis/MySQL 没有边，不是"有红边"** | `DepsDemoController` 无论中间件在不在都**照调**，但**连接建立失败不会产生依赖边**：出口 span 建立在"连接已建立之后的调用"上，而 `connection refused` / 主机名解析失败发生在它之前。实测 Redis / MySQL 连不上时拓扑读口里**没有** `Jedis`/`Mysql` 边；Kafka `send`（不需要连接成功）与 Hutool 外呼**照样有边** | 读图时区分两种"失败"：**无边** = 依赖没连上（缺席**不能**读成"没有调用"，确认请看链路视图）；**有边但 `errorCount=0`** = 调用发生了、客户端只是超时（Kafka `send` 的 `max.block.ms` 超时**不会**置 `isError`），**红边只表示 span 被判错**。verify 的分层断言按此设计（`checks.sh` 断言 F），不要写成"无中间件时 Redis/MySQL 应有红边"。起中间件：`pwsh ./scripts/run-with-agent.ps1 -WithDeps`（内部就是 `docker compose --profile deps up -d`） |
| 16 | **中间件客户端版本必须落在 agent 插件的 support 范围内** | agent 9.4.0：`jedis-2.x-3.x` / `jedis-4.x`、`mysql-8.x`、`kafka-0.11.x~2.x`（另有 `kafka-3.7.x` 一条）、**`lettuce-5.x` 只有 5.x**。Boot 2.7 管理的 lettuce 是 6.x、kafka 3.6 也不在范围 —— 版本不匹配的插件会**静默不生效**：不报错、图上就是没有那个节点 | demo-app 显式钉 `jedis 3.8.0` / `kafka-clients 2.8.2`（不用 lettuce、不用 spring-kafka），MySQL 用 Boot 管理的 `mysql-connector-java`。升级这些依赖前先读 `plugins/apm-*-plugin-*.jar` 里的 `plugin.def` 对一遍 |
