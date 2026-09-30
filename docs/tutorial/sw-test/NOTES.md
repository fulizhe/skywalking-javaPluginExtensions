# NOTES · 教学偏好与工作笔记

## 用户偏好（2026-09-30 确认）

- **中文讲解**，命令、代码、字段名、文件路径保持英文原样。
- **必须真跑通**。选课时明确说「要，必须真跑通」。Docker Desktop 28.2.2 + Compose v2.37.1
  已就绪；宿主 JDK 17、Maven 3.8.9。所以涉及执行的环节要真做，不能只讲。
- **学习目标超出 SkyWalking 本身**。原话：「我学习这个不仅用于 skywalking，还用于其它 java，
  甚至 go 项目，以及其所代表的软件开发概念」。
  → 每讲到一个可迁移的决定，都要给出别的生态里的对应物，否则那部分等于没讲。
- **先通读摸清全貌**，暂不落地改造。→ 前几课以读懂结构与设计取舍为主，不急着动手改 `verify/`。
- **工作记忆要小**。沿用 OMO 课程时的反馈：一次一小块，讲完就能上手。
- 遇到问题倾向**自己查一手源码和日志**，不爱发帖 → 课程要教「去哪查」。

## 环境事实（已验证，别重新探测）

- Docker 28.2.2，Docker Compose v2.37.1-desktop.1 —— 可用。
- 宿主 JDK 17.0.2，Maven 3.8.9。
- **注意**：上游 `test/plugin` 的 `run.sh` / `generator.sh` 是 bash 脚本。
  本机 pwsh 环境跑它们要走 WSL 的 bash（见 AGENTS.md Execution environment）。
  上游 agent 本身要求 JDK 8 工具链构建，本机是 JDK 17 → 这是执行课要处理的现实障碍，
  别假设 `./mvnw -Pagent` 能一把过。

## 本仓库的对照面（教学素材，非常重要）

`verify/` 是作者照着上游 `test/plugin` 形状手写的简化版（bash + jq + Docker Compose，
`verify/README.md` 开篇写明来历）。这给了课程一个难得的「原版 vs 简化版」对照：

| 维度 | 上游 `test/plugin` | 本仓库 `verify/` |
|---|---|---|
| 断言范式 | 声明式 `expectedData.yaml` + 通用匹配引擎 | 命令式 bash `assert_eq` / jq 过滤 |
| 真相来源 | 容器内 mock collector（19876）抓真实上报数据 | 应用自暴露的 HTTP JSON 接口 |
| 目标应用 | 每个 scenario 自带 fat-jar / WAR | 共享一个 `agent/demo-app` |
| 版本矩阵 | `support-version.list` × 每 minor 最新补丁 | `support-version.list` 简单循环 |
| 单元测试 | **明确劝退**（场景即测试） | logfile-reporter-plugin 有约 169 个单测 |

最后一行是本课程最值得开刀的地方：169 个 mock 单测 + 一个场景，哪一个真正证明了
「agent 增强后 interceptor 拿到了正确的 framework 对象」？答案会逼出「场景即测试」这条信条。

## 课程编排草图（可随进展调整）

- **L1 两个测试台，一条阶梯** —— 全貌地图 + 「场景即测试」信条 + 精读一个真实 scenario
- L2 声明式预言 —— `expectedData.yaml` schema 与宽松匹配语义（最可迁移的一课）
- L3 **真跑通** —— 本机跑起一个 scenario；然后故意弄坏插件，看它变红（变异检验）
- L4 版本矩阵 —— `support-version.list` 与「每个 minor 取最新补丁」的理由
- L5 选对阶梯 —— `test/plugin` vs `test/e2e`；单测/场景/全栈的决策表
- L6 抽象成模式 —— 蒸馏出可移植原则，产出跨生态映射 reference
- L7 落地到自己的项目 —— 按本仓库 `verify/` 的欠债清单实操

## 待办 / 悬念

- L3 真跑之前要先确认：WSL bash 可用性、构建上游 agent 的可行路径（可能很重），
  以及**降级方案**——用 `verify/run.sh` 跑本仓库场景来演示「声明式断言 + 变异检验」，
  绕开上游构建成本。等 L3 之前必须先解决，别拖到上课才发现。
- 用户尚未表达要改造 `verify/`。若 L6/L7 结束时用户提出，要先确认再动，不要主动改。
