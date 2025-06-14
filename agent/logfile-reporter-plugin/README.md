# 项目说明

agent侧搜集到的监控数据, 默认应该发送到oap的. 本plugin将之截获本保存到本地cache里. 魔改以适应单体项目快速使用.

1. 监控数据的缓存实现参考Druid里的<code>JdbcDataSourceStat</code>.
2. 

# 参考

1. [Office Site - submit the agent collected data to the backend](https://skywalking.apache.org/docs/skywalking-java/v9.3.0/en/setup/service-agent/java-agent/advanced-reporters/) -- The advanced report provides an alternative way to submit the agent collected data to the backend. All of them are in the optional-reporter-plugins folder

2. 本地目录： E:\gitRepository\projects\skywalking-java\apm-sniffer\optional-reporter-plugins\kafka-reporter-plugin

3. 《SW - java-agent》
4. 《SW - 实战 - 起步(BladeX示例)》

# 关联项目

1. 《sb-skywalking》

# 额外说明


```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl logfile-reporter-plugin -am
// 拷贝插件到SW下 original-
cp ./logfile-reporter-plugin/target/logfile-reporter-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\logfile-reporter-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr logfile-reporter-plugin-
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr logfile-reporter-plugin-

// 验证

```