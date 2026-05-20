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
- `httpParamsLengthThreshold`

Note:

- the application should include the same toolkit class
  `org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils`
- the agent plugin intercepts these methods at runtime
- runtime enable/disable updates
  `plugin.overridehttpclient.collect_http_params`
- runtime enable/disable affects body collection only

## Override config

Dedicated config item (SkyWalking maps
`OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS`):

| Purpose | Config key | Default |
|---------|------------|---------|
| Query string collection (official) | `plugin.httpclient.collect_http_params` | `false` |
| Request body / multipart collection (override) | `plugin.overridehttpclient.collect_http_params` | `false` |
| Clip length (official, shared) | `plugin.http.http_params_length_threshold` | `1024` |

### agent.config example

Use keys **without** the `skywalking.` prefix inside `config/agent.config`:

```properties
# Query only
plugin.httpclient.collect_http_params=true
plugin.overridehttpclient.collect_http_params=false

# Body only
# plugin.httpclient.collect_http_params=false
# plugin.overridehttpclient.collect_http_params=true

# Both
# plugin.httpclient.collect_http_params=true
# plugin.overridehttpclient.collect_http_params=true

plugin.http.http_params_length_threshold=1024
```

### JVM / system property example

Prefix with `skywalking.` when using `-D` or environment variables:

```shell
-Dskywalking.plugin.httpclient.collect_http_params=true
-Dskywalking.plugin.overridehttpclient.collect_http_params=true
-Dskywalking.plugin.http.http_params_length_threshold=1024
```

## Deployment note

It is recommended to remove or rename the official
`apm-httpClient-4.x-plugin-9.4.0.jar` before deployment, otherwise the same
call may be enhanced twice.
