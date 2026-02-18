# 项目说明

agent 侧搜集到的监控数据默认会发送到 OAP。本 plugin 将之截获并保存到本地 cache，魔改以适应单体项目快速使用。

1. **监控数据缓存**：实现参考 Druid 的 `JdbcDataSourceStat`（有界 LRU、同步包装、`getMetrics()` 返回快照，保证线程安全）。
2. **JVM 指标本地化**：通过 `JVMMetricsLocalSender` 覆盖默认的 `JVMMetricsSender`，将 JVM 指标（CPU、内存、GC、线程、类加载等）转为结构化 Map 后写入本地内存缓存，不发给 OAP；供日志上报、前端展示等按需读取。缓存条数上限可配置，见下方「配置项」。

# 参考

1. [Office Site - submit the agent collected data to the backend](https://skywalking.apache.org/docs/skywalking-java/v9.3.0/en/setup/service-agent/java-agent/advanced-reporters/) -- The advanced report provides an alternative way to submit the agent collected data to the backend. All of them are in the optional-reporter-plugins folder

2. 本地目录： E:\gitRepository\projects\skywalking-java\apm-sniffer\optional-reporter-plugins\kafka-reporter-plugin

3. 《SW - java-agent》
4. 《SW - 实战 - 起步(BladeX示例)》

# 配置项

配置由 `LogFileReporterPluginConfig` 集中管理，可通过 JVM 参数或 agent 配置覆盖（具体键名以 SkyWalking 注入规则为准，一般为 `skywalking.plugin.<模块>.<字段>` 的小写形式）。

| 说明 | 配置键示例 | 默认值 |
|------|------------|--------|
| 日志/上报相关缓存条数上限 | `skywalking.plugin.logfilereporter.max_log_size` | 1000 |
| JVM 指标本地缓存条数上限（LRU 淘汰） | `skywalking.plugin.jvmmetricslocal.max_metrics_data_size` | 1000 |

```shell
# 示例
-Dskywalking.plugin.logfilereporter.max_log_size=2000
-Dskywalking.plugin.jvmmetricslocal.max_metrics_data_size=2000
```

# 关联项目

1. 《sb-skywalking》

# 相关注释与代码位置

- **JVMMetricsLocalSender**（`org.apache.skywalking.apm.plugin.dynamic.override`）：类注释说明了“本地实现、不发给 OAP、有界 LRU 缓存、供日志/前端读取”；`maxMetricsDataSize` 字段注释标明由 `LogFileReporterPluginConfig.Plugin.JvmMetricsLocal` 配置。
- **LogFileReporterPluginConfig**（`org.apache.skywalking.apm.agent.core.reporter.logfile`）：`Plugin.LogFileReporter` 对应日志条数上限；`Plugin.JvmMetricsLocal` 对应 JVM 指标缓存条数上限，注释写明“供 JVMMetricsLocalSender 使用、超过后按 LRU 淘汰”。
- **getMetrics()**：方法旁注释说明“返回快照，避免调用方遍历时与写线程并发修改导致异常”。

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