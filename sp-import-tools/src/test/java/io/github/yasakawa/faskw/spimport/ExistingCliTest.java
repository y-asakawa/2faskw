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

class ExistingCliTest {
    @TempDir Path temp;
    ImportModel.Candidate candidate() {
        var c = new ImportModel.Candidate(SourceAnalyzerTest.ENTITY, "source", "provider");
        c.bytes = SourceAnalyzerTest.METADATA.getBytes(java.nio.charset.StandardCharsets.UTF_8); c.hash = SafeFiles.hash(c.bytes); return c;
    }
    @Test void dryRunNeverAddsApplyAndUsesArgumentArray() throws Exception {
        var c = candidate(); var commands = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> {
            commands.add(cmd);
            return new ExistingCli.Response(0, "entity_id=" + c.entity + "\nmetadata_sha256=" + c.hash + "\n");
        }, null);
        assertEquals(0, cli.execute(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false)), false));
        assertEquals(1, commands.size()); assertFalse(commands.getFirst().contains("--apply"));
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("DRY_RUN_OK"));
    }
    @Test void metadataHashMismatchStopsBeforeApply() throws Exception {
        var c = candidate();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> new ExistingCli.Response(0, "entity_id=" + c.entity + "\nmetadata_sha256=" + "0".repeat(64) + "\n"), null);
        var ex = assertThrows(ImportModel.ImportException.class, () -> cli.execute(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false)), false));
        assertEquals(6, ex.exitCode);
    }
    @Test void failureStopsRemainingSpAndRetainsRawReason() throws Exception {
        var c = candidate(); var calls = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return new ExistingCli.Response(2, "ERROR: ACS host is not approved\n"); }, null);
        var p = new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false);
        assertThrows(ImportModel.ImportException.class, () -> cli.execute(List.of(p, p), false));
        assertEquals(1, calls.size()); assertTrue(Files.readString(temp.resolve("cli-output.txt")).contains("ACS host"));
    }
    @Test void resumedExistingSpIsVerifiedAndNeverReadded() throws Exception {
        var c = candidate(); var commands = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> {
            commands.add(cmd);
            if (cmd.get(1).equals("mfa")) return new ExistingCli.Response(0, "managed_policy_drift=OK\nsp=" + c.name + " status=ACTIVE mfa=force cidrs=\n");
            return new ExistingCli.Response(0, "entity_id=OK expected=" + c.entity + " actual=" + c.entity
                + "\nmetadata_sha256=OK expected=" + c.hash + " actual=" + c.hash + "\nattribute_profile=none\nmfa_profile=force\nstatus=ACTIVE\nresult=OK\n");
        }, null);
        assertEquals(0, cli.execute(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, true)), false));
        assertEquals("verify", commands.getFirst().get(1)); assertFalse(commands.stream().anyMatch(cmd -> cmd.contains("add")));
    }
    @Test void failedVerifyRequiresManualCheckNotReadd() throws Exception {
        var c = candidate();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> new ExistingCli.Response(0, "result=OK\n"), null);
        var ex = assertThrows(ImportModel.ImportException.class, () -> cli.execute(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, true)), false));
        assertEquals(8, ex.exitCode);
    }
    @Test void newApprovalsDoNotCauseDryRunWithNoneSubstitution() throws Exception {
        var c = candidate(); c.attributes.add("mail"); var calls = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return new ExistingCli.Response(0, ""); }, null);
        var plan = new ExistingCli.Plan(c, c.name, "imported-mail", "force", "", Map.of("mail", List.of("attributes", "approve", "mail")), true, false);
        assertEquals(5, cli.execute(List.of(plan), false)); assertTrue(calls.isEmpty());
    }
    @Test void rejectsAmbiguousCliDigest() {
        assertThrows(ImportModel.ImportException.class, () -> ExistingCli.key("metadata_sha256=a\nmetadata_sha256=b\n", "metadata_sha256"));
    }
    @Test void processPreservesSemicolonAsDataRatherThanShellCode() throws Exception {
        var response = ExistingCli.process(List.of("/usr/bin/printf", "%s", "a; touch NEVER_EXECUTE"), temp);
        assertEquals("a; touch NEVER_EXECUTE", response.output()); assertFalse(Files.exists(temp.resolve("NEVER_EXECUTE")));
    }
    @Test void resumedSpWithDifferentCidrsIsNotAcceptedOrReadded() throws Exception {
        var c = candidate(); var commands = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> {
            commands.add(cmd);
            if (cmd.get(1).equals("mfa")) return new ExistingCli.Response(0, "managed_policy_drift=OK\nsp=" + c.name + " status=ACTIVE mfa=sp-cidr-bypass cidrs=10.0.0.0/8\n");
            return new ExistingCli.Response(0, "entity_id=OK expected=" + c.entity + " actual=" + c.entity
                + "\nmetadata_sha256=OK expected=" + c.hash + " actual=" + c.hash + "\nattribute_profile=none\nmfa_profile=sp-cidr-bypass\nstatus=ACTIVE\nresult=OK\n");
        }, null);
        var ex = assertThrows(ImportModel.ImportException.class, () -> cli.execute(List.of(new ExistingCli.Plan(c, c.name, "none", "sp-cidr-bypass", "192.168.0.0/16", Map.of(), false, true)), false));
        assertEquals(8, ex.exitCode); assertEquals(2, commands.size());
        assertFalse(commands.stream().anyMatch(cmd -> cmd.contains("add")));
    }
    @Test void targetPreparationUsesRealTextCliContractsWithoutWriting() throws Exception {
        var c = candidate(); var commands = new ArrayList<List<String>>();
        ExistingCli cli = new ExistingCli(temp, temp, (cmd, idp) -> {
            commands.add(cmd); String key = String.join(" ", cmd.subList(1, cmd.size()));
            String output = switch (key) {
                case "init" -> "metadata_provider=present\nattribute_block=present\n";
                case "mfa show" -> "managed_policy_drift=OK\n";
                case "attributes profile list" -> "PROFILE REVISION SOURCE STATUS ATTRIBUTES\nnone 0 builtin OK -\n";
                case "attributes discover" -> "ATTRIBUTE RESOLUTION SAML MAPPING GOVERNANCE USED BY\n";
                case "attributes profile show none" -> "attributes=\nstatus=OK\n";
                default -> "status=NOT_FOUND entity_id=" + c.entity + "\n";
            };
            return new ExistingCli.Response(0, output);
        }, null);
        var plan = cli.prepare(List.of(c), false).getFirst();
        assertEquals("none", plan.profile()); assertEquals("inherit", plan.mfa());
        assertFalse(plan.createProfile()); assertFalse(plan.alreadyExists());
        assertFalse(commands.stream().anyMatch(cmd -> cmd.contains("--apply")));
    }
    private ExistingCli.Response success(List<String> command, ImportModel.Candidate c, String name, String profile) {
        if (command.get(1).equals("verify")) return new ExistingCli.Response(0, "entity_id=OK expected=" + c.entity + " actual=" + c.entity
            + "\nmetadata_sha256=OK expected=" + c.hash + " actual=" + c.hash + "\nattribute_profile=" + profile + "\nmfa_profile=force\nstatus=ACTIVE\nresult=OK\n");
        if (command.get(1).equals("mfa")) return new ExistingCli.Response(0, "managed_policy_drift=OK\nsp=" + name + " status=ACTIVE mfa=force cidrs=\n");
        return new ExistingCli.Response(0, "entity_id=" + c.entity + "\nmetadata_sha256=" + c.hash + "\nresult=APPLY_OK\n");
    }
    @Test void appliesApprovalProfileAndDigestBoundAddBeforeVerification() throws Exception {
        var c = candidate(); c.attributes.add("mail"); var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "imported-mail"); }, null);
        var plan = new ExistingCli.Plan(c, c.name, "imported-mail", "force", "", Map.of("mail", List.of("attributes", "approve", "mail", "--usage", "release")), true, false);
        Path staging = Files.createDirectory(temp.resolve("staging"));
        assertEquals(0, cli.executeStaged(List.of(plan), true, staging));
        assertEquals(7, calls.size());
        assertTrue(calls.get(0).contains("--confirm"));
        assertFalse(calls.get(1).contains("--apply")); assertTrue(calls.get(2).contains("--apply"));
        assertFalse(calls.get(3).contains("--apply"));
        assertEquals(c.hash, calls.get(4).get(calls.get(4).indexOf("--approve-sha256") + 1));
        assertEquals("verify", calls.get(5).get(1));
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("REGISTERED_AND_VERIFIED"));
    }
    @Test void partialApplyRetainsVerifiedFirstSpAndStopsAfterSecondFailure() throws Exception {
        var c = candidate(); var second = candidate(); second.name += "-2";
        var third = candidate(); third.name += "-3"; var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> {
            calls.add(cmd);
            if (cmd.contains(second.name)) return new ExistingCli.Response(1, "ERROR: second metadata is not approved\n");
            return success(cmd, c, c.name, "none");
        }, null);
        var plans = List.of(c, second, third).stream().map(v -> new ExistingCli.Plan(v, v.name, "none", "force", "", Map.<String,List<String>>of(), false, false)).toList();
        assertThrows(ImportModel.ImportException.class, () -> cli.executeStaged(plans, true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals(5, calls.size()); assertFalse(calls.stream().anyMatch(cmd -> cmd.contains(third.name) || cmd.contains("remove")));
        String result = Files.readString(temp.resolve("result.txt"));
        assertTrue(result.contains("REGISTERED_AND_VERIFIED")); assertTrue(result.contains("STOPPED"));
    }

    @Test void pendingAuthorizationStopsBeforeAnyApprovalProfileOrAddWrite() throws Exception {
        var c = candidate(); c.access = new ImportModel.AccessAssessment("UNRESOLVED", List.of("custom source rule"));
        var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "imported-mail"); }, null);
        var plan = new ExistingCli.Plan(c, c.name, "imported-mail", "force", "", Map.of("mail", List.of("attributes", "approve", "mail")), true, false);
        var ex = assertThrows(ImportModel.ImportException.class, () -> cli.executeStaged(List.of(plan), true, Files.createDirectory(temp.resolve("staging"))));
        assertEquals(5, ex.exitCode); assertTrue(calls.isEmpty());
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("SOURCE_ACCESS_CONTROL_PENDING"));
    }
    @Test void pendingAuthorizationDryRunIsPermittedButNeverClaimsCompletion() throws Exception {
        var c = candidate(); c.access = new ImportModel.AccessAssessment("MANUAL_REQUIRED", List.of("allow AA or BB"));
        var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "none"); }, null);
        assertEquals(10, cli.executeStaged(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false)), false,
            Files.createDirectory(temp.resolve("staging"))));
        assertTrue(calls.stream().noneMatch(cmd -> cmd.contains("--apply")));
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("DRY_RUN_ACCESS_PENDING"));
        assertTrue(Files.readString(temp.resolve("access-control-status.txt")).contains("allow AA or BB"));
    }
    @Test void isolatedRegistrationOnlyApplyStillReturnsPendingAndNeverWritesAccessRules() throws Exception {
        var c = candidate(); c.access = new ImportModel.AccessAssessment("MANUAL_REQUIRED", List.of("allow AA or BB"));
        var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "none"); }, message -> "REGISTER ONLY ISOLATED");
        cli.authorizeRegistrationOnly(List.of(c), true, true);
        assertEquals(10, cli.executeStaged(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false)), true,
            Files.createDirectory(temp.resolve("staging"))));
        assertTrue(calls.stream().anyMatch(cmd -> cmd.contains("--apply")));
        assertTrue(calls.stream().noneMatch(cmd -> cmd.contains("access")));
        String result = Files.readString(temp.resolve("result.txt"));
        assertTrue(result.contains("REGISTERED_ACCESS_PENDING")); assertFalse(result.contains("REGISTERED_AND_VERIFIED"));
    }
    @Test void requiresBothFlagsAndTypedConfirmationAndDetectsChangedAssessment() throws Exception {
        var c = candidate(); c.access = new ImportModel.AccessAssessment("MANUAL_REQUIRED", List.of("rule"));
        var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "none"); }, message -> "REGISTER ONLY ISOLATED");
        for (var flags : List.of(List.of(false, true), List.of(true, false))) {
            var ex = assertThrows(ImportModel.ImportException.class, () -> cli.authorizeRegistrationOnly(List.of(c), flags.get(0), flags.get(1)));
            assertEquals(5, ex.exitCode);
        }
        var cancelled = new ExistingCli(temp, temp, (cmd, idp) -> { fail("no command expected"); return null; }, message -> "REGISTER");
        assertEquals(4, assertThrows(ImportModel.ImportException.class, () -> cancelled.authorizeRegistrationOnly(List.of(c), true, true)).exitCode);
        cli.authorizeRegistrationOnly(List.of(c), true, true);
        c.access = new ImportModel.AccessAssessment("UNRESOLVED", List.of("changed rule"));
        assertThrows(ImportModel.ImportException.class, () -> cli.executeStaged(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false)), true,
            Files.createDirectory(temp.resolve("staging"))));
        assertTrue(calls.isEmpty());
    }
    @Test void existingSpVerificationCannotClearPendingAuthorization() throws Exception {
        var c = candidate(); c.access = new ImportModel.AccessAssessment("LEGACY_UNASSESSED", List.of("old bundle"));
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> success(cmd, c, c.name, "none"), null);
        assertEquals(10, cli.executeStaged(List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, true)), false,
            Files.createDirectory(temp.resolve("staging"))));
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("VERIFIED_EXISTING_ACCESS_PENDING"));
    }
    private void reviewedMetadata(ImportModel.Candidate c) {
        c.diagnostic("REVIEW", "METADATA_SIGNATURE_VALIDATION_REQUIRED", "provider.xml:1", "signature unverified", "verify original");
    }
    @Test void metadataReviewRequiresFlagTypedApprovalAndStopsWritesWithoutIt() throws Exception {
        var c = candidate(); reviewedMetadata(c); var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "none"); }, message -> "VERIFY SOURCE METADATA");
        assertEquals(5, assertThrows(ImportModel.ImportException.class, () -> cli.authorizeMetadataReview(List.of(c), false)).exitCode);
        var plan = new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false);
        Path staging = Files.createDirectory(temp.resolve("staging"));
        assertEquals(5, assertThrows(ImportModel.ImportException.class, () -> cli.executeStaged(List.of(plan), true, staging)).exitCode);
        assertTrue(calls.isEmpty());
        var cancelled = new ExistingCli(temp, temp, (cmd, idp) -> { fail("no command"); return null; }, message -> "REGISTER");
        assertEquals(4, assertThrows(ImportModel.ImportException.class, () -> cancelled.authorizeMetadataReview(List.of(c), true)).exitCode);
        cli.authorizeMetadataReview(List.of(c), true);
        assertEquals(0, cli.executeStaged(List.of(plan), true, staging));
    }
    @Test void changedMetadataEvidenceInvalidatesConfirmationAndDryRunNeedsNoApproval() throws Exception {
        var c = candidate(); reviewedMetadata(c); var calls = new ArrayList<List<String>>();
        var cli = new ExistingCli(temp, temp, (cmd, idp) -> { calls.add(cmd); return success(cmd, c, c.name, "none"); }, message -> "VERIFY SOURCE METADATA");
        var plan = new ExistingCli.Plan(c, c.name, "none", "force", "", Map.of(), false, false);
        Path staging = Files.createDirectory(temp.resolve("staging"));
        assertEquals(0, cli.executeStaged(List.of(plan), false, staging));
        calls.clear(); cli.authorizeMetadataReview(List.of(c), true);
        c.facts.add("source filter changed");
        assertThrows(ImportModel.ImportException.class, () -> cli.executeStaged(List.of(plan), true, staging));
        assertTrue(calls.isEmpty());
    }
}
