/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openskywalking.demo.controller;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.skywalking.apm.toolkit.SWLogfileReporterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * profile 快照读口(移植自旧项目 sb-skywalking 的 SWProfileController):
 * 触发采样、读取快照;{@code /longTimeTask} 为采样工作负载。
 * <p>
 * 请求体示例:POST /profile {"endpointName":"GET:/longTimeTask","minDurationThreshold":1,"maxSamplingCount":5}
 * 端点名需与 Spring MVC 实际端点名一致(如 {@code GET:/longTimeTask})。
 */
@RestController
public class ProfileController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileController.class);

    /** profile 快照读口(插件缓存,含方法栈) */
    @GetMapping("/profileData2")
    public Object profileData2() {
        return StatisticStatus.value("profileSnapshotData");
    }

    /** profile 快照读口(agent 侧 profile 任务与采集缓存) */
    @GetMapping("/profileData")
    public Object profileData() {
        Map<String, Object> status = StatisticStatus.get();
        if (StatisticStatus.isHint(status)) {
            return status;
        }
        return SWLogfileReporterUtils.getProfileDatas();
    }

    /** 发起 profile 采样(agent 侧延后 5 秒启动,采集中请持续打 /longTimeTask 流量) */
    @PostMapping("/profile")
    public Object profile(@RequestBody Map<String, Object> params) {
        SWLogfileReporterUtils.startProfile(params);
        return params;
    }

    /** profile 采样工作负载:随机睡 200~2000ms */
    @GetMapping("/longTimeTask")
    public String longTimeTask() {
        int sleep = ThreadLocalRandom.current().nextInt(200, 2000);
        LOGGER.info("### longTimeTask sleep {} ms", sleep);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "longTimeTask done, slept " + sleep + " ms";
    }
}
