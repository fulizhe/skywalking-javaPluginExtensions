# 01 模块骨架 + parent 注册 + 配置/开关/toolkit

Status: done
Type: task

## 目标

建立 `override-httpclient-5.x-plugin` 模块的可编译骨架，并注册进 agent reactor。

## 范围

- 新建 `agent/override-httpclient-5.x-plugin/pom.xml`：
  - parent `org.openskywalking:java-plugin-extensions:1.0.0`
  - `artifactId` = `override-httpclient-5.x-plugin`
  - provided 依赖 `org.apache.httpcomponents.client5:httpclient5:5.0`、`apm-httpclient-commons:${skywalking.version}`
  - test 依赖 junit 4.12、`apm-agent-core`（与 4.x 模块一致）
  - `<finalName>override-apm-httpclient-5.x-plugin-${skywalking.version}</finalName>`
- 在 `agent/pom.xml` 的 `<modules>` 注册新模块。
- 新建包根 `org.apache.skywalking.apm.plugin.override.httpclient.v5`，落地：
  - 采集开关聚合类（等价 4.x `HttpClientCollectionSwitch`）：`isOfficialCollectEnabled` / `isOverrideCollectEnabled`，读 4.x 的 `OverrideHttpClientPluginConfig`（key `plugin.overridehttpclient.collect_http_params`）
  - **不新增** 5.x 配置类与 toolkit 桩：复用 4.x 的 `OverrideHttpClientPluginConfig` 与 `org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils`
- 130 行内的 license 头与现有模块保持一致。

## 验收

- `mvn -q -pl override-httpclient-5.x-plugin -am clean test` 通过（此阶段可无测试）。
- 生成的 class 处于 `release 8` 字节码基线（adr-01）。
- `currentStatus()` 返回 `officialCollectHttpParams` / `overrideCollectHttpParams` / `httpParamsLengthThreshold` 三个键。
