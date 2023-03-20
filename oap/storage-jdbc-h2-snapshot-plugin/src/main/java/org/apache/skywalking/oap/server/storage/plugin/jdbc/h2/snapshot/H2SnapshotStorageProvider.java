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

package org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.snapshot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.skywalking.oap.server.core.storage.IHistoryDeleteDAO;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.ServiceNotProvidedException;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.H2StorageProvider;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.snapshot.dao.H2SnapshotHistoryDeleteDAO;

import java.util.Arrays;

/**
 * H2 Storage provider is for demonstration and preview only. I will find that haven't implemented several interfaces,
 * because not necessary, and don't consider about performance very much.
 * <p>
 * If someone wants to implement SQL-style database as storage, please just refer the logic.
 */
public class H2SnapshotStorageProvider extends H2StorageProvider {

    private static final Logger LOGGER = LogManager.getLogger(H2SnapshotStorageProvider.class);

    private H2SnapshotStorageConfig config;

    public H2SnapshotStorageProvider() {
        config = new H2SnapshotStorageConfig();
    }

    @Override
    public String name() {
        return "h2-napshot";
    }

    @Override
    public ModuleConfig createConfigBeanIfAbsent() {
        return config;
    }

    @Override
    public void prepare() throws ServiceNotProvidedException, ModuleStartException {
        super.prepare();

        Arrays.stream(this.getClass().getSuperclass().getDeclaredFields()).filter(s -> s.getName().equals("h2Client")).findAny().ifPresent(t -> {
            try {
                t.setAccessible(true);
                JDBCHikariCPClient h2Client = (JDBCHikariCPClient) t.get(this);
                LOGGER.warn("### register self IHistoryDeleteDAO impl");
                this.registerServiceImplementation(
                        IHistoryDeleteDAO.class, new H2SnapshotHistoryDeleteDAO(h2Client, config));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
