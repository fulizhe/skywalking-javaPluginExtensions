package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.dependencies.io.grpc.Channel;

import cn.hutool.core.util.ReflectUtil;

// 【关键】告诉 Agent 用这个类替换原生的 GRPCChannelManager
/**
 * {@link https://github.com/apache/skywalking-java/blob/e0de83f6ba49f20c166f6820440f6aa926a3fc0f/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/GRPCChannelManager.java}
 */
@OverrideImplementor(GRPCChannelManager.class)
public class MemoryModeGRPCChannelManager extends GRPCChannelManager {

	private static final ILog LOGGER = LogManager.getLogger(MemoryModeGRPCChannelManager.class);

	@Override
	public void prepare() {
		// 不做任何网络配置准备
	}

	@Override
	public void boot() {
		// 【关键】覆盖原生的 boot 方法
		// 原生代码在这里会创建 ScheduledFuture (runnable) 来不断重连
		// 我们这里留空，什么都不做，彻底掐断 gRPC 连接和线程池的创建源头
		LOGGER.info("[MemoryMode] GRPCChannelManager has been disabled. No network threads will be created.");
		
		// 这里在GRPCChannelManager源码中会读取配置项 collector.backend_service, 如果发现为空则"Agent will not uplink any data."
		// 源码参见顶部的类注释上给出的链接地址
		
		ReflectUtil.setFieldValue(this, "reconnect", false);
	}

	@Override
	public void onComplete() {
		// 留空
	}

	@Override
	public void shutdown() {
		// 留空
	}

	@Override
	public void run() {
		// 【关键】覆盖 Runnable 的 run 方法，防止万一被调度执行连接逻辑
	}

	@Override
	public Channel getChannel() {
		// 因为你的 TraceSegmentServiceClient 和 LogReportServiceClient 已经被重写为存内存
		// 理论上它们不应该再调用 getChannel()。
		// 如果为了安全起见，可以返回 null，或者一个 Mock 的 Channel
		return null;
	}

	// 如果有其他 public 方法被其他 Service 强引用，可能需要根据情况覆盖，
	// 但通常覆盖 boot() 和 run() 就足以阻止线程创建。
}