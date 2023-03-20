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

package org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.snapshot.dao;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.skywalking.oap.server.core.storage.model.Model;
import org.apache.skywalking.oap.server.library.client.jdbc.JDBCClientException;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.SQLBuilder;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao.H2HistoryDeleteDAO;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.snapshot.H2SnapshotStorageConfig;
import org.joda.time.DateTime;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.stream.Collectors;

public class H2SnapshotHistoryDeleteDAO extends H2HistoryDeleteDAO {

    private static final Logger LOGGER = LogManager.getLogger(H2SnapshotHistoryDeleteDAO.class);

    private final JDBCHikariCPClient client;

    private final H2SnapshotStorageConfig config;

    public H2SnapshotHistoryDeleteDAO(JDBCHikariCPClient client, final H2SnapshotStorageConfig config) {
        super(client);
        this.client = client;
        this.config = config;
        LOGGER.warn("### the ttl of custom config is [ {} ]", config.getRecordDataTTL());
    }

    @Override
    public void deleteHistory(Model model, String timeBucketColumnName, int ttl) throws IOException {
        SQLBuilder dataDeleteSQL = new SQLBuilder("delete from " + model.getName() + " where ")
                .append(timeBucketColumnName).append("<= ? ");

        // get the bigger one
        ttl = (config.getRecordDataTTL() > ttl) ? config.getRecordDataTTL() : ttl;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "### current model which will deleted is [ {} ], the record is [ {} ], the downsample is [ {} ], the ttl is [ {} ], this config ttl is [ {} ]. the columns is [ {} ]",
                    model.getName(), model.isRecord(), model.getDownsampling(), ttl, config.getRecordDataTTL(),
                    model.getColumns().stream().map(s -> s.getColumnName().getName()).collect(Collectors.joining(",")));
        }

        try (Connection connection = client.getConnection()) {
            long deadline;
            if (model.isRecord()) {
                deadline = Long.parseLong(new DateTime().plusMinutes(-ttl).toString("yyyyMMddHHmmss"));
            } else {
                switch (model.getDownsampling()) {
                    case Minute:
                        deadline = Long.parseLong(new DateTime().plusMinutes(-ttl).toString("yyyyMMddHHmm"));
                        break;
                    case Hour:
                        deadline = Long.parseLong(new DateTime().plusMinutes(-ttl).toString("yyyyMMddHHmm"));
                        break;
                    case Day:
                        deadline = Long.parseLong(new DateTime().plusMinutes(-ttl).toString("yyyyMMddHHmm"));
                        break;
                    default:
                        return;
                }
            }
            client.executeUpdate(connection, dataDeleteSQL.toString(), deadline);
        } catch (JDBCClientException | SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
