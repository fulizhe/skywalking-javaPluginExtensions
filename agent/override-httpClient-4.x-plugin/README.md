# override-httpClient-4.x-plugin

This module overrides SkyWalking 9.4.0 built-in `apm-httpClient-4.x-plugin`
while keeping the original tracing behavior and enhancing request parameter
collection.

## What it collects

- Reuses the official switch:
  `plugin.httpclient.collect_http_params=true|false`
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

## Deployment note

It is recommended to remove or rename the official
`apm-httpClient-4.x-plugin-9.4.0.jar` before deployment, otherwise the same
call may be enhanced twice.

## Build commands

Run tests:

```bash
mvn -q -pl override-httpClient-4.x-plugin -am clean test
```

Build final artifact:

```bash
mvn -q -pl override-httpClient-4.x-plugin -am package -DskipTests
```

Final artifact path:

```text
override-httpClient-4.x-plugin/target/overide-apm-httpClient-4.x-plugin-9.4.0.jar
```
