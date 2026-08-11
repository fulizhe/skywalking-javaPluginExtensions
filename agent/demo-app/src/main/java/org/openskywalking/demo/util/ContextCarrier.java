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

package org.openskywalking.demo.util;

import java.io.Serializable;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.Setter;

/**
 * <p> CopyFrom {@code apm-agent-core模块中的同名类}
 * @author LQ
 *
 */
@Setter
public class ContextCarrier implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	// ======================== 
	@Getter
	private String traceId;
	/**
	 * The segment id of the parent.
	 */
	@Getter
	private String traceSegmentId;
	/**
	 * The span id in the parent segment.
	 */
	@Getter
	private int spanId = -1;
	@Getter
	private String parentService = "";
	@Getter
	private String parentServiceInstance = "";
	/**
	 * The endpoint(entrance URI/method signature) of the parent service.
	 */
	@Getter
	private String parentEndpoint;
	/**
	 * The network address(ip:port, hostname:port) used in the parent service to access the current service.
	 */
	@Getter
	private String addressUsedAtClient;

	/**
	 * Serialize this {@link ContextCarrier} to a {@link String}, with '|' split.
	 *
	 * @return the serialization string.
	 */
	static String serialize(ContextCarrier contextCarrier) {

		return StrUtil.join("-", "1", Base64.encode(contextCarrier.getTraceId()),
				Base64.encode(contextCarrier.getTraceSegmentId()), contextCarrier.getSpanId() + "",
				Base64.encode(contextCarrier.getParentService()),
				Base64.encode(contextCarrier.getParentServiceInstance()),
				Base64.encode(contextCarrier.getParentEndpoint()),
				Base64.encode(contextCarrier.getAddressUsedAtClient()));

	}

	/**
	 * Initialize fields with the given text.
	 *
	 * @param text carries {@link #traceSegmentId} and {@link #spanId}, with '|' split.
	 */
	public static ContextCarrier deserialize(String text) {
		ContextCarrier contextCarrier = new ContextCarrier();
		String[] parts = text.split("-", 8);
		if (parts.length == 8) {
			try {
				// parts[0] is sample flag, always trace if header exists.
				contextCarrier.traceId = Base64.decodeStr(parts[1]);
				contextCarrier.traceSegmentId = Base64.decodeStr(parts[2]);
				contextCarrier.spanId = Integer.parseInt(parts[3]);
				contextCarrier.parentService = Base64.decodeStr(parts[4]);
				contextCarrier.parentServiceInstance = Base64.decodeStr(parts[5]);
				contextCarrier.parentEndpoint = Base64.decodeStr(parts[6]);
				contextCarrier.addressUsedAtClient = Base64.decodeStr(parts[7]);
			} catch (IllegalArgumentException ignored) {

			}
		}

		return contextCarrier;
	}
}