# 编译(公司内环境)

```
$env:path="D:\apps\java\jdk1.8.0_172\bin;$env:path"

// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl logfile-reporter-plugin -am
// 拷贝插件到SW下 original-
cp ./logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\plugins\logfile-reporter-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr logfile-reporter-plugin-
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-9.4.0\logs\skywalking-api.log | findstr logfile-reporter-plugin-

// 验证

```

# 编译(公司内环境)

```shell
$env:path="D:\apps\java\jdk1.8.0_92-64\bin;$env:path"

// 编译出插件
cd D:\gitRepository\skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl logfile-reporter-plugin -am
// 拷贝插件到SW下 original-
cp ./logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-9.4.0\plugins\logfile-reporter-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-9.4.0\plugins\ | findstr logfile-reporter-plugin-
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-9.4.0\logs\skywalking-api.log | findstr logfile-reporter-plugin-

```