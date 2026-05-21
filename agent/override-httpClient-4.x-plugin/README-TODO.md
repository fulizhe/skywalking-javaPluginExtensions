
1. 考虑将返回值也做个记录. HttpClientExecuteInterceptor; HutoolHttpRequestInterceptor
2. 其它相关的httpclient;  skywalking提供的Plugin里，类似apache httpclient有哪些？ 我要对它们进行扩展，添加入参出参记录功能




我看skywalking plugin里有这样的源码： span.isProfiling()  ，我怎么触发它？ 只在「这条 Span 正处于 SkyWalking 的 Trace Profiling（链路性能剖析）任务中」才会触发。