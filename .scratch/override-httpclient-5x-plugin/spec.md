# Spec: override-httpclient-5.x-plugin

Status: ready-for-agent

## Problem Statement

仓库已有 `override-httpclient-4.x-plugin`，在保留官方 Apache HttpClient 4.x 追踪行为的基础上为同步出口补充了 request 参数（query string）+ 请求体/表单/multipart 文件元数据 + 响应体的采集，并配了一套独立采集开关与运行时开关。

Apache HttpClient 5.x 是同一类能力的另一条客户端线（包名 `org.apache.hc.*`，官方 `apm-httpclient-5.x-plugin` 只做 tracing、不采任何 request/response 数据）。做业务参数的"出参入参"观察时，4.x 用户在 5.x 客户端上同样需要这套采集能力，但目前没有。

目标：实现 `override-httpclient-5.x-plugin`，对官方 `apm-httpclient-5.x-plugin` 做全量替代 —— 同步路径（`InternalHttpClient` / `MinimalHttpClient` 的 `doExecute`）加 4.x 同等的入参/出参采集，异步路径（`HttpAsyncClient` / `IOSessionImpl`）保持官方原样、不加采集，使移除官方 jar 后异步追踪能力不丢失。

## Solution

- 新增 Maven 模块 `override-httpclient-5.x-plugin`，编译依赖 `org.apache.httpcomponents.client5:httpclient5:5.0`（provided，与官方 9.4.0 插件一致），产物 `override-apm-httpclient-5.x-plugin-9.4.0.jar`。
- 增强矩阵 = 官方 5.x 插件的全部 4 个入口：
  - `org.apache.hc.client5.http.impl.classic.InternalHttpClient.doExecute` —— 同步，加采集
  - `org.apache.hc.client5.http.impl.classic.MinimalHttpClient.doExecute` —— 同步，加采集
  - `HttpAsyncClient`（异步）+ `IOSessionImpl`（异步）—— 官方拦截器/包装器**原样复制、不改**，纯保留 tracing
- 采集范围与 4.x override 对齐：
  - query string 走官方开关 `plugin.httpclient.collect_http_params`
  - 请求体 / 表单 / multipart 文件元数据 / 响应体走 override 开关 `plugin.overridehttpclient5.collect_http_params`
  - 阈值共用官方 `plugin.http.http_params_length_threshold`
  - 响应体沿用 repeatable-only 规则，非 repeatable 跳过，避免消费业务响应流
- 提供独立运行时开关工具类 `org.apache.skywalking.apm.toolkit.SWHttpClient5CollectUtils`（`enableCollect` / `disableCollect` / `statisticStatus`），与 4.x 的 `SWHttpClientCollectUtils` 类名不同，避免两个 override 抢同一被增强类。
- 收集逻辑优先用 5.x 公开 API（`ClassicHttpRequest.getEntity()`、multipart `Part`/内容体公开的 `getFilename()`/`getContentLength()`/`getMimeType()`、`EntityUtils.toString(entity, charset)`），反射仅兜底。
- 集成：注册进 `agent/pom.xml` modules、`build-export-skywalking-override-plugins.bat` 加一步（移走官方 `apm-httpclient-5.x-plugin-*.jar`）、demo-app 新增 httpclient5 出口端点 + `<httpclient5.version>` 矩阵属性 + `SWHttpClient5CollectUtils` 桩、`verify/scenarios/` 新增 `override-httpclient5x` 场景（含 5.x 版本矩阵）。

## User Stories

