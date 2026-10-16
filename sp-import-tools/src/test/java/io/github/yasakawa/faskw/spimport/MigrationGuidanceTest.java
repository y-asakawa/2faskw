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

import static io.github.yasakawa.faskw.spimport.ImportModel.*;
import static io.github.yasakawa.faskw.spimport.MigrationGuidance.Category.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationGuidanceTest {
    @TempDir Path temp;

    private Candidate candidate(String name) {
        Candidate c = new Candidate("https://" + name + ".example.org/sp", "/source/metadata.xml:1:2", "local");
        c.name = name; c.access = new AccessAssessment("NONE_OBSERVED", List.of()); return c;
    }
    private Diagnostic hold(String code, String reason) {
        return new Diagnostic("BLOCK", code, "/source/config.xml:10:3", "reason=" + reason + "; actual evidence", "original remedy");
    }
    @Test void sourceIdProblemsAreNotClassifiedAsManualRelease() {
        for (String reason : List.of("ATTRIBUTE_ID_WHITESPACE", "ATTRIBUTE_ID_MISSING", "ATTRIBUTE_ID_INVALID"))
            assertEquals(SOURCE_CONFIG_REVIEW, MigrationGuidance.category(hold("ATTRIBUTE_CONDITION_UNSUPPORTED", reason)));
        for (String reason : List.of("ATTRIBUTE_VALUE_RULE_MANUAL", "ATTRIBUTE_DENY_RULE_MANUAL"))
            assertEquals(MANUAL_ATTRIBUTE_RELEASE, MigrationGuidance.category(hold("ATTRIBUTE_CONDITION_UNSUPPORTED", reason)));
        assertEquals(ADDITIONAL_REVIEW, MigrationGuidance.category(hold("ATTRIBUTE_CONDITION_UNSUPPORTED", "ATTRIBUTE_RULE_UNRESOLVED")));
    }
    @Test void expiredInvalidAndUnsupportedMetadataHaveDifferentWorkRoutes() {
        assertEquals(SOURCE_METADATA_REFRESH, MigrationGuidance.category(hold("METADATA_EFFECTIVE_EXPIRED", "VALID_UNTIL_EXPIRED")));
        assertEquals(SOURCE_METADATA_REVIEW, MigrationGuidance.category(hold("METADATA_SOURCE_FILTER_REJECTED", "VALIDITY_WINDOW_EXCEEDED")));
        assertEquals(SOURCE_METADATA_REVIEW, MigrationGuidance.category(hold("METADATA_SOURCE_FILTER_REJECTED", "ROOT_SIGNATURE_MISSING")));
        assertEquals(ADDITIONAL_REVIEW, MigrationGuidance.category(hold("METADATA_EFFECTIVE_EXPIRED", "CACHE_DURATION_UNSUPPORTED")));
        assertEquals(SOURCE_CONFIG_REVIEW, MigrationGuidance.category(hold("METADATA_SOURCE_FILTER_REJECTED", "MAX_VALIDITY_INTERVAL_NEGATIVE")));
    }
    @Test void oldFreeTextNeverInventsANewReasonOrManualReleaseApproval() {
        Diagnostic legacy = new Diagnostic("BLOCK", "ATTRIBUTE_CONDITION_UNSUPPORTED", "source:1", "attributeID=jasn Value value=staff", "remedy");
        assertEquals("", legacy.reason()); assertEquals(ADDITIONAL_REVIEW, MigrationGuidance.category(legacy));
        assertEquals("", hold("ATTRIBUTE_CONDITION_UNSUPPORTED", "bad\nreason").reason());
        assertEquals("", new Diagnostic("BLOCK", "x", "source", "reason=UNTERMINATED", "r").reason());
    }
    @Test void categoryCountsDeduplicatePerCandidateAndRetainOverlappingWork() {
        Analysis a = new Analysis(); Candidate c = candidate("library");
        c.diagnostics.add(hold("METADATA_EFFECTIVE_EXPIRED", "VALID_UNTIL_EXPIRED"));
        c.diagnostics.add(hold("METADATA_SOURCE_FILTER_REJECTED", "VALID_UNTIL_EXPIRED"));
        c.diagnostics.add(hold("ATTRIBUTE_CONDITION_UNSUPPORTED", "ATTRIBUTE_VALUE_RULE_MANUAL"));
        a.candidates.add(c); String out = MigrationGuidance.summary(a, true);
        assertTrue(out.contains("1件：元メタデータ更新")); assertFalse(out.contains("2件：元メタデータ更新"));
        assertTrue(out.contains("1件：属性送信条件の手動移行")); assertTrue(out.contains("分類間は重複"));
    }
    @Test void manualWorkNamesTheSPAndTestsValueReleaseSeparatelyFromLoginAuthorization() {
        Candidate c = candidate("library"); c.diagnostics.add(hold("ATTRIBUTE_CONDITION_UNSUPPORTED", "ATTRIBUTE_VALUE_RULE_MANUAL"));
        String out = MigrationGuidance.work(c, true, true);
        for (String token : List.of(c.entity, "attribute-filter.xml", "filtered AACLI", "一致値・不一致値・空値・複数値", "ログインの許可/拒否条件は別", "既存CLIの別手順", "全保留", "保留を維持"))
            assertTrue(out.contains(token), token);
        assertTrue(c.blocked()); assertTrue(c.attributes.isEmpty());
    }
    @Test void orderKeepsSourceWorkAheadOfExtractionAndQualification() {
        Candidate c = candidate("library"); c.diagnostics.add(hold("METADATA_EXTRACTION_UNSUPPORTED", ""));
        c.diagnostics.add(hold("METADATA_EFFECTIVE_EXPIRED", "VALID_UNTIL_EXPIRED"));
        String out = MigrationGuidance.work(c, false, true);
        assertTrue(out.indexOf("[SOURCE_METADATA_REFRESH]") < out.indexOf("[EXTRACTION_REVIEW]"));
        assertTrue(out.indexOf("[EXTRACTION_REVIEW]") < out.indexOf("[TARGET_QUALIFICATION]"));
        assertTrue(out.contains("Not every SP must become a static snapshot"));
    }
    @Test void authorizationEvidenceIsSPScopedAndUnresolvedAssessmentStillNeedsInvestigation() {
        Candidate c = candidate("library"); c.access = new AccessAssessment("UNRESOLVED", List.of("context_check_condition=businessCategory AA"));
        assertTrue(MigrationGuidance.categories(c).contains(MANUAL_AUTHORIZATION));
        assertTrue(MigrationGuidance.categories(c).contains(ADDITIONAL_REVIEW));
        String out = MigrationGuidance.work(c, false, true);
        assertTrue(out.contains(c.entity)); assertTrue(out.contains("businessCategory AA")); assertTrue(out.contains("isolated"));
        assertFalse(MigrationGuidance.work(candidate("other"), false, true).contains("businessCategory AA"));
    }
    @Test void readyAndManagedWorkDoesNotPretendAuthorizationWasCopied() {
        Candidate c = candidate("managed"); c.access = null;
        c.managed = new ManagedData.Intent("uid-mail", "test", "force", List.of(), List.of(), null, false);
        assertEquals(java.util.Set.of(TARGET_QUALIFICATION), MigrationGuidance.categories(c));
        assertFalse(c.blocked()); assertTrue(MigrationGuidance.work(c, false, true).contains("compare managed policies"));
        assertFalse(MigrationGuidance.work(c, false, true).contains("Registration remains HELD"));
    }
    @Test void reportRoundTripIsLocalizedOptionalAndNeverAnApprovalSource() throws Exception {
        SourceAnalyzerTest f = new SourceAnalyzerTest(); f.home = temp.resolve("source"); f.fixture();
        f.write("conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("attributeID=\"uid\"", "attributeID=\"uid \""));
        Analysis a = f.analyze(); Candidate c = a.candidates.getFirst(); assertTrue(c.blocked());
        Path bundle = MigrationBundle.save(a, temp.resolve("bundle"), false);
        String text = Files.readString(bundle.resolve("migration-actions.txt"));
        assertTrue(text.contains("[SOURCE_CONFIG_REVIEW]")); assertTrue(text.contains("attributeID=\"uid \""));
        assertFalse(text.codePoints().anyMatch(cp -> cp >= 0x3000 && cp <= 0x9fff));
        assertEquals(text, MigrationGuidance.report(MigrationBundle.load(bundle), false));
        String fingerprint = MigrationBundle.fingerprint(bundle);
        Files.writeString(bundle.resolve("migration-actions.txt"), "Approved, unblocked");
        assertEquals(fingerprint, MigrationBundle.fingerprint(bundle)); assertTrue(MigrationBundle.load(bundle).candidates.getFirst().blocked());
        Files.delete(bundle.resolve("migration-actions.txt"));
        assertTrue(MigrationBundle.load(bundle).candidates.getFirst().blocked());
        assertTrue(MigrationGuidance.report(a, true).contains("SP別移行作業"));
    }
}
