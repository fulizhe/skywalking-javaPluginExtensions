# 项目说明
在运行时, 动态启动/停止诸如sql参数收集, http请求参数收集等等功能. 兼顾正常运行时的系统性能, 以及问题排错时能收集所需的更详尽上下文诉求。
1. plugin.jdbc.trace_sql_parameters=true ( JDBCPluginConfig.java )
2. plugin.springmvc.collect_http_params=true  ( SpringMVCPluginConfig.java )
3. plugin.httpclient.collect_http_params=true  ( HttpClientPluginConfig.java )
4. plugin.feign.collect_request_body=true  ( FeignPluginConfig.java )
5. plugin.tomcat.collect_http_params=true  ( TomcatPluginConfig.java )
6. plugin.dubbo.collect_consumer_arguments=true  ( DubboPluginConfig.java )
7. plugin.dubbo.collect_provider_arguments=true  ( DubboPluginConfig.java )
8. plugin.http.include_http_headers=  ( SpringMVCPluginConfig.java )


# 操作手册
1. 将项目根目录下的`SWUtils.java`文件拷贝道自己的项目中, 这里注意不要更改其中的package结构, 直接使用IDE的重构功能在你的项目中创建出对应的package结构.
2. 在任意地方调用`SWUtils.toggleDebug();` (推荐Controller层)

	```
		@RestController
		public class SWController {
			@GetMapping("/sw/toggleDebug")
			public String toggleDebug() {
				
				SWUtils.toggleDebug();
				
				return "toggleDebug";
			}
		}	
	```
3. 
4. 以上实现参考自skywalking内部的`TraceContext.traceId()`(对应实现类 `TraceContextActivation.java`)

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