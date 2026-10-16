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

package io.github.yasakawa.faskw.spimport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedReleaseMigrationTest {
    @TempDir Path temp;
    private ManagedMigrationTest test;

    private void fixture() throws Exception {
        test = new ManagedMigrationTest(); test.temp = temp; test.fixture(false, false);
        common(test.source, "schacHomeOrganization");
        test.analysis = ManagedFixture.analyze(test.source);
        assertFalse(test.analysis.held(), test.analysis.candidates.getFirst().diagnostics.toString());
    }

    static void common(Path root, String attribute) throws Exception {
        Path path = root.resolve("conf/attribute-filter.xml");
        ManagedFixture.text(root, "conf/attribute-filter.xml", Files.readString(path).replace("</AttributeFilterPolicyGroup>",
            "<AttributeFilterPolicy id=\"common-" + attribute + "\"><PolicyRequirementRule xsi:type=\"ANY\"/>"
                + "<AttributeRule attributeID=\"" + attribute + "\" permitAny=\"true\"/></AttributeFilterPolicy></AttributeFilterPolicyGroup>"));
    }

    private void readyTarget() throws Exception {
        common(test.target, "schacHomeOrganization");
        var catalog = ManagedData.json(test.target, ManagedData.DIR + "attribute-catalog.json", 1024 * 1024);
        var attributes = new ArrayList<Object>(StrictJson.array(catalog, "attributes"));
        attributes.add(ManagedFixture.attribute("schacHomeOrganization", "candidate")); catalog.put("attributes", attributes);
        ManagedFixture.json(test.target, ManagedData.DIR + "attribute-catalog.json", catalog);
    }

    @Test void matchingCommonReleasePreservesProfileAndDoesNotApproveOrCopyCommonPolicy() throws Exception {
        fixture(); readyTarget();
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        assertEquals(0, test.migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals("uid-mail", ManagedData.registry(test.target).getFirst().profile());
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("schacHomeOrganization")));
        assertEquals("candidate", ManagedData.catalog(test.target).attributes().get("schacHomeOrganization").governance());
        String filter = Files.readString(test.target.resolve("conf/attribute-filter.xml"));
        assertTrue(filter.contains("id=\"common-schacHomeOrganization\""));
        var actual = ManagedFixture.analyze(test.target).candidates.getFirst();
        assertFalse(actual.blocked(), actual.diagnostics.toString());
        assertEquals(Set.of("uid", "mail", "schacHomeOrganization"), actual.attributes);
    }

    @Test void missingTargetCommonReleaseStopsBeforeAnyWriteAndExplainsMissingAttribute() throws Exception {
        fixture();
        var ex = assertThrows(ImportModel.ImportException.class, () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_RELEASE_DIFFER"));
        assertTrue(ex.getMessage().contains("missing=[schacHomeOrganization]"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
        assertTrue(Files.readString(test.run.resolve("additional-release-check.txt")).contains("missing=[schacHomeOrganization]"));
    }

    @Test void unexpectedTargetReleaseAlsoStops() throws Exception {
        fixture(); readyTarget(); common(test.target, "cn");
        var ex = assertThrows(ImportModel.ImportException.class, () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("unexpected=[cn]"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void unknownTargetConditionIsNotTreatedAsNoRelease() throws Exception {
        fixture(); readyTarget();
        Path path = test.target.resolve("conf/attribute-filter.xml");
        ManagedFixture.text(test.target, "conf/attribute-filter.xml", Files.readString(path).replace("permitAny=\"true\"", "permitAny=\"unknown\""));
        var ex = assertThrows(ImportModel.ImportException.class, () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_RELEASE_UNRESOLVED"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void targetMetadataFiltersCannotHideOrInjectCommonRelease() throws Exception {
        fixture(); readyTarget();
        Path path = test.target.resolve("conf/metadata-providers.xml");
        ManagedFixture.text(test.target, "conf/metadata-providers.xml", Files.readString(path).replace("/>",
            "><MetadataFilter xsi:type=\"EntityAttributes\"/></MetadataProvider>"));
        var ex = assertThrows(ImportModel.ImportException.class, () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_RELEASE_UNRESOLVED"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void commonMappingIsRequiredButCatalogApprovalIsNotCreated() throws Exception {
        fixture(); common(test.target, "schacHomeOrganization");
        var ex = assertThrows(ImportModel.ImportException.class, () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_ATTRIBUTE_NOT_READY"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void commonPolicyMutationBetweenReviewAndApplyStopsBeforeFirstWrite() throws Exception {
        fixture(); readyTarget(); test.migration.prepare(test.analysis, test.analysis.candidates, false);
        common(test.target, "cn");
        assertThrows(ImportModel.ImportException.class, () -> test.migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void importedFilterResourceIsPinnedEvenOutsideConventionalFile() throws Exception {
        fixture(); readyTarget();
        Path filter = test.target.resolve("conf/attribute-filter.xml");
        String value = Files.readString(filter);
        ManagedFixture.text(test.target, "custom/common.xml", value);
        ManagedFixture.text(test.target, "conf/attribute-filter.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"><import resource=\"../custom/common.xml\"/></beans>");
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        ManagedFixture.text(test.target, "custom/common.xml", value + "\n");
        assertThrows(ImportModel.ImportException.class, () -> test.migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void commonOverlapDoesNotRemoveManagedAttributeFromProfile() throws Exception {
        fixture(); readyTarget(); common(test.source, "mail"); common(test.target, "mail");
        test.analysis = ManagedFixture.analyze(test.source);
        assertEquals(List.of("mail", "uid"), test.analysis.candidates.getFirst().managed.release().profileAttributes());
        assertEquals(List.of("schacHomeOrganization"), test.analysis.candidates.getFirst().managed.release().additionalAttributes());
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        assertEquals(0, test.migration.execute(false, false, Files.createDirectory(temp.resolve("staging"))));
    }

    @Test void commonReleaseCannotMaskAChangedManagedPolicy() throws Exception {
        fixture(); common(test.source, "mail");
        Path filter = test.source.resolve("conf/attribute-filter.xml");
        ManagedFixture.text(test.source, "conf/attribute-filter.xml", Files.readString(filter)
            .replace("<AttributeRule attributeID=\"mail\"><PermitValueRule xsi:type=\"ANY\"/></AttributeRule>", ""));
        var c = ManagedFixture.analyze(test.source).candidates.getFirst();
        assertTrue(c.blocked()); assertEquals(Set.of("uid", "mail", "schacHomeOrganization"), c.attributes);
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.detail().contains("SOURCE_ATTRIBUTE_FILTER_DRIFT")));
    }

    @Test void alteredManagedRequesterIsStillHeldEvenIfCommonRulesCoverAllAttributes() throws Exception {
        fixture(); common(test.source, "uid"); common(test.source, "mail");
        Path filter = test.source.resolve("conf/attribute-filter.xml");
        ManagedFixture.text(test.source, "conf/attribute-filter.xml", Files.readString(filter).replace("xsi:type=\"Requester\"", "xsi:type=\"ANY\""));
        assertTrue(ManagedFixture.analyze(test.source).held());
    }

    @Test void duplicateManagedPolicyIsHeld() throws Exception {
        fixture();
        Path filter = test.source.resolve("conf/attribute-filter.xml");
        String value = Files.readString(filter); int start = value.indexOf("<AttributeFilterPolicy id=\"2faskw-library\"");
        String duplicate = value.substring(start, value.indexOf("</AttributeFilterPolicy>", start) + "</AttributeFilterPolicy>".length());
        ManagedFixture.text(test.source, "conf/attribute-filter.xml", value.replace("</AttributeFilterPolicyGroup>", duplicate + "</AttributeFilterPolicyGroup>"));
        assertTrue(ManagedFixture.analyze(test.source).held());
    }

    private void targetChain(String type) throws Exception {
        Path path = test.target.resolve("conf/metadata-providers.xml");
        String provider = Files.readString(path);
        ManagedFixture.text(test.target, "conf/metadata-providers.xml",
            "<MetadataProvider xmlns=\"urn:mace:shibboleth:2.0:metadata\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\""
                + type + "\" id=\"outer\">" + provider + "</MetadataProvider>");
    }

    @Test void unfilteredChainingProviderAllowsCommonReleaseVerification() throws Exception {
        fixture(); readyTarget(); targetChain("ChainingMetadataProvider");
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        assertEquals(0, test.migration.execute(false, false, Files.createDirectory(temp.resolve("staging"))));
    }

    @Test void unknownProviderAncestorIsHeld() throws Exception {
        fixture(); readyTarget(); targetChain("CustomMetadataProvider");
        var ex = assertThrows(ImportModel.ImportException.class,
            () -> test.migration.prepare(test.analysis, test.analysis.candidates, false));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_RELEASE_UNRESOLVED"));
        assertFalse(test.executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }

    @Test void noneProfileCanHaveOnlyCommonReleasesWithoutAnOwnPolicy() throws Exception {
        fixture();
        ManagedFixture.text(test.target, "conf/attribute-filter.xml", "<AttributeFilterPolicyGroup xmlns=\"urn:mace:shibboleth:2.0:afp\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"></AttributeFilterPolicyGroup>");
        readyTarget();
        var registry = ManagedData.json(test.source, ManagedData.REGISTRY, 2 * 1024 * 1024);
        StrictJson.object(StrictJson.array(registry, "entries").getFirst()).put("attributeProfile", "none");
        ManagedFixture.json(test.source, ManagedData.REGISTRY, registry);
        ManagedFixture.text(test.source, "conf/attribute-filter.xml", "<AttributeFilterPolicyGroup xmlns=\"urn:mace:shibboleth:2.0:afp\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"></AttributeFilterPolicyGroup>");
        common(test.source, "schacHomeOrganization");
        test.analysis = ManagedFixture.analyze(test.source);
        assertFalse(test.analysis.held());
        assertTrue(test.analysis.candidates.getFirst().managed.release().profileAttributes().isEmpty());
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        assertEquals(0, test.migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals("none", ManagedData.registry(test.target).getFirst().profile());
    }

    @Test void newProfileContainsOnlyManagedAttributes() throws Exception {
        fixture(); readyTarget();
        var c = test.analysis.candidates.getFirst(); var original = c.managed;
        c.managed = new ManagedData.Intent("new-profile", "New profile", original.mfa(), original.cidrs(),
            original.approvals(), original.access(), original.globalAccess(), original.release());
        test.migration.prepare(test.analysis, test.analysis.candidates, false);
        assertEquals(0, test.migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals(Set.of("mail", "uid"), Set.copyOf(ManagedData.catalog(test.target).profiles().get("new-profile").attributes()));
        assertFalse(test.executor.calls.stream().anyMatch(calls -> calls.contains("schacHomeOrganization")));
    }

    @Test void commonPolicyChangedByCliIsDetectedAfterRegistration() throws Exception {
        fixture(); readyTarget();
        ExistingCli.Executor changed = (args, home) -> {
            var result = test.executor.execute(args, home);
            if (args.get(1).equals("add") && args.contains("--apply")) {
                Path filter = test.target.resolve("conf/attribute-filter.xml");
                ManagedFixture.text(test.target, "conf/attribute-filter.xml", Files.readString(filter).replace("schacHomeOrganization", "cn"));
            }
            return result;
        };
        var migration = new ManagedMigration(test.target, test.run, new ExistingCli(test.target, test.run, changed, null), null);
        migration.prepare(test.analysis, test.analysis.candidates, false);
        var ex = assertThrows(ImportModel.ImportException.class,
            () -> migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertTrue(ex.getMessage().contains("TARGET_ADDITIONAL_RELEASE_DIFFER"));
        assertEquals(1, ManagedData.registry(test.target).size());
        assertTrue(Files.readString(test.run.resolve("managed-result.txt")).contains("Keep target isolated"));
    }

    @Test void oldV2BundleKeepsOriginalSingleSetContract() throws Exception {
        test = new ManagedMigrationTest(); test.temp = temp; test.fixture(false, false);
        var c = test.analysis.candidates.getFirst(); var original = c.managed;
        c.managed = new ManagedData.Intent(original.profile(), original.description(), original.mfa(), original.cidrs(),
            original.approvals(), original.access(), original.globalAccess());
        assertFalse(ManagedData.intentObject(c.managed).containsKey("releaseContract"));
        Path bundle = temp.resolve("legacy-bundle"); MigrationBundle.save(test.analysis, bundle);
        var loaded = MigrationBundle.load(bundle);
        assertNull(loaded.candidates.getFirst().managed.release());
        assertEquals(Set.of("mail", "uid"), ManagedData.profileAttributes(loaded.candidates.getFirst()));
        test.migration.prepare(loaded, loaded.candidates, false);
        assertEquals(0, test.migration.execute(false, false, Files.createDirectory(temp.resolve("staging"))));
    }

    @Test void overlappingReleaseContractIsRejectedWhenLoadingBundle() throws Exception {
        fixture(); var c = test.analysis.candidates.getFirst(); var original = c.managed;
        c.managed = new ManagedData.Intent(original.profile(), original.description(), original.mfa(), original.cidrs(),
            original.approvals(), original.access(), original.globalAccess(),
            new ManagedData.ReleaseContract(List.of("mail", "uid"), List.of("mail", "schacHomeOrganization")));
        Path bundle = temp.resolve("invalid-bundle"); MigrationBundle.save(test.analysis, bundle);
        var ex = assertThrows(ImportModel.ImportException.class, () -> MigrationBundle.load(bundle));
        assertTrue(ex.getMessage().contains("overlapping release contract"));
    }
}
