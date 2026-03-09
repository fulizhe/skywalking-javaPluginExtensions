package org.apache.skywalking.apm.plugin.dynamic.override;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.profile.ProfileTaskChannelService;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;

/**
 * {@link https://github.com/apache/skywalking-java/blob/f750006020249d29a21cae63ae50da67f182c6ab/apm-sniffer/apm-agent-core/src/main/java/org/apache/skywalking/apm/agent/core/profile/ProfileTaskChannelService.java}
 *
 *
 *  C. 性能剖析相关 (Profile)
     * 线程名: SkywalkingAgent-7-ProfileGetTaskService-0
     * 线程名: SkywalkingAgent-8-ProfileSendSnapshotService-0
     * 对应类: org.apache.skywalking.apm.agent.core.profile.ProfileTaskChannelService
     * 分析：ProfileTaskChannelService 负责处理性能剖析任务的通信和调度。它通常会与 OAP 服务器进行交互，接收性能剖析任务的指令，并将采集到的性能数据发送回 OAP 服务器。在无后端场景下，这些功能我们自己做。
     * 操作：❌ 消除。
 */
@OverrideImplementor(ProfileTaskChannelService.class)
public class NoOpProfileTaskChannelService extends ProfileTaskChannelService {
	
	private static final ILog LOGGER = LogManager.getLogger(NoOpProfileTaskChannelService.class);
	
    @Override public void prepare() {}
    @Override public void boot() {
        // 1. 这里需要. 主要是需要ProfileSendSnapshotService这个线程来回调 ProfileSnapshotSender, 进而可以回调到我们的ProfileSnapshotLocalSender
    	super.boot();

        stopGetTaskListFuture();
    }

    private void stopGetTaskListFuture(){
        try {
            // 通过反射获取 getTaskListFuture
            java.lang.reflect.Field futureField = ProfileTaskChannelService.class.getDeclaredField("getTaskListFuture");
            futureField.setAccessible(true);
            Object getTaskListFuture = futureField.get(this);

            // 检查是否为 Future 类型并尝试停止
            if (getTaskListFuture instanceof java.util.concurrent.Future) {
                ((java.util.concurrent.Future<?>) getTaskListFuture).cancel(true);
                LOGGER.info("### Successfully stopped getTaskListFuture.");
            } else {
                LOGGER.warn("### getTaskListFuture is not of type Future.");
            }

            // 将字段值设置为 null
            futureField.set(this, null);
            LOGGER.info("### Successfully set getTaskListFuture to null.");

        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error("### Failed to access getTaskListFuture via reflection.", e);
        }
    }

    @Override public void onComplete() {}
    @Override public void shutdown() {super.shutdown();}
    @Override public void run() {
         // 2. 这里不需要, 因为我们没有OAP要通信了, 也就没有任务调度了
        // NO OP
    }

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        LOGGER.warn("### GRPC Disabled. Current GRPCChannelStatus is [ {} ]", status);
    }
}
