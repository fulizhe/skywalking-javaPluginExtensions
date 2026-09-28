# Docker 持续压测 notes

本批新增：把 demo-app + logfile-reporter-plugin + SkyWalking agent 打进一个镜像，
用 compose 在 Linux 上长期跑压测，观测插件在长跑下的稳定性。

## 新增/涉及文件

| 文件 | 作用 |
| --- | --- |
| `agent/demo-app/Dockerfile` | 多阶段构建：插件(JDK17) → demo-app(JDK8) → 运行时(JDK8 + Maven + agent) |
| `agent/demo-app/docker-compose.yaml` | 编排 `demo-app`(常驻) + `stress`(无限压测) |
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

```bash
cd agent/demo-app
docker compose up --build -d
docker compose logs -f stress      # 每 10s 一行 [progress]
docker compose down
```

`[progress]` 关键字段：`rps` / `2xx` / `exc` / `heap` +
`plugin=rowsUpserted, lateDropped, sampleOverflow, endpointOverflow, persistErrors, aggregateErrors`。
判断稳定性看这些计数是否异常增长、`heap` 是否持续攀升。

默认是**纯指标**实例（`alert.enabled` 未开，只压正常端点）；要连告警一起压，
在 `demo-app.command` 里追加 `-Dskywalking.plugin.logfilereporter.alert.enabled=true`
（及 `alert.slow_rules`），并把 `stress` 的 `loadtest.paths` 换回含 `/error`、`/http500`。

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
4. **当前镜像未内置 settings.xml**：加 `COPY agent/settings.xml` 后重建受阻于本机代理，
   故远端靠 bind mount 提供；重建成功能后镜像自带，挂载变成冗余但无害。
5. **偶发 `BUILD FAILURE` 是环境问题**：Windows 宿主代理 `127.0.0.1:7897` 套接字耗尽
   会让 aliyun 返回 500（`Only one usage of each socket address`），与 Dockerfile 无关，重试即可。
6. **资源上限**：demo-app `-Xms512m -Xmx512m` + `mem_limit: 1g`，用固定堆更容易看出内存趋势。
7. **H2 需要显式开启**：`-Dskywalking.plugin.logfilereporter.h2.enabled=true`，
   否则 `status/storage` 相关读口可能为空。
