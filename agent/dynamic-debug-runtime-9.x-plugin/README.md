# dynamic-debug-runtime-9.x-plugin

运行时开关：**一次调用**同时打开/关闭 Feign 请求体、JDBC SQL 参数、HttpClient / SpringMVC / Tomcat 请求参数的采集。
默认全关（保性能）；排错时打开，收完再关。

## 怎么用

1. 把 `doc/SWDebugUtils.java` 按原 package 结构（`org.apache.skywalking.apm.toolkit.trace`）拷进你的项目，**package 与类名都不要改**。
2. 把本模块构建出的 jar 放进 agent 的 `plugins/` 目录。
3. 在任意位置（推荐 Controller）调用 `enableDebug()` / `disableDebug()`，各自一次改写下面 5 个开关：

```java
@RestController
public class SWController {
    @GetMapping("/sw/debug/enable")
    public String enable() {
        SWDebugUtils.enableDebug();
        return "debug=enabled";
    }

    @GetMapping("/sw/debug/disable")
    public String disable() {
        SWDebugUtils.disableDebug();
        return "debug=disabled";
    }
}
```

## 开关清单

| 采集项 | 对应配置键 | 被改写的字段 |
| --- | --- | --- |
| SpringMVC 参数 | `plugin.springmvc.collect_http_params` | `SpringMVCPluginConfig$Plugin$SpringMVC#COLLECT_HTTP_PARAMS` |
| SpringMVC HTTP 头 | `plugin.springmvc.include_http_headers` | `SpringMVCPluginConfig$Plugin$Http#INCLUDE_HTTP_HEADERS`（**List**，见下） |
| Tomcat 7/8 参数 | `plugin.tomcat.collect_http_params` | `tomcat78x.TomcatPluginConfig$Plugin$Tomcat#COLLECT_HTTP_PARAMS` |
| Tomcat 10/11 参数 | `plugin.tomcat.collect_http_params` | `tomcat10x.TomcatPluginConfig$Plugin$Tomcat#COLLECT_HTTP_PARAMS` |
| HttpClient 参数 | `plugin.httpclient.collect_http_params` | `HttpClientPluginConfig$Plugin$HttpClient#COLLECT_HTTP_PARAMS` |
| Feign 请求体 | `plugin.feign.collect_request_body` | `FeignPluginConfig$Plugin$Feign#COLLECT_REQUEST_BODY` |
| Dubbo 消费者入参 | `plugin.dubbo.collect_consumer_arguments` | `DubboPluginConfig$Plugin$Dubbo#COLLECT_CONSUMER_ARGUMENTS`（2.7.x 与 3.x 两个类） |
| Dubbo 提供者入参 | `plugin.dubbo.collect_provider_arguments` | `DubboPluginConfig$Plugin$Dubbo#COLLECT_PROVIDER_ARGUMENTS`（2.7.x 与 3.x 两个类） |
| JDBC SQL 参数 | `plugin.jdbc.trace_sql_parameters` | `JDBCPluginConfig$Plugin$JDBC#TRACE_SQL_PARAMETERS` |

> **HTTP 头这一项特殊**：`INCLUDE_HTTP_HEADERS` 不是布尔，而是"要采集的 header 名清单"。
> 名单来源**沿用 agent 自身配置** `plugin.springmvc.include_http_headers`（本插件不再另立一份）：
> `enableDebug()` 恢复该名单、`disableDebug()` 置空。名单在开关**首次触碰时抓取一次**。
> 另外：SpringMVC 的 header 采集**不受** `COLLECT_HTTP_PARAMS` 门控（见 `AbstractMethodInterceptor`），
> 故必须单独开关，否则配了名单就会一直采集。

## 构建与验证

```powershell
# 构建（模块内联在 agent/ 聚合模块中，先进入 agent/ 再 -pl 本模块）
cd E:\gitRepository\_skywalking-javaPluginExtensions\agent
mvn clean package -Dmaven.test.skip=true -pl dynamic-debug-runtime-9.x-plugin -am

# 单测：验证反射开关确实生效、且缺插件时不中断
mvn test -pl dynamic-debug-runtime-9.x-plugin -am

# 产物应只含本插件类
jar tf dynamic-debug-runtime-9.x-plugin\target\dynamic-debug-runtime-9.x-plugin-1.0.0.jar | findstr /i "dynamicdebugruntime"

# 安装到 agent
copy dynamic-debug-runtime-9.x-plugin\target\dynamic-debug-runtime-9.x-plugin-1.0.0.jar <SW_AGENT_HOME>\plugins\

# 启动应用后：确认插件已加载 + 开关被触发
findstr /i "dynamic-debug" <SW_AGENT_HOME>\logs\skywalking-api.log
findstr /i "toggle debug" <SW_AGENT_HOME>\logs\skywalking-api.log
```

## 实现说明

- 增强目标：`org.apache.skywalking.apm.toolkit.trace.SWDebugUtils` 的 `enableDebug()` / `disableDebug()`（`DynamicDebugRuntimeInstrumentation`，各匹配无参重载）。
- `DynamicDebugRuntimeEnableInterceptor` / `DynamicDebugRuntimeDisableInterceptor` 委托 `DynamicDebugRuntimeSwitch` 反射改写各官方插件 `PluginConfig` 的静态开关字段；某插件未装配（config 类不存在）时只告警跳过，**不影响其余开关**。
- 依赖 agent 中已装配对应的官方插件（feign / jdbc / httpclient / springmvc / tomcat）。
- **Dubbo 采集开关未接入**：原实现的常量误指向 httpclient 类且调用被注释，已移除；如需支持请单独补充并核对官方字段名。
