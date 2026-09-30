# Mission: 精读 SkyWalking Java `test/` —— 抽出可移植的集成测试模式

## Why

起点是 [apache/skywalking-java 的 `test/` 目录](https://github.com/apache/skywalking-java/tree/main/test)：想搞懂它到底怎么组织的、为什么这么组织。

但**真正的目的不是学会用 SkyWalking 的测试框架**。用户明确说过：这门课要同时服务于**其它 Java 项目、甚至 Go 项目**，以及**它所代表的软件开发概念**。

所以 `test/` 是**案例研究（case study）**，不是终点。Apache 那 200+ 个 scenario 熬了多年，是一份被真实失败打磨过的公开语料——里面的每一个决定（为什么用声明式 YAML 当断言、为什么场景可以替代 mock、为什么版本钉在 CI 而不是配置里）背后都是别人踩过的坑。把这些决定**抽象成模式**，比学会某一个框架的 API 值钱得多。

顺带一个天然优势：本仓库的 `verify/` 目录就是作者**照着 `test/plugin` 形状手写的一套**（bash + jq + Docker Compose，`verify/README.md` 里写明了这个来历）。所以有现成的「原版 vs 简化版」对照，不用凭空想象。

## Success looks like

学完之后，用户能做到：

- **说清 `test/` 的两半各是什么**：`test/plugin`（单插件场景测试，mock collector 当真相）vs `test/e2e`（全栈 docker-compose + 真 OAP + GraphQL 查询断言），以及为什么插件作者该用前者
- **拿到任意一个 scenario，在 5 分钟内说出它「声明了什么」和「断言了什么」**——`configuration.yml` 声明怎么跑，`expectedData.yaml` 声明必须产生什么，两者严格分离
- **解释「场景即测试」这条信条**：为什么对插桩类代码，mock 单元测试在结构上就不可能证明关键风险（cast 逻辑、版本兼容），而跑真框架的场景一次就证明两件
- **识别「假通过」的测试**：知道只断言 entry span 而不断言 `refs:` 块，等于没测传播；知道 `segmentSize` 为什么要用 `ge` 而不是 `eq`
- **把模式迁移到自己的项目**：在自己项目里选对测试阶梯（单测 / 场景 / 全栈 e2e），并用声明式预期数据而不是散落的 `assertEquals` 当断言
- **知道每个概念在别的生态里叫什么**：YAML 声明式预期 ↔ Go 的 golden file / Testcontainers / Pact / approval test / k6 threshold，能自己画出映射

## Constraints

- 中文母语，偏好中文讲解；代码、字段名、文件路径用英文原样
- Windows 宿主（pwsh 7）+ Docker Desktop 28.x 已就绪，**课程必须包含真跑通的环节**
- **先通读摸清全貌**，再落地。用户尚未表达「要把 verify/ 改成上游形状」的意图——不要预设
- 每课一个单一战果，5–10 分钟内能完成；工作记忆很小，一次不要塞太多
- 每个可迁移的概念都要给出**别的生态的对应物**，否则等于没学到可迁移的部分
- 引用一切事实到一手来源（GitHub permalink / 官方文档），不用参数化知识

## Out of scope

- 不改本仓库的 `verify/`、不重写插件测试（那是后面的事，本课程只负责让人有判断力）
- 不逐个读 200+ 个 scenario，只精读 2–3 个代表性样本
- 不深入 SkyWalking OAP 内部的存储实现
- 不教 TestNG / Docker / Maven 本身怎么用（假定会）
- 不追 `test/e2e` 的 GraphQL 查询细节（够用来做阶梯选择判断即可）
