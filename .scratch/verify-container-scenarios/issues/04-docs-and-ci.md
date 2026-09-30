# 04 — 文档与 CI

**What to build:** `verify/README.md`（用法 / 退出码 / 加场景步骤 / 与 pwsh 主次）；更新 `agent/demo-app/README.md` 标注 Docker/bash 主路径、pwsh 次选；新增 `.github/workflows/verify.yml`（ubuntu 跑 logfile-reporter 场景）。

**Blocked by:** 02

**Status:** done

- [x] `verify/README.md` 讲清一条命令、退出码、如何新增场景与版本矩阵。
- [x] `agent/demo-app/README.md` 明示 verify（Docker/bash）为主、`scripts/*.ps1` 为次选。
- [x] CI workflow 在插件路径变更时触发，跑 `verify/run.sh --scenario logfile-reporter`。

## Comments

**实现（2026-09-29，commit b544ffc）**

- `verify/README.md`：从零开始（前置依赖）→ 一条命令速查（默认全跑 / 单场景 / `--matrix` / `--list`）→ 退出码表 → 目录结构（含 `support-version.list` 语义）→ 断言机制 → 版本矩阵（`-D<name>=<v>` 覆盖）→ 如何新增场景 4 步 → 与 pwsh 脚本的主次关系 → 不在范围内。
- `agent/demo-app/README.md:87-88`：明示"验证回路（推荐，bash）"为主路径并链到 `verify/README.md`，`scripts/*.ps1` 标注为**次选**（方便 Windows 本地调试），并说明两条路径同属一批 HTTP 契约。旧脚本未删除。
- `.github/workflows/verify.yml`：ubuntu-latest，push/PR 到 master 且路径命中 `verify/**`、`agent/logfile-reporter-plugin/**`、`agent/override-httpclient-4.x-plugin/**`、`agent/demo-app/**` 时触发，跑 `bash verify/run.sh --scenario logfile-reporter`。agent 随镜像获取，不依赖本机 agent。
- 排障指引落在 handoff `docs/handoff/2026-09-29-verify-container-scenarios.md` §7（Docker Desktop 残留代理 `127.0.0.1:7897` 的处理、应用未就绪/插件未加载看哪两个日志）。
