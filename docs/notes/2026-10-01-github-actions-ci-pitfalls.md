# GitHub Actions：缓存、矩阵、镜像分发与排错（本轮实跑经验）

- **何时读**：改 `.github/workflows/*.yml`、CI 跑得慢或莫名变红、看到看不懂的报错想在动手前先判断时。
- **性质**：**经验沉淀，不是规范**。§一~§四 与 §六 来自 2026-10-01 那一轮（10 个提交、4 次红 6 次绿），§五 来自 2026-10-03 加 `package.yml` 那一轮，数字均为当次实测。
  规范性的约定在 `docs/repo/` 与 `AGENTS.md`。
- **读者假设**：知道 CI 大概是什么，但没系统碰过 Actions。

---

## 零、先建立心智模型

GitHub Actions 和"自己机器上跑 CI 脚本"的根本差别只有一句：

> **runner 是一次性 VM**。每个 job 拿一台全新的、没有任何 state 的机器，跑完销毁。

这一句推导出后面所有的坑：缓存不会自己留着、镜像每次都要重拉、依赖每次都要重下。**所有优化都只是在对抗这一条。**

本仓 `verify.yml` 现在的形状（一次 push 触发的全貌）：

```mermaid
flowchart TD
    P[push 到 master] --> PL["plan（4~6s）<br/>读场景声明 → 输出 matrix JSON"]
    P --> BR["build-runner（23~39s）<br/>buildx + gha 层缓存 → 推 GHCR"]

    PL --> V
    BR --> V

    V["verify × 5（并行，fail-fast:false）<br/>logfile-reporter / httpclient × 2 / hutool × 2"]
    V --> A["每 job：拉 m2 缓存 → docker pull 运行器镜像<br/>→ verify/run.sh --scenario X --version Y"]
    A --> R["墙钟 = 最慢那个 job"]

    PL -.->|产出 matrix| C1["actions/cache key =<br/>m2-os-hash(settings.xml + agent/**/pom.xml)"]
    BR -.->|层缓存 scope=verify-runner| C2["gha cache"]
```

**为什么是这个形状**：`plan` 与 `build-runner` 无依赖、并行跑；5 个 verify job 各等两者都完成后一起铺开。
墙钟 ≈ 23s（build）+ 最慢 job ≈ **141s**。

---

## 一、缓存：能省"下载"，省不了"计算"

| 手段 | 缓存什么 | key 随什么变 | 落点 |
| --- | --- | --- | --- |
| `actions/cache` | 你指定的任意路径（本仓是 `.m2-verify`） | 你自己写 `key`，本仓 hash 了 `settings.xml` + `agent/**/pom.xml` | `restore-keys` 前缀兜底 |
| `setup-java` 的 `cache: maven` | `~/.m2/repository` | 自动 hash `**/pom.xml` | 自动 |
| buildx 的 `type=gha` | Docker 层（`mode=max` 连中间 stage 一起） | 层内容本身（命中即 `CACHED`） | `scope=` 命名 |

**三条必须知道的规则**（都不是猜的，是文档写明 / 实测确认）：

1. **7 天未访问即驱逐，仓库总量上限 10GB**。所以缓存 key 设计成"随 pom 变 + 前缀兜底"，
   而不是"永久固定 key"——固定 key 命中率高，但内容永远不更新。
2. **fork PR 的 token 是只读的**：保存失败**只 warning，不 fail job**。放心让外部 PR 跑。
3. **`type=gha` 的 cache-to 导出失败默认致命**，要显式 `ignore-error=true`。
   但注意下一节——**它挡不住能力缺失**。

> **第一次跑必然还是慢**，因为缓存要在 job 的 post 步骤才存。这不是没生效，是一次性成本。
> `build-and-test` 加 `cache: maven` 后首跑 48s，落在它历史区间 35~49s 内——单次样本分辨不出收益，
> 这类结论只能等第二次跑。

---

## 二、Docker 镜像：三条路，各自的代价

