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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public final class ReasonMapper {

    private static final Pattern SAFE_REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("matched", "matched"),
            Map.entry("sequence mismatch", "credential_mismatch"),
            Map.entry("sequence_mismatch", "credential_mismatch"),
            Map.entry("missing enrollment", "enrollment_missing"),
            Map.entry("missing_enrollment", "enrollment_missing"),
            Map.entry("inactive enrollment", "enrollment_inactive"),
            Map.entry("inactive_enrollment", "enrollment_inactive"),
            Map.entry("empty sequence", "sequence_missing"),
            Map.entry("empty_sequence", "sequence_missing"),
            Map.entry("bind failed", "ldap_bind_failed"),
            Map.entry("bind_failed", "ldap_bind_failed"),
            Map.entry("legacy ldap login disabled", "legacy_login_disabled"),
            Map.entry("legacy_ldap_login_disabled", "legacy_login_disabled"),
            Map.entry("one time handoff consumed", "handoff_consumed"),
            Map.entry("one_time_handoff_consumed", "handoff_consumed"));

    private final Map<String, String> mappings;

    public ReasonMapper(final Path path) throws IOException {
        mappings = new HashMap<>();
        DEFAULTS.forEach((key, value) -> mappings.put(normalize(key), value));
        if (path != null && Files.isRegularFile(path)) {
            final Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            for (String name : properties.stringPropertyNames()) {
                final String reason = properties.getProperty(name).trim();
                if (!SAFE_REASON.matcher(reason).matches()) {
                    throw new IOException("invalid reason mapping value for " + name);
                }
                mappings.put(normalize(name), reason);
            }
        }
    }

    public String map(final String detail) {
        if (detail == null || detail.isBlank()) {
            return "unspecified";
        }
        final String normalized = normalize(detail);
        final String direct = mappings.get(normalized);
        if (direct != null) {
            return direct;
        }
        if (normalized.endsWith("exception") || normalized.contains(" exception")) {
            return "internal_error";
        }
        return "other";
    }

    private static String normalize(final String value) {
        return value.trim().toLowerCase().replace('_', ' ').replaceAll("\\s+", " ");
    }
}
