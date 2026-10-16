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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceSnapshotTest {
    @TempDir Path temp;
    SourceAnalyzerTest fixture() throws Exception {
        var f = new SourceAnalyzerTest(); f.home = temp.resolve("source"); f.fixture(); return f;
    }
    String aggregate(String attrs, String children) {
        return "<md:EntitiesDescriptor xmlns:md=\"" + SecureXml.MD + "\" xmlns:ds=\"" + MetadataSnapshots.DS + "\" "
            + attrs + ">" + children + "</md:EntitiesDescriptor>";
    }
    void filters(SourceAnalyzerTest f, String xml) throws Exception {
        f.write("conf/metadata-providers.xml", SourceAnalyzerTest.PROVIDERS.replace("/>", ">" + xml + "</MetadataProvider>"));
    }
    @Test void extractsOnlyWhenExplicitlyRequestedAndKeepsOriginalEvidence() throws Exception {
        var f = fixture(); String source = aggregate("Name=\"local\"", SourceAnalyzerTest.METADATA);
        f.write("metadata/library.xml", source);
        assertTrue(f.analyze().candidates.getFirst().blocked());
        var a = f.analyze("--extract-aggregate"); var c = a.candidates.getFirst();
        assertFalse(c.blocked(), c.diagnostics.toString()); assertTrue(c.metadataReviewRequired());
        assertEquals(SourceAnalyzerTest.ENTITY, SecureXml.parse(c.bytes, "test").attr("entityID"));
        assertArrayEquals(source.getBytes(StandardCharsets.UTF_8), a.metadataSources.get(c.sourceMetadataHash));
        var root = MigrationBundle.save(a, temp.resolve("bundle"));
        var read = MigrationBundle.load(root);
        assertEquals(c.sourceMetadataHash, read.candidates.getFirst().sourceMetadataHash);
        assertArrayEquals(c.bytes, read.candidates.getFirst().bytes);
        assertTrue(read.candidates.getFirst().metadataReviewRequired());
        assertTrue(Files.readString(root.resolve("analysis.txt")).contains("UNSIGNED"));
    }
    @Test void originalEvidenceTamperingOrOmissionIsRejected() throws Exception {
        var f = fixture(); f.write("metadata/library.xml", aggregate("", SourceAnalyzerTest.METADATA));
        var a = f.analyze("--extract-aggregate"); var root = MigrationBundle.save(a, temp.resolve("bundle"));
        Path evidence = root.resolve("evidence/metadata-sources/" + a.candidates.getFirst().sourceMetadataHash + ".xml");
        byte[] original = Files.readAllBytes(evidence);
        Files.writeString(evidence, "<wrong/>"); assertThrows(Exception.class, () -> MigrationBundle.load(root));
        Files.write(evidence, original);
        String manifest = Files.readString(root.resolve("manifest.tsv"));
        Files.writeString(root.resolve("manifest.tsv"), manifest.lines().filter(l -> !l.startsWith("X\t"))
            .collect(java.util.stream.Collectors.joining("\n")) + "\n");
        assertThrows(Exception.class, () -> MigrationBundle.load(root));
    }
    @Test void nestedGroupsKeepTightestValidityNamespacesMixedTextAndNoTransferredSignature() throws Exception {
        String early = Instant.now().plusSeconds(3600).toString();
        String late = Instant.now().plusSeconds(7200).toString();
        String entity = SourceAnalyzerTest.METADATA.replace("<SPSSODescriptor", "<Extensions><ext:Notice xsi:type=\"ext:Label\">before<ext:Em>inside</ext:Em>after</ext:Notice></Extensions><SPSSODescriptor");
        String source = aggregate("xmlns:ext=\"urn:example:extension\" xmlns:xsi=\"" + SecureXml.XSI + "\" validUntil=\"" + early + "\"",
            "<ds:Signature><ds:SignatureValue>unverified</ds:SignatureValue></ds:Signature>"
            + "<md:EntitiesDescriptor Name=\"subgroup\" validUntil=\"" + late + "\">" + entity + "</md:EntitiesDescriptor>");
        var snapshot = new MetadataSnapshots(source.getBytes(StandardCharsets.UTF_8), "test").extract(0);
        var root = SecureXml.parse(snapshot.bytes(), "snapshot");
        assertEquals(early, root.attr("validUntil")); assertEquals("urn:example:extension", root.namespaces.get("ext"));
        assertTrue(root.descendants("Signature").isEmpty());
        String xml = new String(snapshot.bytes(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("before<ext:Em>inside</ext:Em>after"));
        assertTrue(snapshot.facts().toString().contains("source_signature_count=1"));
    }
    @Test void capsSnapshotExpiryByInheritedCacheDuration() throws Exception {
        String source = aggregate("cacheDuration=\"PT30M\" validUntil=\"" + Instant.now().plusSeconds(7200) + "\"", SourceAnalyzerTest.METADATA);
        var root = SecureXml.parse(new MetadataSnapshots(source.getBytes(StandardCharsets.UTF_8), "test").extract(0).bytes(), "out");
        assertEquals("PT30M", root.attr("cacheDuration"));
        assertTrue(Instant.parse(root.attr("validUntil")).isBefore(Instant.now().plusSeconds(1801)));
    }
    @Test void expiredOrAmbiguousParentContextRemainsHeld() throws Exception {
        var f = fixture();
        for (String source : java.util.List.of(
            aggregate("validUntil=\"2000-01-01T00:00:00Z\"", SourceAnalyzerTest.METADATA),
            aggregate("xml:base=\"https://metadata.example.org/\"", SourceAnalyzerTest.METADATA),
            aggregate("cacheDuration=\"P1M\"", SourceAnalyzerTest.METADATA),
            aggregate("", "<md:Extensions><x xmlns=\"urn:unknown\"/></md:Extensions>" + SourceAnalyzerTest.METADATA),
            aggregate("custom=\"opaque\"", SourceAnalyzerTest.METADATA))) {
            f.write("metadata/library.xml", source);
            var c = f.analyze("--extract-aggregate").candidates.getFirst();
            assertTrue(c.blocked()); assertNull(c.bytes);
        }
    }
    @Test void failedParentValidityShowsActualValueClockAndParentLocation() throws Exception {
        var f = fixture();
        f.write("metadata/library.xml", aggregate("Name=\"federation\" validUntil=\"2000-01-01T00:00:00Z\"", "\n" + SourceAnalyzerTest.METADATA));
        var a = f.analyze("--extract-aggregate"); var c = a.candidates.getFirst();
        var d = c.diagnostics.stream().filter(v -> v.code().equals("METADATA_EFFECTIVE_EXPIRED")).findFirst().orElseThrow();
        assertTrue(c.blocked()); assertNull(c.bytes);
        assertTrue(d.detail().contains("reason=VALID_UNTIL_EXPIRED"));
        assertTrue(d.detail().contains("scope=EntitiesDescriptor; name=federation"));
        assertTrue(d.detail().contains("validUntil=2000-01-01T00:00:00Z"));
        assertTrue(d.detail().contains("checked_at=")); assertTrue(d.location().matches(".*library.xml:1:[0-9]+"));
        assertTrue(c.facts.stream().anyMatch(v -> v.contains("metadata_context=EntitiesDescriptor") && v.contains("2000-01-01")));
        var read = MigrationBundle.load(MigrationBundle.save(a, temp.resolve("invalid-parent-bundle"))).candidates.getFirst();
        assertEquals(MigrationBundle.detail(c), MigrationBundle.detail(read));
    }
    @Test void nestedGroupFailureReportsTheFailingGroupNotTheEntityOrRoot() throws Exception {
        var f = fixture();
        f.write("metadata/library.xml", aggregate("Name=\"root\"", "\n<md:EntitiesDescriptor Name=\"inner\" cacheDuration=\"P1M\">\n"
            + SourceAnalyzerTest.METADATA + "</md:EntitiesDescriptor>"));
        var c = f.analyze("--extract-aggregate").candidates.getFirst();
        var d = c.diagnostics.stream().filter(v -> v.code().equals("METADATA_EFFECTIVE_EXPIRED")).findFirst().orElseThrow();
        assertTrue(d.detail().contains("reason=CACHE_DURATION_UNSUPPORTED"));
        assertTrue(d.detail().contains("name=inner")); assertTrue(d.detail().contains("cacheDuration=P1M"));
        assertTrue(d.location().matches(".*library.xml:2:[0-9]+")); assertTrue(c.blocked());
    }
    @Test void validityDiagnosticsDistinguishExpiredMalformedAndNonpositiveCache() {
        Instant checkedAt = Instant.parse("2026-08-01T00:00:00Z");
        assertEquals("VALID_UNTIL_EXPIRED", MetadataSnapshots.validityProblem(checkedAt.toString(), "", checkedAt));
        assertEquals("VALID_UNTIL_MALFORMED", MetadataSnapshots.validityProblem("invalid", "", checkedAt));
        assertEquals("CACHE_DURATION_NONPOSITIVE", MetadataSnapshots.validityProblem("", "PT0S", checkedAt));
        assertEquals("CACHE_DURATION_NONPOSITIVE", MetadataSnapshots.validityProblem("", "-PT1S", checkedAt));
        assertEquals("CACHE_DURATION_UNSUPPORTED", MetadataSnapshots.validityProblem("", "P1M", checkedAt));
        assertNull(MetadataSnapshots.validityProblem("", "PT1H", checkedAt));
    }
    @Test void standardSignatureFilterIsExplicitReviewNotClaimedVerification() throws Exception {
        var f = fixture(); filters(f, "<MetadataFilter xsi:type=\"SignatureValidation\" certificateFile=\"/approved/metadata.crt\"/>");
        var c = f.analyze().candidates.getFirst();
        assertFalse(c.blocked()); assertTrue(c.metadataReviewRequired());
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.code().equals("METADATA_SIGNATURE_VALIDATION_REQUIRED")));
    }
    @Test void explicitlyRequiredMissingSignatureIsRejected() throws Exception {
        var f = fixture(); filters(f, "<MetadataFilter xsi:type=\"SignatureValidation\" requireSignedRoot=\"true\"/>");
        var c = f.analyze().candidates.getFirst(); assertTrue(c.blocked());
        assertTrue(c.diagnostics.stream().anyMatch(d -> d.code().equals("METADATA_SOURCE_FILTER_REJECTED")));
    }
    @Test void requiredValidityRejectsMissingExpiredExcessiveOrUnknownDuration() throws Exception {
        var f = fixture(); filters(f, "<MetadataFilter xsi:type=\"RequiredValidUntil\" maxValidityInterval=\"PT1H\"/>");
        for (String validity : java.util.List.of("", "2000-01-01T00:00:00Z", Instant.now().plusSeconds(7200).toString(), "invalid")) {
            f.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"" + validity + "\" entityID="));
            assertTrue(f.analyze().candidates.getFirst().blocked());
        }
        f.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"" + Instant.now().plusSeconds(1800) + "\" entityID="));
        assertFalse(f.analyze().candidates.getFirst().blocked());
        filters(f, "<MetadataFilter xsi:type=\"RequiredValidUntil\" maxValidityInterval=\"P1M\"/>");
        assertTrue(f.analyze().candidates.getFirst().blocked());
    }
    @Test void requiredValidityReportsExactFailureFilterValueAndAllowedWindow() throws Exception {
        var f = fixture(); filters(f, "<MetadataFilter xsi:type=\"RequiredValidUntil\" maxValidityInterval=\"PT1H\"/>");
        var values = java.util.Map.of("", "VALID_UNTIL_MISSING", "2000-01-01T00:00:00Z", "VALID_UNTIL_EXPIRED",
            "invalid", "VALID_UNTIL_MALFORMED", Instant.now().plusSeconds(7200).toString(), "VALIDITY_WINDOW_EXCEEDED");
        for (var entry : values.entrySet()) {
            f.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"" + entry.getKey() + "\" entityID="));
            var c = f.analyze().candidates.getFirst();
            var d = c.diagnostics.stream().filter(v -> v.code().equals("METADATA_SOURCE_FILTER_REJECTED")).findFirst().orElseThrow();
            assertTrue(c.blocked()); assertTrue(d.detail().contains("reason=" + entry.getValue()), d.detail());
            assertTrue(d.detail().contains("maxValidityInterval=PT1H")); assertTrue(d.detail().contains("checked_at="));
            assertTrue(d.detail().contains("metadata-providers.xml:"));
            assertTrue(d.detail().contains("validUntil=" + (entry.getKey().isEmpty() ? "<missing>" : entry.getKey())));
            if (entry.getValue().equals("VALIDITY_WINDOW_EXCEEDED")) assertTrue(d.detail().contains("allowed_until="));
        }
        f.write("metadata/library.xml", SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"" + Instant.now().plusSeconds(1800) + "\" entityID="));
        filters(f, "<MetadataFilter xsi:type=\"RequiredValidUntil\" maxValidityInterval=\"P1M\"/>");
        assertTrue(f.analyze().candidates.getFirst().diagnostics.stream().anyMatch(d -> d.detail().contains("reason=MAX_VALIDITY_INTERVAL_UNSUPPORTED")));
    }
    @Test void chainedStandardFiltersAndSpOnlyRoleSnapshotAreReviewed() throws Exception {
        var f = fixture();
        String source = aggregate("validUntil=\"" + Instant.now().plusSeconds(3600) + "\"",
            "<ds:Signature/>" + SourceAnalyzerTest.METADATA.replace("</EntityDescriptor>", "<IDPSSODescriptor/></EntityDescriptor>"));
        f.write("metadata/library.xml", source);
        filters(f, "<MetadataFilter xsi:type=\"ChainingFilter\" xmlns:md=\"" + SecureXml.MD + "\">"
            + "<MetadataFilter xsi:type=\"SignatureValidation\" requireSignedRoot=\"true\"/>"
            + "<MetadataFilter xsi:type=\"RequiredValidUntil\" maxValidityInterval=\"7200\"/>"
            + "<MetadataFilter xsi:type=\"EntityRoleWhiteList\"><RetainedRole>md:SPSSODescriptor</RetainedRole></MetadataFilter></MetadataFilter>");
        var c = f.analyze("--extract-aggregate").candidates.getFirst();
        assertFalse(c.blocked(), c.diagnostics.toString()); assertTrue(c.metadataReviewRequired());
        assertTrue(SecureXml.parse(c.bytes, "out").descendants("IDPSSODescriptor").isEmpty());
        assertEquals(java.util.Set.of("mail", "uid"), c.attributes);
    }
    @Test void foreignMutatingAndAmbiguousFiltersRemainHeld() throws Exception {
        var f = fixture();
        for (String filter : java.util.List.of(
            "<MetadataFilter xsi:type=\"EntityAttributes\"/>",
            "<MetadataFilter xsi:type=\"SignatureValidation\" customOption=\"true\"/>",
            "<MetadataFilter xmlns:x=\"urn:custom\" xsi:type=\"x:SignatureValidation\"/>",
            "<MetadataFilter xsi:type=\"EntityRole\"><RetainedRole>SPSSODescriptor</RetainedRole></MetadataFilter>",
            "<MetadataFilter xsi:type=\"SignatureValidation\" requireSignedRoot=\"${setting}\"/>",
            "<MetadataFilter xsi:type=\"SignatureValidation\"><CustomTrustEngine/></MetadataFilter>")) {
            filters(f, filter); assertTrue(f.analyze("--extract-aggregate").candidates.getFirst().blocked());
        }
    }
    @Test void extractionCannotReadExternalEntitiesAndDoesNotIncludeNonSpEntities() throws Exception {
        assertThrows(Exception.class, () -> new MetadataSnapshots("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>".getBytes(), "test"));
        var f = fixture(); f.write("metadata/library.xml", aggregate("", SourceAnalyzerTest.METADATA + "<md:EntityDescriptor entityID=\"urn:idp\"><md:IDPSSODescriptor/></md:EntityDescriptor>"));
        assertEquals(1, f.analyze("--extract-aggregate").candidates.size());
    }
    @Test void supportsFullInventoryAndKeepsOriginalXmlOnlyOnce() throws Exception {
        var f = fixture(); StringBuilder entities = new StringBuilder();
        for (int i = 0; i < 248; i++) entities.append(SourceAnalyzerTest.METADATA.replace("library.example.org", "sp" + i + ".example.org"));
        f.write("metadata/library.xml", aggregate("", entities.toString()));
        var a = f.analyze("--extract-aggregate"); assertEquals(248, a.candidates.size()); assertEquals(1, a.metadataSources.size());
        assertTrue(a.candidates.stream().noneMatch(ImportModel.Candidate::blocked));
        var read = MigrationBundle.load(MigrationBundle.save(a, temp.resolve("bundle")));
        assertEquals(248, read.candidates.size()); assertEquals(1, read.metadataSources.size());
        assertTrue(read.candidates.stream().allMatch(ImportModel.Candidate::metadataReviewRequired));
    }
    @Test void olderV3WithoutSnapshotsRemainsReadable() throws Exception {
        var f = fixture(); var root = MigrationBundle.save(f.analyze(), temp.resolve("bundle"));
        Path manifest = root.resolve("manifest.tsv");
        Files.writeString(manifest, Files.readString(manifest).replace(MigrationBundle.SEPARATED_HEADER, MigrationBundle.LEGACY_SEPARATED_HEADER));
        assertFalse(MigrationBundle.load(root).candidates.getFirst().blocked());
    }
    @Test void sourceOnlyExtractionOptionIsRejectedForBundleOrManagedMode() throws Exception {
        assertThrows(ImportModel.ImportException.class, () -> ImportOptions.parse("--bundle-dir", "b", "--extract-aggregate").validate());
        assertThrows(ImportModel.ImportException.class, () -> ImportOptions.parse("--source-idp-home", "s", "--source-type", "2faskw", "--extract-aggregate").validate());
    }
    @Test void inheritedPrefixShadowingAndLanguageUseNearestDefinitions() throws Exception {
        String source = aggregate("xmlns:x=\"urn:outer\" xml:lang=\"ja\"", SourceAnalyzerTest.METADATA.replace("entityID=",
            "xmlns:x=\"urn:inner\" xml:lang=\"en\" entityID="));
        var root = SecureXml.parse(new MetadataSnapshots(source.getBytes(StandardCharsets.UTF_8), "test").extract(0).bytes(), "out");
        assertEquals("urn:inner", root.namespaces.get("x"));
        assertEquals("en", root.attr("{" + javax.xml.XMLConstants.XML_NS_URI + "}lang"));
    }
    @Test void invalidOriginalReferencesAndUnrelatedOriginalEntityAreRejected() throws Exception {
        var f = fixture(); f.write("metadata/library.xml", aggregate("", SourceAnalyzerTest.METADATA));
        var a = f.analyze("--extract-aggregate"); Path root = MigrationBundle.save(a, temp.resolve("bundle"));
        Path manifest = root.resolve("manifest.tsv"); String text = Files.readString(manifest);
        String x = text.lines().filter(l -> l.startsWith("X\t")).findFirst().orElseThrow();
        String r = text.lines().filter(l -> l.startsWith("R\t")).findFirst().orElseThrow();
        for (String changed : java.util.List.of(text + x + "\n", text + r + "\n", text.replace(r + "\n", ""),
            text.replace(x, "X\t" + MigrationBundle.fields("orphan", a.candidates.getFirst().sourceMetadataHash)))) {
            Files.writeString(manifest, changed); assertThrows(Exception.class, () -> MigrationBundle.load(root));
        }
        byte[] other = aggregate("", SourceAnalyzerTest.METADATA.replace(SourceAnalyzerTest.ENTITY, "urn:unrelated")).getBytes(StandardCharsets.UTF_8);
        String otherHash = SafeFiles.hash(other);
        Files.write(root.resolve("evidence/metadata-sources/" + otherHash + ".xml"), other);
        String changed = text.replace(MigrationBundle.fields(a.candidates.getFirst().sourceMetadataHash), MigrationBundle.fields(otherHash));
        Files.writeString(manifest, changed); assertThrows(Exception.class, () -> MigrationBundle.load(root));
    }
    @Test void unknownFilterSecretsAreNotCopiedIntoDiagnostics() throws Exception {
        var f = fixture(); filters(f, "<MetadataFilter xsi:type=\"Custom\" password=\"DO_NOT_EXPORT\"><Secret>DO_NOT_EXPORT</Secret></MetadataFilter>");
        var a = f.analyze(); assertTrue(a.candidates.getFirst().blocked());
        assertFalse(a.candidates.getFirst().diagnostics.toString().contains("DO_NOT_EXPORT"));
        assertFalse(a.evidence.toString().contains("DO_NOT_EXPORT"));
    }
}
