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

class MigrationBundleTest {
    @TempDir Path temp;
    ImportModel.Analysis fixture() {
        var a = new ImportModel.Analysis(); a.source = temp;
        var c = new ImportModel.Candidate(SourceAnalyzerTest.ENTITY, "source:1:2", "provider");
        c.bytes = SourceAnalyzerTest.METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.hash = SafeFiles.hash(c.bytes); c.attributes.add("uid"); c.facts.add("ACS=example");
        c.diagnostic("REVIEW", "SOURCE_TRUST_REVIEW_REQUIRED", c.source, "trust not verified", "verify source");
        a.candidates.add(c); return a;
    }
    @Test void roundTripsAndProtectsPermissions() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        var c = MigrationBundle.load(root).candidates.getFirst();
        assertEquals(java.util.Set.of("uid"), c.attributes); assertFalse(c.blocked());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("manifest.tsv"))));
        assertTrue(Files.exists(root.resolve("analysis.txt")));
    }
    @Test void refusesOverwrite() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        assertThrows(ImportModel.ImportException.class, () -> MigrationBundle.save(fixture(), root));
    }
    @Test void rejectsChangedBytes() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        try (var files = Files.list(root.resolve("metadata"))) { Files.writeString(files.findFirst().orElseThrow(), "<changed/>"); }
        assertThrows(ImportModel.ImportException.class, () -> MigrationBundle.load(root));
    }
    @Test void rejectsSymlinkAndTraversal() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        Path file;
        try (var files = Files.list(root.resolve("metadata"))) { file = files.findFirst().orElseThrow(); }
        Path outside = temp.resolve("outside.xml"); Files.move(file, outside); Files.createSymbolicLink(file, outside);
        assertThrows(Exception.class, () -> MigrationBundle.load(root));
        assertThrows(Exception.class, () -> SafeFiles.contained(root, "../outside.xml"));
    }
    @Test void rejectsUnknownManifestFormatAndRecord() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        Files.writeString(root.resolve("manifest.tsv"), "future-format\n");
        assertThrows(ImportModel.ImportException.class, () -> MigrationBundle.load(root));
    }
    @Test void controlCharactersAreNotInterpretedInReports() {
        assertEquals("x\\u001b[31m\\u000a", SafeFiles.display("x\u001b[31m\n"));
    }
    @Test void outputDefaultsToLauncherDirectoryNotCurrentDirectory() throws Exception {
        var o = ImportOptions.parse("--tool-home", temp.toString(), "--source-idp-home", "/opt/shibboleth-idp");
        assertEquals(temp.resolve("sp-migration"), o.output());
    }
    @Test void outputCannotFollowExistingSymlink() throws Exception {
        Path outside = temp.resolve("outside"); Files.writeString(outside, "untouched");
        Path link = temp.resolve("report"); Files.createSymbolicLink(link, outside);
        assertThrows(Exception.class, () -> SafeFiles.write(link, "bad".getBytes()));
        assertThrows(Exception.class, () -> SafeFiles.append(link, "bad"));
        assertEquals("untouched", Files.readString(outside));
    }
    @Test void heldDuplicateEntitiesRemainReadableInInventory() throws Exception {
        var a = fixture(); var first = a.candidates.getFirst();
        first.diagnostic("BLOCK", "DUPLICATE_ENTITY_ID", first.source, "duplicate", "select source manually");
        var second = new ImportModel.Candidate(first.entity, "other", "other-provider");
        second.bytes = first.bytes; second.hash = first.hash; second.name += "-2";
        second.diagnostic("BLOCK", "DUPLICATE_ENTITY_ID", second.source, "duplicate", "select source manually");
        a.candidates.add(second);
        var read = MigrationBundle.load(MigrationBundle.save(a, temp.resolve("bundle")));
        assertEquals(2, read.candidates.size()); assertTrue(read.candidates.stream().allMatch(ImportModel.Candidate::blocked));
    }
    @Test void outputCannotFollowSymlinkParentDirectory() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside")); Files.writeString(outside.resolve("ledger"), "untouched");
        Path link = temp.resolve("linked-parent"); Files.createSymbolicLink(link, outside);
        assertThrows(Exception.class, () -> SafeFiles.write(link.resolve("new-file"), "bad".getBytes()));
        assertThrows(Exception.class, () -> SafeFiles.append(link.resolve("ledger"), "bad"));
        assertEquals("untouched", Files.readString(outside.resolve("ledger")));
        assertFalse(Files.exists(outside.resolve("new-file")));
    }
    @Test void separatedAssessmentRoundTripsAndHasPrivateReadableReports() throws Exception {
        var a = fixture(); a.candidates.getFirst().access = new ImportModel.AccessAssessment("MANUAL_REQUIRED", java.util.List.of("AND(category AA OR BB, NOT ZZ)", "regex preserved"));
        Path root = MigrationBundle.save(a, temp.resolve("bundle"));
        var read = MigrationBundle.load(root);
        assertEquals(a.candidates.getFirst().access, read.candidates.getFirst().access);
        assertFalse(read.held()); assertTrue(read.accessPending());
        assertTrue(Files.readString(root.resolve("manifest.tsv")).startsWith(MigrationBundle.SEPARATED_HEADER));
        assertTrue(Files.readString(root.resolve("inventory.txt")).contains("access_control=MANUAL_REQUIRED"));
        assertTrue(Files.readString(root.resolve("access-control.txt")).contains("AND(category AA OR BB, NOT ZZ)"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("access-control.txt"))));
    }
    @Test void v3CannotOmitDuplicateOrOrphanItsAssessmentRecord() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        String original = Files.readString(root.resolve("manifest.tsv"));
        String row = original.lines().filter(line -> line.startsWith("A\t")).findFirst().orElseThrow();
        for (String changed : java.util.List.of(original.replace(row + "\n", ""), original + row + "\n",
            original + "A\t" + MigrationBundle.fields("orphan", "MANUAL_REQUIRED", "review") + "\n",
            original.replace(row, "A\t" + MigrationBundle.fields(fixture().candidates.getFirst().name, "UNSAFE_COMPLETE", "review")))) {
            Files.writeString(root.resolve("manifest.tsv"), changed);
            assertThrows(ImportModel.ImportException.class, () -> MigrationBundle.load(root));
        }
    }
    @Test void oldGenericBundleIsReadableButAuthorizationIsNeverAssumedComplete() throws Exception {
        Path root = MigrationBundle.save(fixture(), temp.resolve("bundle"));
        String original = Files.readString(root.resolve("manifest.tsv"));
        String legacy = original.lines().filter(line -> !line.startsWith("A\t")).collect(java.util.stream.Collectors.joining("\n"))
            .replace(MigrationBundle.SEPARATED_HEADER, MigrationBundle.HEADER) + "\n";
        Files.writeString(root.resolve("manifest.tsv"), legacy);
        var c = MigrationBundle.load(root).candidates.getFirst();
        assertFalse(c.blocked()); assertTrue(c.accessPending()); assertEquals("LEGACY_UNASSESSED", c.accessState());
    }
    @Test void multilineEvidenceRoundTripsWithoutSplittingAssessmentItems() throws Exception {
        var a = fixture(); a.candidates.getFirst().access = new ImportModel.AccessAssessment("MANUAL_REQUIRED", java.util.List.of("source pattern\nsecond line\tvalue"));
        Path root = MigrationBundle.save(a, temp.resolve("bundle"));
        assertEquals(a.candidates.getFirst().access, MigrationBundle.load(root).candidates.getFirst().access);
        assertTrue(Files.readString(root.resolve("access-control.txt")).contains("\\u000a"));
    }
    @Test void hundredsOfCandidatesExceedingOldManifestLimitRoundTripWithoutLosingHoldsOrAccess() throws Exception {
        var a = new ImportModel.Analysis(); a.source = temp;
        String evidence = "source authorization evidence: " + "predicate detail ".repeat(800);
        for (int i = 0; i < 248; i++) {
            var c = new ImportModel.Candidate("https://sp" + i + ".example.org/sp", "source.xml:" + i, "aggregate");
            c.diagnostic("BLOCK", "METADATA_EXTRACTION_UNSUPPORTED", c.source, "standalone metadata required", "review source");
            c.facts.add("source context: " + "metadata detail ".repeat(550));
            c.access = new ImportModel.AccessAssessment("UNRESOLVED", java.util.List.of(evidence));
            a.candidates.add(c);
        }
        Path root = MigrationBundle.save(a, temp.resolve("large-bundle"));
        long size = Files.size(root.resolve("manifest.tsv"));
        assertTrue(size > 4 * 1024 * 1024); assertTrue(size < MigrationBundle.MAX_MANIFEST_BYTES);
        assertEquals(SafeFiles.hash(Files.readAllBytes(root.resolve("manifest.tsv"))), MigrationBundle.fingerprint(root));
        var read = MigrationBundle.load(root);
        assertEquals(248, read.candidates.size()); assertTrue(read.held()); assertTrue(read.accessPending());
        for (int i = 0; i < a.candidates.size(); i++) {
            assertEquals(a.candidates.get(i).access, read.candidates.get(i).access);
            assertEquals(a.candidates.get(i).diagnostics, read.candidates.get(i).diagnostics);
            assertEquals(a.candidates.get(i).facts, read.candidates.get(i).facts);
        }
    }
    @Test void sharedBlockingDiagnosticsAreStoredOnceButStillBlockEveryCandidateOnLoad() throws Exception {
        var a = fixture();
        var shared = new ImportModel.Diagnostic("BLOCK", "SHARED_RESOURCE_UNRESOLVED", "service.xml:1", "unknown resource", "review");
        a.diagnostics.add(shared); a.candidates.getFirst().diagnostics.add(shared);
        Path root = MigrationBundle.save(a, temp.resolve("bundle"));
        String row = Files.readString(root.resolve("manifest.tsv")).lines().filter(l -> l.startsWith("S\t")).findFirst().orElseThrow();
        String localDiagnostics = new String(java.util.Base64.getDecoder().decode(row.split("\t", -1)[9]), java.nio.charset.StandardCharsets.UTF_8);
        String encodedCode = MigrationBundle.fields(shared.code());
        assertFalse(localDiagnostics.contains(encodedCode));
        var c = MigrationBundle.load(root).candidates.getFirst();
        assertTrue(c.blocked()); assertEquals(1, c.diagnostics.stream().filter(shared::equals).count());
        assertTrue(Files.readString(root.resolve("analysis.txt")).contains(shared.code()));
    }
    @Test void oversizedManifestIsRejectedByLoadingAndFingerprinting() throws Exception {
        Path root = Files.createDirectory(temp.resolve("bundle"));
        try (var file = java.nio.channels.FileChannel.open(root.resolve("manifest.tsv"), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            file.position(MigrationBundle.MAX_MANIFEST_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[] {10}));
        }
        for (var operation : java.util.List.<org.junit.jupiter.api.function.Executable>of(() -> MigrationBundle.load(root), () -> MigrationBundle.fingerprint(root))) {
            var ex = assertThrows(ImportModel.ImportException.class, operation);
            assertEquals(6, ex.exitCode); assertTrue(ex.getMessage().contains("32 MiB"));
            assertTrue(ex.getMessage().contains("size="));
        }
    }
    @Test void sharedHoldIsRestoredBeforeCheckingMissingMetadataOrManagedIntent() throws Exception {
        var a = fixture(); var c = a.candidates.getFirst(); c.bytes = null;
        var shared = new ImportModel.Diagnostic("BLOCK", "SHARED_SOURCE_FAILURE", "service.xml", "source unavailable", "review");
        a.diagnostics.add(shared); c.diagnostics.add(shared);
        Path root = MigrationBundle.save(a, temp.resolve("bundle"));
        var read = MigrationBundle.load(root).candidates.getFirst();
        assertTrue(read.blocked()); assertNull(read.bytes); assertTrue(read.diagnostics.contains(shared));
    }
}
