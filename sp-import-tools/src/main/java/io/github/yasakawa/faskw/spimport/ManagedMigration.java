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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Managed migration orchestrator. The existing CLI is the sole configuration writer. */
final class ManagedMigration {
    record Plan(Candidate candidate, String name, String profile, boolean createProfile, boolean exists) { }
    private final Path target, run;
    private final ExistingCli cli;
    private final ExistingCli.Prompt prompt;
    private final Map<String, List<String>> approvals = new TreeMap<>();
    private final Map<String, Candidate> newProfiles = new TreeMap<>();
    private final Map<String, Map<String, String>> approvalContracts = new TreeMap<>();
    private final Map<String, String> profileDescriptions = new TreeMap<>();
    private final StringBuilder outcomes = new StringBuilder();
    private Map<String, String> baseline;
    private List<Plan> plans = List.of();
    private final Map<String, ManagedData.Access> observedAccess = new TreeMap<>();
    private final Set<Path> releaseInputs = new java.util.HashSet<>();

    ManagedMigration(Path target, Path run, ExistingCli cli, ExistingCli.Prompt prompt) {
        this.target = target; this.run = run; this.cli = cli; this.prompt = prompt;
    }
    List<Plan> prepare(Analysis source, List<Candidate> selected, boolean resume) throws Exception {
        MetadataValidity.check(selected, java.time.Instant.now());
        baseline = snapshot();
        var init = read("init");
        if (!key(init, "metadata_provider").equals("present") || !key(init, "attribute_block").equals("present")) fail("TARGET_MANAGEMENT_NOT_READY", "Initialize and restart target first.");
        var mfa = read("mfa", "show");
        if (!key(mfa, "managed_policy_drift").equals("OK")) fail("TARGET_MFA_REVIEW_REQUIRED", "Target MFA policy drift.");
        var targetGlobal = ManagedData.global(ManagedData.properties(target, ManagedData.DIR + "mfa-policy.properties", false));
        SafeFiles.text(run.resolve("managed-policy-diff.txt"), "Global MFA is not copied.\nsource=" + SafeFiles.display(source.globalMfa.toString())
            + "\ntarget=" + SafeFiles.display(targetGlobal.toString()) + "\nMatch=" + source.globalMfa.equals(targetGlobal) + "\n");
        if (!source.globalMfa.equals(targetGlobal)) fail("TARGET_GLOBAL_MFA_DIFFER", "Review global MFA with mfa global set separately, then create a new plan; no automatic global changes.");
        var list = read("attributes", "profile", "list");
        var discovery = read("attributes", "discover");
        var registry = ManagedData.registry(target);
        var policies = ManagedData.policies(target);
        var targetProperties = ManagedData.properties(target, ManagedData.DIR + "sp-management.properties", true);
        boolean needsAccess = selected.stream().anyMatch(c -> c.managed != null && c.managed.access() != null);
        if (needsAccess) {
            if (!ManagedData.accessEnabled(targetProperties)) fail("TARGET_ACCESS_NOT_READY", "Run access init --apply, build/restart and verify separately before importing any policies.");
            String wiring = read("access", "init");
            if (!key(wiring, "context_function_managed").equals("true") || !key(wiring, "context_condition_configured").equals("false")
                    || key(wiring, "saml2_sso_profiles").equals("0") || key(wiring, "saml2_sso_profiles").isEmpty()
                    || !key(wiring, "saml2_sso_profiles").equals(key(wiring, "context_check_profiles"))) fail("TARGET_ACCESS_NOT_READY", "ContextCheck configuration must be managed and cover SAML2 profiles; runtime activation still requires browser verification.");
        }
        List<Plan> result = new ArrayList<>(); Set<String> names = new java.util.HashSet<>();
        Map<String, Set<String>> shared = new TreeMap<>();
        for (Candidate c : selected) {
            if (c.blocked() || c.managed == null || c.bytes == null) fail("MANAGED_SOURCE_REQUIRED", "Selected SP lacks verified management intent.");
            System.out.println(MigrationBundle.detail(c));
            confirm("REVIEW SOURCE", "Confirm source provenance, snapshot, NameID/encryption, requester exceptions and SP-side trust.");
            String name = ManagedData.name(answer("Target management name", c.name));
            String profile = ManagedData.name(answer("Target attribute profile", c.managed.profile()));
            if (!names.add(name)) fail("TARGET_NAME_CONFLICT", "Duplicate target name.");
            var existing = registry.stream().filter(e -> e.entity().equals(c.entity) || e.name().equals(name)).toList();
            if (!existing.isEmpty() && (!resume || existing.size() != 1 || !existing.getFirst().entity().equals(c.entity) || !existing.getFirst().name().equals(name))) fail("TARGET_ENTITY_ALREADY_EXISTS", "Use a bound --resume run; existing SPs are never adopted/overwritten.");
            String inventory = read("status", "--entity-id", c.entity);
            if (existing.isEmpty() && !inventory.lines().anyMatch(("status=NOT_FOUND entity_id=" + c.entity)::equals)) fail("TARGET_UNMANAGED_ENTITY_EXISTS", "Existing manual metadata or unknown inventory output; use a separate workflow, never auto-adopt.");
            if (c.managed.mfa().equals("bypass") || c.managed.mfa().equals("sp-cidr-bypass")) confirm("BYPASS " + name, "Confirm source MFA exception and CIDR scope.");
            Set<String> attrs = ManagedData.profileAttributes(c);
            if (shared.putIfAbsent(profile, attrs) != null && !shared.get(profile).equals(attrs)) fail("TARGET_PROFILE_CONFLICT", "Selected SPs map different attribute sets to one profile.");
            boolean present = list.lines().map(l -> l.trim().split("\\s+")).anyMatch(cols -> cols.length == 5 && cols[0].equals(profile));
            if (present) {
                String show = read("attributes", "profile", "show", profile);
                if (!key(show, "status").equals("OK") || !csv(key(show, "attributes")).equals(attrs)) fail("TARGET_PROFILE_CONFLICT", "Existing profile differs; choose a new name, never update it.");
                String description = key(show, "description");
                profileDescriptions.put(profile, description);
                if (!description.equals(c.managed.description())) confirm("PRESERVE PROFILE " + profile, "Existing description differs and will be retained.");
            } else {
                if (attrs.isEmpty()) fail("TARGET_PROFILE_CONFLICT", "Empty profile must resolve to existing none.");
                Candidate other = newProfiles.putIfAbsent(profile, c);
                profileDescriptions.put(profile, c.managed.description());
                if (other != null && !other.managed.description().equals(c.managed.description())) fail("TARGET_PROFILE_CONFLICT", "Shared source descriptions differ.");
            }
            for (var approval : c.managed.approvals()) prepareApproval(approval, attrs.contains(approval.id()), discovery);
            precheckHosts(c, targetProperties);
            Plan plan = new Plan(c, name, profile, !present, !existing.isEmpty());
            if (plan.exists()) verifySp(plan);
            var policy = policies.get(name);
            if (policy != null && (!policy.entity().equals(c.entity) || !plan.exists())) fail("TARGET_ACCESS_CONFLICT", "Existing access policy belongs to another/unregistered SP.");
            ManagedData.Access expected = c.managed.access(), actual = policy == null ? null : policy.access();
            if (actual != null && !actual.equals(expected) && !(resume && expected != null && !expected.enabled()
                    && actual.enabled() && sameRules(actual, expected))) fail("TARGET_ACCESS_CONFLICT", "Unknown policy changes; no overwrite.");
            observedAccess.put(name, actual);
            result.add(plan);
        }
        plans = List.copyOf(result);
        checkReleases(true);
        assertBaseline();
        StringBuilder check = new StringBuilder("Managed migration target precheck: OK\nGlobal MFA: exact contract match; not copied\n");
        for (Plan p : plans) check.append(p.name()).append(" profile=").append(p.profile()).append(" mfa=").append(p.candidate().managed.mfa())
            .append(" access=").append(p.candidate().managed.access() == null ? "ABSENT" : p.candidate().managed.access().enabled() ? "ENABLED" : "DISABLED").append(" existing=").append(p.exists()).append('\n');
        check.append("Approvals: ").append(approvals.keySet()).append("\nShared new profiles: ").append(newProfiles.keySet()).append('\n');
        SafeFiles.text(run.resolve("target-check.txt"), check.toString());
        System.out.print(check);
        return plans;
    }

