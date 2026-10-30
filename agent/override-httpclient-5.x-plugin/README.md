# override-httpclient-5.x-plugin

全量替代 SkyWalking 9.4.0 官方 `apm-httpclient-5.x-plugin`:同步路径(`InternalHttpClient` / `MinimalHttpClient`)保留 tracing 并补充入参/出参采集;异步路径(`HttpAsyncClient` / `IOSessionImpl`)原样保留、不加采集。

```mermaid
flowchart TD
    A[HttpClient 5.x 调用] --> B{同步 / 异步}
    B -->|同步| C[InternalHttpClient / MinimalHttpClient.doExecute]
    C --> D[官方 tracing + query / 请求体 / multipart / 响应体采集]
    B -->|异步| E[HttpAsyncClient.doExecute / IOSessionImpl.poll]
    E --> F[官方 tracing 原样保留, 不加采集]
```

## 构建

```bash
mvn -q -pl override-httpclient-5.x-plugin -am clean test
mvn -q -pl override-httpclient-5.x-plugin -am package -DskipTests
```

产物:`override-httpclient-5.x-plugin/target/override-apm-httpclient-5.x-plugin-9.4.0.jar`

也可用仓库级脚本一次构建并导出三个 override 插件:

```bat
E:\gitRepository\_skywalking-javaPluginExtensions\agent\build-export-skywalking-override-plugins.bat
```

## 采集开关模型

复用 4.x override 的开关(同一个 key、同一个运行时入口),采集职责拆分:

| 用途 | 配置 key | 默认 |
|------|----------|------|
| query string(官方) | `plugin.httpclient.collect_http_params` | `false` |
| 请求体 / 表单 / multipart / 响应体(override) | `plugin.overridehttpclient.collect_http_params` | `false` |
| 裁剪长度(官方,共用) | `plugin.http.http_params_length_threshold` | `1024` |

- `application/x-www-form-urlencoded` 采为 `form=...`
- 请求体优先经 `HttpEntity.writeTo(OutputStream)` 读取(仅可重复实体),兼容 Spring `BodyEntity` 这类 `getContent()` 会抛异常、只能 `writeTo` 的包装实体
- multipart 文本字段进 `http.request.params`,文件项记 `filename / size / contentType` 进 `http.request.files`
- 响应体只在实体可重复时采集(repeatable-only);不可重复的流/解压包装跳过,避免消费业务响应流

## 运行时开关

与 `override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin` **共用**同一个宿主工具类:

```java
org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils
```

```java
SWHttpClientCollectUtils.enableCollect(Collections.emptyMap());
SWHttpClientCollectUtils.disableCollect(Collections.emptyMap());
SWHttpClientCollectUtils.statisticStatus();
```

状态 map 键:`officialCollectHttpParams` / `overrideCollectHttpParams` / `httpParamsLengthThreshold`。
运行时开关只改 override 开关(请求体/响应体),不动官方 query 开关。

开关的拥有者是 4.x 插件:本插件**不注册**自己的 `HttpClientParamRuntimeInstrumentation`,
只读共享的 `OverrideHttpClientPluginConfig`。这样同一个被增强类只有一个增强者,
两个插件同时装载时不会出现外层拦截器压住内层、运行时开关只生效一个的问题。

## 部署注意

部署前移走官方 `apm-httpclient-5.x-plugin-*.jar`(脚本会自动挪到 `optional-plugins/`),否则同步路径会被官方与本插件双重增强。

本插件**必须与 `override-httpclient-4.x-plugin` 一起部署**:开关配置类与 `SWHttpClientCollectUtils`
的增强都由 4.x 插件提供,单独安装 5.x jar 会在加载开关时缺类。两个插件增强的业务类不同
(4.x 是 `org.apache.http.*`,5.x 是 `org.apache.hc.*`),可以同 agent 共存。

## 异步路径为什么"未修改"

本插件整包替代官方插件,必须自带异步增强,否则移除官方 jar 后异步调用将完全失去链路。因此 `HttpAsyncClientDoExecuteInterceptor`、`IOSessionImplPollInterceptor`、`AsyncResponseConsumerWrapper`、`FutureCallbackWrapper` 及两个 define 均**逐字复制自官方 v9.4.0、保持原样**,异步路径不加任何采集。请勿在未同步上游的情况下改动这些文件。
