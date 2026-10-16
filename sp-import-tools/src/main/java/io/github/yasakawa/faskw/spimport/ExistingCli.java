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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

final class ExistingCli {
    record Response(int exit, String output) { }
    interface Executor { Response execute(List<String> command, Path idp) throws Exception; }
    interface Prompt { String ask(String message) throws ImportException; }
    record Plan(Candidate candidate, String name, String profile, String mfa, String cidrs,
                Map<String, List<String>> approvals, boolean createProfile, boolean alreadyExists) { }

    private final Path idp;
    private final Path script;
    private final Path run;
    private final Executor executor;
    private final Prompt prompt;
    private final boolean deferAttributes;
    private final java.time.Clock clock;
    private boolean deferredApproval;
    private final StringBuilder transcript = new StringBuilder();
    private final StringBuilder commands = new StringBuilder();
    private final StringBuilder results = new StringBuilder();
    private final Map<Candidate, AccessAssessment> registrationOnlyApproval = new java.util.IdentityHashMap<>();
    private final StringBuilder accessResults = new StringBuilder();
    private final Map<Candidate, String> metadataReviewApproval = new java.util.IdentityHashMap<>();

    void authorizeMetadataReview(List<Candidate> selected, boolean confirmed) throws Exception {
        if (selected.stream().noneMatch(Candidate::metadataReviewRequired)) return;
        if (!confirmed) throw new ImportException(5,
            "SOURCE_METADATA_REVIEW_REQUIRED: independently verify original signatures/trust, validity, group and filter semantics; then use --confirm-metadata-review");
        for (Candidate c : selected) if (c.metadataReviewRequired()) System.out.println(MigrationBundle.detail(c));
        if (prompt == null || !"VERIFY SOURCE METADATA".equals(prompt.ask(
                "This tool has NOT verified source signatures or executed the source filter chain. Confirm independent review of original metadata and its hash. Type VERIFY SOURCE METADATA: ")))
            throw new ImportException(4, "source metadata verification confirmation cancelled");
        for (Candidate c : selected) if (c.metadataReviewRequired()) {
            metadataReviewApproval.put(c, metadataStamp(c));
            results.append("SOURCE_METADATA_REVIEW_ACKNOWLEDGED entity_id=").append(SafeFiles.display(c.entity))
                .append(" snapshot_sha256=").append(c.hash).append(" original_sha256=").append(c.sourceMetadataHash)
                .append("; administrator acknowledgement, NOT tool cryptographic verification\n");
        }
    }

    private static String metadataStamp(Candidate c) {
        return SafeFiles.hash(MigrationBundle.detail(c).getBytes(StandardCharsets.UTF_8));
    }

    void authorizeRegistrationOnly(List<Candidate> selected, boolean registrationOnly, boolean isolated) throws Exception {
        if (selected.stream().noneMatch(Candidate::accessPending)) return;
        if (!registrationOnly || !isolated)
            throw new ImportException(5, "SOURCE_ACCESS_CONTROL_PENDING: apply requires --registration-only --confirm-isolated-target; enforce network isolation before proceeding");
        if (prompt == null || !"REGISTER ONLY ISOLATED".equals(prompt.ask(
                "Source authorization will NOT be migrated. Keep target isolated until allow/deny tests pass. Type REGISTER ONLY ISOLATED: ")))
            throw new ImportException(4, "registration-only isolation confirmation cancelled");
        for (Candidate c : selected) if (c.accessPending()) registrationOnlyApproval.put(c, c.access);
    }

    ExistingCli(Path idp, Path run, Executor executor, Prompt prompt) {
        this(idp, run, executor, prompt, false);
    }

    ExistingCli(Path idp, Path run, Executor executor, Prompt prompt, boolean deferAttributes) {
        this(idp, run, executor, prompt, deferAttributes, java.time.Clock.systemUTC());
    }

    ExistingCli(Path idp, Path run, Executor executor, Prompt prompt, boolean deferAttributes, java.time.Clock clock) {
        this.idp = idp;
        script = idp.resolve("bin/graphicalmatrix-sp.sh");
        this.run = run;
        this.executor = executor;
        this.prompt = prompt;
        this.deferAttributes = deferAttributes;
        this.clock = clock;
    }

