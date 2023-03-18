# 项目说明
依赖于《sc-zuul-sc》项目, 递归依赖于《projectB》项目


# 额外说明


```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl zuul-1.x-plugin -am
// 拷贝插件到SW下 original-
cp ./zuul-1.x-plugin/target/zuul-1.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\zuul-1.x-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr zuul-
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr zuul-

// 验证
http://localhost/projectB/LQ
```