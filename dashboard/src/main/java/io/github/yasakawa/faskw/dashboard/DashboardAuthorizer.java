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

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;

final class DashboardAuthorizer {

    enum Role {
        DASHBOARD_VIEWER(1),
        DASHBOARD_OPERATOR(2),
        DASHBOARD_AUDITOR(3),
        DASHBOARD_ADMIN(4);

        private final int rank;

        Role(final int rank) {
            this.rank = rank;
        }

        boolean permits(final Role required) {
            return rank >= required.rank;
        }

        static Role parse(final String value) {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    record Principal(String user, Role role, String rateLimitKey) {
    }

    private final DashboardConfig config;
    private final CidrMatcher trustedProxies;
    private final CidrMatcher localNetworks;
    private final Role localRole;
    private final Properties roles;
    private final byte[] proxySecret;

    DashboardAuthorizer(final DashboardConfig config) throws IOException {
        this.config = config;
        trustedProxies = "proxy".equals(config.authMode())
                ? new CidrMatcher(
                        config.trustedProxies(),
                        "dashboard.auth.trustedProxies",
                        false)
                : null;
        localNetworks = "local".equals(config.authMode())
                ? new CidrMatcher(
                        config.localAllowedCidrs(),
                        "dashboard.auth.localAllowedCIDRs",
                        true)
                : null;
        localRole = "local".equals(config.authMode())
                ? Role.parse(config.localRole())
                : null;
        roles = new Properties();
        proxySecret = "proxy".equals(config.authMode())
                ? Files.readString(config.proxySecretFile(), StandardCharsets.UTF_8)
                        .strip().getBytes(StandardCharsets.UTF_8)
                : null;
        if ("proxy".equals(config.authMode())
                && config.rolesFile() != null
                && Files.isReadable(config.rolesFile())) {
            try (InputStream input = Files.newInputStream(config.rolesFile())) {
                roles.load(input);
            }
        }
    }

    boolean permitsSource(final HttpExchange exchange) {
        return localNetworks == null
                || localNetworks.matches(exchange.getRemoteAddress().getAddress());
    }

    Principal authorize(final HttpExchange exchange, final Role required) {
        final Principal principal = authenticate(exchange);
        if (principal == null || !principal.role().permits(required)) {
            return null;
        }
        return principal;
    }

    private Principal authenticate(final HttpExchange exchange) {
        if ("none".equals(config.authMode())) {
            return new Principal("local", Role.DASHBOARD_ADMIN, "user:local");
        }
        if ("local".equals(config.authMode())) {
            if (!permitsSource(exchange)) {
                return null;
            }
            final String address =
                    exchange.getRemoteAddress().getAddress().getHostAddress();
            return new Principal(
                    "local-network",
                    localRole,
                    "address:" + address);
        }
        if (!trustedProxies.matches(exchange.getRemoteAddress().getAddress())) {
            return null;
        }
        final String suppliedSecret = header(exchange, config.proxySecretHeader());
        if (suppliedSecret == null || suppliedSecret.length() > 512
                || !MessageDigest.isEqual(proxySecret,
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        final String user = header(exchange, config.remoteUserHeader());
        if (user == null || user.isBlank() || user.length() > 128 || hasControl(user)) {
            return null;
        }
        final String mapped = roles.getProperty(user);
        final String supplied = mapped != null ? mapped : header(exchange, config.roleHeader());
        if (supplied == null || supplied.isBlank()) {
            return null;
        }
        try {
            return new Principal(
                    user,
                    Role.parse(supplied.split("[,;\\s]+", 2)[0]),
                    "user:" + user);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String header(final HttpExchange exchange, final String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static boolean hasControl(final String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
