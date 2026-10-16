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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SpImportMain {
    public static void main(String[] args) {
        int exit;
        try { exit = run(ImportOptions.parse(args)); }
        catch (ImportException ex) { System.err.println("ERROR: " + SafeFiles.display(ex.getMessage())); exit = ex.exitCode; }
        catch (Exception ex) { System.err.println("ERROR: " + SafeFiles.display(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())); exit = 6; }
        System.exit(exit);
    }

    static int run(ImportOptions options) throws Exception {
        options.validate();
        if (options.flag("help")) { help(); return 0; }
        if (options.flag("version")) { System.out.println("2FAS-KW SP Import Tools " + version()); return 0; }
        ExistingCli.Prompt prompt = null;
        if (!options.flag("non-interactive") && System.console() != null)
            prompt = message -> { String answer = System.console().readLine("%s", message); if (answer == null) throw new ImportException(4, "cancelled at EOF"); return answer.trim(); };
        if (options.flag("apply") && prompt == null) throw new ImportException(2, "INTERACTIVE_TTY_REQUIRED: apply is only allowed from an interactive terminal");
        if (options.has("source-idp-home") && options.has("target-idp-home")
                && options.path("source-idp-home").toRealPath().equals(options.path("target-idp-home").toRealPath()))
            throw new ImportException(2, "SAME_IDP_APPLY_UNSUPPORTED: use a distinct source snapshot and target; adopt is a separate existing procedure");
        Path bundle;
        Analysis analysis;
        if (options.has("source-idp-home")) {
            analysis = ManagedSourceAnalyzer.analyze(options);
            bundle = MigrationBundle.save(analysis, options.output(), options.get("lang", "ja").equals("ja"));
        } else {
            bundle = options.path("bundle-dir");
            analysis = MigrationBundle.load(bundle);
        }
        boolean japanese = options.get("lang", "ja").equals("ja");
        if (options.flag("defer-attributes")) {
            analysis = DeferredRegistration.view(analysis);
            System.out.println(japanese ? "属性移行は後工程: profile=none固定。属性条件の保留は未解決のまま残します。共有policyはnoneでも属性を送信し得るため、移行先を隔離してください。"
                : "DEFERRED ATTRIBUTES: profile=none; source attribute holds remain unresolved. Shared policies may still release attributes. Keep target isolated.");
        }
        System.out.println("bundle=" + bundle.toAbsolutePath());
        System.out.println(ReviewPresentation.overview(analysis, japanese, "all"));
        if (!options.has("target-idp-home")) { restoreOutputOwnership(bundle); return analysis.held() ? 3 : analysis.accessPending() ? 10 : 0; }
        if (analysis.mode.equals("2faskw") && options.flag("registration-only"))
            throw new ImportException(2, "--registration-only cannot bypass managed 2FAS-KW policy migration");
        if (analysis.mode.equals("2faskw") && options.flag("confirm-metadata-review"))
            throw new ImportException(2, "--confirm-metadata-review is for generic source snapshots only; managed validation is unchanged");
        ExistingCli.validateInstallation(options.path("target-idp-home"), options.flag("apply"));
        List<Candidate> selected = select(analysis, options, prompt);
        if (selected.isEmpty()) throw new ImportException(3, "No unblocked SP was selected; see manual-actions.txt");
        Path runs = bundle.resolve("runs"); SafeFiles.directory(runs);
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path run = runs.resolve(runId); SafeFiles.directory(run);
        String fingerprint = MigrationBundle.fingerprint(bundle);
        ExistingCli cli = new ExistingCli(options.path("target-idp-home").toRealPath(), run, ExistingCli::process, prompt, options.flag("defer-attributes"));
        try {
            if (analysis.mode.equals("2faskw")) {
                if (options.flag("apply") && !options.flag("confirm-isolated-target")) throw new ImportException(5, "TARGET_ISOLATION_REQUIRED: use --confirm-isolated-target only after enforcing target isolation");
                ManagedMigration managed = new ManagedMigration(options.path("target-idp-home").toRealPath(), run, cli, prompt);
                try {
                    managed.prepare(analysis, selected, options.has("resume"));
                    String planned = managed.planText(fingerprint);
                    if (options.has("resume")) {
                        Path previous = SafeFiles.contained(bundle, "runs/" + options.get("resume", "") + "/plan.tsv");
                        if (!planned.equals(new String(SafeFiles.read(previous, 4 * 1024 * 1024), StandardCharsets.UTF_8)))
                            throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW: managed mode/selection/name/profile/policy/target differs from prior plan");
                    }
                    SafeFiles.text(run.resolve("plan.tsv"), planned);
                    SafeFiles.text(run.resolve("plan-sha256.txt"), SafeFiles.hash(planned.getBytes(StandardCharsets.UTF_8)) + "\n");
                    System.out.println("plan_sha256=" + SafeFiles.hash(planned.getBytes(StandardCharsets.UTF_8)) + "\ntarget_check=" + run.resolve("target-check.txt"));
                    if (!fingerprint.equals(MigrationBundle.fingerprint(bundle)))
                        throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW: bundle changed");
                    MigrationBundle.load(bundle);
                    Path staging = Files.createTempDirectory("2faskw-managed-import-");
                    try {
                        SafeFiles.directory(staging);
                        if (options.flag("apply") && ((Number) Files.getAttribute(staging, "unix:uid")).intValue() != 0) throw new ImportException(5, "ROOT_REQUIRED: run apply with sudo");
                        int result = managed.execute(options.flag("apply"), options.flag("confirm-isolated-target"), staging);
                        return result == 0 && analysis.held() ? 9 : result;
                    } finally { SafeFiles.deleteTree(staging); }
                } finally { managed.finish(); }
            }
            if (options.flag("apply")) {
                cli.authorizeMetadataReview(selected, options.flag("confirm-metadata-review"));
                cli.authorizeRegistrationOnly(selected, options.flag("registration-only"), options.flag("confirm-isolated-target"));
                cli.authorizeDeferredAttributes(options.flag("registration-only"), options.flag("confirm-isolated-target"));
            }
            List<ExistingCli.Plan> plans = cli.prepare(selected, options.has("resume"));
            String planned = planText(plans, options, fingerprint);
            if (options.has("resume")) {
                Path previous = SafeFiles.contained(bundle, "runs/" + options.get("resume", "") + "/plan.tsv");
                String original = new String(SafeFiles.read(previous, 4 * 1024 * 1024), StandardCharsets.UTF_8);
                if (!original.equals(planned)) throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW: resume selection/name/profile/MFA/CIDRs or bundle differs from prior plan");
            }
            SafeFiles.text(run.resolve("plan.tsv"), planned);
            if (options.flag("apply")) {
                String approval = "REGISTER " + plans.size();
                if (!approval.equals(prompt.ask("Source/target differ; successful commands will not be rolled back as a batch. Type " + approval + ": ")))
                    throw new ImportException(4, "registration cancelled");
            }
            if (!fingerprint.equals(MigrationBundle.fingerprint(bundle)))
                throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW: bundle manifest changed");
            Analysis checked = MigrationBundle.load(bundle);
            if (options.flag("defer-attributes")) checked = DeferredRegistration.view(checked);
            for (Candidate c : selected) if (checked.candidates.stream().noneMatch(actual -> actual.entity.equals(c.entity) && actual.hash.equals(c.hash)
                    && actual.attributes.equals(c.attributes) && java.util.Objects.equals(actual.access, c.access)
                    && !actual.blocked())) throw new ImportException(6, "INPUT_CHANGED_AFTER_REVIEW: selected source changed");
            int result = cli.execute(plans, options.flag("apply"));
            return result == 0 && analysis.held() ? 9 : result;
        } catch (Exception ex) {
            cli.failure(ex); throw ex;
        } finally {
            cli.finish();
            restoreOutputOwnership(bundle);
            System.out.println("run_id=" + runId + "\nresult_file=" + run.resolve("result.txt"));
        }
    }

    static List<Candidate> select(Analysis analysis, ImportOptions options, ExistingCli.Prompt prompt) throws Exception {
        List<Candidate> eligible = analysis.candidates.stream().filter(c -> !c.blocked())
            .filter(c -> options.all("entity-id").isEmpty() || options.all("entity-id").contains(c.entity)).toList();
        if (prompt == null) return eligible;
        if (eligible.isEmpty()) System.out.println(options.get("lang", "ja").equals("ja")
            ? "登録候補はありません。d 番号で保留原因を確認できます。登録するには保留原因を解消して再解析してください。"
            : "No eligible SPs. Use d NUMBER to inspect holds; resolve them and re-analyze before registration.");
        while (true) {
            boolean japanese = options.get("lang", "ja").equals("ja");
            String answer = prompt.ask(japanese ? "対象番号(1,4) / d 番号 / held / ready / all / q: "
                : "Select numbers (1,4) / d NUMBER / held / ready / all / q: ");
            if (answer.equals("q") || answer.isBlank()) throw new ImportException(4, "selection cancelled");
            if (Set.of("held", "ready", "all").contains(answer)) {
                System.out.println(ReviewPresentation.overview(analysis, japanese, answer));
                continue;
            }
            if (answer.matches("d [0-9]+")) {
                try {
                    int index = Integer.parseInt(answer.substring(2)) - 1;
                    if (index < 0 || index >= analysis.candidates.size()) throw new IllegalArgumentException();
                    System.out.println(ReviewPresentation.detail(analysis.candidates.get(index), index + 1, japanese));
                } catch (IllegalArgumentException ex) {
                    System.out.println(japanese ? "一覧にある番号を指定してください。" : "Specify a number from the inventory.");
                }
                continue;
            }
            List<Candidate> selected = new ArrayList<>();
            try {
                for (String value : answer.split(",", -1)) {
                    Candidate c = analysis.candidates.get(Integer.parseInt(value.trim()) - 1);
                    if (c.blocked() || selected.contains(c) || !eligible.contains(c)) throw new IllegalArgumentException();
                    selected.add(c);
                }
                return selected;
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                System.out.println("Select unique, unblocked candidate numbers / 移行保留以外の番号を指定してください。");
            }
        }
    }

    static String planText(List<ExistingCli.Plan> plans, ImportOptions options, String fingerprint) throws Exception {
        return plans.stream().map(p -> MigrationBundle.fields(p.candidate().entity, p.name(), p.profile(), p.mfa(), p.cidrs(), p.candidate().hash))
            .collect(java.util.stream.Collectors.joining("\n", (options.flag("defer-attributes") ? "plan-v4\tdeferred-attributes=true\t" : "plan-v3\t") + fingerprint + "\t"
                + options.path("target-idp-home").toRealPath() + "\tregistration-only=" + options.flag("registration-only")
                + "\tisolated=" + options.flag("confirm-isolated-target")
                + "\tmetadata-reviewed=" + options.flag("confirm-metadata-review") + "\n", "\n"));
    }

    private static void restoreOutputOwnership(Path root) throws Exception {
        String sudoUser = System.getenv("SUDO_USER");
        if (sudoUser == null || !sudoUser.matches("[A-Za-z_][A-Za-z0-9_.-]*") || !System.getProperty("user.name").equals("root")) return;
        var user = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(sudoUser);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) if (!Files.isSymbolicLink(path)) SafeFiles.owner(path, user);
        }
    }

    static String version() { String value = SpImportMain.class.getPackage().getImplementationVersion(); return value == null ? "development" : value; }

    private static void help() {
        System.out.println("""
            2FAS-KW SP Import Tools - one-time, read-only source analysis and existing-CLI registration.
            Usage:
              graphicalmatrix-sp-import.sh --source-idp-home PATH [--analysis-only] [--non-interactive]
              graphicalmatrix-sp-import.sh --bundle-dir PATH --target-idp-home PATH [--apply]
            Options:
              --metadata-config FILE  Additional provider config (repeatable).
              --metadata-file FILE    Standalone metadata; source registration is not inferred (repeatable).
              --property-source FILE  Additional UTF-8 Java properties; conflicting definitions are held.
              --context-check-config FILE  Self-contained source ContextCheck snapshot (default: conf/intercept/context-check-intercept-config.xml).
              --path-map OLD=NEW      Boundary-aware snapshot path mapping (repeatable).
              --entity-id VALUE      Filter exact entityID (repeatable).
              --export-dir PATH      Default: launcher directory/sp-migration, independent of current directory.
              --resume RUN_ID        Recheck prior plan and target; never blindly re-add a completed SP.
              --source-type MODE     auto (default), shibboleth, or 2faskw. Damaged registry never falls back.
              --registration-only    Generic apply with pending authorization; rules are NOT migrated.
              --defer-attributes     Generic target registration with profile=none; no approval/profile creation; attributes remain pending.
              --extract-aggregate    Generic source analysis only: extract reviewed unsigned entity/role snapshots with original evidence.
              --confirm-metadata-review  Apply only after independent original signature/trust, validity and filter review (typed confirmation required).
              --confirm-isolated-target  Required for managed or registration-only apply; enforce isolation separately.
              --non-interactive      Analysis/export/target dry-run only; never apply.
              --lang ja|en           Japanese review guidance by default; original diagnostic evidence stays in English.
              --version             Show tool version.
              --help                Show this help.
            Apply requires root and an interactive terminal. Source IdP, DB and authentication code are not changed.
            Exit: 0 completed; 2 usage; 3 held analysis; 4 cancelled; 5 target prerequisites; 6 bundle/input;
                  7 CLI command; 8 verify failed; 9 selected completed but other source items held;
                  10 registration analyzed/checked but source authorization remains pending (not full migration);
                  11 registration phase checked/completed with attributes deferred (not full migration).
            Registering an SP does not switch SP-side trust or prove browser SSO success.
            Interactive selection: held / ready / all filter views; d NUMBER shows checks and original evidence.
            Review guidance is also saved in review-checklist.txt; it is not a verification result.
            """);
    }
    private SpImportMain() { }
}
