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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class GraphicalMatrixConfigCheckTool {
    private static final List<String> GRAPHICAL_EXTENSIONS =
        List.of(".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp");
    private static final List<String> BOOLEAN_PROPERTIES = List.of(
        "graphicalmatrix.allow_duplicates",
        "graphicalmatrix.force_sequence_change",
        "graphicalmatrix.change.ldapRateLimit.enabled",
        "graphicalmatrix.selfservice.enabled",
        "graphicalmatrix.change.legacyLdapLoginEnabled",
        "graphicalmatrix.productionMode",
        "graphicalmatrix.view.template.enabled",
        "graphicalmatrix.view.css.enabled"
    );

    private int failures;
    private int warnings;

    private GraphicalMatrixConfigCheckTool() {
    }

    public static void main(final String[] args) {
        String idpHome = System.getenv().getOrDefault("IDP_HOME", "/opt/shibboleth-idp");
        for (int i = 0; i < args.length; i++) {
            if ("--idp-home".equals(args[i]) && i + 1 < args.length) {
                idpHome = args[++i];
            } else {
                System.err.println("FAIL: unknown argument: " + args[i]);
                System.exit(2);
            }
        }

        final GraphicalMatrixConfigCheckTool tool = new GraphicalMatrixConfigCheckTool();
        System.exit(tool.run(idpHome));
    }

    private int run(final String idpHome) {
        final Path propertiesPath = propertiesPath(idpHome);
        if (!Files.isRegularFile(propertiesPath)) {
            fail("config file missing: " + propertiesPath);
            summary();
            return 1;
        }
        if (!Files.isReadable(propertiesPath)) {
            fail("config file is not readable: " + propertiesPath);
            summary();
            return 1;
        }
        ok("config file readable: " + propertiesPath);

        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesPath)) {
            properties.load(in);
        } catch (Exception ex) {
            fail("config file load failed: " + rootMessage(ex));
            summary();
            return 1;
        }

        checkBooleanProperties(properties);

        final GraphicalMatrixConfig config;
        try {
            config = GraphicalMatrixConfig.load(idpHome);
        } catch (Exception ex) {
            fail("runtime configuration invalid: " + rootMessage(ex));
            summary();
            return 1;
        }

        ok("grid valid: columns=" + config.getColumns()
            + " rows=" + config.getRows()
            + " cells=" + (config.getColumns() * config.getRows()));
        ok("graphicals valid: enabled=" + config.getGraphicalIds().size());
        ok("choice valid: choice=" + config.getChoiceCount()
            + " allow_duplicates=" + config.isDuplicateSelectionsAllowed());
        ok("aliases valid: count=" + config.getAliases().size());
        ok("challenge valid: seconds=" + config.getChallengeSeconds());
        checkLockout(config);
        checkLdapRateLimit(config);
        ok("self-service valid: enabled=" + config.isSelfServiceEnabled()
            + " transaction_seconds=" + config.getSelfServiceTransactionSeconds()
            + " legacy_ldap_login=" + config.isLegacyLdapLoginEnabled());

        checkGraphicalFiles(config);
        checkViewFiles(config);
        checkSaveData(idpHome, properties);
        checkStorage(idpHome, config, properties);
        checkMfaPolicy(idpHome);
        checkSpManagement(idpHome);

        summary();
        return failures == 0 ? 0 : 1;
    }

    private void checkBooleanProperties(final Properties properties) {
        for (final String key : BOOLEAN_PROPERTIES) {
            final String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            final String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!List.of("true", "false", "1", "0", "yes", "no", "on", "off").contains(normalized)) {
                warn("unrecognized boolean value is treated as false: " + key + "=" + value.trim());
            }
        }
    }

    private void checkLockout(final GraphicalMatrixConfig config) {
        ok("GraphicalMatrix lockout valid: failure_limit=" + config.getLockoutFailureLimit()
            + " lock_seconds=" + config.getLockoutLockSeconds()
            + " max_lock_failure_count=" + config.getLockoutMaxLockFailureCount()
            + " max_lock_seconds=" + config.getLockoutMaxLockSeconds());

        if (config.getLockoutFailureLimit() < 3) {
            warn("GraphicalMatrix lockout failure limit is lower than recommended: failure_limit="
                + config.getLockoutFailureLimit());
        } else if (config.getLockoutFailureLimit() > 10) {
            warn("GraphicalMatrix lockout failure limit is higher than recommended: failure_limit="
                + config.getLockoutFailureLimit());
        }
        if (config.getLockoutLockSeconds() < 300) {
            warn("GraphicalMatrix lockout duration is shorter than recommended: lock_seconds="
                + config.getLockoutLockSeconds());
        } else if (config.getLockoutLockSeconds() > 3600) {
            warn("GraphicalMatrix lockout duration is longer than recommended: lock_seconds="
                + config.getLockoutLockSeconds());
        }
        if (config.getLockoutMaxLockFailureCount() > 100) {
            warn("GraphicalMatrix maximum lock threshold is higher than recommended: "
                + "max_lock_failure_count=" + config.getLockoutMaxLockFailureCount());
        }
    }

    private void checkLdapRateLimit(final GraphicalMatrixConfig config) {
        if (!config.isChangeLdapRateLimitEnabled()) {
            ok("LDAP change rate limit disabled");
            return;
        }
        final List<String> bypassCidrs = config.getChangeLdapRateLimitIpLimitBypassCidrs();
        ok("LDAP change rate limit valid: key=" + config.getChangeLdapRateLimitKey()
            + " failure_limit=" + config.getChangeLdapRateLimitFailureLimit()
            + " ip_failure_limit=" + config.getChangeLdapRateLimitIpFailureLimit()
            + " ip_limit_bypass_cidrs=" + bypassCidrs.size());
        if (!bypassCidrs.isEmpty() && "ip".equals(config.getChangeLdapRateLimitKey())) {
            warn("LDAP IP-limit bypass does not bypass the keyed limiter when key=ip; "
                + "use key=user or key=ip-user for shared NAT networks");
        }
        if (bypassCidrs.stream().anyMatch(value -> value.endsWith("/0"))) {
            warn("LDAP IP-limit bypass covers every address; use only the required trusted CIDRs");
        }
    }

    private void checkGraphicalFiles(final GraphicalMatrixConfig config) {
        final Path directory = config.getGraphicalDirectory().toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            fail("graphical directory missing: " + directory);
            return;
        }
        if (!Files.isReadable(directory)) {
            fail("graphical directory is not readable: " + directory);
            return;
        }

        int found = 0;
        for (final String id : config.getGraphicalIds()) {
            if (findGraphical(directory, id) == null) {
                fail("graphical file missing or unreadable: id=" + id + " directory=" + directory);
            } else {
                found++;
            }
        }
        if (found == config.getGraphicalIds().size()) {
            ok("graphical files readable: directory=" + directory + " count=" + found);
        }
    }

    private void checkViewFiles(final GraphicalMatrixConfig config) {
        if (config.isCssEnabled()) {
            checkReadableFile("CSS", config.getCssPath());
        } else {
            ok("external CSS disabled");
        }

        if (!config.isTemplateEnabled()) {
            ok("external templates disabled");
            return;
        }

        checkReadableFile("template", config.getTemplatePath());
        checkReadableFile("locked template", config.getLockedTemplatePath());
        checkReadableFile("unavailable template", config.getUnavailableTemplatePath());
        checkReadableFile("TOTP register template", config.getTotpRegisterTemplatePath());
        checkReadableFile("change start template", config.getChangeStartTemplatePath());
        checkReadableFile("change current template", config.getChangeCurrentTemplatePath());
        checkReadableFile("change menu template", config.getChangeMenuTemplatePath());
        checkReadableFile("change new template", config.getChangeNewTemplatePath());
        checkReadableFile("change method template", config.getChangeMethodTemplatePath());
        checkReadableFile("change complete template", config.getChangeCompleteTemplatePath());
    }

    private void checkStorage(final String idpHome, final GraphicalMatrixConfig config,
            final Properties properties) {
        final boolean ldapSaveData = ldapSaveData(properties);
        try {
            final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(idpHome);
            storage.encode(sampleSequence(config),
                config.isOrderedSelectionRequired(), config.isDuplicateSelectionsAllowed());
            if (productionMode(properties) && "plaintext".equals(storage.mode())) {
                fail("plaintext sequence storage is not allowed when graphicalmatrix.productionMode=true");
            } else if ("plaintext".equals(storage.mode())) {
                warn("sequence storage is plaintext; use auto/hash, keyword, or aes-gcm outside PoC");
            } else {
                ok("sequence storage usable: mode=" + storage.mode());
            }
        } catch (Exception ex) {
            fail("sequence storage invalid: " + rootMessage(ex));
        }

        try {
            final GraphicalMatrixTotpSeedStorage storage = GraphicalMatrixTotpSeedStorage.load(idpHome);
            if ("unconfigured-hash".equals(storage.mode())) {
                final String message = "TOTP seed storage is not configured for hash sequence storage; "
                    + "set graphicalmatrix.totp.seed.storage to aes-gcm or keyword before using TOTP";
                if (ldapSaveData) {
                    fail(message);
                } else {
                    warn(message);
                }
                return;
            }
            storage.encode("JBSWY3DPEHPK3PXP");
            if ("plaintext".equals(storage.mode())) {
                warn("TOTP seed storage is plaintext; use keyword or aes-gcm outside PoC");
            } else {
                ok("TOTP seed storage usable: mode=" + storage.mode());
            }
        } catch (Exception ex) {
            fail("TOTP seed storage invalid: " + rootMessage(ex));
        }
    }

    private void checkSaveData(final String idpHome, final Properties properties) {
        final String mode;
        try {
            mode = GraphicalMatrixSaveDataConfig.normalize(
                properties.getProperty("graphicalmatrix.savedata", "db"));
            ok("save data backend valid: mode=" + mode);
        } catch (Exception ex) {
            fail("save data backend invalid: " + rootMessage(ex));
            return;
        }

        if (!"ldap".equals(mode)) {
            return;
        }

        final Path ldapProperties = GraphicalMatrixLdapConfig.ldapPropertiesPath(idpHome);
        if (Files.isRegularFile(ldapProperties) && Files.isReadable(ldapProperties)) {
            ok("LDAP storage config readable: " + ldapProperties);
        } else {
            fail("LDAP storage config missing or unreadable: " + ldapProperties);
            return;
        }

        try {
            GraphicalMatrixLdapConfig.load(idpHome);
            ok("LDAP storage config valid");
        } catch (Exception ex) {
            fail("LDAP storage config invalid: " + rootMessage(ex));
        }
    }

    private void checkMfaPolicy(final String idpHome) {
        final Path policyPath = Path.of(idpHome, "conf", "graphicalmatrix", "mfa-policy.properties");
        if (!Files.isRegularFile(policyPath) || !Files.isReadable(policyPath)) {
            fail("MFA policy file missing or unreadable: " + policyPath);
            return;
        }

        final Properties policyProperties = new Properties();
        try (InputStream in = Files.newInputStream(policyPath)) {
            policyProperties.load(in);
        } catch (Exception ex) {
            fail("MFA policy file load failed: " + rootMessage(ex));
            return;
        }

        final GraphicalMatrixMfaPolicy policy;
        try {
            policy = GraphicalMatrixMfaPolicy.parse(policyProperties);
        } catch (Exception ex) {
            fail("MFA policy invalid: " + rootMessage(ex));
            return;
        }

        ok("MFA policy valid: default=" + policy.defaultPolicy() + " order=" + policy.orderText());
        warnMfaPolicyOverlap("forceSPs and bypassSPs",
            intersection(policy.forceSPs(), policy.bypassSPs()));
        warnMfaPolicyOverlap("forceSPs and bypassSpCidrs",
            intersection(policy.forceSPs(), policy.bypassSpEntityIds()));
        warnMfaPolicyOverlap("bypassSPs and requiredSPs",
            intersection(policy.bypassSPs(), policy.requiredSPs()));
    }

    private void checkSpManagement(final String idpHome) {
        final Path configPath = Path.of(idpHome, "conf", "graphicalmatrix",
            "sp-management.properties");
        if (!Files.isRegularFile(configPath)) {
            ok("SP management CLI is not configured");
            return;
        }

        final GraphicalMatrixSpManagementConfig spConfig;
        try {
            spConfig = GraphicalMatrixSpManagementConfig.load(idpHome);
        } catch (Exception ex) {
            fail("SP management configuration invalid: " + rootMessage(ex));
            return;
        }
        if (!spConfig.enabled()) {
            if (spConfig.accessEnabled()) {
                fail("SP attribute access control requires SP management to be enabled");
                return;
            }
            ok("SP management CLI is disabled");
            return;
        }

        if (spConfig.runtimeGroup().isBlank()) {
            fail("SP management requires graphicalmatrix.sp.runtimeGroup to identify the IdP "
                + "runtime group");
            return;
        }

        try {
            if (GraphicalMatrixSpXmlConfig.hasManagedProvider(spConfig.metadataProvidersPath())) {
                ok("SP managed metadata provider configured: "
                    + GraphicalMatrixSpXmlConfig.PROVIDER_ID);
            } else {
                fail("SP management is enabled but managed metadata provider is missing");
            }
            if (GraphicalMatrixSpXmlConfig.hasManagedAttributeBlock(spConfig.attributeFilterPath())) {
                ok("SP managed attribute filter block configured");
            } else {
                fail("SP management is enabled but managed attribute filter block is missing");
            }
        } catch (Exception ex) {
            fail("SP management XML configuration invalid: " + rootMessage(ex));
        }

        try {
            final GraphicalMatrixSpRegistry registry =
                GraphicalMatrixSpRegistry.load(spConfig.registryPath());
            ok("SP management registry valid: entries=" + registry.entries().size());
            checkSpAccess(spConfig, registry);
        } catch (Exception ex) {
            fail("SP management registry invalid: " + rootMessage(ex));
        }

        if (!Files.isDirectory(spConfig.managedMetadataDirectory())) {
            fail("SP managed metadata directory is missing: " + spConfig.managedMetadataDirectory());
        } else if (!Files.isReadable(spConfig.managedMetadataDirectory())) {
            fail("SP managed metadata directory is unreadable: " + spConfig.managedMetadataDirectory());
        } else {
            try {
                checkRuntimeReadable("SP managed metadata directory",
                    spConfig.managedMetadataDirectory(), spConfig.runtimeGroup(), true);
                try (var files = Files.list(spConfig.managedMetadataDirectory())) {
                    for (final Path metadata : files.filter(Files::isRegularFile).toList()) {
                        checkNonSymlinkReadable("SP managed metadata", metadata);
                        checkRuntimeReadable("SP managed metadata", metadata,
                            spConfig.runtimeGroup(), false);
                    }
                }
                ok("SP managed metadata directory readable: " + spConfig.managedMetadataDirectory());
            } catch (Exception ex) {
                fail("SP managed metadata directory runtime access invalid: " + rootMessage(ex));
            }
        }
    }

    private void checkSpAccess(final GraphicalMatrixSpManagementConfig config,
            final GraphicalMatrixSpRegistry registry) {
        if (!config.accessEnabled()) {
            ok("SP attribute access control is disabled");
            return;
        }
        try {
            if (config.runtimeGroup().isBlank()) {
                throw new IllegalStateException("graphicalmatrix.sp.runtimeGroup is required when "
                    + "SP attribute access control is enabled");
            }
            checkRuntimeReadable("SP management configuration directory",
                config.registryPath().getParent(), config.runtimeGroup(), true);
            checkNonSymlinkReadable("SP management registry", config.registryPath());
            checkNonSymlinkReadable("SP access policy", config.accessPolicyPath());
            checkNonSymlinkReadable("SP attribute catalog", config.attributeCatalogPath());
            checkRuntimeReadable("SP management registry", config.registryPath(),
                config.runtimeGroup(), false);
            checkRuntimeReadable("SP access policy", config.accessPolicyPath(),
                config.runtimeGroup(), false);
            checkRuntimeReadable("SP attribute catalog", config.attributeCatalogPath(),
                config.runtimeGroup(), false);
            final GraphicalMatrixSpAccessPolicy access =
                GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
            final GraphicalMatrixAttributeCatalog catalog =
                GraphicalMatrixAttributeCatalog.load(config.attributeCatalogPath());
            final GraphicalMatrixSpAccessXmlConfig.Inspection inspection =
                GraphicalMatrixSpAccessXmlConfig.inspect(config.contextCheckConfigPath(),
                    config.relyingPartyPath());
            if (inspection.conflict() || !inspection.managedFunction()) {
                fail("SP access ContextCheck function is missing or conflicts with existing config");
            } else if (inspection.saml2Profiles() == 0
                    || inspection.saml2Profiles() != inspection.contextCheckProfiles()) {
                fail("context-check is not configured exactly once in every SAML2.SSO profile");
            } else {
                ok("SP access ContextCheck integration valid: profiles="
                    + inspection.saml2Profiles());
            }
            for (final GraphicalMatrixSpAccessPolicy.Policy policy : access.policies()) {
                final GraphicalMatrixSpRegistry.Entry entry = registry.get(policy.name());
                if (!entry.entityId().equals(policy.entityId())) {
                    throw new IllegalStateException("policy entityID mismatch: " + policy.name());
                }
                if (entry.accessPolicyEnabled() != policy.enabled()
                        || entry.accessPolicyRevision() != policy.revision()) {
                    throw new IllegalStateException("policy revision mismatch: " + policy.name());
                }
                for (final GraphicalMatrixSpAccessPolicy.Rule rule :
                        java.util.stream.Stream.concat(policy.allow().stream(),
                            policy.deny().stream()).toList()) {
                    final GraphicalMatrixAttributeCatalog.Attribute attribute =
                        catalog.requireAttribute(rule.attributeId());
                    if (!attribute.accessApproved()
                            || catalog.isBlocked(attribute.id(), config.blockedAttributes())) {
                        throw new IllegalStateException(
                            "policy attribute is not approved: " + attribute.id());
                    }
                }
            }
            for (final GraphicalMatrixSpRegistry.Entry entry : registry.entries()) {
                final GraphicalMatrixAttributeCatalog.Profile profile =
                    catalog.findProfile(entry.attributeProfile());
                if (profile != null && entry.attributeProfileRevision() != profile.revision()) {
                    throw new IllegalStateException(
                        "attribute profile revision mismatch: " + entry.name());
                }
                if (entry.accessPolicyEnabled() && access.find(entry.name()) == null) {
                    throw new IllegalStateException(
                        "registry references a missing access policy: " + entry.name());
                }
            }
            ok("SP access policy and attribute catalog valid: policies="
                + access.policies().size() + " attributes=" + catalog.attributes().size()
                + " profiles=" + catalog.profiles().size());
        } catch (Exception ex) {
            fail("SP access control configuration invalid: " + rootMessage(ex));
        }
    }

    private void checkNonSymlinkReadable(final String label, final Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || !Files.isReadable(path)) {
            throw new IllegalStateException(label + " is missing, unreadable, or a symlink: " + path);
        }
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IllegalStateException(label + " has unsafe permissions: " + path);
            }
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are checked on supported production filesystems.
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(label + " permissions cannot be read: " + path, ex);
        }
    }

    private void checkRuntimeReadable(final String label, final Path path,
            final String runtimeGroup, final boolean directory) throws IOException {
        final PosixFileAttributes attributes = Files.readAttributes(path,
            PosixFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        final Set<PosixFilePermission> permissions = attributes.permissions();
        final boolean groupReadable = directory
            ? permissions.contains(PosixFilePermission.GROUP_READ)
                && permissions.contains(PosixFilePermission.GROUP_EXECUTE)
            : permissions.contains(PosixFilePermission.GROUP_READ);
        final boolean othersAccessible = directory
            ? permissions.contains(PosixFilePermission.OTHERS_READ)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
            : false;
        if (!runtimeGroup.equals(attributes.group().getName()) || !groupReadable
                || othersAccessible) {
            throw new IllegalStateException(label + " is not readable by configured runtime group "
                + runtimeGroup + ": " + path);
        }
    }

    private void warnMfaPolicyOverlap(final String label, final Set<String> overlap) {
        if (!overlap.isEmpty()) {
            warn("MFA policy overlap in " + label + "; policyOrder decides: " + overlap);
        }
    }

    private static Set<String> intersection(final Set<String> left, final Set<String> right) {
        final Set<String> overlap = new LinkedHashSet<>(left);
        overlap.retainAll(right);
        return overlap;
    }

    private static List<String> sampleSequence(final GraphicalMatrixConfig config) {
        final List<String> available = config.getGraphicalIds();
        final List<String> sample = new ArrayList<>();
        for (int i = 0; i < config.getChoiceCount(); i++) {
            sample.add(available.get(i % available.size()));
        }
        return sample;
    }

    private void checkReadableFile(final String label, final Path path) {
        final Path absolute = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(absolute) && Files.isReadable(absolute)) {
            ok(label + " readable: " + absolute);
        } else {
            fail(label + " missing or unreadable: " + absolute);
        }
    }

    private static Path findGraphical(final Path directory, final String id) {
        for (final String extension : GRAPHICAL_EXTENSIONS) {
            final Path candidate = directory.resolve(id + extension).normalize();
            if (candidate.startsWith(directory)
                    && Files.isRegularFile(candidate)
                    && Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean productionMode(final Properties properties) {
        final String value = properties.getProperty("graphicalmatrix.productionMode", "false")
            .trim().toLowerCase(Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value) || "on".equals(value);
    }

    private static boolean ldapSaveData(final Properties properties) {
        try {
            return "ldap".equals(GraphicalMatrixSaveDataConfig.normalize(
                properties.getProperty("graphicalmatrix.savedata", "db")));
        } catch (Exception ex) {
            return false;
        }
    }

    private static Path propertiesPath(final String idpHome) {
        final String override = System.getenv().getOrDefault("GRAPHICAL_PROPERTIES", "").trim();
        return override.isEmpty()
            ? Path.of(idpHome, "conf", "graphicalmatrix", "graphicalmatrix.properties")
            : Path.of(override);
    }

    private static String rootMessage(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return current.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void ok(final String message) {
        System.out.println("OK: " + message);
    }

    private void warn(final String message) {
        warnings++;
        System.out.println("WARN: " + message);
    }

    private void fail(final String message) {
        failures++;
        System.out.println("FAIL: " + message);
    }

    private void summary() {
        System.out.println("CONFIG_SUMMARY failures=" + failures + " warnings=" + warnings);
    }
}
