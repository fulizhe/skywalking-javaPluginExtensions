# override-hutool-http-5.x-plugin

This module overrides the official SkyWalking `hutool-http-5.x-plugin`
behavior and adds request parameter collection for Hutool HttpRequest.

## Collection switch model

This module reuses the same collection model as
`override-httpclient-4.x-plugin`.

- official switch `plugin.httpclient.collect_http_params=true`
  controls query-string collection
- shared override switch `plugin.overridehttpclient.collect_http_params=true`
  controls request body and multipart/file metadata collection

The override switch is shared with `override-httpclient-4.x-plugin`, including
the same runtime control entry.

## What it collects

- query string
- `application/x-www-form-urlencoded` form body
- plain request body from Hutool `body(...)`
- `multipart/form-data` text fields
- multipart file metadata as `filename / size / contentType`
- clipping by `plugin.http.http_params_length_threshold`

## Shared runtime control

Use the same toolkit entry as `override-httpclient-4.x-plugin`:

```java
org.apache.skywalking.apm.toolkit.SWHttpClientCollectUtils
```

Status fields follow the same layout:

- `officialCollectHttpParams`
- `overrideCollectHttpParams`
- `httpParamsLengthThreshold`

## Deployment note

Use this plugin together with `override-httpclient-4.x-plugin` when you want
both Apache HttpClient 4.x and Hutool HTTP to share the same body collection
switch.
