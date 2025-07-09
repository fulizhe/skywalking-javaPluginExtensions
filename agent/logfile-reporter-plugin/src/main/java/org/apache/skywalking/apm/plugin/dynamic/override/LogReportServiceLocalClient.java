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
 */

package org.apache.skywalking.apm.plugin.dynamic.override;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient;
import org.apache.skywalking.apm.agent.core.util.CollectionUtil;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;

/**
 * <p>
 *
 * <p>
 * <p>
 * Refer To {@code KafkaLogReporterServiceClient}
 * Refer To {@link https://github.com/apache/skywalking-java/blob/main/apm-sniffer/optional-reporter-plugins/kafka-reporter-plugin/src/main/java/org/apache/skywalking/apm/agent/core/kafka/KafkaLogReporterServiceClient.java}
 * <p>
 *
 *     <p>
 *         这里需要让Skywalking集成日志框架. 例如：https://skywalking.apache.org/docs/skywalking-java/v9.4.0/en/setup/service-agent/java-agent/application-toolkit-logback-1.x/
 *         集成完毕之后，我们通过日志框架写入的日志将被本类发送给OAP端
 *     </p>
 */
@OverrideImplementor(LogReportServiceClient.class)
public class LogReportServiceLocalClient extends LogReportServiceClient {
    private static final ILog LOGGER = LogManager.getLogger(LogReportServiceLocalClient.class);

    // 借鉴自Druid的JdbcDataSourceStat
    private LinkedHashMap<String, Map<String, Object>> logDataCache;

    @Override
    public void prepare() {
        logDataCache = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return (size() > 1000);

            }
        };
    }

    public List<Map<String, Object>> getLogDatas() {
        return new java.util.ArrayList<>(logDataCache.values());
    }

    @Override
    public void produce(final LogData.Builder logData) {
        super.produce(logData);
    }

    @Override
    public void consume(final List<LogData.Builder> dataList) {
        if (CollectionUtil.isEmpty(dataList)) {
            return;
        }

        for (LogData.Builder data : dataList) {
            // Kafka Log reporter sends one log per time.
            // Every time, service name should be set to keep data integrity.
            // 设置服务名，保证数据完整性
            data.setService(Config.Agent.SERVICE_NAME);

            // 将LogData.Builder构建为LogData对象
            LogData logDataObj = data.build();

            // 将LogData对象的典型字段转换为Map，便于外部简单使用
            Map<String, Object> logDataMap = new HashMap<>();
            logDataMap.put("service", logDataObj.getService());
            logDataMap.put("serviceInstance", logDataObj.getServiceInstance());
            logDataMap.put("endpoint", logDataObj.getEndpoint());

            final LogDataBody body = logDataObj.getBody();
            logDataMap.put("body_content_case", body.getContentCase().toString());
            logDataMap.put("body_type", body.getType());
            switch (body.getContentCase()) {
                case TEXT:
                    logDataMap.put("body_content", body.getText().getText());
                    break;
                case JSON:
                    logDataMap.put("body_content", body.getJson().getJson());
                    break;
                case YAML:
                    logDataMap.put("body_content", body.getYaml().getYaml());
                    break;
                case CONTENT_NOT_SET:
                default:
                    logDataMap.put("body_content", null);
                    break;
            }

            // 先将标签存到一个单独的Map中，再放进logDataMap
            Map<String, String> tagsMap = new HashMap<>();
            logDataObj.getTags().getDataList().forEach(tag -> {
                tagsMap.put(tag.getKey(), tag.getValue());
            });

            logDataMap.put("tags", tagsMap);
            logDataMap.put("timestamp", logDataObj.getTimestamp());
            logDataMap.put("traceContext", logDataObj.getTraceContext().toString());
            logDataMap.put("layer", logDataObj.getLayer());

            // 以 service + timestamp 作为唯一key存入缓存
            String cacheKey = logDataObj.getService() + "_" + logDataObj.getTimestamp();
            logDataCache.put(cacheKey, logDataMap);
        }
    }
}