# 13 — 验证回路扩展 + 文档收尾

**What to build:** 端到端验收收口：验证脚本覆盖"环形文件大小恒定 ≤ 上限 / 对账零差异 / 队列丢弃可观测 / 取已知 traceId 返回整条链路、logs 条数与旧视图一致"；并把本次存储形态回写设计文档、拟 ADR。

**Blocked by:** 08 — snapshot 读路径；10 — 背压计数；12 — 使用侧门面

**Status:** done

- [ ] `validate-h2.ps1` 新增断言全绿；`validate.ps1` 全绿
- [ ] 统一方案 §5 回写本次存储形态（H2 header + 环形 payload + 指针）
- [ ] ADR-03 拟稿（H2 定位与拆法）
- [ ] spec 状态更新为完成，验收结论归档

## Comments

**验收执行（2026-09-22，本机 Windows）**

- `mvn -pl logfile-reporter-plugin test`：**102/102 全绿**（含新增 `CappedFileStorageTest` 7 项）。
- `validate-h2.ps1`：**exit 0 全绿**；对账 `checkedCount=19, totalDiffs=0, h2Size=22, h2ErrorCount=0, writeQueueDropped=0`；新增断言 —— 环形文件定长 128MB、`/inner/sw/trace-recent` 非空、`/inner/sw/trace-query?traceId=…` 返回整条链路 `logs>0`。
- `validate.ps1`：**exit 0 全绿**（对外零行为变化）。
- 修复记录：异步写引入 parity 竞态（首轮 `totalDiffs=3`，MISSING_IN_NEW）→ 比对前 `awaitIdle(2000ms)` 等写队列排空 → `totalDiffs=0`；另修首块 `payload_id=0` 被 `>0` 误判为无 payload（改 `>=0`）。
- 文档：`docs/adr/adr-03-capped-file-payload-for-trace-details.md` 新建；`docs/adr/index.md`、`docs/todos/h2化-统一方案.md` §5.2 已回写。
