# 项目说明
在应用不重启的情况下, 动态启用/关闭skywalking agent端向server端发送监控数据的功能.

# 背景说明
1. 现有微服务架构下, 缺乏链路追踪功能. 
2. 贸然引入完整的链路追踪功能, 即使skywalking这种已经最大化降低接入门槛的, 也存在现阶段不能被接受的维护成本. (skywalking-OAP的H2存储模式单次不能存活太长时间)
3. 过往我们已经基于skywalking的`-Dskywalking.agent.keep_tracing=true`配置, 在不启用skywalking-OAP的情况下, 基于traceId来查询日志的方式实现了一个非常简陋的链路追踪功能(我们称之为"人肉链路追踪").
4. 该"人肉链路追踪"方案暴露的一个问题就是查询功能/界面比较简陋, 加之过往相关人员已经养成了登录服务器打开日志文件查询日志的习惯, 在不投入专门的精力进行辅助的情况下, 推进难度较大. (业务特点+人员素养双重决定的)
5. 基于此我们希望实现skywalking链路追踪功能的动态启用: 
	a. 平时agent开启, 但基于我们的默认配置不会向server端推送监控数据, 即直接丢弃收集到的数据.
	b. 平时server关闭.
	c. 在发生问题时, 先启动服务端, 然后开启agent端向服务端推送监控数据的开关, 访问有问题的功能; 拿着traceId去skywalking提供的页面下查询, 实现快速排错.
	d. 问题解决后, 关闭server端, 关闭agent端向服务端推送监控数据的开关.
6. 实现这个"动态启用"启用功能之后, 可以让h2版本的skywalking存活得久一些. 实属无奈之举.	
	
	
	
TODO
1. <s>向服务端推送logger数据</s>	
2. <s>关闭metric功能</s>

# 额外说明

```
// 编译出插件
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl dynamic-enable-runtime-8.x-plugin -am
// 拷贝插件到SW下 original-
cp ./dynamic-enable-runtime-8.x-plugin/target/dynamic-enable-runtime-8.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\dynamic-enable-runtime-8.x-plugin-1.0.0.jar
// 验证拷贝成功
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr dynamic-enable-runtime-8.x-plugin
// 验证加载成功
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr dynamic-enable-runtime-8.x-plugin

// 验证
http://localhost/projectB/LQ
```

---- 以上的快捷版本
cd E:\gitRepository\_skywalking-javaPluginExtensions\
mvn clean package '-Dmaven.test.skip=true' -T 2C -pl dynamic-enable-runtime-8.x-plugin -am
cp ./dynamic-enable-runtime-8.x-plugin/target/dynamic-enable-runtime-8.x-plugin-1.0.0.jar D:\apps\apache-skywalking-java-agent-8.8.0\plugins\dynamic-enable-runtime-8.x-plugin-1.0.0.jar
ls D:\apps\apache-skywalking-java-agent-8.8.0\plugins\ | findstr dynamic-enable-runtime-8.x-plugin
cat D:\apps\apache-skywalking-java-agent-8.8.0\logs\skywalking-api.log | findstr dynamic-enable-runtime-8.x-plugin