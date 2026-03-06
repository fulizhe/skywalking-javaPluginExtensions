package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.commands.CommandService;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

/**
 * {@link https://github.com/apache/skywalking-java/blob/e0de83f6ba49f20c166f6820440f6aa926a3fc0f/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/commands/CommandService.java}
 * 
 *  服务端命令相关
		线程名: SkywalkingAgent-1-CommandService-0
		对应类: org.apache.skywalking.apm.agent.core.commands.CommandService
		分析：轮询 OAP 获取控制指令（如重置采样率）。无后端场景下无效。
		操作：❌ 消除。
 */
@OverrideImplementor(CommandService.class)
public class NoOpCommandService extends CommandService {
	
	private static final ILog LOGGER = LogManager.getLogger(NoOpCommandService.class);
	
    @Override public void prepare() {}
    @Override public void boot() {
    	LOGGER.info("### CommandService has been disabled. ");
    }
    @Override public void onComplete() {}
    @Override public void shutdown() {}
    @Override public void run() {}
}
