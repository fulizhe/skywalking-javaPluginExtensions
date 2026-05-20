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
override-httpClient-4.x-plugin/target/overide-apm-httpClient-4.x-plugin-9.4.0.jar
```
## 编译(公司内环境)

```
$env:path="D:\apps\java\jdk1.8.0_172\bin;$env:path"

// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl override-httpClient-4.x-plugin -am
// 拷贝插件到SW下 original-
cp ./override-httpClient-4.x-plugin/target/override-apm-httpClient-4.x-plugin-9.4.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\plugins\override-apm-httpClient-4.x-plugin-9.4.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr override-apm-httpClient-4.x-plugin
// 移除官方版本
mv D:\apps\apache-skywalking-java-agent-9.4.0\plugins\apm-httpClient-4.x-plugin-9.4.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\optional-plugins -ErrorAction SilentlyContinue
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-9.4.0\logs\skywalking-api.log | findstr override-apm-httpclient-4.x-plugin

// 验证

```