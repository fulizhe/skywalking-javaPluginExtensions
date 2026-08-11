# 07 — 能力导览首页与 README

**What to build:** 面向新读者的能力导览首页与 README:讲清插件是什么(本地内存报告 vs OAP 模式、六类数据流地图)、logfile 命名来历、怎么跑(一条命令)——兑现"他人 5 分钟建立认知"。

**Blocked by:** 06 — 静态仪表盘移植

**Status:** done

- [x] 首页包含三块叙事:插件是什么(六类数据流地图 + 与 OAP 模式对比)、logfile 命名来历、怎么跑
- [x] README 覆盖快速上手(setup 或手动)、验证回路用法、术语指引
- [x] 首页可链接到全部仪表盘与验证入口

实现:
- 导览首页 `src/main/resources/static/index.html`:三块叙事(OAP vs 本地内存报告对照表 + 六类数据流地图,每类带统计读口与仪表盘链接;logfile 命名来历;一条命令怎么跑)。与 dashboard.css 同风格,链接全部仪表盘与验证入口。
- `/` 路由:删除 `HomeController`(原返回纯文本),改由 Spring 静态欢迎页直接 200 返回 `static/index.html`——不能做成 302 重定向,就绪探测与断言 C 只认 200。
- `README.md`(demo-app 目录):快速上手(方式 A 一键验证 / B 手动调试 / C 纯应用启动)、验证回路用法(断言范围、告警端点、退出码)、术语指引表、目录结构、工单索引。
- 注意:setup 脚本(工单 08)未落地,README 快速上手当前按"本机已有 agent 9.4.0"书写,08 落地后需回填 setup 小节。
- 遗留坑(已处理,2026-08-10):validate.ps1 只在 jar 缺失时重建 demo-app,源码变更后直接跑会复用旧 jar。已修复为默认每次重建(`mvn clean package`),快速路径改为显式 `-SkipAppBuild`;run-with-agent.ps1 与 setup.ps1 同款逻辑一并修复,README 参数表同步更新。
