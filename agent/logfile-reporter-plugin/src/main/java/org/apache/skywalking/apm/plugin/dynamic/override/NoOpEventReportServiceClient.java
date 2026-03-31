package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.EventReportServiceClient;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;

/**
 * 本类型的作用就是向 OAP侧发送两个事件: StartingEvent, shutdownEvent. 这在我们的场景下用处不大
 * 
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