1. As a 后端开发, I want HttpClient 5.x 同步请求的 query string 入参能被采集, so that 我能在调用链路上看到 URL 查询参数。
2. As a 后端开发, I want HttpClient 5.x 同步请求的 `application/x-www-form-urlencoded` 请求体能被采集, so that 我能看到表单提交的内容。
3. As a 后端开发, I want HttpClient 5.x 同步请求的可重复文本体能被采集(如 `StringEntity`), so that 我能看到 JSON/文本请求载荷。
4. As a 后端开发, I want HttpClient 5.x 同步请求的 multipart 文本字段能被采集, so that 我能看到 multipart 里的普通字段。
5. As a 后端开发, I want HttpClient 5.x 同步请求的 multipart 文件项能记录 filename/size/contentType 元数据, so that 我能知道上传了哪个文件、多大、什么类型,而不需要读取文件流。
6. As a 后端开发, I want 大体积/超阈值的数据值能被按阈值截断, so that 采集的 span tag 不会撑爆。
7. As a 后端开发, I want 并行启用官方 query 开关与 override 请求体开关时两者同时采集, so that 出参维度完整。
8. As a 后端开发, I want 只开官方 query 开关时仅采 query、只开 override 开关时仅采请求体/响应体, so that 采集粒度可独立控制。
9. As a 后端开发, I want 响应体只在实体可重复时被采集, so that 消费不可重复响应流不会破坏业务逻辑。
10. As a 后端开发, I want 通过 `SWHttpClient5CollectUtils.enableCollect/disableCollect` 运行时开关请求体采集, so that 不用重启应用即可调整采集粒度。
11. As a 后端开发, I want `SWHttpClient5CollectUtils.statisticStatus()` 返回当前开关状态全部貌, so that 我能实时确认采集状态。
12. As a 后端开发, I want 异步 HttpClient 5.x 请求(HttAsyncClient/IOSessionImpl)保持官方追踪行为不变, so that 移除官方 jar 后异步链路不会消失。
13. As a 后端开发, I want 异步路径不因 override 而增加采集, so that 异步性能与行为保持官方原样。
14. As a 运维, I want 部署时能移走官方 `apm-httpclient-5.x-plugin-*.jar` 以避免同步路径双重增强, so that 同一调用不会被打两遍。
15. As a 后端开发, I want 4.x override 与 5.x override 可同时装载而不冲突, so that 一条链上既有 4.x 又有 5.x 出口都能被采集。
16. As a 插件作者, I want 5.x override 有与 4.x 同构的收集器单元测试, so that 采集边界在 CI 上被验证。
17. As a 插件作者, I want demo-app 提供 httpclient5 出口端点和运行时开关读口, so that 我可以一键启动验证回路亲手看到采集效果。
18. As a 插件作者, I want verify 场景对多个 5.x 版本跑增强矩阵, so that 能证明跨 5.x 版本增强可用。
19. As a 插件作者, I want 构建/导出脚本能一键构建 5.x override 并把官方 jar 挪到 optional-plugins, so that 部署步骤可重复、可审计。

## Implementation Decisions

- **新模块命名**: `override-httpclient-5.x-plugin`，产物 `override-apm-httpclient-5.x-plugin-${skywalking.version}.jar`。Java 包根 `org.apache.skywalking.apm.plugin.override.httpclient.v5`。
- **编译依赖**: `org.apache.httpcomponents.client5:httpclient5:5.0`，scope `provided`，与官方 9.4.0 插件一致，规避官方 bean 的编译时 API 不一致。另依赖 `apm-httpclient-commons`（provided）以复用 `HttpClientPluginConfig`。
- **全量替代官方**: 官方 5.x jar 是原子的，不能只拆同步部分；为避免同步路径双重增强，部署/验证时移走整个官方 `apm-httpclient-5.x-plugin-*.jar`，由 override 的 4 个增强入口补齐能力。这同时意味着异步追踪由 override 的**原样副本**延续。
- **异步原样保留**: `HttpAsyncClientDoExecuteInterceptor`、`IOSessionImplPollInterceptor`、`AsyncResponseConsumerWrapper`、`FutureCallbackWrapper` 及对应的 instrumentation（`HttpAsyncClientInstrumentation`、`IOSessionImplInstrumentation`）按官方源码逐字复制，**不做任何改动**——不在异步路径加采集。这些类与同步拦截器无继承耦合，可独立照搬。代码中需在版权头下方加注释说明"与官方同源、未修改、保持原样，理由见 README"。
- **同步采集入口**: `InternalHttpClient.doExecute` 与 `MinimalHttpClient.doExecute` 两处都挂同一套 param/entity/multipart/response 采集。两入口 `doExecute` 签名均为 `(HttpHost, ClassicHttpRequest, HttpContext)`，参数位与 4.x 相同（`allArguments[0]`=HttpHost，`allArguments[1]`=请求）。
- **采集范围划分**: query 走官方开关 `plugin.httpclient.collect_http_params`；请求体/表单/multipart 文件/响应体走 override 开关 `plugin.overridehttpclient5.collect_http_params`；阈值共用 `plugin.http.http_params_length_threshold`（复用 commons `HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS` 与 `Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD`）。
- **独立配置类**: `OverrideHttpClient5PluginConfig`，`@PluginConfig(root=...).Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS=false`，生成配置 key `plugin.overridehttpclient5.collect_http_params`，与 4.x 的 `overridehttpclient` 区分。
- **独立运行时开关**: `org.apache.skywalking.apm.toolkit.SWHttpClient5CollectUtils`（`enableCollect(Map)` / `disableCollect(Map)` / `statisticStatus()`，桩实现返回 FALSE/空 map），由归 override 的静态方法拦截器按类名增强。类名与 4.x 不同，避免两个 override 抢同一被增强类。
- **收集器实现**: 优先 5.x 公开 API、少反射。请求体通过 `ClassicHttpRequest.getEntity()` 取实体；按 contentType 分流 form / multipart / 已知实体 / 通用实体；multipart 通过公开的内容体类方法读 filename/size/mimeType 与文本字段；文本读取用 `EntityUtils.toString(entity, charset)`（对可重复实体）。**不读**不具可重复性的实体/响应流。反射仅在个别 5.x 无公开 getter 的字段处兜底。
- **增强声明**: `skywalking-plugin.def` 内注册四项 instrumentation（Internal/Minimal/HttpAsync/IOSessionImpl），命名空间 `org.apache.skywalking.apm.plugin.override.httpclient.v5.*`。
- **复用响应实体规则**: 响应体仅在 `isRepeatable()=true` 时采集；对 `DecompressingEntity`/代理/非重复包装类跳过。README 沿用 4.x 的"业务侧把响应转成可重复实体才采"的取值范围。

