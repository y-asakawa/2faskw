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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedMigrationTest {
    @TempDir Path temp;
    Path source, target, run;
    ManagedFixture.Cli executor;
    ImportModel.Analysis analysis;
    ManagedMigration migration;
    void fixture(boolean policy, boolean enabled) throws Exception {
        source = temp.resolve("source"); target = temp.resolve("target"); run = Files.createDirectory(temp.resolve("run"));
        ManagedFixture.source(source); if (policy) ManagedFixture.access(source, enabled); ManagedFixture.base(target);
        analysis = ManagedFixture.analyze(source); assertFalse(analysis.held(), analysis.candidates.getFirst().diagnostics.toString());
        executor = new ManagedFixture.Cli(target); migration = create(run);
    }
    ManagedMigration create(Path directory) { return new ManagedMigration(target, directory, new ExistingCli(target, directory, executor, null), null); }
    @Test void expiredMetadataStopsManagedPreparationBeforeCliCalls() throws Exception {
        fixture(false, false);
        var c = analysis.candidates.getFirst();
        c.bytes = SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2000-01-01T00:00:00Z\" entityID=").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5, assertThrows(ImportModel.ImportException.class, () -> migration.prepare(analysis, analysis.candidates, false)).exitCode);
        assertTrue(executor.calls.isEmpty());
    }
    @Test void managedExecutionRechecksMetadataAfterPreparationAndBeforeWrites() throws Exception {
        fixture(false, false); migration.prepare(analysis, analysis.candidates, false); executor.calls.clear();
        var c = analysis.candidates.getFirst();
        c.bytes = SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2000-01-01T00:00:00Z\" entityID=").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5, assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, true, temp)).exitCode);
        assertTrue(executor.calls.isEmpty()); assertTrue(ManagedData.registry(target).isEmpty());
    }
    @Test void readOnlyPreparationAndDryRunNeverApply() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false);
        assertEquals(0, migration.execute(false, false, Files.createDirectory(temp.resolve("staging"))));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
        assertTrue(ManagedData.registry(target).isEmpty());
    }
    @Test void applyRequiresIsolationBeforeAnyWrite() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false);
        var ex = assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, false, temp));
        assertTrue(ex.getMessage().contains("ISOLATION")); assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }
    @Test void registerThenAccessThenVerifyUsesEntityConfirmation() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false);
        assertEquals(0, migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        var writes = executor.calls.stream().filter(c -> c.contains("--apply")).toList();
        assertEquals("add", writes.getFirst().getFirst()); assertEquals("access", writes.get(1).getFirst());
        assertEquals(SourceAnalyzerTest.ENTITY, writes.get(1).get(writes.get(1).indexOf("--confirm") + 1));
        assertTrue(ManagedData.policies(target).get("library").access().enabled());
        String result = Files.readString(run.resolve("managed-result.txt")); assertTrue(result.contains("CONFIG_VERIFIED"));
        assertTrue(result.contains("READY_FOR_CUTOVER=NOT_CONFIRMED"));
    }
    @Test void disabledPolicyIsRecreatedThenDisabledNotCleared() throws Exception {
        fixture(true, false); migration.prepare(analysis, analysis.candidates, false);
        migration.execute(true, true, Files.createDirectory(temp.resolve("staging")));
        var access = ManagedData.policies(target).get("library").access(); assertFalse(access.enabled()); assertEquals(List.of("AA"), access.allow().get("businessCategory"));
        assertTrue(executor.calls.stream().anyMatch(c -> c.size() > 1 && c.subList(0, 2).equals(List.of("access", "disable")) && c.contains("--apply")));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("clear")));
    }
    @Test void accessFailureResumesWithoutReaddingAndKeepsBoundPlan() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false); String plan = migration.planText("bundlehash");
        executor.failApply = "access set";
        assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals(1, ManagedData.registry(target).size()); assertTrue(ManagedData.policies(target).isEmpty());
        executor.failApply = ""; executor.calls.clear(); ManagedMigration resumed = create(Files.createDirectory(temp.resolve("resume")));
        resumed.prepare(analysis, analysis.candidates, true); assertEquals(plan, resumed.planText("bundlehash"));
        resumed.execute(true, true, Files.createDirectory(temp.resolve("staging-resume")));
        assertFalse(executor.calls.stream().anyMatch(c -> c.getFirst().equals("add"))); assertTrue(ManagedData.policies(target).get("library").access().enabled());
    }
    @Test void disableFailureResumesOnlyDisable() throws Exception {
        fixture(true, false); migration.prepare(analysis, analysis.candidates, false); executor.failApply = "access disable";
        assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertTrue(ManagedData.policies(target).get("library").access().enabled());
        executor.failApply = ""; executor.calls.clear(); var resumed = create(Files.createDirectory(temp.resolve("resume")));
        resumed.prepare(analysis, analysis.candidates, true); resumed.execute(true, true, Files.createDirectory(temp.resolve("staging-resume")));
        assertFalse(executor.calls.stream().anyMatch(c -> (c.getFirst().equals("add") || c.size() > 1 && c.subList(0, 2).equals(List.of("access", "set"))) && c.contains("--apply")));
        assertFalse(ManagedData.policies(target).get("library").access().enabled());
    }
    @Test void globalMfaMismatchStopsBeforeWrites() throws Exception {
        fixture(false, false); ManagedFixture.text(target, ManagedData.DIR + "mfa-policy.properties", "graphicalmatrix.mfa.default=bypass\n");
        assertThrows(ImportModel.ImportException.class, () -> migration.prepare(analysis, analysis.candidates, false));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }
    @Test void nameConflictNeverUpdatesOrAdopts() throws Exception {
        fixture(false, false); var c = analysis.candidates.getFirst();
        ManagedFixture.registry(target, List.of(ManagedFixture.entry("library", "https://other.example.org/sp", c.hash, "none", "inherit", "metadata/library.xml")));
        assertThrows(ImportModel.ImportException.class, () -> migration.prepare(analysis, analysis.candidates, false));
        assertFalse(executor.calls.stream().anyMatch(calls -> calls.contains("--apply") || calls.contains("adopt") || calls.contains("update")));
    }
    @Test void sameEntityRequiresBoundResumeEvenWhenIdentical() throws Exception {
        fixture(false, false); migration.prepare(analysis, analysis.candidates, false); migration.execute(true, true, Files.createDirectory(temp.resolve("staging")));
        var fresh = create(Files.createDirectory(temp.resolve("fresh")));
        assertThrows(ImportModel.ImportException.class, () -> fresh.prepare(analysis, analysis.candidates, false));
    }
    @Test void targetMutationAfterPrecheckStopsBeforeFirstWrite() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false);
        ManagedFixture.text(target, "conf/attribute-resolver.xml", "<changed/>");
        assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }
    @Test void releaseAndAccessDependencyProducesOneReleaseApprovalWithoutDowngrade() throws Exception {
        fixture(true, true); var c = analysis.candidates.getFirst(); c.attributes.add("businessCategory");
        c.managed = new ManagedData.Intent("shared", "Shared profile", "force", List.of(), c.managed.approvals().stream()
            .map(a -> a.id().equals("businessCategory") ? new ManagedData.Approval(a.id(), "release-approved", a.classification(), a.purpose()) : a).toList(), c.managed.access(), true);
        var doc = ManagedData.json(target, ManagedData.DIR + "attribute-catalog.json", 1024 * 1024);
        for (Object raw : StrictJson.array(doc, "attributes")) if (StrictJson.object(raw).get("id").equals("businessCategory")) StrictJson.object(raw).put("governance", "candidate");
        ManagedFixture.json(target, ManagedData.DIR + "attribute-catalog.json", doc);
        migration.prepare(analysis, analysis.candidates, false); migration.execute(true, true, Files.createDirectory(temp.resolve("staging")));
        var approvals = executor.calls.stream().filter(args -> args.getFirst().equals("attributes") && args.get(1).equals("approve") && args.contains("--apply")).toList();
        assertEquals(1, approvals.size()); assertEquals("release", approvals.getFirst().get(approvals.getFirst().indexOf("--usage") + 1));
        assertEquals("release-approved", ManagedData.catalog(target).attributes().get("businessCategory").governance());
    }
    @Test void allTargetPreconditionsCheckedBeforeAnyApply() throws Exception {
        fixture(true, true); ManagedFixture.text(target, ManagedData.DIR + "sp-management.properties", "graphicalmatrix.sp.access.enabled=false\n");
        assertThrows(ImportModel.ImportException.class, () -> migration.prepare(analysis, analysis.candidates, false));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }
    @Test void alteredExistingAccessRulesAreNotOverwrittenOnResume() throws Exception {
        fixture(true, true); migration.prepare(analysis, analysis.candidates, false); executor.failApply = "access set";
        assertThrows(ImportModel.ImportException.class, () -> migration.execute(true, true, Files.createDirectory(temp.resolve("staging"))));
        ManagedFixture.json(target, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of(ManagedFixture.policy("library", SourceAnalyzerTest.ENTITY, true, "BB"))));
        executor.calls.clear(); var resumed = create(Files.createDirectory(temp.resolve("resume")));
        assertThrows(ImportModel.ImportException.class, () -> resumed.prepare(analysis, analysis.candidates, true));
        assertFalse(executor.calls.stream().anyMatch(c -> c.contains("--apply")));
    }
    @Test void sharedProfileCreatedOnceBeforeBothSpRegistrations() throws Exception {
        fixture(false, false); var first = analysis.candidates.getFirst();
        first.managed = new ManagedData.Intent("shared", "Shared", "force", List.of(), first.managed.approvals(), null, true);
        var second = new ImportModel.Candidate("https://second.example.org/sp", "source", "provider"); second.name = "second";
        second.bytes = new String(first.bytes, java.nio.charset.StandardCharsets.UTF_8).replace(first.entity, second.entity).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        second.hash = SafeFiles.hash(second.bytes); second.attributes.addAll(first.attributes); second.managed = first.managed; analysis.candidates.add(second);
        migration.prepare(analysis, analysis.candidates, false); migration.execute(true, true, Files.createDirectory(temp.resolve("staging")));
        assertEquals(1, executor.calls.stream().filter(c -> c.size() > 2 && c.subList(0, 3).equals(List.of("attributes", "profile", "create")) && c.contains("--apply")).count());
        assertEquals(2, ManagedData.registry(target).size());
    }
    @Test void commasEqualsSpacesRemainSingleExactAccessValues() throws Exception {
        fixture(true, true); var c = analysis.candidates.getFirst();
        var policy = new ManagedData.Access(true, Map.of("businessCategory", List.of(" AA,BB=CC ")), Map.of());
        c.managed = new ManagedData.Intent(c.managed.profile(), "", "force", List.of(), c.managed.approvals(), policy, true);
        migration.prepare(analysis, analysis.candidates, false); migration.execute(true, true, Files.createDirectory(temp.resolve("staging")));
        assertEquals(List.of(" AA,BB=CC "), ManagedData.policies(target).get("library").access().allow().get("businessCategory"));
    }
}
