# 项目说明


# 额外说明


```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl sqlite-3.x-plugin -am
// 拷贝插件到SW下 original-
cp ./sqlite-3.x-plugin/target/sqlite-3.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\sqlite-3.x-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr sqlite-3.x
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr sqlite-3.x-plugin-

// 验证

```