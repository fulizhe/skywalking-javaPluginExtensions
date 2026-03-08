package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.asyncprofiler.AsyncProfilerTaskChannelService;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.commands.CommandService;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;

/**
 * {@link https://github.com/apache/skywalking-java/blob/f750006020249d29a21cae63ae50da67f182c6ab/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/asyncprofiler/AsyncProfilerTaskChannelService.java}
 *
 *  必须禁用ProfileTaskChannelService，否则其内部的异步任务会把收集的profile数据消费掉，导致我们无法获取到数据。
 *
 *  C. 性能剖析相关 (Profile)
     * 线程名: SkywalkingAgent-7-ProfileGetTaskService-0
     * 线程名: SkywalkingAgent-8-ProfileSendSnapshotService-0
     * 对应类: org.apache.skywalking.apm.agent.core.profile.ProfileTaskChannelService
     * 分析：ProfileTaskChannelService 负责处理性能剖析任务的通信和调度。它通常会与 OAP 服务器进行交互，接收性能剖析任务的指令，并将采集到的性能数据发送回 OAP 服务器。在无后端场景下，这些功能我们自己做。
     * 操作：❌ 消除。
 */
@OverrideImplementor(AsyncProfilerTaskChannelService.class)
public class NoOpAsyncProfilerTaskChannelService extends AsyncProfilerTaskChannelService {
	
	private static final ILog LOGGER = LogManager.getLogger(NoOpAsyncProfilerTaskChannelService.class);
	
    @Override public void prepare() {}
    @Override public void boot() {
    	LOGGER.info("### CommandService has been disabled. ");
    }
    @Override public void onComplete() {}
    @Override public void shutdown() throws Throwable {super.shutdown();}
    @Override public void run() {}

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
    }
}
