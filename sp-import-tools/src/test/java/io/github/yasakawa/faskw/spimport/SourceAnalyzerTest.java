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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAnalyzerTest {
    @TempDir Path home;
    static final String ENTITY = "https://library.example.org/sp";
    static final String METADATA = """
        <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata" entityID="https://library.example.org/sp">
          <SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
            <AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="https://library.example.org/acs" index="0"/>
          </SPSSODescriptor>
        </EntityDescriptor>
        """;
    static final String PROVIDERS = """
        <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          id="Library" xsi:type="FilesystemMetadataProvider" metadataFile="%{idp.home}/metadata/library.xml"/>
        """;
    static final String FILTER = """
        <AttributeFilterPolicyGroup xmlns="urn:mace:shibboleth:2.0:afp" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <AttributeFilterPolicy id="library">
            <PolicyRequirementRule xsi:type="Requester" value="https://library.example.org/sp"/>
            <AttributeRule attributeID="uid"><PermitValueRule xsi:type="ANY"/></AttributeRule>
            <AttributeRule attributeID="mail"><PermitValueRule xsi:type="ANY"/></AttributeRule>
          </AttributeFilterPolicy>
        </AttributeFilterPolicyGroup>
        """;

    void write(String name, String value) throws Exception { Path file = home.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, value); }
    void fixture() throws Exception {
        write("conf/idp.properties", "# source\n");
        write("conf/services.properties", "");
        write("conf/services.xml", """
            <beans xmlns="http://www.springframework.org/schema/beans" xmlns:util="http://www.springframework.org/schema/util">
              <util:list id="shibboleth.MetadataResolverResources"><value>%{idp.home}/conf/metadata-providers.xml</value></util:list>
              <util:list id="shibboleth.AttributeFilterResources"><value>%{idp.home}/conf/attribute-filter.xml</value></util:list>
              <util:list id="shibboleth.RelyingPartyResolverResources"><value>%{idp.home}/conf/relying-party.xml</value></util:list>
            </beans>
            """);
        write("conf/metadata-providers.xml", PROVIDERS);
        write("conf/attribute-filter.xml", FILTER);
        write("conf/relying-party.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"/>");
        write("metadata/library.xml", METADATA);
    }
    ImportModel.Analysis analyze(String... extra) throws Exception {
        var args = new java.util.ArrayList<>(java.util.List.of("--source-idp-home", home.toString()));
        args.addAll(java.util.List.of(extra));
        return new SourceAnalyzer(ImportOptions.parse(args.toArray(String[]::new))).analyze();
    }

    @Test void followsStaticResourcesAndKeepsSourceUnchanged() throws Exception {
        fixture(); String before = Files.readString(home.resolve("conf/metadata-providers.xml"));
        var result = analyze();
        assertEquals(1, result.candidates.size());
        var c = result.candidates.getFirst();
        assertFalse(c.blocked(), c.diagnostics.toString());
        assertEquals(java.util.Set.of("uid", "mail"), c.attributes);
        assertEquals(SafeFiles.hash(METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8)), c.hash);
        assertTrue(c.source.matches(".*:[0-9]+:[0-9]+"));
        assertEquals(before, Files.readString(home.resolve("conf/metadata-providers.xml")));
    }
    @Test void excludesUnreferencedBackups() throws Exception {
        fixture(); write("conf/unused.bak.xml", PROVIDERS); assertEquals(1, analyze().candidates.size());
    }
    @Test void untrustedNamespaceCannotMasqueradeAsAnyPermitRule() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("xsi:type=\"ANY\"", "xmlns:other=\"urn:custom-policy\" xsi:type=\"other:ANY\""));
        assertTrue(analyze().candidates.getFirst().blocked());
    }
    @Test void followsNondefaultServiceResourceAndImport() throws Exception {
        fixture(); write("conf/services.properties", "idp.service.metadata.resources=CustomResources\n");
        write("conf/services.xml", """
            <beans xmlns="http://www.springframework.org/schema/beans" xmlns:util="http://www.springframework.org/schema/util">
              <import resource="resource-list.xml"/>
            </beans>
            """);
        write("conf/resource-list.xml", """
            <beans xmlns="http://www.springframework.org/schema/beans" xmlns:util="http://www.springframework.org/schema/util">
              <util:list id="CustomResources"><value>%{idp.home}/custom/providers.xml</value></util:list>
            </beans>
            """);
        write("custom/providers.xml", PROVIDERS);
        assertFalse(analyze().candidates.getFirst().blocked());
    }
    @Test void chainedProviderIsNotVisitedTwice() throws Exception {
        fixture(); write("conf/metadata-providers.xml", """
            <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ChainingMetadataProvider">
              <MetadataProvider id="Library" xsi:type="FilesystemMetadataProvider" metadataFile="metadata/library.xml"/>
            </MetadataProvider>
            """);
        assertEquals(1, analyze().candidates.size());
    }
    @Test void unknownSharedPolicyBlocksInsteadOfReleasingNone() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("xsi:type=\"Requester\"", "xsi:type=\"Script\""));
        assertTrue(analyze().candidates.getFirst().blocked());
    }
    @Test void unrelatedRequesterDoesNotApply() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace(ENTITY, "https://other.example.org/sp"));
        var c = analyze().candidates.getFirst(); assertTrue(c.attributes.isEmpty()); assertFalse(c.blocked());
    }
    @Test void simpleSharedOrIsSupported() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("<PolicyRequirementRule xsi:type=\"Requester\" value=\"" + ENTITY + "\"/>", """
            <PolicyRequirementRule xsi:type="OR"><Rule xsi:type="Requester" value="https://other.example.org/sp"/><Rule xsi:type="Requester" value="https://library.example.org/sp"/></PolicyRequirementRule>
            """));
        assertFalse(analyze().candidates.getFirst().blocked());
    }
    @Test void denyAndValueConditionsAreHeld() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("PermitValueRule", "DenyValueRule"));
        assertTrue(analyze().candidates.getFirst().blocked());
    }
    @Test void unsupportedConditionNamesPolicyAttributeTypeAndOperands() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("<PermitValueRule xsi:type=\"ANY\"/>",
            "<PermitValueRule xsi:type=\"OR\"><Rule xsi:type=\"Value\" value=\"staff\"/><Rule xsi:type=\"Regex\" regex=\"^member.*\"/></PermitValueRule>"));
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
        var d = c.diagnostics.stream().filter(v -> v.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).findFirst().orElseThrow();
        for (String text : java.util.List.of("policy=library", "attributeID=uid", "OR", "Value", "value=staff", "Regex", "regex=^member.*"))
            assertTrue(d.detail().contains(text), d.detail());
        assertTrue(d.location().contains("attribute-filter.xml:"));
    }
    @Test void scriptAndArbitraryAttributeContentsAreOmittedFromConditionEvidence() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("<PermitValueRule xsi:type=\"ANY\"/>",
            "<PermitValueRule xsi:type=\"Script\" customSecret=\"PRIVATE_OPERAND\"><Script>PRIVATE_SCRIPT_BODY</Script></PermitValueRule>"));
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
        for (var d : c.diagnostics.stream().filter(v -> v.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).toList()) {
            assertTrue(d.detail().contains("Script")); assertTrue(d.detail().contains("text omitted"));
            assertFalse(d.detail().contains("PRIVATE_OPERAND")); assertFalse(d.detail().contains("PRIVATE_SCRIPT_BODY"));
        }
    }
    @Test void largeConditionEvidenceIsBoundedWithoutChangingHold() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("<PermitValueRule xsi:type=\"ANY\"/>",
            "<PermitValueRule xsi:type=\"OR\">" + "<Rule xsi:type=\"Value\" value=\"staff\"/>".repeat(100) + "</PermitValueRule>"));
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
        var d = c.diagnostics.stream().filter(v -> v.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).findFirst().orElseThrow();
        assertTrue(d.detail().contains("truncated; inspect source XML")); assertTrue(d.detail().length() < 4400);
    }
    @Test void attributeIdWhitespaceIsQuotedClassifiedAndNeverAutomaticallyTrimmed() throws Exception {
        fixture(); String xml = FILTER.replace("attributeID=\"uid\"", "attributeID=\"uid \"");
        write("conf/attribute-filter.xml", xml); var c = analyze().candidates.getFirst();
        assertTrue(c.blocked()); assertFalse(c.attributes.contains("uid"));
        var d = c.diagnostics.stream().filter(v -> v.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).findFirst().orElseThrow();
        assertEquals("ATTRIBUTE_ID_WHITESPACE", d.reason()); assertTrue(d.detail().contains("attributeID=\"uid \""));
        assertEquals(xml, Files.readString(home.resolve("conf/attribute-filter.xml")));
    }
    @Test void missingAndUnsupportedIdsHaveSpecificReasons() throws Exception {
        fixture();
        for (var entry : java.util.Map.of("", "ATTRIBUTE_ID_MISSING", "123uid", "ATTRIBUTE_ID_INVALID").entrySet()) {
            write("conf/attribute-filter.xml", FILTER.replace("attributeID=\"uid\"", "attributeID=\"" + entry.getKey() + "\""));
            var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
            assertTrue(c.diagnostics.stream().anyMatch(d -> d.reason().equals(entry.getValue())));
        }
    }
    @Test void valueAndDenyConditionsAreManualMigrationNotSourceIdFailures() throws Exception {
        fixture();
        for (var entry : java.util.Map.of(
            "<PermitValueRule xsi:type=\"Value\" value=\"urn:example:entitlement:library\"/>", "ATTRIBUTE_VALUE_RULE_MANUAL",
            "<DenyValueRule xsi:type=\"Value\" value=\"guest\"/>", "ATTRIBUTE_DENY_RULE_MANUAL").entrySet()) {
            write("conf/attribute-filter.xml", FILTER.replace("<PermitValueRule xsi:type=\"ANY\"/>", entry.getKey()));
            var c = analyze().candidates.getFirst(); assertTrue(c.blocked()); assertTrue(c.attributes.isEmpty());
            assertTrue(c.diagnostics.stream().filter(d -> d.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).allMatch(d -> d.reason().equals(entry.getValue())));
        }
    }
    @Test void badIdAndManualConditionRemainSeparateHoldsOnTheSameRule() throws Exception {
        fixture(); write("conf/attribute-filter.xml", FILTER.replace("attributeID=\"uid\"", "attributeID=\"uid \"")
            .replace("<PermitValueRule xsi:type=\"ANY\"/>", "<PermitValueRule xsi:type=\"Value\" value=\"staff\"/>"));
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.reason().equals("ATTRIBUTE_ID_WHITESPACE")));
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.reason().equals("ATTRIBUTE_VALUE_RULE_MANUAL")));
    }
    @Test void scriptsRefsForeignTypesAndUnknownOptionsStayAdditionalReview() throws Exception {
        fixture();
        for (String condition : java.util.List.of(
            "<PermitValueRule xsi:type=\"Script\"><Script>NOT_EXECUTED</Script></PermitValueRule>",
            "<PermitValueRule xsi:type=\"Value\" value=\"staff\" unknownOption=\"true\"/>",
            "<PermitValueRule xmlns:custom=\"urn:custom\" xsi:type=\"custom:Value\" value=\"staff\"/>",
            "<PermitValueRuleReference ref=\"customRule\"/>",
            "<DenyValueRule xsi:type=\"Script\"><Script>NOT_EXECUTED</Script></DenyValueRule>")) {
            write("conf/attribute-filter.xml", FILTER.replace("<PermitValueRule xsi:type=\"ANY\"/>", condition));
            var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
            assertTrue(c.diagnostics.stream().filter(d -> d.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")).allMatch(d -> d.reason().equals("ATTRIBUTE_RULE_UNRESOLVED")));
        }
        write("conf/attribute-filter.xml", FILTER.replace("attributeID=\"uid\"", "attributeID=\"uid\" permitAny=\"unknown\"")
            .replace("<PermitValueRule xsi:type=\"ANY\"/>", "<DenyValueRule xsi:type=\"Value\" value=\"guest\"/>"));
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked());
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.reason().equals("ATTRIBUTE_RULE_UNRESOLVED") && d.detail().contains("attributeID=\"uid\"")));
    }
    @Test void missingReferencedFileExplainsFailure() throws Exception {
        fixture(); Files.delete(home.resolve("metadata/library.xml"));
        assertTrue(analyze().diagnostics.stream().anyMatch(d -> d.code().equals("METADATA_FILE_NOT_FOUND")));
    }
    @Test void neverFetchesRemoteAndReportsMdqIncomplete() throws Exception {
        fixture(); write("conf/metadata-providers.xml", PROVIDERS.replace("FilesystemMetadataProvider", "DynamicHTTPMetadataProvider"));
        assertTrue(analyze().diagnostics.stream().anyMatch(d -> d.code().equals("MDQ_LIST_INCOMPLETE")));
    }
    @Test void cachedRemoteProviderIsReviewedAsChangedBehavior() throws Exception {
        fixture(); write("conf/metadata-providers.xml", PROVIDERS.replace("FilesystemMetadataProvider", "FileBackedHTTPMetadataProvider").replace("metadataFile", "backingFile"));
        var c = analyze().candidates.getFirst();
        assertFalse(c.blocked()); assertTrue(c.diagnostics.stream().anyMatch(d -> d.code().equals("METADATA_REFRESH_BEHAVIOR_CHANGED")));
    }
    @Test void aggregateListsSpButDoesNotExportUnsafeChild() throws Exception {
        fixture(); write("metadata/library.xml", "<EntitiesDescriptor xmlns=\"urn:oasis:names:tc:SAML:2.0:metadata\">" + METADATA + "</EntitiesDescriptor>");
        var c = analyze().candidates.getFirst(); assertTrue(c.blocked()); assertNull(c.bytes);
    }
    @Test void relyingPartyOverridesAreSeparateFromRegistration() throws Exception {
        fixture(); write("conf/relying-party.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:util=\"http://www.springframework.org/schema/util\"><util:list id=\"shibboleth.RelyingPartyOverrides\"><bean parent=\"RelyingPartyByName\"/></util:list></beans>");
        var c = analyze().candidates.getFirst();
        assertFalse(c.blocked()); assertEquals("UNRESOLVED", c.accessState()); assertTrue(c.accessPending());
    }
    @Test void managedTargetReleaseAnalysisRetainsConservativeOverrideHold() throws Exception {
        fixture();
        write("conf/relying-party.xml", SourceAccessAnalyzerTest.HEADER
            + "<util:list id=\"shibboleth.RelyingPartyOverrides\">"
            + SourceAccessAnalyzerTest.override(ENTITY, "context-check") + "</util:list></beans>");
        Files.createDirectories(home.resolve("metadata/2faskw-managed-sp"));
        write("conf/metadata-providers.xml", "<MetadataProvider xmlns=\"urn:mace:shibboleth:2.0:metadata\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " id=\"GraphicalMatrixManagedSPMetadata\" xsi:type=\"LocalDynamicMetadataProvider\" sourceDirectory=\"%{idp.home}/metadata/2faskw-managed-sp\"/>");
        var incoming = new ImportModel.Candidate(ENTITY, "incoming", "provider"); incoming.bytes = METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = new SourceAnalyzer(ImportOptions.parse("--source-idp-home", home.toString())).analyzeReleases(java.util.List.of(incoming));
        assertTrue(result.candidates.getFirst().blocked());
        assertTrue(result.candidates.getFirst().diagnostics.stream().anyMatch(d -> d.code().equals("RP_DEPENDENCY_UNRESOLVED")));
        assertNull(result.candidates.getFirst().access);
    }
    @Test void boundaryAwarePathMapping() throws Exception {
        fixture(); write("conf/metadata-providers.xml", PROVIDERS.replace("%{idp.home}/metadata", "/old/idp/metadata"));
        assertFalse(analyze("--path-map", "/old/idp=" + home).candidates.getFirst().blocked());
    }
    @Test void propertiesSupportEscapesAndContinuationWithoutLoggingSecrets() throws Exception {
        fixture(); write("conf/idp.properties", "metadata.path=metadata/\\\n library.xml\npassword=NEVER_PRINT_THIS\n");
        write("conf/metadata-providers.xml", PROVIDERS.replace("%{idp.home}/metadata/library.xml", "%{metadata.path}"));
        var result = analyze(); assertFalse(result.candidates.getFirst().blocked());
        assertFalse(result.evidence.toString().contains("NEVER_PRINT_THIS"));
    }
    @Test void importCyclesBlock() throws Exception {
        fixture(); write("conf/metadata-providers.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"><import resource=\"metadata-providers.xml\"/></beans>");
        assertTrue(analyze().diagnostics.stream().anyMatch(d -> d.code().equals("CONFIG_REFERENCE_CYCLE")));
    }
    @Test void secureXmlRejectsDoctypeAndExcessiveDepth() {
        assertThrows(Exception.class, () -> SecureXml.parse("<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><x>&p;</x>".getBytes(), "test"));
        assertThrows(Exception.class, () -> SecureXml.parse(("<x>".repeat(65) + "</x>".repeat(65)).getBytes(), "test"));
    }
    @Test void unknownOrDuplicateOptionsAreRejected() throws Exception {
        assertThrows(ImportModel.ImportException.class, () -> ImportOptions.parse("--oops"));
        assertThrows(ImportModel.ImportException.class, () -> ImportOptions.parse("--apply", "--apply"));
        assertThrows(ImportModel.ImportException.class, () -> ImportOptions.parse("--bundle-dir", "b", "--target-idp-home", "t", "--apply", "--non-interactive").validate());
    }
}