    private void checkReleases(boolean first) throws Exception {
        List<Plan> scoped = plans.stream().filter(p -> p.candidate().managed.release() != null).toList();
        if (scoped.isEmpty()) return; // Older v2 bundles retain their original single-set contract.
        Analysis observed = new SourceAnalyzer(ImportOptions.parse("--source-idp-home", target.toString()))
            .analyzeReleases(scoped.stream().map(Plan::candidate).toList());
        Path report = run.resolve("additional-release-check.txt");
        if (!Files.exists(report)) SafeFiles.text(report, "Additional attribute releases: checked against target policies; not copied.\n");
        SafeFiles.append(report, "\ncheck=" + (first ? "prepare" : "recheck") + "\n");
        for (var entry : observed.inputHashes.entrySet()) {
            String key = snapshotKey(entry.getKey());
            if ((!first && !releaseInputs.contains(entry.getKey())) || baseline.containsKey(key) && !baseline.get(key).equals(entry.getValue()))
                fail("TARGET_CHANGED_AFTER_REVIEW", "Attribute policy input changed: " + key);
            releaseInputs.add(entry.getKey()); baseline.putIfAbsent(key, entry.getValue());
        }
        String discovery = read("attributes", "discover");
        var registry = ManagedData.registry(target);
        for (Plan p : scoped) {
            Candidate actual = observed.candidates.stream().filter(c -> c.entity.equals(p.candidate().entity)).findFirst().orElseThrow();
            SafeFiles.append(report, MigrationBundle.detail(actual));
            if (actual.blocked() || observed.held()) fail("TARGET_ADDITIONAL_RELEASE_UNRESOLVED", "Resolve target policy/provider context; see additional-release-check.txt.");
            Set<String> expected = new TreeSet<>(p.candidate().managed.release().additionalAttributes());
            Set<String> extra;
            try { extra = ReleasePolicies.additional(actual, p.name(), ManagedData.profileAttributes(p.candidate()),
                registry.stream().anyMatch(e -> e.entity().equals(p.candidate().entity))); }
            catch (IllegalArgumentException ex) { fail("TARGET_ATTRIBUTE_FILTER_DRIFT", ex.getMessage()); return; }
            Set<String> missing = new TreeSet<>(expected); missing.removeAll(extra);
            Set<String> unexpected = new TreeSet<>(extra); unexpected.removeAll(expected);
            SafeFiles.append(report, p.name() + " source_additional=" + expected + " target_additional=" + extra
                + " missing=" + missing + " unexpected=" + unexpected + "\n");
            if (!missing.isEmpty() || !unexpected.isEmpty()) fail("TARGET_ADDITIONAL_RELEASE_DIFFER",
                p.name() + " missing=" + missing + " unexpected=" + unexpected + "; review target common policies separately; no profile/global policy is changed automatically.");
            for (String id : expected) {
                String[] row = discovery.lines().map(l -> l.trim().split("\\s+")).filter(cols -> cols.length >= 4 && cols[0].equals(id)).findFirst().orElse(null);
                if (row == null || !Set.of("declared", "runtime-observed", "conditional").contains(row[1])
                        || !row[2].equals("mapped") || row[3].equals("blocked"))
                    fail("TARGET_ADDITIONAL_ATTRIBUTE_NOT_READY", id + ": verify target resolver/mapping and policy; no approval is created for common releases.");
            }
        }
    }

