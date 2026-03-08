package org.apache.skywalking.apm.agent.core.plugin.localprofile;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.profile.ProfileTask;
import org.apache.skywalking.apm.agent.core.profile.ProfileTaskExecutionService;

import java.util.Collections;
import java.util.UUID;

class LocalProfileTrigger {

    /**
     * 手动触发一个 Profile 任务
     *
     * @param endpointName 监控的端点名称 (e.g., "/api/v1/user/list")，支持通配符具体看SW实现
     * @param durationMin  任务持续时间（分钟）
     * @param minDurationThresholdMs  响应时间阈值，超过这个值的请求才会被 dump (例如 500ms)
     */
    public static void startProfileTask(String endpointName, int durationMin, int minDurationThresholdMs) {
        
        // 1. 构建 ProfileTask 对象
        // 注意：ProfileTask 的构造函数或 Setter 可能是非 public 的，
        // 如果是 private，你需要用反射去设值。
        // SkyWalking 版本不同，字段略有差异，以下以 8.x/9.x 为例
        ProfileTask task = new ProfileTask();
        
        // 必填字段
        task.setTaskId(UUID.randomUUID().toString()); // 既然没 OAP，自己生成一个 ID
        task.setFirstSpanOPName(endpointName);        // 监控的目标接口
        task.setDuration(durationMin);                // 监控持续时间
        task.setMinDurationThreshold(minDurationThresholdMs); // 抓取阈值
        task.setThreadDumpPeriod(10);                 // Dump 间隔，默认 10ms
        task.setMaxSamplingCount(5);                  // 在此任务期间最多抓取几个请求（防止系统过载）
        task.setStartTime(System.currentTimeMillis()); // 任务开始时间
        task.setCreateTime(System.currentTimeMillis());

        // 2. 获取核心服务实例
        ProfileTaskExecutionService executionService = ServiceManager.INSTANCE
                .findService(ProfileTaskExecutionService.class);

        if (executionService != null) {
            // 3. 注入任务
            // addProfileTask 接受一个 List
            executionService.addProfileTask(task);
            System.out.println("Local Profile Task added: " + endpointName);
        } else {
            System.err.println("ProfileTaskExecutionService not found!");
        }
    }
}