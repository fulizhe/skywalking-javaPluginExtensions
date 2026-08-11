# override-hutool-http-5.x-plugin compile/export

## Recommended

Use the repo-level batch script to build and export both override plugins in one pass:

```bat
E:\gitRepository\_skywalking-javaPluginExtensions\agent\build-export-skywalking-override-plugins.bat
```

You can also pass explicit paths:

```bat
E:\gitRepository\_skywalking-javaPluginExtensions\agent\build-export-skywalking-override-plugins.bat ^
  "D:\apps\apache-skywalking-java-agent-9.4.0" ^
  "D:\apps\java\jdk-17.0.8"
```

The script will:

- build `override-httpclient-4.x-plugin`
- build `override-hutool-http-5.x-plugin`
- copy both custom jars into `SkyWalking Agent/plugins`
- move the official built-in jars into `SkyWalking Agent/optional-plugins`
- print the exported jar list
- try to scan `logs/skywalking-api.log` for related load messages

## Current Hutool artifact

```text
override-hutool-http-5.x-plugin/target/override-apm-hutool-http-5.x-plugin-9.4.0.jar
```
