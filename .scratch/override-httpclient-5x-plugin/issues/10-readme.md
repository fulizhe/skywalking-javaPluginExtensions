# 10 最小 README

Status: done
Type: task
Blocked by: 03, 04

## 目标

给新模块一份最小 README，开头三行内让读者能动手（AGENTS.md 写作规则）。

## 范围

- 新建 `agent/override-httpclient-5.x-plugin/README.md`：
  - 开头：一句话说明「全量替代官方 apm-httpclient-5.x-plugin；同步加采集、异步原样保留」，紧跟构建命令代码块。
  - 采集开关模型：官方 query 开关 / override 开关 / 共享阈值（表格）。
  - 运行时开关：`SWHttpClient5CollectUtils` 三个方法与状态键。
  - 部署注意：移走官方 `apm-httpclient-5.x-plugin-*.jar`，否则同步双重增强。
  - 异步说明：四个异步文件与官方同源、未修改、保持原样（说明原因：移除官方 jar 后由副本延续异步 tracing）。
  - 响应体采集边界：repeatable-only，简要指回可选做法（不复制 4.x 长文）。
- 不复制 4.x 的 README-TODO / README-compile。

## 验收

- README 首屏含构建命令，读者无需滚动即可动手。
- 明确写出「异步未修改 + 理由」与「移走官方 jar」两条。
