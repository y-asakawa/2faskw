/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicalMatrixSpManagementToolTest {
    private static final String ENTITY_ID = "https://sp.example.org/shibboleth";

    @TempDir
    Path temporary;

    @Test
    void rejectsProfileCreationWhenAnAttributeIsNotCurrentlySamlMapped() throws Exception {
        prepareIdp();
        invoke("init", "--apply");

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> new GraphicalMatrixSpGovernanceTool(
                GraphicalMatrixSpManagementConfig.load(temporary.toString())).execute("attributes",
                    new String[] {"profile", "create", "unmapped-business",
                        "--attributes", "businessCategory"}));

        assertTrue(exception.getMessage().contains("release-approved and SAML-mapped"));
    }

    @Test
    void addsAnLdapResolverAttributeWithDryRunAndApplyGuidance() throws Exception {
        prepareIdp();
        final Path resolver = temporary.resolve("conf/attribute-resolver.xml");
        final String before = Files.readString(resolver);

        final String dryRun = invokeOutput("attributes", "resolver", "add",
            "employeeType");
        assertTrue(dryRun.contains("mode=dry-run"));
        assertTrue(dryRun.contains("data_connector=localTestLdap"));
        assertTrue(dryRun.contains("--apply --confirm 'employeeType'"));
        assertEquals(before, Files.readString(resolver));

        final String applied = invokeOutput("attributes", "resolver", "add",
            "employeeType", "--apply", "--confirm", "employeeType");
        assertTrue(applied.contains("result=APPLY_OK"));
        assertTrue(applied.contains("next_build=sudo "));
        assertTrue(applied.contains("attributes discover --sp SP_NAME --user USER"));
        assertTrue(Files.readString(resolver).contains("id=\"employeeType\""));

        final String repeated = invokeOutput("attributes", "resolver", "add",
            "employeeType");
        assertTrue(repeated.contains("reason=ALREADY_CONFIGURED"));

        final IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
            () -> new GraphicalMatrixSpGovernanceTool(
                GraphicalMatrixSpManagementConfig.load(temporary.toString())).execute(
                    "attributes", new String[] {"resolver", "add", "safeAlias",
                        "--source-attribute", "userPassword"}));
        assertTrue(blocked.getMessage().contains("blocked LDAP source attribute"));
    }

    @Test
    void initializesAnLdapResolverConnectorWithDryRunAndApplyGuidance() throws Exception {
        prepareIdp();
        final Path resolver = temporary.resolve("conf/attribute-resolver.xml");
        Files.writeString(resolver, Files.readString(resolver).replaceAll(
            "(?s)\\s*<DataConnector id=\"localTestLdap\".*?</DataConnector>", ""));
        final String before = Files.readString(resolver);

        final String dryRun = invokeOutput("attributes", "resolver", "init");
        assertTrue(dryRun.contains("mode=dry-run"));
        assertTrue(dryRun.contains("data_connector=graphicalmatrixLdap"));
        assertTrue(dryRun.contains("--apply --confirm 'graphicalmatrixLdap'"));
        assertEquals(before, Files.readString(resolver));

        final String applied = invokeOutput("attributes", "resolver", "init",
            "--data-connector", "graphicalmatrixLdap", "--apply", "--confirm",
            "graphicalmatrixLdap");
        assertTrue(applied.contains("result=APPLY_OK"));
        assertTrue(applied.contains("attributes resolver add ATTRIBUTE"));
        assertTrue(Files.readString(resolver).contains("id=\"graphicalmatrixLdap\""));

        final String repeated = invokeOutput("attributes", "resolver", "init");
        assertTrue(repeated.contains("reason=LDAP_DATA_CONNECTOR_ALREADY_CONFIGURED"));
    }

    @Test
    void nextPrintsStateSpecificNumberedGuidance() throws Exception {
        prepareIdp();
        final Path managementConfig = temporary.resolve(
            "conf/graphicalmatrix/sp-management.properties");
        final String enabledConfig = Files.readString(managementConfig);
        Files.writeString(managementConfig, enabledConfig.replace(
            "graphicalmatrix.sp.management.enabled = true",
            "graphicalmatrix.sp.management.enabled = false"));

        final String disabledManagement = invokeOutput("next");
        assertTrue(disabledManagement.contains("state=DISABLED"));
        assertTrue(disabledManagement.contains("[1/2] Enable SP management"));
        assertTrue(disabledManagement.contains("[2/2] Check the next required operation"));

        Files.writeString(managementConfig, enabledConfig);
        final String notInitialized = invokeOutput("next");
        assertTrue(notInitialized.contains("state=NOT_INITIALIZED"));
        assertTrue(notInitialized.contains("[1/4] Review the planned initialization changes"));
        assertTrue(notInitialized.contains(cliCommand("init --apply")));
        assertTrue(notInitialized.contains("[3/4] Restart Jetty to load the new IdP configuration"));

        invoke("init", "--apply");
        final String empty = invokeOutput("next");
        assertTrue(empty.contains("state=INITIALIZED_EMPTY"));
        assertTrue(empty.contains("[1/3] Allow the metadata and ACS hosts"));
        assertTrue(empty.contains("--approve-sha256 METADATA_SHA256 --apply"));

        final Path metadataFile = Files.writeString(temporary.resolve("sp.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final String digest = GraphicalMatrixSpMetadata.fromFile(
            config, metadataFile, ENTITY_ID).sha256();
        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "uid",
            "--mfa", "force", "--approve-sha256", digest, "--apply");

        final String active = invokeOutput("next", "library");
        assertTrue(active.contains("state=ACCESS_NOT_INITIALIZED"));
        assertTrue(active.contains(cliCommand("access init")));
        assertTrue(active.contains(cliCommand("access init --apply")));

        final String globalActive = invokeOutput("next");
        assertTrue(globalActive.contains("state=ACCESS_NOT_INITIALIZED"));
        assertTrue(globalActive.contains(cliCommand("access init")));

        final String all = invokeOutput("next", "--all");
        assertTrue(all.contains("scope=ALL"));
        assertTrue(all.contains("sp_count=1"));
        assertTrue(all.contains("sp_name=library"));
        assertTrue(all.contains("state=ACCESS_NOT_INITIALIZED"));
        assertTrue(all.contains(cliCommand("access init")));

        invoke("disable", "library", "--apply");
        final String disabledSp = invokeOutput("next", "library");
        assertTrue(disabledSp.contains("state=DISABLED"));
        assertTrue(disabledSp.contains("[1/3] Review the planned enable operation"));
        assertTrue(disabledSp.contains(cliCommand("enable library --apply")));
        assertFalse(disabledSp.contains("Start the test from the SP protected resource."));

        final String notFound = invokeOutput("next", "missing-sp");
        assertTrue(notFound.contains("state=NOT_FOUND"));
        assertTrue(notFound.contains(cliCommand("list")));
    }

    @Test
    void nextGuidesAdoptionOfAnExistingManualSp() throws Exception {
        prepareIdp();
        final Path legacyMetadata = Files.writeString(temporary.resolve("metadata/legacy.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final Path providers = temporary.resolve("conf/metadata-providers.xml");
        Files.writeString(providers, Files.readString(providers).replace(
            "</MetadataProvider>",
            "  <MetadataProvider id=\"LegacySp\" xsi:type=\"FilesystemMetadataProvider\" "
                + "metadataFile=\"%{idp.home}/metadata/legacy.xml\"/>\n"
                + "</MetadataProvider>"));

        invoke("init", "--apply");
        final String existing = invokeOutput("next");
        final String status = invokeOutput("status", "--entity-id", ENTITY_ID);

        assertTrue(Files.isRegularFile(legacyMetadata));
        assertTrue(status.contains("metadata_file=" + legacyMetadata));
        assertTrue(invokeOutput("status", "--entity-id", ENTITY_ID, "--format", "json")
            .contains("\"metadataFile\":\"" + legacyMetadata + "\""));
        assertTrue(existing.contains("state=EXISTING_SP_DISCOVERED"));
        assertTrue(existing.contains("[1/4] Review the existing SP"));
        assertTrue(existing.contains("[2/4] Review the planned adoption"));
        assertTrue(existing.contains("[3/4] Apply the adoption after review"));
        assertTrue(existing.contains("[4/4] Verify the adopted SP"));
        assertTrue(existing.contains("not the displayed SOURCE/provider ID"));
        assertTrue(existing.contains("Example: local-test-sp."));
        assertTrue(existing.contains("Use the same NAME selected in step 2."));
    }

    @Test
    void nextRequiresManualReviewForInvalidMetadata() throws Exception {
        prepareIdp();
        invoke("init", "--apply");
        Files.writeString(temporary.resolve("metadata/2faskw-managed-sp/invalid.xml"),
            "not XML", StandardCharsets.UTF_8);

        final String review = invokeOutput("next");

        assertTrue(review.contains("state=REVIEW_REQUIRED"));
        assertTrue(review.contains("Manual Review Required"));
        assertTrue(review.contains("[1/2] Display the detected metadata entries"));
        assertTrue(review.contains("[2/2] Correct the duplicate or invalid metadata"));
    }

    @Test
    void migratesPrivateStateOutsideTheAutoScannedConfigurationTree() throws Exception {
        prepareIdp();
        final Path legacyRevisions = temporary.resolve(
            "conf/graphicalmatrix/sp-management-revisions");
        final Path legacyBackups = temporary.resolve(
            "conf/graphicalmatrix/sp-management-backups");
        Files.createDirectories(legacyRevisions);
        Files.createDirectories(legacyBackups);
        Files.writeString(legacyRevisions.resolve("revision.txt"), "revision");
        Files.writeString(legacyBackups.resolve("backup.txt"), "backup");

        invoke("init", "--apply");

        assertFalse(Files.exists(legacyRevisions));
        assertFalse(Files.exists(legacyBackups));
        assertTrue(Files.isRegularFile(temporary.resolve(
            "credentials/graphicalmatrix/sp-management-revisions/revision.txt")));
        assertTrue(Files.isRegularFile(temporary.resolve(
            "credentials/graphicalmatrix/sp-management-backups/backup.txt")));
    }

    @Test
    void migratesInvalidLegacyMetadataProviderId() throws Exception {
        prepareIdp();
        final Path providers = temporary.resolve("conf/metadata-providers.xml");
        final String source = Files.readString(providers).replace(
            "</MetadataProvider>",
            "  <MetadataProvider id=\"" + GraphicalMatrixSpXmlConfig.LEGACY_PROVIDER_ID + "\" "
                + "xsi:type=\"LocalDynamicMetadataProvider\" "
                + "sourceDirectory=\"%{idp.home}/metadata/2faskw-managed-sp\"/>\n"
                + "</MetadataProvider>");
        Files.writeString(providers, source, StandardCharsets.UTF_8);

        invoke("init", "--apply");

        final String migrated = Files.readString(providers);
        assertTrue(migrated.contains("id=\"" + GraphicalMatrixSpXmlConfig.PROVIDER_ID + "\""));
        assertFalse(migrated.contains(GraphicalMatrixSpXmlConfig.LEGACY_PROVIDER_ID));
    }

    @Test
    void initializesAddsVerifiesDisablesAndEnables() throws Exception {
        prepareIdp();
        invoke("init", "--apply");
        assertTrue(Files.readString(temporary.resolve("conf/metadata-providers.xml"))
            .contains(GraphicalMatrixSpXmlConfig.PROVIDER_ID));
        final Path registryPath = temporary.resolve(
            "conf/graphicalmatrix/sp-management-registry.json");
        final PosixFileAttributes registryAttributes = Files.readAttributes(registryPath,
            PosixFileAttributes.class);
        final PosixFileAttributes configDirectoryAttributes = Files.readAttributes(
            registryPath.getParent(), PosixFileAttributes.class);
        assertEquals(configDirectoryAttributes.group(), registryAttributes.group());
        assertEquals(PosixFilePermissions.fromString("rw-r-----"),
            registryAttributes.permissions());

        final Path metadataFile = Files.writeString(temporary.resolve("sp.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final String digest = GraphicalMatrixSpMetadata.fromFile(config, metadataFile, ENTITY_ID).sha256();

        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "uid",
            "--mfa", "force");
        assertTrue(GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).isEmpty());

        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "uid",
            "--mfa", "force", "--approve-sha256", digest, "--apply");
        GraphicalMatrixSpRegistry.Entry entry = GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).get("library");
        assertEquals("ACTIVE", entry.status());
        assertEquals(1, entry.currentRevision());
        assertTrue(Files.readString(temporary.resolve("conf/attribute-filter.xml")).contains(ENTITY_ID));
        assertTrue(Files.readString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"))
            .contains("graphicalmatrix.mfa.forceSPs = " + ENTITY_ID));

        invoke("verify", "library");
        invoke("disable", "library", "--apply");
        entry = GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).get("library");
        assertEquals("DISABLED", entry.status());
        assertFalse(Files.readString(temporary.resolve("conf/attribute-filter.xml")).contains(ENTITY_ID));
        invoke("verify", "library");

        invoke("enable", "library", "--apply");
        entry = GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).get("library");
        assertEquals("ACTIVE", entry.status());
        assertEquals(3, entry.currentRevision());
    }

    @Test
    void rollsBackRemovesAndPreservesUnmanagedMfaPolicy() throws Exception {
        prepareIdp();
        final String existingEntity = "https://existing.example.org/shibboleth";
        Files.writeString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"), """
            graphicalmatrix.mfa.default = require
            graphicalmatrix.mfa.forceSPs = %s
            graphicalmatrix.mfa.bypassSPs =
            graphicalmatrix.mfa.bypassSpCidrs =
            graphicalmatrix.mfa.requiredSPs =
            """.formatted(existingEntity), StandardCharsets.UTF_8);
        invoke("init", "--apply");

        final Path metadataFile = Files.writeString(temporary.resolve("sp.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final String digest = GraphicalMatrixSpMetadata.fromFile(config, metadataFile, ENTITY_ID).sha256();
        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "uid",
            "--mfa", "force", "--approve-sha256", digest, "--apply");
        invoke("set-attributes", "library", "uid-mail", "--apply");
        invoke("rollback", "library", "--revision", "1", "--confirm", ENTITY_ID, "--apply");

        GraphicalMatrixSpRegistry.Entry entry = GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).get("library");
        assertEquals("uid", entry.attributeProfile());
        assertTrue(Files.readString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"))
            .contains(existingEntity));

        invoke("disable", "library", "--apply");
        invoke("remove", "library", "--confirm", "library", "--apply");
        assertTrue(GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).isEmpty());
        assertTrue(Files.readString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"))
            .contains("graphicalmatrix.mfa.forceSPs = " + existingEntity));
    }

    @Test
    void configuresAndRollsBackAnAttributeAccessPolicy() throws Exception {
        prepareIdp();
        invoke("init", "--apply");
        final Path metadataFile = Files.writeString(temporary.resolve("sp.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final String digest = GraphicalMatrixSpMetadata.fromFile(
            config, metadataFile, ENTITY_ID).sha256();
        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "uid",
            "--mfa", "force", "--approve-sha256", digest, "--apply");

        invoke("attributes", "approve", "businessCategory", "--usage", "access",
            "--classification", "internal", "--purpose", "SP authorization test",
            "--apply", "--confirm", "businessCategory");
        invoke("access", "set", "library", "--allow", "businessCategory=AA",
            "--apply", "--confirm", ENTITY_ID);
        GraphicalMatrixSpRegistry.Entry entry = GraphicalMatrixSpRegistry.load(
            config.registryPath()).get("library");
        assertTrue(entry.accessPolicyEnabled());
        assertEquals(1, entry.accessPolicyRevision());
        assertTrue(Files.isRegularFile(config.revisionsDirectory().resolve(
            "library/revision-000002/metadata.xml")));
        assertTrue(Files.isRegularFile(config.revisionsDirectory().resolve(
            "library/revision-000002/access-policy.json")));

        invoke("access", "disable", "library", "--apply", "--confirm", ENTITY_ID);
        invoke("rollback", "library", "--revision", "2", "--apply", "--confirm", ENTITY_ID);

        entry = GraphicalMatrixSpRegistry.load(config.registryPath()).get("library");
        final GraphicalMatrixSpAccessPolicy.Policy restored =
            GraphicalMatrixSpAccessPolicy.load(config.accessPolicyPath()).find("library");
        assertTrue(entry.accessPolicyEnabled());
        assertEquals(1, entry.accessPolicyRevision());
        assertTrue(restored.enabled());
        assertEquals(1, restored.revision());
        assertEquals(GraphicalMatrixSpAccessEvaluator.Result.ALLOW,
            new GraphicalMatrixSpAccessEvaluator().evaluate(restored, ENTITY_ID,
                java.util.Map.of("businessCategory", java.util.List.of("AA"))).result());
    }

    @Test
    void listsLegacyProfilesAndRecordsManagedProfileRevisionOnAdd() throws Exception {
        prepareIdp();
        final Path properties = temporary.resolve(
            "conf/graphicalmatrix/sp-management.properties");
        Files.writeString(properties, Files.readString(properties)
            + "graphicalmatrix.sp.attributeProfile.legacy-business = uid,businessCategory\n");
        invoke("init", "--apply");

        final String profiles = invokeOutput("attributes", "profile", "list");
        assertTrue(profiles.contains("legacy-business"));
        assertTrue(profiles.contains("LEGACY_UNREVIEWED"));
        assertTrue(invokeOutput("attributes", "profile", "show", "legacy-business")
            .contains("status=LEGACY_UNREVIEWED"));
        final String profilesJson = invokeOutput(
            "attributes", "profile", "list", "--format", "json");
        final java.util.List<?> profileRows =
            (java.util.List<?>) GraphicalMatrixJson.parse(profilesJson);
        assertTrue(profileRows.stream().map(item -> {
            try {
                return GraphicalMatrixJson.asObject(item, "profile").get("name");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }).anyMatch("uid"::equals));
        assertTrue(profileRows.stream().map(item -> {
            try {
                return GraphicalMatrixJson.asObject(item, "profile").get("name");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }).anyMatch("legacy-business"::equals));

        invoke("attributes", "profile", "create", "managed-uid", "--attributes", "uid",
            "--description", "Managed uid", "--apply", "--confirm", "managed-uid");
        final Path metadataFile = Files.writeString(temporary.resolve("sp.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        final GraphicalMatrixSpManagementConfig config =
            GraphicalMatrixSpManagementConfig.load(temporary.toString());
        final String digest = GraphicalMatrixSpMetadata.fromFile(
            config, metadataFile, ENTITY_ID).sha256();
        invoke("add", "--name", "library", "--entity-id", ENTITY_ID,
            "--metadata-file", metadataFile.toString(), "--attribute-profile", "managed-uid",
            "--mfa", "force", "--approve-sha256", digest, "--apply");

        final GraphicalMatrixSpRegistry.Entry entry =
            GraphicalMatrixSpRegistry.load(config.registryPath()).get("library");
        assertEquals("managed-uid", entry.attributeProfile());
        assertEquals(1, entry.attributeProfileRevision());
    }

    @Test
    void adoptsAndRestoresLegacyFilesystemProvider() throws Exception {
        prepareIdp();
        final Path legacyMetadata = Files.writeString(temporary.resolve("metadata/legacy.xml"),
            GraphicalMatrixSpMetadataTest.metadata(ENTITY_ID, "https://sp.example.org/acs"));
        Files.writeString(temporary.resolve("conf/metadata-providers.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="ShibbolethMetadata" xsi:type="ChainingMetadataProvider">
              <MetadataProvider id="LegacySp" xsi:type="FilesystemMetadataProvider"
                  metadataFile="%{idp.home}/metadata/legacy.xml"/>
            </MetadataProvider>
            """, StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/attribute-filter.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <AttributeFilterPolicyGroup xmlns="urn:mace:shibboleth:2.0:afp"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <AttributeFilterPolicy id="legacyPolicy">
                <PolicyRequirementRule xsi:type="Requester" value="%s"/>
                <AttributeRule attributeID="uid"><PermitValueRule xsi:type="ANY"/></AttributeRule>
              </AttributeFilterPolicy>
            </AttributeFilterPolicyGroup>
            """.formatted(ENTITY_ID), StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"), """
            graphicalmatrix.mfa.default = require
            graphicalmatrix.mfa.forceSPs = %s
            graphicalmatrix.mfa.bypassSPs =
            graphicalmatrix.mfa.bypassSpCidrs =
            graphicalmatrix.mfa.requiredSPs =
            """.formatted(ENTITY_ID), StandardCharsets.UTF_8);

        invoke("init", "--apply");
        invoke("adopt", "legacy", "--entity-id", ENTITY_ID, "--confirm", ENTITY_ID, "--apply");
        assertFalse(Files.readString(temporary.resolve("conf/metadata-providers.xml"))
            .contains("id=\"LegacySp\""));
        assertFalse(Files.exists(legacyMetadata));

        invoke("restore-legacy", "legacy", "--confirm", ENTITY_ID, "--apply");
        assertTrue(Files.readString(temporary.resolve("conf/metadata-providers.xml"))
            .contains("id=\"LegacySp\""));
        assertTrue(Files.isRegularFile(legacyMetadata));
        assertTrue(Files.readString(temporary.resolve("conf/attribute-filter.xml")).contains("legacyPolicy"));
        assertTrue(GraphicalMatrixSpRegistry.load(
            temporary.resolve("conf/graphicalmatrix/sp-management-registry.json")).isEmpty());
    }

    private void prepareIdp() throws Exception {
        Files.createDirectories(temporary.resolve("conf/graphicalmatrix"));
        Files.createDirectories(temporary.resolve("metadata"));
        Files.createDirectories(temporary.resolve("logs"));
        final String runtimeGroup = Files.readAttributes(temporary,
            PosixFileAttributes.class).group().getName();
        Files.writeString(temporary.resolve("conf/graphicalmatrix/sp-management.properties"), """
            graphicalmatrix.sp.management.enabled = true
            graphicalmatrix.sp.metadata.allowedAcsHosts = sp.example.org
            graphicalmatrix.sp.reload.enabled = false
            graphicalmatrix.sp.runtimeGroup = %s
            """.formatted(runtimeGroup), StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/metadata-providers.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="ShibbolethMetadata" xsi:type="ChainingMetadataProvider">
            </MetadataProvider>
            """, StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/attribute-filter.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <AttributeFilterPolicyGroup xmlns="urn:mace:shibboleth:2.0:afp"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            </AttributeFilterPolicyGroup>
            """, StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/graphicalmatrix/mfa-policy.properties"), """
            graphicalmatrix.mfa.default = require
            graphicalmatrix.mfa.forceSPs =
            graphicalmatrix.mfa.bypassSPs =
            graphicalmatrix.mfa.bypassSpCidrs =
            graphicalmatrix.mfa.requiredSPs =
            graphicalmatrix.mfa.bypassIPs =
            graphicalmatrix.mfa.bypassCIDRs =
            graphicalmatrix.mfa.useForwardedFor = false
            """, StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("conf/attribute-resolver.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <AttributeResolver xmlns="urn:mace:shibboleth:2.0:resolver"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <AttributeDefinition id="uid" xsi:type="Simple">
                <AttributeEncoder xsi:type="SAML2String" name="urn:oid:0.9.2342.19200300.100.1.1"/>
              </AttributeDefinition>
              <AttributeDefinition id="mail" xsi:type="Simple">
                <AttributeEncoder xsi:type="SAML2String" name="urn:oid:0.9.2342.19200300.100.1.3"/>
              </AttributeDefinition>
              <AttributeDefinition id="businessCategory" xsi:type="Simple"/>
              <DataConnector id="localTestLdap" xsi:type="LDAPDirectory">
                <ReturnAttributes>uid mail businessCategory</ReturnAttributes>
              </DataConnector>
            </AttributeResolver>
            """, StandardCharsets.UTF_8);
    }

    private void invoke(final String... command) {
        final String[] args = new String[command.length + 1];
        args[0] = temporary.toString();
        System.arraycopy(command, 0, args, 1, command.length);
        GraphicalMatrixSpManagementTool.main(args);
    }

    private String invokeOutput(final String... command) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            invoke(command);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String cliCommand(final String arguments) {
        return "sudo " + temporary.resolve("bin/graphicalmatrix-sp.sh") + " " + arguments;
    }
}
