# 冻结线也回移 dynamic-debug-runtime-9.x-plugin：两线资产同为四个

> **状态**：accepted（2026-10-13）。**修订 ADR-06** —— 推翻其决策 #3（"发布资产按线而异：master 四个 / 冻结线三个，不得把四个当共同口径"）与 Considered Options 里"回移到冻结线一起发：拒绝"一条。ADR-06 的其余决策（激活模块、以 `module-status.md` 为准、回移仅限代码、enable 不计入活跃数）继续有效。

## 背景

1. ADR-06 决策 #3 把"发布资产按线而异"立为口径，理由是"`dynamic-debug-runtime-9.x-plugin` 属新增模块、冻结线只收 bugfix"，并在 Considered Options 里明确否决了"回移到冻结线一起发（冻结线也四个资产）"。
2. 该前提**不成立**：`dynamic-debug-runtime` 在冻结线上**本来就在 reactor 里**（`agent/pom.xml` 的 `<module>dynamic-debug-runtime-8.x-plugin</module>` 一直在），只是那条线上是同一个坏模块（`v8`、死代码、缺插件整段 `throw`、`activeSpan` NPE），且从未进本线 CI 的 `paths` / `MODULES`，因此既不跑测试门禁、也不出现在发布资产里。
3. 所以本轮 ② 不是"给冻结线新增一个模块"，而是**修好本线既有的坏模块**——属冻结线允许的 bugfix 范畴。执行结果：cherry-pick `acf341f`（复活并改名 9.x + 拆分/容错/NPE/去死代码/单测）与 `76acb4e`（补 Dubbo / Tomcat10.x / SpringMVC HTTP 头开关）到 `release/1.0.0`；再把**本线自己的** `build-and-test.yml` / `package.yml` 的 `paths` / `-pl` / `MODULES` 由三个扩为四个（`0d9f3a8`）；打 `1.0.0-maint3`。

## 决策

1. **冻结线也回移该模块**。活跃模块集合在两条线上**同为四个**：`logfile-reporter-plugin`、`dynamic-debug-runtime-9.x-plugin`、`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`。ADR-06 决策 #3 与"冻结线也四个"那条 rejected option **作废**。
2. **ADR-06 决策 #4"回移仅限代码"仍然成立，且本轮遵守**：CI 的两个 workflow 文件名在两线各自持有，未 cherry-pick（四模块的改动落在**本线自己的** workflow 上）；`verify/**`、`demo-app` 仍未回移（本线无这些目录）。
3. **口径改由 `docs/repo/version-lines.md` 与 `docs/repo/module-status.md` 承担**，ADR-06 不再作为"资产数按线而异"的依据。

## Considered Options

- **维持"按线而异"（master 四 / 冻结线三）**：拒绝。前提（冻结线没有该模块）不成立；两线各带一个不同的资产数，只会持续制造"冻结线为什么少一个 jar"的疑问，而答案只是历史遗留。
- **就地改 ADR-06**：拒绝。`docs/adr/index.md` 规则 #2 要求**不改旧 ADR**，决策变化时新写 ADR 并引用旧的。
- **把 CI 的两个 workflow 文件 cherry-pick 到冻结线**：拒绝。两线的 `build-and-test.yml` 分支过滤器不同（`branches: master` vs `release/1.0.0`），cherry-pick 只会冲突；按 DRY 的代价换来两个不可合并的文件，不如各自维护（本线文件已存在，只需改数字）。

## Consequences

- **发布形态**：冻结线的 `package` 产出由三个 jar 变四个；`1.0.0-maint3` 起，两线的 Release 资产数量一致。
- **文档**：`version-lines.md` 的「资产」由"按线而异（四 / 三）"改为"两条线同为四个"（commit `831a911`）。
- **冻结线 reactor**：`agent/pom.xml` 的 `<modules>` 随 cherry-pick 一并摘掉已冻结的 `dynamic-enable-runtime-8.x-plugin`，与 master 一致（该模块目录保留、仅移出 reactor，与 ADR-06 决策 #5 一致）。
- **仍未决（沿革）**：冻结线 `.gitee/workflows/` 未补 `build-and-test.yml` 镜像；冻结线 `package.yml` 的 `on.push.branches` 仍写 `master`（本线分支推送不触发干跑，只有 tag / release 触发）。两者都未动。

## 参考

- `docs/adr/adr-06-activate-dynamic-debug-runtime.md` —— 被本轮修订的原始决策
- `docs/repo/version-lines.md` —— 资产数与"回移走 cherry-pick"的现行口径
- `docs/repo/module-status.md` —— active / frozen 现状（唯一准绳）
