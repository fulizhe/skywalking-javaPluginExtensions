# 02 同步采集器（param / entity / multipart / response）

Status: done
Type: task
Blocked by: 01

## 目标

实现 5.x 版的请求参数收集器与响应体收集器，行为对齐 4.x override，实现上优先 5.x 公开 API。

## 范围

- `HttpClientParamCollector`（包内可见）：
  - `collect(ClassicHttpRequest)` → 返回 `CollectedTags{params, files}`（结构同 4.x）
  - query：官方开关开启时采 `query=<...>`
  - 请求体：override 开关开启时按 contentType 分流
    - `application/x-www-form-urlencoded` → `form=...`
    - multipart（`org.apache.hc.client5.http.entity.mime.*` / `MultipartFormEntity`）→ 文本字段进 params，文件项进 files，记录 `filename/size/contentType`
    - 已知实体（`StringEntity`/`ByteArrayEntity`/`InputStreamEntity`/`FileEntity` 等 core5 对应类）→ 文本或文件元数据
    - 通用实体 → 可重复则读文本，否则记 `body_meta`
  - 文本读取用 `EntityUtils.toString(entity, charset)`（仅可重复实体）
  - 阈值裁剪复用 `HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD`
- `HttpClientResponseCollector`：
  - 仅 override 开关开启且实体 `isRepeatable()` 时采
  - 仅允许 JSON / `+json` / text-plain 类 contentType
  - 非重复实体/流/压缩包装跳过并 debug 日志
- 常量：`http.request.params`、`http.request.files`、`http.response.body`（与 4.x 同名 tag key）。

## 验收

- 收集器对 form 请求体同时产出 query 与 form 条目。
- multipart 同时产出文本字段与文件元数据（filename/size/contentType）。
- 超阈值值被裁剪到阈值长度。
- 非重复响应实体不消费流、返回 null。
