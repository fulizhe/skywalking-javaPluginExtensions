package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.jvm.JVMService;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

@OverrideImplementor(JVMService.class)
public class DisableJVMService extends JVMService {
	private static final ILog LOGGER = LogManager.getLogger(DisableJVMService.class);

	@Override
	public void boot() throws Throwable {
		// 直接不初始化线程池
		// super.boot();				
	}

	@Override
	public void run() {
		LOGGER.debug("### disable jvm Metrics");
	}
}
