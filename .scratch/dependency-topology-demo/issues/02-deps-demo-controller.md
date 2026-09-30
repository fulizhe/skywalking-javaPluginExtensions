# 02 — demo-app 依赖造数面：DepsDemoController（超时为硬要求）

**What to build:** demo-app 新增三类中间件的客户端依赖 + 独立 `deps.*` 配置 + 一个 `DepsDemoController`，产出 Cache / Database / MQ / 真实外呼四组依赖边。**不需要中间件在场也能跑通**（连不上 → 红边）。

**Blocked by:** 无（可先做；中间件在 03 起）。

**Status:** open

## 依赖与配置

- [ ] `pom.xml` 加三个（agent 侧插件已就位，宿主组件库已收录，**组件名零改动**）：
  - [ ] `spring-boot-starter-data-redis`（Lettuce）
  - [ ] `mysql-connector-j`
  - [ ] `org.apache.kafka:kafka-clients`（**不用** spring-kafka）
- [ ] 配置走**独立 `deps.*` 前缀 + 环境变量兜底**：`${DEPS_REDIS_HOST:localhost}` / `DEPS_REDIS_PORT:6379` / `DEPS_MYSQL_URL:` / `DEPS_MYSQL_USER:` / `DEPS_MYSQL_PASSWORD:` / `DEPS_KAFKA_BOOTSTRAP:` / `DEPS_KAFKA_TOPIC:`。
- [ ] **不碰 `spring.datasource`**（会让 Boot 用 MySQL 顶掉 H2 演示库；NOTES 已记 H2 的既有坑）；MySQL DataSource 与 Kafka producer/consumer **手工建**。

## 端点

- [ ] `GET /api/deps-demo/redis?op=get|set|del` —— Lettuce；三种 op 让明细档出三个节点。
- [ ] `GET /api/deps-demo/mysql?sleepMs=0` —— `SELECT 1` / `SELECT SLEEP(?)`，**不建表**；`sleepMs` **上限 3000**（超过就拒），用于造慢边。
- [ ] `GET /api/deps-demo/kafka?op=produce|consume` —— kafka-clients 手工 producer/consumer；topic 用 Admin 自动建，consume 带 poll 超时。
- [ ] `GET /api/deps-demo/http?site=httpbin|baidu|google` —— Hutool（已有 override 插件，面更宽）；**白名单枚举**，不接受任意 URL 参数。
- [ ] 四个端点的**响应体**回显：调用的目标、耗时、是否失败、失败原因摘要（便于页面/README 说明造数效果）。

## 超时（硬要求，不是可选项）

> 造数端点会被 16 线程压测打、也被页面 5s 轮询读。**默认超时多为无限或几十秒**，一旦某条出网调用挂住，压测会退化成串行等待、页面会读到半截数据。

- [ ] 每条出网调用**显式设值**，逐条记下实际生效值（Boot 属性名随版本变，认不认要实测）：

| 通道 | 参数 | 取值 |
| --- | --- | --- |
| Redis | 连接超时 + 命令超时 | 各 1s |
| MySQL | JDBC URL `connectTimeout` / `socketTimeout` | 2000ms / 3000ms |
| Kafka | `request.timeout.ms` / `delivery.timeout.ms` / `max.block.ms` | 3000 / 5000 / 3000 |
| Hutool 外呼 | `HttpRequest.timeout()`（一个值覆盖连接与读） | 3000ms |

- [ ] 每个端点整体 `try/catch`，异常转成响应体字段返回（HTTP 200 + `ok:false`）→ **Entry 不标错、边标错**：图上表现为"self 的边红、端点不红"，正好演示边级错误口径，且**不刷 Trace 告警**。
- [ ] 不新增 `@Trace` 注解或手动 span（要的就是真实 agent 打点）。

## 不做

- [ ] 不加 ES / MongoDB / RocketMQ / spring-kafka。
- [ ] 不做任意 URL 外呼（SSRF 口子）。
- [ ] 不起任何中间件进程（属票 03 的 compose）。
- [ ] 不动既有 `HelloController` / `FullSampleController` / `HutoolHttpDemoController` 与既有造数端点。

## 验证

- [ ] 无中间件时逐个 `curl` 四个端点：均在**数秒内**返回，`ok:false` 且带失败原因（连不上是预期）。
- [ ] `/api/deps-demo/mysql?sleepMs=500` 明显慢于 `sleepMs=0`（验证造慢可用）。
- [ ] `/api/deps-demo/http?site=未知` 被拒（白名单生效）。
- [ ] `mvn -f agent/demo-app/pom.xml -DskipTests package` 通过。