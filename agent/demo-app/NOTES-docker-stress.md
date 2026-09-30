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

`[progress]` 关键字段：`rps` / `2xx` / `exc` / `heap` +
`plugin=rowsUpserted, lateDropped, sampleOverflow, endpointOverflow, persistErrors, aggregateErrors`。
判断稳定性看这些计数是否异常增长、`heap` 是否持续攀升。

**告警默认已开**（`alert.enabled=true` + `slow_rules=…/api/order/*=8000`）：
这是为了让人手路径开箱即见告警面板的效果。因此 `stress` 侧显式收窄了
`loadtest.paths` 为**正常端点**——错误请求会触发插件**同步 webhook 回打自身**
形成放大回路，恰好污染这里要看的 `persistErrors` / `aggregateErrors` 与 heap 趋势。
要压"指标+告警耦合"，在 `stress.command` 的 `-Dloadtest.paths` 末尾追加
`,/api/trace-alert-demo/error,/api/trace-alert-demo/http500`；
要回到纯指标长跑，则把 `demo-app.command` 里的两行 `alert.*` 参数删掉。

## 远端部署（Paramiko）

```powershell
$env:DEPLOY_SSH_PASSWORD='***'
python agent/demo-app/deploy/deploy_remote.py `
  --host 172.16.1.108 --user root --remote-dir /root/_demo_app_sw_stress
```

脚本流程：`docker save <image> | gzip`(本地) → `mkdir -p` → SFTP 上传
`*.tar.gz` + `docker-compose.yaml` + `settings.xml` → 远端 `docker load` →
`docker compose up -d` → 轮询 `healthy` → tail stress 日志。

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

1. **聚合 pom 的 module 目录必须存在**：`agent/pom.xml` 的 `<modules>` 列了全部同级插件，
   Maven 解析聚合 pom 时要求目录存在，哪怕 `-pl` 只构建一个 → 所以 `plugin-build`
   阶段是 `COPY agent agent`，不是只拷插件目录。
2. **构建/运行 mirror 要一致**：镜像内 `.m2` 是用 `agent/settings.xml`(aliyun) 预热的；
   运行时 `mvn` 必须也带 `-s`，否则会因 `_remote.repositories` 仓库 id 不匹配而整包重下。
   compose 里以 bind mount `../settings.xml:/opt/demo-app/settings.xml:ro` 提供。
3. **远端无构建上下文**：`deploy_remote.py` 生成部署版 compose——删掉 `build:` 段、
   把 settings 挂载由 `../settings.xml` 改成 `./settings.xml`。本地仓库那份仍保留 `build:`。
4. **镜像已内置 settings.xml**：`COPY agent/settings.xml` 早已在 Dockerfile 里，
   重建也曾被本机代理阻过、现已能通过；compose 里对 `../settings.xml` 的 bind mount
   变成冗余但无害，远端部署版仍需要它（远端无构建上下文）。
5. **偶发 `BUILD FAILURE` 是环境问题**：Windows 宿主代理 `127.0.0.1:7897` 套接字耗尽
   会让 aliyun 返回 500（`Only one usage of each socket address`），与 Dockerfile 无关，重试即可。
5b. **拉 agent 发行包不要用 `ADD https://…`**：远端 ADD 经代理易 flaky
   （`unexpected EOF`），已改为 `curl --retry 5 --retry-all-errors`（同 `verify/Dockerfile`）。
   同理刻意不写 `# syntax=docker/dockerfile:1`——该指令会强制拉前端镜像，受限网络下易失败。
   另：插件 jar 用通配符 `logfile-reporter-plugin-*.jar` 拷入，不再写死版本号，
   避免升版后静默失效（通配符不会误中 `original-logfile-reporter-plugin-*.jar`）。
6. **资源上限**：demo-app `-Xms512m -Xmx512m` + `mem_limit: 1g`，用固定堆更容易看出内存趋势。
7. **H2 需要显式开启**：`-Dskywalking.plugin.logfilereporter.h2.enabled=true`，
   否则 `status/storage` 相关读口可能为空。
8. **H2 Web Console 远程访问受 Host 白名单限制**：compose 已开插件内置控制台
   （`h2.console_enabled=true`，端口 8092）。本地 `http://127.0.0.1:8092` 正常，
   但用宿主 IP（如 `http://172.16.1.108:8092`）会返回 `HTTP 404` + 响应体
   `Host 172.16.1.108 not found`。原因是 H2 2.1.212 的 `WebThread.checkHost` 只放行
   server 自身地址、`localhost`/`127.0.0.1` 与 `webExternalNames` 列表；插件传的
   `-webAllowOthers` 只管 socket 层是否接受非本机连接，并**不**解除该 Host 校验
   （容器内自身地址是容器 IP，故宿主 IP 不在白名单）。
   - **临时绕过（无需重建）**：SSH 本地端口转发
     `ssh -L 8092:127.0.0.1:8092 root@172.16.1.108`，浏览器访问
     `http://localhost:8092`（Host 为 localhost，放行）；命令行验证可用
     `curl -H 'Host: localhost' http://172.16.1.108:8092`（应 200）。
   - **根治**：给控制台传 `-webExternalNames=<host>[,<host>...]`。当前插件
     `startConsole` 写死参数、未暴露该项，需新增配置（如
     `h2.console_external_names`）后重建镜像。
