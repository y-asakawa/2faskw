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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class GraphicalMatrixSpManagementConfig {
    static final String ENABLED = "graphicalmatrix.sp.management.enabled";
    static final String PROFILE_PREFIX = "graphicalmatrix.sp.attributeProfile.";

    private final Path idpHome;
    private final boolean enabled;
    private final Set<String> allowedMetadataHosts;
    private final Set<String> allowedAcsHosts;
    private final int maxMetadataBytes;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final boolean reloadEnabled;
    private final int revisionRetentionDays;
    private final int maxRevisionsPerSp;
    private final int backupRetentionDays;
    private final Map<String, List<String>> attributeProfiles;

    private GraphicalMatrixSpManagementConfig(final Path home, final Properties properties) {
        idpHome = home.toAbsolutePath().normalize();
        enabled = booleanValue(properties, ENABLED, false);
        allowedMetadataHosts = hosts(properties.getProperty(
            "graphicalmatrix.sp.metadata.allowedHosts", ""));
        allowedAcsHosts = hosts(properties.getProperty(
            "graphicalmatrix.sp.metadata.allowedAcsHosts", ""));
        maxMetadataBytes = integerValue(properties,
            "graphicalmatrix.sp.metadata.maxBytes", 1_048_576, 4096, 10_485_760);
        connectTimeout = Duration.ofSeconds(integerValue(properties,
            "graphicalmatrix.sp.metadata.connectTimeoutSeconds", 5, 1, 60));
        readTimeout = Duration.ofSeconds(integerValue(properties,
            "graphicalmatrix.sp.metadata.readTimeoutSeconds", 10, 1, 120));
        reloadEnabled = booleanValue(properties, "graphicalmatrix.sp.reload.enabled", true);
        revisionRetentionDays = integerValue(properties,
            "graphicalmatrix.sp.revision.retentionDays", 180, 1, 3650);
        maxRevisionsPerSp = integerValue(properties,
            "graphicalmatrix.sp.revision.maxPerSp", 50, 1, 500);
        backupRetentionDays = integerValue(properties,
            "graphicalmatrix.sp.backup.retentionDays", 30, 1, 3650);
        attributeProfiles = profiles(properties);
    }

    static GraphicalMatrixSpManagementConfig load(final String idpHome) throws IOException {
        final Path home = Path.of(idpHome);
        final Path path = home.resolve("conf/graphicalmatrix/sp-management.properties");
        final Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        return new GraphicalMatrixSpManagementConfig(home, properties);
    }

    Path idpHome() {
        return idpHome;
    }

    Path configPath() {
        return idpHome.resolve("conf/graphicalmatrix/sp-management.properties");
    }

    boolean enabled() {
        return enabled;
    }

    Set<String> allowedMetadataHosts() {
        return allowedMetadataHosts;
    }

    Set<String> allowedAcsHosts() {
        return allowedAcsHosts;
    }

    int maxMetadataBytes() {
        return maxMetadataBytes;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    Duration readTimeout() {
        return readTimeout;
    }

    boolean reloadEnabled() {
        return reloadEnabled;
    }

    int revisionRetentionDays() {
        return revisionRetentionDays;
    }

    int maxRevisionsPerSp() {
        return maxRevisionsPerSp;
    }

    int backupRetentionDays() {
        return backupRetentionDays;
    }

    Map<String, List<String>> attributeProfiles() {
        return attributeProfiles;
    }

    List<String> attributesFor(final String profile) {
        final List<String> attributes = attributeProfiles.get(profile);
        if (attributes == null) {
            throw new IllegalArgumentException("unknown attribute profile: " + profile);
        }
        return attributes;
    }

    Path metadataProvidersPath() {
        return idpHome.resolve("conf/metadata-providers.xml");
    }

    Path attributeFilterPath() {
        return idpHome.resolve("conf/attribute-filter.xml");
    }

    Path mfaPolicyPath() {
        return idpHome.resolve("conf/graphicalmatrix/mfa-policy.properties");
    }

    Path managedMetadataDirectory() {
        return idpHome.resolve("metadata/2faskw-managed-sp");
    }

    Path disabledMetadataDirectory() {
        return idpHome.resolve("metadata/2faskw-managed-sp-disabled");
    }

    Path registryPath() {
        return idpHome.resolve("conf/graphicalmatrix/sp-management-registry.json");
    }

    Path revisionsDirectory() {
        return idpHome.resolve("credentials/graphicalmatrix/sp-management-revisions");
    }

    Path backupsDirectory() {
        return idpHome.resolve("credentials/graphicalmatrix/sp-management-backups");
    }

    Path auditLogPath() {
        return idpHome.resolve("logs/graphicalmatrix-sp-management-audit.log");
    }

    private static Map<String, List<String>> profiles(final Properties properties) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("none", List.of());
        out.put("uid", List.of("uid"));
        out.put("uid-mail", List.of("uid", "mail"));
        for (final String key : properties.stringPropertyNames()) {
            if (!key.startsWith(PROFILE_PREFIX)) {
                continue;
            }
            final String name = key.substring(PROFILE_PREFIX.length()).trim();
            if (!name.matches("[a-z][a-z0-9-]{0,31}") || out.containsKey(name)) {
                throw new IllegalArgumentException("invalid or reserved attribute profile: " + name);
            }
            final List<String> attributes = csv(properties.getProperty(key));
            if (attributes.isEmpty()) {
                throw new IllegalArgumentException("custom attribute profile is empty: " + name);
            }
            for (final String attribute : attributes) {
                if (!attribute.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
                    throw new IllegalArgumentException("invalid attribute ID in profile " + name);
                }
                final String lower = attribute.toLowerCase(Locale.ROOT);
                if (lower.contains("password") || lower.contains("sequence")
                        || lower.contains("totp") || lower.contains("webauthn")
                        || lower.contains("credential") || lower.contains("admin")) {
                    throw new IllegalArgumentException(
                        "sensitive attribute is not allowed in profile " + name + ": " + attribute);
                }
            }
            out.put(name, List.copyOf(attributes));
        }
        return Map.copyOf(out);
    }

    private static Set<String> hosts(final String value) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String raw : value.split(",", -1)) {
            final String host = raw.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                continue;
            }
            if (!host.matches("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) {
                throw new IllegalArgumentException("invalid allowed host: " + host);
            }
            out.add(host);
        }
        return Set.copyOf(out);
    }

    private static List<String> csv(final String value) {
        final List<String> out = new ArrayList<>();
        for (final String raw : value.split(",", -1)) {
            final String item = raw.trim();
            if (!item.isEmpty() && !out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static boolean booleanValue(final Properties properties, final String key,
            final boolean defaultValue) {
        final String value = properties.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static int integerValue(final Properties properties, final String key,
            final int defaultValue, final int minimum, final int maximum) {
        final String value = properties.getProperty(key, Integer.toString(defaultValue)).trim();
        try {
            final int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer", ex);
        }
    }
}
