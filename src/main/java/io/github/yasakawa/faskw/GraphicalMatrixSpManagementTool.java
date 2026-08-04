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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GraphicalMatrixSpManagementTool {
    private static final Set<String> MUTATING = Set.of(
        "init", "add", "update", "set-attributes", "set-mfa", "disable", "enable",
        "remove", "adopt", "rollback", "restore-legacy");
    private static final String GUIDANCE_RULE =
        "============================================================";

    private record NextStep(String title, List<String> notes, String command) {
        NextStep {
            notes = List.copyOf(notes);
        }
    }

    private final GraphicalMatrixSpManagementConfig config;

    private GraphicalMatrixSpManagementTool(final GraphicalMatrixSpManagementConfig value) {
        config = value;
    }

    public static void main(final String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        try {
            final GraphicalMatrixSpManagementConfig config =
                GraphicalMatrixSpManagementConfig.load(args[0]);
            final String command = args[1];
            final Options options = Options.parse(Arrays.copyOfRange(args, 2, args.length));
            if (MUTATING.contains(command) && options.apply() && !config.enabled()) {
                throw new IllegalStateException(GraphicalMatrixSpManagementConfig.ENABLED
                    + " must be true before applying SP management changes");
            }
            new GraphicalMatrixSpManagementTool(config).execute(command, options);
        } catch (Exception ex) {
            System.err.println("ERROR: " + rootMessage(ex));
            System.exit(ex instanceof IllegalArgumentException ? 2 : 1);
        }
    }

    private void execute(final String command, final Options options) throws Exception {
        switch (command) {
            case "status" -> status(options);
            case "list" -> list(options);
            case "next" -> next(options);
            case "init" -> initialize(options);
            case "add" -> add(options);
            case "update" -> update(options);
            case "set-attributes" -> setAttributes(options);
            case "set-mfa" -> setMfa(options);
            case "disable" -> setEnabled(options, false);
            case "enable" -> setEnabled(options, true);
            case "remove" -> remove(options);
            case "verify" -> verify(options);
            case "check-update" -> checkUpdate(options);
            case "adopt" -> adopt(options);
            case "history" -> history(options);
            case "rollback" -> rollback(options);
            case "restore-legacy" -> restoreLegacy(options);
            default -> {
                usage();
                throw new IllegalArgumentException("unknown SP management command: " + command);
            }
        }
    }

    private void status(final Options options) throws Exception {
        options.allow(Set.of("entity-id", "format"), Set.of());
        final List<GraphicalMatrixSpXmlConfig.Inventory> inventory = inventoryWithDuplicates();
        final String filter = options.value("entity-id", "");
        final List<GraphicalMatrixSpXmlConfig.Inventory> selected = inventory.stream()
            .filter(item -> filter.isEmpty() || filter.equals(item.entityId())).toList();
        if ("json".equals(options.value("format", "text"))) {
            printInventoryJson(selected);
            return;
        }
        System.out.printf("%-32s %-28s %-58s %s%n", "SOURCE", "TYPE", "ENTITY ID", "STATUS");
        for (final GraphicalMatrixSpXmlConfig.Inventory item : selected) {
            System.out.printf("%-32s %-28s %-58s %s%n", truncate(item.sourceId(), 32),
                truncate(item.sourceType(), 28), truncate(item.entityId(), 58), item.status());
            if (item.metadataFile() != null) {
                System.out.println("  metadata_file=" + item.metadataFile());
            }
            if (!item.detail().isBlank()) {
                System.out.println("  detail=" + item.detail());
            }
        }
        if (!filter.isEmpty() && selected.isEmpty()) {
            System.out.println("status=NOT_FOUND entity_id=" + filter);
        }
    }

    private void list(final Options options) throws Exception {
        options.allow(Set.of("format"), Set.of("all"));
        if (options.flag("all")) {
            status(new Options(Map.of("format", options.value("format", "text")), Set.of(), List.of()));
            return;
        }
        final GraphicalMatrixSpRegistry registry = registry();
        if ("json".equals(options.value("format", "text"))) {
            System.out.print(GraphicalMatrixSpRegistry.toJson(registry.entries()));
            return;
        }
        System.out.printf("%-20s %-10s %-58s %-16s %s%n",
            "NAME", "STATUS", "ENTITY ID", "ATTRIBUTES", "MFA");
        for (final GraphicalMatrixSpRegistry.Entry entry : registry.entries()) {
            System.out.printf("%-20s %-10s %-58s %-16s %s%n", entry.name(), entry.status(),
                truncate(entry.entityId(), 58), entry.attributeProfile(), entry.mfaProfile());
        }
    }

    private void next(final Options options) throws Exception {
        options.allow(Set.of(), Set.of("all"));
        if (!options.positionals().isEmpty() && options.positionals().size() != 1) {
            throw new IllegalArgumentException("next accepts at most one SP name");
        }
        if (options.flag("all") && !options.positionals().isEmpty()) {
            throw new IllegalArgumentException("next --all does not accept an SP name");
        }
        if (!config.enabled()) {
            printNext("DISABLED", "SP management is disabled", "Next Steps", List.of(
                nextStep("Enable SP management in the configuration",
                    "sudoedit " + shellQuote(config.configPath().toString()),
                    "Set " + GraphicalMatrixSpManagementConfig.ENABLED + " = true."),
                nextStep("Check the next required operation",
                    cliCommand("next"))));
            return;
        }
        if (!GraphicalMatrixSpXmlConfig.hasManagedProvider(config.metadataProvidersPath())
                || !GraphicalMatrixSpXmlConfig.hasManagedAttributeBlock(config.attributeFilterPath())) {
            printNext("NOT_INITIALIZED",
                "The managed metadata provider or attribute filter is not initialized",
                "Next Steps", List.of(
                    nextStep("Review the planned initialization changes",
                        cliCommand("init"),
                        "This command does not modify any configuration."),
                    nextStep("Apply the reviewed changes",
                        cliCommand("init --apply"),
                        "Run this command only after confirming the output from step 1."),
                    nextStep("Restart Jetty to load the new IdP configuration",
                        "sudo systemctl restart jetty-idp.service",
                        "The metadata provider is not active until Jetty reloads its configuration."),
                    nextStep("Verify the initialization result",
                        cliCommand("status"))));
            return;
        }
        final List<GraphicalMatrixSpXmlConfig.Inventory> inventory = inventoryWithDuplicates();
        if (inventory.stream().anyMatch(item -> Set.of("DUPLICATE_ENTITY_ID", "INVALID_METADATA")
                .contains(item.status()))) {
            printNext("REVIEW_REQUIRED", "Duplicate or invalid metadata was detected",
                "Manual Review Required", List.of(
                    nextStep("Display the detected metadata entries",
                        cliCommand("status")),
                    nextStep("Correct the duplicate or invalid metadata",
                        cliCommand("next"),
                        "Rerun this command after completing the manual correction.")));
            return;
        }
        final GraphicalMatrixSpRegistry registry = registry();
        if (!options.positionals().isEmpty()) {
            final String name = options.positionals().get(0);
            final GraphicalMatrixSpRegistry.Entry entry = registry.entries().stream()
                .filter(item -> item.name().equals(name)).findFirst().orElse(null);
            if (entry == null) {
                printNext("NOT_FOUND", "The specified SP name is not registered: " + name,
                    "Next Steps", List.of(
                        nextStep("Display the registered SP names",
                            cliCommand("list")),
                        nextStep("Retry with the correct SP name or add a new SP", "",
                            "Use next SP_NAME with a listed name, or use add for a new SP.")));
                return;
            }
            printManagedSpNext(entry);
            return;
        }
        final List<GraphicalMatrixSpXmlConfig.Inventory> existing = inventory.stream()
            .filter(item -> "EXISTING_LOCAL".equals(item.status())).toList();
        if (options.flag("all")) {
            printAllNext(existing, registry.entries());
            return;
        }
        if (!existing.isEmpty()) {
            printExistingSpNext(existing.get(0));
            return;
        }
        if (registry.isEmpty()) {
            printNext("INITIALIZED_EMPTY",
                "SP management is initialized, but no managed SP is registered", "Next Steps",
                List.of(
                    nextStep("Allow the metadata and ACS hosts",
                        "sudoedit " + shellQuote(config.configPath().toString()),
                        "Add the approved FQDNs to allowedHosts and allowedAcsHosts."),
                    nextStep("Review the planned SP addition",
                        cliCommand("add --name NAME --entity-id ENTITY_ID --metadata-file FILE"),
                        "This command is a dry-run and does not modify the IdP."),
                    nextStep("Apply the addition after verifying the metadata digest",
                        cliCommand("add --name NAME --entity-id ENTITY_ID --metadata-file FILE "
                            + "--approve-sha256 METADATA_SHA256 --apply"))));
            return;
        }
        printNext("ACTIVE", "All managed SP settings are consistent",
            "No Configuration Changes Required", List.of(
                nextStep("Review the managed SP list", cliCommand("list")),
                nextStep("Run an SSO test", "",
                    "Start the test from each SP protected resource.")));
    }

    private void printManagedSpNext(final GraphicalMatrixSpRegistry.Entry entry) {
        if ("ACTIVE".equals(entry.status())) {
            printNext("ACTIVE", "The managed SP is active and consistent", "Next Steps", List.of(
                nextStep("Verify the managed files and policy",
                    cliCommand("verify " + entry.name())),
                nextStep("Run an SSO test", "",
                    "Start the test from the SP protected resource.")));
            return;
        }
        if ("DISABLED".equals(entry.status())) {
            printNext("DISABLED", "The managed SP is currently disabled", "Next Steps", List.of(
                nextStep("Review the planned enable operation",
                    cliCommand("enable " + entry.name()),
                    "The SP cannot be used for SSO while disabled."),
                nextStep("Apply the reviewed change",
                    cliCommand("enable " + entry.name() + " --apply")),
                nextStep("Verify the enabled SP",
                    cliCommand("verify " + entry.name()))));
            return;
        }
        printNext("REVIEW_REQUIRED", "The managed SP has an unsupported state: " + entry.status(),
            "Manual Review Required", List.of(
                nextStep("Review the managed SP registry", cliCommand("list"))));
    }

    private void printExistingSpNext(final GraphicalMatrixSpXmlConfig.Inventory existing) {
        final String entityId = shellQuote(existing.entityId());
        printNext("EXISTING_SP_DISCOVERED",
            "An existing manually managed SP was detected: " + existing.entityId(),
            "Next Steps", List.of(
                nextStep("Review the existing SP",
                    cliCommand("status --entity-id " + entityId)),
                nextStep("Review the planned adoption",
                    cliCommand("adopt NAME --entity-id " + entityId),
                    "NAME is a new local management name, not the displayed SOURCE/provider ID.",
                    "Use lowercase letters, digits, and hyphens only: [a-z0-9][a-z0-9-]{0,62}.",
                    "Example: local-test-sp."),
                nextStep("Apply the adoption after review",
                    cliCommand("adopt NAME --entity-id " + entityId
                        + " --apply --confirm " + entityId),
                    "Use the same NAME selected in step 2."),
                nextStep("Verify the adopted SP",
                    cliCommand("verify NAME"),
                    "Use the same NAME selected in step 2.")));
    }

    private void printAllNext(final List<GraphicalMatrixSpXmlConfig.Inventory> existing,
            final List<GraphicalMatrixSpRegistry.Entry> entries) {
        if (existing.isEmpty() && entries.isEmpty()) {
            printNext("INITIALIZED_EMPTY",
                "SP management is initialized, but no managed SP is registered", "Next Steps",
                List.of(nextStep("Review the SP addition procedure", "",
                    "Run " + cliCommand("next")
                        + " without --all for the complete add template.")));
            return;
        }
        System.out.println("scope=ALL");
        System.out.println("reason=Guidance for every detected and managed SP follows");
        System.out.println("sp_count=" + (existing.size() + entries.size()));
        for (final GraphicalMatrixSpXmlConfig.Inventory item : existing) {
            System.out.println();
            System.out.println("sp_entity_id=" + item.entityId());
            printExistingSpNext(item);
        }
        for (final GraphicalMatrixSpRegistry.Entry entry : entries) {
            System.out.println();
            System.out.println("sp_name=" + entry.name());
            printManagedSpNext(entry);
        }
    }

    private void initialize(final Options options) throws Exception {
        options.allow(Set.of(), Set.of("apply"));
        final boolean provider = GraphicalMatrixSpXmlConfig.hasManagedProvider(config.metadataProvidersPath());
        final boolean attributes = GraphicalMatrixSpXmlConfig.hasManagedAttributeBlock(config.attributeFilterPath());
        System.out.println("mode=" + mode(options));
        System.out.println("metadata_provider=" + (provider ? "present" : "will_add"));
        System.out.println("attribute_block=" + (attributes ? "present" : "will_add"));
        System.out.println("registry=" + config.registryPath());
        if (!options.apply()) {
            System.out.println("next_command=" + cliCommand("init --apply"));
            return;
        }
        final Snapshot snapshot = snapshot(config.metadataProvidersPath(), config.attributeFilterPath(),
            config.registryPath());
        try {
            prepareDirectories();
            GraphicalMatrixSpXmlConfig.initializeMetadataProvider(config.metadataProvidersPath(), config.idpHome());
            GraphicalMatrixSpXmlConfig.initializeAttributeBlock(config.attributeFilterPath());
            if (!Files.exists(config.registryPath())) {
                GraphicalMatrixSpRegistry.empty().save(config.registryPath());
            }
            securePaths();
            audit("SP_INIT", "-", "-", "OK", "");
            System.out.println("result=APPLY_OK");
            System.out.println("next_command=sudo systemctl restart jetty-idp.service");
            System.out.println("after_restart=" + cliCommand("status"));
        } catch (Exception ex) {
            snapshot.restore();
            audit("SP_INIT", "-", "-", "FAILED", rootMessage(ex));
            throw ex;
        }
    }

    private void add(final Options options) throws Exception {
        options.allow(Set.of("name", "entity-id", "metadata-url", "metadata-file",
            "attribute-profile", "mfa", "cidrs", "approve-sha256"),
            Set.of("apply", "confirm-bypass"));
        requireInitialized();
        final String name = validateName(options.required("name"));
        final String entityId = options.required("entity-id");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        if (oldRegistry.entries().stream().anyMatch(entry -> entry.name().equals(name))) {
            throw new IllegalArgumentException("SP name already exists; use update: " + name);
        }
        if (oldRegistry.findByEntityId(entityId) != null) {
            throw new IllegalArgumentException("entityID is already managed; use update");
        }
        rejectInventoryDuplicate(entityId);
        final GraphicalMatrixSpMetadata.Parsed metadata = metadata(options, entityId);
        final String attributes = options.value("attribute-profile", "none");
        config.attributesFor(attributes);
        final String mfa = options.value("mfa", "inherit");
        final List<String> cidrs = csv(options.value("cidrs", ""));
        GraphicalMatrixSpMfaConfig.validateProfile(mfa, cidrs);
        requireBypassConfirmation(mfa, options);
        printPlan("ADD", name, metadata, attributes, mfa, cidrs, options);
        if (!options.apply()) {
            return;
        }
        requireDigest(options, metadata);
        final Instant now = Instant.now();
        final String file = relativeMetadataFile(entityId, true);
        final GraphicalMatrixSpRegistry.Entry entry = new GraphicalMatrixSpRegistry.Entry(
            name, entityId, "ACTIVE", metadata.source(), metadata.sha256(), file,
            metadata.certificateFingerprints(), metadata.acsUrls(), attributes, mfa, cidrs,
            now.toString(), now.toString(), 0, "", "", "", "", "");
        applyEntry("SP_ADD", oldRegistry, entry, metadata.bytes(), null);
    }

    private void update(final Options options) throws Exception {
        options.allow(Set.of("metadata-url", "metadata-file", "attribute-profile", "mfa", "cidrs",
            "approve-sha256"), Set.of("apply", "confirm-bypass"));
        final String name = onePositional(options, "update NAME");
        requireInitialized();
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        final GraphicalMatrixSpMetadata.Parsed metadata = metadata(options, current.entityId());
        final String attributes = options.value("attribute-profile", current.attributeProfile());
        config.attributesFor(attributes);
        final String mfa = options.value("mfa", current.mfaProfile());
        final List<String> cidrs = options.has("cidrs") ? csv(options.value("cidrs", "")) : current.cidrs();
        GraphicalMatrixSpMfaConfig.validateProfile(mfa, cidrs);
        requireBypassConfirmation(mfa, options);
        printPlan("UPDATE", name, metadata, attributes, mfa, cidrs, options);
        if (!options.apply()) {
            return;
        }
        requireDigest(options, metadata);
        final GraphicalMatrixSpRegistry.Entry updated = current.withMetadata(metadata,
            relativeMetadataFile(current.entityId(), true), attributes, mfa, cidrs, Instant.now());
        applyEntry("SP_UPDATE", oldRegistry, updated, metadata.bytes(), null);
    }

    private void setAttributes(final Options options) throws Exception {
        options.allow(Set.of(), Set.of("apply"));
        if (options.positionals().size() != 2) {
            throw new IllegalArgumentException("usage: set-attributes NAME PROFILE [--apply]");
        }
        final String name = options.positionals().get(0);
        final String profile = options.positionals().get(1);
        config.attributesFor(profile);
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("attribute_profile_current=" + current.attributeProfile());
        System.out.println("attribute_profile_new=" + profile);
        if (!options.apply()) {
            System.out.println("next_command="
                + cliCommand("set-attributes " + name + " " + profile + " --apply"));
            return;
        }
        applyEntry("SP_SET_ATTRIBUTES", oldRegistry,
            current.withPolicies(profile, current.mfaProfile(), current.cidrs(), Instant.now()),
            readMetadata(current), null);
    }

    private void setMfa(final Options options) throws Exception {
        options.allow(Set.of("cidrs"), Set.of("apply", "confirm-bypass"));
        if (options.positionals().size() != 2) {
            throw new IllegalArgumentException("usage: set-mfa NAME PROFILE [--cidrs ...] [--apply]");
        }
        final String name = options.positionals().get(0);
        final String profile = options.positionals().get(1);
        final List<String> cidrs = csv(options.value("cidrs", ""));
        GraphicalMatrixSpMfaConfig.validateProfile(profile, cidrs);
        requireBypassConfirmation(profile, options);
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("mfa_profile_current=" + current.mfaProfile());
        System.out.println("mfa_profile_new=" + profile);
        System.out.println("cidrs=" + String.join(",", cidrs));
        if (!options.apply()) {
            return;
        }
        applyEntry("SP_SET_MFA", oldRegistry,
            current.withPolicies(current.attributeProfile(), profile, cidrs, Instant.now()),
            readMetadata(current), null);
    }

    private void setEnabled(final Options options, final boolean enable) throws Exception {
        options.allow(Set.of(), Set.of("apply"));
        final String name = onePositional(options, (enable ? "enable" : "disable") + " NAME");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        final String expected = enable ? "DISABLED" : "ACTIVE";
        if (!expected.equals(current.status())) {
            throw new IllegalStateException("SP must be " + expected + " before this operation");
        }
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("status_current=" + current.status());
        System.out.println("status_new=" + (enable ? "ACTIVE" : "DISABLED"));
        if (!options.apply()) {
            return;
        }
        final GraphicalMatrixSpRegistry.Entry updated = current.withStatus(
            enable ? "ACTIVE" : "DISABLED", Instant.now());
        final byte[] bytes = readMetadata(current);
        applyEntry(enable ? "SP_ENABLE" : "SP_DISABLE", oldRegistry, updated, bytes, null);
    }

    private void remove(final Options options) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String name = onePositional(options, "remove NAME");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        if (!"DISABLED".equals(current.status())) {
            throw new IllegalStateException("SP must be disabled before remove");
        }
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("entity_id=" + current.entityId());
        System.out.println("action=remove registry, metadata, attribute policy, and MFA policy");
        if (!options.apply()) {
            return;
        }
        if (!name.equals(options.value("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match SP name: " + name);
        }
        final GraphicalMatrixSpRegistry newRegistry = oldRegistry.copy();
        newRegistry.remove(name);
        final GraphicalMatrixSpRegistry.Entry removed = current.withStatus("REMOVED", Instant.now())
            .withRevision(current.currentRevision() + 1, Instant.now());
        final Path active = metadataPath(current, true);
        final Path disabled = metadataPath(current, false);
        final Snapshot snapshot = snapshot(config.registryPath(), config.attributeFilterPath(),
            config.mfaPolicyPath(), active, disabled);
        try {
            Files.deleteIfExists(active);
            Files.deleteIfExists(disabled);
            GraphicalMatrixSpXmlConfig.renderManagedAttributes(config.attributeFilterPath(),
                newRegistry.entries(), config);
            GraphicalMatrixSpMfaConfig.render(config.mfaPolicyPath(), oldRegistry.entries(),
                newRegistry.entries(), null);
            newRegistry.save(config.registryPath());
            saveRevision(removed, null, "REMOVE");
            securePaths();
            reloadAndCheck();
            audit("SP_REMOVE", name, current.entityId(), "OK", "");
            System.out.println("result=APPLY_OK");
        } catch (Exception ex) {
            final Exception failure = restoreAfterFailure(snapshot, ex);
            audit("SP_REMOVE", name, current.entityId(), "FAILED", rootMessage(failure));
            throw failure;
        }
    }

    private void verify(final Options options) throws Exception {
        options.allow(Set.of(), Set.of());
        final String name = onePositional(options, "verify NAME");
        final GraphicalMatrixSpRegistry.Entry entry = registry().get(name);
        final boolean active = "ACTIVE".equals(entry.status());
        final Path canonicalPath = metadataPath(entry, true);
        final Path metadataPath = metadataPath(entry, active);
        final GraphicalMatrixSpMetadata.Parsed metadata =
            GraphicalMatrixSpMetadata.parseExisting(config, metadataPath);
        boolean valid = true;
        valid &= check("entity_id", entry.entityId(), metadata.entityId());
        valid &= check("metadata_sha256", entry.metadataSha256(), metadata.sha256());
        valid &= check("metadata_file", entry.metadataFile(), relative(canonicalPath));
        System.out.println("metadata_location=" + relative(metadataPath));
        System.out.println("attribute_profile=" + entry.attributeProfile());
        System.out.println("mfa_profile=" + entry.mfaProfile());
        System.out.println("status=" + entry.status());
        if (!valid) {
            throw new IllegalStateException("managed SP verification failed");
        }
        System.out.println("result=OK");
    }

    private void checkUpdate(final Options options) throws Exception {
        options.allow(Set.of("metadata-url", "metadata-file"), Set.of());
        final String name = onePositional(options, "check-update NAME");
        final GraphicalMatrixSpRegistry.Entry current = registry().get(name);
        final GraphicalMatrixSpMetadata.Parsed metadata = metadata(options, current.entityId());
        printMetadata(metadata);
        System.out.println("current_sha256=" + current.metadataSha256());
        System.out.println("update_available=" + !current.metadataSha256().equals(metadata.sha256()));
    }

    private void adopt(final Options options) throws Exception {
        options.allow(Set.of("entity-id", "confirm"), Set.of("apply"));
        requireInitialized();
        final String name = validateName(onePositional(options, "adopt NAME --entity-id ENTITY_ID"));
        final String entityId = options.required("entity-id");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        if (oldRegistry.findByEntityId(entityId) != null) {
            throw new IllegalArgumentException("entityID is already managed");
        }
        final List<GraphicalMatrixSpXmlConfig.Inventory> matches = inventoryWithDuplicates().stream()
            .filter(item -> entityId.equals(item.entityId())).toList();
        if (matches.size() != 1 || !"EXISTING_LOCAL".equals(matches.get(0).status())
                || !"FilesystemMetadataProvider".equals(matches.get(0).sourceType())) {
            throw new IllegalArgumentException(
                "adopt requires exactly one EXISTING_LOCAL FilesystemMetadataProvider");
        }
        final GraphicalMatrixSpXmlConfig.Inventory inventory = matches.get(0);
        final GraphicalMatrixSpMetadata.Parsed metadata =
            GraphicalMatrixSpMetadata.parseExisting(config, inventory.metadataFile());
        final String attributeProfile = GraphicalMatrixSpXmlConfig.inferAttributeProfile(
            config.attributeFilterPath(), entityId);
        final String mfaProfile = GraphicalMatrixSpMfaConfig.inferProfile(config.mfaPolicyPath(), entityId);
        final List<String> cidrs = GraphicalMatrixSpMfaConfig.inferCidrs(config.mfaPolicyPath(), entityId);
        final String providerXml = GraphicalMatrixSpXmlConfig.providerXml(
            config.metadataProvidersPath(), inventory.sourceId());
        final String attributeXml = GraphicalMatrixSpXmlConfig.extractLegacyAttributePolicies(
            config.attributeFilterPath(), entityId, false);
        System.out.println("mode=" + mode(options));
        System.out.println("action=ADOPT");
        System.out.println("name=" + name);
        System.out.println("entity_id=" + entityId);
        System.out.println("source_provider=" + inventory.sourceId());
        System.out.println("metadata_sha256=" + metadata.sha256());
        System.out.println("attribute_profile=" + attributeProfile);
        System.out.println("mfa_profile=" + mfaProfile);
        if (!options.apply()) {
            System.out.println("next_command=" + cliCommand("adopt " + name
                + " --entity-id " + shellQuote(entityId) + " --apply --confirm "
                + shellQuote(entityId)));
            return;
        }
        if (!entityId.equals(options.value("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match entityID");
        }
        final Instant now = Instant.now();
        final GraphicalMatrixSpRegistry.Entry entry = new GraphicalMatrixSpRegistry.Entry(
            name, entityId, "ACTIVE", "legacy:" + inventory.metadataFile(), metadata.sha256(),
            relativeMetadataFile(entityId, true), metadata.certificateFingerprints(), metadata.acsUrls(),
            attributeProfile, mfaProfile, cidrs, now.toString(), now.toString(), 0,
            inventory.sourceId(), relative(inventory.metadataFile()), providerXml, attributeXml, mfaProfile);
        final Snapshot snapshot = snapshot(config.metadataProvidersPath(), config.attributeFilterPath(),
            config.mfaPolicyPath(), config.registryPath(), managedMetadataPath(entityId, true),
            inventory.metadataFile());
        try {
            GraphicalMatrixSpXmlConfig.removeProvider(config.metadataProvidersPath(), inventory.sourceId());
            GraphicalMatrixSpXmlConfig.extractLegacyAttributePolicies(config.attributeFilterPath(), entityId, true);
            Files.deleteIfExists(inventory.metadataFile());
            applyEntryInternal("SP_ADOPT", oldRegistry, entry, metadata.bytes(), null);
        } catch (Exception ex) {
            final Exception failure = restoreAfterFailure(snapshot, ex);
            audit("SP_ADOPT", name, entityId, "FAILED", rootMessage(failure));
            throw failure;
        }
    }

    private void history(final Options options) throws Exception {
        options.allow(Set.of(), Set.of());
        final String name = validateName(onePositional(options, "history NAME"));
        final Path directory = config.revisionsDirectory().resolve(name);
        System.out.printf("%-10s %-24s %-12s %-16s %s%n",
            "REVISION", "UPDATED", "STATUS", "ATTRIBUTES", "MFA");
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (final Path revision : revisionDirectories(directory)) {
            final GraphicalMatrixSpRegistry.Entry entry =
                GraphicalMatrixSpRegistry.loadEntry(revision.resolve("record.json"));
            System.out.printf("%-10d %-24s %-12s %-16s %s%n", entry.currentRevision(),
                entry.updatedAt(), entry.status(), entry.attributeProfile(), entry.mfaProfile());
            final Path event = revision.resolve("event.txt");
            if (Files.isRegularFile(event)) {
                System.out.println("  event=" + Files.readString(event).trim());
            }
        }
    }

    private void rollback(final Options options) throws Exception {
        options.allow(Set.of("revision", "confirm"), Set.of("apply"));
        final String name = onePositional(options, "rollback NAME --revision N");
        final int revision = positiveInteger(options.required("revision"), "revision");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        final Path revisionDirectory = revisionDirectory(name, revision);
        final GraphicalMatrixSpRegistry.Entry target =
            GraphicalMatrixSpRegistry.loadEntry(revisionDirectory.resolve("record.json"));
        final byte[] metadata = Files.isRegularFile(revisionDirectory.resolve("metadata.xml"))
            ? Files.readAllBytes(revisionDirectory.resolve("metadata.xml")) : null;
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("entity_id=" + target.entityId());
        System.out.println("revision_current=" + current.currentRevision());
        System.out.println("revision_target=" + revision);
        System.out.println("metadata_sha256=" + target.metadataSha256());
        System.out.println("attribute_profile=" + target.attributeProfile());
        System.out.println("mfa_profile=" + target.mfaProfile());
        if (!options.apply()) {
            return;
        }
        if (!current.entityId().equals(options.value("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match entityID");
        }
        if (!current.entityId().equals(target.entityId()) || !current.name().equals(target.name())) {
            throw new IllegalStateException("revision identity does not match current registry entry");
        }
        final GraphicalMatrixSpRegistry.Entry restored = new GraphicalMatrixSpRegistry.Entry(
            current.name(), current.entityId(), target.status(), target.source(), target.metadataSha256(),
            target.metadataFile(), target.certificateFingerprints(), target.acsUrls(),
            target.attributeProfile(), target.mfaProfile(), target.cidrs(), current.createdAt(),
            Instant.now().toString(), current.currentRevision(), current.legacyProviderId(),
            current.legacyMetadataFile(), current.legacyProviderXml(), current.legacyAttributeXml(),
            current.legacyMfaProfile());
        applyEntry("SP_ROLLBACK", oldRegistry, restored, metadata, "target_revision=" + revision);
    }

    private void restoreLegacy(final Options options) throws Exception {
        options.allow(Set.of("confirm"), Set.of("apply"));
        final String name = onePositional(options, "restore-legacy NAME");
        final GraphicalMatrixSpRegistry oldRegistry = registry();
        final GraphicalMatrixSpRegistry.Entry current = oldRegistry.get(name);
        if (current.legacyProviderId().isBlank() || current.legacyProviderXml().isBlank()
                || current.legacyMetadataFile().isBlank()) {
            throw new IllegalStateException("SP was not adopted from a supported legacy provider");
        }
        System.out.println("mode=" + mode(options));
        System.out.println("name=" + name);
        System.out.println("entity_id=" + current.entityId());
        System.out.println("legacy_provider=" + current.legacyProviderId());
        System.out.println("legacy_metadata_file=" + current.legacyMetadataFile());
        if (!options.apply()) {
            return;
        }
        if (!current.entityId().equals(options.value("confirm", ""))) {
            throw new IllegalArgumentException("--confirm must exactly match entityID");
        }
        final Path originalRevision = revisionDirectory(name, 1).resolve("metadata.xml");
        if (!Files.isRegularFile(originalRevision)) {
            throw new IllegalStateException("legacy-original metadata revision is missing");
        }
        final Path legacyMetadata = config.idpHome().resolve(current.legacyMetadataFile()).normalize();
        final GraphicalMatrixSpRegistry newRegistry = oldRegistry.copy();
        newRegistry.remove(name);
        final Snapshot snapshot = snapshot(config.metadataProvidersPath(), config.attributeFilterPath(),
            config.mfaPolicyPath(), config.registryPath(), metadataPath(current, true),
            metadataPath(current, false), legacyMetadata);
        try {
            Files.createDirectories(legacyMetadata.getParent());
            atomicWrite(legacyMetadata, Files.readAllBytes(originalRevision));
            GraphicalMatrixSpXmlConfig.restoreProvider(config.metadataProvidersPath(),
                current.legacyProviderXml());
            Files.deleteIfExists(metadataPath(current, true));
            Files.deleteIfExists(metadataPath(current, false));
            GraphicalMatrixSpXmlConfig.renderManagedAttributes(config.attributeFilterPath(),
                newRegistry.entries(), config);
            GraphicalMatrixSpXmlConfig.restoreLegacyAttributePolicies(config.attributeFilterPath(),
                current.legacyAttributeXml());
            GraphicalMatrixSpMfaConfig.render(config.mfaPolicyPath(), oldRegistry.entries(),
                newRegistry.entries(), current);
            newRegistry.save(config.registryPath());
            saveRevision(current.withStatus("LEGACY_RESTORED", Instant.now())
                .withRevision(current.currentRevision() + 1, Instant.now()),
                Files.readAllBytes(originalRevision), "RESTORE_LEGACY");
            securePaths();
            reloadAndCheck();
            audit("SP_RESTORE_LEGACY", name, current.entityId(), "OK", "");
            System.out.println("result=APPLY_OK");
        } catch (Exception ex) {
            final Exception failure = restoreAfterFailure(snapshot, ex);
            audit("SP_RESTORE_LEGACY", name, current.entityId(), "FAILED", rootMessage(failure));
            throw failure;
        }
    }

    private void applyEntry(final String event, final GraphicalMatrixSpRegistry oldRegistry,
            final GraphicalMatrixSpRegistry.Entry proposed, final byte[] metadata,
            final String detail) throws Exception {
        final Snapshot snapshot = snapshot(config.registryPath(), config.attributeFilterPath(),
            config.mfaPolicyPath(), managedMetadataPath(proposed.entityId(), true),
            managedMetadataPath(proposed.entityId(), false));
        try {
            applyEntryInternal(event, oldRegistry, proposed, metadata, detail);
        } catch (Exception ex) {
            final Exception failure = restoreAfterFailure(snapshot, ex);
            audit(event, proposed.name(), proposed.entityId(), "FAILED", rootMessage(failure));
            throw failure;
        }
    }

    private void applyEntryInternal(final String event, final GraphicalMatrixSpRegistry oldRegistry,
            final GraphicalMatrixSpRegistry.Entry proposed, final byte[] metadata,
            final String detail) throws Exception {
        prepareDirectories();
        final GraphicalMatrixSpRegistry newRegistry = oldRegistry.copy();
        final int revision = Math.max(proposed.currentRevision(),
            oldRegistry.findByEntityId(proposed.entityId()) == null ? 0
                : oldRegistry.findByEntityId(proposed.entityId()).currentRevision()) + 1;
        final GraphicalMatrixSpRegistry.Entry entry = proposed.withRevision(revision, Instant.now());
        final Path active = managedMetadataPath(entry.entityId(), true);
        final Path disabled = managedMetadataPath(entry.entityId(), false);
        if ("ACTIVE".equals(entry.status())) {
            if (metadata == null) {
                throw new IllegalStateException("active SP revision has no metadata");
            }
            atomicWrite(active, metadata);
            Files.deleteIfExists(disabled);
        } else {
            if (metadata != null) {
                atomicWrite(disabled, metadata);
            }
            Files.deleteIfExists(active);
        }
        newRegistry.put(entry);
        GraphicalMatrixSpXmlConfig.renderManagedAttributes(config.attributeFilterPath(),
            newRegistry.entries(), config);
        GraphicalMatrixSpMfaConfig.render(config.mfaPolicyPath(), oldRegistry.entries(),
            newRegistry.entries(), null);
        newRegistry.save(config.registryPath());
        saveRevision(entry, metadata, event);
        securePaths();
        reloadAndCheck();
        audit(event, entry.name(), entry.entityId(), "OK", detail == null ? "" : detail);
        System.out.println("result=APPLY_OK");
        System.out.println("revision=" + entry.currentRevision());
        System.out.println("next_command=" + cliCommand("verify " + entry.name()));
    }

    private GraphicalMatrixSpMetadata.Parsed metadata(final Options options,
            final String expectedEntityId) throws Exception {
        final boolean url = options.has("metadata-url");
        final boolean file = options.has("metadata-file");
        if (url == file) {
            throw new IllegalArgumentException("specify exactly one of --metadata-url or --metadata-file");
        }
        return url
            ? GraphicalMatrixSpMetadata.fromUrl(config, URI.create(options.required("metadata-url")),
                expectedEntityId)
            : GraphicalMatrixSpMetadata.fromFile(config, Path.of(options.required("metadata-file")),
                expectedEntityId);
    }

    private void requireInitialized() throws Exception {
        if (!GraphicalMatrixSpXmlConfig.hasManagedProvider(config.metadataProvidersPath())
                || !GraphicalMatrixSpXmlConfig.hasManagedAttributeBlock(config.attributeFilterPath())) {
            throw new IllegalStateException("SP management is not initialized; run init and init --apply first");
        }
    }

    private void rejectInventoryDuplicate(final String entityId) throws Exception {
        if (inventoryWithDuplicates().stream().anyMatch(item -> entityId.equals(item.entityId()))) {
            throw new IllegalArgumentException("entityID already exists in IdP metadata; use adopt or update");
        }
    }

    private List<GraphicalMatrixSpXmlConfig.Inventory> inventoryWithDuplicates() throws Exception {
        final List<GraphicalMatrixSpXmlConfig.Inventory> source =
            new ArrayList<>(GraphicalMatrixSpXmlConfig.inventory(config));
        final Map<String, Integer> counts = new HashMap<>();
        source.stream().map(GraphicalMatrixSpXmlConfig.Inventory::entityId)
            .filter(value -> !value.isBlank()).forEach(value -> counts.merge(value, 1, Integer::sum));
        final GraphicalMatrixSpRegistry registry = registry();
        final List<GraphicalMatrixSpXmlConfig.Inventory> out = new ArrayList<>();
        for (final GraphicalMatrixSpXmlConfig.Inventory item : source) {
            String status = item.status();
            if (!item.entityId().isBlank() && counts.getOrDefault(item.entityId(), 0) > 1) {
                status = "DUPLICATE_ENTITY_ID";
            } else if (GraphicalMatrixSpXmlConfig.PROVIDER_ID.equals(item.sourceId())
                    && !"INVALID_METADATA".equals(status)) {
                status = registry.findByEntityId(item.entityId()) == null ? "UNRESOLVED" : "MANAGED";
            }
            out.add(new GraphicalMatrixSpXmlConfig.Inventory(item.sourceId(), item.sourceType(),
                item.entityId(), item.metadataFile(), status, item.detail()));
        }
        return List.copyOf(out);
    }

    private GraphicalMatrixSpRegistry registry() throws IOException {
        return GraphicalMatrixSpRegistry.load(config.registryPath());
    }

    private void prepareDirectories() throws IOException {
        migrateLegacyPrivateDirectory("sp-management-revisions", config.revisionsDirectory());
        migrateLegacyPrivateDirectory("sp-management-backups", config.backupsDirectory());
        Files.createDirectories(config.managedMetadataDirectory());
        Files.createDirectories(config.disabledMetadataDirectory());
        Files.createDirectories(config.revisionsDirectory());
        Files.createDirectories(config.backupsDirectory());
        Files.createDirectories(config.auditLogPath().getParent());
        setPermissions(config.managedMetadataDirectory(), "rwxr-xr-x");
        setPermissions(config.disabledMetadataDirectory(), "rwx------");
        setPermissions(config.revisionsDirectory(), "rwx------");
        setPermissions(config.backupsDirectory(), "rwx------");
    }

    private void migrateLegacyPrivateDirectory(final String name, final Path destination)
            throws IOException {
        final Path legacy = config.idpHome().resolve("conf/graphicalmatrix").resolve(name);
        if (!Files.exists(legacy)) {
            return;
        }
        if (!Files.isDirectory(legacy)) {
            throw new IOException("legacy SP management path is not a directory: " + legacy);
        }
        if (Files.exists(destination)) {
            throw new IOException("both legacy and current SP management directories exist; "
                + "merge them manually before continuing: " + legacy + " and " + destination);
        }
        Files.createDirectories(destination.getParent());
        Files.move(legacy, destination);
    }

    private void reloadAndCheck() throws Exception {
        if (!config.reloadEnabled()) {
            return;
        }
        try {
            runRequired(List.of(config.idpHome().resolve("bin/reload-metadata.sh").toString(),
                "-id", GraphicalMatrixSpXmlConfig.PROVIDER_ID));
        } catch (IOException ex) {
            throw new IOException("metadata reload failed: " + rootMessage(ex)
                + "; if the response targets http://localhost/idp but the IdP does not listen on "
                + "localhost:80, set graphicalmatrix.sp.reload.baseUrl in "
                + config.configPath() + " to the local IdP base URL, for example "
                + "http://127.0.0.1:8080/idp");
        }
        runRequired(List.of(config.idpHome().resolve("bin/reload-service.sh").toString(),
            "-id", "shibboleth.AttributeFilterService"));
        final Path checker = config.idpHome().resolve("bin/graphicalmatrix-plugin-check.sh");
        if (Files.isExecutable(checker)) {
            runRequired(List.of(checker.toString(), "--idp-home", config.idpHome().toString(),
                "--config-only"));
        }
    }

    private static void runRequired(final List<String> command) throws Exception {
        final Path executable = Path.of(command.get(0));
        if (!Files.isExecutable(executable)) {
            throw new IOException("required reload/check command is missing or not executable: " + executable);
        }
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int status = process.waitFor();
        if (status != 0) {
            throw new IOException("command failed (" + status + "): " + executable + ": " + output.trim());
        }
    }

    private void saveRevision(final GraphicalMatrixSpRegistry.Entry entry, final byte[] metadata,
            final String event) throws IOException {
        final Path directory = revisionDirectory(entry.name(), entry.currentRevision());
        Files.createDirectories(directory);
        GraphicalMatrixSpRegistry.saveEntry(directory.resolve("record.json"), entry);
        if (metadata != null) {
            atomicWrite(directory.resolve("metadata.xml"), metadata);
        }
        Files.writeString(directory.resolve("event.txt"), event + "\n", StandardCharsets.UTF_8);
        pruneRevisions(entry.name(), !entry.legacyProviderId().isBlank());
    }

    private void pruneRevisions(final String name, final boolean preserveFirst) throws IOException {
        final Path directory = config.revisionsDirectory().resolve(name);
        final List<Path> revisions = revisionDirectories(directory);
        final Instant cutoff = Instant.now().minus(config.revisionRetentionDays(), ChronoUnit.DAYS);
        final int excess = Math.max(0, revisions.size() - config.maxRevisionsPerSp());
        for (int i = 0; i < revisions.size(); i++) {
            final Path revision = revisions.get(i);
            if (preserveFirst && revision.getFileName().toString().equals("revision-000001")) {
                continue;
            }
            final boolean expired = Files.getLastModifiedTime(revision).toInstant().isBefore(cutoff);
            if (i < excess || expired) {
                deleteRevision(revision);
            }
        }
    }

    private static void deleteRevision(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (final Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(directory);
    }

    private static List<Path> revisionDirectories(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().matches("revision-[0-9]{6}"))
                .sorted().toList();
        }
    }

    private Path revisionDirectory(final String name, final int revision) {
        return config.revisionsDirectory().resolve(validateName(name))
            .resolve(String.format(Locale.ROOT, "revision-%06d", revision));
    }

    private byte[] readMetadata(final GraphicalMatrixSpRegistry.Entry entry) throws IOException {
        final Path active = metadataPath(entry, true);
        final Path disabled = metadataPath(entry, false);
        if (Files.isRegularFile(active)) {
            return Files.readAllBytes(active);
        }
        if (Files.isRegularFile(disabled)) {
            return Files.readAllBytes(disabled);
        }
        throw new IOException("managed metadata file is missing for " + entry.name());
    }

    private Path metadataPath(final GraphicalMatrixSpRegistry.Entry entry, final boolean active) {
        return managedMetadataPath(entry.entityId(), active);
    }

    private Path managedMetadataPath(final String entityId, final boolean active) {
        return (active ? config.managedMetadataDirectory() : config.disabledMetadataDirectory())
            .resolve(GraphicalMatrixSpMetadata.standardFileName(entityId));
    }

    private String relativeMetadataFile(final String entityId, final boolean active) {
        return relative(managedMetadataPath(entityId, active));
    }

    private String relative(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(config.idpHome())) {
            throw new IllegalArgumentException("path is outside IDP_HOME: " + path);
        }
        return config.idpHome().relativize(normalized).toString();
    }

    private void securePaths() {
        setPermissions(config.managedMetadataDirectory(), "rwxr-xr-x");
        setPermissions(config.disabledMetadataDirectory(), "rwx------");
        setPermissions(config.revisionsDirectory(), "rwx------");
        setPermissions(config.backupsDirectory(), "rwx------");
        setPermissions(config.registryPath(), "rw-r-----");
        inheritGroup(config.registryPath(), config.registryPath().getParent());
        try {
            if (Files.isDirectory(config.managedMetadataDirectory())) {
                try (var files = Files.list(config.managedMetadataDirectory())) {
                    files.forEach(path -> setPermissions(path, "rw-r--r--"));
                }
            }
            if (Files.isDirectory(config.disabledMetadataDirectory())) {
                try (var files = Files.list(config.disabledMetadataDirectory())) {
                    files.forEach(path -> setPermissions(path, "rw-------"));
                }
            }
        } catch (IOException ignored) {
            // Permission hardening is best effort on non-POSIX test filesystems.
        }
    }

    private static void setPermissions(final Path path, final String mode) {
        try {
            if (Files.exists(path)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some test filesystems do not expose POSIX permissions.
        }
    }

    private static void inheritGroup(final Path path, final Path parent) {
        try {
            if (!Files.exists(path) || !Files.isDirectory(parent)) {
                return;
            }
            final PosixFileAttributes parentAttributes = Files.readAttributes(parent,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            final PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setGroup(parentAttributes.group());
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some test filesystems do not expose POSIX ownership.
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
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            setPermissions(config.auditLogPath(), "rw-r-----");
        } catch (Exception ex) {
            System.err.println("WARN: unable to write SP management audit log: " + rootMessage(ex));
        }
    }

    private Snapshot snapshot(final Path... paths) throws IOException {
        migrateLegacyPrivateDirectory("sp-management-revisions", config.revisionsDirectory());
        migrateLegacyPrivateDirectory("sp-management-backups", config.backupsDirectory());
        Files.createDirectories(config.backupsDirectory());
        setPermissions(config.backupsDirectory(), "rwx------");
        pruneTransactionBackups();
        final Snapshot snapshot = new Snapshot(config.backupsDirectory(), paths);
        snapshot.persist();
        return snapshot;
    }

    private void pruneTransactionBackups() throws IOException {
        final Path root = config.backupsDirectory();
        if (!Files.isDirectory(root)) {
            return;
        }
        final Instant cutoff = Instant.now().minus(config.backupRetentionDays(), ChronoUnit.DAYS);
        try (var directories = Files.list(root)) {
            for (final Path directory : directories
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("transaction-"))
                    .toList()) {
                if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                    deleteRevision(directory);
                }
            }
        }
    }

    private Exception restoreAfterFailure(final Snapshot snapshot, final Exception operationFailure) {
        try {
            snapshot.restore();
            securePaths();
            reloadAndCheck();
            return operationFailure;
        } catch (Exception rollbackFailure) {
            final IllegalStateException combined = new IllegalStateException(
                "operation failed: " + rootMessage(operationFailure)
                    + "; automatic rollback failed: " + rootMessage(rollbackFailure),
                operationFailure);
            combined.addSuppressed(rollbackFailure);
            return combined;
        }
    }

    private static void atomicWrite(final Path path, final byte[] bytes) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, bytes);
    }

    private static void requireDigest(final Options options,
            final GraphicalMatrixSpMetadata.Parsed metadata) {
        final String approved = options.value("approve-sha256", "").toLowerCase(Locale.ROOT);
        if (!metadata.sha256().equals(approved)) {
            throw new IllegalArgumentException("--approve-sha256 must match displayed metadata_sha256");
        }
    }

    private static void requireBypassConfirmation(final String profile, final Options options) {
        if ("bypass".equals(profile) && !options.flag("confirm-bypass")) {
            throw new IllegalArgumentException("MFA bypass requires --confirm-bypass");
        }
    }

    private static void printPlan(final String action, final String name,
            final GraphicalMatrixSpMetadata.Parsed metadata, final String attributes,
            final String mfa, final List<String> cidrs, final Options options) {
        System.out.println("mode=" + mode(options));
        System.out.println("action=" + action);
        System.out.println("name=" + name);
        printMetadata(metadata);
        System.out.println("attribute_profile=" + attributes);
        System.out.println("mfa_profile=" + mfa);
        System.out.println("cidrs=" + String.join(",", cidrs));
        if (!options.apply()) {
            System.out.println("approval_required=--approve-sha256 " + metadata.sha256());
        }
    }

    private static void printMetadata(final GraphicalMatrixSpMetadata.Parsed metadata) {
        System.out.println("entity_id=" + metadata.entityId());
        System.out.println("metadata_source=" + metadata.source());
        System.out.println("metadata_sha256=" + metadata.sha256());
        for (final String acs : metadata.acsUrls()) {
            System.out.println("acs=" + acs);
        }
        for (final String fingerprint : metadata.certificateFingerprints()) {
            System.out.println("certificate_sha256=" + fingerprint);
        }
        for (final String warning : metadata.warnings()) {
            System.out.println("warning=" + warning);
        }
    }

    private static NextStep nextStep(final String title, final String command,
            final String... notes) {
        return new NextStep(title, List.of(notes), command);
    }

    private static void printNext(final String state, final String reason, final String heading,
            final List<NextStep> steps) {
        System.out.println("state=" + state);
        System.out.println("reason=" + reason);
        System.out.println();
        System.out.println(GUIDANCE_RULE);
        System.out.println(heading);
        System.out.println(GUIDANCE_RULE);
        for (int i = 0; i < steps.size(); i++) {
            final NextStep step = steps.get(i);
            System.out.println();
            System.out.printf(Locale.ROOT, "[%d/%d] %s%n", i + 1, steps.size(), step.title());
            for (final String note : step.notes()) {
                System.out.println("      " + note);
            }
            if (!step.command().isBlank()) {
                System.out.println();
                System.out.println("  " + step.command());
            }
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean check(final String label, final String expected, final String actual) {
        final boolean matches = expected.equals(actual);
        System.out.println(label + "=" + (matches ? "OK" : "MISMATCH")
            + " expected=" + expected + " actual=" + actual);
        return matches;
    }

    private static String mode(final Options options) {
        return options.apply() ? "apply" : "dry-run";
    }

    private String cliCommand(final String arguments) {
        final String executable = shellCommandToken(config.idpHome().resolve(
            "bin/graphicalmatrix-sp.sh").toString());
        return arguments == null || arguments.isBlank()
            ? "sudo " + executable : "sudo " + executable + " " + arguments;
    }

    private static String shellCommandToken(final String value) {
        return value.matches("[A-Za-z0-9_./:+-]+") ? value : shellQuote(value);
    }

    private static String onePositional(final Options options, final String usage) {
        if (options.positionals().size() != 1) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return options.positionals().get(0);
    }

    private static String validateName(final String name) {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("SP name must match [a-z0-9][a-z0-9-]{0,62}");
        }
        return name;
    }

    private static int positiveInteger(final String value, final String name) {
        try {
            final int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be an integer", ex);
        }
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

    private static String truncate(final String value, final int length) {
        return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "…";
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
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void printInventoryJson(final List<GraphicalMatrixSpXmlConfig.Inventory> values) {
        System.out.println("[");
        for (int i = 0; i < values.size(); i++) {
            final var item = values.get(i);
            System.out.print("  {\"source\":\"" + json(item.sourceId()) + "\",\"type\":\""
                + json(item.sourceType()) + "\",\"entityId\":\"" + json(item.entityId())
                + "\",\"metadataFile\":\"" + json(item.metadataFile() == null
                    ? "" : item.metadataFile().toString())
                + "\",\"status\":\"" + json(item.status()) + "\",\"detail\":\""
                + json(item.detail()) + "\"}");
            System.out.println(i + 1 < values.size() ? "," : "");
        }
        System.out.println("]");
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void usage() {
        System.err.println("Usage: GraphicalMatrixSpManagementTool IDP_HOME COMMAND [OPTIONS]");
        System.err.println("Commands: status, list, next, init, add, update, set-attributes, set-mfa,");
        System.err.println("          disable, enable, remove, verify, check-update, adopt, history,");
        System.err.println("          rollback, restore-legacy");
    }

    private record Options(Map<String, String> values, Set<String> flags, List<String> positionals) {
        static Options parse(final String[] args) {
            final Map<String, String> values = new LinkedHashMap<>();
            final Set<String> flags = new LinkedHashSet<>();
            final List<String> positionals = new ArrayList<>();
            final Set<String> booleanOptions = Set.of("apply", "all", "confirm-bypass");
            for (int i = 0; i < args.length; i++) {
                final String argument = args[i];
                if (!argument.startsWith("--")) {
                    positionals.add(argument);
                    continue;
                }
                final String key = argument.substring(2);
                if (!key.matches("[a-z][a-z0-9-]*")) {
                    throw new IllegalArgumentException("invalid option: " + argument);
                }
                if (booleanOptions.contains(key)) {
                    if (!flags.add(key)) {
                        throw new IllegalArgumentException("duplicate option: " + argument);
                    }
                } else {
                    if (++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException("option requires a value: " + argument);
                    }
                    if (values.putIfAbsent(key, args[i]) != null) {
                        throw new IllegalArgumentException("duplicate option: " + argument);
                    }
                }
            }
            return new Options(Map.copyOf(values), Set.copyOf(flags), List.copyOf(positionals));
        }

        boolean apply() {
            return flag("apply");
        }

        boolean flag(final String key) {
            return flags.contains(key);
        }

        boolean has(final String key) {
            return values.containsKey(key);
        }

        String value(final String key, final String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        String required(final String key) {
            final String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing required option: --" + key);
            }
            return value;
        }

        void allow(final Set<String> allowedValues, final Set<String> allowedFlags) {
            for (final String key : values.keySet()) {
                if (!allowedValues.contains(key)) {
                    throw new IllegalArgumentException("unsupported option for command: --" + key);
                }
            }
            for (final String key : flags) {
                if (!allowedFlags.contains(key)) {
                    throw new IllegalArgumentException("unsupported flag for command: --" + key);
                }
            }
        }
    }

    private static final class Snapshot {
        private final Path backupRoot;
        private final Map<Path, byte[]> originals = new LinkedHashMap<>();
        private final Set<Path> missing = new LinkedHashSet<>();

        Snapshot(final Path root, final Path... paths) throws IOException {
            Files.createDirectories(root);
            backupRoot = Files.createTempDirectory(root, "transaction-");
            for (final Path path : paths) {
                final Path normalized = path.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    originals.put(normalized, Files.readAllBytes(normalized));
                } else {
                    missing.add(normalized);
                }
            }
        }

        void persist() throws IOException {
            Files.createDirectories(backupRoot);
            int index = 0;
            for (final Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                final String name = String.format(Locale.ROOT, "%02d-%s", ++index,
                    entry.getKey().getFileName());
                Files.write(backupRoot.resolve(name), entry.getValue());
            }
        }

        void restore() throws IOException {
            IOException failure = null;
            for (final Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                try {
                    atomicWrite(entry.getKey(), entry.getValue());
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
