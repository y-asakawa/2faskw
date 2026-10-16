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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedSourceAnalyzerTest {
    @TempDir Path root;
    @Test void autoDetectsManagedAndPreservesSourceNameProfileMfa() throws Exception {
        ManagedFixture.source(root); var result = ManagedFixture.analyze(root);
        assertFalse(result.held(), result.diagnostics + " " + result.candidates.getFirst().diagnostics);
        var c = result.candidates.getFirst(); assertEquals("2faskw", result.mode); assertEquals("library", c.name);
        assertEquals("uid-mail", c.managed.profile()); assertEquals("force", c.managed.mfa());
        assertEquals(List.of("mail", "uid"), c.managed.approvals().stream().map(ManagedData.Approval::id).toList());
    }
    @Test void repeatedAcsLocationsAcrossBindingsAreAcceptedWithoutChangingSourceBytes() throws Exception {
        ManagedFixture.source(root);
        String metadata = SourceAnalyzerTest.METADATA.replace("</SPSSODescriptor>", """
            <AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Artifact" Location="https://library.example.org/acs" index="1"/>
            </SPSSODescriptor>
            """);
        String file = "metadata/2faskw-managed-sp/library.xml";
        ManagedFixture.text(root, file, metadata);
        var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        var entry = StrictJson.object(StrictJson.array(doc, "entries").getFirst());
        entry.put("metadataSha256", SafeFiles.hash(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        entry.put("acsUrls", List.of("https://library.example.org/acs", "https://library.example.org/acs"));
        ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        byte[] registryBefore = Files.readAllBytes(root.resolve(ManagedData.REGISTRY));
        byte[] metadataBefore = Files.readAllBytes(root.resolve(file));

        assertEquals(List.of("https://library.example.org/acs"), ManagedData.registry(root).getFirst().acs());
        var result = ManagedFixture.analyze(root);
        assertFalse(result.held(), result.diagnostics + " " + result.candidates.getFirst().diagnostics);
        assertEquals("force", result.candidates.getFirst().managed.mfa());
        Path bundle = MigrationBundle.save(result, root.resolve("export"));
        var loaded = MigrationBundle.load(bundle);
        assertFalse(loaded.held());
        assertArrayEquals(metadataBefore, loaded.candidates.getFirst().bytes);
        assertArrayEquals(registryBefore, Files.readAllBytes(root.resolve(ManagedData.REGISTRY)));
        assertArrayEquals(metadataBefore, Files.readAllBytes(root.resolve(file)));
    }
    @Test void repeatedAcsLocationsDoNotHideRegistryMetadataMismatch() throws Exception {
        ManagedFixture.source(root);
        var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        StrictJson.object(StrictJson.array(doc, "entries").getFirst()).put("acsUrls",
            List.of("https://library.example.org/other-acs", "https://library.example.org/other-acs"));
        ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        var result = ManagedFixture.analyze(root);
        assertTrue(result.held());
        assertTrue(result.candidates.getFirst().diagnostics.stream()
            .anyMatch(d -> d.detail().contains("REGISTRY_METADATA_MISMATCH: ACS/certificates")));
    }
    @Test void acsValuesStillRequireStringsWithoutControlCharacters() throws Exception {
        ManagedFixture.source(root);
        for (Object value : List.of(1L, true, "https://library.example.org/acs\n")) {
            var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
            StrictJson.object(StrictJson.array(doc, "entries").getFirst()).put("acsUrls", List.of(value, value));
            ManagedFixture.json(root, ManagedData.REGISTRY, doc);
            assertThrows(IllegalArgumentException.class, () -> ManagedData.registry(root));
        }
    }
    @Test void otherRegistryListsStillRejectDuplicateValues() throws Exception {
        for (String field : List.of("certificateFingerprints", "cidrs")) {
            ManagedFixture.source(root);
            var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
            var entry = StrictJson.object(StrictJson.array(doc, "entries").getFirst());
            String value = field.equals("cidrs") ? "192.0.2.0/24" : "AA:BB";
            entry.put(field, List.of(value, value));
            if (field.equals("cidrs")) entry.put("mfaProfile", "sp-cidr-bypass");
            ManagedFixture.json(root, ManagedData.REGISTRY, doc);
            var failure = assertThrows(IllegalArgumentException.class, () -> ManagedData.registry(root));
            assertEquals("duplicate values: " + field, failure.getMessage());
        }
    }
    @Test void preservesDisabledPolicyAndExactValues() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, false);
        var c = ManagedFixture.analyze(root).candidates.getFirst(); assertFalse(c.blocked(), c.diagnostics.toString());
        assertFalse(c.managed.access().enabled()); assertEquals(List.of("AA"), c.managed.access().allow().get("businessCategory"));
        assertEquals(3, c.managed.approvals().size());
    }
    @Test void schemaOneDoesNotInventAccessStateFromMissingRegistryFields() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, true);
        var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024); doc.put("schemaVersion", 1L);
        var e = StrictJson.object(StrictJson.array(doc, "entries").getFirst());
        e.remove("accessPolicyEnabled"); e.remove("accessPolicyRevision"); e.remove("attributeProfileRevision"); ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        assertTrue(ManagedFixture.analyze(root).candidates.getFirst().managed.access().enabled());
    }
    @Test void schemaTwoPolicyRevisionDriftIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, true);
        var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        StrictJson.object(StrictJson.array(doc, "entries").getFirst()).put("accessPolicyRevision", 2L); ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void corruptRegistryNeverFallsBackToGeneralMode() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, ManagedData.REGISTRY, "{\"schemaVersion\":9,\"entries\":[]}");
        assertThrows(ImportModel.ImportException.class, () -> ManagedFixture.analyze(root));
    }
    @Test void missingRegistryInExplicitManagedModeFails() {
        assertThrows(ImportModel.ImportException.class, () -> ManagedFixture.analyze(root, "--source-type", "2faskw"));
    }
    @Test void explicitGeneralModeWarnsThatManagementPoliciesAreNotTransferred() throws Exception {
        ManagedFixture.source(root); var a = ManagedFixture.analyze(root, "--source-type", "shibboleth");
        assertEquals("shibboleth", a.mode); assertNull(a.candidates.getFirst().managed);
        assertTrue(a.diagnostics.stream().anyMatch(d -> d.code().equals("MANAGED_SOURCE_EXPLICITLY_IGNORED")));
    }
    @Test void metadataDigestDriftIsHeldNotRepaired() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, "metadata/2faskw-managed-sp/library.xml", SourceAnalyzerTest.METADATA + "\n");
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void activeRegistryWithoutProviderIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, "conf/metadata-providers.xml", "<MetadataProvider xmlns=\"urn:mace:shibboleth:2.0:metadata\"/>");
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void missingCatalogDoesNotDefaultToReleaseApproved() throws Exception {
        ManagedFixture.source(root); Files.delete(root.resolve(ManagedData.DIR + "attribute-catalog.json"));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void mfaDriftIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, ManagedData.DIR + "mfa-policy.properties", "# no force\n");
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void enabledPolicyWithDisabledGlobalAccessRequiresManualDecision() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, true);
        ManagedFixture.text(root, ManagedData.DIR + "sp-management.properties", "graphicalmatrix.sp.access.enabled=false\n");
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void xmlReleaseProfileMismatchIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, "conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("attributeID=\"mail\"", "attributeID=\"cn\""));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void globalPermitAnyIsSeparatedFromManagedProfileWithoutChangingSource() throws Exception {
        ManagedFixture.source(root);
        ManagedFixture.text(root, "conf/attribute-filter.xml", Files.readString(root.resolve("conf/attribute-filter.xml")).replace("</AttributeFilterPolicyGroup>", """
            <AttributeFilterPolicy id="global"><PolicyRequirementRule xsi:type="ANY"/>
              <AttributeRule attributeID="schacHomeOrganization" permitAny="true"/>
            </AttributeFilterPolicy></AttributeFilterPolicyGroup>
            """));
        byte[] before = Files.readAllBytes(root.resolve("conf/attribute-filter.xml"));
        var analysis = ManagedFixture.analyze(root); var c = analysis.candidates.getFirst();
        assertFalse(c.blocked(), c.diagnostics.toString());
        assertEquals(java.util.Set.of("uid", "mail", "schacHomeOrganization"), c.attributes);
        assertEquals(List.of("mail", "uid"), c.managed.release().profileAttributes());
        assertEquals(List.of("schacHomeOrganization"), c.managed.release().additionalAttributes());
        assertEquals(List.of("mail", "uid"), c.managed.approvals().stream().map(ManagedData.Approval::id).toList());
        assertFalse(c.diagnostics.stream().anyMatch(d -> d.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")));
        var loaded = MigrationBundle.load(MigrationBundle.save(analysis, root.resolve("export"))).candidates.getFirst();
        assertEquals(c.managed, loaded.managed); assertEquals(c.attributes, loaded.attributes);
        assertArrayEquals(before, Files.readAllBytes(root.resolve("conf/attribute-filter.xml")));
    }
    @Test void registryMetadataSymlinkIsRejected() throws Exception {
        ManagedFixture.source(root); Path file = root.resolve("metadata/2faskw-managed-sp/library.xml"); Files.delete(file);
        Files.createSymbolicLink(file, root.resolve("metadata/library.xml")); assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void orphanPolicyIsHeld() throws Exception {
        ManagedFixture.source(root);
        ManagedFixture.json(root, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of(ManagedFixture.policy("orphan", "https://other.example.org/sp", true, "AA"))));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void activeAccessWithoutContextCheckIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, true);
        ManagedFixture.text(root, "conf/relying-party.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"><bean parent=\"SAML2.SSO\"/></beans>");
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void missingPolicyWithEnabledGlobalAccessIsNotTreatedAsEmpty() throws Exception {
        ManagedFixture.source(root); Files.delete(root.resolve(ManagedData.DIR + "access-policy.json"));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void missingSchemaOnePolicyIsNotInferredFromMissingFlags() throws Exception {
        ManagedFixture.source(root); var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        doc.put("schemaVersion", 1L); ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        ManagedFixture.text(root, ManagedData.DIR + "sp-management.properties", "graphicalmatrix.sp.access.enabled=false\n");
        Files.delete(root.resolve(ManagedData.DIR + "access-policy.json"));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void customAccessFunctionIsHeld() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, true);
        Path config = root.resolve("conf/intercept/context-check-intercept-config.xml");
        Files.writeString(config, Files.readString(config).replace("io.github.yasakawa.faskw.GraphicalMatrixSpAccessFunction", "example.CustomAccessFunction"));
        assertTrue(ManagedFixture.analyze(root).held());
    }
    @Test void bundleV2RoundTripPreservesPolicyAndGlobalSettingsButNotLegacySecrets() throws Exception {
        ManagedFixture.source(root); ManagedFixture.access(root, false); var a = ManagedFixture.analyze(root);
        Path bundle = MigrationBundle.save(a, root.resolve("export")); var loaded = MigrationBundle.load(bundle);
        assertEquals("2faskw", loaded.mode); assertEquals(a.globalMfa, loaded.globalMfa);
        assertEquals(a.candidates.getFirst().managed, loaded.candidates.getFirst().managed);
        assertTrue(Files.readString(bundle.resolve("manifest.tsv")).startsWith(MigrationBundle.MANAGED_HEADER));
        assertFalse(Files.exists(bundle.resolve(ManagedData.REGISTRY))); assertFalse(Files.exists(bundle.resolve("credentials")));
    }
    @Test void bundleCannotOverrideSourceMode() throws Exception {
        var options = ImportOptions.parse("--bundle-dir", root.toString(), "--source-type", "shibboleth");
        assertThrows(ImportModel.ImportException.class, options::validate);
    }
    @Test void spManagementPropertiesUseLatinOneAndJavaEscapes() throws Exception {
        ManagedFixture.source(root); Path path = root.resolve(ManagedData.DIR + "sp-management.properties");
        Files.write(path, "label=caf\u00e9\nescaped=\\u65e5\\u672c\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        var p = ManagedData.properties(root, ManagedData.DIR + "sp-management.properties", true);
        assertEquals("caf\u00e9", p.getProperty("label")); assertEquals("\u65e5\u672c", p.getProperty("escaped"));
    }
    @Test void decodedDuplicatePropertyKeysAreRejected() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, ManagedData.DIR + "sp-management.properties", "key=one\n\\u006bey=two\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedData.properties(root, ManagedData.DIR + "sp-management.properties", true));
    }
    @Test void mfaPropertiesRejectRuntimeAndCliDecodingDifference() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, ManagedData.DIR + "mfa-policy.properties", "graphicalmatrix.mfa.forceSPs=\\u0061\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedData.properties(root, ManagedData.DIR + "mfa-policy.properties", false));
    }
    @Test void mfaPropertiesRejectNonAsciiRuntimeDifference() throws Exception {
        ManagedFixture.source(root); ManagedFixture.text(root, ManagedData.DIR + "mfa-policy.properties", "graphicalmatrix.mfa.forceSPs=https://caf\u00e9.example.org/sp\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedData.properties(root, ManagedData.DIR + "mfa-policy.properties", false));
    }
    @Test void oversizedRegistryRevisionDoesNotWrap() throws Exception {
        ManagedFixture.source(root); var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        var entry = StrictJson.object(StrictJson.array(doc, "entries").getFirst());
        entry.put("attributeProfileRevision", 4294967296L);
        ManagedFixture.json(root, ManagedData.REGISTRY, doc);
        assertThrows(IllegalArgumentException.class, () -> ManagedData.registry(root));
    }
    @Test void duplicateJsonKeysAndTrailingInputAreRejected() {
        assertThrows(Exception.class, () -> StrictJson.parse("{\"schemaVersion\":1,\"schemaVersion\":2}".getBytes()));
        assertThrows(Exception.class, () -> StrictJson.parse("{} {}".getBytes()));
    }
    @Test void unknownPolicyFieldAndDuplicateValuesAreRejected() throws Exception {
        ManagedFixture.source(root); var p = new java.util.LinkedHashMap<>(ManagedFixture.policy("library", SourceAnalyzerTest.ENTITY, true, "AA")); p.put("script", "not executed");
        ManagedFixture.json(root, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of(p)));
        assertThrows(Exception.class, () -> ManagedData.policies(root));
        p.remove("script"); p.put("allow", List.of(Map.of("attributeId", "businessCategory", "values", List.of("AA", "AA"))));
        ManagedFixture.json(root, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of(p)));
        assertThrows(Exception.class, () -> ManagedData.policies(root));
    }
    @Test void normalizedJsonHasStableOrderingAcrossMaps() throws Exception {
        Map<String, Object> one = new java.util.LinkedHashMap<>(), two = new java.util.LinkedHashMap<>();
        one.put("a", 1L); one.put("b", 2L); two.put("b", 2L); two.put("a", 1L);
        assertArrayEquals(StrictJson.write(one), StrictJson.write(two));
    }
}