| 方案 | 上传 | 下游 job 拉取 | 镜像没变时 |
| --- | --- | --- | --- |
| A 各 job 自己 build | 0 | 从 gha cache 导层 + `load` 进 docker image store | 导层仍要走一遍 |
| B build 一次 → artifact 分发 | ~1GB | 3 份各 ~1GB（gzip 来回一趟） | 照传 |
| C build 一次 → 推 registry（GHCR） | 内容寻址 | `docker pull` 走 registry 压缩层 | **只传 manifest（KB 级）** |

**本仓选了 C**，因为 registry 是内容寻址的：镜像没变时 blob 已存在，重推只 PUT 一个 manifest。
实测首次推约 1GB 用了 24s，之后每次只几秒。

### 三个坑（每一个都让 run 在十几秒内失败，且失败形态很有误导性）

**1. 默认 driver 不支持 gha 缓存导出**

```
buildx failed with: failed to build: Cache export is not supported for the docker driver.
```

`docker/build-push-action` 用 runner 上默认的 builder，而默认是 `docker` driver。
**先装 `docker/setup-buildx-action`**（它建 `docker-container` driver 的 builder）才能用 gha 缓存。

> **关键教训**：`ignore-error=true` 只覆盖"导出超时"，**不覆盖"能力缺失"**。
> 这类报错是 build 期直接失败，`ignore-error` 根本没参与。

**2. `build-push-action` 不认证 registry**

```
failed to fetch anonymous token: ... https://ghcr.io/token?... 403 Forbidden
```

看它的 `action.yml`，`github-token` 的描述是 *"used to authenticate against a repository for
**Git context**"* —— 只管 **Git 源**认证，不管 registry 推送。
**推 registry 前必须显式加一步 `docker/login-action`。**

**3. GHCR 镜像名必须全小写**

`github.repository` 带大写字母（如 `skywalking-javaPluginExtensions`）会直接被 registry 拒。
workflow 里有一道 `tr '[:upper:]' '[:lower:]'`，顺带把结果写进 `$GITHUB_ENV` 给后续步骤复用。

---

## 三、matrix：并行度 ≠ 更快

**坑：matrix 必须是映射，不能是裸数组。**

```
Error when evaluating 'strategy' for job 'verify'. (Line: 139, Col: 15): A sequence was not expected
```

`matrix: [ {...}, {...} ]` 不行，必须 `matrix: { "include": [ {...} ] }`。
**这个错误的迷惑性在于**：`plan` 和 `build-runner` 都是绿的，只有 `verify` 一个 job 都没创建——
矩阵是在 job 实例化**之前**展开的，所以看起来像"第三个 job 卡住了"，实际是它压根没资格被创建。

**坑：加了 `needs` 就有串行化。**

方案 C 每个 job 省下 32~39s（pull 比导层+load 快），但 `needs: build-runner` 让构建串在最前面，
吃掉大部分收益：

| | 方案 A（各 job 自建） | 方案 C（build 一次 + GHCR） |
| --- | --- | --- |
| 单个 job | 96~144s | **53~113s** |
| build job | — | 23~39s |
| **墙钟** | **146s** | **141s** |

净省 5s。**不是 bug，是"省下的时间"和"新增的串行点"量级相当。**
要真正吃到那 30s 得再拆一个 `prepare` job 让缓存恢复与构建并行——不值得。

**动态矩阵**（本仓做法，避免 CI 抄一遍场景清单）：

```mermaid
flowchart LR
    A["verify/scenarios/*/support-version.list<br/>（场景自己声明支持哪些版本）"] --> B["plan job"]
    B -->|"jq 生成 {include:[{scenario,version}]}"| C["outputs.matrix"]
    C -->|"fromJSON(needs.plan.outputs.matrix)"| D["5 个 verify job"]
```

加版本只改 `.list` 一个文件，CI 自动跟随。

**两条卫生习惯**：

- matrix 值走 `env:` 传进 `run:`，**不内插进脚本文本**（`--scenario "$VERIFY_SCENARIO"`）。
- `fail-fast: false`：一个场景挂了不该掐掉其余——全跑完才知道是"只坏一个"还是"全坏"。

---

## 四、`paths` 白名单语义（本轮最大的坑）

