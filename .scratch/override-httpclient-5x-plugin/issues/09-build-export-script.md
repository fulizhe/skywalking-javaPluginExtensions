# 09 构建/导出脚本集成

Status: done（脚本已改；未在装有 agent 的目录执行）
Type: task
Blocked by: 01

## 目标

让 `build-export-skywalking-override-plugins.bat` 一键构建 5.x override，并把官方 5.x jar 挪到 optional-plugins。

## 范围

- 在 `agent/build-export-skywalking-override-plugins.bat` 增加一组常量与步骤：
  - `HTTPCLIENT5_MODULE=override-httpclient-5.x-plugin`
  - `HTTPCLIENT5_JAR=override-apm-httpclient-5.x-plugin-9.4.0.jar`
  - `HTTPCLIENT5_OFFICIAL_JAR=apm-httpclient-5.x-plugin-9.4.0.jar`
  - 新增 build 步骤 + export 步骤（复用 `:build_module` / `:export_plugin`）
- 同步更新脚本末尾 summary（步骤编号、部署 jar 扫描、日志 grep pattern 加 5.x）。

## 验收

- 脚本 dry-run（无 agent 目录时）能给出清晰 pre-check 失败信息。
- 有 agent 目录时：构建出 5.x jar、复制进 plugins、官方 5.x jar 移入 optional-plugins。
- summary 正确列出 3 个 override jar。
