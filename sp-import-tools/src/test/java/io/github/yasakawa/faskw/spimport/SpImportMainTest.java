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

class SpImportMainTest {
    @TempDir Path temp;

    SourceAnalyzerTest source() throws Exception {
        var fixture = new SourceAnalyzerTest(); fixture.home = temp.resolve("source"); fixture.fixture(); return fixture;
    }

    @Test void analysisEntryExportsReportsWithoutChangingSource() throws Exception {
        var fixture = source(); Path tool = Files.createDirectory(temp.resolve("tools"));
        String before = Files.readString(fixture.home.resolve("conf/attribute-filter.xml"));
        var options = ImportOptions.parse("--tool-home", tool.toString(), "--source-idp-home", fixture.home.toString(), "--non-interactive");
        assertEquals(0, SpImportMain.run(options));
        Path bundle = tool.resolve("sp-migration");
        assertEquals(1, MigrationBundle.load(bundle).candidates.size());
        assertTrue(Files.readString(bundle.resolve("analysis.txt")).contains("library.example.org"));
        assertEquals(before, Files.readString(fixture.home.resolve("conf/attribute-filter.xml")));
        assertThrows(ImportModel.ImportException.class, () -> SpImportMain.run(options));
        assertEquals(1, MigrationBundle.load(bundle).candidates.size());
    }

