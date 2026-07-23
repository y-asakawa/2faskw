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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GraphicalMatrixLdapConfig {
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\{([^}:]+)(?::([^}]*))?}");

    private final Properties properties;
    private final String url;
    private final String baseDn;
    private final String userFilter;
    private final boolean subtreeSearch;
    private final String bindDn;
    private final String bindCredential;
    private final long connectTimeoutMillis;
    private final long responseTimeoutMillis;
    private final String sequenceAttr;
    private final String initialSequenceAttr;
    private final String statusAttr;
    private final String failedCountAttr;
    private final String lockedUntilAttr;
    private final String mfaMethodAttr;
    private final String totpSeedAttr;
    private final String totpStatusAttr;
    private final String totpRegisteredAtAttr;
    private final String lastSuccessAtAttr;
    private final String forceSequenceChangeAttr;
    private final String stateVersionAttr;
    private final String createdAtAttr;
    private final String updatedAtAttr;

    private GraphicalMatrixLdapConfig(final Properties properties) {
        this.properties = properties;
        this.url = property("graphicalmatrix.ldap.url", "");
        this.baseDn = property("graphicalmatrix.ldap.baseDN", "");
        this.userFilter = property("graphicalmatrix.ldap.userFilter", "(cn={user})");
        this.subtreeSearch = booleanProperty("graphicalmatrix.ldap.subtreeSearch", false);
        this.bindDn = property("graphicalmatrix.ldap.bindDN", "");
        this.bindCredential = secret("graphicalmatrix.ldap.bindCredential",
            "graphicalmatrix.ldap.bindCredentialFile");
        this.connectTimeoutMillis = durationMillis(property("graphicalmatrix.ldap.connectTimeout", "PT3S"));
        this.responseTimeoutMillis = durationMillis(property("graphicalmatrix.ldap.responseTimeout", "PT3S"));
        this.sequenceAttr = attr("sequence", "ldap_sequence");
        this.initialSequenceAttr = attr("initial_sequence", "ldap_initial_sequence");
        this.statusAttr = attr("status", "ldap_status");
        this.failedCountAttr = attr("failed_count", "ldap_failed_count");
        this.lockedUntilAttr = attr("locked_until", "ldap_locked_until");
        this.mfaMethodAttr = attr("mfa_method", "ldap_mfa_method");
        this.totpSeedAttr = attr("totp_seed", "ldap_totp_seed");
        this.totpStatusAttr = attr("totp_status", "ldap_totp_status");
        this.totpRegisteredAtAttr = attr("totp_registered_at", "ldap_totp_registered_at");
        this.lastSuccessAtAttr = attr("last_success_at", "ldap_last_success_at");
        this.forceSequenceChangeAttr = attr("force_sequence_change", "ldap_force_sequence_change");
        this.stateVersionAttr = attr("state_version", "ldap_state_version");
        this.createdAtAttr = attr("created_at", "ldap_created_at");
        this.updatedAtAttr = attr("updated_at", "ldap_updated_at");
        validate();
    }

    static GraphicalMatrixLdapConfig load(final String idpHome) {
        final Properties props = new Properties();
        loadIfPresent(props, graphicalPropertiesPath(idpHome));
        loadIfPresent(props, ldapPropertiesPath(idpHome));
        loadIfPresent(props, Path.of(idpHome, "credentials", "secrets.properties"));
        props.setProperty("idp.home", idpHome);
        return new GraphicalMatrixLdapConfig(props);
    }

    String url() {
        return url;
    }

    String baseDn() {
        return baseDn;
    }

    String userFilter() {
        return userFilter;
    }

    boolean subtreeSearch() {
        return subtreeSearch;
    }

    String bindDn() {
        return bindDn;
    }

    String bindCredential() {
        return bindCredential;
    }

    long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    String sequenceAttr() {
        return sequenceAttr;
    }

    String statusAttr() {
        return statusAttr;
    }

    String failedCountAttr() {
        return failedCountAttr;
    }

    String lockedUntilAttr() {
        return lockedUntilAttr;
    }

    String mfaMethodAttr() {
        return mfaMethodAttr;
    }

    String totpSeedAttr() {
        return totpSeedAttr;
    }

    String totpStatusAttr() {
        return totpStatusAttr;
    }

    String totpRegisteredAtAttr() {
        return totpRegisteredAtAttr;
    }

    String lastSuccessAtAttr() {
        return lastSuccessAtAttr;
    }

    String forceSequenceChangeAttr() {
        return forceSequenceChangeAttr;
    }

    String stateVersionAttr() {
        return stateVersionAttr;
    }

    String updatedAtAttr() {
        return updatedAtAttr;
    }

    String[] returningAttributes() {
        return new String[] {
            sequenceAttr, initialSequenceAttr, statusAttr, failedCountAttr, lockedUntilAttr,
            mfaMethodAttr, totpSeedAttr, totpStatusAttr, totpRegisteredAtAttr, lastSuccessAtAttr,
            forceSequenceChangeAttr, stateVersionAttr, createdAtAttr, updatedAtAttr
        };
    }

    private void validate() {
        require(url, "graphicalmatrix.ldap.url");
        require(baseDn, "graphicalmatrix.ldap.baseDN");
        require(userFilter, "graphicalmatrix.ldap.userFilter");
        require(bindDn, "graphicalmatrix.ldap.bindDN");
        require(bindCredential, "graphicalmatrix.ldap.bindCredential/File");
    }

    private String attr(final String column, final String defaultValue) {
        final String value = property("graphicalmatrix.ldap.attr." + column, "");
        if (!value.isEmpty()) {
            return value;
        }
        return property("graphicalmatrix.ldap." + column, defaultValue);
    }

    private String property(final String key, final String defaultValue) {
        return resolve(properties.getProperty(key, defaultValue), 0);
    }

    private String secret(final String directKey, final String fileKey) {
        final String direct = property(directKey, "");
        if (!direct.isEmpty()) {
            return direct;
        }
        final String file = property(fileKey, "");
        if (file.isEmpty()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read LDAP secret file: " + file, ex);
        }
    }

    private String resolve(final String value, final int depth) {
        if (value == null || depth > 8) {
            return value;
        }
        final Matcher matcher = PLACEHOLDER.matcher(value);
        final StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            final String replacement = properties.getProperty(matcher.group(1), matcher.group(2) != null
                ? matcher.group(2) : "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolve(replacement, depth + 1)));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }

    private boolean booleanProperty(final String key, final boolean defaultValue) {
        final String value = property(key, String.valueOf(defaultValue)).toLowerCase(java.util.Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value) || "on".equals(value);
    }

    private long durationMillis(final String value) {
        if (value == null || value.isBlank()) {
            return 3000L;
        }
        final String trimmed = value.trim();
        if (trimmed.startsWith("PT") && trimmed.endsWith("S")) {
            try {
                return Math.max(1000L, Long.parseLong(trimmed.substring(2, trimmed.length() - 1)) * 1000L);
            } catch (NumberFormatException ex) {
                return 3000L;
            }
        }
        try {
            return Math.max(1000L, Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            return 3000L;
        }
    }

    private static void require(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing LDAP setting: " + label);
        }
    }

    private static void loadIfPresent(final Properties props, final Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load LDAP properties: " + path, ex);
        }
    }

    private static Path graphicalPropertiesPath(final String idpHome) {
        final String override = System.getenv("GRAPHICAL_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_PROPERTIES").trim()
            : "";
        return override.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "graphicalmatrix.properties")
            : Path.of(override);
    }

    static Path ldapPropertiesPath(final String idpHome) {
        final String override = System.getenv("GRAPHICAL_LDAP_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_LDAP_PROPERTIES").trim()
            : "";
        return override.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "ldap.properties")
            : Path.of(override);
    }
}
