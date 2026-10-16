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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAccessAnalyzerTest {
    @TempDir Path temp;
    static final String HEADER = """
        <beans xmlns="http://www.springframework.org/schema/beans"
          xmlns:util="http://www.springframework.org/schema/util"
          xmlns:c="http://www.springframework.org/schema/c" xmlns:p="http://www.springframework.org/schema/p">
        """;
    static String override(String entity, String flows) {
        return "<bean parent=\"RelyingPartyByName\" c:relyingPartyIds=\"" + entity + "\">"
            + "<property name=\"profileConfigurations\"><list><bean parent=\"SAML2.SSO\" p:postAuthenticationFlows=\""
            + flows + "\"/></list></property></bean>";
    }
    static String logical(String op, String children) {
        return "<bean parent=\"shibboleth.Conditions." + op + "\"><constructor-arg><list>" + children + "</list></constructor-arg></bean>";
    }
    static String selector(String value) {
        return "<bean parent=\"shibboleth.Conditions.RelyingPartyId\" c:candidates=\"" + value + "\"/>";
    }
    static String attribute(String extra, String values) {
        return "<bean class=\"net.shibboleth.idp.profile.logic.SimpleAttributePredicate\" " + extra
            + "><property name=\"attributeValueMap\"><map><entry key=\"businessCategory\"><list>"
            + values + "</list></entry></map></property></bean>";
    }
    static String condition(String expression) {
        return expression.replaceFirst("<bean ", "<bean id=\"shibboleth.context-check.Condition\" ");
    }
    ImportModel.AccessAssessment assess(String rp, String xml, String entity) throws Exception {
        Path config = temp.resolve("context.xml");
        if (xml != null) Files.writeString(config, HEADER + xml + "</beans>");
        var root = SecureXml.parse((HEADER + rp + "</beans>").getBytes(java.nio.charset.StandardCharsets.UTF_8), "rp.xml");
        return new SourceAccessAnalyzer(root.descendants(), config, Files::readAllBytes)
            .assess(new ImportModel.Candidate(entity, "source", "provider"));
    }
    String overrides(String body) { return "<util:list id=\"shibboleth.RelyingPartyOverrides\">" + body + "</util:list>"; }
    @Test void unrelatedLiteralOverrideDoesNotHoldRegistrationOrAccess() throws Exception {
        var result = assess(overrides(override("https://other.example.org/sp", "context-check")), null, SourceAnalyzerTest.ENTITY);
        assertEquals("NONE_OBSERVED", result.status()); assertFalse(result.pending());
    }
    @Test void extractsAndOrNotAndPreservesRegexAndUnspecifiedFiltering() throws Exception {
        String attr = attribute("", "<value>AA</value><value>BB</value>");
        String regex = "<bean class=\"net.shibboleth.idp.profile.logic.RegexAttributePredicate\" p:useUnfilteredAttributes=\"true\" p:attributeId=\"mail\" p:pattern=\"^(.*?)@(gm.|)example.org\"/>";
        String tree = logical("OR", logical("AND", selector("#{ '" + SourceAnalyzerTest.ENTITY + "' }")
            + logical("AND", attr + regex + logical("NOT", attribute("p:useUnfilteredAttributes=\"false\"", "<value>ZZ</value>"))))
            + logical("NOT", selector("#{ {'" + SourceAnalyzerTest.ENTITY + "','https://other.example.org/sp'} }")));
        var result = assess(overrides(override(SourceAnalyzerTest.ENTITY, "#{ {'context-check','attribute-release'} }")), condition(tree), SourceAnalyzerTest.ENTITY);
        assertEquals("MANUAL_REQUIRED", result.status());
        String text = result.evidence().toString();
        assertTrue(text.contains("businessCategory IN [AA, BB]")); assertTrue(text.contains("NOT(ATTRIBUTE_AND"));
        assertTrue(text.contains("^(.*?)@(gm.|)example.org")); assertTrue(text.contains("UNSPECIFIED"));
        assertTrue(text.contains("useUnfilteredAttributes=false")); assertTrue(text.contains("NOT_PERFORMED"));
    }
    @Test void nestedConditionIdsAreNotDuplicateTopLevelBeans() throws Exception {
        String nested = condition(logical("AND", selector(SourceAnalyzerTest.ENTITY) + attribute("", "<value>AA</value>")));
        var result = assess(overrides(override(SourceAnalyzerTest.ENTITY, "context-check,attribute-release")),
            condition(logical("OR", nested)), SourceAnalyzerTest.ENTITY);
        assertEquals("MANUAL_REQUIRED", result.status());
    }
    @Test void fallbackSimplifiesOtherSpBranchesWithoutLosingManualStatus() throws Exception {
        String tree = logical("OR", logical("AND", selector("https://other.example.org/sp") + "<bean class=\"UnknownPredicate\"/>")
            + logical("NOT", selector("https://other.example.org/sp")));
        var result = assess(overrides(override(SourceAnalyzerTest.ENTITY, "context-check")), condition(tree), SourceAnalyzerTest.ENTITY);
        assertEquals("MANUAL_REQUIRED", result.status());
        assertTrue(result.evidence().toString().contains("TRUE(fallback permits this SP)"));
    }
    @Test void missingContextIsAccessUnresolvedNotRegistrationFailure() throws Exception {
        assertEquals("UNRESOLVED", assess(overrides(override(SourceAnalyzerTest.ENTITY, "context-check")), null, SourceAnalyzerTest.ENTITY).status());
    }
    @Test void customScriptRefAndExpressionAreNeverExecuted() throws Exception {
        for (String xml : List.of("<bean id=\"shibboleth.context-check.Function\" class=\"CustomFunction\"/>",
            "<bean id=\"shibboleth.context-check.Condition\" parent=\"CustomPredicate\"/>",
            condition(logical("AND", "<ref bean=\"externalRule\"/>")),
            condition(logical("AND", selector("#{T(java.lang.Runtime).getRuntime()}") )))) {
            assertEquals("UNRESOLVED", assess(overrides(override(SourceAnalyzerTest.ENTITY, "context-check")), xml, SourceAnalyzerTest.ENTITY).status());
        }
    }
    @Test void unknownSelectorRemainsSeparateAndUnresolved() throws Exception {
        assertEquals("UNRESOLVED", assess(overrides("<ref bean=\"externalOverride\"/>"), null, SourceAnalyzerTest.ENTITY).status());
    }
    @Test void defaultContextAndCustomInterceptorsAreReported() throws Exception {
        String rp = "<bean id=\"shibboleth.DefaultRelyingParty\" parent=\"RelyingParty\"><property name=\"profileConfigurations\"><list>"
            + "<bean parent=\"SAML2.SSO\" p:postAuthenticationFlows=\"context-check,custom-check\"/></list></property></bean>";
        var result = assess(rp, condition(logical("AND", attribute("", "<value>AA</value>"))), SourceAnalyzerTest.ENTITY);
        assertEquals("MANUAL_REQUIRED", result.status());
        assertTrue(result.evidence().toString().contains("custom_post_authentication_flow"));
    }
    @Test void importsAndForeignPredicatesRemainUnresolved() throws Exception {
        for (String xml : List.of("<import resource=\"other.xml\"/>", "<bean xmlns=\"urn:foreign\" id=\"shibboleth.context-check.Condition\"/>",
            condition(logical("AND", attribute("", "<value>${category}</value>"))))) {
            assertEquals("UNRESOLVED", assess(overrides(override(SourceAnalyzerTest.ENTITY, "context-check")), xml, SourceAnalyzerTest.ENTITY).status());
        }
    }
    @Test void contextConfigIsHashedAndExplicitPathIsSupported() throws Exception {
        var fixture = new SourceAnalyzerTest(); fixture.home = temp.resolve("source"); fixture.fixture();
        fixture.write("conf/relying-party.xml", HEADER + overrides(override(SourceAnalyzerTest.ENTITY, "context-check")) + "</beans>");
        Path config = temp.resolve("elsewhere.xml");
        Files.writeString(config, HEADER + condition(logical("AND", attribute("", "<value>AA</value>"))) + "</beans>");
        var analysis = fixture.analyze("--context-check-config", config.toString());
        assertFalse(analysis.candidates.getFirst().blocked()); assertEquals("MANUAL_REQUIRED", analysis.candidates.getFirst().accessState());
        assertTrue(analysis.inputHashes.containsKey(config.toRealPath()));
    }
    @Test void literalParserDoesNotEvaluateExpressions() {
        assertEquals(List.of("a", "b"), SourceAccessAnalyzer.literalValues("#{ {'a','b'} }"));
        assertEquals(List.of("a"), SourceAccessAnalyzer.literalValues("#{ 'a' }"));
        assertNull(SourceAccessAnalyzer.literalValues("#{ 'a' + 'b' }"));
        assertNull(SourceAccessAnalyzer.literalValues("${selector}"));
    }
    @Test void ambiguousPlainMultiSelectorsAreNotAssumedUnrelated() throws Exception {
        var result = assess(overrides(override(SourceAnalyzerTest.ENTITY + ",https://other.example.org/sp", "attribute-release")), null, SourceAnalyzerTest.ENTITY);
        assertEquals("UNRESOLVED", result.status());
        assertTrue(result.evidence().toString().contains("scope unresolved"));
    }
    @Test void customOverrideContainerOrStandaloneBeansRequireReview() throws Exception {
        for (String rp : List.of("<bean id=\"shibboleth.RelyingPartyOverrides\" factory-bean=\"externalFactory\" factory-method=\"get\"/>",
            "<bean id=\"customAccessRule\" class=\"org.example.CustomPredicate\"/>")) {
            assertEquals("UNRESOLVED", assess(rp, null, SourceAnalyzerTest.ENTITY).status());
        }
    }
    @Test void scalarAndCredentialSupportDoNotBecomeUnknownAuthorization() throws Exception {
        var result = assess("<bean class=\"java.lang.String\"><constructor-arg value=\"constant\"/></bean>"
            + "<bean class=\"net.shibboleth.idp.profile.spring.factory.BasicX509CredentialFactoryBean\"/>", null, SourceAnalyzerTest.ENTITY);
        assertEquals("NONE_OBSERVED", result.status()); assertTrue(result.evidence().toString().contains("standard_support_beans=2"));
    }
    @Test void importedFrameworkResourcesAreSummarizedAsManualReviewNotHundredsOfUnknownBeans() throws Exception {
        var f = new SourceAnalyzerTest(); f.home = temp.resolve("source"); f.fixture();
        StringBuilder beans = new StringBuilder(HEADER);
        for (int i = 0; i < 200; i++) beans.append("<bean id=\"framework-").append(i)
            .append("\" class=\"net.shibboleth.idp.saml.profile.config.StringConfigurationLookupStrategy\"/>");
        f.write("system/conf/relying-party-mddriven.xml", beans + "</beans>");
        f.write("conf/relying-party.xml", HEADER + "<import resource=\"../system/conf/relying-party-mddriven.xml\"/>"
            + overrides(override(SourceAnalyzerTest.ENTITY, "context-check")) + "</beans>");
        f.write("conf/intercept/context-check-intercept-config.xml", HEADER + condition(logical("AND", attribute("", "<value>AA</value>"))) + "</beans>");
        var c = f.analyze().candidates.getFirst();
        assertEquals("MANUAL_REQUIRED", c.accessState());
        assertTrue(c.access.evidence().toString().contains("businessCategory IN [AA]"));
        assertEquals(1, c.access.evidence().stream().filter(s -> s.startsWith("standard_framework_review=")).count());
        assertTrue(c.access.evidence().stream().noneMatch(s -> s.startsWith("custom_bean_scope_unresolved=")));
    }
    @Test void customClassesAndSpoofedSystemResourcePathsRemainUnresolved() throws Exception {
        var f = new SourceAnalyzerTest(); f.home = temp.resolve("source"); f.fixture();
        for (String path : List.of("system/conf/relying-party-system.xml", "custom/relying-party-system.xml")) {
            f.write(path, HEADER + "<bean class=\"org.example.CustomAuthorization\"/></beans>");
            f.write("conf/relying-party.xml", HEADER + "<import resource=\"../" + path + "\"/></beans>");
            assertEquals("UNRESOLVED", f.analyze().candidates.getFirst().accessState());
        }
        f.write("custom/relying-party-system.xml", HEADER + "<bean class=\"net.shibboleth.idp.saml.profile.config.StringConfigurationLookupStrategy\"/></beans>");
        assertEquals("UNRESOLVED", f.analyze().candidates.getFirst().accessState());
    }
    @Test void scalarFactoryAndDynamicValueAreNotAssumedInert() throws Exception {
        for (String bean : List.of("<bean class=\"java.lang.String\" factory-method=\"valueOf\"/>",
            "<bean class=\"java.lang.String\"><constructor-arg value=\"#{externalRule}\"/></bean>"))
            assertEquals("UNRESOLVED", assess(bean, null, SourceAnalyzerTest.ENTITY).status());
    }
}
