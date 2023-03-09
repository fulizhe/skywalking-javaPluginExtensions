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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.plugin.dynamic.DynamicEnabledGRPCChannelManager;
import org.junit.Assert;
import org.junit.Test;

public class DynamicEnabledGRPCChannelManagerTest {
	@Test
	public void testServiceOverrideFromPlugin() {
		GRPCChannelManager service = ServiceManager.INSTANCE.findService(GRPCChannelManager.class);
		Assert.assertEquals(DynamicEnabledGRPCChannelManager.class, service.getClass());
	}

	private void getMethod(Object obj, final String methodName, Object... parameters) {
		try {		
			final Method method = obj.getClass().getMethod(methodName);
			method.invoke(obj);
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}
}
