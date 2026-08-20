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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Preserves and validates the IdP-wide portion of mfa-policy.properties. */
final class GraphicalMatrixMfaPolicyConfig {
    static final String DEFAULT = "graphicalmatrix.mfa.default";
    static final String ORDER = "graphicalmatrix.mfa.policyOrder";
    static final String BYPASS_IPS = "graphicalmatrix.mfa.bypassIPs";
    static final String BYPASS_CIDRS = "graphicalmatrix.mfa.bypassCIDRs";
    static final String FORWARDED = "graphicalmatrix.mfa.useForwardedFor";

    private static final List<String> GLOBAL_KEYS = List.of(
        DEFAULT, ORDER, BYPASS_IPS, BYPASS_CIDRS);
    private static final Set<String> POLICY_KEYS = Set.of(
        DEFAULT, ORDER, BYPASS_IPS, BYPASS_CIDRS, FORWARDED,
        "graphicalmatrix.mfa.forceSPs", "graphicalmatrix.mfa.bypassSPs",
        "graphicalmatrix.mfa.bypassSpCidrs", "graphicalmatrix.mfa.requiredSPs");

    record Settings(String defaultPolicy, String policyOrder, List<String> bypassIps,
                    List<String> bypassCidrs, boolean useForwardedFor) {
        Settings {
            defaultPolicy = defaultPolicy.trim().toLowerCase(Locale.ROOT);
            bypassIps = List.copyOf(bypassIps);
            bypassCidrs = List.copyOf(bypassCidrs);
            policyOrder = validate(defaultPolicy, policyOrder, bypassIps, bypassCidrs,
                useForwardedFor).orderText();
        }
    }

    private GraphicalMatrixMfaPolicyConfig() {
    }

    static Settings load(final Path path) throws IOException {
        final Document document = parse(path);
        final Properties properties = properties(document.values());
        final GraphicalMatrixMfaPolicy policy = GraphicalMatrixMfaPolicy.parse(properties);
        return new Settings(policy.defaultPolicy(), policy.orderText(),
            csv(document.values().get(BYPASS_IPS)),
            csv(document.values().get(BYPASS_CIDRS)),
            Boolean.parseBoolean(document.values().getOrDefault(FORWARDED, "false")));
    }

    static Properties loadProperties(final Path path) throws IOException {
        final Document document = parse(path);
        final Properties properties = properties(document.values());
        GraphicalMatrixMfaPolicy.parse(properties);
        return properties;
    }

    static byte[] render(final Path path, final Settings settings) throws IOException {
        final Document document = parse(path);
        final Map<String, String> replacements = Map.of(
            DEFAULT, settings.defaultPolicy(),
            ORDER, settings.policyOrder(),
            BYPASS_IPS, String.join(",", settings.bypassIps()),
            BYPASS_CIDRS, String.join(",", settings.bypassCidrs()));
        final List<String> output = new ArrayList<>();
        final Set<String> replaced = new LinkedHashSet<>();
        for (final String line : document.lines()) {
            final String key = propertyKey(line);
            if (GLOBAL_KEYS.contains(key)) {
                output.add(key + " = " + replacements.get(key));
                replaced.add(key);
            } else {
                output.add(line);
            }
        }
        for (final String key : GLOBAL_KEYS) {
            if (!replaced.contains(key)) {
                output.add(key + " = " + replacements.get(key));
            }
        }
        final byte[] bytes = (String.join("\n", output) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        validateRendered(bytes);
        return bytes;
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static GraphicalMatrixMfaPolicy validate(final String defaultPolicy, final String order,
            final List<String> bypassIps, final List<String> bypassCidrs,
            final boolean forwarded) {
        final Properties properties = new Properties();
        properties.setProperty(DEFAULT, defaultPolicy);
        properties.setProperty(ORDER, order);
        properties.setProperty(BYPASS_IPS, String.join(",", bypassIps));
        properties.setProperty(BYPASS_CIDRS, String.join(",", bypassCidrs));
        properties.setProperty(FORWARDED, Boolean.toString(forwarded));
        return GraphicalMatrixMfaPolicy.parse(properties);
    }

    private static void validateRendered(final byte[] bytes) {
        final Properties properties = new Properties();
        try {
            properties.load(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid rendered MFA policy", ex);
        }
        GraphicalMatrixMfaPolicy.parse(properties);
    }

    private static Document parse(final Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("MFA policy file is missing: " + path);
        }
        final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String line : lines) {
            final String key = propertyKey(line);
            if (!POLICY_KEYS.contains(key)) {
                continue;
            }
            if (values.putIfAbsent(key, propertyValue(line)) != null) {
                throw new IllegalArgumentException("duplicate MFA property: " + key);
            }
        }
        return new Document(List.copyOf(lines), Map.copyOf(values));
    }

    private static Properties properties(final Map<String, String> values) {
        final Properties properties = new Properties();
        values.forEach(properties::setProperty);
        return properties;
    }

    private static List<String> csv(final String value) {
        final List<String> output = new ArrayList<>();
        if (value == null) {
            return output;
        }
        for (final String token : value.split(",", -1)) {
            final String item = token.trim();
            if (!item.isEmpty() && !output.contains(item)) {
                output.add(item);
            }
        }
        return List.copyOf(output);
    }

    private static String propertyKey(final String line) {
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return "";
        }
        final int separator = trimmed.indexOf('=');
        return separator < 0 ? "" : trimmed.substring(0, separator).trim();
    }

    private static String propertyValue(final String line) {
        final int separator = line.indexOf('=');
        return separator < 0 ? "" : line.substring(separator + 1).trim();
    }

    private record Document(List<String> lines, Map<String, String> values) {
    }
}
