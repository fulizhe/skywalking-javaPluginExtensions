# 01 — 通用运行器镜像与宿主入口（骨架）

**What to build:** 新增 `verify/Dockerfile`（JDK8 运行时 + JDK17 工具链 + Maven + agent 9.4.0 + curl/jq）、`verify/docker-compose.yaml`（`verify` 服务，仓库 bind-mount，m2 命名卷）、`verify/lib.sh`（断言/HTTP/等待助手）、`verify/run-scenario.sh`（容器内入口：按 `scenario.conf` 构建插件与 demo-app、装配 agent、启动、加载校验、跑 `checks.sh`、按语义退出）、`verify/run.sh`（宿主 bash 入口：构建镜像一次 + 跑场景/矩阵 + 聚合退出码）。

**Blocked by:** （无）

**Status:** ready-for-agent

- [ ] 镜像构建成功；容器内 `mvn -v`、`jq --version`、`/opt/skywalking-agent/skywalking-agent.jar` 均存在。
- [ ] `bash verify/run.sh --list` 列出场景；无参跑默认场景。
- [ ] 场景缺失 / 构建失败 / 未就绪 / 插件未加载分别以 `2/1/2/3` 退出；断言失败 `5`；全绿 `0`。
