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

import java.util.List;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.dynamic.AgentConfigChangeWatcher;
import org.apache.skywalking.apm.agent.core.conf.dynamic.AgentConfigChangeWatcher.ConfigChangeEvent;
import org.apache.skywalking.apm.agent.core.conf.dynamic.ConfigurationDiscoveryService;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

@OverrideImplementor(TraceSegmentServiceClient.class)
public class DyanmicEnabledTraceSegmentServiceClient extends TraceSegmentServiceClient {
	private static final ILog LOGGER = LogManager.getLogger(DyanmicEnabledTraceSegmentServiceClient.class);
	private DyanmicEnabledConfigWatcher dyanmicEnabledTraceSegmentServiceClientConfigWatcher;

	DyanmicEnabledConfigWatcher getConfigWatcher() {
		return dyanmicEnabledTraceSegmentServiceClientConfigWatcher;
	}

	@Override
	public void prepare() {
		super.prepare();
		dyanmicEnabledTraceSegmentServiceClientConfigWatcher = new DyanmicEnabledConfigWatcher("agent.dynamic.enable");
	}

	@Override
	public void boot() {
		super.boot();

		ServiceManager.INSTANCE.findService(ConfigurationDiscoveryService.class)
				.registerAgentConfigChangeWatcher(dyanmicEnabledTraceSegmentServiceClientConfigWatcher);
	}

	@Override
	public void consume(List<TraceSegment> arg0) {
		if (!dyanmicEnabledTraceSegmentServiceClientConfigWatcher.isEnbaleSendDataToServer()) {
			if (LOGGER.isDebugEnable()) {
				LOGGER.debug("### disable the agent [ {} ] send data to server. the collection size of data is [ {} ]",
						Config.Agent.SERVICE_NAME, arg0.size());
			}
			return;
		}

		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("### send data to server. the collection size of data is [ {} ]", Config.Agent.SERVICE_NAME,
					arg0.size());
		}
		//
		super.consume(arg0);
	}

	public void changeAgentEnableStatus(boolean isEnable) {
		final String value = dyanmicEnabledTraceSegmentServiceClientConfigWatcher.value();
		final boolean currentVal = Boolean.parseBoolean(value);
		if (currentVal == isEnable) {
			if (LOGGER.isInfoEnable()) {
				LOGGER.info("### current agent-enable status is [ {} ], do not need change", currentVal);
			}
			return;
		}
		//
		if (LOGGER.isInfoEnable()) {
			LOGGER.info("### current agent-enable status is [ {} ],will change to [ {} ]", currentVal, !currentVal);
		}
		final ConfigChangeEvent event = new AgentConfigChangeWatcher.ConfigChangeEvent(String.valueOf(!currentVal),
				AgentConfigChangeWatcher.EventType.MODIFY);
		dyanmicEnabledTraceSegmentServiceClientConfigWatcher.notify(event);
	}
}