    private void prepareApproval(ManagedData.Approval source, boolean release, String discovery) throws Exception {
        String[] row = discovery.lines().map(l -> l.trim().split("\\s+")).filter(cols -> cols.length >= 4 && cols[0].equals(source.id())).findFirst().orElse(null);
        if (row == null || !Set.of("declared", "runtime-observed", "conditional").contains(row[1])) fail("TARGET_ATTRIBUTE_UNRESOLVED", source.id() + ": configure resolver and verify user values first.");
        if (release && !row[2].equals("mapped")) fail("TARGET_MAPPING_REQUIRED", source.id());
        if (row[3].equals("blocked")) fail("TARGET_ATTRIBUTE_BLOCKED", source.id());
        boolean approved = row[3].equals("release-approved") || !release && row[3].equals("internal-only");
        if (approved) {
            String show = read("attributes", "show", source.id());
            if (!key(show, "classification").equals(source.classification()) || !key(show, "purpose").equals(source.purpose())
                    || (!release && row[3].equals("release-approved") && source.governance().equals("internal-only")))
                confirm("PRESERVE ATTRIBUTE " + source.id(), "Target approval/classification/purpose differs; preserve target (possibly broader release permission).");
            approvalContracts.put(source.id(), Map.of("governance", row[3], "classification", key(show, "classification"), "purpose", key(show, "purpose")));
            return;
        }
        String usage = release || source.governance().equals("release-approved") ? "release" : "access";
        List<String> previous = approvals.get(source.id());
        if (previous != null && previous.get(previous.indexOf("--usage") + 1).equals("release")) return;
        String classification = source.classification();
        String purpose = source.purpose();
        if (!Set.of("public", "personal", "internal", "restricted").contains(classification) || purpose.isBlank()) fail("TARGET_APPROVAL_REVIEW_REQUIRED", "Explicit non-credential classification and purpose required.");
        ManagedData.bounded(purpose, 512);
        if (usage.equals("release") && !row[2].equals("mapped")) fail("TARGET_MAPPING_REQUIRED", source.id());
        confirm("APPROVE " + source.id() + " " + usage, "Review attribute usage, classification=" + classification + ", purpose=" + SafeFiles.display(purpose));
        List<String> args = List.of("attributes", "approve", source.id(), "--usage", usage, "--classification", classification, "--purpose", purpose);
        read(args.toArray(String[]::new)); approvals.put(source.id(), args);
        approvalContracts.put(source.id(), Map.of("governance", usage.equals("release") ? "release-approved" : "internal-only", "classification", classification, "purpose", purpose));
    }

