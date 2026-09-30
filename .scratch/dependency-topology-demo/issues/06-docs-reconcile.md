# 06 — 文档收口：依赖面口径 + 上手入口（含既有 spec 修正）

**What to build:** 把新依赖面与 self 单节点的口径写进既有文档，并**补上 `0578cb4` 欠下的上手入口**。

**Blocked by:** 01（self 节点口径）、02（造数端点与超时）、03（compose 用法）、05（断言口径）。

**Status:** resolved —— NOTES 14/15/16、README 依赖拓扑小节 + 术语表、starter 页面速查表、notes index、既有 spec 矛盾均已改
## 改既有文档

| 文件 | 动作 |
| --- | --- |
| `agent/demo-app/NOTES-docker-stress.md` | **第 14 条改写**：依赖面从 3 条改为分层 6 节点；新增坑——`--profile deps`、中间件未起即红边、外网不可达即红边、造数前需重造（内存窗口重启即失） |
| `agent/demo-app/README.md` | 新增「依赖拓扑演示」小节：`--profile deps` 用法、四组造数端点表、三条边级口径（只到组件类型 / 段内配对 / 边级错误率）；术语表补「依赖边」「依赖拓扑」「总览档 / 明细档」 |
| `verify/README-starter.md` | 页面速查表（L95 起）加 `topology.html` 一行：**这是 `0578cb4` 欠下的上手入口**，本页是本仓唯一的上手主线 |
| `docs/notes/index.md` | 补两篇 9-30 笔记（`2026-09-30-edge-aggregator-tradeoffs.md` / `2026-09-30-exit-span-runtime-probe.md`）——当前索引未收录 |
| `docs/tutorial/index.html` | 无需改（依赖拓扑不是课程线）；若日后要开线再同步左栏大纲 |
| `.scratch/dependency-topology/spec.md` | **修掉自相矛盾**：L21/L159「左列 = 入口端点，零新代码」→ 改为"总览档 self 单点、端点维度在明细档与边列表"；L230「演示环境依赖面很薄」加一句指向本 spec |
| `.scratch/dependency-topology-demo/spec.md` | 落地后把 `Status` 与票 05 的实际断言口径回写（尤其组件名最终形态） |

## 新增页面口径（票 01 已加，此处只核对）

- [ ] caveats 覆盖三条：总览档左列是端点聚合 / 外呼域名不各自成节点 / 中间件未起是红边不是没边。
- [ ] 页内「暂无依赖边」空态的造数入口补上四组新端点。

## 不做

- [ ] 不写 per-page 入门手册（本仓无此形态；沿用"starter 一行 + README 一段 + 页内文案"三处惯例）。
- [ ] 不改 `CONTEXT.md` 已有的「依赖拓扑」词条（若术语有变再改）。

## 验证

- [ ] 按 `verify/README-starter.md` 的学习顺序走一遍，拓扑页能被找到并看懂（含 self 节点数字的含义）。
- [ ] 逐条核对：文档里没有与票 04 实测结论冲突的措辞。