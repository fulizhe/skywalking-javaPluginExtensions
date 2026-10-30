# 03 同步拦截器 + instrumentation + plugin.def

Status: done
Type: task
Blocked by: 01, 02

## 目标

为两个同步入口挂上 tracing + 采集拦截器，并注册插件定义。

## 范围

- `HttpClientExecuteInterceptor`（InstanceMethodsAroundInterceptor）：
  - `beforeMethod`：保持官方 tracing 行为（exit span、operation=requestURI、`Tags.URL`/`Tags.HTTP.METHOD`、`SpanLayer.asHttp`、注入 context carrier header），并对 `allArguments[0]`=HttpHost、`allArguments[1]`=ClassicHttpRequest 做空判。
  - 采集调用：`official || override` 时调用 `HttpClientParamCollector`，把 params 写入 `Tags.HTTP.PARAMS` + `http.request.params`，files 写入 `http.request.files`。
  - `afterMethod`：用 `ClassicHttpResponse.getCode()` 写 `http.response.status_code`，>=400 标错；调用 `HttpClientResponseCollector` 写 `http.response.body`；`ContextManager.stopSpan()`。
  - `handleMethodException`：记录日志 + `span.log(t)`。
  - profiling fallback：两开关都关但 `span.isProfiling()` 时补采请求参数（与 4.x 一致）。
- `define/` 下两个 instrumentation：
  - `InternalHttpClientInstrumentation` → `org.apache.hc.client5.http.impl.classic.InternalHttpClient.doExecute`
  - `MinimalHttpClientInstrumentation` → `org.apache.hc.client5.http.impl.classic.MinimalHttpClient.doExecute`
- `src/main/resources/skywalking-plugin.def` 注册上述两条（命名空间 `org.apache.skywalking.apm.plugin.override.httpclient.v5.*`）。

## 验收

- `mvn -pl override-httpclient-5.x-plugin -am clean test` 通过。
- 两个 instrumentation 的 `enhanceClass` 命中正确 FQCN、`getMethodsMatcher` 命中 `doExecute`。
- `skywalking-plugin.def` 含两条 sync 条目。
