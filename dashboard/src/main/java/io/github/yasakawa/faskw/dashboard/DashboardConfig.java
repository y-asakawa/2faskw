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
package io.github.yasakawa.faskw.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public record DashboardConfig(
        boolean enabled,
        String bindAddress,
        int port,
        String basePath,
        Path storagePath,
        int retentionDays,
        int aggregateRetentionDays,
        int defaultHours,
        int maxRangeDays,
        int pageSize,
        int maxPageSize,
        int maxExportRows,
        String authMode,
        String trustedProxies,
        String remoteUserHeader,
        String roleHeader,
        Path rolesFile,
        String localAllowedCidrs,
        String localRole,
        boolean exportEnabled,
        boolean ingestEnabled,
        String ingestBindAddress,
        int ingestPort,
        boolean requireMtls,
        int maxBatchEvents,
        int maxRequestBytes,
        int allowedClockSkewSeconds,
        Path ingestKeyStore,
        Path ingestKeyStorePasswordFile,
        Path ingestTrustStore,
        Path ingestTrustStorePasswordFile,
        int staleEventSeconds,
        int parseFailureWarningCount) {

    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int MAX_RETENTION_DAYS = 90;
    static final int DEFAULT_AGGREGATE_RETENTION_DAYS = 90;
    static final int MAX_AGGREGATE_RETENTION_DAYS = 180;
    static final int DEFAULT_MAX_RANGE_DAYS = 100;
    static final int MAX_RANGE_DAYS = 100;

    public static DashboardConfig load(final Path path) throws IOException {
        return load(path, true);
    }

    static DashboardConfig loadForOffline(final Path path) throws IOException {
        return load(path, false);
    }

    private static DashboardConfig load(final Path path, final boolean validateIngestTls)
            throws IOException {
        final Properties properties = loadProperties(path);
        final DashboardConfig config = new DashboardConfig(
                bool(properties, "dashboard.enabled", false),
                value(properties, "dashboard.http.bindAddress", "127.0.0.1"),
                integer(properties, "dashboard.http.port", 9080, 1, 65535),
                normalizeBasePath(value(properties, "dashboard.http.basePath", "/2faskw-dashboard")),
                Path.of(value(properties, "dashboard.storage.path",
                        "/var/lib/2faskw-dashboard/dashboard")),
                integer(properties, "dashboard.storage.retentionDays",
                        DEFAULT_RETENTION_DAYS, 1, MAX_RETENTION_DAYS),
                integer(properties, "dashboard.storage.aggregateRetentionDays",
                        DEFAULT_AGGREGATE_RETENTION_DAYS,
                        1, MAX_AGGREGATE_RETENTION_DAYS),
                integer(properties, "dashboard.query.defaultHours", 24, 1, 744),
                integer(properties, "dashboard.query.maxRangeDays",
                        DEFAULT_MAX_RANGE_DAYS, 1, MAX_RANGE_DAYS),
                integer(properties, "dashboard.query.pageSize", 100, 1, 500),
                integer(properties, "dashboard.query.maxPageSize", 500, 1, 1000),
                integer(properties, "dashboard.query.maxExportRows", 10000, 1, 100000),
                value(properties, "dashboard.auth.mode", "proxy").toLowerCase(Locale.ROOT),
                value(properties, "dashboard.auth.trustedProxies", "127.0.0.1/32,::1/128"),
                value(properties, "dashboard.auth.remoteUserHeader", "X-Remote-User"),
                value(properties, "dashboard.auth.roleHeader", "X-2FASKW-Role"),
                Path.of(value(properties, "dashboard.auth.rolesFile",
                        "/etc/2faskw-dashboard/roles.properties")),
                value(properties, "dashboard.auth.localAllowedCIDRs", ""),
                value(properties, "dashboard.auth.localRole", "DASHBOARD_VIEWER")
                        .toUpperCase(Locale.ROOT),
                bool(properties, "dashboard.export.enabled", false),
                bool(properties, "dashboard.ingest.enabled", true),
                value(properties, "dashboard.ingest.bindAddress", "0.0.0.0"),
                integer(properties, "dashboard.ingest.port", 9443, 1, 65535),
                bool(properties, "dashboard.ingest.requireMtls", true),
                integer(properties, "dashboard.ingest.maxBatchEvents", 500, 1, 5000),
                integer(properties, "dashboard.ingest.maxRequestBytes", 1048576, 1024, 16 * 1024 * 1024),
                integer(properties, "dashboard.ingest.allowedClockSkewSeconds", 300, 0, 86400),
                optionalPath(properties, "dashboard.ingest.keyStore"),
                optionalPath(properties, "dashboard.ingest.keyStorePasswordFile"),
                optionalPath(properties, "dashboard.ingest.trustStore"),
                optionalPath(properties, "dashboard.ingest.trustStorePasswordFile"),
                integer(properties, "dashboard.health.staleEventSeconds", 900, 30, 86400),
                integer(properties, "dashboard.health.parseFailureWarningCount", 1, 1, 1000000));
        config.validate(validateIngestTls);
        return config;
    }

    public void validate() throws IOException {
        validate(true);
    }

    private void validate(final boolean validateIngestTls) throws IOException {
        final InetAddress bind = InetAddress.getByName(bindAddress);
        if (!List.of("none", "local", "proxy").contains(authMode)) {
            throw new IllegalArgumentException(
                    "dashboard.auth.mode must be none, local, or proxy");
        }
        if ("none".equals(authMode) && !bind.isLoopbackAddress()) {
            throw new IllegalArgumentException(
                    "dashboard.auth.mode=none may only bind to a loopback address");
        }
        if ("local".equals(authMode)) {
            if (bind.isAnyLocalAddress()) {
                throw new IllegalArgumentException(
                        "dashboard.auth.mode=local must bind to a specific interface address");
            }
            new CidrMatcher(
                    localAllowedCidrs,
                    "dashboard.auth.localAllowedCIDRs",
                    true);
            if (!List.of("DASHBOARD_VIEWER", "DASHBOARD_OPERATOR").contains(localRole)) {
                throw new IllegalArgumentException(
                        "dashboard.auth.localRole must be DASHBOARD_VIEWER"
                                + " or DASHBOARD_OPERATOR");
            }
        }
        if ("proxy".equals(authMode)) {
            new CidrMatcher(
                    trustedProxies,
                    "dashboard.auth.trustedProxies",
                    false);
        }
        if (pageSize > maxPageSize) {
            throw new IllegalArgumentException(
                    "dashboard.query.pageSize must not exceed maxPageSize");
        }
        if (aggregateRetentionDays < retentionDays) {
            throw new IllegalArgumentException(
                    "dashboard.storage.aggregateRetentionDays must not be less than "
                            + "dashboard.storage.retentionDays");
        }
        if (port == ingestPort && bindAddress.equals(ingestBindAddress) && ingestEnabled) {
            throw new IllegalArgumentException("UI and ingest listeners must use different sockets");
        }
        if (ingestEnabled) {
            if (!requireMtls) {
                throw new IllegalArgumentException("dashboard ingest requires mTLS");
            }
            if (validateIngestTls) {
                for (Path required : new Path[] {
                        ingestKeyStore,
                        ingestKeyStorePasswordFile,
                        ingestTrustStore,
                        ingestTrustStorePasswordFile}) {
                    if (required == null || !Files.isReadable(required)) {
                        throw new IllegalArgumentException(
                                "dashboard ingest TLS file is missing or unreadable: " + required);
                    }
                }
            }
        }
    }

    private static Properties loadProperties(final Path path) throws IOException {
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    static String value(final Properties properties, final String name, final String defaultValue) {
        return properties.getProperty(name, defaultValue).trim();
    }

    static boolean bool(final Properties properties, final String name, final boolean defaultValue) {
        final String raw = value(properties, name, Boolean.toString(defaultValue));
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(raw);
    }

    static int integer(
            final Properties properties,
            final String name,
            final int defaultValue,
            final int minimum,
            final int maximum) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value(properties, name, Integer.toString(defaultValue)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    static Path optionalPath(final Properties properties, final String name) {
        final String raw = value(properties, name, "");
        return raw.isEmpty() ? null : Path.of(raw);
    }

    private static String normalizeBasePath(final String value) {
        if (!value.startsWith("/") || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("dashboard.http.basePath is invalid");
        }
        if (value.length() > 1 && value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
