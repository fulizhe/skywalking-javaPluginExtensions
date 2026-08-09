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

package org.openskywalking.demo.tracealert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存保存 Agent Webhook 投递的告警,便于本地验证(非生产持久化)。
 * 移植自旧项目 sb-skywalking 的 TraceAlertWebhookStore。
 */
public final class TraceAlertWebhookStore {

    private static final int MAX_EVENTS = 50;

    private static final ConcurrentLinkedDeque<Map<String, Object>> RECENT = new ConcurrentLinkedDeque<>();

    private TraceAlertWebhookStore() {
    }

    public static void record(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        RECENT.addFirst(Collections.unmodifiableMap(payload));
        while (RECENT.size() > MAX_EVENTS) {
            RECENT.pollLast();
        }
    }

    public static List<Map<String, Object>> recent() {
        return Collections.unmodifiableList(new ArrayList<>(RECENT));
    }

    public static void clear() {
        RECENT.clear();
    }

    public static int size() {
        return RECENT.size();
    }
}
