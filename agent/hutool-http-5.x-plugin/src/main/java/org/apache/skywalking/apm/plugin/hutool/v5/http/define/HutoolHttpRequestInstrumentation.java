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

package org.apache.skywalking.apm.plugin.hutool.v5.http.define;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.named;
import static org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static org.apache.skywalking.apm.agent.core.plugin.match.NameMatch.byName;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;

//import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
//import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
//import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
//import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
//import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.matcher.ElementMatcher;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.description.method.MethodDescription;


/**
 * 
 */
public class HutoolHttpRequestInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {
    private static final String ENHANCE_CLASS = "cn.hutool.http.HttpRequest";
    private static final String ENHANCE_METHOD = "execute";
    private static final String INTERCEPTOR_CLASS = "org.apache.skywalking.apm.plugin.hutool.v5.http.HutoolHttpRequestInterceptor";

	private static final ILog LOGGER = LogManager.getLogger(HutoolHttpRequestInstrumentation.class);

    
    @Override
    protected ClassMatch enhanceClass() {    
		LOGGER.warn("### enhanceClass-hutool, {} ", ENHANCE_CLASS);
        return byName(ENHANCE_CLASS);
    }

    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return new ConstructorInterceptPoint[0];
    }

    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[]{
        		new InstanceMethodsInterceptPoint() {
                    @Override
                    public ElementMatcher<MethodDescription> getMethodsMatcher() {
                		LOGGER.warn("### getMethodsMatcher-hutool, {} ", ENHANCE_METHOD);

                        return named(ENHANCE_METHOD).and(takesArguments(boolean.class));
                    }

                    @Override
                    public String getMethodsInterceptor() {
                    	LOGGER.warn("### getMethodsInterceptor-hutool, {} ", ENHANCE_METHOD);
                        return INTERCEPTOR_CLASS;
                    }

                    @Override
                    public boolean isOverrideArgs() {
                        return false;
                    }
                }      		
        };
    }

    @Override
    public StaticMethodsInterceptPoint[] getStaticMethodsInterceptPoints() {
        return new StaticMethodsInterceptPoint[0];
    }
}
