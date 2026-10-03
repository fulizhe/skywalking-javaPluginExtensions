# 版本线约定（version lines）

`master`（=2.0.0 线）与 `release/1.0.0`（=1.0.0 线）并行维护的机制。**两条线的定位见 `AGENTS.md` 的「版本线」一节**，本页只讲机制。

- **tag 语义**：`1.0.0` 是原发布点，**永不移动**；冻结线**每轮维护必须追加 `1.0.0-maintN`（N 递增）**
  —— 往冻结线提交却不打 tag，等于这轮维护没发布，下次没人知道线上是哪个版本。
- **发布机制：每个 tag 对应一个 Release**（两条线同一套）——tag 是发布点，Release 由 CI 在该 tag 上
  构建插件 jar 并挂成资产。执行细节在 `.github/workflows/package.yml`。
  - **前提**：workflow 文件从**被推送的那个 ref 的树里**读，不是从默认分支。打 tag 之前，那条线的 tip
    必须已经带着 `package.yml`；否则 tag 推上去 CI 一片安静 —— GitHub 连 run 都不建，不是建了 run 失败。
    冻结线已于 `09d24d3` 补上（`1.0.0-maint2` 打 tag 时还没有，故那一发只能补跑）。
  - **已打过 tag 的发布点补不了树**（tag 不移）：改用 `workflow_dispatch` 补发，`分支=master`、
    `tag=<目标 tag>`；分支必须选默认分支，因为 dispatch 只认默认分支上那份 workflow。
  - **资产**：三个活跃插件的 jar + `SHA256SUMS.txt`。`collect` 排除 `original-*.jar` ——
    shade（`shadedArtifactAttached=false`）会把打包前那份改名留下，它没做 H2 重定位，发出去装不上。
  - **jar 名不是版本信号，tag 才是**：同一个模块两条线上的 jar 名不同（`logfile-reporter-plugin`
    在 1.0.0 线是 `-1.0.0.jar`，master 上是 `-2.0.0.jar`）。两个 override 插件的文件名走 `finalName`
    （含 `${skywalking.version}`），与 parent pom 版本无关。
  - workflow 文件本身的校验与踩坑（schema / 上下文可用性 / 无 checkout 时 `gh` 需要 `GH_REPO`）
    见 `docs/notes/2026-10-01-github-actions-ci-pitfalls.md` §五。
- **2.0.0 线（`master`）用同一套机制**：`2.0.0` 为首个发布点，之后每次更新追加 `2.0.0-maintN`。
  2.0.0 是之后主要维护的版本；1.0.0 线只接下面「改动范围」与「例外」允许的东西。
- **一轮维护的形状：先提交，后打 tag。** tag 是「这次发布哪个提交」的唯一声明，且**永不移动** ——
  所以它必须打在分支 tip 上（落在本轮最后一个 commit 上）；tag 之后分支可以继续往前，tag 不动。

  ```text
  release/1.0.0 分支                    tag / Release
      ├─ commit A  可观测性读口
      ├─ commit B  修 bug
      └─ commit C  文档
             │
             └─ tag 1.0.0-maint3   ← 必须落在 C 上（分支 tip）
                    │
                    └─ push tag → Actions 出 run → Release 自动建（构建 C 的代码）
  ```

  这一轮结束。分支继续往前；下一轮 commit D 之后打 `1.0.0-maint4`。
- **「最新」有两个含义，别混**：**用户装哪个**看最新的 `1.0.0-maintN` tag / Release；
  **`release/1.0.0` 分支 tip 是最新的代码**，可能已经领先最后一个 Release（下一轮还没打 tag 的活）。
  分支领先最后一个 tag 是正常的，不是漏发。
- **两条线对照**：

  | | `release/1.0.0` | `master`（2.0.0 线） |
  | --- | --- | --- |
  | 首个发布点 | `1.0.0`（2026-09-25） | `2.0.0`（尚未打） |
  | 之后每轮 | `1.0.0-maintN` | `2.0.0-maintN` |
  | 能接什么 | 只接 bugfix + 可观测性读口（见下面「改动范围」与「例外」两条） | 全部功能 |
  | 频率 | 低（偶尔维护） | 高（主要维护的版本） |

- **同步方式**：两线已分叉（类结构不同），**bugfix 用 cherry-pick，不 merge**。
- **版本号**：pom 里的版本是内部坐标，**不随 tag 变**（保持下游流程不变）；构建靠 tag + jar 内
  `pom.properties` 区分。实测现状（2026-10-03）：parent `agent/pom.xml` 两条线都是 `1.0.0`；
  `logfile-reporter-plugin` 在 master 是 `2.0.0`、在 1.0.0 线是 `1.0.0`。**这一处不一致不影响发布形态**
  （见上面「jar 名不是版本信号」），但看 pom 时别当版本依据。
- **改动范围**：冻结线只接 bugfix / 必要诊断小改，**新增功能一律进 2.0.0 开发线（`master`）**。
- **例外（可观测性读口）**：为让冻结线具备"能不能自证健康"的排查能力，允许**只加可观测性接口**。
  判据四条全中才可：① **不动现有代码**——既有文件一行不改（拦截器独立成 `PluginDefine`、
  不往既有拦截点数组追加；对外读口独立开一个方法，不改既有方法的返回形状）；
  ② **不改既有契约**——不往已被依赖的返回结构里塞新键；③ **纯增量、可完全回退**——
  注销 `plugin.def` 一行即失效；④ **不带 UI**。已记录的先例：
  `1.0.0-maint2` 的 `hotLayerStat()`（同步自 master 的 `hotLayerSnapshot` / self-stat 面板 ⑦，
  2026-10-03）；UI 与宿主工具类桩留在 master（冻结线无 demo-app，桩类本就由应用侧提供）。
- **提交冻结线的清单**（每次维护逐条核对）：既有代码零改动 · 新增可观测性读口已接上
  拦截器与 `plugin.def` · `mvn package` + `mvn test` 通过 · **已打 `1.0.0-maintN` tag 且对应 Release 已建**
  （tag 推完去 Actions 看有没有 run —— **没有 run 就没 Release**，tag 本身不算发布完成）·
  tag message 写明本轮内容与回退方式。
- **分支现状**：`javaSWExtend` 为未清理旧分支，**不要动**；本地 `release/1.0.0` 跟踪 `origin/release/1.0.0`，
  打 tag 与推冻结线都在它上面做。
