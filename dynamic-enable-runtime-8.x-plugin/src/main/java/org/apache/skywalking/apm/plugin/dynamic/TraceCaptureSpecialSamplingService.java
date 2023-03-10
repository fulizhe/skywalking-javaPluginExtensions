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
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.agent.core.sampling.SamplingService;

/**
 * <p> 只向server端推送指定的链路
 * @author LQ
 * @see trace-ignore-plugin模块中的{@code TraceIgnoreExtendService}冲突
 * @deprecated 暂未启用, 需要完善逻辑, 并且注册到 META-INF/services 中
 */
@Deprecated
@OverrideImplementor(SamplingService.class)
class TraceCaptureSpecialSamplingService extends SamplingService {
	private static final ILog LOGGER = LogManager.getLogger(TraceCaptureSpecialSamplingService.class);

	private DyanmicEnabledConfigWatcher dyanmicEnabledTraceSegmentServiceClientConfigWatcher;

	@Override
	public void prepare() {
		super.prepare();
	}

	@Override
	public void boot() {
		super.boot();

		DyanmicEnabledTraceSegmentServiceClient findService = (DyanmicEnabledTraceSegmentServiceClient) ServiceManager.INSTANCE
				.findService(TraceSegmentServiceClient.class);
		dyanmicEnabledTraceSegmentServiceClientConfigWatcher = findService.getConfigWatcher();
	}

	@Override
	public void onComplete() {
	}

	@Override
	public void shutdown() {
		super.shutdown();
	}

	@Override
	public boolean trySampling(final String operationName) {
		if (!dyanmicEnabledTraceSegmentServiceClientConfigWatcher.isEnbaleSendDataToServer()) {
			LOGGER.debug("### discard trace of current operationName [ {} ]", operationName);
			return false;
		}

		if (ContextManager.isActive()
				&& ContextManager.getCorrelationContext().get("__CAPTURE_CURRENT_TRACE__").isPresent()) {
			return true;
		}

		return false;
		//return super.trySampling(operationName);
	}

	@Override
	public void forceSampled() {
		super.forceSampled();
	}

}