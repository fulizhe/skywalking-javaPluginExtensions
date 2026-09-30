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

`stress` 收在 compose profile 里，**默认不启动**（只想看效果就别带它）。

```bash
cd agent/demo-app
# A) 只起 demo-app，用浏览器看仪表盘（告警默认已开）
docker compose up --build -d demo-app
#    浏览器 http://127.0.0.1:9600/  与  /dashboards/index.html

# B) 连压测一起起（显式带 profile）
docker compose --profile stress up --build -d
docker compose logs -f stress      # 每 10s 一行 [progress]
docker compose down
```

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
**远端压力机要的是"指标 + 告警"全压**，由 `deploy_remote.py` 在渲染远端 compose 时
覆盖成含错误路径的混合列表（见下文「远端部署」），**不需要手改 compose**。
要让远端也只压正常路径（纯指标长跑），给部署命令加 `--stress-paths compose`。

## 远端部署（Paramiko）

```powershell
$env:DEPLOY_SSH_PASSWORD='***'
python agent/demo-app/deploy/deploy_remote.py `
  --host 172.16.1.108 --user root --remote-dir /root/_demo_app_sw_stress
```

脚本流程：`docker save <image> | gzip`(本地) → `mkdir -p` → SFTP 上传
`*.tar.gz` + `docker-compose.yaml` + `settings.xml` → 远端 `docker load` →
`docker compose up -d` → 轮询 `healthy` → tail stress 日志。

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
| 本地 compose | 5 条**正常**路径 | 错误路径会同步 webhook 回打自身形成放大环路，污染稳定性观测 |
| 远端（脚本默认） | 7 条**全压**（含 `/api/trace-alert-demo/error`、`/http500`） | 压力机要看"指标 + 告警"耦合下的表现 |

- 覆盖发生在 `render_remote_compose()`：就地替换 `-Dloadtest.paths` 并打印实际生效值；
  传 `--stress-paths compose` 则沿用 compose 里的 5 条正常路径。
- **远端 compose 是脚本渲染出来的**，直接 SSH 上去手改会被下一次部署覆盖。

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
| 6 | 资源上限 | 固定堆更容易看出内存趋势 | demo-app `-Xms512m -Xmx512m` + `mem_limit: 1g` |
| 7 | H2 需要显式开启 | 否则 `status/storage` 相关读口可能为空 | `-Dskywalking.plugin.logfilereporter.h2.enabled=true` |
| 8 | H2 Web Console 远程访问受 Host 白名单限制 | compose 已开内置控制台（`h2.console_enabled=true`，端口 8092），本地 `http://127.0.0.1:8092` 正常；用宿主 IP（如 `http://172.16.1.108:8092`）返回 `HTTP 404` + 响应体 `Host 172.16.1.108 not found`。根因：H2 2.1.212 的 `WebThread.checkHost` 只放行 server 自身地址、`localhost`/`127.0.0.1` 与 `webExternalNames` 列表；插件传的 `-webAllowOthers` 只管 socket 层是否接受非本机连接，**不**解除该 Host 校验（容器内自身地址是容器 IP，故宿主 IP 不在白名单） | **临时绕过（无需重建）**：SSH 本地端口转发 `ssh -L 8092:127.0.0.1:8092 root@172.16.1.108`，浏览器访问 `http://localhost:8092`（Host 为 localhost，放行）；命令行验证用 `curl -H 'Host: localhost' http://172.16.1.108:8092`（应 200）。<br>**根治**：给控制台传 `-webExternalNames=<host>[,<host>...]`；当前插件 `startConsole` 写死参数、未暴露该项，需新增配置（如 `h2.console_external_names`）后重建镜像 |
| 8b | 影子库 JDBC URL / 控制台登录凭据 | 影子库地址是 `H2TraceSegmentStorage.java:62` 的 `JDBC_URL` **写死常量**，没有对应的 `h2.*` 配置项（`h2.*` 只管 `enabled` / `shadow_max_rows` / `payload_capped_size_mb` / `console_enabled`）；`DB_CLOSE_DELAY=-1` 表示"最后一条连接断了也保留内存库"，进程退出才丢，所以控制台断开重连还能查到数据 | 8092 控制台登录填 URL `jdbc:h2:mem:sw_trace_segment;DB_CLOSE_DELAY=-1`、User `sa`、Password **留空**（H2 建库时给的是空密码）。<br>别和 demo-app 自己的库混淆：`jdbc:h2:mem:dbtest`（9600 的 `/h2` 控制台）密码是 `123456` |
| 9 | **完整部署会清空指标历史** | 影子库是 `jdbc:h2:mem:`（纯内存，进程退出即丢，见 8b）。完整部署 `docker load` 同 tag 的新镜像后镜像 ID 变化 ⇒ `docker compose up -d` 重建容器 ⇒ 进程重启 ⇒ 指标归零。实测：一次全量部署把攒了 19 小时的 7d 视图打回原点 | 只改 compose/压测路径时用 `--skip-load`；需要保留历史就别重建 demo-app 容器。部署后想确认起点，看指标序列最早那分钟是否等于容器启动时间 |
| 10 | `stress` 容器重建 ≠ `demo-app` 重建 | `docker compose up -d` 只重建**配置发生变化**的 service。改 `stress.command` 的压测路径只重建 stress，demo-app 继续跑、指标不断 | 想只换压测路径就 `--skip-load`；别为了改压测路径去动 demo-app 的镜像或命令行 |
| 11 | `sampleOverflow` 增长不是故障 | 每 (分钟桶, endpoint) 的蓄水池满 5000 条后，后续样本按 `floorMod(random.nextLong(), seen)` 随机顶替旧样本（等概率抽样，见 `TraceMetricsAggregator` 的 `MAX_SAMPLES_PER_KEY`）。持续压测必然增长 | 判稳定性只看 `persistErrors` / `aggregateErrors` / `endpointOverflow` 是否为 0，以及 `heap` 是否在 GC 回落区间震荡、`RestartCount` 是否恒 0 |
| 12 | stress 容器 OOM 重启循环 | `mvn` JVM 与 surefire fork（`HttpLoadTest` 的子 JVM）**都不带 `-Xmx`**，在 `mem_limit` 下很容易被 cgroup OOM kill；`restart: unless-stopped` 把它变成重启循环（实测 `RestartCount` 一度到 348），表现为指标出现分钟级空洞 | compose 里 `stress` 显式限两处堆：`MAVEN_OPTS: -Xmx256m`（maven 自身）+ `-DargLine=-Xmx512m`（surefire fork），并把 `mem_limit` 提到 `2g` 留余量。验证：`docker inspect --format '{{.RestartCount}}' <stress容器>` 应恒 0 |
| 13 | 本机 Docker 拉不动 Docker Hub | 直连 `registry-1.docker.io:443` 超时；而走 Windows 系统代理 `127.0.0.1:7897` 会撞上套接字耗尽（`Only one usage of each socket address`），两种方式都构建失败 | Docker Desktop → Settings → Docker Engine 配 `"registry-mirrors": ["https://docker.m.daocloud.io"]`（实测能拉到 `maven:3.8.6-eclipse-temurin-*`），并把 Resources → Proxies 的 Manual 打开留空（不走系统代理）。aliyun / archive.apache.org 直连本来就通，不受影响 |
| 14 | **依赖拓扑页的依赖面天生很薄** | demo-app 的"外部依赖"只有三个：进程内嵌 H2（`h2-jdbc-driver`）、回环自调的 Hutool HTTP（`componentId=128`，**不在组件库里**故显示为 `Http(#128)`）、以及一个真实外呼 `https://httpbin.org`（`/api/trace-alert-demo/httpclient-httpbin`）。缓存 / MQ 一个都没有 | **这是预期的**：机制正确性优先于图的丰富度，真实宽度由使用者自己的单体提供。造数用 `/queryDbByMybatis`、`/queryDbByJdbc`、`/api/hutool-demo/post-json`；看图前先重造数——依赖边是**纯内存、分钟级窗口、重启即失**（同坑见 8b / 9）。尾巴分位需单边样本 ≥20 才出值，少样本时 `p90/p95/p99` 显示 `-1` 属正常口径不是故障 |