```yaml
on:
  push:
    paths: ['verify/**', 'agent/...', 'demo-app/**']   # ← 白名单，不是黑名单
```

**语义是"只有这些路径变了才跑"**，不是"这些路径变化时不跑"。

后果：**改 workflow 文件本身不会触发它自己**。本轮 `1b0c09e` 只改了 `verify.yml`，
push 之后 GitHub 上**根本没有 run**——当时误以为是 Gitee→GitHub 镜像延迟，
实际是过滤器直接判定"这次不该跑"。

修法（两个 workflow 都有）：

```yaml
      # 带自身路径:否则改 workflow 永远不触发它自己,验证逻辑改动得不到验证
      - '.github/workflows/verify.yml'
```

**顺带解掉一个鸡生蛋问题**：过滤器按**推送后的 ref** 求值，所以"把自身路径加进 `paths`"
这个 commit 自己就会触发——不需要另找办法跑一次。

> 顺带还有个容易忽略的：**过滤器按 diff 判定**。如果一次 push 里没有任何一个文件命中 `paths`，
> 就一次都不跑。所以"改 A 顺便改了 B"时要看的是**整次 push 的文件集合**，不是单个 commit。

---

## 五、workflow 文件的契约：GitHub 的 schema，不是 YAML 语法

2026-10-03 加 `package.yml` 那一轮，本地过了三道校验（YAML 能解析、5 个 `run` 块过 `bash -n`、collect 脚本在假目录里跑通），推上去仍然**炸了两次**（一次 run 秒红且 job 数 = 0，一次卡在最后一步），另有两处**不报错但结果是错的**。**四次都不是 YAML 语法问题** —— GitHub 对 workflow 文件另有一套契约，本地那三道校验一道都不覆盖。

下表按"炸"排在前面两行、"静默"排在后面两行 —— **静默那两条更贵**：红的时候你知道要查，job 名和资产列表没人看。

| 症状 | 真正的契约 | 本地为什么看不见 |
| --- | --- | --- |
| run 立刻红，且 **job 数 = 0** | `jobs.*.name` 不接受 `env` 上下文（只允许 `github`/`inputs`/`needs`/`strategy`/`matrix`/`vars`） | 这是 schema 约束，YAML 解析器照单全收 |
| job 名原样显示 `attach to release ${{ (inputs.tag \|\| …` | `jobs.*.name` **不插值**；只有 `run-name` 插值 | actionlint 按上下文可用性表放行了，文档没写会不会插值 |
| 前 4 步绿、最后 `gh release create` 挂 | gh 依次靠 `--repo` / `GH_REPO` / git remote 推断目标仓库 | 那个 job 故意不 checkout，workspace 里不是 git 仓库；而本地永远在 git 仓库里 |
| Release 多挂一个 `original-*.jar`（479KB） | shade（`shadedArtifactAttached=false`）把打包前那份改名留在 `target/` | 假目录里没这个文件；本地那次只看了 maven 日志，没 `ls target/` |

三道本地校验各管一段契约，互不替代：`bash -n` 对齐 **bash 的契约**（抓 `run` 块语法错），actionlint 对齐 **GitHub 的契约**（抓 schema 与上下文可用性），collect 脚本对齐 **文件系统的事实**（抓选错文件）。上表第 1 行只有 actionlint 抓得到，第 4 行只有拿真实 `target/` 跑才抓得到。

```bash
# 本地入口：三个 workflow 一起过一遍，exit 0 才推
actionlint .github/workflows/build-and-test.yml .github/workflows/verify.yml .github/workflows/package.yml
```

> **失败步骤与通过步骤的差别往往就是根因。** 第 3 行里 `gh api "repos/$GITHUB_REPOSITORY/…"`（路径写死，不需要推断）过了，`gh release create "$TAG"`（靠推断）挂了 —— 对照这两行不必猜。而拿不到日志时（见 §六 的环境限制）这条路尤其管用：同一个 job 里"哪几步绿、哪一步挂"本身就是判据。

