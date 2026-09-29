# 04 — 文档与 CI

**What to build:** `verify/README.md`（用法 / 退出码 / 加场景步骤 / 与 pwsh 主次）；更新 `agent/demo-app/README.md` 标注 Docker/bash 主路径、pwsh 次选；新增 `.github/workflows/verify.yml`（ubuntu 跑 logfile-reporter 场景）。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `verify/README.md` 讲清一条命令、退出码、如何新增场景与版本矩阵。
- [ ] `agent/demo-app/README.md` 明示 verify（Docker/bash）为主、`scripts/*.ps1` 为次选。
- [ ] CI workflow 在插件路径变更时触发，跑 `verify/run.sh --scenario logfile-reporter`。
