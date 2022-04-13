# 项目说明


# 额外说明


```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl hutool-http-5.x-plugin -am
// 拷贝插件到SW下 original-
cp ./hutool-http-5.x-plugin/target/hutool-http-5.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\hutool-http-5.x-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr hutool-http-
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr hutool-http-

// 验证

```