**还有一条与触发面直接相关的约束：workflow 文件是从被推送的那个 ref 的树里读的**，不是从默认分支。tag 指向的提交里没有那个 workflow，就没有任何东西会跑 —— GitHub 连 run 都不建（不是建了 run 然后失败）。

> 实证：`1.0.0-maint2` 指向 `955fa4e`（1.0.0 冻结线、早于 `package.yml` 的一天），那棵树里只有 `.github/workflows/build-and-push-jar.yml`，没有 `package.yml` → tag 推上去后 CI 一片安静，Actions 页上连一条记录都没有。同一天推的探针 tag 指向含 `package.yml` 的 master 提交，正常触发。
>
> **推论**：两条版本线分叉后（`docs/repo/version-lines.md`），冻结线的提交天生不含后来加的 workflow。两条出路 —— 把 workflow cherry-pick 到那条线（tag 面就原生可用了），或从默认分支用 `workflow_dispatch` + `tag` 输入发（本仓 `package.yml` 已支持，见其输入说明）。

---

## 六、怎么读一次失败的 run

按这个顺序，能少走弯路：

| 顺序 | 看什么 | 判读 |
| --- | --- | --- |
| 1 | **哪些 job 存在** | "少了一个 job" ≠ 卡住，常见于：矩阵展开失败、`if` 把 job 跳过了、**文件没过校验（job 数 = 0，见 §五）** |
| 2 | **Annotations**（run 页和 job 页都有） | 那是真正的失败原因；**报错常挂在 workflow 级而不是出错的 job 上** |
| 3 | 步骤级耗时 | 几十秒就失败 = 配置/权限/语法问题；几分钟才失败 = 真在构建或跑测试 |
| 4 | 具体日志行 | 报错原文进代码块，别转述 |

**两个会打断排查的环境限制**（都实测踩过）：

- **匿名 GitHub API 只有 60 次/小时**，轮询几次就 403。排查期间改用网页看，
  或 `GET /rate_limit` 看 `reset` 时间。
- **登出状态下日志看不到**，只能看 annotations 摘要。完整日志要么登录，要么本地复现。

**本地复现的技巧**（比在 CI 上试快得多）：让脚本在**真正需要外部依赖的那一步之前**停下来。
比如验证参数校验逻辑时把 docker 指向一个死地址：

```bash
DOCKER_HOST=tcp://127.0.0.1:1 VERIFY_SKIP_BUILD=1 bash verify/run.sh --scenario X --version Y
# 参数校验路径全测得到，且秒级返回
```

在容器里跑 CI 脚本也能对齐环境（runner 上有 `bash` + `jq`，本机 WSL 常常缺 `jq`）：

```bash
docker run --rm -v "D:\repo:/src" -w /src alpine:latest \
  sh -c "apk add --no-cache jq bash >/dev/null && bash /t/script.sh"
```

---

## 七、版本与迁移：怎么判断 major 能不能升

Actions 的 major 版本跳动大多是 runtime 与依赖升级，**但仍要查再升**——本轮为消两条弃用告警升了四个：

| action | 原 | 现 | 判定依据 |
| --- | --- | --- | --- |
| `actions/checkout` | v4 | v7 | Node 20 → 24 运行时（runner 强制按 Node 24 跑） |
| `actions/cache` | v4 | v6 | 同上 |
| `actions/setup-java` | v4 | v6 | v5 的 breaking 只有 Node 24；v6 的 release notes 明写 *"V6 ESM migration is not a user-facing breaking change"* |
| runner 镜像 | `ubuntu-latest` | `ubuntu-24.04` | `ubuntu-latest` 将于 2026-10-19 迁 Ubuntu 26；**钉同 major 不吃周更镜像的亏**，且避开无人值守的发行版大版本改动 |

```bash
# 看某个 action 最近发了什么 major / 有没有 breaking
curl -s https://api.github.com/repos/<owner>/<action>/releases/tags/v<N>.0.0 \
  | python -c "import json,sys; print(json.load(sys.stdin)['body'])"
```

**弃用告警本身值得处理**——它意味着 runner 已经在替你跑新 runtime，等哪天彻底停止支持就是硬失败。

---

## 八、本仓现状（指针，不重复数字）

