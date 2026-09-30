# Exit span 实跑取证：操作名格式、标签内容、段内形态

> **日期**：2026-09-30　**关联**：`.scratch/dependency-topology/` issue 01
> **方法**：本地构建 demo-app 镜像（`demo-app-agent:9.4.0`，JDK17 构建插件 / JDK8 运行）→ 造数 → 从 `/inner/sw/trace-query` 摘 Exit span 原样 JSON。
> **性质**：一次性取证笔记，结论供 issue 02/04 引用。**不改任何生产代码。**

## 结论速览

| 问题 | 结论 | 对设计的影响 |
| --- | --- | --- |
| Exit `operationName` 格式 | **已参数化、结构化**：DB=方法签名、HTTP=纯 path | ⚠️ **推翻「需要归一化」的预设**——agent 已归一化过 |
| `db.*` tag | **三个都在**：`db.type` / `db.instance` / `db.statement` | 🔓 **实例级依赖节点零成本解锁**（非 peer 字段） |
| 段内 / 跨段形态 | 实跑全为**段内配对**；异步候选 **0 段** | ⚠️ **异步取舍未被实测覆盖**，只能靠单元测试钉住 |

## 一、Exit span 字段实测

三類出口各抓一条，字段原样：

| componentId | componentName | spanLayer | operationName | tagKeys |
| --- | --- | --- | --- | --- |
| `2` | `HttpClient` | `Http` | `/status/400` | `url` `http.method` `http.status_code` |
| `128` | **（空）** | `Http` | `/api/hutool-demo/echo/json` | `url` `http.method` `http.status_code` |
| `32` | `h2-jdbc-driver` | `Database` | `H2/JDBC/PreparedStatement/executeQuery` | `db.type` `db.instance` `db.statement` |

同组件下观察到的全部 operationName 取值：

```text
componentId=2   (HttpClient)      /status/400
componentId=128 (hutool-http)     /api/hutool-demo/echo/{query,form,json}
componentId=32  (h2-jdbc-driver)  H2/JDBC/PreparedStatement/{execute,executeQuery}
```

## 二、tag 原样值

```text
--- componentId=2  HttpClient        段 Entry = GET:/api/trace-alert-demo/httpclient-httpbin
  url            = https://httpbin.org/status/400          ← 含 scheme + host + path
  http.method    = GET
  http.status_code = 400

--- componentId=128 hutool-http       段 Entry = GET:/api/hutool-demo/post-json
  url            = http://127.0.0.1:9600/api/hutool-demo/echo/json   ← 回环自调，host 是自己
  http.method    = POST
  http.status_code = 200

--- componentId=32  h2-jdbc-driver   段 Entry = GET:/queryDbByJdbc
  db.type        = H2
  db.instance    = dbtest                                      ← 实例名！
  db.statement   = SELECT * FROM "USER" WHERE use_name = ?    ← 已参数化，占位符
```

## 三、三个发现

### 发现 1：operationName 已归一化，基数担忧不成立

DB 侧是 `H2/JDBC/PreparedStatement/executeQuery`（方法签名），HTTP 侧是纯 path（`/status/400`、`/api/hutool-demo/echo/json`）。

**均不含** query string、host、SQL 变量、UUID。Agent 在 Exit span 上已做过归一化。

→ issue 12 原本担心「SQL 语句 / URL path 进 operationName 会让边节点基数无界」，**实测不成立**。归一化规则**不需要写**。
→ 但**上限保护仍要保留**：基数等于「不同出口 path 数」，业务 path 多时仍会增长，只是远晚于预期。`h2-jdbc-driver` 侧尤其安全——所有 SQL 塌缩成两个方法签名。

### 发现 2：实例级依赖节点零成本解锁（不需捕获 peer）

spec 的 Out of Scope 写「不捕获对端地址（`peer`）」，理由是链路转换未读该字段。**实测给出了成本更低的替代路径**：

| 依赖类型 | 实例身份来源 | 取值示例 |
| --- | --- | --- |
| 数据库 | `db.instance` tag | `dbtest` |
| HTTP | `url` tag 的 scheme+host 部分 | `https://httpbin.org` / `http://127.0.0.1:9600` |

两者**都已在 `tagList` 里**，读标签即可，**不需要碰载荷结构、不触发 ADR-03 容量重估**。

→ 「依赖节点升到实例级」的解锁条件**已经满足**，且成本远低于原设想的「扩 `SegmentLogConverter` 捕获 `peer`」。
→ **本期实现仍按 spec 停在组件类型 + operationName**（不越界）。此项作为下一轮输入记于此。

### 发现 3：`componentId=128` 在组件库里查不到名字

`COMPONENT_ID_NAME_MAP.get(128)` 返回 **null**（hutool-http 插件的组件 ID 不在 `component-libraries.yml` 的 629 行里）。

→ 总览档右列若只依赖组件名翻译，该节点会**没有名字**。
→ **必须在读口/宿主侧加 fallback**：按 `spanLayer` 归类（`Http` / `Database` / …）或退化为 `component-<id>`。
→ 这不是本 feature 引入的问题（现有 span JSON 的 `componentName` 同样为空），但**拓扑图会把它暴露成视觉缺陷**，故纳入 issue 03。

## 四、未能取证项

| 项 | 原因 | 影响 |
| --- | --- | --- |
| **异步（跨段）出口形态** | 实跑造出的调用**全部是同步段内配对**；「有 Exit 但无 Entry」的段 **0 条**。demo-app 无 `@Async` 出口依赖可造 | 「异步不建边」取舍**未被实测覆盖** → 只能靠 issue 02 的**负向单元测试**钉住，不可依赖实跑证据 |
| 缓存 / 消息队列依赖 | demo-app 依赖库无 Redis / MQ / Kafka 客户端（`pom.xml` 已核） | 无对应实例可取证；不影响设计（走通用 tag 路径） |

## 五、对 issue 的输入

| Issue | 输入 |
| --- | --- |
| 02 | 边键右端用 `componentId`（整数）；**不需要** operationName 归一化；小样本池与双维上限维持原判 |
| 03 | **必须加组件名 fallback**（`spanLayer` 归类或 `component-<id>`）——发现 3 |
| 04 | 明细档标签文案：DB 侧 operationName 已是可读方法签名，可直接展示；实例身份（发现 2）本期不展示 |
| 05 | 断言用得上 `componentId` 整数做稳定锚点，不依赖组件名（组件名可能为空） |

## 附：取证命令

```powershell
# 造数（本地）
docker compose up -d --build demo-app          # agent/demo-app
Invoke-WebRequest http://127.0.0.1:9600/queryDbByMybatis
Invoke-WebRequest http://127.0.0.1:9600/queryDbByJdbc
Invoke-WebRequest "http://127.0.0.1:9600/api/hutool-demo/post-json"
Invoke-WebRequest "http://127.0.0.1:9600/api/trace-alert-demo/httpclient-httpbin?code=200"

# 取证（从现有读口，无需新代码）
Invoke-RestMethod "http://127.0.0.1:9600/inner/sw/trace-recent?limit=60"
Invoke-RestMethod "http://127.0.0.1:9600/inner/sw/trace-query?traceId=<id>"   # 摘 spanType=Exit
```

> 提醒：插件的 H2 内存库与内存热层**重启即失**（ADR-04），重启后需重新造数再取证。
> 组件名翻译在宿主侧（`COMPONENT_ID_NAME_MAP`），故 `componentName` 字段只出现在 demo-app 读口响应里，插件侧看不到。
