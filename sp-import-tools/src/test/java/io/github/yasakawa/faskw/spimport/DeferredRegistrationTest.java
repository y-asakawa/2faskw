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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredRegistrationTest {
    @TempDir Path temp;

    Candidate candidate() {
        Candidate c = new Candidate(SourceAnalyzerTest.ENTITY, "metadata.xml", "provider");
        c.bytes = SourceAnalyzerTest.METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.hash = SafeFiles.hash(c.bytes); c.attributes.addAll(List.of("uid", "mail"));
        return c;
    }
    ExistingCli.Plan plan(Candidate c) { return new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false); }
    ExistingCli.Response response(List<String> cmd, Candidate c, String profile) {
        String args = String.join(" ", cmd.subList(1, cmd.size()));
        String output = switch (args) {
            case "init" -> "metadata_provider=present\nattribute_block=present\n";
            case "attributes profile list" -> "none 0 builtin OK -\nuid-mail 0 builtin OK uid,mail\n";
            case "attributes profile show none" -> "source=builtin\nstatus=OK\nattributes=\n";
            default -> args.startsWith("status ") ? "status=NOT_FOUND entity_id=" + c.entity + "\n"
                : args.startsWith("verify ") ? "entity_id=OK expected=" + c.entity + " actual=" + c.entity
                    + "\nmetadata_sha256=OK expected=" + c.hash + " actual=" + c.hash + "\nattribute_profile=" + profile + "\nmfa_profile=force\nstatus=ACTIVE\nresult=OK\n"
                : args.equals("mfa show") ? "managed_policy_drift=OK\nsp=" + c.name + " status=ACTIVE mfa=force cidrs=\n"
                : "entity_id=" + c.entity + "\nmetadata_sha256=" + c.hash + "\n";
        };
        return new ExistingCli.Response(0, output);
    }

    @Test void onlyAttributeHoldsAreDeferredAndSourceIsNotMutated() throws Exception {
        Analysis source = new Analysis(); Candidate c = candidate(); source.candidates.add(c);
        c.diagnostic("BLOCK", "ATTRIBUTE_CONDITION_UNSUPPORTED", "filter.xml:10", "reason=VALUE_RESTRICTED_RELEASE; AA only", "retain value restriction");
        c.diagnostic("ERROR", "ATTRIBUTE_POLICY_UNRESOLVED", "filter.xml:11", "unknown policy", "review policy");
        Analysis view = DeferredRegistration.view(source); Candidate deferred = view.candidates.getFirst();
        assertTrue(c.blocked()); assertFalse(deferred.blocked());
        assertArrayEquals(c.bytes, deferred.bytes); assertNotSame(c.bytes, deferred.bytes);
        assertEquals(c.attributes, deferred.attributes);
        assertTrue(deferred.facts.stream().anyMatch(f -> f.contains("BLOCK ATTRIBUTE_CONDITION_UNSUPPORTED")));
        assertEquals("BLOCK", c.diagnostics.getFirst().severity());
        assertTrue(ReviewPresentation.overview(view, true, "all").contains("属性後設定mode"));
        assertTrue(ReviewPresentation.detail(deferred, 1, true).contains("元の送信属性（未移行）"));
        assertTrue(ReviewPresentation.overview(view, false, "all").contains("Deferred attribute mode"));
        c.diagnostic("BLOCK", "METADATA_EXPIRED", "metadata.xml:1", "expired", "replace metadata");
        assertTrue(DeferredRegistration.view(source).candidates.getFirst().blocked());
    }
    @Test void globalErrorsAndManagedModeCannotBeBypassed() throws Exception {
        Analysis source = new Analysis(); Candidate c = candidate(); source.candidates.add(c);
        Diagnostic d = new Diagnostic("BLOCK", "ATTRIBUTE_POLICY_UNRESOLVED", "config.xml", "global failure", "fix config");
        source.diagnostics.add(d); c.diagnostics.add(d);
        assertTrue(DeferredRegistration.view(source).held());
        assertTrue(DeferredRegistration.view(source).candidates.getFirst().blocked());
        source.mode = "2faskw";
        assertEquals(2, assertThrows(ImportException.class, () -> DeferredRegistration.view(source)).exitCode);
    }
    @Test void canonicalBundleRemainsHeldAfterViewAndReportsCannotApproveIt() throws Exception {
        SourceAnalyzerTest fixture = new SourceAnalyzerTest(); fixture.home = temp.resolve("source"); fixture.fixture();
        fixture.write("conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("xsi:type=\"ANY\"", "xsi:type=\"Script\""));
        Analysis source = ManagedSourceAnalyzer.analyze(ImportOptions.parse("--source-idp-home", fixture.home.toString()));
        Path bundle = MigrationBundle.save(source, temp.resolve("bundle"));
        String before = MigrationBundle.fingerprint(bundle);
        assertFalse(DeferredRegistration.view(MigrationBundle.load(bundle)).candidates.getFirst().blocked());
        assertTrue(MigrationBundle.load(bundle).candidates.getFirst().blocked());
        Files.writeString(bundle.resolve("inventory.txt"), "APPROVED");
        assertTrue(MigrationBundle.load(bundle).candidates.getFirst().blocked());
        assertEquals(before, MigrationBundle.fingerprint(bundle));
    }
    @Test void prepareFixesNoneWithoutDiscoveryApprovalsProfilesOrAttributePrompt() throws Exception {
        Candidate c = candidate(); List<List<String>> calls = new ArrayList<>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return response(cmd, c, "none"); }, message -> {
            assertFalse(message.contains("attribute profile") || message.contains("Classification") || message.contains("Release purpose"));
            return message.contains("REVIEW SOURCE") ? "REVIEW SOURCE" : "";
        }, true);
        ExistingCli.Plan p = cli.prepare(List.of(c), false).getFirst();
        assertEquals("none", p.profile()); assertTrue(p.approvals().isEmpty()); assertFalse(p.createProfile());
        assertTrue(calls.stream().noneMatch(cmd -> cmd.contains("discover") || cmd.contains("approve") || cmd.contains("create") || cmd.contains("--apply")));
        assertTrue(Files.readString(temp.resolve("attribute-migration-todo.txt")).contains("source_attributes=[mail, uid]"));
    }
    @Test void missingOrNonemptyNoneFailsInsteadOfCreatingProfile() throws Exception {
        Candidate c = candidate();
        for (String value : List.of("status=OK\nsource=builtin\nattributes=mail\n", "status=OK\nsource=managed\nattributes=\n", "status=OK\nsource=builtin\n")) {
            ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> cmd.contains("show") && cmd.contains("none")
                ? new ExistingCli.Response(0, value) : response(cmd, c, "none"), null, true);
            assertEquals(5, assertThrows(ImportException.class, () -> cli.prepare(List.of(c), false)).exitCode);
        }
    }
    @Test void deferredDryRunAndApplyOnlyRegisterSpAndAlwaysRemainPending() throws Exception {
        Candidate c = candidate();
        for (boolean apply : List.of(false, true)) {
            Path run = Files.createDirectory(temp.resolve("run-" + apply));
            List<List<String>> calls = new ArrayList<>();
            ExistingCli cli = new ExistingCli(temp, run, (cmd, idp) -> { calls.add(cmd); return response(cmd, c, "none"); }, message -> "DEFER ATTRIBUTES ISOLATED", true);
            if (apply) cli.authorizeDeferredAttributes(true, true);
            assertEquals(11, cli.executeStaged(List.of(plan(c)), apply, Files.createDirectory(run.resolve("staging"))));
            assertEquals(apply ? 4 : 1, calls.size());
            assertTrue(calls.stream().noneMatch(cmd -> cmd.contains("approve") || cmd.contains("create") || cmd.contains("access")));
            String result = Files.readString(run.resolve("result.txt"));
            assertTrue(result.contains(apply ? "REGISTERED_ATTRIBUTES_PENDING" : "DRY_RUN_ATTRIBUTES_PENDING"));
            assertFalse(result.contains("REGISTERED_AND_VERIFIED"));
            assertTrue(Files.readString(run.resolve("next-steps.txt")).contains("Keep target isolated"));
        }
    }
    @Test void bothIsolationFlagsAndTypedAcknowledgementAreRequired() throws Exception {
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { fail("no command"); return null; }, message -> "DEFER ATTRIBUTES ISOLATED", true);
        assertEquals(5, assertThrows(ImportException.class, () -> cli.authorizeDeferredAttributes(true, false)).exitCode);
        assertEquals(5, assertThrows(ImportException.class, () -> cli.authorizeDeferredAttributes(false, true)).exitCode);
        ExistingCli cancelled = new ExistingCli(temp, temp, (cmd, idp) -> null, message -> "YES", true);
        assertEquals(4, assertThrows(ImportException.class, () -> cancelled.authorizeDeferredAttributes(true, true)).exitCode);
        assertEquals(5, assertThrows(ImportException.class, () -> cli.executeStaged(List.of(plan(candidate())), true, temp)).exitCode);
    }
    @Test void invalidDeferredPlanCannotWriteAnything() throws Exception {
        Candidate c = candidate();
        for (ExistingCli.Plan bad : List.of(new ExistingCli.Plan(c, c.name, "uid-mail", "force", "", Map.of(), false, false),
                new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), true, false),
                new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of("mail", List.of("attributes", "approve", "mail")), false, false))) {
            ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { fail("no command"); return null; }, message -> "DEFER ATTRIBUTES ISOLATED", true);
            cli.authorizeDeferredAttributes(true, true);
            assertEquals(6, assertThrows(ImportException.class, () -> cli.executeStaged(List.of(bad), true, temp)).exitCode);
        }
    }
    @Test void resumedSpIsNotReaddedAndPhaseTwoProfileChangeIsNotOverwritten() throws Exception {
        Candidate c = candidate(); List<List<String>> calls = new ArrayList<>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return response(cmd, c, "none"); }, null, true);
        var p = new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, true);
        assertEquals(11, cli.executeStaged(List.of(p), false, Files.createDirectory(temp.resolve("first-stage"))));
        assertEquals(2, calls.size()); assertTrue(calls.stream().noneMatch(cmd -> cmd.contains("add")));
        ExistingCli changed = new ExistingCli(temp, temp, (cmd, idp) -> response(cmd, c, "uid-mail"), null, true);
        assertEquals(8, assertThrows(ImportException.class, () -> changed.executeStaged(List.of(p), false, Files.createDirectory(temp.resolve("second-stage")))).exitCode);
    }
    @Test void allMetadataIsCheckedBeforeAnyWriteEvenWhenOnlySecondHasExpired() throws Exception {
        Candidate first = candidate(), second = candidate();
        second.bytes = SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2000-01-01T00:00:00Z\" entityID=").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { fail("no commands before complete validity preflight"); return null; }, null);
        assertEquals(5, assertThrows(ImportException.class, () -> cli.executeStaged(List.of(plan(first), plan(second)), true, temp)).exitCode);
    }
    @Test void expiryDuringCliDryRunStopsBeforeApply() throws Exception {
        Candidate c = candidate();
        c.bytes = SourceAnalyzerTest.METADATA.replace("entityID=", "validUntil=\"2030-01-01T00:00:01Z\" entityID=").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.hash = SafeFiles.hash(c.bytes);
        Instant[] now = {Instant.parse("2030-01-01T00:00:00Z")};
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        List<List<String>> calls = new ArrayList<>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); now[0] = now[0].plusSeconds(2); return response(cmd, c, "none"); },
            message -> "DEFER ATTRIBUTES ISOLATED", true, clock);
        cli.authorizeDeferredAttributes(true, true);
        assertEquals(5, assertThrows(ImportException.class, () -> cli.executeStaged(List.of(plan(c)), true, temp)).exitCode);
        assertEquals(1, calls.size()); assertFalse(calls.getFirst().contains("--apply"));
    }
    @Test void optionCannotDeferInAnalysisOrApplyWithoutIsolation() throws Exception {
        assertThrows(ImportException.class, () -> ImportOptions.parse("--source-idp-home", temp.toString(), "--defer-attributes").validate());
        assertThrows(ImportException.class, () -> ImportOptions.parse("--bundle-dir", temp.toString(), "--target-idp-home", temp.toString(), "--defer-attributes", "--apply").validate());
        ImportOptions.parse("--bundle-dir", temp.toString(), "--target-idp-home", temp.toString(), "--defer-attributes", "--registration-only", "--confirm-isolated-target", "--apply").validate();
    }
}
