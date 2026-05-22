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

package org.apache.skywalking.apm.plugin.hutool.v5.http;

import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.AgentPackagePath;
import org.apache.skywalking.apm.agent.core.conf.ConfigNotFoundException;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.util.ConfigInitializer;
import org.apache.skywalking.apm.util.PropertyPlaceholderHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Historical config initializer kept in the tree to preserve the earlier
 * standalone Hutool configuration loading attempt.
 * <p> This initializer is no longer invoked by the active Hutool override.
 * <p> Replacement:
 * <ul>
 *   <li>active switch sources:
 *   {@code org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig} and
 *   {@code org.apache.skywalking.apm.plugin.override.httpclient.v4.OverrideHttpClientPluginConfig}</li>
 *   <li>Hutool-side switch adapter:
 *   {@code org.apache.skywalking.apm.plugin.hutool.v5.http.HutoolHttpCollectionSwitch}</li>
 *   <li>active request collection path:
 *   {@code org.apache.skywalking.apm.plugin.hutool.v5.http.HutoolHttpRequestInterceptor}</li>
 * </ul>
 *
 * @deprecated Not used by the current Hutool override implementation.
 */
@Deprecated
class HutoolHttpRequestConfigInitializer {
    private static final ILog LOGGER = LogManager.getLogger(HutoolHttpRequestConfigInitializer.class);
    private static final String CONFIG_FILE_NAME = "/config/apm-trace-ignore-plugin.config";
    private static final String ENV_KEY_PREFIX = "skywalking.";

    public static void initialize() {
        try (final InputStream configFileStream = loadConfigFromAgentFolder()) {
            Properties properties = new Properties();
            properties.load(configFileStream);
            for (String key : properties.stringPropertyNames()) {
                String value = (String) properties.get(key);
                properties.put(key, PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(value, properties));
            }
            ConfigInitializer.initialize(properties, HutoolHttpRequestConfig.class);
        } catch (Exception e) {
            LOGGER.error(e, "Failed to read the config file, skywalking is going to run in default config.");
        }

        try {
            overrideConfigBySystemProp();
        } catch (Exception e) {
            LOGGER.error(e, "Failed to read the system env.");
        }
    }

    private static void overrideConfigBySystemProp() throws IllegalAccessException {
        Properties properties = new Properties();
        Properties systemProperties = System.getProperties();
        for (final Map.Entry<Object, Object> prop : systemProperties.entrySet()) {
            if (prop.getKey().toString().startsWith(ENV_KEY_PREFIX)) {
                String realKey = prop.getKey().toString().substring(ENV_KEY_PREFIX.length());
                properties.put(realKey, prop.getValue());
            }
        }

        if (!properties.isEmpty()) {
            ConfigInitializer.initialize(properties, HutoolHttpRequestConfig.class);
        }
    }

    private static InputStream loadConfigFromAgentFolder() throws AgentPackageNotFoundException, ConfigNotFoundException {
        File configFile = new File(AgentPackagePath.getPath(), CONFIG_FILE_NAME);
        if (configFile.exists() && configFile.isFile()) {
            try {
                LOGGER.info("Ignore config file found in {}.", configFile);
                return new FileInputStream(configFile);
            } catch (FileNotFoundException e) {
                throw new ConfigNotFoundException("Fail to load apm-trace-ignore-plugin.config", e);
            }
        }
        throw new ConfigNotFoundException("Fail to load ignore config file.");
    }
}
