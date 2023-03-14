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

package org.apache.skywalking.apm.plugin.dynamic;

import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;

@OverrideImplementor(GRPCChannelManager.class)
public class DynamicEnabledGRPCChannelManager extends GRPCChannelManager {
	private static final ILog LOGGER = LogManager.getLogger(DynamicEnabledGRPCChannelManager.class);

	private DyanmicEnabledConfigWatcher dyanmicEnabledTraceSegmentServiceClientConfigWatcher;

	@Override
	public void boot() {
		Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL = 24 * 60 * 60; // 一天验活一次
		
		super.boot();

		DyanmicEnabledTraceSegmentServiceClient findService = (DyanmicEnabledTraceSegmentServiceClient) ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		dyanmicEnabledTraceSegmentServiceClientConfigWatcher = findService.getConfigWatcher();
	}

	@Override
	public void run() {
		//LOGGER.debug("Selected collector grpc service running, reconnect:{}.", reconnect);

		if (!dyanmicEnabledTraceSegmentServiceClientConfigWatcher.isEnbaleSendDataToServer()) {
			LOGGER.info("### disable grpc heart beat for [ {} ]", Config.Agent.SERVICE_NAME);
			return;
		}
		
		super.run();				
	}

}
