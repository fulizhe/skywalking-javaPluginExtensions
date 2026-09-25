# 异步链路调试说明（`/helloAsyncServlet`：Servlet 3 async + `@TraceCrossThread`）

> **用途**：在 demo-app 上复现并**肉眼验证**"跨线程异步 ⇒ 新 segment、入口 span 不覆盖异步等待"这条结论（Phase 5 讨论的实证场景，见 `[已实现]h2化-phase5-metrics-口径与插入点讨论.md` §9）。
> **环境**：Windows + pwsh 7；运行 JDK 8、agent 9.4.0、插件已装入 agent；**应用工作目录必须是 `agent/demo-app`**（原因见 §7）。
> **端口**：下文以 **9601** 为例（脚本默认 9600，可任意指定）。
>
> **实现备注**：`/helloAsyncServlet` 调试场景已实现；本文验证流程仍为人工操作，自动回归脚本尚未固化。

---

## 0. 前置

- agent：`D:\apps\apache-skywalking-java-agent-9.4.0`（`skywalking-agent.jar` 存在）
- 运行 JDK：`D:\apps\java\jdk1.8.0_172`
- demo jar：`agent/demo-app/target/demo-app-1.0.0.jar`（改了 Java/静态资源需重新 `package`）
- 插件 jar：`agent/logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar` 已复制进 agent `plugins/`

## 1. 启动（推荐：现成脚本，自动装配 + 保持运行）

```powershell
# 在仓库根执行；-SkipPluginBuild 复用已构建插件；端口 9601
pwsh ./agent/demo-app/scripts/run-with-agent.ps1 -SkipPluginBuild -SkipAppBuild -Port 9601
```

- 脚本会：构建/复用 demo jar → 装插件 → 以 `-javaagent` + `-Dskywalking.*` 启动 → `WebPort=9601` → 轮询就绪 → **保持运行**（前台挂住，Ctrl+C 停止）。
- **改了源码就不用 `-SkipAppBuild`**（否则复用旧 jar，静态页看不到更新）。
- 手动等价命令（脚本不可用时）：

```powershell
$java='D:\apps\java\jdk1.8.0_172\bin\java.exe'
$agent='D:\apps\apache-skywalking-java-agent-9.4.0\skywalking-agent.jar'
$jar='E:\gitRepository\_skywalking-javaPluginExtensions\agent\demo-app\target\demo-app-1.0.0.jar'
$env:WebPort='9601'
& $java "-javaagent:$agent" -Dskywalking.agent.keep_tracing=true `
  -Dskywalking.plugin.logfilereporter.h2.enabled=true -jar $jar
```

## 2. 触发场景

```powershell
curl.exe -s --noproxy "*" http://127.0.0.1:9601/helloAsyncServlet
# → helloAsyncServlet|traceId=<TID>|segmentId=<SID>|thread=Thread-N
```

把返回的 `<TID>` 记为 `$tid`。该端点的实现（`HelloController.java`）：

```java
AsyncContext ctx = request.startAsync();      // Servlet 3 异步：容器线程立即返回
new Thread(new AsyncServletTask(ctx)).start(); // 子线程 @TraceCrossThread 续接父上下文
```

## 3. 取回整链（H2 + 环形文件）

```powershell
curl.exe -s --noproxy "*" "http://127.0.0.1:9601/inner/sw/trace-query?traceId=$tid"
# 或先列最近段挑 traceId：
curl.exe -s --noproxy "*" "http://127.0.0.1:9601/inner/sw/trace-recent?limit=20"
```

## 4. 图形化查看（两种）

- **仪表盘联动**：`http://127.0.0.1:9601/dashboards/dashboard.html?p=statistic`
  → Trace 缓存表每行右侧 **查看** 按钮 → 弹框内嵌 `trace-view.html`（ECharts 链路图 + Span 瀑布表）。
- **直接打开**：`http://127.0.0.1:9601/dashboards/trace-view.html?traceid=$tid`

## 5. 判读要点

| 观察 | 含义 |
|---|---|
| `logs`（=segments）**2 条** | 异步子线程**自成一条 segment**（`CROSS_THREAD` ref），不是并入入口段 |
| 段1 `Entry` `GET:/helloAsyncServlet`，耗时**很小**（1~90ms） | 入口 span 在 `startAsync()` 返回即结束 |
| 段2 `Local` `Thread/…AsyncServletTask/run`，耗时**几百 ms** | 异步工作在同 traceId 的**另一条段**里 |
| 段2 `componentId=0 → Unknown` | 工具包 `@TraceCrossThread` 建的是无组件 Local span（正常）；页面兜底显示 operationName |
| `payloadExpired=false` | 载荷未过期（环形文件未覆盖） |

> 结论：**`afterFinished` 每次只给一条 segment**；异步（含 servlet3 async + `@TraceCrossThread`）必然产"新段"，入口 span 的 duration **不含**异步等待（与 Glowroot 把 async 单独归属同义）。

## 6. 实测样例（本机 9.4.0 + Spring Boot）

```
helloAsyncServlet|traceId=2676f7...40001|segmentId=2676f7...840000|thread=Thread-4

segments=2 payloadExpired=False
  [seg1 Entry] op=GET:/helloAsyncServlet          comp=SpringMVC  dur≈70ms
  [seg2 Local] op=Thread/…HelloController$AsyncServletTask/run  comp=Unknown  dur≈239ms
```

`trace-view.html` 顶部摘要：`segments=2 spans=2 端到端=309 ms`。

## 7. 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `trace-query` 返回 `logs: []`、`payloadExpired=false`（`recent` 却有记录） | 实例的**工作目录**不是 demo-app，且默认相对路径 `trace-payload.capped.db` 被**另一个实例占用** → capped 写入失败 → `payload_id` 为空 | 用脚本（自带 `-WorkingDirectory demo-app`）；或显式 `-Dskywalking.plugin.logfilereporter.h2.payload_capped_file=<绝对/独立文件名>` |
| 返回 `traceId=-1` / 空 | 没挂 agent，或上下文未传播 | 确认 `-javaagent`；确认端点在 agent 下运行 |
| `查看` 弹框空白 / 静态页没更新 | 复用了旧 jar | 去掉 `-SkipAppBuild` 重新 `package` |
| curl 回环被代理劫持 / 502 | 代理环境 | 一律加 `--noproxy "*"` |
| 端口被占 | 旧实例未停 | 换 `-Port`，或 `Get-NetTCPConnection -LocalPort 9601 -State Listen` 找 pid 后 `Stop-Process` |

## 8. 对照：demo 的四种"异步"

| 端点 | 机制 | 预期 |
|---|---|---|
| `/helloAsync` | Spring `@Async` | 子线程新段 |
| `/helloAsync2` | `RunnableWrapper.of(...)`（裸线程） | 子线程新段 |
| `/helloAsync3` | `@TraceCrossThread`（裸线程） | 子线程新段 |
| **`/helloAsyncServlet`** | **Servlet 3 `startAsync()` + `@TraceCrossThread`** | **入口段 2ms 级结束；异步自成新段** |

## 9. 一键回归（可选）

把 §2–§3 串起来断言"segments=2、入口为 Entry、异步为 Local"即可做成脚本（参照 `scripts/validate-h2.ps1` 的 `Invoke-LocalHttp` 模式）。当前为人工调试流程，未固化为脚本。
