# 03 — compose deps profile：Redis / MySQL / Kafka

**What to build:** `agent/demo-app/docker-compose.yaml` 增加三个中间件 service，收在 `profiles: ["deps"]`，并把服务名通过环境变量注入 demo-app。目标：一条 `docker compose --profile deps up` 起齐，看得到完整的依赖拓扑图。

**Blocked by:** 无。

**Status:** resolved —— compose config 校验通过(默认只 demo-app,--profile deps 多三个);容器内实跑未做(本机 docker daemon 不可用)
## 编排

- [ ] 三个 service：`redis`、`mysql`、`kafka`，**全部 `profiles: ["deps"]`** —— 沿用仓库既有做法（`stress` 就收在 profile 里），默认 `up` 保持轻量不变。
- [ ] `demo-app` 段加 `environment: DEPS_REDIS_HOST: redis` / `DEPS_MYSQL_URL: jdbc:mysql://mysql:3306/...` / `DEPS_KAFKA_BOOTSTRAP: kafka:9092` 等。
  - **不改 `command`**（java 命令行不加新 `-D`），Spring 直接读环境变量（票 02 的 `${...:默认}` 兜底）。
  - `depends_on` 只等**已声明 healthcheck** 的服务；不等未声明健康检查的（避免 compose 卡在 `service_started` 之外的语义差异上）。
- [ ] 各 service 给 `mem_limit`；**MySQL 不挂 init sql、不建表**（票 02 只 `SELECT 1` / `SLEEP`）。
- [ ] Kafka 用 **KRaft 单节点**（不配 ZK），够造边即可。
- [ ] 是否把中间件端口映射到宿主机（`6379`/`3306`/`9092`）由实现者定；映射则写进 compose 注释（便于用 redis-cli 现场看）。

## 文档与注释（compose 内）

- [ ] 文件头注释补一条用法：**看拓扑图**用 `docker compose --profile deps up --build -d`，随后造数端点见 `README`。
- [ ] 注释写明：**外网可达性取决于宿主网络**——baidu / google 不通就是红边，是预期不是故障。

## 不做

- [ ] 不改默认 `docker compose up` 的行为（不加中间件）。
- [ ] 不加 ES / MongoDB / RocketMQ。
- [ ] 不改 demo-app 镜像与 `mem_limit`（1g）。
- [ ] 不改 `verify/` 的场景编排（verify 在宿主机直接跑 jar，用 `localhost`，不需要 compose 服务）。

## 验证

- [ ] `docker compose config` 通过；`docker compose --profile deps up --build -d` 后三个中间件 healthy/running。
- [ ] 不带 profile 的 `docker compose up` 仍只起 demo-app（+ 可选 stress）。
- [ ] 容器内 `curl` 四个造数端点：Redis / MySQL / Kafka 三条**绿**（`ok:true`），外呼视网络。