# override-httpClient-4.x-plugin

This module overrides SkyWalking 9.4.0 built-in `apm-httpClient-4.x-plugin`
while keeping the original tracing behavior and enhancing request parameter
collection.

## Collection switch model

This plugin keeps SkyWalking official config behavior unchanged and adds an
override config entry of its own.

Collection responsibility is split:

- official switch `plugin.httpclient.collect_http_params=true`
  controls query-string parameter collection
- override switch `plugin.overridehttpclient.collect_http_params=true`
  controls request body and multipart/file metadata collection

Runtime enable/disable through `SWHttpClientCollectUtils` updates the override
switch only.

## What it collects

- Uses the official switch for query collection:
  `plugin.httpclient.collect_http_params=true|false`
- Uses the override switch for body collection:
  `plugin.overridehttpclient.collect_http_params=true|false`
- Reuses the official threshold:
  `plugin.http.http_params_length_threshold`
- Collects query string
- Collects `application/x-www-form-urlencoded`
- Collects repeatable plain request body when it can be read safely
- Collects `multipart/form-data`
- Records file part metadata as `filename / size / contentType`
- Clips oversized values with the same threshold rule

## Body and file together

If the request is `multipart/form-data`, text fields and file fields are both
collected in the same request.

Example:

- text field `remark=upload-demo`
- file field `file={filename=a.txt,size=123,contentType=text/plain}`

So when a user submits normal body fields together with uploaded files, both
kinds of data are preserved.

## Runtime control

This module provides a runtime control entry modeled after the dynamic control
pattern used elsewhere in this repository.

Toolkit class:

```java
org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils
```

Available methods:

```java
SWHttpClientCollectUtils.enableCollect(Map<String, Object> config)
SWHttpClientCollectUtils.disableCollect(Map<String, Object> config)
SWHttpClientCollectUtils.statisticStatus()
```

Suggested usage:

```java
import org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils;

Map<String, Object> result1 = (Map<String, Object>) SWHttpClientCollectUtils.enableCollect(Collections.emptyMap());
Map<String, Object> result2 = (Map<String, Object>) SWHttpClientCollectUtils.statisticStatus();
Map<String, Object> result3 = (Map<String, Object>) SWHttpClientCollectUtils.disableCollect(Collections.emptyMap());
```

Status response includes:

- `officialCollectHttpParams`
- `overrideCollectHttpParams`
- `effectiveCollectQueryParams`
- `effectiveCollectBodyParams`
- `effectiveCollectHttpParams`
- `httpParamsLengthThreshold`

Note:

- the application should include the same toolkit class
  `org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils`
- the agent plugin intercepts these methods at runtime
- runtime enable/disable updates
  `plugin.overridehttpclient.collect_http_params`
- runtime enable/disable affects body collection only

## Override config

New dedicated config item:

```text
skywalking.plugin.overridehttpclient.collect_http_params=true|false
```

Default:

```text
false
```

## Deployment note

It is recommended to remove or rename the official
`apm-httpClient-4.x-plugin-9.4.0.jar` before deployment, otherwise the same
call may be enhanced twice.
