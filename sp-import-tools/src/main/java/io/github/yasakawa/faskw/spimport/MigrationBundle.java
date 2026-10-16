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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MigrationBundle {
    static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    static final String HEADER = "2faskw-sp-migration\t1";
    static final String LEGACY_SEPARATED_HEADER = "2faskw-sp-migration\t3\tshibboleth";
    static final String SEPARATED_HEADER = "2faskw-sp-migration\t4\tshibboleth";
    static final String MANAGED_HEADER = "2faskw-sp-migration\t2\t2faskw";
    static Path save(Analysis analysis, Path requested) throws Exception {
        return save(analysis, requested, true);
    }
    static Path save(Analysis analysis, Path requested, boolean japanese) throws Exception {
        Path output = requested.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new ImportException(6, "OUTPUT_EXISTS: use --bundle-dir to reuse this bundle or --export-dir with a new directory; existing results are never overwritten: " + output);
        SafeFiles.directory(output.getParent());
        Path temporary = Files.createTempDirectory(output.getParent(), ".sp-migration-");
        try {
            SafeFiles.directory(temporary.resolve("metadata"));
            SafeFiles.directory(temporary.resolve("evidence"));
            SafeFiles.directory(temporary.resolve("runs"));
            if (!analysis.metadataSources.isEmpty()) SafeFiles.directory(temporary.resolve("evidence/metadata-sources"));
            StringBuilder manifest = new StringBuilder((analysis.mode.equals("2faskw") ? MANAGED_HEADER : SEPARATED_HEADER) + "\n");
            if (analysis.mode.equals("2faskw")) manifest.append("G\t").append(fields(new String(StrictJson.write(analysis.globalMfa), StandardCharsets.UTF_8))).append('\n');
            Set<String> sourceHashes = analysis.candidates.stream().map(c -> c.sourceMetadataHash).filter(h -> !h.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            long metadataBytes = 0;
            for (String hash : sourceHashes) {
                byte[] source = analysis.metadataSources.get(hash);
                if (analysis.mode.equals("2faskw") || source == null || source.length > SafeFiles.MAX_FILE || !SafeFiles.hash(source).equals(hash))
                    throw new ImportException(6, "BUNDLE_INVALID: original metadata evidence missing or inconsistent");
                metadataBytes += source.length;
                if (metadataBytes > 256L * 1024 * 1024) throw new ImportException(6, "BUNDLE_LIMIT_EXCEEDED: original metadata evidence exceeds 256 MiB");
                SafeFiles.write(temporary.resolve("evidence/metadata-sources/" + hash + ".xml"), source);
                manifest.append("R\t").append(fields(hash)).append('\n');
            }
            StringBuilder inventory = new StringBuilder("SP inventory / SP一覧\nStatic analysis only; runtime use and metadata signatures are not verified.\n\n");
            StringBuilder report = new StringBuilder("Source IdP analysis / 元IdP解析\nsource=" + SafeFiles.display(analysis.source.toString()) + "\n\n");
            StringBuilder plan = new StringBuilder("Migration candidates / 移行候補\nNo target changes have been made.\n\n");
            StringBuilder actions = new StringBuilder("Manual actions / 必要な手作業\nConfirm source authenticity, target resolver/mapping, host allowlists, init/restart, NameID/encryption, and SP-side IdP trust.\n\n");
            StringBuilder accessReport = new StringBuilder("Source access-control assessment / 元IdPアクセス制御\n"
                + "SP registration and access-control migration are separate. No source authorization rule is applied by generic import.\n"
                + "NONE_OBSERVED is not a non-restriction guarantee. Keep a registration-only target isolated until equivalence and browser tests pass.\n\n");
            for (Diagnostic diagnostic : analysis.diagnostics) {
                report.append(diagnostic);
                manifest.append("D\t").append(fields(diagnostic.severity(), diagnostic.code(), diagnostic.location(), diagnostic.detail(), diagnostic.remedy())).append('\n');
            }
            int index = 0;
            Set<Diagnostic> inheritedBlocks = analysis.diagnostics.stream()
                .filter(d -> Set.of("BLOCK", "ERROR").contains(d.severity())).collect(java.util.stream.Collectors.toSet());
            for (Candidate c : analysis.candidates) {
                if (!analysis.mode.equals("2faskw") && c.access == null) c.access = new AccessAssessment("LEGACY_UNASSESSED", List.of("Source authorization was not assessed; re-export with the current tool."));
                if (c.bytes != null) {
                    if (c.bytes.length > SafeFiles.MAX_FILE) throw new ImportException(6, "BUNDLE_LIMIT_EXCEEDED: entity snapshot exceeds 50 MiB");
                    metadataBytes += c.bytes.length;
                    if (metadataBytes > 256L * 1024 * 1024) throw new ImportException(6, "BUNDLE_LIMIT_EXCEEDED: original and extracted metadata exceed 256 MiB");
                    c.metadata = "metadata/" + (++index) + "-" + c.name + ".xml";
                    SafeFiles.write(temporary.resolve(c.metadata), c.bytes);
                }
                manifest.append("S\t").append(fields(c.entity, c.name, c.source, c.provider, c.metadata, c.hash,
                    String.join(",", c.attributes), String.join("\n", c.facts),
                    encodeDiagnostics(c.diagnostics.stream().filter(d -> !inheritedBlocks.contains(d)).toList()))).append('\n');
                if (c.managed != null) manifest.append("M\t").append(fields(c.entity, new String(StrictJson.write(ManagedData.intentObject(c.managed)), StandardCharsets.UTF_8))).append('\n');
                if (!analysis.mode.equals("2faskw")) manifest.append("A\t").append(fields(c.name, c.access.status(), String.join("\n", c.access.evidence()))).append('\n');
                if (!c.sourceMetadataHash.isEmpty()) manifest.append("X\t").append(fields(c.name, c.sourceMetadataHash)).append('\n');
                inventory.append(c.name).append(" | ").append(c.state()).append(" | ").append(SafeFiles.display(c.entity))
                    .append(" | registration=").append(c.registrationState()).append(" | access_control=").append(c.accessState()).append('\n');
                String detail = detail(c);
                report.append(detail);
                plan.append(c.name).append(" attributes=").append(String.join(",", c.attributes)).append(" state=").append(c.state()).append('\n');
                for (Diagnostic d : c.diagnostics) actions.append(c.name).append(" ").append(d);
                accessReport.append(accessDetail(c));
                if (c.accessPending()) actions.append(c.name).append(" ACCESS_CONTROL_MANUAL_REQUIRED (separate from registration)\n")
                    .append(accessDetail(c)).append("  action: Reproduce authorization and test allowed/denied users before exposing the target.\n");
            }
            byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            requireManifestSize(manifestBytes.length);
            SafeFiles.write(temporary.resolve("manifest.tsv"), manifestBytes);
            SafeFiles.text(temporary.resolve("inventory.txt"), inventory.toString());
            SafeFiles.text(temporary.resolve("analysis.txt"), report.toString());
            SafeFiles.text(temporary.resolve("migration-plan.txt"), plan.toString());
            SafeFiles.text(temporary.resolve("manual-actions.txt"), actions.toString());
            SafeFiles.text(temporary.resolve("access-control.txt"), accessReport.toString());
            SafeFiles.text(temporary.resolve("review-checklist.txt"), ReviewPresentation.overview(analysis, japanese, "all"));
            SafeFiles.text(temporary.resolve("migration-actions.txt"), MigrationGuidance.report(analysis, japanese));
            if (analysis.mode.equals("2faskw")) SafeFiles.text(temporary.resolve("managed-policy-diff.txt"),
                "Source mode: 2faskw\nSource global MFA (not copied): " + SafeFiles.display(analysis.globalMfa.toString())
                + "\n" + analysis.candidates.stream().map(MigrationBundle::detail).collect(java.util.stream.Collectors.joining())
                + "\nTarget comparison is produced in runs/RUN_ID/target-check.txt. Browser access/MFA and SP trust tests remain manual.\n");
            SafeFiles.text(temporary.resolve("evidence/source-resource-summary.txt"), String.join("\n", analysis.evidence) + "\n");
            Files.move(temporary, output);
            return output;
        } finally { SafeFiles.deleteTree(temporary); }
    }

    static Analysis load(Path requested) throws Exception {
        if (Files.isSymbolicLink(requested)) throw new ImportException(6, "BUNDLE_INVALID: symlink bundle root");
        Path root = requested.toRealPath();
        String contents = new String(readManifest(root), StandardCharsets.UTF_8);
        String[] lines = contents.split("\n", -1);
        if (!Set.of(HEADER, MANAGED_HEADER, LEGACY_SEPARATED_HEADER, SEPARATED_HEADER).contains(lines[0])) throw new ImportException(6, "BUNDLE_INVALID: unsupported manifest format");
        boolean snapshots = lines[0].equals(SEPARATED_HEADER);
        boolean separated = snapshots || lines[0].equals(LEGACY_SEPARATED_HEADER);
        Analysis result = new Analysis();
        if (lines[0].equals(MANAGED_HEADER)) result.mode = "2faskw";
        result.source = root;
        Set<String> entities = new HashSet<>();
        Set<String> paths = new HashSet<>();
        long totalBytes = 0;
        boolean globals = false;
        java.util.Map<String, ManagedData.Intent> managed = new java.util.LinkedHashMap<>();
        java.util.Map<String, AccessAssessment> access = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> originals = new java.util.LinkedHashMap<>();
        java.util.Map<String, Set<String>> originalEntities = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] columns = lines[i].split("\t", -1);
            if (snapshots && columns[0].equals("R") && columns.length == 2) {
                String hash = decode(columns[1]);
                if (!hash.matches("[a-f0-9]{64}") || result.metadataSources.containsKey(hash)) throw new ImportException(6, "BUNDLE_INVALID: original metadata hash/duplicate");
                byte[] data = SafeFiles.read(SafeFiles.contained(root, "evidence/metadata-sources/" + hash + ".xml"), SafeFiles.MAX_FILE);
                totalBytes += data.length;
                if (totalBytes > 256L * 1024 * 1024 || !SafeFiles.hash(data).equals(hash)) throw new ImportException(6, "BUNDLE_INVALID: original metadata size/hash mismatch");
                var original = SecureXml.parse(data, "original metadata");
                if (!SecureXml.MD.equals(original.namespace) || !Set.of("EntityDescriptor", "EntitiesDescriptor").contains(original.name)) throw new ImportException(6, "BUNDLE_INVALID: original metadata root");
                result.metadataSources.put(hash, data);
                originalEntities.put(hash, original.descendants("EntityDescriptor").stream().filter(n -> SecureXml.MD.equals(n.namespace))
                    .map(n -> n.attr("entityID")).collect(java.util.stream.Collectors.toSet()));
            } else if (snapshots && columns[0].equals("X") && columns.length == 3) {
                if (originals.putIfAbsent(decode(columns[1]), decode(columns[2])) != null) throw new ImportException(6, "BUNDLE_INVALID: duplicate snapshot evidence reference");
            } else if (result.mode.equals("2faskw") && columns[0].equals("G") && columns.length == 2 && !globals) {
                var map = StrictJson.parse(decode(columns[1]).getBytes(StandardCharsets.UTF_8));
                if (!map.keySet().equals(ManagedData.GLOBAL_DEFAULTS.keySet())) throw new ImportException(6, "BUNDLE_INVALID: global MFA contract");
                var global = new java.util.TreeMap<String, String>();
                for (String key : map.keySet()) global.put(key, StrictJson.string(map, key));
                result.globalMfa = java.util.Collections.unmodifiableMap(global); globals = true;
            } else if (result.mode.equals("2faskw") && columns[0].equals("M") && columns.length == 3) {
                String entity = decode(columns[1]);
                if (managed.putIfAbsent(entity, ManagedData.intent(StrictJson.parse(decode(columns[2]).getBytes(StandardCharsets.UTF_8)))) != null)
                    throw new ImportException(6, "BUNDLE_INVALID: duplicate managed intent");
            } else if (separated && columns[0].equals("A") && columns.length == 4) {
                String name = decode(columns[1]); String status = decode(columns[2]); String text = decode(columns[3]);
                List<String> evidence = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
                if (evidence.size() > 1000 || !name.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new ImportException(6, "BUNDLE_INVALID: access assessment limits");
                try {
                    if (access.putIfAbsent(name, new AccessAssessment(status, evidence)) != null) throw new IllegalArgumentException("duplicate access assessment");
                } catch (IllegalArgumentException ex) { throw new ImportException(6, "BUNDLE_INVALID: access assessment status/identity"); }
            } else if (columns[0].equals("D") && columns.length == 6) {
                result.diagnostics.add(diagnostic(columns, 1));
            } else if (columns[0].equals("S") && columns.length == 10) {
                String[] values = new String[9];
                for (int j = 0; j < values.length; j++) values[j] = decode(columns[j + 1]);
                Candidate c = new Candidate(values[0], values[2], values[3]);
                if (c.entity.isBlank() || !SafeFiles.display(c.entity).equals(c.entity)) throw new ImportException(6, "BUNDLE_INVALID: invalid entityID");
                c.name = values[1];
                if (!c.name.matches("[a-z0-9][a-z0-9-]{0,62}"))
                    throw new ImportException(6, "BUNDLE_INVALID: invalid management name");
                c.metadata = values[4]; c.hash = values[5];
                if (!values[6].isEmpty()) for (String attr : values[6].split(",", -1)) {
                    if (!attr.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") || !c.attributes.add(attr)) throw new ImportException(6, "BUNDLE_INVALID: invalid attribute IDs");
                }
                if (!values[7].isEmpty()) c.facts.addAll(List.of(values[7].split("\n")));
                if (!values[8].isEmpty()) for (String line : values[8].split("\n")) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 5) throw new ImportException(6, "BUNDLE_INVALID: diagnostic fields");
                    c.diagnostics.add(diagnostic(parts, 0));
                }
                if (!c.metadata.isEmpty()) {
                    if (!c.metadata.startsWith("metadata/") || !paths.add(c.metadata)) throw new ImportException(6, "BUNDLE_INVALID: duplicate/nonmetadata path");
                    c.bytes = SafeFiles.read(SafeFiles.contained(root, c.metadata), SafeFiles.MAX_FILE);
                    totalBytes += c.bytes.length;
                    if (totalBytes > 256L * 1024 * 1024) throw new ImportException(6, "BUNDLE_INVALID: total metadata size limit");
                    if (!SafeFiles.hash(c.bytes).equals(c.hash)) throw new ImportException(6, "BUNDLE_INVALID: metadata SHA-256 mismatch");
                    var node = SecureXml.parse(c.bytes, c.metadata);
                    if (!node.name.equals("EntityDescriptor") || !node.namespace.equals(SecureXml.MD) || !node.attr("entityID").equals(c.entity))
                        throw new ImportException(6, "BUNDLE_INVALID: metadata entityID mismatch");
                }
                result.candidates.add(c);
            } else throw new ImportException(6, "BUNDLE_INVALID: unknown record at line " + (i + 1));
            if (result.candidates.size() > 10000) throw new ImportException(6, "BUNDLE_INVALID: candidate limit");
        }
        for (Candidate c : result.candidates) {
            for (Diagnostic d : result.diagnostics) if (Set.of("BLOCK", "ERROR").contains(d.severity()) && !c.diagnostics.contains(d)) c.diagnostics.add(d);
        }
        for (Candidate c : result.candidates) {
            if (c.metadata.isEmpty() && !c.blocked()) throw new ImportException(6, "BUNDLE_INVALID: missing metadata for candidate");
            c.sourceMetadataHash = originals.remove(c.name);
            if (c.sourceMetadataHash == null) c.sourceMetadataHash = "";
            if (!c.sourceMetadataHash.isEmpty() && !result.metadataSources.containsKey(c.sourceMetadataHash)) throw new ImportException(6, "BUNDLE_INVALID: original metadata reference missing");
            if (!c.sourceMetadataHash.isEmpty() && !originalEntities.get(c.sourceMetadataHash).contains(c.entity))
                throw new ImportException(6, "BUNDLE_INVALID: snapshot entityID is not present in original metadata");
            if (c.diagnostics.stream().anyMatch(d -> d.code().equals("METADATA_AGGREGATE_EXTRACTED")) && c.sourceMetadataHash.isEmpty())
                throw new ImportException(6, "BUNDLE_INVALID: extracted metadata requires original evidence");
            if (separated) {
                c.access = access.remove(c.name);
                if (c.access == null) throw new ImportException(6, "BUNDLE_INVALID: access assessment missing");
            } else if (result.mode.equals("shibboleth")) c.access = new AccessAssessment("LEGACY_UNASSESSED",
                List.of("Legacy v1 bundle does not contain source authorization evidence; re-export before cutover."));
            c.managed = managed.remove(c.entity);
            if (result.mode.equals("2faskw") && !c.blocked() && c.managed == null) throw new ImportException(6, "BUNDLE_INVALID: managed intent missing");
            if (c.managed != null) {
                Set<String> profile = ManagedData.profileAttributes(c);
                if (c.managed.release() != null) {
                    Set<String> effective = new java.util.TreeSet<>(profile);
                    var additional = c.managed.release().additionalAttributes();
                    if (additional.stream().anyMatch(profile::contains)) throw new ImportException(6, "BUNDLE_INVALID: overlapping release contract");
                    effective.addAll(additional);
                    if (!effective.equals(c.attributes)) throw new ImportException(6, "BUNDLE_INVALID: effective release attributes differ");
                }
                Set<String> needed = new java.util.TreeSet<>(profile);
                if (c.managed.access() != null) { needed.addAll(c.managed.access().allow().keySet()); needed.addAll(c.managed.access().deny().keySet()); }
                if (!needed.equals(c.managed.approvals().stream().map(ManagedData.Approval::id).collect(java.util.stream.Collectors.toSet()))
                        || c.managed.approvals().stream().anyMatch(a -> profile.contains(a.id()) && !a.governance().equals("release-approved")))
                    throw new ImportException(6, "BUNDLE_INVALID: approval/attribute dependencies differ");
            }
            if (!entities.add(c.entity) && result.candidates.stream().filter(other -> other.entity.equals(c.entity)).anyMatch(other -> !other.blocked()))
                throw new ImportException(6, "BUNDLE_INVALID: unblocked duplicate entityID");
        }
        if (result.mode.equals("2faskw") && (!globals || !managed.isEmpty())) throw new ImportException(6, "BUNDLE_INVALID: global/intent reference missing");
        if (!access.isEmpty()) throw new ImportException(6, "BUNDLE_INVALID: orphan access assessment");
        if (!originals.isEmpty() || !result.candidates.stream().map(c -> c.sourceMetadataHash).filter(h -> !h.isEmpty()).collect(java.util.stream.Collectors.toSet()).equals(result.metadataSources.keySet()))
            throw new ImportException(6, "BUNDLE_INVALID: orphan original metadata evidence");
        return result;
    }

    static byte[] readManifest(Path root) throws Exception {
        Path file = SafeFiles.contained(root, "manifest.tsv");
        requireManifestSize(Files.size(file));
        return SafeFiles.read(file, MAX_MANIFEST_BYTES);
    }

    static String fingerprint(Path root) throws Exception { return SafeFiles.hash(readManifest(root)); }

    private static void requireManifestSize(long size) throws ImportException {
        if (size > MAX_MANIFEST_BYTES) throw new ImportException(6,
            "BUNDLE_LIMIT_EXCEEDED: manifest size=" + size + " bytes exceeds limit=" + MAX_MANIFEST_BYTES
                + " bytes (32 MiB); narrow the selected entityIDs with --entity-id");
    }

    private static Diagnostic diagnostic(String[] parts, int start) throws ImportException {
        String level = decode(parts[start]);
        if (!Set.of("BLOCK", "ERROR", "REVIEW", "INFO").contains(level)) throw new ImportException(6, "BUNDLE_INVALID: severity");
        return new Diagnostic(level, decode(parts[start + 1]), decode(parts[start + 2]), decode(parts[start + 3]), decode(parts[start + 4]));
    }

    static String detail(Candidate c) {
        StringBuilder out = new StringBuilder("\nSP " + SafeFiles.display(c.entity) + "\nname=" + c.name + "\nstate=" + c.state()
            + "\nsource=" + SafeFiles.display(c.source) + "\nprovider=" + SafeFiles.display(c.provider) + "\nsha256=" + c.hash
            + "\nrelease_attributes=" + String.join(",", c.attributes) + "\n");
        out.append("registration_state=").append(c.registrationState()).append("\naccess_control_state=").append(c.accessState()).append('\n');
        if (!c.sourceMetadataHash.isEmpty()) out.append("original_metadata=evidence/metadata-sources/").append(c.sourceMetadataHash).append(".xml\noriginal_metadata_sha256=").append(c.sourceMetadataHash).append('\n');
        if (c.access != null) for (String evidence : c.access.evidence()) out.append("access_evidence=").append(SafeFiles.display(evidence)).append('\n');
        if (c.managed != null) out.append("managed_profile=").append(c.managed.profile()).append("\nmanaged_mfa=").append(c.managed.mfa())
            .append("\nmanaged_cidrs=").append(String.join(",", c.managed.cidrs())).append("\nmanaged_access=")
            .append(c.managed.access() == null ? "ABSENT" : SafeFiles.display(c.managed.access().toString())).append('\n');
        if (c.managed != null && c.managed.release() != null)
            out.append("managed_profile_attributes=").append(String.join(",", c.managed.release().profileAttributes()))
                .append("\nadditional_release_attributes=").append(String.join(",", c.managed.release().additionalAttributes())).append('\n');
        for (String fact : c.facts) out.append(SafeFiles.display(fact)).append('\n');
        for (Diagnostic d : c.diagnostics) out.append(d);
        return out.toString();
    }

    static String accessDetail(Candidate c) {
        StringBuilder text = new StringBuilder("SP " + SafeFiles.display(c.entity) + "\nname=" + c.name
            + "\nregistration_state=" + c.registrationState() + "\naccess_control_state=" + c.accessState() + "\n");
        if (c.access != null) for (String evidence : c.access.evidence()) text.append("  ").append(SafeFiles.display(evidence)).append('\n');
        return text.append('\n').toString();
    }

    private static String encodeDiagnostics(List<Diagnostic> diagnostics) {
        List<String> lines = new ArrayList<>();
        for (Diagnostic d : diagnostics) lines.add(fields(d.severity(), d.code(), d.location(), d.detail(), d.remedy()));
        return String.join("\n", lines);
    }
    static String fields(String... values) { return java.util.Arrays.stream(values).map(v -> Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))).collect(java.util.stream.Collectors.joining("\t")); }
    private static String decode(String text) throws ImportException {
        try { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ex) { throw new ImportException(6, "BUNDLE_INVALID: field encoding"); }
    }

    private MigrationBundle() { }
}
