/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.yasakawa.faskw;

import java.sql.Connection;
import java.sql.DriverManager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class GraphicalMatrixDataSource {
    private static HikariDataSource dataSource;
    private static String dataSourceKey = "";

    private GraphicalMatrixDataSource() {
    }

    public static Connection getConnection(final String idpHome) throws Exception {
        final GraphicalMatrixDbConfig config = GraphicalMatrixDbConfig.load(idpHome);
        Class.forName(config.getDriver());
        if (!config.isPoolEnabled()) {
            return DriverManager.getConnection(config.getUrl(), config.getUser(), config.getPassword());
        }
        return pooled(config).getConnection();
    }

    public static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            dataSourceKey = "";
        }
    }

    private static synchronized HikariDataSource pooled(final GraphicalMatrixDbConfig config) {
        final String key = config.poolKey();
        if (dataSource != null && key.equals(dataSourceKey)) {
            return dataSource;
        }
        if (dataSource != null) {
            dataSource.close();
        }
        final HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("GraphicalMatrixPool");
        hikari.setDriverClassName(config.getDriver());
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUser());
        hikari.setPassword(config.getPassword());
        hikari.setMaximumPoolSize(positive(config.getPoolMaximumSize(), 10));
        hikari.setMinimumIdle(Math.min(
            positive(config.getPoolMinimumIdle(), 2),
            positive(config.getPoolMaximumSize(), 10)));
        hikari.setConnectionTimeout(positive(config.getPoolConnectionTimeoutMillis(), 30_000L));
        hikari.setIdleTimeout(positive(config.getPoolIdleTimeoutMillis(), 600_000L));
        hikari.setMaxLifetime(positive(config.getPoolMaxLifetimeMillis(), 1_800_000L));
        hikari.setValidationTimeout(positive(config.getPoolValidationTimeoutMillis(), 5_000L));
        hikari.setAutoCommit(true);
        dataSource = new HikariDataSource(hikari);
        dataSourceKey = key;
        return dataSource;
    }

    private static int positive(final int value, final int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(final long value, final long fallback) {
        return value > 0L ? value : fallback;
    }
}
