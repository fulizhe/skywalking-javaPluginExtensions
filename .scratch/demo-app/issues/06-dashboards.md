# 06 — 静态仪表盘移植

**What to build:** 移植静态仪表盘(统计/JVM/实例/meter/告警/profile 页 + 导航),轮询与验证脚本同一批 JSON 契约,静态资源随应用打包、不依赖外部网络——实跑下图表有数,他人可直观看到各数据流被缓存。

**Blocked by:** 03 — 统计快照读口移植;04 — Trace 告警验证面移植

**Status:** done

- [x] 六类页面(统计/JVM/实例/meter/告警/profile)+ 导航页在实跑下可用、图表有数据
- [x] 页面与验证脚本读同一批 JSON 契约(同源,不另造接口)
- [x] 静态资源(js/css 库)随应用打包,无外部 CDN/网络依赖

## Comments

- 2026-08-09 已实现并验证(旧项目不在本机,按现有 JSON 契约全新编写,纯原生 JS+SVG,零外部库):
  - 静态目录 `src/main/resources/static/dashboards/`:`index.html` 导航页(六卡片)+ `dashboard.html`(通用壳,`?p=statistic|jvm|meter|instance|alert|profile`)+ `dashboard.css` + `dashboard.js`(按页注册端点/轮询间隔/渲染器,轮询 3~6s);随应用打包,Spring Boot 静态映射 `/dashboards/*`,无 CDN 依赖,断网可用。
  - 页面与验证脚本同源:直接轮询 `/statistic`、`/statisticJVM`、`/statisticMeter`、`/statisticInstanceProperties`、`/statisticTraceAlert`、`/inner/sw/trace-alert/recent`、`/profileData2`、`/profile`、`/longTimeTask`,未新造任何接口;无插件提示契约(plugin=absent 的 hint map)在页面显示提示而非报错。
  - 六页内容:统计(trace 缓存表,点击展开 span 时间线/明细,含 enableLogfileReporter 运行态)、JVM(CPU/堆/线程折线图 + 最新采样卡片:内存/内存池/GC/线程状态/类加载)、meter(指标卡片网格 + 名称过滤)、实例属性(键值表)、告警(运行指标卡片 + 规则命中表 + webhook 收讫事件表)、profile(采样触发/压测按钮 + 带方法栈快照列表)。
  - 实测(agent + 插件):17/17 断言全绿——静态资源与六页 200、五流数据齐全、SLOW/ERROR 收讫事件、profile 快照含 60 层方法栈。
  - **关键实现发现**:JVM 指标为"批次上报"(每批 1~2 个采样点,缓存条数即批次数,实测 177+ 批),折线图须跨批次合并为时间序列,取最近 120 点绘制,而非仅用最后一批。
  - 契约勘误:之前误以为 `/statisticJVM`、`/profileData2` 返回 `{value:[...]}` 包装,实为 PowerShell 管道单元素展开假象;原始响应均为裸数组(已在页面与验证脚本按裸数组处理)。
