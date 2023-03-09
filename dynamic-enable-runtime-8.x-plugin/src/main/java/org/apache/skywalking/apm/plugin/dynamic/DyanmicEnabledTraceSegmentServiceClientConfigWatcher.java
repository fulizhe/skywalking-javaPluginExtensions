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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.skywalking.apm.agent.core.conf.dynamic.AgentConfigChangeWatcher;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

public class DyanmicEnabledTraceSegmentServiceClientConfigWatcher extends AgentConfigChangeWatcher {
	private static final ILog LOGGER = LogManager.getLogger(DyanmicEnabledTraceSegmentServiceClientConfigWatcher.class);

	private final AtomicBoolean enable;

	public DyanmicEnabledTraceSegmentServiceClientConfigWatcher(final String propertyKey) {
		super(propertyKey);
		this.enable = new AtomicBoolean(false);
	}

	private void enable(boolean e) {
		if (LOGGER.isDebugEnable()) {
			LOGGER.debug("Updating using new static config: {}", e);
		}

		enable.getAndSet(e);
		LOGGER.warn("### current agent-enable status is [ {} ]", isEnbaleSendDataToServer());
	}

	@Override
	public void notify(final ConfigChangeEvent value) {
		LOGGER.warn("### change agent-enable status to [ {} ]", value.getNewValue());
		if (EventType.DELETE.equals(value.getEventType())) {
			enable(false);
		} else {
			enable(Boolean.parseBoolean(value.getNewValue()));
		}
	}

	@Override
	public String value() {
		return Objects.toString(enable.get());
	}

	public boolean isEnbaleSendDataToServer() {
		return enable.get();
	}
}
