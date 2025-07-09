/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.profile.ProfileSnapshotSender;
import org.apache.skywalking.apm.agent.core.profile.TracingThreadSnapshot;
import org.apache.skywalking.apm.dependencies.com.google.protobuf.ProtocolStringList;
import org.apache.skywalking.apm.network.language.profile.v3.ThreadSnapshot;
import org.apache.skywalking.apm.network.language.profile.v3.ThreadStack;

/**
 * <p>
 *
 * <p>
 * <p>
 * Refer To {@code KafkaProfileSnapshotSender}
 * Refer To {@link https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaProfileSnapshotSender.java}
 * <p>
 */
@OverrideImplementor(ProfileSnapshotSender.class)
public class ProfileSnapshotLocalSender extends ProfileSnapshotSender {
    private static final ILog LOGGER = LogManager.getLogger(ProfileSnapshotLocalSender.class);

    // 借鉴自Druid的JdbcDataSourceStat
    private LinkedHashMap<String, Map<String, Object>> profileSnapshotDataCache;
    @Override
    public void prepare() {
        profileSnapshotDataCache = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return (size() > 500);

            }
        };
    }

    @Override
    public void boot() {
    }

    public List<Map<String, Object>> getProfileSnapshotDatas() {
        return new java.util.ArrayList<>(profileSnapshotDataCache.values());
    }

    @Override
    public void send(final List<TracingThreadSnapshot> buffer) {

        for (TracingThreadSnapshot snapshot : buffer) {
            final ThreadSnapshot object = snapshot.transform();
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("Thread snapshot reporting, topic: {}, taskId: {}, sequence:{}, traceId: {}",
                             object.getTaskId(), object.getSequence(), object.getTraceSegmentId()
                );
            }

            // 将ThreadSnapshot对象转换为Map，便于外部简单使用
            final Map<String, Object> snapshotMap = new HashMap<>();
            snapshotMap.put("taskId", object.getTaskId());
            snapshotMap.put("sequence", object.getSequence());
            snapshotMap.put("traceSegmentId", object.getTraceSegmentId());
            snapshotMap.put("time", object.getTime());
            // 将自定义类型stack转换为Map，便于外部简单使用
            if (object.getStack() != null) {
                final ThreadStack stack = object.getStack();
                final ProtocolStringList codeSignaturesList = stack.getCodeSignaturesList();
                // 将自定义类型 ProtocolStringList 转换为普通的 List<String>
                List<String> codeSignatures =  codeSignaturesList.stream().map(String::valueOf).collect(Collectors.toList());
                // 将 stack 信息转换为 Map，便于外部简单使用
                Map<String, Object> stackMap = new HashMap<>();
                stackMap.put("codeSignatures", codeSignatures);
                snapshotMap.put("stack", stackMap);
            } else {
                snapshotMap.put("stack", null);
            }


            // 注意：如果ThreadSnapshot有复杂类型字段，可能需要进一步转换为Map或其他可序列化格式
            // 以taskId+sequence作为唯一key存入缓存
            String cacheKey = object.getTaskId() + "_" + object.getSequence();
            profileSnapshotDataCache.put(cacheKey, snapshotMap);

        }
    }
}