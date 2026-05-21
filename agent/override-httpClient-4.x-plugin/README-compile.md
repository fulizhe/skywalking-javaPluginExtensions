# override-httpClient-4.x-plugin

This module overrides SkyWalking 9.4.0 built-in `apm-httpClient-4.x-plugin`
while keeping the original tracing behavior and enhancing request parameter
collection.

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
override-httpClient-4.x-plugin/target/override-apm-httpclient-4.x-plugin-9.4.0.jar
```

## Recommended compile/export flow

Use the repo-level batch script to build and export both override plugins in one pass:

```bat
E:\gitRepository\_skywalking-javaPluginExtensions\agent\build-export-skywalking-override-plugins.bat
```

You can also pass explicit paths:

```bat
E:\gitRepository\_skywalking-javaPluginExtensions\agent\build-export-skywalking-override-plugins.bat ^
  "D:\apps\apache-skywalking-java-agent-9.4.0" ^
  "D:\apps\java\jdk1.8.0_172"
```

The script will:

- build `override-httpClient-4.x-plugin`
- build `hutool-http-5.x-plugin`
- copy both custom jars into `SkyWalking Agent/plugins`
- move the official built-in jars into `SkyWalking Agent/optional-plugins`
- print the exported jar list
- try to scan `logs/skywalking-api.log` for related load messages
