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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraphicalMatrixConfig implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_COLUMNS = 5;
    private static final int DEFAULT_ROWS = 5;
    private static final int DEFAULT_CHOICE_COUNT = 4;
    private static final int DEFAULT_ORDER_MODE = 1;
    private static final int DEFAULT_CHALLENGE_SECONDS = 180;
    private static final int DEFAULT_LOCKOUT_FAILURE_LIMIT = 5;
    private static final int DEFAULT_LOCKOUT_LOCK_SECONDS = 900;
    private static final int DEFAULT_LOCKOUT_MAX_LOCK_FAILURE_COUNT = 10;
    private static final int DEFAULT_LOCKOUT_MAX_LOCK_SECONDS = 2592000;
    private static final int DEFAULT_CHANGE_LDAP_RATE_LIMIT_FAILURE_LIMIT = 5;
    private static final int DEFAULT_CHANGE_LDAP_RATE_LIMIT_WINDOW_SECONDS = 300;
    private static final int DEFAULT_CHANGE_LDAP_RATE_LIMIT_LOCK_SECONDS = 900;
    private static final int DEFAULT_SELF_SERVICE_TRANSACTION_SECONDS = 600;
    private static final boolean DEFAULT_ALLOW_DUPLICATE_SELECTIONS = false;
    private static final boolean DEFAULT_FORCE_SEQUENCE_CHANGE_ENABLED = false;
    private static final boolean DEFAULT_CHANGE_LDAP_RATE_LIMIT_ENABLED = true;
    private static final boolean DEFAULT_SELF_SERVICE_ENABLED = false;
    private static final boolean DEFAULT_LEGACY_LDAP_LOGIN_ENABLED = true;
    private static final String DEFAULT_CHANGE_LDAP_RATE_LIMIT_KEY = "ip-user";
    private static final String DEFAULT_GRAPHICALS = "img01-25";
    private static final String DEFAULT_GRAPHICAL_DIRECTORY =
        "/opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals";
    private static final String DEFAULT_CSS_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css";
    private static final String DEFAULT_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/graphicalmatrix.html";
    private static final String DEFAULT_LOCKED_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/locked.html";
    private static final String DEFAULT_UNAVAILABLE_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/unavailable.html";
    private static final String DEFAULT_TOTP_REGISTER_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html";
    private static final String DEFAULT_CHANGE_START_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-start.html";
    private static final String DEFAULT_CHANGE_CURRENT_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-current.html";
    private static final String DEFAULT_CHANGE_MENU_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-menu.html";
    private static final String DEFAULT_CHANGE_NEW_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-new.html";
    private static final String DEFAULT_CHANGE_METHOD_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-method.html";
    private static final String DEFAULT_CHANGE_COMPLETE_TEMPLATE_PATH =
        "/opt/shibboleth-idp/conf/graphicalmatrix/views/change-complete.html";
    private static final Pattern GRAPHICAL_ID = Pattern.compile("([A-Za-z_-]*)(\\d+)");

    private final int columns;
    private final int rows;
    private final int choiceCount;
    private final int orderMode;
    private final int challengeSeconds;
    private final int lockoutFailureLimit;
    private final int lockoutLockSeconds;
    private final int lockoutMaxLockFailureCount;
    private final int lockoutMaxLockSeconds;
    private final boolean changeLdapRateLimitEnabled;
    private final int changeLdapRateLimitFailureLimit;
    private final int changeLdapRateLimitWindowSeconds;
    private final int changeLdapRateLimitLockSeconds;
    private final String changeLdapRateLimitKey;
    private final boolean selfServiceEnabled;
    private final int selfServiceTransactionSeconds;
    private final boolean legacyLdapLoginEnabled;
    private final boolean duplicateSelectionsAllowed;
    private final boolean forceSequenceChangeEnabled;
    private final List<String> graphicalIds;
    private final Map<String, String> aliases;
    private final Map<String, String> reverseAliases;
    private final Path graphicalDirectory;
    private final boolean cssEnabled;
    private final Path cssPath;
    private final int cssCacheSeconds;
    private final boolean templateEnabled;
    private final Path templatePath;
    private final Path lockedTemplatePath;
    private final Path unavailableTemplatePath;
    private final Path totpRegisterTemplatePath;
    private final Path changeStartTemplatePath;
    private final Path changeCurrentTemplatePath;
    private final Path changeMenuTemplatePath;
    private final Path changeNewTemplatePath;
    private final Path changeMethodTemplatePath;
    private final Path changeCompleteTemplatePath;

    private GraphicalMatrixConfig(final int columns, final int rows, final int choiceCount,
            final int orderMode, final int challengeSeconds,
            final int lockoutFailureLimit, final int lockoutLockSeconds,
            final int lockoutMaxLockFailureCount, final int lockoutMaxLockSeconds,
            final boolean changeLdapRateLimitEnabled,
            final int changeLdapRateLimitFailureLimit, final int changeLdapRateLimitWindowSeconds,
            final int changeLdapRateLimitLockSeconds, final String changeLdapRateLimitKey,
            final boolean selfServiceEnabled, final int selfServiceTransactionSeconds,
            final boolean legacyLdapLoginEnabled,
            final boolean duplicateSelectionsAllowed, final boolean forceSequenceChangeEnabled,
            final List<String> graphicalIds, final Map<String, String> aliases,
            final Path graphicalDirectory,
            final boolean cssEnabled, final Path cssPath, final int cssCacheSeconds,
            final boolean templateEnabled, final Path templatePath,
            final Path lockedTemplatePath, final Path unavailableTemplatePath,
            final Path totpRegisterTemplatePath, final Path changeStartTemplatePath,
            final Path changeCurrentTemplatePath, final Path changeMenuTemplatePath,
            final Path changeNewTemplatePath, final Path changeMethodTemplatePath,
            final Path changeCompleteTemplatePath) {
        this.columns = columns;
        this.rows = rows;
        this.choiceCount = choiceCount;
        this.orderMode = orderMode;
        this.challengeSeconds = challengeSeconds;
        this.lockoutFailureLimit = lockoutFailureLimit;
        this.lockoutLockSeconds = lockoutLockSeconds;
        this.lockoutMaxLockFailureCount = lockoutMaxLockFailureCount;
        this.lockoutMaxLockSeconds = lockoutMaxLockSeconds;
        this.changeLdapRateLimitEnabled = changeLdapRateLimitEnabled;
        this.changeLdapRateLimitFailureLimit = changeLdapRateLimitFailureLimit;
        this.changeLdapRateLimitWindowSeconds = changeLdapRateLimitWindowSeconds;
        this.changeLdapRateLimitLockSeconds = changeLdapRateLimitLockSeconds;
        this.changeLdapRateLimitKey = changeLdapRateLimitKey;
        this.selfServiceEnabled = selfServiceEnabled;
        this.selfServiceTransactionSeconds = selfServiceTransactionSeconds;
        this.legacyLdapLoginEnabled = legacyLdapLoginEnabled;
        this.duplicateSelectionsAllowed = duplicateSelectionsAllowed;
        this.forceSequenceChangeEnabled = forceSequenceChangeEnabled;
        this.graphicalIds = Collections.unmodifiableList(new ArrayList<>(graphicalIds));
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        this.reverseAliases = Collections.unmodifiableMap(reverseAliases(aliases));
        this.graphicalDirectory = graphicalDirectory;
        this.cssEnabled = cssEnabled;
        this.cssPath = cssPath;
        this.cssCacheSeconds = cssCacheSeconds;
        this.templateEnabled = templateEnabled;
        this.templatePath = templatePath;
        this.lockedTemplatePath = lockedTemplatePath;
        this.unavailableTemplatePath = unavailableTemplatePath;
        this.totpRegisterTemplatePath = totpRegisterTemplatePath;
        this.changeStartTemplatePath = changeStartTemplatePath;
        this.changeCurrentTemplatePath = changeCurrentTemplatePath;
        this.changeMenuTemplatePath = changeMenuTemplatePath;
        this.changeNewTemplatePath = changeNewTemplatePath;
        this.changeMethodTemplatePath = changeMethodTemplatePath;
        this.changeCompleteTemplatePath = changeCompleteTemplatePath;
    }

    public static GraphicalMatrixConfig load(final String idpHome) throws IOException {
        final Properties properties = new Properties();
        final String overridePath = System.getenv("GRAPHICAL_PROPERTIES") != null
            ? System.getenv("GRAPHICAL_PROPERTIES").trim()
            : "";
        final Path path = overridePath.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "graphicalmatrix.properties")
            : Path.of(overridePath);
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            }
        }

        final int columns = intProperty(properties, "graphicalmatrix.columns", DEFAULT_COLUMNS);
        final int rows = intProperty(properties, "graphicalmatrix.rows", DEFAULT_ROWS);
        final int choiceCount = intProperty(properties, "graphicalmatrix.choice", DEFAULT_CHOICE_COUNT);
        final int orderMode = intProperty(properties, "graphicalmatrix.order", DEFAULT_ORDER_MODE);
        final int challengeSeconds =
            intProperty(properties, "graphicalmatrix.challenge.seconds", DEFAULT_CHALLENGE_SECONDS);
        final int lockoutFailureLimit = intProperty(properties,
            "graphicalmatrix.lockout.failureLimit", DEFAULT_LOCKOUT_FAILURE_LIMIT);
        final int lockoutLockSeconds = intProperty(properties,
            "graphicalmatrix.lockout.lockSeconds", DEFAULT_LOCKOUT_LOCK_SECONDS);
        final int lockoutMaxLockFailureCount = intProperty(properties,
            "graphicalmatrix.lockout.maxLockFailureCount", DEFAULT_LOCKOUT_MAX_LOCK_FAILURE_COUNT);
        final int lockoutMaxLockSeconds = intProperty(properties,
            "graphicalmatrix.lockout.maxLockSeconds", DEFAULT_LOCKOUT_MAX_LOCK_SECONDS);
        final boolean changeLdapRateLimitEnabled = booleanProperty(properties,
            "graphicalmatrix.change.ldapRateLimit.enabled", DEFAULT_CHANGE_LDAP_RATE_LIMIT_ENABLED);
        final int changeLdapRateLimitFailureLimit = intProperty(properties,
            "graphicalmatrix.change.ldapRateLimit.failureLimit",
            DEFAULT_CHANGE_LDAP_RATE_LIMIT_FAILURE_LIMIT);
        final int changeLdapRateLimitWindowSeconds = intProperty(properties,
            "graphicalmatrix.change.ldapRateLimit.windowSeconds",
            DEFAULT_CHANGE_LDAP_RATE_LIMIT_WINDOW_SECONDS);
        final int changeLdapRateLimitLockSeconds = intProperty(properties,
            "graphicalmatrix.change.ldapRateLimit.lockSeconds",
            DEFAULT_CHANGE_LDAP_RATE_LIMIT_LOCK_SECONDS);
        final String changeLdapRateLimitKey = properties.getProperty(
            "graphicalmatrix.change.ldapRateLimit.key", DEFAULT_CHANGE_LDAP_RATE_LIMIT_KEY)
            .trim().toLowerCase(Locale.ROOT);
        final boolean selfServiceEnabled = booleanProperty(properties,
            "graphicalmatrix.selfservice.enabled", DEFAULT_SELF_SERVICE_ENABLED);
        final int selfServiceTransactionSeconds = intProperty(properties,
            "graphicalmatrix.selfservice.transactionTtlSeconds",
            DEFAULT_SELF_SERVICE_TRANSACTION_SECONDS);
        final boolean legacyLdapLoginEnabled = booleanProperty(properties,
            "graphicalmatrix.change.legacyLdapLoginEnabled", DEFAULT_LEGACY_LDAP_LOGIN_ENABLED);
        final boolean duplicateSelectionsAllowed = booleanProperty(properties,
            "graphicalmatrix.allow_duplicates", DEFAULT_ALLOW_DUPLICATE_SELECTIONS);
        final boolean forceSequenceChangeEnabled = booleanProperty(properties,
            "graphicalmatrix.force_sequence_change", DEFAULT_FORCE_SEQUENCE_CHANGE_ENABLED);
        final List<String> graphicalIds = parseGraphicals(
            properties.getProperty("graphicalmatrix.graphicals", DEFAULT_GRAPHICALS));
        graphicalIds.removeAll(parseGraphicals(properties.getProperty("graphicalmatrix.not_graphicals", "")));
        final Map<String, String> aliases = parseAliases(
            properties.getProperty("graphicalmatrix.aliases", ""), graphicalIds);
        final Path graphicalDirectory = Path.of(properties.getProperty("graphicalmatrix.place",
            properties.getProperty("graphicalmtrix.place", DEFAULT_GRAPHICAL_DIRECTORY))).normalize();
        final boolean cssEnabled = booleanProperty(properties, "graphicalmatrix.view.css.enabled", true);
        final Path cssPath = Path.of(properties.getProperty("graphicalmatrix.view.css",
            DEFAULT_CSS_PATH)).normalize();
        final int cssCacheSeconds = intProperty(properties, "graphicalmatrix.view.css.cacheSeconds", 0);
        final boolean templateEnabled =
            booleanProperty(properties, "graphicalmatrix.view.template.enabled", true);
        final Path templatePath = Path.of(properties.getProperty("graphicalmatrix.view.template",
            DEFAULT_TEMPLATE_PATH)).normalize();
        final Path lockedTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.lockedTemplate", DEFAULT_LOCKED_TEMPLATE_PATH)).normalize();
        final Path unavailableTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.unavailableTemplate", DEFAULT_UNAVAILABLE_TEMPLATE_PATH)).normalize();
        final Path totpRegisterTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.totpRegisterTemplate", DEFAULT_TOTP_REGISTER_TEMPLATE_PATH)).normalize();
        final Path changeStartTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeStartTemplate", DEFAULT_CHANGE_START_TEMPLATE_PATH)).normalize();
        final Path changeCurrentTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeCurrentTemplate", DEFAULT_CHANGE_CURRENT_TEMPLATE_PATH)).normalize();
        final Path changeMenuTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeMenuTemplate", DEFAULT_CHANGE_MENU_TEMPLATE_PATH)).normalize();
        final Path changeNewTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeNewTemplate", DEFAULT_CHANGE_NEW_TEMPLATE_PATH)).normalize();
        final Path changeMethodTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeMethodTemplate", DEFAULT_CHANGE_METHOD_TEMPLATE_PATH)).normalize();
        final Path changeCompleteTemplatePath = Path.of(properties.getProperty(
            "graphicalmatrix.view.changeCompleteTemplate", DEFAULT_CHANGE_COMPLETE_TEMPLATE_PATH)).normalize();

        validate(columns, rows, choiceCount, orderMode, challengeSeconds,
            lockoutFailureLimit, lockoutLockSeconds,
            lockoutMaxLockFailureCount, lockoutMaxLockSeconds,
            changeLdapRateLimitEnabled, changeLdapRateLimitFailureLimit,
            changeLdapRateLimitWindowSeconds, changeLdapRateLimitLockSeconds,
            changeLdapRateLimitKey, selfServiceEnabled, selfServiceTransactionSeconds,
            legacyLdapLoginEnabled, duplicateSelectionsAllowed, graphicalIds);
        if (cssCacheSeconds < 0) {
            throw new IllegalArgumentException("GraphicalMatrix CSS cache seconds must be zero or positive.");
        }
        return new GraphicalMatrixConfig(columns, rows, choiceCount, orderMode, challengeSeconds,
            lockoutFailureLimit, lockoutLockSeconds,
            lockoutMaxLockFailureCount, lockoutMaxLockSeconds,
            changeLdapRateLimitEnabled, changeLdapRateLimitFailureLimit,
            changeLdapRateLimitWindowSeconds, changeLdapRateLimitLockSeconds,
            changeLdapRateLimitKey, selfServiceEnabled, selfServiceTransactionSeconds,
            legacyLdapLoginEnabled, duplicateSelectionsAllowed, forceSequenceChangeEnabled,
            graphicalIds, aliases, graphicalDirectory,
            cssEnabled, cssPath, cssCacheSeconds, templateEnabled, templatePath,
            lockedTemplatePath, unavailableTemplatePath, totpRegisterTemplatePath,
            changeStartTemplatePath, changeCurrentTemplatePath, changeMenuTemplatePath,
            changeNewTemplatePath, changeMethodTemplatePath, changeCompleteTemplatePath);
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getChoiceCount() {
        return choiceCount;
    }

    public int getOrderMode() {
        return orderMode;
    }

    public boolean isOrderedSelectionRequired() {
        return orderMode == 1;
    }

    public int getChallengeSeconds() {
        return challengeSeconds;
    }

    public long getChallengeMillis() {
        return challengeSeconds * 1000L;
    }

    public int getLockoutFailureLimit() {
        return lockoutFailureLimit;
    }

    public int getLockoutLockSeconds() {
        return lockoutLockSeconds;
    }

    public long getLockoutLockMillis() {
        return lockoutLockSeconds * 1000L;
    }

    public int getLockoutMaxLockFailureCount() {
        return lockoutMaxLockFailureCount;
    }

    public int getLockoutMaxLockSeconds() {
        return lockoutMaxLockSeconds;
    }

    public long getLockoutMaxLockMillis() {
        return lockoutMaxLockSeconds * 1000L;
    }

    public GraphicalMatrixLockoutPolicy getLockoutPolicy() {
        return new GraphicalMatrixLockoutPolicy(
            lockoutFailureLimit,
            getLockoutLockMillis(),
            lockoutMaxLockFailureCount,
            getLockoutMaxLockMillis()
        );
    }

    public boolean isChangeLdapRateLimitEnabled() {
        return changeLdapRateLimitEnabled;
    }

    public int getChangeLdapRateLimitFailureLimit() {
        return changeLdapRateLimitFailureLimit;
    }

    public long getChangeLdapRateLimitWindowMillis() {
        return changeLdapRateLimitWindowSeconds * 1000L;
    }

    public long getChangeLdapRateLimitLockMillis() {
        return changeLdapRateLimitLockSeconds * 1000L;
    }

    public String getChangeLdapRateLimitKey() {
        return changeLdapRateLimitKey;
    }

    public boolean isChangeLdapRateLimitEffective() {
        return changeLdapRateLimitEnabled
            && changeLdapRateLimitFailureLimit > 0
            && changeLdapRateLimitWindowSeconds > 0
            && changeLdapRateLimitLockSeconds > 0;
    }

    public boolean isSelfServiceEnabled() {
        return selfServiceEnabled;
    }

    public int getSelfServiceTransactionSeconds() {
        return selfServiceTransactionSeconds;
    }

    public long getSelfServiceTransactionMillis() {
        return selfServiceTransactionSeconds * 1000L;
    }

    public boolean isLegacyLdapLoginEnabled() {
        return legacyLdapLoginEnabled;
    }

    public boolean isDuplicateSelectionsAllowed() {
        return duplicateSelectionsAllowed;
    }

    public boolean isForceSequenceChangeEnabled() {
        return forceSequenceChangeEnabled;
    }

    public List<String> getGraphicalIds() {
        return graphicalIds;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    public String resolveGraphicalToken(final String token) {
        final String value = token != null ? token.trim() : "";
        if (value.isEmpty()) {
            return "";
        }
        final String byAlias = aliases.get(value.toUpperCase(Locale.ROOT));
        return byAlias != null ? byAlias : value;
    }

    public List<String> resolveSequenceToGraphicals(final List<String> tokens) {
        final List<String> out = new ArrayList<>();
        for (final String token : tokens) {
            out.add(resolveGraphicalToken(token));
        }
        validateSequence(out);
        return out;
    }

    public List<String> normalizeInitialSequence(final List<String> tokens) {
        final List<String> out = new ArrayList<>();
        for (final String token : tokens) {
            final String value = token != null ? token.trim() : "";
            final String upper = value.toUpperCase(Locale.ROOT);
            if (aliases.containsKey(upper)) {
                out.add(upper);
                continue;
            }
            final String reverse = reverseAliases.get(value);
            out.add(reverse != null ? reverse : value);
        }
        validateSequence(resolveSequenceToGraphicals(out));
        return out;
    }

    public void validateSequence(final List<String> graphicalSequence) {
        if (graphicalSequence.size() != choiceCount) {
            throw new IllegalArgumentException(
                "GraphicalMatrix sequence count must be " + choiceCount + ": " + graphicalSequence.size());
        }
        if (!containsAll(graphicalSequence)) {
            throw new IllegalArgumentException("GraphicalMatrix sequence contains unavailable graphical IDs.");
        }
        if (!duplicateSelectionsAllowed && new LinkedHashSet<>(graphicalSequence).size() != graphicalSequence.size()) {
            throw new IllegalArgumentException("GraphicalMatrix sequence contains duplicate graphical IDs.");
        }
    }

    public Path getGraphicalDirectory() {
        return graphicalDirectory;
    }

    public boolean isCssEnabled() {
        return cssEnabled;
    }

    public Path getCssPath() {
        return cssPath;
    }

    public int getCssCacheSeconds() {
        return cssCacheSeconds;
    }

    public boolean isTemplateEnabled() {
        return templateEnabled;
    }

    public Path getTemplatePath() {
        return templatePath;
    }

    public Path getLockedTemplatePath() {
        return lockedTemplatePath;
    }

    public Path getUnavailableTemplatePath() {
        return unavailableTemplatePath;
    }

    public Path getTotpRegisterTemplatePath() {
        return totpRegisterTemplatePath;
    }

    public Path getChangeStartTemplatePath() {
        return changeStartTemplatePath;
    }

    public Path getChangeCurrentTemplatePath() {
        return changeCurrentTemplatePath;
    }

    public Path getChangeMenuTemplatePath() {
        return changeMenuTemplatePath;
    }

    public Path getChangeNewTemplatePath() {
        return changeNewTemplatePath;
    }

    public Path getChangeMethodTemplatePath() {
        return changeMethodTemplatePath;
    }

    public Path getChangeCompleteTemplatePath() {
        return changeCompleteTemplatePath;
    }

    public boolean containsAll(final List<String> ids) {
        return graphicalIds.containsAll(ids);
    }

    private static int intProperty(final Properties properties, final String key, final int defaultValue) {
        final String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "GraphicalMatrix property must be an integer: " + key, ex);
        }
    }

    private static boolean booleanProperty(final Properties properties, final String key,
            final boolean defaultValue) {
        final String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        final String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
            || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static List<String> parseGraphicals(final String value) {
        final Set<String> out = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }

        for (final String raw : value.split(",")) {
            final String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            final int dash = item.indexOf('-', 1);
            if (dash > 0) {
                addRange(out, item.substring(0, dash).trim(), item.substring(dash + 1).trim());
            } else {
                out.add(item);
            }
        }
        return new ArrayList<>(out);
    }

    private static Map<String, String> parseAliases(final String value, final List<String> graphicalIds) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        for (final String raw : value.split(",")) {
            final String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            final int colon = item.indexOf(':');
            if (colon <= 0 || colon == item.length() - 1) {
                throw new IllegalArgumentException("Invalid GraphicalMatrix alias: " + item);
            }
            final String alias = item.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            final String graphicalId = item.substring(colon + 1).trim();
            if (!graphicalIds.contains(graphicalId)) {
                throw new IllegalArgumentException("GraphicalMatrix alias target is not enabled: " + item);
            }
            out.put(alias, graphicalId);
        }
        return out;
    }

    private static Map<String, String> reverseAliases(final Map<String, String> aliases) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : aliases.entrySet()) {
            out.putIfAbsent(entry.getValue(), entry.getKey());
        }
        return out;
    }

    private static void addRange(final Set<String> out, final String start, final String end) {
        final Matcher startMatcher = GRAPHICAL_ID.matcher(start);
        final Matcher endMatcher = GRAPHICAL_ID.matcher(end);
        if (!startMatcher.matches() || !endMatcher.matches()) {
            throw new IllegalArgumentException("Invalid graphical range: " + start + "-" + end);
        }
        if (!endMatcher.group(1).isEmpty() && !startMatcher.group(1).equals(endMatcher.group(1))) {
            throw new IllegalArgumentException("Graphical range prefixes differ: " + start + "-" + end);
        }

        final int first = Integer.parseInt(startMatcher.group(2));
        final int last = Integer.parseInt(endMatcher.group(2));
        if (first > last) {
            throw new IllegalArgumentException("Graphical range start is greater than end: " + start + "-" + end);
        }

        final String prefix = startMatcher.group(1);
        final int width = Math.max(startMatcher.group(2).length(), endMatcher.group(2).length());
        for (int i = first; i <= last; i++) {
            out.add(prefix + String.format("%0" + width + "d", Integer.valueOf(i)));
        }
    }

    private static void validate(final int columns, final int rows, final int choiceCount,
            final int orderMode, final int challengeSeconds,
            final int lockoutFailureLimit, final int lockoutLockSeconds,
            final int lockoutMaxLockFailureCount, final int lockoutMaxLockSeconds,
            final boolean changeLdapRateLimitEnabled, final int changeLdapRateLimitFailureLimit,
            final int changeLdapRateLimitWindowSeconds, final int changeLdapRateLimitLockSeconds,
            final String changeLdapRateLimitKey,
            final boolean selfServiceEnabled, final int selfServiceTransactionSeconds,
            final boolean legacyLdapLoginEnabled,
            final boolean duplicateSelectionsAllowed, final List<String> graphicalIds) {
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException("GraphicalMatrix columns and rows must be positive.");
        }
        if (choiceCount < 1) {
            throw new IllegalArgumentException("GraphicalMatrix choice must be positive.");
        }
        if (orderMode != 1 && orderMode != 2) {
            throw new IllegalArgumentException("GraphicalMatrix order must be 1 or 2.");
        }
        if (challengeSeconds < 30 || challengeSeconds > 900) {
            throw new IllegalArgumentException("GraphicalMatrix challenge seconds must be between 30 and 900.");
        }
        if (lockoutFailureLimit < 1 || lockoutFailureLimit > 100) {
            throw new IllegalArgumentException(
                "GraphicalMatrix lockout failure limit must be between 1 and 100.");
        }
        if (lockoutLockSeconds < 1 || lockoutLockSeconds > 2592000) {
            throw new IllegalArgumentException(
                "GraphicalMatrix lockout seconds must be between 1 and 2592000.");
        }
        if (lockoutMaxLockFailureCount <= lockoutFailureLimit
                || lockoutMaxLockFailureCount > 1000) {
            throw new IllegalArgumentException(
                "GraphicalMatrix maximum lock failure count must be greater than "
                + "the normal failure limit and at most 1000.");
        }
        if (lockoutMaxLockSeconds < lockoutLockSeconds
                || lockoutMaxLockSeconds > 2592000) {
            throw new IllegalArgumentException(
                "GraphicalMatrix maximum lock seconds must be between "
                + "the normal lock seconds and 2592000.");
        }
        if (changeLdapRateLimitEnabled) {
            if (changeLdapRateLimitFailureLimit < 1) {
                throw new IllegalArgumentException("GraphicalMatrix change LDAP rate limit failure limit must be positive.");
            }
            if (changeLdapRateLimitWindowSeconds < 1 || changeLdapRateLimitLockSeconds < 1) {
                throw new IllegalArgumentException("GraphicalMatrix change LDAP rate limit seconds must be positive.");
            }
            if (!"ip".equals(changeLdapRateLimitKey)
                    && !"user".equals(changeLdapRateLimitKey)
                    && !"ip-user".equals(changeLdapRateLimitKey)) {
                throw new IllegalArgumentException(
                    "GraphicalMatrix change LDAP rate limit key must be ip, user, or ip-user.");
            }
        }
        if (selfServiceTransactionSeconds < 60 || selfServiceTransactionSeconds > 900) {
            throw new IllegalArgumentException(
                "GraphicalMatrix self-service transaction seconds must be between 60 and 900.");
        }
        if (!selfServiceEnabled && !legacyLdapLoginEnabled) {
            throw new IllegalArgumentException(
                "GraphicalMatrix self-service or legacy LDAP login must be enabled.");
        }
        final int cells = columns * rows;
        if (graphicalIds.size() != cells) {
            throw new IllegalArgumentException(
                "GraphicalMatrix graphical count must match columns * rows. graphicals="
                + graphicalIds.size() + ", cells=" + cells);
        }
        if (!duplicateSelectionsAllowed && choiceCount > graphicalIds.size()) {
            throw new IllegalArgumentException("GraphicalMatrix choice exceeds graphical count.");
        }
    }
}
