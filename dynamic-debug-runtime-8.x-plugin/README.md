# 项目说明
在运行时, 动态启动/停止诸如sql参数收集, http请求参数收集等等功能.
1. plugin.jdbc.trace_sql_parameters=true
2. plugin.springmvc.collect_http_params=true
3. plugin.httpclient.collect_http_params=true
4. plugin.feign.collect_request_body=true

# 额外说明


```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl dynamic-debug-runtime-8.x-plugin -am
// 拷贝插件到SW下 original-
cp ./dynamic-debug-runtime-8.x-plugin/target/dynamic-debug-runtime-8.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\dynamic-debug-runtime-8.x-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr dynamic-debug-runtime-8.x
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr hutool-http-

// 验证

```