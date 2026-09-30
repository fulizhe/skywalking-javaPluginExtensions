# Resources · SkyWalking Java `test/` 与可移植集成测试模式

所有链接都指向一手来源。课程里的事实断言都应能在这里找到对应条目。
记录基准 commit：`ea2fb09b0736cbbcc28b66ca57ca6513f6c9284f`（apache/skywalking-java, main）。

## 一手来源 —— 上游框架（最高信任）

### 必读主线

- [test/plugin/CLAUDE.md — Plugin Test Guide](https://github.com/apache/skywalking-java/blob/main/test/plugin/CLAUDE.md)
  **本课程第 1 课的权威文本。** Apache 自己给 AI/写测试的人的指南，浓缩了整套 philosophy：
  「场景即测试」、断言完整 span 形状、跨进程 ref 必须断言、版本矩阵规则、容器类型与
  JDK/Tomcat 版本的真实来源、冷启动 3s 超时。**读这一份的收益 > 读另外九份。**
- [docs/en/setup/service-agent/java-agent/Plugin-test.md — 官方机制文档](https://github.com/apache/skywalking-java/blob/main/docs/en/setup/service-agent/java-agent/Plugin-test.md)
  框架的完整 mechanics：`configuration.yml` / `expectedData.yaml` 全部字段语义、
  匹配算子（`eq` `ne` `gt` `ge` `not null` `not blank` `start with` `end with`）、
  本地运行方式、新增 scenario 步骤。**当作 schema 参考手册用。**
- [test/plugin/run.sh](https://github.com/apache/skywalking-java/blob/main/test/plugin/run.sh)
  编排器入口。看它怎么遍历 `support-version.list`、怎么调 runner-helper、怎么传
  `base_image_java` / `base_image_tomcat`。
- [test/plugin/generator.sh](https://github.com/apache/skywalking-java/blob/main/test/plugin/generator.sh)
  脚手架生成器。**「框架必须自带 30 秒新建测试的入口」这个模式本身就是可迁移知识点。**

### 源码锚点

- [test/plugin/runner-helper/…/Main.java](https://github.com/apache/skywalking-java/tree/main/test/plugin/runner-helper/src/main/java/org/apache/skywalking/plugin/test/helper)
  YAML → 运行脚本的翻译层（`DockerComposeRunningGenerator` / `DockerContainerRunningGenerator`）。
- [test/plugin/runner-helper/…/vo/CaseConfiguration.java](https://github.com/apache/skywalking-java/blob/main/test/plugin/runner-helper/src/main/java/org/apache/skywalking/plugin/test/helper/vo/CaseConfiguration.java)
  `configuration.yml` 的 POJO —— 配置模型的权威定义。
- [test/plugin/containers/jvm-container/src/main/docker/run.sh](https://github.com/apache/skywalking-java/blob/main/test/plugin/containers/jvm-container/src/main/docker/run.sh)
  容器入口。真相在这里：起 mock collector（19876）、健康检查、3s 冷启动 curl、
  抓 `actualData.yaml`、跑 validator、成功即删 actualData。
- [test/e2e/base/base-compose.yml](https://github.com/apache/skywalking-java/blob/main/test/e2e/base/base-compose.yml)
  全栈那半边：OAP + BanyanDB + provider + consumer。
- [test/e2e/case/grpc/e2e.yaml](https://github.com/apache/skywalking-java/blob/main/test/e2e/case/grpc/e2e.yaml)
  e2e 的编排三段式：`setup` / `trigger` / `verify`（GraphQL 查询 + 期望文件比对）。

### 精读样本（课程会用到）

- [test/plugin/scenarios/struts2.7-scenario](https://github.com/apache/skywalking-java/tree/main/test/plugin/scenarios/struts2.7-scenario)
  **首选样本。** `type: tomcat`（WAR），且断言了完整跨进程 `refs:` 块 ——
  2 entry + 1 exit 的标准形状，最能说明「断言完整形状」是什么意思。
- [test/plugin/scenarios/httpclient-4.3.x-scenario](https://github.com/apache/skywalking-java/tree/main/test/plugin/scenarios/httpclient-4.3.x-scenario)
  client-only 形状的对照：只断言 exit span，没有第二个 entry。
- [test/plugin/scenarios/jetty-scenario](https://github.com/apache/skywalking-java/tree/main/test/plugin/scenarios/jetty-scenario)
  `type: jvm` 形状（fat-jar + `bin/startup.sh` + `${agent_opts}`），与 tomcat 形状对照。
- [`.github/workflows/plugins-jdk17-test.0.yaml`](https://github.com/apache/skywalking-java/tree/main/.github/workflows)
  CI 泳道。版本钉在泳道而非 `configuration.yml` 的证据。

## 一手来源 —— 本仓库的对照面

- [verify/README.md](../../../verify/README.md)
  **关键资产。** 本仓库手写的 `test/plugin` 简化版（bash + jq + Docker Compose），
  开篇即写明「参考 apache/skywalking-java 的 test/plugin 设计」。跟上游逐条对照，
  缺什么、为什么这么简化，是本课程最好的批判素材。
- [verify/run.sh](../../../verify/run.sh) / [verify/run-scenario.sh](../../../verify/run-scenario.sh)
  宿主侧与容器侧入口。可与上游 `run.sh` + `runner-helper` 直接对比。
- [verify/lib.sh](../../../verify/lib.sh)
  bash 版断言助手（`assert_eq` / `assert_ge` / `wait_ready`）——
  对照上游 `expectedData.yaml` 的声明式算子，看「命令式断言 vs 声明式预言」的差异。
- [verify/scenarios/logfile-reporter/checks.sh](../../../verify/scenarios/logfile-reporter/checks.sh)
  24 条 jq 断言的真实样本。同一批断言，用另一种范式写会是什么样。
- [docs/handoff/2026-09-29-verify-container-scenarios.md](../../../docs/handoff/2026-09-29-verify-container-scenarios.md)
  `verify/` 的交付记录，含绿跑证据。
- [docs/repo/known-todos.md](../../../docs/repo/known-todos.md)
  已登记的缺口（verify 覆盖待扩展）——课程结论的落点之一。

## 外部对照 —— 同一模式在别的生态叫什么

迁移到其它项目时要能对上号，所以这些是「可迁移性」的锚点。

- [Testcontainers for Java](https://java.testcontainers.org/) — 声明式启动依赖容器。
  对应关系：上游用预构建镜像 + `docker run`，Testcontainers 用代码描述容器生命周期。
- [testcontainers-go](https://golang.testcontainers.org/) — 同一概念的 Go 版。Go 侧迁移首选。
- [Google Testing Blog — Test Sizes](https://testing.googleblog.com/2010/12/test-sizes.html)
  「小/中/大」测试的分级依据与代价。这正是「选对阶梯」的理论出处。
- [Michael Feathers — Working Effectively with Legacy Code（集成测试章节）](https://www.manning.com/books/working-effectively-with-legacy-code)
  「不要 mock 你要测的那一层」这一原则的经典出处。
- [Approval Tests / ApprovalTests](https://approvaltests.com/) — 声明式预期数据的思想源头。
- [Jest — Snapshot Testing](https://jestjs.io/docs/snapshot-testing) — 同一模式的另一个实现。
- [Pact](https://docs.pact.io/) — 消费者驱动的契约测试，声明式期望的另一形态。
- [k6 — Thresholds](https://k6.io/docs/using-k6/thresholds/) — 断言「不变量」而非「等值」。
- [Martin Fowler — Eradicating Non-Determinism in Tests](https://martinfowler.com/articles/nonDeterminism.html)
  测试不稳定的根源分类。读它才能理解 3s 冷启动超时那类坑的本质。
- [YAML 官方 spec](https://yaml.org/spec/1.2.2/) — 需要确认宽松匹配语义时。

## Wisdom (Communities)

- [apache/skywalking-java Issues](https://github.com/apache/skywalking-java/issues)
  框架 bug vs 自己测试写错，多数已知问题已有人报过。搜 `test/plugin` / `expectedData` 关键词。
- [SkyWalking 官方社区（邮件列表 / Slack / CN Cfz）](https://skywalking.apache.org/community/)
  问「这个模式该怎么用」比问「这个 API 怎么调」更有效。
- 用户偏好已记录：**遇到问题先自己查日志和一手源码，不急于发帖**（沿用 OMO 课程时的偏好）。
  所以课程要教会「去哪查」，而不只是给答案。

## Gaps

- 上游 `test/plugin` **没有**面向外部使用者的中文说明；`Plugin-test.md` 也假定读者已经在
  Apache 仓库里。**本课程要补的正是这一层：讲清「为什么这么设计」而不只是「怎么填字段」。**
- 尚缺一份跨生态的**模式映射表**（声明式预期 ↔ golden file ↔ snapshot ↔ Pact ↔ k6 threshold）。
  计划作为本课程线的核心 reference 文档产出。
- 尚缺**本仓库 `verify/` 与上游 `test/plugin` 的逐项差异清单**（含「哪些差异是对的、哪些是欠债」）。
  计划在测试阶梯那几课里逐步累积，最后固化成 reference。
