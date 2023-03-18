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

package org.apache.skywalking.apm.plugin.dynamic.ignore;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.remote.TraceSegmentServiceClient;
import org.apache.skywalking.apm.agent.test.tools.AgentServiceRule;
import org.apache.skywalking.apm.plugin.dynamic.override.DyanmicEnabledConfigWatcher;
import org.apache.skywalking.apm.plugin.dynamic.override.DyanmicEnabledTraceSegmentServiceClient;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.powermock.reflect.Whitebox;

public class DyanmicEnabledTraceSegmentServiceClientTest {

	@Rule
	public final EnvironmentVariables environmentVariables = new EnvironmentVariables()
			.set("SW_AGENT_TRACE_IGNORE_PATH", "path_test");

	@Rule
	public AgentServiceRule serviceRule = new AgentServiceRule();

	@Test
	public void testServiceOverrideFromPlugin() {
		TraceSegmentServiceClient service = ServiceManager.INSTANCE.findService(TraceSegmentServiceClient.class);
		Assert.assertEquals(DyanmicEnabledTraceSegmentServiceClient.class, service.getClass());
	}

	@Test
	public void test2() {
		TraceSegmentServiceClient service = ServiceManager.INSTANCE.findService(TraceSegmentServiceClient.class);
		Whitebox.setInternalState(service, "dyanmicEnabledTraceSegmentServiceClientConfigWatcher",
				new DyanmicEnabledConfigWatcher(""));
	}
}
