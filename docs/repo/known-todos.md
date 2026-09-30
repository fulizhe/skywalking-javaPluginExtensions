# 已知 TODO（repo known gaps）

- **pwsh 本地回路未跟进 override 插件**（2026-09-30 遗留）：`agent/demo-app/scripts/*.ps1` 的断言仍只覆盖 `logfile-reporter-plugin`。本次新增的 override 读口与断言只在容器场景里：
  - 触发端点：`/api/hutool-demo/{get-query,post-form,post-json,post-multipart,error-call}`（`HutoolHttpDemoController`）、`/api/trace-alert-demo/httpclient-post`；
  - 断言来源：`verify/scenarios/override-hutool/checks.sh`（30 条）、`verify/scenarios/override-httpclient/checks.sh`（5 条）。
  二选一收口：① `validate.ps1` 补上两插件的等价断言（保持 pwsh 与容器两套同源对照）；② 明确宣布 pwsh 回路只服务 logfile-reporter 本地调试，不再追平 override 场景（则把本条删掉并在 README 注明）。
- trace 写线程的分配 / CPU 优化（P0 SQL 复用+cap 节流 / P1 流式序列化直写 gzip / P2 去小对象 / P3 gzip 复用与 level，先量化再动）→ `docs/todos/h2化-trace写路径分配与CPU优化.md`
