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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAttributePolicyTest {
    @TempDir Path home;
    private SourceAnalyzerTest source;
    private static final String FORMAT = "urn:oasis:names:tc:SAML:2.0:attrname-format:uri";
    private static final String RELEASE = "http://shibboleth.net/ns/attributes/releaseAllValues";
    private static final String SUBJECT = "urn:oasis:names:tc:SAML:profiles:subject-id:req";

    @BeforeEach void fixture() throws Exception {
        source = new SourceAnalyzerTest(); source.home = home; source.fixture();
    }

    private String exact(String element, String name, String value) {
        return "<" + element + " xsi:type=\"EntityAttributeExactMatch\" attributeName=\"" + name
            + "\" attributeNameFormat=\"" + FORMAT + "\" attributeValue=\"" + value + "\"/>";
    }

    private String tag(String name, String value) {
        return "<saml:Attribute Name=\"" + name + "\" NameFormat=\"" + FORMAT + "\">"
            + "<saml:AttributeValue>" + value + "</saml:AttributeValue></saml:Attribute>";
    }

    private void metadata(String tags) throws Exception {
        source.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("<SPSSODescriptor", """
            <Extensions xmlns:mdattr="urn:oasis:names:tc:SAML:metadata:attribute" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
              <mdattr:EntityAttributes>%s</mdattr:EntityAttributes>
            </Extensions>
            <SPSSODescriptor
            """.formatted(tags)));
    }

    private void filter(String requirement, String rules) throws Exception {
        source.write("conf/attribute-filter.xml", """
            <AttributeFilterPolicyGroup xmlns="urn:mace:shibboleth:2.0:afp" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <AttributeFilterPolicy id="test">%s%s</AttributeFilterPolicy>
            </AttributeFilterPolicyGroup>
            """.formatted(requirement, rules));
    }

    private ImportModel.Candidate candidate() throws Exception { return source.analyze().candidates.getFirst(); }
    private void expect(Set<String> attributes) throws Exception {
        var c = candidate(); assertFalse(c.blocked(), c.diagnostics.toString()); assertEquals(attributes, c.attributes);
    }

    @Test void permitAnySupportsXmlBooleanFormsAndPreservesInputsAndBundle() throws Exception {
        for (String value : new String[]{"true", "1"}) {
            filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"uid\" permitAny=\"" + value + "\"/>");
            byte[] before = Files.readAllBytes(home.resolve("conf/attribute-filter.xml"));
            var analysis = source.analyze(); var c = analysis.candidates.getFirst();
            assertFalse(c.blocked()); assertEquals(Set.of("uid"), c.attributes);
            Path bundle = MigrationBundle.save(analysis, home.resolve("export-" + value));
            var loaded = MigrationBundle.load(bundle).candidates.getFirst();
            assertEquals(c.attributes, loaded.attributes);
            assertArrayEquals(SourceAnalyzerTest.METADATA.getBytes(StandardCharsets.UTF_8), loaded.bytes);
            assertArrayEquals(before, Files.readAllBytes(home.resolve("conf/attribute-filter.xml")));
        }
    }

    @Test void invalidOrConflictingPermitAnyIsHeld() throws Exception {
        for (String rule : new String[]{
                "<AttributeRule attributeID=\"uid\" permitAny=\"yes\"/>",
                "<AttributeRule attributeID=\"uid\" permitAny=\"false\"/>",
                "<AttributeRule attributeID=\"uid\" permitAny=\"true\"><DenyValueRule xsi:type=\"ANY\"/></AttributeRule>"}) {
            filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", rule); assertTrue(candidate().blocked());
        }
    }

    @Test void explicitFalsePermitAnyStillAllowsExplicitPermitRule() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>",
            "<AttributeRule attributeID=\"uid\" permitAny=\"false\"><PermitValueRule xsi:type=\"ANY\"/></AttributeRule>");
        expect(Set.of("uid"));
    }

    @Test void exactEntityTagControlsAllValuesAndRecordsEvidence() throws Exception {
        metadata(tag(RELEASE, "mail"));
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>",
            "<AttributeRule attributeID=\"mail\">" + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        expect(Set.of("mail"));
        assertTrue(candidate().facts.stream().anyMatch(s -> s.contains("entity_attribute_match=YES")));
    }

    @Test void missingEntityTagReleasesNothingWithoutBlocking() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>",
            "<AttributeRule attributeID=\"mail\">" + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        expect(Set.of());
        assertTrue(candidate().facts.stream().anyMatch(s -> s.contains("entity_attribute_match=NO")));
    }

    @Test void exactTagCanBePolicyRequirement() throws Exception {
        metadata(tag(RELEASE, "eduPersonScopedAffiliation"));
        filter(exact("PolicyRequirementRule", RELEASE, "eduPersonScopedAffiliation"),
            "<AttributeRule attributeID=\"eduPersonScopedAffiliation\" permitAny=\"true\"/>");
        expect(Set.of("eduPersonScopedAffiliation"));
        metadata(""); expect(Set.of());
    }

    @Test void multipleTagValuesAndRepeatedAttributesUseExactStringMatching() throws Exception {
        metadata(tag(RELEASE, "uid").replace("</saml:Attribute>",
            "<saml:AttributeValue>mail</saml:AttributeValue></saml:Attribute>") + tag(RELEASE, "eduPersonPrincipalName"));
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">"
            + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        expect(Set.of("mail"));
        metadata(tag(RELEASE, "MAIL")); expect(Set.of());
    }

    @Test void unknownPolicyRequirementReportsConcreteTagReason() throws Exception {
        filter(exact("PolicyRequirementRule", RELEASE, "mail").replace(" attributeNameFormat=\"" + FORMAT + "\"", ""),
            "<AttributeRule attributeID=\"mail\" permitAny=\"true\"/>");
        var c = candidate(); assertTrue(c.blocked());
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.code().equals("ATTRIBUTE_POLICY_UNRESOLVED")));
        assertTrue(c.facts.stream().anyMatch(s -> s.contains("entity_attribute_match=UNKNOWN") && s.contains("explicit NameFormat")));
    }

    @Test void subjectIdOrHandlesPairwiseAnyAndSubjectTags() throws Exception {
        String rules = "<AttributeRule attributeID=\"samlPairwiseID\"><PermitValueRule xsi:type=\"OR\">"
            + exact("Rule", SUBJECT, "pairwise-id") + exact("Rule", SUBJECT, "any") + "</PermitValueRule></AttributeRule>"
            + "<AttributeRule attributeID=\"samlSubjectID\">" + exact("PermitValueRule", SUBJECT, "subject-id") + "</AttributeRule>";
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", rules);
        for (String value : new String[]{"pairwise-id", "any", "subject-id", "other"}) {
            metadata(tag(SUBJECT, value));
            expect(value.equals("subject-id") ? Set.of("samlSubjectID") : value.equals("other") ? Set.of() : Set.of("samlPairwiseID"));
        }
    }

    @Test void nestedAllValueAndOrDoesNotBecomeUnconditional() throws Exception {
        metadata(tag(RELEASE, "mail"));
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>",
            "<AttributeRule attributeID=\"mail\"><PermitValueRule xsi:type=\"AND\">"
                + exact("Rule", RELEASE, "mail") + "<Rule xsi:type=\"OR\">"
                + exact("Rule", SUBJECT, "any") + exact("Rule", SUBJECT, "subject-id")
                + "</Rule></PermitValueRule></AttributeRule>");
        expect(Set.of()); metadata(tag(RELEASE, "mail") + tag(SUBJECT, "any")); expect(Set.of("mail"));
    }

    @Test void mappedRegistryAndDynamicRuleModesAreHeld() throws Exception {
        metadata(tag(RELEASE, "mail"));
        String rule = exact("PermitValueRule", RELEASE, "mail");
        for (String changed : new String[]{rule.replace(" attributeNameFormat=\"" + FORMAT + "\"", ""),
                rule.replace("/>", " ignoreUnmappedEntityAttributes=\"true\"/>"),
                rule.replace("attributeValue=\"mail\"", "attributeValue=\"%{release.attribute}\"")}) {
            filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">" + changed + "</AttributeRule>");
            assertTrue(candidate().blocked());
        }
    }

    @Test void explicitRawXmlFalseModeAndStringTypeAreSupported() throws Exception {
        metadata(tag(RELEASE, "mail").replace("<saml:AttributeValue>",
            "<saml:AttributeValue xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xsi:type=\"xs:string\">"));
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">"
            + exact("PermitValueRule", RELEASE, "mail").replace("/>", " ignoreUnmappedEntityAttributes=\"false\"/>") + "</AttributeRule>");
        expect(Set.of("mail"));
    }

    @Test void metadataFiltersHoldEvenWhenRawTagIsPresentOrAbsent() throws Exception {
        source.write("conf/metadata-providers.xml", SourceAnalyzerTest.PROVIDERS.replace("/>",
            "><MetadataFilter xsi:type=\"EntityAttributes\"/></MetadataProvider>"));
        filter(exact("PolicyRequirementRule", RELEASE, "mail"), "<AttributeRule attributeID=\"mail\" permitAny=\"true\"/>");
        for (String tags : new String[]{"", tag(RELEASE, "mail")}) {
            metadata(tags); var c = candidate(); assertTrue(c.blocked()); assertTrue(c.attributes.isEmpty());
            assertTrue(c.diagnostics.stream().anyMatch(d -> d.code().equals("ATTRIBUTE_POLICY_UNRESOLVED")));
        }
    }

    @Test void roleTagsRequestedAttributesAndWrongNamespacesAreNotEntityTags() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">"
            + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        metadata(tag(RELEASE, "mail"));
        String valid = Files.readString(home.resolve("metadata/library.xml"));
        source.write("metadata/library.xml", valid.replace("urn:oasis:names:tc:SAML:metadata:attribute", "urn:custom-tags"));
        expect(Set.of());
        int start = valid.indexOf("<Extensions"); int end = valid.indexOf("</Extensions>") + "</Extensions>".length();
        String extension = valid.substring(start, end);
        source.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("</SPSSODescriptor>",
            extension + "<AttributeConsumingService index=\"0\"><RequestedAttribute Name=\"mail\"/></AttributeConsumingService></SPSSODescriptor>"));
        expect(Set.of());
    }

    @Test void wrongNameFormatOrValueDoesNotMatch() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">"
            + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        for (String tags : new String[]{tag(RELEASE, "uid"), tag(RELEASE, "mail").replace(FORMAT, "urn:other-format"),
                tag(RELEASE, "mail").replace(" NameFormat=\"" + FORMAT + "\"", "")}) {
            metadata(tags); expect(Set.of());
        }
    }

    @Test void assertionsComplexValuesAndUnknownTypesRemainHeld() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">"
            + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>");
        for (String tags : new String[]{"<saml:Assertion/>", tag(RELEASE, "<saml:NameID>mail</saml:NameID>"),
                tag(RELEASE, "mail").replace("<saml:AttributeValue>",
                    "<saml:AttributeValue xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"saml:unknown\">"),
                tag(RELEASE, "mail").replace("<saml:AttributeValue>",
                    "<saml:AttributeValue xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\">" )}) {
            metadata(tags); assertTrue(candidate().blocked());
        }
    }

    @Test void unknownValueRulesAndMalformedOrRemainHeld() throws Exception {
        for (String permit : new String[]{"<PermitValueRule xsi:type=\"Value\" value=\"AA\"/>",
                "<PermitValueRule xsi:type=\"OR\"/>", "<PermitValueRule xsi:type=\"OR\"><Other xsi:type=\"ANY\"/></PermitValueRule>",
                "<PermitValueRule xmlns:other=\"urn:custom\" xsi:type=\"other:ANY\"/>"}) {
            filter("<PolicyRequirementRule xsi:type=\"ANY\"/>", "<AttributeRule attributeID=\"mail\">" + permit + "</AttributeRule>");
            assertTrue(candidate().blocked());
        }
    }

    @Test void standardDefaultFiltersDoNotTreatEveryTagBasedAttributeAsReleased() throws Exception {
        filter("<PolicyRequirementRule xsi:type=\"ANY\"/>",
            "<AttributeRule attributeID=\"schacHomeOrganization\" permitAny=\"true\"/>"
                + "<AttributeRule attributeID=\"mail\">" + exact("PermitValueRule", RELEASE, "mail") + "</AttributeRule>"
                + "<AttributeRule attributeID=\"eduPersonPrincipalName\">" + exact("PermitValueRule", RELEASE, "eduPersonPrincipalName") + "</AttributeRule>");
        expect(Set.of("schacHomeOrganization"));
    }
}