## Testing Decisions

- **何为好测试**: 只测外部可见行为（收集器对给定 request/response 产出正确的 tag 串、开关切换后的采集分叉、截断边界），不测拦截器与 bytebuddy 内部接线。用同一官方开关与 override 开关的组合驱动采集分叉。
- **测试模块**: `override-httpclient-5.x-plugin` 内的单元测试（编译依赖 httpclient5 + apm-httpclient-commons + junit）。
- **先行用例 (prior art)**: 直接镜像 4.x 的 `HttpClientParamCollectorTest` —— `shouldCollectQueryAndFormBody`、`shouldCollectMultipartFileSizeAndTextPart`、`shouldClipLargeBodyValues`、开关组合用例(`shouldReportOfficialCollectionSwitchWhenOfficialSwitchIsTurnedOn` 等) —— 把 4.x 的 `HttpPost`/`MultipartEntityBuilder` 换成 5.x 的 `BasicClassicHttpRequest`/`MultipartEntityBuilder`(core5 或 client5 对应类)。响应收集器测试镜像 `HttpClientResponseCollectorTest`。配置文件测试镜像 `OverrideHttpClientPluginConfigTest`。
- **集成 seam**: `verify/scenarios/override-httpclient5x` 的 `checks.sh` 沿用 `override-httpclient` 的断言模式(先断言开关读口 `.raw.overrideCollectHttpParams`、再断言 httpclient5 出口 span 带 `http.request.params` tag 且含表单体值)；读取面使用 demo-app 新增的 httpclient5 collect 读口与 logfile-reporter 的 `/statistic`。demo-app 断言与 4.x 场景保持同构。

## Out of Scope

- **异步路径采集**: 异步(HttpAsyncClient/IOSessionImpl)只保留官方 tracing，不加任何入参/出参采集。后续如需异步采集，单独开票。
- **同时保留官方同步增强**: 为避免双重增强，官方 `apm-httpclient-5.x-plugin-*.jar` 一律移走；"保留官方 jar 只用其同步部分"不可行(jar 原子性)。
- **shared 公共收集器**: 4.x 与 5.x 收集器不抽公共模块，各自自包含(遵循仓库现有多 override 插件的自包含模式)。
- **asynchttpclient/non-apache 客户端**: 不在本 spec 范围。
- **README 的 4.x 全套迁移**: 只建最小 README(采集开关模型 + 构建命令 + 异步原样保留说明)，不复制 4.x 的响应体长文与 runtime 控制长文。

## Further Notes

- **已浮现的后果**: 移走官方 5.x jar 后异步追踪由 override 的原样副本延续——因此副本必须保持一致，任何对官方异步源码的漂移都会静默丢异步链路。代码注释与 README 需明示"异步四件套与官方同源、未修改"。
- 4.x override(`SWHttpClientCollectUtils`, 增强 4.x 类)与 5.x override(`SWHttpClient5CollectUtils`, 增强 5.x 类)类名与增强面都不重叠，可同 agent 共存。
- `adr-01`(JDK17 迁移)适用: 新模块沿用 `<release>8</release>` 字节码基线 + JDK 17 工具链。
- `demo-app` 是 Sample & validation 模块(不在 agent reactor)，其新增端点/矩阵属性/桩类只服务验证回路，不牵动插件 reactor 构建。