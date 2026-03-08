package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.dynamic.AgentConfigChangeWatcher;
import org.apache.skywalking.apm.agent.core.conf.dynamic.ConfigurationDiscoveryService;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.network.trace.component.command.ConfigurationDiscoveryCommand;

/**
 * {@link https://github.com/apache/skywalking-java/blob/e0de83f6ba49f20c166f6820440f6aa926a3fc0f/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/conf/dynamic/ConfigurationDiscoveryService.java}
 * 
 动态配置相关
	线程名: SkywalkingAgent-10-ConfigurationDiscoveryService-0
	对应类: org.apache.skywalking.apm.agent.core.conf.dynamic.ConfigurationDiscoveryService
	分析：从 OAP 拉取动态配置。
	操作：❌ 消除。 
 */
@OverrideImplementor(ConfigurationDiscoveryService.class)
public class NoOpConfigurationDiscoveryService extends ConfigurationDiscoveryService {

	private static final ILog LOGGER = LogManager.getLogger(NoOpConfigurationDiscoveryService.class);

	@Override
	public void prepare() {
	}

	@Override
	public void boot() {
		LOGGER.info("### ConfigurationDiscoveryService has been disabled. ");
	}

	@Override
	public void onComplete() {
	}

	@Override
	public void shutdown() {
	}

	@Override
	public void statusChanged(GRPCChannelStatus status) {
		LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
	}

	@Override
	public synchronized void registerAgentConfigChangeWatcher(AgentConfigChangeWatcher arg0) {
	}

	@Override
	public void handleConfigurationDiscoveryCommand(ConfigurationDiscoveryCommand configurationDiscoveryCommand) {
	}
}