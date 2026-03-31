package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.EventReportServiceClient;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;

/**
 * <p> 本类型的作用就是向 OAP侧发送两个事件: StartingEvent, shutdownEvent. 这在我们的场景下用处不大
 * <p> 注意基类所在的package, 可以说本项目要解决的问题基本都在这个package下.
 * <p> {@link https://github.com/apache/skywalking-java/blob/b1351bcc826de086b65212c87b232524ef33a7c1/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/EventReportServiceClient.java}
 * @deprecated 暂未启用. 
 */
@Deprecated
@OverrideImplementor(EventReportServiceClient.class)
public class NoOpEventReportServiceClient extends EventReportServiceClient {

	private static final ILog LOGGER = LogManager.getLogger(NoOpEventReportServiceClient.class);

	@Override
	public void prepare() throws Throwable {
		LOGGER.info("### EventReportServiceClient has been disabled. ");
	}

	@Override
	public void boot() throws Throwable {
		LOGGER.info("### EventReportServiceClient has been disabled. ");
	}

	@Override
	public void onComplete() throws Throwable {
		// 上报 StartingEvent
	}

	@Override
	public void shutdown() throws Throwable {
		// 上报 shutdownEvent
	}

	@Override
	public void statusChanged(final GRPCChannelStatus status) {
		LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
	}
}
