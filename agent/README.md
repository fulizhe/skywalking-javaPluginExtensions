# skywalking-javaPluginExtensions

#### 介绍
Skywalking Java Agent插件

### 清单
1. [consul](./consul-1.x-plugin)
2. [override-hutool-http](./override-hutool-http-5.x-plugin)
3. [override-httpclient](./override-httpclient-4.x-plugin)
4. [dynamic-debug-runtime](./dynamic-debug-runtime-8.x-plugin)
5. [dynamic-enable-runtime](./dynamic-enable-runtime-8.x-plugin)
6. [sqlite](./sqlite-3.x-plugin)
7. [logfile-reporter](./logfile-reporter-plugin)


#### 参考
1. [SkyAPM - java-plugin-extensions](https://github.com/SkyAPM/java-plugin-extensions)
2. [skywalking-java GitHub](https://github.com/apache/skywalking-java)
3. [skywalking - 官网](https://skywalking.apache.org/)


## AI PROMPT

同样的要求，在 override-httpclient-4.x-plugin 中捕获 返回值，我们特别在意对于返回流的消耗导致业务侧读取不到，这对于监控是不可接受的，监控只能是助力，而不是阻碍，宁可采集不到。这也是请求侧参数收集同样的标准