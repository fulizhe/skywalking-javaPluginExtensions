# 12 — 使用侧门面 + demo 读口/页面

**What to build:** 使用侧可经宿主工具类按 traceId 取回整条链路（**仅 JDK 原生类型**）；拦截器把方法入参透传给 Agent 内部实现；demo-app 提供读口与页面——列出最近链路、输入 traceId 即可**人工查看整条链路**。

**Blocked by:** 11 — 存储侧查询

**Status:** done

- [ ] 宿主工具类新增查询方法（按 traceId / 最近列表），拦截器透传入参并返回原生 Map/List
- [ ] demo 读口返回整条链路；页面可列出最近列表并展示指定 traceId 的整条链路
- [ ] 不改既有宿主工具类方法签名与既有 demo 端点契约
