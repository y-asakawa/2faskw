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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class ManagedSourceAnalyzer {
    static Analysis analyze(ImportOptions options) throws Exception {
        Path home = options.path("source-idp-home");
        boolean present = Files.exists(home.resolve(ManagedData.REGISTRY), LinkOption.NOFOLLOW_LINKS);
        String mode = options.get("source-type", "auto");
        if (mode.equals("2faskw") && !present) throw new ImportException(3, "SOURCE_SNAPSHOT_INCOMPLETE: management registry is missing");
        boolean managed = mode.equals("2faskw") || (mode.equals("auto") && present);
        // Validate before general analysis: a damaged registry must never fall back to generic import.
        List<ManagedData.Entry> entries;
        String initialRegistryHash = managed ? SafeFiles.hash(SafeFiles.read(SafeFiles.contained(home.toRealPath(), ManagedData.REGISTRY), 2 * 1024 * 1024)) : "";
        try { entries = managed ? ManagedData.registry(home.toRealPath()) : List.of(); }
        catch (Exception ex) { throw new ImportException(3, "REGISTRY_SCHEMA_UNSUPPORTED_OR_INVALID: " + SafeFiles.display(ex.getMessage())); }
        Analysis a = new SourceAnalyzer(options).analyze();
        if (!managed) {
            if (present) a.diagnostics.add(new Diagnostic("REVIEW", "MANAGED_SOURCE_EXPLICITLY_IGNORED", home.toString(),
                "General mode omits source management names, MFA and access policies.", "Use --source-type 2faskw to preserve managed settings."));
            return a;
        }
        a.mode = "2faskw";
        Map<Path, String> hashes = new HashMap<>();
        try {
            Path root = home.toRealPath();
            for (String file : List.of(ManagedData.REGISTRY, ManagedData.DIR + "attribute-catalog.json",
                    ManagedData.DIR + "sp-management.properties", ManagedData.DIR + "mfa-policy.properties")) hash(root, file, hashes);
            if (Files.exists(root.resolve(ManagedData.DIR + "access-policy.json"))) hash(root, ManagedData.DIR + "access-policy.json", hashes);
            if (!initialRegistryHash.equals(hashes.get(SafeFiles.contained(root, ManagedData.REGISTRY)))) throw new IllegalArgumentException("SOURCE_CHANGED_DURING_ANALYSIS: registry");
            var catalog = ManagedData.catalog(root);
            var properties = ManagedData.properties(root, ManagedData.DIR + "sp-management.properties", true);
            var mfa = ManagedData.properties(root, ManagedData.DIR + "mfa-policy.properties", false);
            var policies = ManagedData.policies(root);
            a.globalMfa = ManagedData.global(mfa);
            boolean globalAccess = ManagedData.accessEnabled(properties);
            if (!Files.exists(root.resolve(ManagedData.DIR + "access-policy.json"))
                    && (globalAccess || entries.stream().anyMatch(e -> e.accessRevision() < 0)))
                throw new IllegalArgumentException("SOURCE_ACCESS_POLICY_MISSING: access state cannot be inferred from a missing snapshot file");
            if (globalAccess && !policies.isEmpty()) {
                hash(root, "conf/intercept/context-check-intercept-config.xml", hashes);
                hash(root, "conf/relying-party.xml", hashes);
                ManagedAccessWiring.require(root);
            }
            Map<String, ManagedData.Entry> byEntity = new HashMap<>();
            for (var entry : entries) byEntity.put(entry.entity(), entry);
            for (var policy : policies.values()) {
                var e = byEntity.get(policy.entity());
                if (e == null || !e.name().equals(policy.name())) throw new IllegalArgumentException("SOURCE_ACCESS_IDENTITY_MISMATCH: orphan/name mismatch");
            }
            for (Candidate c : List.copyOf(a.candidates)) {
                var e = byEntity.get(c.entity);
                if (e == null) {
                    c.diagnostic("BLOCK", "UNMANAGED_SP_SEPARATE_WORKFLOW", c.source, "This XML SP has no managed registry entry.", "Analyze/import this SP separately in general mode; adopt is not automatic.");
                    continue;
                }
                c.name = e.name();
                if (!e.status().equals("ACTIVE")) {
                    a.evidence.add("excluded management state=" + e.status() + " name=" + e.name());
                    a.candidates.remove(c); continue;
                }
                try {
                    if (!e.file().startsWith("metadata/2faskw-managed-sp/")) throw new IllegalArgumentException("SOURCE_METADATA_PATH_UNSUPPORTED");
                    if (!c.provider.equals("GraphicalMatrixManagedSPMetadata (LocalDynamicMetadataProvider)")) throw new IllegalArgumentException("SOURCE_MANAGED_PROVIDER_UNSUPPORTED");
                    hash(root, e.file(), hashes);
                    byte[] bytes = SafeFiles.read(SafeFiles.contained(root, e.file()), SafeFiles.MAX_FILE);
                    if (!e.hash().equals(SafeFiles.hash(bytes)) || !e.hash().equals(c.hash)) throw new IllegalArgumentException("REGISTRY_METADATA_MISMATCH: file digest");
                    var xml = SecureXml.parse(bytes, e.file());
                    if (!xml.attr("entityID").equals(e.entity())) throw new IllegalArgumentException("REGISTRY_METADATA_MISMATCH: entityID");
                    var acs = new TreeSet<String>(); var certs = new TreeSet<String>();
                    for (var node : xml.descendants()) {
                        if (node.namespace.equals(SecureXml.MD) && node.name.equals("AssertionConsumerService")) acs.add(node.attr("Location"));
                        if (node.namespace.equals("http://www.w3.org/2000/09/xmldsig#") && node.name.equals("X509Certificate")) {
                            byte[] der = java.util.Base64.getMimeDecoder().decode(node.text.toString());
                            var cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
                            String digest = SafeFiles.hash(cert.getEncoded()).toUpperCase(java.util.Locale.ROOT);
                            certs.add(java.util.HexFormat.ofDelimiter(":").withUpperCase().formatHex(java.util.HexFormat.of().parseHex(digest)));
                        }
                    }
                    if (!acs.equals(new TreeSet<>(e.acs())) || !certs.equals(new TreeSet<>(e.certificates()))) throw new IllegalArgumentException("REGISTRY_METADATA_MISMATCH: ACS/certificates");
                    var profile = ManagedData.profile(e.profile(), catalog, properties);
                    var release = ReleasePolicies.source(c, e.name(), profile.attributes());
                    if (e.profileRevision() >= 0 && profile.revision() != e.profileRevision()) throw new IllegalArgumentException("SOURCE_PROFILE_REVISION_MISMATCH");
                    if (!e.mfa().equals(ManagedData.inferredMfa(mfa, e.entity(), e.cidrs()))) throw new IllegalArgumentException("SOURCE_MFA_DRIFT");
                    var policy = policies.get(e.name());
                    if (e.accessRevision() >= 0 && (policy == null ? e.accessRevision() != 0 || e.accessEnabled()
                            : e.accessRevision() != policy.revision() || e.accessEnabled() != policy.access().enabled())) throw new IllegalArgumentException("SOURCE_ACCESS_REVISION_MISMATCH");
                    if (policy != null && policy.access().enabled() && !globalAccess) throw new IllegalArgumentException("SOURCE_ACCESS_GLOBAL_DISABLED: enabled policy is not effective; explicit manual decision required");
                    Set<String> profileAttrs = new TreeSet<>(release.profileAttributes());
                    Set<String> needed = new TreeSet<>(profileAttrs);
                    if (policy != null) { needed.addAll(policy.access().allow().keySet()); needed.addAll(policy.access().deny().keySet()); }
                    List<ManagedData.Approval> approvals = new ArrayList<>();
                    for (String id : needed) {
                        var approval = catalog.attributes().get(id);
                        if (approval == null || !Set.of("release-approved", "internal-only").contains(approval.governance())
                                || (profileAttrs.contains(id) && !approval.governance().equals("release-approved"))) throw new IllegalArgumentException("SOURCE_ATTRIBUTE_APPROVAL_MISSING: " + id);
                        approvals.add(approval);
                    }
                    c.managed = new ManagedData.Intent(e.profile(), profile.description(), e.mfa(), e.cidrs(), List.copyOf(approvals), policy == null ? null : policy.access(), globalAccess, release);
                    c.facts.add("additional_release_handling=target equivalence required; common policies are not copied");
                    c.facts.add("managed_schema_contract=1.3.5/1.4.0; source revisions are evidence only, not copied");
                    c.facts.add("source_mfa=" + e.mfa() + " cidrs=" + String.join(",", e.cidrs()));
                    c.facts.add("source_access=" + (policy == null ? "ABSENT" : policy.access().enabled() ? "ENABLED" : "DISABLED") + " global=" + globalAccess);
                } catch (Exception ex) { c.diagnostic("BLOCK", "MANAGED_SOURCE_INCONSISTENT", c.source, SafeFiles.display(ex.getMessage()), "Correct the source snapshot using normal management procedures and export again."); }
            }
            for (var e : entries) if (e.status().equals("ACTIVE") && a.candidates.stream().noneMatch(c -> c.entity.equals(e.entity()))
                    && (options.all("entity-id").isEmpty() || options.all("entity-id").contains(e.entity()))) {
                Candidate c = new Candidate(e.entity(), ManagedData.REGISTRY, "registry"); c.name = e.name();
                c.diagnostic("BLOCK", "REGISTRY_SP_NOT_LOADED", e.file(), "Active managed SP was not found through provider XML.", "Restore metadata/provider resources and re-export."); a.candidates.add(c);
            }
            for (var pair : hashes.entrySet()) {
                if (!pair.getValue().equals(SafeFiles.hash(SafeFiles.read(pair.getKey(), SafeFiles.MAX_FILE)))) throw new IllegalArgumentException("SOURCE_CHANGED_DURING_ANALYSIS");
                a.evidence.add("managed snapshot " + root.relativize(pair.getKey()) + " sha256=" + pair.getValue());
            }
        } catch (Exception ex) {
            Diagnostic d = new Diagnostic("BLOCK", "SOURCE_SNAPSHOT_INCOMPLETE_OR_CHANGED", ManagedData.DIR, SafeFiles.display(ex.getMessage()), "Pause source management updates and provide a consistent complete snapshot; no generic fallback.");
            a.diagnostics.add(d); a.candidates.forEach(c -> c.diagnostics.add(d));
        }
        return a;
    }
    private static void hash(Path root, String file, Map<Path, String> hashes) throws Exception {
        Path path = SafeFiles.contained(root, file);
        hashes.put(path, SafeFiles.hash(SafeFiles.read(path, SafeFiles.MAX_FILE)));
    }
    private ManagedSourceAnalyzer() { }
}