| 想知道 | 看哪 |
| --- | --- |
| verify 回路怎么跑、场景怎么声明 | `verify/README.md` |
| 场景与矩阵 | `verify/scenarios/*/scenario.conf` + `support-version.list` |
| 三个 workflow 的完整形态 | `.github/workflows/verify.yml` / `build-and-test.yml` / `package.yml` |
| 改 workflow 之前的本地校验 | `actionlint .github/workflows/*.yml`（§五） |
| 编排约定（脚本入口、端口） | `AGENTS.md` 的 Task routing / 验证节奏 |

---

## 九、这一轮最该记住的一条

三次失败（`ignore-error` 挡不住 driver、`build-push-action` 不认证 registry、matrix 不收裸数组）
有一个共同点：

> **都是"看起来对"，不是"跑一遍对"。**

具体说：

| 我验证了什么 | 我没验证什么 | 于是 |
| --- | --- | --- |
| jq 管道输出了正确的 JSON **内容** | GitHub 要求的**形状**（映射而非数组） | run #20 矩阵展开失败 |
| `docker compose config` 能解析配置文件 | 那个 YAML 编辑器为什么报错 | 一条误报被当成真问题排查了半天 |
| `docker buildx build --push` 的参数 | build-push-action **自己**会不会认证 registry | run #18 403 |

**教训不是"多小心"，是"验证的边界要对齐被验证对象的契约"。**
10-01 那轮三次都能在推上去之前抓到——只要验证时用的是**目标系统同款工具 + 同款输入形状**，
而不是"我本地跑通了"。第 3 条尤其：**本地 Bash / PowerShell 通过 ≠ Linux runner 上通过**
（`jq` 在本机 WSL 里就没有）。

但 10-03 那轮给了这条一个上限：**"提前抓到"的前提是本地有那个同款工具。**
§五 第 1 行（schema / 上下文可用性）在装上 actionlint 之前没有任何本地手段能抓 ——
YAML 解析器、`bash -n`、跑一遍脚本，全都放行。所以新 workflow 的第一个动作不是"再检查一遍"，
是**先确认校验手段本身到位**。

## 十、还没验的

- **`cache: maven` 的真实收益**：`build-and-test` 首跑 48s 落在历史区间内，需第二次命中后才可比。
- **GHCR 在缓存失效时的表现**：层缓存与 GHCR 拉取同时冷启动时，总耗时没测过。
- **`actions/cache` 的 10GB 上限是否够用**：当前 m2 约 1GB + 层缓存若干，估算够，但没量过层缓存实际占用。
- **fork PR 的端到端行为**：只确认了"保存失败降级成 warning"（文档保证），没真拉一个 fork PR 试过。
- **`package.yml` 只验了 `push: tags` 一条发布面**：`release: published` 与 `workflow_dispatch`（尤其"UI 用已有 tag 建 Release"会不会触发）都没跑过。
- **`jobs.*.name` 的插值行为只在一处实测**：actionlint 放行、GitHub 原样显示。其它 job 名未逐一确认，但按"静态文本总是安全"处理。
- **`--clobber` 不删旧资产**：同一个 tag 重跑且这轮少产出一个资产时，旧资产会留在 Release 上（实测留下一个 `original-*.jar`）。是否在 `attach` 里加"清理不在本轮清单里的资产"未定。
- **Gitee → GitHub 镜像延迟**：分支 0~60s、tag 一次约 4 分钟，各 1 个样本。排查"没触发"时先排掉它（§四 同类误判）。镜像**会**传播 tag 删除。
- **`1.0.0` 冻结线上的 `build-and-push-jar.yml` 是死代码**：分支名写成 `main`（本仓是 `master`）、没有 `tags` 触发、`-DskipTests`、用 JDK 8（与 ADR-01 的 JDK 17 工具链冲突，`<release>8` 需要 javac 9+）、`[ ! -f ".../target/*.jar" ]` 里glob 没展开所以断言恒假、`./mvnw` 大概率不存在。名字里的"Push to Artifact Repo"也没有对应步骤。待定：删掉，还是改成能用的。