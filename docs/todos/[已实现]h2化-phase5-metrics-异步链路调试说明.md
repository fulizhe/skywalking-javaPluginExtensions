# 异步链路调试说明（`/helloAsyncServlet`：Servlet 3 async + `@TraceCrossThread`）

> **用途**：在 demo-app 上复现并**肉眼验证**"跨线程异步 ⇒ 新 segment、入口 span 不覆盖异步等待"这条结论（Phase 5 讨论的实证场景，见 `[已实现]h2化-phase5-metrics-口径与插入点讨论.md` §9）。
> **环境**：Windows + pwsh 7；运行 JDK 8、agent 9.4.0、插件已装入 agent；**应用工作目录必须是 `agent/demo-app`**（原因见 §7）。
> **端口**：下文以 **9601** 为例（脚本默认 9600，可任意指定）。
>
> **实现备注**：`/helloAsyncServlet` 调试场景已实现；仪表盘侧也已加"异步触发面板"（§4.1），**代码已落地但交互验证未收口**（§10）；本文验证流程仍为人工操作，自动回归脚本尚未固化。

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

### 4.1 异步触发面板（免手工 curl）

`dashboard.html` 的 **统计页（`p=statistic`）** 顶部有一个"Servlet 3 async + `@TraceCrossThread`"面板，把 §2+§4 串成一次点击：

| 元素 | id | 行为 |
| --- | --- | --- |
| 触发按钮 | `async-scenario-btn` | 点击后同源 `GET /helloAsyncServlet`（`cache: "no-store"`）；期间按钮置灰、文案变"触发中…"，`.finally` 恢复 |
| 状态 | `async-scenario-status` | `请求中…` → `已触发 · traceId <TID>` / `已触发，响应中未返回 traceId`（`is-success`）/ `触发失败：<原因>`（`is-error`）；`role="status" aria-live="polite"` |
| 链路入口 | `async-scenario-link` | 成功且解析到 traceId 时显示，指向 `trace-view.html?traceid=<TID>`（`target="_blank" rel="noopener"`） |

- 面板**仅在 `p=statistic` 显示**，其他页 `hidden`；面板内写明本场景**目的**（验证异步子线程自成新 segment、入口 span 不含异步等待）。
- 成功后自动 `refresh()` 刷新下方 Trace 缓存表，无需手动刷新。
- 相关文件：`static/dashboards/dashboard.html`（结构）、`dashboard.js`（`setupAsyncScenario`，约 684–729 行）、`dashboard.css`（`.scenario-panel/.scenario-status/.scenario-link`）。
- ⚠️ 已知小问题（**不影响功能**，见 §10.2）：traceId 正则多了一个反斜杠。

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

---

## 10. 异步触发面板：当前验证状态（2026-09-25 记录，**验证未收口**）

> **决策（用户，2026-09-25）**：本轮只回写文档，代码与验证均不动；以下缺口择专门时间再收口。**不要**因为本文档已描述该面板就当作"已验收"。

### 10.1 已验证 / 未验证

| 项 | 状态 | 证据或说明 |
| --- | --- | --- |
| 面板结构与 `p=statistic` 显隐逻辑 | ✅ 已核对源码 | `dashboard.html:24-33`、`dashboard.js:684-729` |
| JS 语法 | ✅ 通过 | `node --check dashboard.js` |
| 按钮**确实打到** `/helloAsyncServlet` | ✅ 已验证 | 纯应用 9601（无 agent）下点击，服务端收到请求 |
| 无 agent 时**空 traceId 分支** | ✅ 已验证 | 页面正确显示"已触发，响应中未返回 traceId"，未误报成功链接 |
| **带 agent → 非空 traceId → 链接可打开链路** | ❌ **未验证** | 核心验收项，从未跑通 |
| HTTP 失败态（`!response.ok` / 网络异常） | ❌ 未验证 | 代码有 `is-error` 分支，无实测 |
| 重复点击保护 | ❌ 未验证 | 代码有 `disabled` + `.finally` 恢复，无实测 |
| 桌面 / 窄屏布局、面板是否挤压 Trace 缓存表 | ❌ 未视觉验收 | 无截图证据 |
| 浏览器 console 报错 | ❌ 未验收 | 曾生成 `.playwright-mcp/console-*.log`，已随临时产物清理 |

**未跑通的原因**（不是代码缺陷，是环境/流程问题）：

- 启动 demo-app 的 `mvn spring-boot:run` 前台进程被后台任务超时机制杀掉，最后一次以 `BUILD FAILURE` + `Application finished with exit code: -1` 收场，9601/9602 随即无监听 → 浏览器侧 `net::ERR_CONNECTION_REFUSED`。
- 带 agent 的验证要求该实例**独占工作目录**（§7），而旧 9600 实例（PID `39660`）占用 `agent/demo-app/target/` 下产物，导致 `mvn clean` 删不掉被锁文件（`Failed to delete ... run-with-agent-out.log`）。**该实例属其他用途，不要强杀**；复验前先确认端口占用与文件锁归属。

### 10.2 已知小问题（不影响功能，可留到下次一并修）

`dashboard.js:706` 的 traceId 解析正则多了一个反斜杠：

```js
var match = text.match(/(?:^|\\|)traceId=([^|]+)/);   // 意图应为 /(?:^|\|)traceId=([^|]+)/
```

- `(?:^|\\|)` 被解析为三个分支：行首 **或** 字面反斜杠 **或 空**。因为存在**空分支**，该组在任何位置都能匹配，退化成"全文任意位置搜 `traceId=`"。
- **后果：功能正常**——响应形如 `helloAsyncServlet|traceId=<TID>|segmentId=...`，照样能取到 traceId 并生成链接。
- **代价：边界检查失效**——若将来某字段值内部含 `traceId=` 字样，可能取到非预期值。修法是删掉多余的那个反斜杠。

### 10.3 下次收口的建议顺序

1. 确认 9600/9601 端口与 `agent/demo-app/target/` 文件锁归属，必要时**换端口**（而非杀进程）起带 agent 实例；
2. 用 §1 的 `run-with-agent.ps1` 起实例（**不要**用会超时的前台 Maven），保持前台挂住；
3. 浏览器打开 `dashboard.html?p=statistic` → 点按钮 → 断言状态为 `已触发 · traceId <非空>`，点"查看链路"确认落到 `trace-view.html` 且 `segments=2`（§5 判读要点）；
4. 停实例后（Ctrl+C）再跑一次按钮，确认 `is-error` 文案；
5. 桌面 + 窄屏各截一张图，确认面板不挤压 Trace 缓存表、无横向滚动；
6. 顺手修 §10.2 的正则；如有余力，把 §2–§3 + §4.1 串成 `validate-h2.ps1` 的一个断言组（§9）。
