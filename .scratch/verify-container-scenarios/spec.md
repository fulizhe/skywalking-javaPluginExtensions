# 容器化场景验证回路（Linux/Docker + bash 为主，pwsh 退居二线）

Status: ready-for-agent

## Problem Statement

现有验证回路 `agent/demo-app/scripts/*.ps1` 与 Windows 强绑定：`D:\apps` 路径约定、`curl.exe --noproxy "*"`、`Start-Process`/`Get-NetTCPConnection`、UTF-8 控制台等。由此产生三个问题：

1. **无法在 CI / Linux 跑**：验证只能在作者的 Windows 机器上进行，`build-and-test.yml` 只跑插件单测，端到端回路无守门。
2. **每加一个插件就要改脚本**：插件 jar 名与全部断言硬编码在 `validate.ps1`（`validate.ps1:38`、断言 A/B/C/D），没有"声明式场景"这一层。
3. **缺跨库版本验证手段**：`override-httpclient-4.x` / `override-hutool-http-5.x` 的核心价值是覆盖多版本库，却没有 `support-version` 版本矩阵。

上游 [`apache/skywalking-java`](https://github.com/apache/skywalking-java/tree/main/test) 的 `test/plugin` 用「通用运行器镜像 + 声明式场景配置 + 版本矩阵 + 数据驱动断言」解决同类问题；本仓库借其形、按其约束裁剪。

## Solution

新增顶层 `verify/` —— 以 Linux/Docker + bash 为第一路径的场景验证回路：

- **通用运行器镜像** `verify/Dockerfile`：JDK8 运行时 + JDK17 构建工具链 + Maven + agent 9.4.0 + `curl`/`jq`；仓库 bind-mount 到 `/src`，m2 命名卷缓存依赖；
- **宿主入口** `verify/run.sh`：一条命令构建镜像并跑一个/全部场景或版本矩阵，聚合退出码（CI 友好）；
- **每插件场景** `verify/scenarios/<plugin>/`：`scenario.conf`（声明 `plugins` / `entry` / `health` / `agent-opts` / 官方插件是否停用）+ `checks.sh`（bash+jq 断言）+ 可选 `support-version.list`（版本矩阵）；
- **首个场景** `logfile-reporter`：把 `validate.ps1` 的 A/B/C/D 断言移植为 bash+jq，覆盖等价；
- **第二个场景** `override-httpclient`：运行时开关 + httpclient POST 表单体采集 tag（`http.request.params`）端到端断言，并对 httpclient 4.5.x 跑版本矩阵；
- **pwsh 保留**：`validate.ps1` 等不删除，README 标注为「本地调试次选」，主路径迁移到 `verify/`。

## User Stories

1. 作为插件作者，我想在 Linux / CI / Windows 任一环境用 `bash verify/run.sh` 得到零/非零退出码，以便不再依赖 Windows 专有 shell。
2. 作为插件作者，我想 logfile-reporter 的 A/B/C/D 断言在容器内以 bash+jq 重跑，以便验证结论与 `validate.ps1` 等价。
3. 作为插件作者，我想 override-httpclient 的采集开关与「POST 表单体被采为 `http.request.params` tag」端到端被断言，以便验证插件核心价值。
4. 作为插件作者，我想对 override-httpclient 跑 httpclient 4.5.x 版本矩阵，以便证明跨版本兼容（上游 `support-version.list` 的形态）。
5. 作为未来插件作者，我想往 `verify/scenarios/<plugin>/` 填 `scenario.conf` + `checks.sh` 即可接入，不必改运行器。
6. 作为维护者，我想 pwsh 回路仍在（次选），且不因本次改动失效。
7. 作为维护者，我想 CI 能跑 verify 回路（agent 随镜像获取，无本机 agent 依赖）。

## Implementation Decisions

- **目录**：`verify/` 置仓库顶层（对应上游 `test/plugin`；跨 agent 插件 + demo-app）。
- **运行器镜像**：多阶段 —— `maven:3.8.6-eclipse-temurin-17` 提供 JDK17，`COPY --from` 到 `maven:3.8.6-eclipse-temurin-8`（JDK8 运行时宿主）；从 Apache 官方发行包取 agent 9.4.0（与既有 Dockerfile/ADR-01 同源）；apt 装 `curl`/`jq`。
- **构建与运行都在容器内**：插件用 JDK17（`release 8`）、demo-app 用 JDK8（与既有 Dockerfile 口径一致）；仓库 rw bind-mount，m2 命名卷。
- **场景声明** `scenario.conf`：`PLUGIN_MODULES`（`mvn -pl`）、`PLUGIN_JAR_GLOBS`（安装进 agent `plugins/` 的 jar，相对 `agent/`）、`PLUGIN_LOAD_REGEX`、`HEALTH_PATH`、`AGENT_OPTS`、`REMOVE_OFFICIAL_HTTPCLIENT`。
- **断言经既有 HTTP 契约**：`/statistic`、`/statisticTraceAlert`、`/inner/sw/trace-alert/{recent,clear}`、`/toggle`、`/httpclient/collect/*`；不触碰插件内部类。tag 断言依赖已回退稳定的 `tagList[].tag-key` 契约（见 `docs/notes/2026-09-29-cross-classloader-tag-json-semantics.md`）。
- **版本矩阵**：`<httpclient.version>` 在 demo-app pom 中覆盖 Spring Boot 管理版本（属性名即 Boot 的 `httpclient.version`），运行器以 `-Dhttpclient.version=<v>` 构建 demo-app。
- **退出码**：0 全绿；构建/未就绪 1/2；插件未加载 3；断言失败 5（沿用 `validate.ps1` 语义）。
- **pwsh 退居二线**：README 明示主次；不删除旧脚本。

## Testing Decisions

- 好测试 = 只测外部可见行为（HTTP 契约），不测实现细节。
- 唯一新 seam = `verify/run.sh` 的退出码 + 每场景 `checks.sh` 的 HTTP 断言层；与 `validate.ps1` 同一批 HTTP 契约，二者互为对照。
- 运行器自身不新增单测基建；以「两场景在 Docker 内跑通并返回 0」为验收。
- 既有单测接缝不变（插件模块 JUnit 照旧）。

## Out of Scope

- mock collector 端到端对账（借上游 `skywalking-mock-collector`，价值高但需另立 spec）。
- override-hutool 场景（demo-app 暂无 hutool 读口桩/控制器）。
- 删除或改造 pwsh 脚本。
- 上游式 Freemarker/配置生成与 tomcat-container 形态。
- 用 verify 回路替代插件模块单测。

## Further Notes

- 与 ADR-01 的构建/运行 JDK 解耦一致：容器内插件构建 JDK17、demo-app 构建与运行 JDK8。
- 术语沿用 `CONTEXT.md` 的「验证回路」。
- 首个范围 = `logfile-reporter-plugin`（与 `docs/repo/known-todos.md` 一致）；override-httpclient 作为版本矩阵试点。
