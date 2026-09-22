# 01 — storage 子包重构（只搬家，行为零变化）

**What to build:** 把 trace 存储相关类（存储接口、其 H2 实现、以及被二者共用的转换点）收敛进独立的 storage 子包，并调整可见性使跨包使用成立。**行为与对外输出零变化**——这是后续所有存储改造的 prefactor（"先让改动变容易"）。

**Blocked by:** None — can start immediately

**Status:** done

- [ ] 存储接口与 H2 实现迁入 storage 子包；共用的转换点改为跨包可见
- [ ] H2 实现中被客户端使用的审计/计数方法改为跨包可见
- [ ] 对应单元测试随类迁入同子包并继续全绿
- [ ] `validate.ps1` 与 `validate-h2.ps1` 全绿（证明对外零行为变化）
