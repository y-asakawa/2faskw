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
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPresentationTest {
    @TempDir Path temp;

    private Candidate candidate(String name) {
        Candidate c = new Candidate("https://" + name + ".example.org/sp", "/source/metadata.xml", "provider");
        c.name = name;
        c.access = new AccessAssessment("NONE_OBSERVED", List.of());
        return c;
    }
    private void block(Candidate c, String code) {
        c.diagnostic("BLOCK", code, "/source/config.xml:12:3", "Original cause for " + code, "Original remedy for " + code);
    }

    @Test void summaryCountsUniqueCandidatesPerCodeAndKeepsOtherCauses() {
        Analysis a = new Analysis(); Candidate held = candidate("held"), ready = candidate("ready");
        block(held, "METADATA_EXTRACTION_UNSUPPORTED"); block(held, "METADATA_EFFECTIVE_EXPIRED");
        block(held, "METADATA_EFFECTIVE_EXPIRED"); block(held, "ATTRIBUTE_CONDITION_UNSUPPORTED");
        a.candidates.addAll(List.of(held, ready));
        String out = ReviewPresentation.overview(a, true, "all");
        assertTrue(out.contains("検出したSP：2")); assertTrue(out.contains("確認後に登録可能：1"));
        assertTrue(out.contains("登録保留：1")); assertTrue(out.contains("1件：メタデータの期限"));
        assertTrue(out.contains("主な原因：メタデータの期限"));
        assertTrue(out.contains("ATTRIBUTE_CONDITION_UNSUPPORTED")); assertTrue(out.contains("件数は重複"));
        assertFalse(out.contains("親の集合メタデータが期限切れです"));
    }
    @Test void reviewChecklistIncludesSourceSignatureRefreshAttributesAuthorizationAndCutover() {
        Candidate c = candidate("library"); c.attributes.addAll(List.of("uid", "mail"));
        c.access = new AccessAssessment("MANUAL_REQUIRED", List.of("context_check_condition=regex(mail)"));
        for (String code : List.of("METADATA_AGGREGATE_EXTRACTED", "METADATA_REFRESH_BEHAVIOR_CHANGED"))
            c.diagnostic("REVIEW", code, "source:1", "evidence", "remedy");
        String checks = String.join("\n", ReviewPresentation.checks(c, true));
        for (String expected : List.of("/source/metadata.xml", "trust engine", "validUntil", "filter順序", "mail,uid",
                "AACLI", "送信承認", "snapshot再取得", "許可ユーザーと拒否ユーザー", "NameID", "新IdP entityID", "MFA"))
            assertTrue(checks.contains(expected), expected);
        assertTrue(checks.contains("hashだけで署名確認の代用にしない"));
    }
    @Test void conditionalRefreshCheckAndNoRestrictionGuaranteeAreAccurate() {
        Candidate c = candidate("static"); String checks = String.join("\n", ReviewPresentation.checks(c, true));
        assertFalse(checks.contains("自動更新は引き継がれない"));
        assertTrue(checks.contains("制限なしとは保証されない"));
    }
    @Test void managedChecklistDoesNotClaimItsPoliciesAreNeverMigrated() {
        Analysis a = new Analysis(); a.mode = "2faskw";
        Candidate c = candidate("managed"); c.access = null;
        c.managed = new ManagedData.Intent("uid-mail", "test", "force", List.of(), List.of(), null, false);
        a.candidates.add(c);
        String out = ReviewPresentation.overview(a, true, "all");
        assertTrue(out.contains("管理SPのpolicy移行も対象")); assertTrue(out.contains("MFA=force"));
        assertTrue(out.contains("global MFAはコピーされない"));
        assertFalse(out.contains("元IdPのアクセス制御は自動移行しません"));
    }
    @Test void filtersKeepOriginalNumbersAndNeverChangeEligibility() {
        Analysis a = new Analysis(); Candidate held = candidate("held"), ready = candidate("ready");
        block(held, "METADATA_SOURCE_FILTER_REJECTED"); a.candidates.addAll(List.of(held, ready));
        String heldView = ReviewPresentation.overview(a, true, "held"), readyView = ReviewPresentation.overview(a, true, "ready");
        assertTrue(heldView.contains("[1] held")); assertFalse(heldView.contains("[2] ready"));
        assertTrue(readyView.contains("[2] ready")); assertFalse(readyView.contains("[1] held"));
        assertTrue(held.blocked()); assertFalse(ready.blocked());
    }
    @Test void detailPreservesEveryOriginalCauseLocationAndEvidence() {
        Candidate c = candidate("held"); block(c, "METADATA_EFFECTIVE_EXPIRED"); block(c, "NEW_UNKNOWN_CODE");
        c.metadata = "metadata/1-held.xml"; c.hash = "a".repeat(64); c.sourceMetadataHash = "b".repeat(64);
        String out = ReviewPresentation.detail(c, 8, true);
        assertTrue(out.contains("[8] held")); assertTrue(out.contains("Original cause for NEW_UNKNOWN_CODE"));
        assertTrue(out.contains("Original remedy for NEW_UNKNOWN_CODE")); assertTrue(out.contains("/source/config.xml:12:3"));
        assertTrue(out.contains("evidence/metadata-sources/" + c.sourceMetadataHash + ".xml"));
        assertTrue(out.contains("すべて解消するまで登録不可"));
    }
    @Test void englishOutputContainsNoJapaneseGuidance() {
        Analysis a = new Analysis(); Candidate c = candidate("library"); a.candidates.add(c);
        String out = ReviewPresentation.overview(a, false, "all");
        assertTrue(out.contains("Checks before registration")); assertTrue(out.contains("[1] library"));
        assertFalse(out.codePoints().anyMatch(cp -> cp >= 0x3000 && cp <= 0x9fff));
    }
    @Test void interactiveFiltersDetailsInvalidNumbersAndSelectionRemainReadOnly() throws Exception {
        Analysis a = new Analysis(); Candidate held = candidate("held"), ready = candidate("ready");
        block(held, "METADATA_EFFECTIVE_EXPIRED"); a.candidates.addAll(List.of(held, ready));
        var answers = new ArrayDeque<>(List.of("held", "ready", "all", "d 99999999999999999999", "d 0", "d 1", "1", "2,2", "2"));
        List<Candidate> selected = SpImportMain.select(a, ImportOptions.parse("--source-idp-home", temp.toString()), message -> answers.remove());
        assertEquals(List.of(ready), selected); assertTrue(answers.isEmpty()); assertTrue(held.blocked());
    }
    @Test void allHeldStillAllowsDetailAndCancelWithoutSelecting() {
        Analysis a = new Analysis(); Candidate c = candidate("held"); block(c, "METADATA_EFFECTIVE_EXPIRED"); a.candidates.add(c);
        var answers = new ArrayDeque<>(List.of("d 1", "ready", "1", "q"));
        var ex = assertThrows(ImportException.class, () -> SpImportMain.select(a,
            ImportOptions.parse("--source-idp-home", temp.toString()), message -> answers.remove()));
        assertEquals(4, ex.exitCode); assertTrue(answers.isEmpty());
    }
    @Test void entitySelectionConstraintCannotBeBypassedByViews() throws Exception {
        Analysis a = new Analysis(); Candidate first = candidate("first"), second = candidate("second"); a.candidates.addAll(List.of(first, second));
        var answers = new ArrayDeque<>(List.of("ready", "1", "2"));
        assertEquals(List.of(second), SpImportMain.select(a, ImportOptions.parse("--source-idp-home", temp.toString(), "--entity-id", second.entity), message -> answers.remove()));
        assertTrue(answers.isEmpty());
    }
    @Test void bundleChecklistIsLocalizedAndDoesNotBecomeApprovalEvidence() throws Exception {
        var fixture = new SourceAnalyzerTest(); fixture.home = temp.resolve("source"); fixture.fixture();
        Analysis a = fixture.analyze();
        Path en = MigrationBundle.save(a, temp.resolve("en"), false), ja = MigrationBundle.save(a, temp.resolve("ja"), true);
        assertTrue(Files.readString(en.resolve("review-checklist.txt")).contains("Checks before registration"));
        assertTrue(Files.readString(ja.resolve("review-checklist.txt")).contains("登録前に確認すること"));
        String fingerprint = MigrationBundle.fingerprint(ja);
        Files.writeString(ja.resolve("review-checklist.txt"), "Verified: yes");
        assertEquals(fingerprint, MigrationBundle.fingerprint(ja));
        assertEquals(MigrationBundle.detail(a.candidates.getFirst()), MigrationBundle.detail(MigrationBundle.load(ja).candidates.getFirst()));
    }
    @Test void overviewPrintsCommonChecksOnceAndKeepsIndividualAttributesAndConditions() {
        Analysis a = new Analysis(); Candidate first = candidate("first"), second = candidate("second");
        first.attributes.add("uid"); second.attributes.add("mail");
        first.access = new AccessAssessment("MANUAL_REQUIRED", List.of("context_check_condition=ATTRIBUTE_AND(businessCategory IN [AA, BB])"));
        a.candidates.addAll(List.of(first, second));
        String out = ReviewPresentation.overview(a, true, "all");
        String common = "信頼鍵/trust engineによる原本署名の別途検証";
        assertEquals(1, out.split(java.util.regex.Pattern.quote(common), -1).length - 1);
        assertTrue(out.contains("全SP共通")); assertTrue(out.contains("送信予定属性：uid")); assertTrue(out.contains("送信予定属性：mail"));
        assertTrue(out.contains("businessCategory IN [AA, BB]"));
        String detail = ReviewPresentation.detail(first, 1, true);
        assertTrue(detail.contains("trust engine")); assertTrue(detail.contains("AACLI")); assertTrue(detail.contains("NameID"));
    }
    @Test void overviewExposesActualBlockingValuesAndUnsupportedConditionNotJustCodes() {
        Analysis a = new Analysis(); Candidate c = candidate("held");
        c.diagnostic("BLOCK", "METADATA_EFFECTIVE_EXPIRED", "/source/metadata.xml:1:2",
            "reason=CACHE_DURATION_UNSUPPORTED; scope=EntitiesDescriptor; cacheDuration=P1M; checked_at=2026-08-01T00:00:00Z", "remedy");
        c.diagnostic("BLOCK", "ATTRIBUTE_CONDITION_UNSUPPORTED", "/source/filter.xml:5:2",
            "policy=staff; attributeID=mail; condition=DenyValueRule value=guest", "remedy");
        a.candidates.add(c); String out = ReviewPresentation.overview(a, true, "held");
        for (String value : List.of("cacheDuration=P1M", "checked_at=2026-08-01", "policy=staff", "attributeID=mail", "DenyValueRule value=guest", "/source/filter.xml:5:2"))
            assertTrue(out.contains(value), value);
        assertTrue(out.contains("主な原因：cacheDurationを固定期間として安全に扱えません"));
        assertTrue(c.blocked());
    }
}
