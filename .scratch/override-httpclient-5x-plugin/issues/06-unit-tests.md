# 06 单元测试（镜像 4.x）

Status: done
Type: task
Blocked by: 02

## 目标

用与 4.x 同构的收集器单测锁定 5.x 采集边界。

## 范围

- `HttpClientParamCollectorTest`，镜像 4.x 用例（换 5.x 类型）：
  - `shouldCollectQueryAndFormBody`
  - `shouldCollectMultipartFileSizeAndTextPart`
  - `shouldClipLargeBodyValues`
  - `shouldCollectOnlyQueryWhenOnlyOfficialSwitchIsTurnedOn`
  - `shouldCollectOnlyBodyWhenOnlyOverrideSwitchIsTurnedOn`
  - 开关/运行时开关用例（`shouldReportOfficialCollectionSwitch...`、`shouldEnableOnlyOverrideCollection...`）
- `HttpClientResponseCollectorTest`，镜像 4.x。
- `OverrideHttpClient5PluginConfigTest`，镜像 4.x。
- 测试 request 用 5.x 类型（如 `BasicClassicHttpRequest` / `HttpPost`，`MultipartEntityBuilder` 用 client5 对应类）。
- `@After` 还原三个静态配置值，避免用例互相污染。

## 验收

- `mvn -q -pl override-httpclient-5.x-plugin -am clean test` 全绿。
- 用例只断言收集器产出的 tag 串与开关分叉，不断言拦截器内部。
