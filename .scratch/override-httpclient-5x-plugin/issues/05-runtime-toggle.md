# 05 运行时开关拦截器 + 静态增强

Status: done
Type: task
Blocked by: 01

## 目标

让应用侧 `SWHttpClient5CollectUtils` 的静态方法在运行时被 agent 接管，用于开关请求体采集与查询状态。

## 范围

- 三个 `StaticMethodsAroundInterceptor`（镜像 4.x）：
  - `HttpClientCollectEnableRuntimeInterceptor` → `toggleRuntimeCollect(true)` 并 `defineReturnValue(currentStatus())`
  - `HttpClientCollectDisableRuntimeInterceptor` → `toggleRuntimeCollect(false)`
  - `HttpClientCollectStatusExposeInterceptor` → `defineReturnValue(currentStatus())`
- `define/HttpClientParamRuntimeInstrumentation`（`ClassStaticMethodsEnhancePluginDefine`）：
  - enhanceClass = `org.apache.skywalking.apm.toolkit.SWHttpClient5CollectUtils`
  - 匹配 `enableCollect(Map)` / `disableCollect(Map)` / `statisticStatus()`
- `skywalking-plugin.def` 追加该条注册。

## 硬约束

- 只切换 override 开关，不动官方 `plugin.httpclient.collect_http_params`。
- 状态 map 键与 4.x 同名（`officialCollectHttpParams` / `overrideCollectHttpParams` / `httpParamsLengthThreshold`）。

## 验收

- `statisticStatus()` 经拦截器返回真实状态而非桩空 map。
- enable/disable 后 `isOverrideCollectEnabled()` 随之变化。
- 编译通过。
