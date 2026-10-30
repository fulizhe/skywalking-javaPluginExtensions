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
 */

// 本文件与官方 apm-httpclient-5.x-plugin (v9.4.0) 中的 FutureCallbackWrapper 同源,
// 未修改, 保持原样(异步路径不加入参/出参采集)。
// 原因: 本 override 插件整包替代官方插件, 异步 tracing 必须由这份副本原样延续。
package org.apache.skywalking.apm.plugin.override.httpclient.v5.wrapper;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.skywalking.apm.agent.core.context.ContextManager;

public class FutureCallbackWrapper<T> implements FutureCallback<T> {

    private FutureCallback<T> callback;

    public FutureCallbackWrapper(FutureCallback<T> callback) {
        this.callback = callback;
    }

    @Override
    public void completed(T o) {
        if (ContextManager.isActive()) {
            ContextManager.stopSpan();
        }
        if (callback != null) {
            callback.completed(o);
        }
    }

    @Override
    public void failed(Exception e) {
        if (ContextManager.isActive()) {
            ContextManager.activeSpan().errorOccurred().log(e);
            ContextManager.stopSpan();
        }
        if (callback != null) {
            callback.failed(e);
        }
    }

    @Override
    public void cancelled() {
        if (ContextManager.isActive()) {
            ContextManager.activeSpan().errorOccurred();
            ContextManager.stopSpan();
        }
        if (callback != null) {
            callback.cancelled();
        }
    }
}
