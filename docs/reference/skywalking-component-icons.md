# SkyWalking 组件图标（品牌图标）在哪 / 能不能抄

**一句话结论**：**SkyWalking 9.4.0 拿不到那套品牌图标**（docker 鲸鱼 / Go gopher / PostgreSQL 大象 / nginx / node）。
本仓依赖拓扑页现在用的是**手绘内联 SVG**；若要换成官方品牌图标，需去**更新的 SkyWalking 版本**里找，或自行提供（Apache-2.0）。

> 本文每条结论都标了 **已核实 / 未核实**，附可复现的验证命令。核实时间：2026-10-01，基准：本机 `apache/skywalking-ui:9.4.0` 容器 + GitHub `master` / `main` 分支。

---

## 1. 问题从哪来

SkyWalking 官方拓扑图里，不同组件有不同的**品牌图标**（docker、Go、PostgreSQL、nginx、node、redis…）。
本仓 `agent/demo-app/src/main/resources/static/dashboards/topology.html` 的依赖拓扑页也想达到同样的"扫一眼分清层次"效果。

---

## 2. 核实结果

| 查了什么 | 怎么查的 | 结果 |
| --- | --- | --- |
| **SkyWalking 9.4.0 UI 包里有没有品牌图标** | 从本机 `sw-ui` 容器（`apache/skywalking-ui:9.4.0`）拷出 `/skywalking/webapp/skywalking-webapp.jar`，`jar tf` 过滤 `icon` / `.svg` | **没有**。整个 jar 里只有 `favicon.ico` 和一个 codicon 字体，**零组件 svg** |
| 官方 UI 仓有没有品牌图标 | `api.github.com/repos/apache/skywalking-booster-ui/contents/src/assets/icons` | **没有**。96 个文件，全是 Material Symbols 风格**单色**图标：`database` / `mq` / `cloud_queue` / `storage` / `gateway` / `kubernetes` / `cilium` / `service_mesh`… |
| 组件→图标的映射在哪 | `oap-server/server-starter/src/main/resources/component-libraries.yml`（954 行） | **该文件里没有 `icon` 字段**（`^\s*icon:` 匹配 0 处） |
| OAP 仓有没有 icon 静态资源目录 | 逐层列 `oap-server/`、`server-starter/`、`server-core/` | **没有**名为 `icon*` 的目录 |
| 老 UI 仓（rocketbot-ui）的 `src/resources/graph/icon/` | GitHub API | **404**（该路径在当前 master 不存在，可能是更老的版本） |

**推断（未核实）**：你截图里那套品牌图标应来自**更新的 SkyWalking（10.x/11.x）**或**别的产品线**
（例如 Grafana 的 SkyWalking 应用）。本机这个 9.4.0 部署里确定没有。

---

## 3. 复现命令（本机已验证可跑）

```powershell
# ① 本机 SkyWalking UI 包里到底有没有组件图标（结论：没有）
docker cp sw-ui:/skywalking/webapp/skywalking-webapp.jar $env:TEMP\sw-webapp.jar
& "D:\apps\java\jdk-17.0.8\bin\jar.exe" tf "$env:TEMP\sw-webapp.jar" | Select-String "\.svg"
# 容器里直接翻（更快的否定证据）
docker exec sw-ui sh -c "find / -name '*.svg' | wc -l"      # → 0

# ② 官方 UI 仓的图标目录（结论：96 个 Material 单色图标，无品牌图标）
Invoke-RestMethod -Headers @{ "User-Agent"="probe" } `
  "https://api.github.com/repos/apache/skywalking-booster-ui/contents/src/assets/icons" |
  Select-Object -ExpandProperty name

# ③ 组件库映射里有没有 icon 字段（结论：没有）
Invoke-WebRequest -UseBasicParsing `
  "https://raw.githubusercontent.com/apache/skywalking/master/oap-server/server-starter/src/main/resources/component-libraries.yml" |
  Select-String -Pattern "^\s*icon:"      # → 无匹配
```

> 注：GitHub **code search**（`/search/code`）需要 token，本次核实未用；上面几条都是 Contents API + raw 文件，能覆盖这三个关键问题。

---

## 4. 若仍想用品牌图标：三条路

| 路 | 做法 | 成本 / 风险 |
| --- | --- | --- |
| A · 找到更新的 SkyWalking 取源 | 在 10.x/11.x 的 UI 产物里取 svg（升级后的 `sw-ui` 容器 jar 或对应 UI 仓） | 版本要对齐；产物是打包后的 js/css，取 svg 需解包；**Apache-2.0，拷进本仓要附 NOTICE** |
| B · 自己画品牌化图标 | 保持现在的 `ICON_SVG` 结构，把圆柱/堆叠盘/六边形/云朵换成更"像官方"的彩色版本 | 零外部依赖、零许可问题；缺点是与官方那套**不是同一套视觉**，读者仍需建立一次映射 |
| C · 干脆按层分类而非按组件 | 既然拿不到品牌图标，就把语义收敛到"层"（Database / Cache / MQ / Http / RPC），图标即层图标 | 语义最稳（组件名会变、层稳定）；**当前实现已经是这个思路**，只是图标是自己画的 |

---

## 5. 本仓现状与接入点（已经抽象好了）

依赖拓扑页的图标全部来自**一个 map**，换图标只动这一处：

| 位置 | 作用 |
| --- | --- |
| `topology.html` 的 `ICON_SVG` | 手绘内联 SVG：`database` / `cache` / `mq` / `http` / `grpc` / `generic` / `self` |
| `iconFor(componentName, spanLayer)` | **组件名 → 图标**的映射（按实测组件名匹配，如 `Jedis`→cache、`GRPC`→grpc） |
| `renderIconKey()` | 图下方那行"图标 = 组件类型"的真图标说明 |
| ECharts `legend[].data[].icon` | 图例用同一批图标（`image://` + data URI） |

因此"换成官方/自绘品牌图标"的落点很集中：

1. 把 svg 放进 `agent/demo-app/src/main/resources/static/dashboards/icons/`；
2. 加 `NOTICE` 说明来源与许可；
3. 把 `ICON_SVG` 的值从"内联 svg 字符串"换成"文件名"，`svgSymbol()` 改成拼 `icons/<name>.svg`。

**页面其余逻辑不用动** —— 因为接口已经是"名字 → 图标"。

---

## 6. 与本仓其它口径的关系

组件名一律按**实测值**匹配（与本文件 §2 同源的一条纪律）：

| 组件（页面显示） | 层（spanLayer） | 当前图标 |
| --- | --- | --- |
| `h2-jdbc-driver` | `Database` | 圆柱 |
| `mysql-connector-java` | `Database` | 圆柱 |
| `Jedis` | `Cache` | 堆叠盘 |
| `kafka-producer` | `MQ` | 六边形 |
| `HttpClient` / `Http(#128)` | `Http` | 云朵 |
| `GRPC` | `RPCFramework` | 辐射 |

> 这张表本身也是实测结论：组件库里 gRPC 的键是**大写 `GRPC`**、层是 `RPCFramework`（都不是直觉的 `gRPC`）——
> 与 [`../notes/2026-10-01-deps-demo-topology-probe.md`](../notes/2026-10-01-deps-demo-topology-probe.md) 同批。
