# 05 运行时开关拦截器 + 静态增强

Status: superseded（已改为复用 4.x 的开关，见 spec）
Type: task
Blocked by: 01

## 目标（已被后续决定取代）

原计划让 5.x 自带 `SWHttpClient5CollectUtils` 的静态增强。实施后改为**复用 4.x 的开关**：
`SWHttpClientCollectUtils` 只由 4.x 插件增强，5.x 只读共享的 `OverrideHttpClientPluginConfig`。
这样两个插件同装时不会出现外层拦截器压住内层、运行时开关只生效一个的问题（与 hutool 同模式）。

## 范围（已删除）

- 三个 `StaticMethodsAroundInterceptor`：已删除。
- `define/HttpClientParamRuntimeInstrumentation`：已删除。
- `skywalking-plugin.def`：不注册运行时增强。

## 硬约束

- 只切换 override 开关，不动官方 `plugin.httpclient.collect_http_params`。
- 状态 map 键与 4.x 同名（`officialCollectHttpParams` / `overrideCollectHttpParams` / `httpParamsLengthThreshold`）。

## 验收

- `statisticStatus()` 经拦截器返回真实状态而非桩空 map。
- enable/disable 后 `isOverrideCollectEnabled()` 随之变化。
- 编译通过。