    void authorizeDeferredAttributes(boolean registrationOnly, boolean isolated) throws Exception {
        if (!deferAttributes) return;
        if (!registrationOnly || !isolated)
            throw new ImportException(5, "TARGET_ISOLATION_REQUIRED: deferred attributes require --registration-only --confirm-isolated-target");
        if (prompt == null || !"DEFER ATTRIBUTES ISOLATED".equals(prompt.ask(
                "No attributes or authorization rules will be migrated. none does not suppress shared release policies. Keep target isolated. Type DEFER ATTRIBUTES ISOLATED: ")))
            throw new ImportException(4, "deferred attribute isolation confirmation cancelled");
        deferredApproval = true;
    }

    static void validateInstallation(Path idp, boolean apply) throws Exception {
        if (!Files.isDirectory(idp) || !Files.isExecutable(idp.resolve("bin/graphicalmatrix-sp.sh")))
            throw new ImportException(5, "CLI_NOT_FOUND: install 2FAS-KW SP management on the target IdP");
        boolean compatible = false;
        for (String sub : List.of("edit-webapp/WEB-INF/lib", "dist/plugin-webapp/WEB-INF/lib")) {
            Path dir = idp.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (var paths = Files.list(dir)) {
                for (Path jar : paths.filter(p -> p.getFileName().toString().matches("2faskw-idp-plugin-.*\\.jar")).toList()) {
                    try (JarFile file = new JarFile(jar.toFile())) {
                        String version = file.getManifest().getMainAttributes().getValue("Implementation-Version");
                        if (Set.of("1.3.5", "1.4.0").contains(version)) compatible = true;
                        else throw new ImportException(5, "CLI_VERSION_UNSUPPORTED: " + SafeFiles.display(String.valueOf(version)));
                    }
                }
            }
        }
        if (!compatible) throw new ImportException(5, "CLI_VERSION_UNSUPPORTED: target plugin version cannot be established");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(idp.resolve("conf/graphicalmatrix/sp-management.properties"))) { properties.load(reader); }
        if (apply && !"true".equalsIgnoreCase(properties.getProperty("graphicalmatrix.sp.management.enabled", "false").trim()))
            throw new ImportException(5, "TARGET_MANAGEMENT_NOT_READY: graphicalmatrix.sp.management.enabled must be true");
    }

    List<Plan> prepare(List<Candidate> selected, boolean resume) throws Exception {
        MetadataValidity.check(selected, clock.instant());
        Response init = command(List.of("init"));
        require(init, 5, "TARGET_MANAGEMENT_NOT_READY");
        if (!"present".equals(key(init.output(), "metadata_provider")) || !"present".equals(key(init.output(), "attribute_block")))
            throw new ImportException(5, "TARGET_MANAGEMENT_NOT_READY: run init --apply and restart/recheck the target before migration");
        Response mfaState = command(List.of("mfa", "show"));
        require(mfaState, 5, "TARGET_MFA_REVIEW_REQUIRED");
        if (!"OK".equals(key(mfaState.output(), "managed_policy_drift")))
            throw new ImportException(5, "TARGET_MFA_REVIEW_REQUIRED: resolve target managed policy drift before migration");
        Response profiles = command(List.of("attributes", "profile", "list"));
        require(profiles, 5, "TARGET_PROFILE_UNRESOLVED");
        Response discovery = deferAttributes ? new Response(0, "") : command(List.of("attributes", "discover"));
        if (!deferAttributes) require(discovery, 5, "TARGET_ATTRIBUTE_UNRESOLVED");
        List<Plan> plans = new ArrayList<>();
        Set<String> usedNames = new java.util.HashSet<>();
        Set<String> newProfiles = new java.util.HashSet<>();
        for (Candidate c : selected) {
            if (c.blocked() || c.bytes == null) throw new ImportException(3, "source candidate is held: " + SafeFiles.display(c.entity));
            System.out.println(MigrationBundle.detail(c));
            if (prompt != null && !"REVIEW SOURCE".equals(prompt.ask("Confirm source trust, snapshot refresh, NameID/encryption and target defaults. Type REVIEW SOURCE: ")))
                throw new ImportException(4, "source review cancelled");
            String name = answer("Target management name", c.name);
            if (!name.matches("[a-z0-9][a-z0-9-]{0,62}") || !usedNames.add(name)) throw new ImportException(2, "invalid/duplicate target management name");
            String suggested = c.attributes.isEmpty() ? "none" : "imported-" + name;
            for (String line : deferAttributes ? new String[0] : profiles.output().split("\\R")) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length == 5 && columns[3].equals("OK") && attributes(columns[4].equals("-") ? "" : columns[4]).equals(c.attributes)) { suggested = columns[0]; break; }
            }
            String profile = deferAttributes ? "none" : answer("Target attribute profile (must match all source release attributes)", suggested);
            if (!profile.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new ImportException(2, "invalid profile name");
            boolean listed = profiles.output().lines().anyMatch(line -> line.trim().startsWith(profile + " "));
            boolean create = false;
            Map<String, List<String>> approvals = new LinkedHashMap<>();
            if (listed) {
                Response show = command(List.of("attributes", "profile", "show", profile));
                require(show, 5, "TARGET_PROFILE_CONFLICT");
                if (deferAttributes && (!"builtin".equals(key(show.output(), "source"))
                        || show.output().lines().noneMatch(line -> line.startsWith("attributes="))))
                    throw new ImportException(5, "TARGET_PROFILE_CONFLICT: confirmed built-in empty profile none is required");
                if (!"OK".equals(key(show.output(), "status")) || !attributes(key(show.output(), "attributes")).equals(deferAttributes ? Set.of() : c.attributes))
                    throw new ImportException(5, "TARGET_PROFILE_CONFLICT: profile is invalid or does not match source attribute set");
            } else {
                if (deferAttributes) throw new ImportException(5, "TARGET_PROFILE_CONFLICT: built-in empty profile none is required");
                if (c.attributes.isEmpty()) throw new ImportException(5, "TARGET_PROFILE_CONFLICT: an empty source must use an existing empty profile");
                create = true;
                if (!newProfiles.add(profile)) throw new ImportException(5, "TARGET_PROFILE_CONFLICT: pre-create a shared profile or choose distinct new profile names");
                for (String attr : c.attributes) {
                    String[] candidate = discovery.output().lines().map(l -> l.trim().split("\\s+"))
                        .filter(cols -> cols.length >= 4 && cols[0].equals(attr)).findFirst().orElse(null);
                    if (candidate == null || !Set.of("declared", "runtime-observed", "conditional").contains(candidate[1]))
                        throw new ImportException(5, "TARGET_ATTRIBUTE_UNRESOLVED: " + attr + "; configure target resolver first");
                    if (!candidate[2].equals("mapped")) throw new ImportException(5, "TARGET_MAPPING_REQUIRED: " + attr);
                    if (candidate[3].equals("blocked")) throw new ImportException(5, "TARGET_ATTRIBUTE_BLOCKED: " + attr);
                    if (!candidate[3].equals("release-approved")) {
                        if (prompt == null) throw new ImportException(5, "TARGET_ATTRIBUTE_UNAPPROVED: " + attr + "; use interactive approval or existing CLI manually");
                        String classification = prompt.ask("Classification for " + attr + " (public/personal/internal/restricted): ");
                        String purpose = prompt.ask("Release purpose for " + attr + ": ");
                        if (!Set.of("public", "personal", "internal", "restricted").contains(classification) || purpose.isBlank())
                            throw new ImportException(2, "classification and purpose must be explicit");
                        List<String> approval = List.of("attributes", "approve", attr, "--usage", "release", "--classification", classification, "--purpose", purpose);
                        require(command(approval), 7, "CLI_DRY_RUN_FAILED");
                        approvals.put(attr, approval);
                    }
                }
            }
            String mfa = answer("MFA profile (inherit/force/required/bypass/sp-cidr-bypass)", "inherit");
            if (!Set.of("inherit", "force", "required", "bypass", "sp-cidr-bypass").contains(mfa)) throw new ImportException(2, "invalid MFA profile");
            String cidrs = mfa.equals("sp-cidr-bypass") && prompt != null ? prompt.ask("Explicit comma-separated bypass CIDRs: ") : "";
            if (!cidrs.isBlank()) cidrs = String.join(",", java.util.Arrays.stream(cidrs.split(",", -1)).map(String::trim).filter(v -> !v.isBlank()).distinct().toList());
            if (mfa.equals("sp-cidr-bypass") && cidrs.isBlank()) throw new ImportException(2, "CIDRs are required");
            if ((mfa.equals("bypass") || mfa.equals("sp-cidr-bypass")) && (prompt == null || !prompt.ask("Type BYPASS " + name + " to confirm the MFA exception: ").equals("BYPASS " + name)))
                throw new ImportException(4, "bypass not confirmed");
            Response status = command(List.of("status", "--entity-id", c.entity));
            require(status, 5, "TARGET_ENTITY_ALREADY_EXISTS");
            boolean exists = !status.output().contains("status=NOT_FOUND entity_id=" + c.entity);
            if (exists && !resume) throw new ImportException(5, "TARGET_ENTITY_ALREADY_EXISTS: use --resume with the prior run; no update/adopt will be performed");
            plans.add(new Plan(c, name, profile, mfa, cidrs, approvals, create, exists));
        }
        StringBuilder text = new StringBuilder("entityID | name | attribute-profile | MFA | CIDRs | new approvals | new profile\n");
        for (Plan p : plans) text.append(SafeFiles.display(p.candidate().entity)).append(" | ").append(p.name()).append(" | ").append(p.profile())
            .append(" | ").append(p.mfa()).append(" | ").append(p.cidrs()).append(" | ").append(p.approvals().keySet()).append(" | ").append(p.createProfile()).append('\n');
        SafeFiles.text(run.resolve("target-check.txt"), text.toString());
        if (deferAttributes) SafeFiles.text(run.resolve("attribute-migration-todo.txt"), DeferredRegistration.todo(plans));
        System.out.print(text);
        return plans;
    }

    int execute(List<Plan> plans, boolean apply) throws Exception {
        Path staging = Files.createTempDirectory("2faskw-sp-import-");
        try {
            if (apply && ((Number) Files.getAttribute(staging, "unix:uid")).intValue() != 0)
                throw new ImportException(5, "root-owned staging is required; run apply with sudo");
            SafeFiles.directory(staging);
            return executeStaged(plans, apply, staging);
        } catch (Exception ex) {
            failure(ex); throw ex;
        } finally { SafeFiles.deleteTree(staging); finish(); }
    }

    int executeStaged(List<Plan> plans, boolean apply, Path staging) throws Exception {
        boolean incomplete = false;
        try {
            if (deferAttributes) {
                if (apply && !deferredApproval) throw new ImportException(5, "TARGET_ISOLATION_REQUIRED: deferred attribute acknowledgement missing; no target write performed");
                for (Plan p : plans) if (!p.profile().equals("none") || p.createProfile() || !p.approvals().isEmpty())
                    throw new ImportException(6, "DEFERRED_PLAN_INVALID: profile must be none with no approvals or profile creation");
                if (!Files.exists(run.resolve("attribute-migration-todo.txt")))
                    SafeFiles.text(run.resolve("attribute-migration-todo.txt"), DeferredRegistration.todo(plans));
                results.append("ATTRIBUTES_PENDING: profile=none; no attribute approval/profile creation. Keep target isolated; shared policies may still release attributes.\n");
            }
            for (Plan p : plans) if (p.candidate().blocked()) throw new ImportException(3, "source candidate is held: " + SafeFiles.display(p.candidate().entity));
            MetadataValidity.check(plans.stream().map(Plan::candidate).toList(), clock.instant());
            boolean pending = plans.stream().anyMatch(p -> p.candidate().accessPending());
            for (Plan p : plans) {
                Candidate c = p.candidate();
                if (apply && c.metadataReviewRequired() && !metadataStamp(c).equals(metadataReviewApproval.get(c)))
                    throw new ImportException(5, "SOURCE_METADATA_REVIEW_REQUIRED: confirmation missing or snapshot/review evidence changed; no target write performed");
                accessResults.append(MigrationBundle.accessDetail(c));
                if (apply && c.accessPending() && !c.access.equals(registrationOnlyApproval.get(c)))
                    throw new ImportException(5, "SOURCE_ACCESS_CONTROL_PENDING: registration-only approval is missing or changed; no target write performed");
            }
            if (pending) results.append("ACCESS_CONTROL_PENDING: registration verification is not authorization verification. Keep the target isolated.\n");
            for (Plan plan : plans) {
                Candidate c = plan.candidate();
                MetadataValidity.check(List.of(c), clock.instant());
                Path file = staging.resolve(plan.name() + ".xml");
                SafeFiles.write(file, c.bytes);
                if (plan.alreadyExists()) {
                    verify(plan);
                    record(plan, deferAttributes ? "VERIFIED_EXISTING_ATTRIBUTES_PENDING" : c.accessPending() ? "VERIFIED_EXISTING_ACCESS_PENDING" : "VERIFIED_EXISTING");
                    continue;
                }
                for (var entry : plan.approvals().entrySet()) if (apply) {
                    List<String> args = new ArrayList<>(entry.getValue());
                    args.addAll(List.of("--apply", "--confirm", entry.getKey()));
                    require(command(args), 7, "CLI_APPLY_FAILED");
                    record(plan, "ATTRIBUTE_APPROVED:" + entry.getKey());
                }
                if (plan.createProfile()) {
                    if (!apply && !plan.approvals().isEmpty()) {
                        results.append(plan.name()).append(" WAITING_ATTRIBUTE_APPROVAL_AND_PROFILE; no add dry-run with a substitute profile\n");
                        incomplete = true; continue;
                    }
                    List<String> profile = List.of("attributes", "profile", "create", plan.profile(), "--attributes", String.join(",", c.attributes), "--description", "One-time SP migration");
                    require(command(profile), 7, "CLI_DRY_RUN_FAILED");
                    if (apply) {
                        List<String> args = new ArrayList<>(profile);
                        args.addAll(List.of("--apply", "--confirm", plan.profile()));
                        require(command(args), 7, "CLI_APPLY_FAILED");
                        record(plan, "PROFILE_CREATED");
                    } else {
                        results.append(plan.name()).append(" WAITING_PROFILE_CREATION; no add dry-run with a substitute profile\n");
                        incomplete = true; continue;
                    }
                }
                List<String> add = new ArrayList<>(List.of("add", "--name", plan.name(), "--entity-id", c.entity,
                    "--metadata-file", file.toString(), "--attribute-profile", plan.profile(), "--mfa", plan.mfa()));
                if (!plan.cidrs().isBlank()) add.addAll(List.of("--cidrs", plan.cidrs()));
                if (Set.of("bypass", "sp-cidr-bypass").contains(plan.mfa())) add.add("--confirm-bypass");
                Response dry = command(add);
                require(dry, 7, "CLI_DRY_RUN_FAILED");
                String digest = key(dry.output(), "metadata_sha256");
                if (!digest.equals(c.hash) || !key(dry.output(), "entity_id").equals(c.entity))
                    throw new ImportException(6, "CLI_OUTPUT_UNRECOGNIZED: metadata digest/entity mismatch");
                if (apply) {
                    MetadataValidity.check(List.of(c), clock.instant());
                    if (!SafeFiles.hash(SafeFiles.read(file, SafeFiles.MAX_FILE)).equals(digest)) throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW");
                    add.addAll(List.of("--approve-sha256", digest, "--apply"));
                    require(command(add), 7, "CLI_APPLY_FAILED");
                    record(plan, "ADD_OK_VERIFY_PENDING");
                    verify(plan);
                    record(plan, deferAttributes ? "REGISTERED_ATTRIBUTES_PENDING" : c.accessPending() ? "REGISTERED_ACCESS_PENDING" : "REGISTERED_AND_VERIFIED");
                } else record(plan, deferAttributes ? "DRY_RUN_ATTRIBUTES_PENDING" : c.accessPending() ? "DRY_RUN_ACCESS_PENDING" : "DRY_RUN_OK");
            }
            return incomplete ? 5 : deferAttributes ? 11 : pending ? 10 : 0;
        } catch (Exception ex) {
            failure(ex);
            throw ex;
        } finally {
            finish();
        }
    }

    void verify(Plan plan) throws Exception {
        Response verified = command(List.of("verify", plan.name()));
        require(verified, 8, "CLI_VERIFY_FAILED");
        String text = verified.output();
        String entity = "entity_id=OK expected=" + plan.candidate().entity + " actual=" + plan.candidate().entity;
        String hash = "metadata_sha256=OK expected=" + plan.candidate().hash + " actual=" + plan.candidate().hash;
        if (!text.lines().anyMatch(entity::equals) || !text.lines().anyMatch(hash::equals)
                || !key(text, "attribute_profile").equals(plan.profile()) || !key(text, "mfa_profile").equals(plan.mfa())
                || !key(text, "status").equals("ACTIVE") || !key(text, "result").equals("OK"))
            throw new ImportException(8, "CLI_VERIFY_FAILED: target does not match exact entityID/hash/profile/MFA/ACTIVE state; do not re-add");
        Response mfa = command(List.of("mfa", "show"));
        require(mfa, 8, "CLI_VERIFY_FAILED");
        String expected = "sp=" + plan.name() + " status=ACTIVE mfa=" + plan.mfa() + " cidrs=" + plan.cidrs();
        List<String> actual = mfa.output().lines().filter(line -> line.startsWith("sp=" + plan.name() + " ")).toList();
        if (actual.size() != 1 || !actual.getFirst().equals(expected) || !"OK".equals(key(mfa.output(), "managed_policy_drift")))
            throw new ImportException(8, "CLI_VERIFY_FAILED: target MFA/CIDRs or managed policy drift differs; do not re-add");
    }

    private void record(Plan p, String stage) throws Exception {
        String line = Instant.now() + " " + p.name() + " " + stage + "\n";
        results.append(line);
        Path ledger = run.resolve("execution-state.tsv");
        if (!Files.exists(ledger)) SafeFiles.text(ledger, "stage\tentity\tname\tprofile\tmfa\tcidrs\tsha256 (base64 fields)\n");
        SafeFiles.append(ledger, MigrationBundle.fields(stage, p.candidate().entity, p.name(), p.profile(), p.mfa(), p.cidrs(), p.candidate().hash) + "\n");
    }

    void failure(Exception ex) {
        if (results.indexOf("STOPPED:") < 0) results.append("STOPPED: ")
            .append(SafeFiles.display(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()))
            .append("\nRemaining commands were not executed. Successful approvals/profiles/SPs are retained.\n");
    }

    void finish() throws Exception {
        for (var item : Map.of("cli-output.txt", transcript.toString(), "commands.txt", commands.toString(),
            "access-control-status.txt", accessResults.toString() + "Source authorization rules: NOT APPLIED by generic import\nAuthorization equivalence/browser allow-deny tests: NOT CHECKED\n",
            "result.txt", results.toString() + "SP-side trust switch: NOT CHECKED\nBrowser SSO: NOT CHECKED\nSource IdP modified: NO\n",
            "next-steps.txt", "Review execution-state.tsv/result.txt before retry. Use --bundle-dir and --resume RUN_ID with the same mode/flags. Never automatically remove successes.\n"
                + (deferAttributes ? "Registration phase only; see attribute-migration-todo.txt. Keep target isolated until resolver/mapping, attribute approvals/profiles/value filters, authorization and filtered AACLI/browser allow-deny checks are complete. none is not a global release deny rule.\n" : "")
                + "Configure SP-side trust in the new IdP and test attributes, NameID, login and MFA in a browser.\n").entrySet()) {
            Path target = run.resolve(item.getKey());
            if (!Files.exists(target)) SafeFiles.text(target, item.getValue());
        }
    }

    private String answer(String label, String fallback) throws ImportException {
        if (prompt == null) return fallback;
        String answer = prompt.ask(label + " [" + fallback + "]: ");
        return answer.isBlank() ? fallback : answer;
    }

    Response command(List<String> args) throws Exception {
        List<String> full = new ArrayList<>(); full.add(script.toString()); full.addAll(args);
        String display = full.stream().map(v -> "'" + SafeFiles.display(v).replace("'", "'\\''") + "'").collect(java.util.stream.Collectors.joining(" "));
        commands.append("# Existing CLI command; listed for review, never evaluated as shell text.\n").append(display).append('\n');
        Instant start = Instant.now();
        Response response = executor.execute(full, idp);
        transcript.append("\n").append(start).append(" ").append(display).append("\nexit=").append(response.exit()).append(" elapsed=")
            .append(Duration.between(start, Instant.now())).append('\n');
        for (String line : response.output().split("\\R")) transcript.append(SafeFiles.display(line)).append('\n');
        System.out.print(response.output().lines().map(SafeFiles::display).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
        return response;
    }

    static String key(String output, String key) throws ImportException {
        List<String> values = output.lines().filter(l -> l.startsWith(key + "=")).map(l -> l.substring(key.length() + 1)).toList();
        if (values.size() > 1) throw new ImportException(6, "CLI_OUTPUT_UNRECOGNIZED: duplicate " + key);
        return values.isEmpty() ? "" : values.getFirst();
    }
    private static Set<String> attributes(String csv) { return csv.isEmpty() ? Set.of() : new java.util.TreeSet<>(List.of(csv.split(","))); }
    static void require(Response response, int code, String reason) throws ImportException { if (response.exit() != 0) throw new ImportException(code, reason + ": existing CLI failed; see cli-output.txt"); }

    static Response process(List<String> command, Path idp) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GRAPHICALMATRIX_IDP_HOME", idp.toString());
        builder.environment().remove("IDP_HOME");
        builder.environment().remove("IDP_BASE_URL");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) {
                byte[] data = stream.readNBytes(2 * 1024 * 1024 + 1);
                if (data.length > 2 * 1024 * 1024) { process.destroyForcibly(); throw new IllegalStateException("CLI output exceeds limit"); }
                return data;
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        });
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new ImportException(7, "CLI_TIMEOUT: target state may require manual verification");
            return new Response(process.exitValue(), new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
