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
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.agent.core.util.CollectionUtil;

@OverrideImplementor(LogReportServiceClient.class)
public class DynamicEnabledLogReportServiceClient extends LogReportServiceClient {
	private static final ILog LOGGER = LogManager.getLogger(DynamicEnabledLogReportServiceClient.class);

	private DyanmicEnabledConfigWatcher dyanmicEnabledTraceSegmentServiceClientConfigWatcher;

	@Override
	public void prepare() throws Throwable {
		//ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
		super.prepare();
	}

	@Override
	public void boot() throws Throwable {
		super.boot();

		DyanmicEnabledTraceSegmentServiceClient findService = (DyanmicEnabledTraceSegmentServiceClient) ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		dyanmicEnabledTraceSegmentServiceClientConfigWatcher = findService.getConfigWatcher();
	}

	@Override
	public void consume(List<LogData> arg0) {
		if (CollectionUtil.isEmpty(arg0)) {
			return;
		}

		if (!dyanmicEnabledTraceSegmentServiceClientConfigWatcher.isEnbaleSendDataToServer()) {
			LOGGER.debug("### disable push log data to server, the collection size of log data is [ {} ]", arg0);
			return;
		}

		// 其实在8.8.x中, 已经实现了基于 GRPCChannelStatus 的判断. 但是我们还是在上面加上我们的配置判断
		super.consume(arg0);
	}

}
