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

// 本文件与官方 apm-httpclient-5.x-plugin (v9.4.0) 中的 Constants 同源, 未修改, 保持原样。
// 原因: 本 override 插件整包替代官方插件(避免同步路径双重增强), 异步 tracing 由这份原样副本延续。
package org.apache.skywalking.apm.plugin.override.httpclient.v5;

public class Constants {

    public static String SKYWALKING_CONTEXT_SNAPSHOT = "skywalking-context-snapshot";
}
