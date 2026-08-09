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

package org.openskywalking.demo.config;

import org.apache.skywalking.apm.meter.micrometer.SkywalkingMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;

/**
 * meter 指标流造数:Spring Boot 2.5.4 无 actuator,micron 直接注册 JVM binder;
 * SkywalkingMeterRegistry 把 micrometer 指标桥接进 agent 的 MeterSystem(9.4.0 toolkit),
 * 插件将 MeterSender 改写为本地缓存后,读口 {@code /statisticMeter} 可见。
 */
@Configuration
public class SkywalkingMicrometerConfiguration {

    @Bean
    public SkywalkingMeterRegistry skywalkingMeterRegistry() {
        SkywalkingMeterRegistry registry = new SkywalkingMeterRegistry();
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        return registry;
    }
}
