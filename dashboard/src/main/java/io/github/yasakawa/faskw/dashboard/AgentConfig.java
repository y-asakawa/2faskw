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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

public record AgentConfig(
        boolean enabled,
        String nodeId,
        Path source,
        Path statePath,
        Path spoolPath,
        long spoolMaxBytes,
        int pollMillis,
        int heartbeatSeconds,
        URI dashboardUrl,
        Path keyStore,
        Path keyStorePasswordFile,
        Path trustStore,
        Path trustStorePasswordFile,
        int batchMaxEvents,
        int batchFlushMillis,
        int connectTimeoutMillis,
        int requestTimeoutMillis,
        int retryInitialMillis,
        int retryMaxMillis,
        PrivacyFilter.UserMode userMode,
        PrivacyFilter.IpMode ipMode,
        Path hmacKeyFile,
        Path reasonMappingFile) {

    private static final Pattern SAFE_NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public static AgentConfig load(final Path path) throws IOException {
        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        final String nodeId = DashboardConfig.value(properties, "agent.nodeId", "");
        final String dashboardUrl = DashboardConfig.value(properties, "agent.dashboardUrl", "");
        final AgentConfig config = new AgentConfig(
                DashboardConfig.bool(properties, "agent.enabled", false),
                nodeId,
                Path.of(DashboardConfig.value(properties, "agent.source",
                        "/opt/shibboleth-idp/logs/graphicalmatrix-audit.log")),
                Path.of(DashboardConfig.value(properties, "agent.statePath",
                        "/var/lib/2faskw-dashboard-agent/state")),
                Path.of(DashboardConfig.value(properties, "agent.spoolPath",
                        "/var/lib/2faskw-dashboard-agent/spool")),
                longValue(properties, "agent.spoolMaxBytes", 268435456L, 1048576L, 10737418240L),
                DashboardConfig.integer(properties, "agent.pollMillis", 500, 100, 60000),
                DashboardConfig.integer(
                        properties, "agent.heartbeatSeconds", 60, 10, 3600),
                dashboardUrl.isBlank() ? null : URI.create(dashboardUrl),
                DashboardConfig.optionalPath(properties, "agent.tls.keyStore"),
                DashboardConfig.optionalPath(properties, "agent.tls.keyStorePasswordFile"),
                DashboardConfig.optionalPath(properties, "agent.tls.trustStore"),
                DashboardConfig.optionalPath(properties, "agent.tls.trustStorePasswordFile"),
                DashboardConfig.integer(properties, "agent.batch.maxEvents", 250, 1, 500),
                DashboardConfig.integer(properties, "agent.batch.flushMillis", 2000, 100, 60000),
                DashboardConfig.integer(properties, "agent.connectTimeoutMillis", 3000, 100, 60000),
                DashboardConfig.integer(properties, "agent.requestTimeoutMillis", 10000, 1000, 300000),
                DashboardConfig.integer(properties, "agent.retry.initialMillis", 1000, 100, 60000),
                DashboardConfig.integer(properties, "agent.retry.maxMillis", 60000, 1000, 600000),
                PrivacyFilter.parseUserMode(
                        DashboardConfig.value(properties, "agent.privacy.userMode", "plain")),
                PrivacyFilter.parseIpMode(
                        DashboardConfig.value(properties, "agent.privacy.ipMode", "plain")),
                DashboardConfig.optionalPath(properties, "agent.privacy.hmacKeyFile"),
                DashboardConfig.optionalPath(properties, "agent.reasonMappingFile"));
        config.validate();
        return config;
    }

    public void validate() throws IOException {
        if (!SAFE_NODE.matcher(nodeId).matches()) {
            throw new IllegalArgumentException(
                    "agent.nodeId must be explicitly set to a safe opaque identifier");
        }
        if (!Files.isReadable(source)) {
            throw new IllegalArgumentException("agent.source is not readable: " + source);
        }
        if (dashboardUrl == null || !"https".equalsIgnoreCase(dashboardUrl.getScheme())
                || dashboardUrl.getHost() == null) {
            throw new IllegalArgumentException("agent.dashboardUrl must be an absolute HTTPS URL");
        }
        for (Path required : new Path[] {
                keyStore, keyStorePasswordFile, trustStore, trustStorePasswordFile}) {
            if (required == null || !Files.isReadable(required)) {
                throw new IllegalArgumentException(
                        "Agent TLS file is missing or unreadable: " + required);
            }
        }
        if (userMode == PrivacyFilter.UserMode.HMAC
                && (hmacKeyFile == null || !Files.isReadable(hmacKeyFile))) {
            throw new IllegalArgumentException(
                    "agent.privacy.userMode=hmac requires a readable hmacKeyFile");
        }
        if (retryInitialMillis > retryMaxMillis) {
            throw new IllegalArgumentException(
                    "agent.retry.initialMillis must not exceed retry.maxMillis");
        }
    }

    private static long longValue(
            final Properties properties,
            final String name,
            final long defaultValue,
            final long minimum,
            final long maximum) {
        final long parsed;
        try {
            parsed = Long.parseLong(
                    DashboardConfig.value(properties, name, Long.toString(defaultValue)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }
}
