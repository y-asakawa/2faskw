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
import java.nio.file.FileSystems;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixSpGovernanceTool {
    private final GraphicalMatrixSpManagementConfig config;
    private final String executable;

    GraphicalMatrixSpGovernanceTool(final GraphicalMatrixSpManagementConfig value) {
        config = value;
        executable = config.idpHome().resolve("bin/graphicalmatrix-sp.sh").toString();
    }

    void execute(final String group, final String[] arguments) throws Exception {
        if (arguments.length == 0) {
            usage(group);
            throw new IllegalArgumentException("missing " + group + " subcommand");
        }
        final String command = arguments[0];
        final Arguments options = Arguments.parse(Arrays.copyOfRange(arguments, 1, arguments.length));
        if (options.apply() && !config.enabled()) {
            throw new IllegalStateException(GraphicalMatrixSpManagementConfig.ENABLED
                + " must be true before applying SP governance changes");
        }
        if ("access".equals(group)) {
            access(command, options);
        } else {
            attributes(command, options);
        }
    }

    private void access(final String command, final Arguments options) throws Exception {
        switch (command) {
            case "init" -> accessInit(options);
            case "list" -> accessList(options);
            case "show" -> accessShow(options);
            case "set" -> accessSet(options);
            case "disable" -> accessToggle(options, false);
            case "enable" -> accessToggle(options, true);
            case "clear" -> accessClear(options);
            case "test" -> accessTest(options);
            default -> throw new IllegalArgumentException(
                "unknown access subcommand: " + command);
        }
    }

    private void attributes(final String command, final Arguments options) throws Exception {
        switch (command) {
            case "discover" -> attributeDiscover(options);
            case "list" -> attributeList(options);
            case "show" -> attributeShow(options);
            case "approve" -> attributeApprove(options);
            case "block" -> attributeBlock(options);
            case "profile" -> attributeProfile(options);
            default -> throw new IllegalArgumentException(
                "unknown attributes subcommand: " + command);
        }
    }

    private void accessInit(final Arguments options) throws Exception {
        options.allow(Set.of(), Set.of("apply"));
        options.noPositionals("access init");
        System.out.println("mode=" + mode(options));
        System.out.println("context_config=" + config.contextCheckConfigPath());
        System.out.println("relying_party_config=" + config.relyingPartyPath());
        System.out.println("policy_file=" + config.accessPolicyPath());
        System.out.println("catalog_file=" + config.attributeCatalogPath());

        if (Files.isRegularFile(config.contextCheckConfigPath())) {
            final GraphicalMatrixSpAccessXmlConfig.Inspection inspection =
                GraphicalMatrixSpAccessXmlConfig.inspect(config.contextCheckConfigPath(),
                    config.relyingPartyPath());
            printInspection(inspection);
            if (inspection.conflict()) {
                System.out.println("state=CONTEXT_CHECK_CONFLICT");
                System.out.println("result=NO_CHANGE");
                throw new IllegalStateException(
                    "an existing non-2FAS-KW ContextCheck function or condition is configured");
            }
        } else {
            System.out.println("context_check_module=NOT_ENABLED_OR_CONFIG_MISSING");
        }
        if (!options.apply()) {
            System.out.println("next_command=sudo " + executable + " access init --apply");
            return;
        }

        requireRuntimeGroup();

        final boolean enableModule = !Files.isRegularFile(config.contextCheckConfigPath());
        final Snapshot snapshot = snapshot(config.contextCheckConfigPath(),
            config.relyingPartyPath(), config.configPath(), config.accessPolicyPath(),
            config.attributeCatalogPath());
        try {
            if (enableModule) {
                runRequired(List.of(config.idpHome().resolve("bin/module.sh").toString(),
                    "-e", "idp.intercept.ContextCheck"));
            }
            final GraphicalMatrixSpAccessXmlConfig.Inspection inspection =
                GraphicalMatrixSpAccessXmlConfig.inspect(config.contextCheckConfigPath(),
                    config.relyingPartyPath());
            if (inspection.conflict()) {
                throw new IllegalStateException(
                    "an existing non-2FAS-KW ContextCheck function or condition is configured");
            }
            GraphicalMatrixSpAccessXmlConfig.initializeContextCheck(
                config.contextCheckConfigPath());
            GraphicalMatrixSpAccessXmlConfig.initializeRelyingParty(
                config.relyingPartyPath());
            if (!Files.exists(config.accessPolicyPath())) {
                GraphicalMatrixSpAccessPolicy.empty().save(config.accessPolicyPath());
            }
            if (!Files.exists(config.attributeCatalogPath())) {
                GraphicalMatrixAttributeCatalog.defaults().save(config.attributeCatalogPath());
            }
            updateProperties(Map.of(
                "graphicalmatrix.sp.access.enabled", "true",
                "graphicalmatrix.sp.access.reloadIntervalSeconds",
                    Integer.toString(config.accessReloadIntervalSeconds()),
                "graphicalmatrix.sp.access.auditDecisions",
                    Boolean.toString(config.accessAuditDecisions())));
            secureGovernanceFiles();
            validateConsistency();
            audit("ACCESS_INIT", "-", "-", "OK", "PENDING_RESTART");
        } catch (Exception ex) {
            snapshot.restore();
            if (enableModule) {
                try {
                    runRequired(List.of(config.idpHome().resolve("bin/module.sh").toString(),
                        "-d", "idp.intercept.ContextCheck"));
                } catch (Exception moduleFailure) {
                    ex.addSuppressed(moduleFailure);
                }
            }
            audit("ACCESS_INIT", "-", "-", "FAILED", rootMessage(ex));
            throw ex;
        }
        System.out.println("state=ACCESS_PENDING_RESTART");
        System.out.println("result=APPLY_OK");
        System.out.println("next_build=sudo " + config.idpHome().resolve("bin/build.sh"));
        System.out.println("next_restart=sudo systemctl restart jetty-idp.service");
        System.out.println("next_status=curl --noproxy '*' -fsSI "
            + config.reloadBaseUrl() + "/status");
    }

    private void accessList(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        options.noPositionals("access list");
        final GraphicalMatrixSpAccessPolicy policy =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        if ("json".equals(options.single("format", "text"))) {
            System.out.print(policy.toJson());
            return;
        }
        System.out.printf("%-20s %-8s %-8s %-58s%n",
            "NAME", "ENABLED", "REVISION", "ENTITY ID");
        for (final GraphicalMatrixSpAccessPolicy.Policy item : policy.policies()) {
            System.out.printf("%-20s %-8s %-8d %-58s%n", item.name(),
                item.enabled(), item.revision(), truncate(item.entityId(), 58));
        }
    }

    private void accessShow(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        final String name = one(options, "access show NAME");
        final GraphicalMatrixSpAccessPolicy.Policy policy =
            requirePolicy(GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()), name);
        if ("json".equals(options.single("format", "text"))) {
            System.out.print(GraphicalMatrixSpAccessPolicy.singlePolicyJson(policy));
            return;
        }
        System.out.println("name=" + policy.name());
        System.out.println("entity_id=" + policy.entityId());
        System.out.println("enabled=" + policy.enabled());
        System.out.println("revision=" + policy.revision());
        printRules("allow", policy.allow());
        printRules("deny", policy.deny());
    }

    private void accessSet(final Arguments options) throws Exception {
        options.allow(Set.of("allow", "deny", "confirm"), Set.of("apply"));
        final String name = one(options, "access set NAME --allow ATTRIBUTE=VALUE");
        final GraphicalMatrixSpRegistry registry = registry();
        final GraphicalMatrixSpRegistry.Entry entry = registry.get(name);
        if (!"ACTIVE".equals(entry.status())) {
            throw new IllegalStateException("access policy requires an ACTIVE managed SP");
        }
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        final List<GraphicalMatrixSpAccessPolicy.Rule> allow =
            rules(options.values("allow"), "allow");
        final List<GraphicalMatrixSpAccessPolicy.Rule> deny =
            rules(options.values("deny"), "deny");
        if (allow.isEmpty()) {
            throw new IllegalArgumentException("at least one --allow rule is required");
        }
        validateAccessAttributes(catalog, allow, deny);
        final GraphicalMatrixSpAccessPolicy current =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        final GraphicalMatrixSpAccessPolicy.Policy existing = current.find(name);
        final int revision = existing == null ? 1 : existing.revision() + 1;
        final GraphicalMatrixSpAccessPolicy.Policy proposed =
            new GraphicalMatrixSpAccessPolicy.Policy(name, entry.entityId(), true, "restrict",
                revision, allow, deny, Instant.now().toString());
        System.out.println("mode=" + mode(options));
        System.out.println("action=ACCESS_POLICY_SET");
        System.out.println("name=" + name);
        System.out.println("entity_id=" + entry.entityId());
        System.out.println("revision_new=" + revision);
        printRules("allow", proposed.allow());
        printRules("deny", proposed.deny());
        if (!options.apply()) {
            System.out.println("next_command=sudo " + executable + " access set " + name
                + repeated("--allow", options.values("allow"))
                + repeated("--deny", options.values("deny"))
                + " --apply --confirm " + shellQuote(entry.entityId()));
            return;
        }
        confirmEntity(options, entry);
        applyAccessPolicy("ACCESS_POLICY_SET", registry, entry,
            current.with(proposed), proposed, true);
    }

    private void accessToggle(final Arguments options, final boolean enabled) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String name = one(options, "access " + (enabled ? "enable" : "disable") + " NAME");
        final GraphicalMatrixSpRegistry registry = registry();
        final GraphicalMatrixSpRegistry.Entry entry = registry.get(name);
        final GraphicalMatrixSpAccessPolicy current =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        final GraphicalMatrixSpAccessPolicy.Policy old = requirePolicy(current, name);
        final GraphicalMatrixSpAccessPolicy.Policy proposed =
            old.withEnabled(enabled, Instant.now());
        System.out.println("mode=" + mode(options));
        System.out.println("action=ACCESS_POLICY_" + (enabled ? "ENABLE" : "DISABLE"));
        System.out.println("name=" + name);
        System.out.println("enabled_current=" + old.enabled());
        System.out.println("enabled_new=" + enabled);
        System.out.println("revision_new=" + proposed.revision());
        if (!options.apply()) {
            return;
        }
        confirmEntity(options, entry);
        applyAccessPolicy("ACCESS_POLICY_" + (enabled ? "ENABLE" : "DISABLE"),
            registry, entry, current.with(proposed), proposed, enabled);
    }

    private void accessClear(final Arguments options) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String name = one(options, "access clear NAME");
        final GraphicalMatrixSpRegistry registry = registry();
        final GraphicalMatrixSpRegistry.Entry entry = registry.get(name);
        final GraphicalMatrixSpAccessPolicy current =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        final GraphicalMatrixSpAccessPolicy.Policy old = requirePolicy(current, name);
        System.out.println("mode=" + mode(options));
        System.out.println("action=ACCESS_POLICY_CLEAR");
        System.out.println("name=" + name);
        System.out.println("revision_current=" + old.revision());
        if (!options.apply()) {
            return;
        }
        confirmEntity(options, entry);
        applyAccessPolicy("ACCESS_POLICY_CLEAR", registry, entry,
            current.without(name), null, false);
    }

    private void accessTest(final Arguments options) throws Exception {
        options.allow(Set.of("user"), Set.of());
        final String name = one(options, "access test NAME --user USER");
        final GraphicalMatrixSpRegistry.Entry entry = registry().get(name);
        final String user = options.required("user");
        final GraphicalMatrixSpAccessPolicy.Policy policy =
            requirePolicy(GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()), name);
        final Map<String, List<String>> attributes =
            GraphicalMatrixAttributeResolverTest.resolve(config, user, entry.entityId());
        final GraphicalMatrixSpAccessEvaluator.Decision decision =
            new GraphicalMatrixSpAccessEvaluator().evaluate(policy, entry.entityId(), attributes);
        System.out.println("sp=" + name);
        System.out.println("entity_id=" + entry.entityId());
        System.out.println("user=" + user);
        System.out.println("policy_revision=" + policy.revision());
        System.out.println("decision=" + decision.result());
        System.out.println("reason=" + decision.reason());
        System.out.println("attributes_checked="
            + String.join(",", decision.attributesChecked().stream().sorted().toList()));
        System.out.println("values_displayed=false");
        audit("ACCESS_POLICY_TEST", name, entry.entityId(), "OK",
            "decision=" + decision.result() + ",reason=" + decision.reason());
    }

    private void applyAccessPolicy(final String event, final GraphicalMatrixSpRegistry registry,
            final GraphicalMatrixSpRegistry.Entry entry,
            final GraphicalMatrixSpAccessPolicy proposed,
            final GraphicalMatrixSpAccessPolicy.Policy changed,
            final boolean enabled) throws Exception {
        final Snapshot snapshot = snapshot(config.accessPolicyPath(), config.registryPath());
        try {
            proposed.save(config.accessPolicyPath());
            final int policyRevision = changed == null ? 0 : changed.revision();
            final GraphicalMatrixSpRegistry.Entry updated = entry
                .withAccessPolicy(enabled, policyRevision, Instant.now())
                .withRevision(entry.currentRevision() + 1, Instant.now());
            registry.put(updated);
            registry.save(config.registryPath());
            saveGovernanceRevision(updated, changed, event);
            secureGovernanceFiles();
            validateConsistency();
            audit(event, entry.name(), entry.entityId(), "OK",
                "access_policy_revision=" + policyRevision);
            System.out.println("result=APPLY_OK");
            System.out.println("policy_revision=" + policyRevision);
            System.out.println("reload_delay_seconds=" + config.accessReloadIntervalSeconds());
        } catch (Exception ex) {
            snapshot.restore();
            audit(event, entry.name(), entry.entityId(), "FAILED", rootMessage(ex));
            throw ex;
        }
    }

    private void attributeDiscover(final Arguments options) throws Exception {
        options.allow(Set.of("sp", "user", "format"), Set.of());
        options.noPositionals("attributes discover");
        if (options.has("sp") != options.has("user")) {
            throw new IllegalArgumentException("--sp and --user must be specified together");
        }
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        final GraphicalMatrixSpRegistry registry = registry();
        final GraphicalMatrixSpAccessPolicy policy =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        final Map<String, GraphicalMatrixAttributeDiscovery.Candidate> discovered =
            new LinkedHashMap<>();
        for (final GraphicalMatrixAttributeDiscovery.Candidate item :
                GraphicalMatrixAttributeDiscovery.discover(config, catalog, registry, policy)) {
            discovered.put(item.id(), item);
        }
        if (options.has("sp")) {
            final GraphicalMatrixSpRegistry.Entry entry = registry.get(options.required("sp"));
            final Map<String, List<String>> runtime = GraphicalMatrixAttributeResolverTest.resolve(
                config, options.required("user"), entry.entityId());
            for (final String id : runtime.keySet()) {
                final GraphicalMatrixAttributeDiscovery.Candidate old = discovered.get(id);
                discovered.put(id, new GraphicalMatrixAttributeDiscovery.Candidate(id,
                    "runtime-observed", old == null ? "unknown" : old.samlMapping(),
                    old == null ? config.isBlockedAttribute(id) ? "blocked" : "candidate"
                        : old.governance(),
                    old == null ? Set.of() : old.usedBy()));
            }
        }
        final List<GraphicalMatrixAttributeDiscovery.Candidate> values =
            discovered.values().stream().sorted(Comparator.comparing(
                GraphicalMatrixAttributeDiscovery.Candidate::id)).toList();
        if ("json".equals(options.single("format", "text"))) {
            final List<Object> rows = new ArrayList<>();
            for (final var item : values) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id());
                row.put("resolution", item.resolution());
                row.put("samlMapping", item.samlMapping());
                row.put("governance", item.governance());
                row.put("usedBy", item.usedBy().stream().sorted().toList());
                rows.add(row);
            }
            System.out.print(GraphicalMatrixJson.write(rows));
            return;
        }
        System.out.printf("%-28s %-18s %-18s %-20s %s%n",
            "ATTRIBUTE", "RESOLUTION", "SAML MAPPING", "GOVERNANCE", "USED BY");
        for (final var item : values) {
            System.out.printf("%-28s %-18s %-18s %-20s %s%n", item.id(),
                item.resolution(), item.samlMapping(), item.governance(),
                item.usedBy().isEmpty() ? "-" : String.join(",", item.usedBy()));
        }
        audit("ATTRIBUTE_DISCOVER", options.single("sp", "-"), "-", "OK",
            "candidate_count=" + values.size());
    }

    private void attributeList(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        options.noPositionals("attributes list");
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        if ("json".equals(options.single("format", "text"))) {
            System.out.print(catalog.toJson());
            return;
        }
        System.out.printf("%-28s %-18s %-18s %-20s %s%n",
            "ATTRIBUTE", "RESOLUTION", "SAML MAPPING", "GOVERNANCE", "CLASSIFICATION");
        for (final var item : catalog.attributes()) {
            System.out.printf("%-28s %-18s %-18s %-20s %s%n", item.id(),
                item.resolution(), item.samlMapping(), item.governance(),
                item.classification());
        }
    }

    private void attributeShow(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        final String id = one(options, "attributes show ATTRIBUTE");
        final GraphicalMatrixAttributeCatalog.Attribute item = catalog().requireAttribute(id);
        if ("json".equals(options.single("format", "text"))) {
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", item.id());
            out.put("resolution", item.resolution());
            out.put("samlMapping", item.samlMapping());
            out.put("governance", item.governance());
            out.put("classification", item.classification());
            out.put("purpose", item.purpose());
            out.put("updatedAt", item.updatedAt());
            System.out.print(GraphicalMatrixJson.write(out));
            return;
        }
        System.out.println("attribute=" + item.id());
        System.out.println("resolution=" + item.resolution());
        System.out.println("saml_mapping=" + item.samlMapping());
        System.out.println("governance=" + item.governance());
        System.out.println("classification=" + item.classification());
        System.out.println("purpose=" + item.purpose());
        System.out.println("updated_at=" + item.updatedAt());
    }

    private void attributeApprove(final Arguments options) throws Exception {
        options.allow(Set.of("usage", "classification", "purpose", "confirm"), Set.of("apply"));
        final String id = one(options, "attributes approve ATTRIBUTE --usage access|release");
        final String usage = options.required("usage");
        if (!Set.of("access", "release").contains(usage)) {
            throw new IllegalArgumentException("--usage must be access or release");
        }
        final String classification = options.required("classification");
        if (!GraphicalMatrixAttributeCatalog.CLASSIFICATION.contains(classification)
                || "credential".equals(classification)) {
            throw new IllegalArgumentException("invalid approval classification");
        }
        final String purpose = options.required("purpose");
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        if (catalog.isBlocked(id, config.blockedAttributes())) {
            throw new IllegalArgumentException("blocked attribute cannot be approved: " + id);
        }
        final GraphicalMatrixAttributeDiscovery.Candidate candidate =
            candidate(id, catalog);
        if ("release".equals(usage) && !"mapped".equals(candidate.samlMapping())) {
            throw new IllegalStateException(
                "attribute requires a SAML mapping before release approval: " + id);
        }
        final String governance = "release".equals(usage)
            ? "release-approved" : "internal-only";
        final GraphicalMatrixAttributeCatalog.Attribute approved =
            new GraphicalMatrixAttributeCatalog.Attribute(id, candidate.resolution(),
                candidate.samlMapping(), governance, classification, purpose,
                Instant.now().toString());
        System.out.println("mode=" + mode(options));
        System.out.println("action=ATTRIBUTE_APPROVE");
        System.out.println("attribute=" + id);
        System.out.println("usage=" + usage);
        System.out.println("governance_new=" + governance);
        System.out.println("saml_mapping=" + candidate.samlMapping());
        if (!options.apply()) {
            return;
        }
        confirm(options, id);
        applyCatalog("ATTRIBUTE_APPROVE", catalog.withAttribute(approved), id, Set.of());
    }

    private void attributeBlock(final Arguments options) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String id = one(options, "attributes block ATTRIBUTE");
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        final Set<String> references = references(id, catalog,
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()));
        if (!references.isEmpty()) {
            throw new IllegalStateException("attribute is in use: "
                + String.join(",", references));
        }
        final GraphicalMatrixAttributeCatalog.Attribute old = catalog.findAttribute(id);
        final GraphicalMatrixAttributeCatalog.Attribute blocked =
            new GraphicalMatrixAttributeCatalog.Attribute(id,
                old == null ? "unknown" : old.resolution(),
                old == null ? "unknown" : old.samlMapping(),
                "blocked", "credential", "Blocked by administrator",
                Instant.now().toString());
        System.out.println("mode=" + mode(options));
        System.out.println("action=ATTRIBUTE_BLOCK");
        System.out.println("attribute=" + id);
        if (!options.apply()) {
            return;
        }
        confirm(options, id);
        applyCatalog("ATTRIBUTE_BLOCK", catalog.withAttribute(blocked), id, Set.of());
    }

    private void attributeProfile(final Arguments options) throws Exception {
        if (options.positionals().isEmpty()) {
            throw new IllegalArgumentException("missing attributes profile subcommand");
        }
        final String command = options.positionals().get(0);
        final Arguments nested = options.withoutFirstPositional();
        switch (command) {
            case "list" -> profileList(nested);
            case "show" -> profileShow(nested);
            case "create" -> profileSave(nested, false);
            case "update" -> profileSave(nested, true);
            case "remove" -> profileRemove(nested);
            case "import-legacy" -> profileImportLegacy(nested);
            default -> throw new IllegalArgumentException(
                "unknown attributes profile subcommand: " + command);
        }
    }

    private void profileList(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        options.noPositionals("attributes profile list");
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        final List<ProfileView> profiles = profileViews(catalog);
        if ("json".equals(options.single("format", "text"))) {
            final List<Object> out = new ArrayList<>();
            for (final ProfileView profile : profiles) {
                out.add(profileMap(profile));
            }
            System.out.print(GraphicalMatrixJson.write(out));
            return;
        }
        System.out.printf("%-32s %-10s %-20s %-20s %s%n",
            "PROFILE", "REVISION", "SOURCE", "STATUS", "ATTRIBUTES");
        for (final ProfileView profile : profiles) {
            System.out.printf("%-32s %-10d %-20s %-20s %s%n", profile.name(),
                profile.revision(), profile.source(), profile.warning().isBlank()
                    ? "OK" : profile.warning(),
                profile.attributes().isEmpty() ? "-" : String.join(",", profile.attributes()));
        }
    }

    private void profileShow(final Arguments options) throws Exception {
        options.allow(Set.of("format"), Set.of());
        final String name = one(options, "attributes profile show NAME");
        final ProfileView profile = profileViews(catalog()).stream()
            .filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        if (profile == null) {
            throw new IllegalArgumentException("attribute profile not found: " + name);
        }
        if ("json".equals(options.single("format", "text"))) {
            System.out.print(GraphicalMatrixJson.write(profileMap(profile)));
            return;
        }
        System.out.println("profile=" + profile.name());
        System.out.println("attributes=" + String.join(",", profile.attributes()));
        System.out.println("description=" + profile.description());
        System.out.println("revision=" + profile.revision());
        System.out.println("source=" + profile.source());
        System.out.println("status=" + (profile.warning().isBlank() ? "OK" : profile.warning()));
    }

    private void profileSave(final Arguments options, final boolean update) throws Exception {
        options.allow(Set.of("attributes", "description", "confirm"), Set.of("apply"));
        final String name = one(options,
            "attributes profile " + (update ? "update" : "create") + " NAME");
        GraphicalMatrixAttributeCatalog.validateProfileName(name);
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        final GraphicalMatrixAttributeCatalog.Profile old = catalog.findProfile(name);
        if (update && old == null) {
            throw new IllegalArgumentException("managed attribute profile not found: " + name);
        }
        if (!update && (old != null || config.attributeProfiles().containsKey(name))) {
            throw new IllegalArgumentException("attribute profile already exists: " + name);
        }
        final List<String> attributes = csv(options.required("attributes"));
        validateReleaseProfileAttributes(catalog, attributes);
        final String description = options.single("description",
            old == null ? "" : old.description());
        final GraphicalMatrixAttributeCatalog.Profile proposed =
            new GraphicalMatrixAttributeCatalog.Profile(name, attributes, description,
                old == null ? 1 : old.revision() + 1, "managed", Instant.now().toString());
        final GraphicalMatrixAttributeCatalog updated = catalog.withProfile(proposed);
        final Set<String> affected = registry().entries().stream()
            .filter(entry -> entry.attributeProfile().equals(name))
            .map(GraphicalMatrixSpRegistry.Entry::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        System.out.println("mode=" + mode(options));
        System.out.println("action=ATTRIBUTE_PROFILE_" + (update ? "UPDATE" : "CREATE"));
        System.out.println("profile=" + name);
        System.out.println("revision_new=" + proposed.revision());
        System.out.println("attributes=" + String.join(",", proposed.attributes()));
        System.out.println("affected_sps=" + (affected.isEmpty() ? "-" : String.join(",", affected)));
        if (!options.apply()) {
            return;
        }
        confirm(options, name);
        applyCatalog("ATTRIBUTE_PROFILE_" + (update ? "UPDATE" : "CREATE"),
            updated, name, affected);
    }

    private void profileRemove(final Arguments options) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String name = one(options, "attributes profile remove NAME");
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        if (catalog.findProfile(name) == null) {
            throw new IllegalArgumentException("managed attribute profile not found: " + name);
        }
        final Set<String> affected = registry().entries().stream()
            .filter(entry -> entry.attributeProfile().equals(name))
            .map(GraphicalMatrixSpRegistry.Entry::name).collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!affected.isEmpty()) {
            throw new IllegalStateException("profile is used by SPs: "
                + String.join(",", affected));
        }
        System.out.println("mode=" + mode(options));
        System.out.println("action=ATTRIBUTE_PROFILE_REMOVE");
        System.out.println("profile=" + name);
        if (!options.apply()) {
            return;
        }
        confirm(options, name);
        applyCatalog("ATTRIBUTE_PROFILE_REMOVE", catalog.withoutProfile(name), name, Set.of());
    }

    private void profileImportLegacy(final Arguments options) throws Exception {
        options.allow(Set.of("new-name", "confirm"), Set.of("apply"));
        final String source = one(options,
            "attributes profile import-legacy SOURCE --new-name NAME");
        final String name = options.required("new-name");
        GraphicalMatrixAttributeCatalog.validateProfileName(name);
        if (Set.of("none", "uid", "uid-mail").contains(source)) {
            throw new IllegalArgumentException("built-in profiles do not require import");
        }
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        if (catalog.findProfile(source) != null) {
            throw new IllegalArgumentException("source is already a managed profile: " + source);
        }
        final List<String> attributes = config.attributeProfiles().get(source);
        if (attributes == null) {
            throw new IllegalArgumentException("legacy properties profile not found: " + source);
        }
        validateReleaseProfileAttributes(catalog, attributes);
        final GraphicalMatrixAttributeCatalog.Profile proposed =
            new GraphicalMatrixAttributeCatalog.Profile(name, attributes,
                "Imported from properties profile " + source, 1,
                "legacy-properties", Instant.now().toString());
        final GraphicalMatrixAttributeCatalog updated = catalog.withProfile(proposed);
        System.out.println("mode=" + mode(options));
        System.out.println("action=ATTRIBUTE_PROFILE_IMPORT_LEGACY");
        System.out.println("source_profile=" + source);
        System.out.println("new_profile=" + name);
        System.out.println("attributes=" + String.join(",", attributes));
        if (!options.apply()) {
            return;
        }
        confirm(options, name);
        applyCatalog("ATTRIBUTE_PROFILE_IMPORT_LEGACY", updated, name, Set.of());
    }

    private void applyCatalog(final String event,
            final GraphicalMatrixAttributeCatalog proposed, final String subject,
            final Set<String> affectedSpNames) throws Exception {
        final Snapshot snapshot = snapshot(config.attributeCatalogPath(),
            config.attributeFilterPath(), config.registryPath());
        try {
            proposed.save(config.attributeCatalogPath());
            final GraphicalMatrixSpManagementConfig updatedConfig =
                GraphicalMatrixSpManagementConfig.load(config.idpHome().toString());
            final GraphicalMatrixSpRegistry registry = registry();
            final ProfileRevisionUpdate update =
                updateProfileRevisions(registry, proposed, affectedSpNames);
            GraphicalMatrixSpXmlConfig.renderManagedAttributes(config.attributeFilterPath(),
                update.registry().entries(), updatedConfig);
            update.registry().save(config.registryPath());
            for (final GraphicalMatrixSpRegistry.Entry entry : update.changed()) {
                saveGovernanceRevision(entry, null, event);
            }
            secureGovernanceFiles();
            validateConsistency();
            if (!affectedSpNames.isEmpty()) {
                reloadAttributeFilter();
            }
            audit(event, "-", "-", "OK", "subject=" + subject);
            System.out.println("result=APPLY_OK");
        } catch (Exception ex) {
            snapshot.restore();
            if (!affectedSpNames.isEmpty()) {
                try {
                    reloadAttributeFilter();
                } catch (Exception reloadFailure) {
                    ex.addSuppressed(reloadFailure);
                }
            }
            audit(event, "-", "-", "FAILED", rootMessage(ex));
            throw ex;
        }
    }

    private ProfileRevisionUpdate updateProfileRevisions(
            final GraphicalMatrixSpRegistry registry,
            final GraphicalMatrixAttributeCatalog catalog,
            final Set<String> affectedSpNames) {
        final List<GraphicalMatrixSpRegistry.Entry> changed = new ArrayList<>();
        for (final String name : affectedSpNames) {
            final GraphicalMatrixSpRegistry.Entry entry = registry.get(name);
            final GraphicalMatrixAttributeCatalog.Profile profile =
                catalog.findProfile(entry.attributeProfile());
            if (profile == null) {
                throw new IllegalStateException(
                    "used managed profile is missing: " + entry.attributeProfile());
            }
            final GraphicalMatrixSpRegistry.Entry updated = entry
                .withAttributeProfileRevision(profile.revision(), Instant.now())
                .withRevision(entry.currentRevision() + 1, Instant.now());
            registry.put(updated);
            changed.add(updated);
        }
        return new ProfileRevisionUpdate(registry, List.copyOf(changed));
    }

    private record ProfileRevisionUpdate(GraphicalMatrixSpRegistry registry,
                                         List<GraphicalMatrixSpRegistry.Entry> changed) {
    }

    private GraphicalMatrixAttributeDiscovery.Candidate candidate(final String id,
            final GraphicalMatrixAttributeCatalog catalog) throws Exception {
        return GraphicalMatrixAttributeDiscovery.discover(config, catalog, registry(),
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath())).stream()
            .filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "attribute was not discovered in IdP configuration: " + id));
    }

    private void validateAccessAttributes(final GraphicalMatrixAttributeCatalog catalog,
            final List<GraphicalMatrixSpAccessPolicy.Rule> allow,
            final List<GraphicalMatrixSpAccessPolicy.Rule> deny) {
        final Set<String> ids = new LinkedHashSet<>();
        allow.forEach(item -> ids.add(item.attributeId()));
        deny.forEach(item -> ids.add(item.attributeId()));
        if (ids.size() > GraphicalMatrixSpAccessPolicy.MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("access policy attribute count exceeds "
                + GraphicalMatrixSpAccessPolicy.MAX_ATTRIBUTES);
        }
        for (final String id : ids) {
            final GraphicalMatrixAttributeCatalog.Attribute attribute =
                catalog.requireAttribute(id);
            if (!attribute.accessApproved() || catalog.isBlocked(id,
                    config.blockedAttributes())) {
                throw new IllegalArgumentException(
                    "attribute is not approved for access policy use: " + id);
            }
        }
    }

    private void validateReleaseProfileAttributes(final GraphicalMatrixAttributeCatalog catalog,
            final List<String> attributes) throws Exception {
        final Map<String, GraphicalMatrixAttributeDiscovery.Candidate> discovered =
            new LinkedHashMap<>();
        for (final GraphicalMatrixAttributeDiscovery.Candidate candidate :
                GraphicalMatrixAttributeDiscovery.discover(config, catalog, registry(),
                    GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()))) {
            discovered.put(candidate.id(), candidate);
        }
        final List<String> invalid = new ArrayList<>();
        for (final String id : attributes) {
            final GraphicalMatrixAttributeCatalog.Attribute attribute = catalog.findAttribute(id);
            final GraphicalMatrixAttributeDiscovery.Candidate candidate = discovered.get(id);
            if (attribute == null || !attribute.releaseApproved() || candidate == null
                    || !"mapped".equals(candidate.samlMapping())
                    || catalog.isBlocked(id, config.blockedAttributes())) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("attribute profile cannot be created or updated because "
                + "its attributes are not currently release-approved and SAML-mapped: "
                + String.join(",", invalid)
                + "; run attributes discover and configure the IdP attribute mapping first");
        }
    }

    private void validateConsistency() throws Exception {
        final GraphicalMatrixSpRegistry registry = registry();
        final GraphicalMatrixSpAccessPolicy policy =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath());
        final GraphicalMatrixAttributeCatalog catalog = catalog();
        for (final GraphicalMatrixSpAccessPolicy.Policy item : policy.policies()) {
            final GraphicalMatrixSpRegistry.Entry entry = registry.get(item.name());
            if (!entry.entityId().equals(item.entityId())) {
                throw new IllegalStateException(
                    "access policy entityID mismatch: " + item.name());
            }
            validateAccessAttributes(catalog, item.allow(), item.deny());
        }
        for (final GraphicalMatrixSpRegistry.Entry entry : registry.entries()) {
            config.attributesFor(entry.attributeProfile());
        }
        if (config.accessEnabled()) {
            final var inspection = GraphicalMatrixSpAccessXmlConfig.inspect(
                config.contextCheckConfigPath(), config.relyingPartyPath());
            if (inspection.conflict() || !inspection.managedFunction()
                    || inspection.saml2Profiles() == 0
                    || inspection.saml2Profiles() != inspection.contextCheckProfiles()) {
                throw new IllegalStateException("ContextCheck integration is inconsistent");
            }
        }
    }

    private GraphicalMatrixSpRegistry registry() throws IOException {
        return GraphicalMatrixSpRegistry.load(config.registryPath());
    }

    private GraphicalMatrixAttributeCatalog catalog() throws IOException {
        return GraphicalMatrixAttributeCatalog.load(config.attributeCatalogPath());
    }

    private void reloadAttributeFilter() throws Exception {
        if (!config.reloadEnabled()) {
            return;
        }
        runRequired(List.of(config.idpHome().resolve("bin/reload-service.sh").toString(),
            "-id", "shibboleth.AttributeFilterService"));
    }

    private static void runRequired(final List<String> command) throws Exception {
        final Path executable = Path.of(command.get(0));
        if (!Files.isExecutable(executable)) {
            throw new IOException("required command is missing or not executable: " + executable);
        }
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        final Process process = builder.start();
        final String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        final int status = process.waitFor();
        if (status != 0) {
            throw new IOException("command failed (" + status + "): " + executable
                + ": " + output.trim());
        }
    }

    private void updateProperties(final Map<String, String> updates) throws IOException {
        final List<String> lines = new ArrayList<>(Files.readAllLines(config.configPath(),
            StandardCharsets.ISO_8859_1));
        final Set<String> pending = new LinkedHashSet<>(updates.keySet());
        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();
            for (final Map.Entry<String, String> entry : updates.entrySet()) {
                if (trimmed.matches(java.util.regex.Pattern.quote(entry.getKey())
                        + "\\s*=.*")) {
                    if (!pending.remove(entry.getKey())) {
                        throw new IOException("duplicate property: " + entry.getKey());
                    }
                    lines.set(i, entry.getKey() + " = " + entry.getValue());
                }
            }
        }
        if (!pending.isEmpty()) {
            lines.add("");
            lines.add("# BEGIN 2FAS-KW SP access settings - managed by graphicalmatrix-sp.sh");
            for (final String key : updates.keySet()) {
                if (pending.contains(key)) {
                    lines.add(key + " = " + updates.get(key));
                }
            }
            lines.add("# END 2FAS-KW SP access settings");
        }
        GraphicalMatrixSpFiles.atomicWrite(config.configPath(),
            String.join("\n", lines) + "\n");
    }

    private void secureGovernanceFiles() throws IOException {
        if (config.runtimeGroup().isBlank()) {
            return;
        }
        final Path directory = config.registryPath().getParent();
        setRuntimeGroup(directory);
        requirePermissions(directory, "rwxr-x---");
        secureRuntimeFile(config.accessPolicyPath());
        secureRuntimeFile(config.attributeCatalogPath());
        secureRuntimeFile(config.registryPath());
    }

    private void secureRuntimeFile(final Path path) throws IOException {
        setRuntimeGroup(path);
        requirePermissions(path, "rw-r-----");
    }

    private void requireRuntimeGroup() {
        if (config.runtimeGroup().isBlank()) {
            throw new IllegalStateException("graphicalmatrix.sp.runtimeGroup must be set before "
                + "enabling SP attribute access control");
        }
    }

    private void setRuntimeGroup(final Path path) throws IOException {
        try {
            if (!Files.exists(path)) {
                return;
            }
            final PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setGroup(FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(config.runtimeGroup()));
            }
        } catch (UnsupportedOperationException ignored) {
            // POSIX ownership is unavailable on some test filesystems.
        }
    }

    private static void requirePermissions(final Path path, final String mode) throws IOException {
        if (Files.exists(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        }
    }

    private static void setPermissions(final Path path, final String mode) {
        try {
            if (Files.exists(path)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are unavailable on some test filesystems.
        }
    }

    private void saveGovernanceRevision(final GraphicalMatrixSpRegistry.Entry entry,
            final GraphicalMatrixSpAccessPolicy.Policy policy, final String event)
            throws IOException {
        final Path directory = config.revisionsDirectory().resolve(entry.name()).resolve(
            String.format(Locale.ROOT, "revision-%06d", entry.currentRevision()));
        Files.createDirectories(directory);
        GraphicalMatrixSpRegistry.saveEntry(directory.resolve("record.json"), entry);
        final Path metadata = config.idpHome().resolve(entry.metadataFile()).normalize();
        if (Files.isRegularFile(metadata)) {
            GraphicalMatrixSpFiles.atomicWrite(directory.resolve("metadata.xml"),
                Files.readAllBytes(metadata));
        }
        final GraphicalMatrixSpAccessPolicy.Policy savedPolicy = policy != null ? policy
            : GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()).find(entry.name());
        if (savedPolicy != null) {
            GraphicalMatrixSpFiles.atomicWrite(directory.resolve("access-policy.json"),
                GraphicalMatrixSpAccessPolicy.singlePolicyJson(savedPolicy));
        }
        if (Files.isRegularFile(config.attributeCatalogPath())) {
            GraphicalMatrixSpFiles.atomicWrite(directory.resolve("attribute-catalog.json"),
                Files.readAllBytes(config.attributeCatalogPath()));
        }
        GraphicalMatrixSpFiles.atomicWrite(directory.resolve("event.txt"), event + "\n");
        setPermissions(directory, "rwx------");
        try (var files = Files.list(directory)) {
            files.forEach(path -> setPermissions(path, "rw-------"));
        }
    }

    private Snapshot snapshot(final Path... paths) throws IOException {
        Files.createDirectories(config.backupsDirectory());
        setPermissions(config.backupsDirectory(), "rwx------");
        pruneBackups();
        final Snapshot snapshot = new Snapshot(paths);
        snapshot.persist(config.backupsDirectory());
        return snapshot;
    }

    private void pruneBackups() throws IOException {
        if (!Files.isDirectory(config.backupsDirectory())) {
            return;
        }
        final Instant cutoff = Instant.now().minus(
            config.backupRetentionDays(), ChronoUnit.DAYS);
        try (var paths = Files.list(config.backupsDirectory())) {
            for (final Path directory : paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("transaction-"))
                    .toList()) {
                if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                    try (var files = Files.list(directory)) {
                        for (final Path file : files.toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.deleteIfExists(directory);
                }
            }
        }
    }

    private void audit(final String event, final String name, final String entityId,
            final String result, final String detail) {
        try {
            Files.createDirectories(config.auditLogPath().getParent());
            final String actor = System.getenv().getOrDefault("SUDO_USER",
                System.getenv().getOrDefault("USER", "unknown"));
            final String line = "ts=" + Instant.now() + " event=" + token(event)
                + " actor=" + token(actor) + " name=" + token(name)
                + " entity_id=" + token(entityId) + " result=" + token(result)
                + " detail=" + token(detail) + System.lineSeparator();
            Files.writeString(config.auditLogPath(), line, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            setPermissions(config.auditLogPath(), "rw-r-----");
        } catch (Exception ex) {
            System.err.println("WARN: unable to write SP management audit log: "
                + rootMessage(ex));
        }
    }

    private static List<GraphicalMatrixSpAccessPolicy.Rule> rules(
            final List<String> values, final String label) {
        final List<GraphicalMatrixSpAccessPolicy.Rule> out = new ArrayList<>();
        for (final String raw : values) {
            final int separator = raw.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException(
                    "--" + label + " must use ATTRIBUTE=VALUE");
            }
            out.add(new GraphicalMatrixSpAccessPolicy.Rule(raw.substring(0, separator),
                List.of(raw.substring(separator + 1))));
        }
        return List.copyOf(out);
    }

    private static Set<String> references(final String id,
            final GraphicalMatrixAttributeCatalog catalog,
            final GraphicalMatrixSpAccessPolicy policy) {
        final Set<String> references = new LinkedHashSet<>();
        for (final GraphicalMatrixAttributeCatalog.Profile profile : catalog.profiles()) {
            if (profile.attributes().contains(id)) {
                references.add("profile:" + profile.name());
            }
        }
        for (final GraphicalMatrixSpAccessPolicy.Policy item : policy.policies()) {
            if (item.allow().stream().anyMatch(rule -> rule.attributeId().equals(id))
                    || item.deny().stream().anyMatch(rule -> rule.attributeId().equals(id))) {
                references.add("access:" + item.name());
            }
        }
        return Set.copyOf(references);
    }

    private static GraphicalMatrixSpAccessPolicy.Policy requirePolicy(
            final GraphicalMatrixSpAccessPolicy policy, final String name) {
        final GraphicalMatrixSpAccessPolicy.Policy found = policy.find(name);
        if (found == null) {
            throw new IllegalArgumentException("access policy is not configured: " + name);
        }
        return found;
    }

    private static void confirmEntity(final Arguments options,
            final GraphicalMatrixSpRegistry.Entry entry) {
        if (!entry.entityId().equals(options.single("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match entityID");
        }
    }

    private static void confirm(final Arguments options, final String expected) {
        if (!expected.equals(options.single("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match " + expected);
        }
    }

    private static void printRules(final String label,
            final List<GraphicalMatrixSpAccessPolicy.Rule> rules) {
        for (final GraphicalMatrixSpAccessPolicy.Rule rule : rules) {
            System.out.println(label + "." + rule.attributeId() + "="
                + String.join(",", rule.values()));
        }
    }

    private static void printInspection(
            final GraphicalMatrixSpAccessXmlConfig.Inspection value) {
        System.out.println("context_function_configured=" + value.functionConfigured());
        System.out.println("context_function_managed=" + value.managedFunction());
        System.out.println("context_condition_configured=" + value.conditionConfigured());
        System.out.println("context_condition_stock=" + value.stockCondition());
        System.out.println("saml2_sso_profiles=" + value.saml2Profiles());
        System.out.println("context_check_profiles=" + value.contextCheckProfiles());
    }

    private List<ProfileView> profileViews(final GraphicalMatrixAttributeCatalog catalog) {
        final List<ProfileView> out = new ArrayList<>();
        out.add(new ProfileView("none", List.of(), "No attributes", 0,
            "builtin", "", ""));
        out.add(new ProfileView("uid", List.of("uid"), "Built-in uid profile", 0,
            "builtin", "", ""));
        out.add(new ProfileView("uid-mail", List.of("uid", "mail"),
            "Built-in uid and mail profile", 0, "builtin", "", ""));
        for (final String name : config.legacyAttributeProfileNames().stream().sorted().toList()) {
            out.add(new ProfileView(name, config.attributesFor(name),
                "Read-only profile from sp-management.properties", 0,
                "legacy-properties", "", "LEGACY_UNREVIEWED"));
        }
        for (final GraphicalMatrixAttributeCatalog.Profile profile : catalog.profiles()) {
            out.add(new ProfileView(profile.name(), profile.attributes(), profile.description(),
                profile.revision(), profile.source(), profile.updatedAt(), ""));
        }
        return List.copyOf(out);
    }

    private record ProfileView(String name, List<String> attributes, String description,
                               int revision, String source, String updatedAt, String warning) {
    }

    private static Map<String, Object> profileMap(final ProfileView profile) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", profile.name());
        out.put("attributes", profile.attributes());
        out.put("description", profile.description());
        out.put("revision", (long) profile.revision());
        out.put("source", profile.source());
        out.put("updatedAt", profile.updatedAt());
        out.put("status", profile.warning().isBlank() ? "OK" : profile.warning());
        return out;
    }

    private static String repeated(final String option, final List<String> values) {
        final StringBuilder out = new StringBuilder();
        for (final String value : values) {
            out.append(' ').append(option).append(' ').append(shellQuote(value));
        }
        return out.toString();
    }

    private static String one(final Arguments options, final String usage) {
        if (options.positionals().size() != 1) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return options.positionals().get(0);
    }

    private static List<String> csv(final String value) {
        final List<String> out = new ArrayList<>();
        for (final String raw : value.split(",", -1)) {
            final String item = raw.trim();
            if (!item.isEmpty() && !out.contains(item)) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static String mode(final Arguments options) {
        return options.apply() ? "apply" : "dry-run";
    }

    private static String truncate(final String value, final int length) {
        return value.length() <= length ? value
            : value.substring(0, Math.max(1, length - 1)) + "…";
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String token(final String value) {
        return value == null || value.isBlank() ? "-"
            : value.replace("\\", "\\\\").replace(" ", "\\ ")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String rootMessage(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
            : current.getMessage();
    }

    private static void usage(final String group) {
        if ("access".equals(group)) {
            System.err.println("Access: init, list, show, set, disable, enable, clear, test");
        } else {
            System.err.println("Attributes: discover, list, show, approve, block, profile");
        }
    }

    private record Arguments(Map<String, List<String>> values, Set<String> flags,
                             List<String> positionals) {
        static Arguments parse(final String[] arguments) {
            final Map<String, List<String>> values = new LinkedHashMap<>();
            final Set<String> flags = new LinkedHashSet<>();
            final List<String> positionals = new ArrayList<>();
            for (int i = 0; i < arguments.length; i++) {
                final String argument = arguments[i];
                if (!argument.startsWith("--")) {
                    positionals.add(argument);
                    continue;
                }
                final String key = argument.substring(2);
                if (!key.matches("[a-z][a-z0-9-]*")) {
                    throw new IllegalArgumentException("invalid option: " + argument);
                }
                if ("apply".equals(key)) {
                    if (!flags.add(key)) {
                        throw new IllegalArgumentException("duplicate option: " + argument);
                    }
                } else {
                    if (++i >= arguments.length || arguments[i].startsWith("--")) {
                        throw new IllegalArgumentException(
                            "option requires a value: " + argument);
                    }
                    values.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(arguments[i]);
                }
            }
            final Map<String, List<String>> immutable = new LinkedHashMap<>();
            values.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new Arguments(Map.copyOf(immutable), Set.copyOf(flags),
                List.copyOf(positionals));
        }

        Arguments withoutFirstPositional() {
            return new Arguments(values, flags,
                positionals.subList(1, positionals.size()));
        }

        boolean apply() {
            return flags.contains("apply");
        }

        boolean has(final String key) {
            return values.containsKey(key);
        }

        List<String> values(final String key) {
            return values.getOrDefault(key, List.of());
        }

        String single(final String key, final String defaultValue) {
            final List<String> found = values(key);
            if (found.isEmpty()) {
                return defaultValue;
            }
            if (found.size() != 1) {
                throw new IllegalArgumentException("duplicate option: --" + key);
            }
            return found.get(0);
        }

        String required(final String key) {
            final String value = single(key, "");
            if (value.isBlank()) {
                throw new IllegalArgumentException("missing required option: --" + key);
            }
            return value;
        }

        void allow(final Set<String> allowedValues, final Set<String> allowedFlags) {
            for (final String key : values.keySet()) {
                if (!allowedValues.contains(key)) {
                    throw new IllegalArgumentException(
                        "unsupported option for command: --" + key);
                }
            }
            for (final String key : flags) {
                if (!allowedFlags.contains(key)) {
                    throw new IllegalArgumentException(
                        "unsupported flag for command: --" + key);
                }
            }
        }

        void noPositionals(final String usage) {
            if (!positionals.isEmpty()) {
                throw new IllegalArgumentException("usage: " + usage);
            }
        }
    }

    private static final class Snapshot {
        private final Map<Path, byte[]> originals = new LinkedHashMap<>();
        private final Set<Path> missing = new LinkedHashSet<>();

        Snapshot(final Path... paths) throws IOException {
            for (final Path path : paths) {
                final Path normalized = path.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    originals.put(normalized, Files.readAllBytes(normalized));
                } else {
                    missing.add(normalized);
                }
            }
        }

        void persist(final Path root) throws IOException {
            final Path directory = Files.createTempDirectory(root, "transaction-");
            setPermissions(directory, "rwx------");
            int index = 0;
            for (final Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                final String name = String.format(Locale.ROOT, "%02d-%s", ++index,
                    entry.getKey().getFileName());
                final Path backup = directory.resolve(name);
                Files.write(backup, entry.getValue());
                setPermissions(backup, "rw-------");
            }
        }

        void restore() throws IOException {
            IOException failure = null;
            for (final Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                try {
                    GraphicalMatrixSpFiles.atomicWrite(entry.getKey(), entry.getValue());
                } catch (IOException ex) {
                    failure = ex;
                }
            }
            for (final Path path : missing) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    failure = ex;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
