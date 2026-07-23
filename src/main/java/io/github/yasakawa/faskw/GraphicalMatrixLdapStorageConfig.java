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
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GraphicalMatrixLdapStorageConfig {
    static final String LAYOUT_SUBTREE = "subtree";
    static final String LAYOUT_USER_ENTRY = "user-entry";

    private static final Pattern PLACEHOLDER = Pattern.compile("%\\{([^}:]+)(?::([^}]*))?}");
    private static final String PREFIX = "graphicalmatrix.webauthn.ldap.";

    private final Properties properties;
    private final String layout;
    private final String url;
    private final String bindDn;
    private final String bindCredential;
    private final long connectTimeoutMillis;
    private final long responseTimeoutMillis;
    private final String valueStorage;
    private final String baseDn;
    private final String[] objectClasses;
    private final String recordRdnAttr;
    private final String userBaseDn;
    private final String userFilter;
    private final boolean userSubtreeSearch;
    private final String contextAttr;
    private final String idAttr;
    private final String expiresAttr;
    private final String valueAttr;
    private final String versionAttr;

    private GraphicalMatrixLdapStorageConfig(final Properties properties) {
        this.properties = properties;
        this.layout = lower(property(PREFIX + "layout", LAYOUT_SUBTREE));
        this.url = property(PREFIX + "url", property("graphicalmatrix.ldap.url", ""));
        this.bindDn = property(PREFIX + "bindDN", property("graphicalmatrix.ldap.bindDN", ""));
        this.bindCredential = secret(PREFIX + "bindCredential", PREFIX + "bindCredentialFile",
            "graphicalmatrix.ldap.bindCredential", "graphicalmatrix.ldap.bindCredentialFile");
        this.connectTimeoutMillis = durationMillis(property(PREFIX + "connectTimeout",
            property("graphicalmatrix.ldap.connectTimeout", "PT3S")));
        this.responseTimeoutMillis = durationMillis(property(PREFIX + "responseTimeout",
            property("graphicalmatrix.ldap.responseTimeout", "PT3S")));
        this.valueStorage = lower(property(PREFIX + "value.storage", "plaintext"));
        this.baseDn = property(PREFIX + "baseDN", "");
        this.objectClasses = list(property(PREFIX + "objectClasses",
            "top,organizationalRole,extensibleObject"));
        this.recordRdnAttr = property(PREFIX + "recordRdnAttr", "cn");
        this.userBaseDn = property(PREFIX + "userBaseDN", property("graphicalmatrix.ldap.baseDN", ""));
        this.userFilter = property(PREFIX + "userFilter", "(cn={id})");
        this.userSubtreeSearch = booleanProperty(PREFIX + "userSubtreeSearch", true);
        this.contextAttr = attr("context", "gmStorageContext");
        this.idAttr = attr("id", "gmStorageId");
        this.expiresAttr = attr("expires", "gmStorageExpires");
        this.valueAttr = attr("value", "gmStorageValue");
        this.versionAttr = attr("version", "gmStorageVersion");
        validate();
    }

    static GraphicalMatrixLdapStorageConfig load(final String idpHome) {
        final Properties props = new Properties();
        loadIfPresent(props, graphicalPropertiesPath(idpHome));
        loadIfPresent(props, GraphicalMatrixLdapConfig.ldapPropertiesPath(idpHome));
        loadIfPresent(props, webauthnLdapPropertiesPath(idpHome));
        loadIfPresent(props, Path.of(idpHome, "credentials", "secrets.properties"));
        props.setProperty("idp.home", idpHome);
        return new GraphicalMatrixLdapStorageConfig(props);
    }

    String layout() {
        return layout;
    }

    String url() {
        return url;
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

    String baseDn() {
        return baseDn;
    }

    String[] objectClasses() {
        return objectClasses.clone();
    }

    String recordRdnAttr() {
        return recordRdnAttr;
    }

    String userBaseDn() {
        return userBaseDn;
    }

    String userFilter() {
        return userFilter;
    }

    boolean userSubtreeSearch() {
        return userSubtreeSearch;
    }

    String contextAttr() {
        return contextAttr;
    }

    String idAttr() {
        return idAttr;
    }

    String expiresAttr() {
        return expiresAttr;
    }

    String valueAttr() {
        return valueAttr;
    }

    String versionAttr() {
        return versionAttr;
    }

    String[] returningAttributes() {
        return new String[] {
            contextAttr, idAttr, expiresAttr, valueAttr, versionAttr
        };
    }

    private void validate() {
        require(url, PREFIX + "url");
        require(bindDn, PREFIX + "bindDN");
        require(bindCredential, PREFIX + "bindCredential/File");
        if (!LAYOUT_SUBTREE.equals(layout) && !LAYOUT_USER_ENTRY.equals(layout)) {
            throw new IllegalArgumentException("Unsupported WebAuthn LDAP layout: " + layout);
        }
        if (!"plaintext".equals(valueStorage)) {
            throw new IllegalArgumentException(
                "Unsupported WebAuthn LDAP value storage: " + valueStorage + " (only plaintext is implemented)");
        }
        if (LAYOUT_SUBTREE.equals(layout)) {
            require(baseDn, PREFIX + "baseDN");
            require(recordRdnAttr, PREFIX + "recordRdnAttr");
            if (objectClasses.length == 0) {
                throw new IllegalArgumentException("Missing WebAuthn LDAP objectClasses");
            }
        } else {
            require(userBaseDn, PREFIX + "userBaseDN");
            require(userFilter, PREFIX + "userFilter");
        }
        require(contextAttr, PREFIX + "attr.context");
        require(idAttr, PREFIX + "attr.id");
        require(expiresAttr, PREFIX + "attr.expires");
        require(valueAttr, PREFIX + "attr.value");
        require(versionAttr, PREFIX + "attr.version");
    }

    private String attr(final String column, final String defaultValue) {
        return property(PREFIX + "attr." + column, defaultValue);
    }

    private String property(final String key, final String defaultValue) {
        return resolve(properties.getProperty(key, defaultValue), 0);
    }

    private String secret(final String directKey, final String fileKey,
            final String fallbackDirectKey, final String fallbackFileKey) {
        final String direct = property(directKey, "");
        if (!direct.isEmpty()) {
            return direct;
        }
        final String file = property(fileKey, "");
        if (!file.isEmpty()) {
            return readSecret(file);
        }
        final String fallbackDirect = property(fallbackDirectKey, "");
        if (!fallbackDirect.isEmpty()) {
            return fallbackDirect;
        }
        final String fallbackFile = property(fallbackFileKey, "");
        return fallbackFile.isEmpty() ? "" : readSecret(fallbackFile);
    }

    private static String readSecret(final String file) {
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
        final String value = lower(property(key, String.valueOf(defaultValue)));
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

    private static String[] list(final String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .toArray(String[]::new);
    }

    private static String lower(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void require(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing WebAuthn LDAP setting: " + label);
        }
    }

    private static void loadIfPresent(final Properties props, final Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load WebAuthn LDAP properties: " + path, ex);
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

    static Path webauthnLdapPropertiesPath(final String idpHome) {
        final String override = System.getenv("GRAPHICAL_WEBAUTHN_LDAP_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_WEBAUTHN_LDAP_PROPERTIES").trim()
            : "";
        return override.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "webauthn-ldap.properties")
            : Path.of(override);
    }
}
