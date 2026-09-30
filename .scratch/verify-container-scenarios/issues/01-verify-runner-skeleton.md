# 01 — 通用运行器镜像与宿主入口（骨架）

**What to build:** 新增 `verify/Dockerfile`（JDK8 运行时 + JDK17 工具链 + Maven + agent 9.4.0 + curl/jq）、`verify/docker-compose.yaml`（`verify` 服务，仓库 bind-mount，m2 命名卷）、`verify/lib.sh`（断言/HTTP/等待助手）、`verify/run-scenario.sh`（容器内入口：按 `scenario.conf` 构建插件与 demo-app、装配 agent、启动、加载校验、跑 `checks.sh`、按语义退出）、`verify/run.sh`（宿主 bash 入口：构建镜像一次 + 跑场景/矩阵 + 聚合退出码）。

**Blocked by:** （无）

**Status:** done

- [x] 镜像构建成功；容器内 `mvn -v`、`jq --version`、`/opt/skywalking-agent/skywalking-agent.jar` 均存在。
- [x] `bash verify/run.sh --list` 列出场景；无参跑默认场景。
- [x] 场景缺失 / 构建失败 / 未就绪 / 插件未加载分别以 `2/1/2/3` 退出；断言失败 `5`；全绿 `0`。

## Comments

**实现（2026-09-29，commit b544ffc / f30c4db / 1581339）**

- 运行器骨架 6 个文件全部落地：`Dockerfile`（多阶段：temurin-17 构建 → temurin-8 运行，apt 装 curl/jq，agent 9.4.0 用 `curl --retry 5` 下载后 `tar -xzf -C /opt`）、`docker-compose.yaml`（仓库 rw bind-mount 到 `/src` + m2 命名卷 `verify-m2`）、`lib.sh`（`assert_eq/ge/le/jq`、`http_get/post`、`wait_ready`）、`run-scenario.sh`（容器内 6 步：读 conf → 插件 JDK17 构建 → demo-app JDK8 构建 → 装配 agent → 启动+就绪+加载校验 → 跑 checks）、`run.sh`（宿主：build 镜像一次 → 跑场景/矩阵 → 聚合退出码）、`README.md`。
- 退出码按 spec 落地并逐条核过实现位置：场景缺失 `run-scenario.sh:29` → 2；构建失败 `:54/:63/:64/:74` → 1；应用 120s 未就绪 `:92-95` → 2；插件加载关键字未命中 `:102-105` → 3；断言失败 `:122` → 5；全绿 `:123` → 0。`run.sh` 侧 `--list` → 0（`:30-32`）、场景缺失 → 2（`:45`）、镜像构建失败 → 1（`:51`）、聚合失败 → 1（`:79`）。
- 运行期缺陷修正（均属"不修跑不起来"级别）：`wait_ready` 曾二次拼接 `BASE_URL` 生成畸形 URL 致就绪探测恒失败，已改为直 curl 传入 URL（`lib.sh:50-54`）；`Dockerfile` 曾对不存在的 `apache-skywalking-java-agent-9.4.0` 目录做 `mv`，已删（发行包顶层目录即 `skywalking-agent/`，`tar -tzf` 实探确认）；agent 下载从 `ADD https://…` 改为 `curl`（远端 ADD 经代理 flaky 会 `unexpected EOF`）。
- 镜像可用性已由后续 02/03 工单的容器实跑反向证明（日志出现 `场景[xxx]全绿: PASS=… (exit 0)`）。
