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

package org.apache.skywalking.apm.agent.core.plugin.localprofile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.profile.ProfileTaskChannelService;
import org.apache.skywalking.apm.agent.core.profile.TracingThreadSnapshot;
import org.apache.skywalking.apm.network.language.profile.v3.ThreadSnapshot;
import org.apache.skywalking.apm.network.language.profile.v3.ThreadStack;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ReflectUtil;

/**
 * <p>
 * 向外界提供可观测性. 与 {@code ProfileSnapshotLocalSender}重复了
 * <p>
 * 参考自: {@code }
 */
public class LocalProfileStatusExposeInterceptor implements StaticMethodsAroundInterceptor {
	private static final ILog LOGGER = LogManager.getLogger(LocalProfileStatusExposeInterceptor.class);

	// 借鉴自Druid的JdbcDataSourceStat
	private LinkedHashMap<String, Map<String, Object>> cache;

	public LocalProfileStatusExposeInterceptor() {
		cache = new LinkedHashMap<String, Map<String, Object>>(16, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
				return (size() > 500);

			}
		};
	}

	@Override
	public void beforeMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			MethodInterceptResult result) {

		LOGGER.info("### Status Expose222");

		// 1. =================================================================
		final ProfileTaskChannelService client = (ProfileTaskChannelService) ServiceManager.INSTANCE
				.findService(ProfileTaskChannelService.class);
		// 这里必须使用反射来获取, 不要尝试进行类型转换为真实类型
		BlockingQueue<TracingThreadSnapshot> snapshotQueue = (BlockingQueue<TracingThreadSnapshot>) ReflectUtil
				.getFieldValue(client, "snapshotQueue");
		List<Object> temp = CollUtil.newArrayList();
		snapshotQueue.drainTo(temp);

		// 将TracingThreadSnapshot对象转换为Map，并存入cache
		cache.clear();
		for (Object obj : temp) {
			final TracingThreadSnapshot snapshot = (TracingThreadSnapshot) obj;
			final ThreadSnapshot transform = snapshot.transform();
			final ThreadStack stack = transform.getStack();

			// 从transform对象中获取信息，填入map
			String taskId = transform.getTaskId();
			String traceSegmentId = transform.getTraceSegmentId();
			Map<String, Object> map = new HashMap<>();
			map.put("taskId", transform.getTaskId());
			map.put("traceSegmentId", transform.getTraceSegmentId());
			map.put("time", transform.getTime());
			map.put("sequence", transform.getSequence());
			// 将stackList转换为List<Map>，每个元素为一帧的详细信息
			List<Map<String, Object>> stackMapList = new ArrayList<>();
			ThreadStack stackList = transform.getStack();
			List<String> codeSignaturesList = new ArrayList<>(stackList.getCodeSignaturesList());
			map.put("stack", codeSignaturesList);

			cache.put(IdUtil.fastSimpleUUID(), map);

		}

		// 6. =================================================================
		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("data", cache);

		result.defineReturnValue(resultMap);
	}

	@Override
	public Object afterMethod(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Object ret) {
		return ret;
	}

	@Override
	public void handleMethodException(Class clazz, Method method, Object[] allArguments, Class<?>[] parameterTypes,
			Throwable t) {
		ContextManager.activeSpan().errorOccurred().log(t);
	}
}
