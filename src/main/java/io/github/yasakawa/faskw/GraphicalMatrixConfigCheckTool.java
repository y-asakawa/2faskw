package io.github.yasakawa.faskw;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        ok("self-service valid: enabled=" + config.isSelfServiceEnabled()
            + " transaction_seconds=" + config.getSelfServiceTransactionSeconds()
            + " legacy_ldap_login=" + config.isLegacyLdapLoginEnabled());

        checkGraphicalFiles(config);
        checkViewFiles(config);
        checkSaveData(idpHome, properties);
        checkStorage(idpHome, config, properties);
        checkMfaPolicy(idpHome);

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
