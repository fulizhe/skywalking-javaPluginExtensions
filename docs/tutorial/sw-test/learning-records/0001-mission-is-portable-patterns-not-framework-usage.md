# 目标是「可移植模式」，不是「SkyWalking 框架用法」

用户在学 `apache/skywalking-java/test/` 时明确说明：这份材料**不仅用于 SkyWalking，还要用于其它
Java 项目、甚至 Go 项目，以及它所代表的软件开发概念**。所以 `test/` 是**案例研究**，不是终点。
每条设计决定都要能对上别的生态里的对应物（声明式预期 ↔ golden file / Jest snapshot / Pact /
k6 threshold），否则那部分等于没讲。

**Evidence**: 问「学它最终想做什么」时，用户选了「先通读摸清全貌」，并追加原话「我学习这个不仅
用于 skywalking，还用于其它 java，甚至 go 项目，以及其所代表的软件开发概念」。同时明确「要，必须
真跑通」——执行环节不接受只讲不做。

**Implications**:
1. 课程重心从「怎么填 `expectedData.yaml` 字段」移到「**为什么这么设计、代价是什么**」。字段级
   细节压缩成 reference 速查，正文留给设计取舍。
2. 每一课都要有一节「**这在别处叫什么**」，这是本课程区别于普通源码导读的地方。
3. 必须包含真跑通的环节。已验证本机 Docker 28.2.2 / Compose v2.37.1 / JDK 17 / Maven 3.8.9 可用。
4. 暂不主动改造本仓库 `verify/`。用户只说「先通读摸清全貌」，没说要落地。`verify/` 现在只当
   对照素材用。
5. 意外收获：本仓库 `verify/` 就是照上游 `test/plugin` 形状手写的简化版（bash + jq + Compose），
   而 `logfile-reporter-plugin` 同时有约 169 个 mock 单测。这个「单测很多 vs 场景才 24 条断言」
   的对比是讲透「场景即测试」这条信条的最佳靶子——比空谈上游设计更有说服力，因为反面例子在
   自己代码里。相关：[[MISSION.md]]
