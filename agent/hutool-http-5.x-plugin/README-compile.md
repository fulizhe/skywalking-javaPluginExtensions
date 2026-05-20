## 编译(公司内环境)

```
$env:path="D:\apps\java\jdk1.8.0_172\bin;$env:path"

// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl hutool-http-5.x-plugin -am
// 拷贝插件到SW下 original-
cp ./hutool-http-5.x-plugin/target/override-apm-hutool-http-5.x-plugin-9.4.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\plugins\override-apm-hutool-http-5.x-plugin-9.4.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr override-apm-hutool-http-5.x-plugin-
// 移除官方版本
mv D:\apps\apache-skywalking-java-agent-9.4.0\plugins\apm-hutool-http-5.x-plugin-9.4.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\optional-plugins -ErrorAction SilentlyContinue
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-9.4.0\logs\skywalking-api.log | findstr override-apm-hutool-http-5.x-plugin-

// 验证

```