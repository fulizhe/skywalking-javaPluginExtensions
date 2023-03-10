package org.apache.skywalking.apm.plugin.dynamic;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.EventReportServiceClient;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;

/**
 * Refer To {@link https://github.com/apache/skywalking-java/blob/main/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/remote/EventReportServiceClient.java}
 * @author LQ
 *
 */
@OverrideImplementor(EventReportServiceClient.class)
public class DisableEventReportServiceClient extends EventReportServiceClient {
	private static final ILog LOGGER = LogManager.getLogger(DisableEventReportServiceClient.class);

	@Override
	public void boot() throws Throwable {

	}

	@Override
	public void prepare() throws Throwable {

	}

	@Override
	public void onComplete() throws Throwable {

	}

	@Override
	public void shutdown() throws Throwable {

	}

	@Override
	public void statusChanged(final GRPCChannelStatus status) {
		LOGGER.warn("### GRPCChannelStatus is [ {} ]", status);
	}
}