    @Test void heldAnalysisStillWritesDetailedReasonAndRemedy() throws Exception {
        var fixture = source(); fixture.write("conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("xsi:type=\"ANY\"", "xsi:type=\"Script\""));
        Path tool = Files.createDirectory(temp.resolve("tools"));
        assertEquals(3, SpImportMain.run(ImportOptions.parse("--tool-home", tool.toString(), "--source-idp-home", fixture.home.toString(), "--analysis-only")));
        String actions = Files.readString(tool.resolve("sp-migration/manual-actions.txt"));
        assertTrue(actions.contains("ATTRIBUTE_CONDITION_UNSUPPORTED")); assertTrue(actions.contains("action:"));
    }

    @Test void sameIdpCannotBeUsedAsBothSourceAndTarget() throws Exception {
        var fixture = source();
        var ex = assertThrows(ImportModel.ImportException.class, () -> SpImportMain.run(ImportOptions.parse("--source-idp-home", fixture.home.toString(), "--target-idp-home", fixture.home.toString())));
        assertEquals(2, ex.exitCode); assertFalse(Files.exists(fixture.home.resolve("sp-migration")));
    }
    @Test void managedEntryExportsV2WithoutChangingManagementFiles() throws Exception {
        Path home = temp.resolve("managed"); ManagedFixture.source(home); ManagedFixture.access(home, false);
        Path tool = Files.createDirectory(temp.resolve("tools"));
        byte[] before = Files.readAllBytes(home.resolve(ManagedData.REGISTRY));
        assertEquals(0, SpImportMain.run(ImportOptions.parse("--tool-home", tool.toString(), "--source-idp-home", home.toString(), "--source-type", "2faskw", "--analysis-only")));
        var bundle = MigrationBundle.load(tool.resolve("sp-migration"));
        assertEquals("2faskw", bundle.mode); assertFalse(bundle.candidates.getFirst().managed.access().enabled());
        assertArrayEquals(before, Files.readAllBytes(home.resolve(ManagedData.REGISTRY)));
        assertTrue(Files.exists(tool.resolve("sp-migration/managed-policy-diff.txt")));
    }
    @Test void genericAnalysisReturnsDistinctPendingExitAndSeparateReport() throws Exception {
        var fixture = source();
        fixture.write("conf/relying-party.xml", SourceAccessAnalyzerTest.HEADER
            + "<util:list id=\"shibboleth.RelyingPartyOverrides\">"
            + SourceAccessAnalyzerTest.override(SourceAnalyzerTest.ENTITY, "context-check") + "</util:list></beans>");
        Path tool = Files.createDirectory(temp.resolve("tools"));
        assertEquals(10, SpImportMain.run(ImportOptions.parse("--tool-home", tool.toString(), "--source-idp-home", fixture.home.toString(), "--non-interactive")));
        var c = MigrationBundle.load(tool.resolve("sp-migration")).candidates.getFirst();
        assertFalse(c.blocked()); assertEquals("UNRESOLVED", c.accessState());
        assertTrue(Files.readString(tool.resolve("sp-migration/access-control.txt")).contains("context_check_unresolved"));
    }
    @Test void managedBundleCannotUseRegistrationOnlyShortcut() throws Exception {
        Path home = temp.resolve("managed"); ManagedFixture.source(home);
        var source = ManagedSourceAnalyzer.analyze(ImportOptions.parse("--source-idp-home", home.toString()));
        Path bundle = MigrationBundle.save(source, temp.resolve("bundle"));
        var ex = assertThrows(ImportModel.ImportException.class, () -> SpImportMain.run(ImportOptions.parse("--bundle-dir", bundle.toString(), "--target-idp-home", home.toString(), "--registration-only", "--non-interactive")));
        assertEquals(2, ex.exitCode);
    }
    @Test void managedBundleCannotDeferAttributes() throws Exception {
        Path home = temp.resolve("managed"); ManagedFixture.source(home);
        var source = ManagedSourceAnalyzer.analyze(ImportOptions.parse("--source-idp-home", home.toString()));
        Path bundle = MigrationBundle.save(source, temp.resolve("bundle"));
        var ex = assertThrows(ImportModel.ImportException.class, () -> SpImportMain.run(ImportOptions.parse("--bundle-dir", bundle.toString(),
            "--target-idp-home", home.toString(), "--defer-attributes", "--non-interactive")));
        assertEquals(2, ex.exitCode);
    }
    @Test void resumePlanBindsDeferredModeAndIsolationEvenWithSameNoneProfile() throws Exception {
        var c = new ExistingCliTest().candidate();
        var plans = java.util.List.of(new ExistingCli.Plan(c, c.name, "none", "force", "", java.util.Map.of(), false, false));
        String[] base = {"--bundle-dir", temp.toString(), "--target-idp-home", temp.toString()};
        String ordinary = SpImportMain.planText(plans, ImportOptions.parse(base), "fingerprint");
        var args = new java.util.ArrayList<>(java.util.List.of(base)); args.add("--defer-attributes");
        String deferred = SpImportMain.planText(plans, ImportOptions.parse(args.toArray(String[]::new)), "fingerprint");
        assertNotEquals(ordinary, deferred); assertTrue(deferred.startsWith("plan-v4\tdeferred-attributes=true\t"));
        args.add("--confirm-isolated-target");
        assertNotEquals(deferred, SpImportMain.planText(plans, ImportOptions.parse(args.toArray(String[]::new)), "fingerprint"));
    }
    @Test void genericHeldAttributeBundleCompletesRealLauncherDryRunWithoutAttributeCommands() throws Exception {
        var fixture = source();
        fixture.write("conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("xsi:type=\"ANY\"", "xsi:type=\"Script\""));
        var source = ManagedSourceAnalyzer.analyze(ImportOptions.parse("--source-idp-home", fixture.home.toString()));
        Path bundle = MigrationBundle.save(source, temp.resolve("bundle"));
        String before = MigrationBundle.fingerprint(bundle);
        var c = source.candidates.getFirst();
        Path target = temp.resolve("target");
        Files.createDirectories(target.resolve("bin"));
        Files.createDirectories(target.resolve("conf/graphicalmatrix"));
        Files.writeString(target.resolve("conf/graphicalmatrix/sp-management.properties"), "graphicalmatrix.sp.management.enabled=true\n");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "1.4.0");
        Files.createDirectories(target.resolve("edit-webapp/WEB-INF/lib"));
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(target.resolve("edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.4.0.jar")), manifest)) { }
        Path script = target.resolve("bin/graphicalmatrix-sp.sh");
        Files.writeString(script, """
            #!/usr/bin/env bash
            set -euo pipefail
            printf '%s\n' "$*" >> "$GRAPHICALMATRIX_IDP_HOME/calls.txt"
            case "$*" in
              init) printf 'metadata_provider=present\\nattribute_block=present\\n' ;;
              'mfa show') printf 'managed_policy_drift=OK\\n' ;;
              'attributes profile list') printf 'none 0 builtin OK -\\n' ;;
              'attributes profile show none') printf 'source=builtin\\nstatus=OK\\nattributes=\\n' ;;
              status*) printf 'status=NOT_FOUND entity_id=%s\\n' 'ENTITY' ;;
              add*) printf 'entity_id=%s\\nmetadata_sha256=%s\\n' 'ENTITY' 'HASH' ;;
              *) printf 'Unexpected command\\n' >&2; exit 1 ;;
            esac
            """.replace("ENTITY", c.entity).replace("HASH", c.hash));
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        assertEquals(11, SpImportMain.run(ImportOptions.parse("--bundle-dir", bundle.toString(), "--target-idp-home", target.toString(),
            "--defer-attributes", "--non-interactive")));
        assertEquals(before, MigrationBundle.fingerprint(bundle));
        assertTrue(MigrationBundle.load(bundle).candidates.getFirst().blocked());
        String calls = Files.readString(target.resolve("calls.txt"));
        assertFalse(calls.contains("discover") || calls.contains("approve") || calls.contains("create") || calls.contains("--apply"));
        assertTrue(calls.contains("--attribute-profile none"));
        try (var runs = Files.list(bundle.resolve("runs"))) {
            Path run = runs.findFirst().orElseThrow();
            assertTrue(Files.readString(run.resolve("plan.tsv")).startsWith("plan-v4\tdeferred-attributes=true\t"));
            assertTrue(Files.readString(run.resolve("attribute-migration-todo.txt")).contains("ATTRIBUTE_CONDITION_UNSUPPORTED"));
            assertTrue(Files.readString(run.resolve("result.txt")).contains("DRY_RUN_ATTRIBUTES_PENDING"));
        }
    }
}