    String planText(String bundleHash) throws Exception {
        StringBuilder out = new StringBuilder("managed-plan-v2\t" + bundleHash + "\n");
        out.append(MigrationBundle.fields(target.toRealPath().toString())).append('\n');
        out.append(MigrationBundle.fields(new String(StrictJson.write(approvalContracts), StandardCharsets.UTF_8),
            new String(StrictJson.write(profileDescriptions), StandardCharsets.UTF_8))).append('\n');
        for (Plan p : plans) out.append(MigrationBundle.fields(p.candidate().entity, p.name(), p.profile(), p.candidate().hash,
            String.join(",", p.candidate().attributes), new String(StrictJson.write(ManagedData.intentObject(p.candidate().managed)), StandardCharsets.UTF_8))).append('\n');
        // Source intent, target mapping and global comparison are bound independent of completed phases.
        return out.toString();
    }
    int execute(boolean apply, boolean isolated, Path staging) throws Exception {
        if (apply) {
            if (!isolated) fail("TARGET_ISOLATION_REQUIRED", "--confirm-isolated-target is required; enforce isolation at the edge before applying.");
            confirm("ISOLATED TARGET", "Target must be unpublished/blocked at the edge, backed up, and under exclusive administration. This prompt does not enforce network isolation.");
            confirm("REGISTER " + plans.size(), "Successful CLI operations are retained; no batch rollback.");
        }
        try {
            MetadataValidity.check(plans.stream().map(Plan::candidate).toList(), java.time.Instant.now());
            assertBaseline();
            checkReleases(false);
            if (!apply) {
                for (Plan p : plans) {
                    if (p.createProfile() || !approvals.isEmpty()) { stage(p, "WAITING_DEPENDENCIES"); continue; }
                    if (!p.exists()) dryAdd(p, stageMetadata(p, staging));
                    stage(p, "DRY_RUN_OK; access apply requires active SP and isolation");
                }
                return newProfiles.isEmpty() && approvals.isEmpty() ? 0 : 5;
            }
            assertBaseline();
            SafeFiles.text(run.resolve("approved-actions.tsv"), approvals.entrySet().stream().map(e -> MigrationBundle.fields(e.getKey(), String.join("\n", e.getValue()))).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            for (var approval : approvals.entrySet()) {
                List<String> args = new ArrayList<>(approval.getValue()); args.addAll(List.of("--apply", "--confirm", approval.getKey()));
                write(args);
            }
            for (var profile : newProfiles.entrySet()) {
                List<String> args = new ArrayList<>(List.of("attributes", "profile", "create", profile.getKey(), "--attributes",
                    String.join(",", ManagedData.profileAttributes(profile.getValue())), "--description", profile.getValue().managed.description()));
                read(args.toArray(String[]::new)); args.addAll(List.of("--apply", "--confirm", profile.getKey())); write(args);
            }
            for (Plan p : plans) {
                MetadataValidity.check(List.of(p.candidate()), java.time.Instant.now());
                stage(p, "DEPENDENCIES_READY");
                if (!p.exists()) {
                    Path metadata = stageMetadata(p, staging);
                    List<String> args = dryAdd(p, metadata);
                    MetadataValidity.check(List.of(p.candidate()), java.time.Instant.now());
                    args.addAll(List.of("--approve-sha256", p.candidate().hash, "--apply")); write(args);
                }
                verifySp(p); stage(p, "SP_REGISTERED");
                applyAccess(p); stage(p, "ACCESS_CONFIGURED");
                verifyProfile(p); verifySp(p); verifyAccess(p); stage(p, "CONFIG_VERIFIED");
                read("mfa", "test", "--sp", p.name(), "--ip", "192.0.2.10");
            }
            assertBaseline();
            return 0;
        } catch (Exception ex) { outcomes.append("STOPPED: ").append(SafeFiles.display(ex.getMessage())).append("\nKeep target isolated; completed operations are retained.\n"); cli.failure(ex); throw ex; }
        finally { finish(); }
    }
    private void applyAccess(Plan p) throws Exception {
        var expected = p.candidate().managed.access();
        var current = policy(p.name());
        if (!java.util.Objects.equals(current, observedAccess.get(p.name()))) fail("TARGET_CHANGED_AFTER_REVIEW", "Access policy changed outside this run.");
        if (expected == null || expected.equals(current)) return;
        if (current == null) {
            List<String> args = new ArrayList<>(List.of("access", "set", p.name()));
            rulesArgs(args, "--allow", expected.allow()); rulesArgs(args, "--deny", expected.deny());
            read(args.toArray(String[]::new)); args.addAll(List.of("--apply", "--confirm", p.candidate().entity)); write(args);
            stage(p, "ACCESS_SET_VERIFY_PENDING");
        } else if (expected.enabled() || !current.enabled() || !sameRules(expected, current)) fail("TARGET_ACCESS_CONFLICT", "Policy is not an expected partial disable phase.");
        if (!expected.enabled()) {
            List<String> args = new ArrayList<>(List.of("access", "disable", p.name())); read(args.toArray(String[]::new));
            args.addAll(List.of("--apply", "--confirm", p.candidate().entity)); write(args);
        }
    }
    private static void rulesArgs(List<String> args, String flag, Map<String, List<String>> rules) {
        new TreeMap<>(rules).forEach((attr, values) -> values.forEach(value -> args.addAll(List.of(flag, attr + "=" + value))));
    }
    private List<String> dryAdd(Plan p, Path file) throws Exception {
        var intent = p.candidate().managed;
        List<String> args = new ArrayList<>(List.of("add", "--name", p.name(), "--entity-id", p.candidate().entity, "--metadata-file", file.toString(), "--attribute-profile", p.profile(), "--mfa", intent.mfa()));
        if (!intent.cidrs().isEmpty()) args.addAll(List.of("--cidrs", String.join(",", intent.cidrs())));
        if (Set.of("bypass", "sp-cidr-bypass").contains(intent.mfa())) args.add("--confirm-bypass");
        String dry = read(args.toArray(String[]::new));
        if (!key(dry, "metadata_sha256").equals(p.candidate().hash) || !key(dry, "entity_id").equals(p.candidate().entity)) throw new ImportException(6, "CLI_OUTPUT_UNRECOGNIZED: metadata/entity mismatch");
        return args;
    }
    private Path stageMetadata(Plan p, Path staging) throws Exception {
        Path file = staging.resolve(p.name() + ".xml"); SafeFiles.write(file, p.candidate().bytes); return file;
    }
    private void verifySp(Plan p) throws Exception {
        cli.verify(new ExistingCli.Plan(p.candidate(), p.name(), p.profile(), p.candidate().managed.mfa(), String.join(",", p.candidate().managed.cidrs()), Map.of(), false, true));
    }
    private void verifyProfile(Plan p) throws Exception {
        String text = read("attributes", "profile", "show", p.profile());
        if (!key(text, "status").equals("OK") || !csv(key(text, "attributes")).equals(ManagedData.profileAttributes(p.candidate()))
                || !key(text, "description").equals(profileDescriptions.get(p.profile()))) throw new ImportException(8, "CLI_VERIFY_FAILED: profile attributes/status/description");
        for (var a : p.candidate().managed.approvals()) {
            String attr = read("attributes", "show", a.id()); String g = key(attr, "governance");
            if (!Set.of("release-approved", "internal-only").contains(g) || ManagedData.profileAttributes(p.candidate()).contains(a.id()) && (!g.equals("release-approved") || !key(attr, "saml_mapping").equals("mapped")))
                throw new ImportException(8, "CLI_VERIFY_FAILED: approval " + a.id());
            if (!approvalContracts.get(a.id()).equals(Map.of("governance", g, "classification", key(attr, "classification"), "purpose", key(attr, "purpose"))))
                throw new ImportException(8, "CLI_VERIFY_FAILED: approval classification/purpose changed");
        }
    }
    private void verifyAccess(Plan p) throws Exception {
        if (!java.util.Objects.equals(policy(p.name()), p.candidate().managed.access())) throw new ImportException(8, "ACCESS_PHASE_INCOMPLETE: do not expose the target");
        if (p.candidate().managed.access() != null) read("access", "show", p.name());
    }
    private ManagedData.Access policy(String name) throws Exception {
        var value = ManagedData.policies(target).get(name); return value == null ? null : value.access();
    }
    private void stage(Plan p, String stage) throws Exception {
        outcomes.append(p.name()).append(' ').append(stage).append('\n');
        Path ledger = run.resolve("managed-execution-state.tsv");
        if (!Files.exists(ledger)) SafeFiles.text(ledger, "managed-execution-v2\n");
        SafeFiles.append(ledger, MigrationBundle.fields(Instant.now().toString(), p.candidate().entity, p.name(), p.profile(), stage, p.candidate().hash) + "\n");
    }
    void finish() throws Exception {
        if (!Files.exists(run.resolve("managed-result.txt"))) SafeFiles.text(run.resolve("managed-result.txt"), outcomes
            + "READY_FOR_CUTOVER=NOT_CONFIRMED\nCUTOVER_VERIFIED=NOT_CONFIRMED\nKeep target isolated until browser allow/deny, MFA, attributes, NameID and SP trust are verified.\n"
            + "No source/DB/LDAP/secret/global MFA migration; no batch rollback.\n");
    }
    private String read(String... args) throws Exception {
        var response = cli.command(List.of(args)); ExistingCli.require(response, 5, "CLI_PRECHECK_OR_DRY_RUN_FAILED"); return response.output();
    }
    private void write(List<String> args) throws Exception {
        assertBaseline();
        checkReleases(false);
        var beforeRegistry = ManagedData.registry(target);
        var response = cli.command(args); ExistingCli.require(response, 7, "CLI_APPLY_FAILED");
        if (!key(response.output(), "result").equals("APPLY_OK")) throw new ImportException(7, "CLI_APPLY_RESULT_UNKNOWN: inspect actual target state before resume");
        Map<String, String> after = snapshot();
        Set<String> permitted = new java.util.HashSet<>(List.of(ManagedData.REGISTRY));
        if (args.getFirst().equals("attributes")) { permitted.add(ManagedData.DIR + "attribute-catalog.json"); permitted.add("conf/attribute-filter.xml"); }
        else if (args.getFirst().equals("access")) permitted.add(ManagedData.DIR + "access-policy.json");
        else if (args.getFirst().equals("add")) {
            permitted.add("conf/attribute-filter.xml"); permitted.add(ManagedData.DIR + "mfa-policy.properties");
            String name = args.get(args.indexOf("--name") + 1);
            var registered = ManagedData.registry(target).stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow(); permitted.add(registered.file());
        }
        Set<String> paths = new java.util.HashSet<>(baseline.keySet()); paths.addAll(after.keySet());
        for (String path : paths) if (!permitted.contains(path) && !java.util.Objects.equals(baseline.get(path), after.get(path)))
            throw new ImportException(6, "TARGET_CHANGED_AFTER_REVIEW: unrelated target file changed during CLI operation");
        var afterRegistry = ManagedData.registry(target);
        for (var entry : beforeRegistry) {
            boolean own = args.getFirst().equals("access") && args.get(2).equals(entry.name());
            var current = afterRegistry.stream().filter(e -> e.name().equals(entry.name())).findFirst().orElse(null);
            if (!own && !entry.equals(current)) throw new ImportException(6, "TARGET_CHANGED_AFTER_REVIEW: unrelated registry entry changed");
        }
        baseline = after;
        checkReleases(false);
    }
    private Map<String, String> snapshot() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        for (String file : List.of(ManagedData.REGISTRY, ManagedData.DIR + "attribute-catalog.json", ManagedData.DIR + "access-policy.json",
                ManagedData.DIR + "sp-management.properties", ManagedData.DIR + "mfa-policy.properties", "conf/metadata-providers.xml",
                "conf/attribute-filter.xml", "conf/attribute-resolver.xml", "conf/relying-party.xml", "conf/intercept/context-check-intercept-config.xml")) {
            hashes.put(file, Files.exists(target.resolve(file)) ? SafeFiles.hash(SafeFiles.read(SafeFiles.contained(target, file), SafeFiles.MAX_FILE)) : "ABSENT");
        }
        for (var entry : ManagedData.registry(target)) if (entry.status().equals("ACTIVE"))
            hashes.put(entry.file(), SafeFiles.hash(SafeFiles.read(SafeFiles.contained(target, entry.file()), SafeFiles.MAX_FILE)));
        for (Path path : releaseInputs) hashes.put(snapshotKey(path), SafeFiles.hash(SafeFiles.read(path, SafeFiles.MAX_FILE)));
        return hashes;
    }
    private String snapshotKey(Path path) throws Exception {
        Path root = target.toRealPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.toString();
    }
    private void assertBaseline() throws Exception { if (!baseline.equals(snapshot())) throw new ImportException(6, "TARGET_CHANGED_AFTER_REVIEW: re-export/review; no overwrite"); }
    private String answer(String label, String fallback) throws ImportException { if (prompt == null) return fallback; String answer = prompt.ask(label + " [" + SafeFiles.display(fallback) + "]: "); return answer.isBlank() ? fallback : answer; }
    private void confirm(String text, String message) throws ImportException {
        if (prompt == null) return; // Read-only plans remain reviewable without a TTY.
        if (!prompt.ask(message + " Type " + text + ": ").equals(text)) throw new ImportException(4, "managed migration cancelled");
    }
    private static String key(String output, String key) throws ImportException { return ExistingCli.key(output, key); }
    private static Set<String> csv(String value) { return value.isEmpty() ? Set.of() : new TreeSet<>(List.of(value.split(","))); }
    private static boolean sameRules(ManagedData.Access a, ManagedData.Access b) { return a.allow().equals(b.allow()) && a.deny().equals(b.deny()); }
    private static void fail(String code, String detail) throws ImportException { throw new ImportException(5, code + ": " + detail); }
    private static void precheckHosts(Candidate c, java.util.Properties p) throws Exception {
        Set<String> hosts = new TreeSet<>(List.of(p.getProperty("graphicalmatrix.sp.metadata.allowedAcsHosts", "").split(","))
            .stream().map(String::trim).map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList());
        for (var node : SecureXml.parse(c.bytes, c.source).descendants("AssertionConsumerService")) {
            var uri = java.net.URI.create(node.attr("Location")); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) fail("TARGET_ACS_URL_UNSUPPORTED", "HTTPS without credentials/fragment is required.");
            if (host == null || !hosts.contains(host.toLowerCase(java.util.Locale.ROOT))) fail("TARGET_ACS_HOST_NOT_APPROVED", "Configure allowedAcsHosts before any write.");
        }
    }
}
