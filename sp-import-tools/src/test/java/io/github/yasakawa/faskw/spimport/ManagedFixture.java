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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class ManagedFixture {
    static void text(Path root, String name, String contents) throws Exception {
        Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, contents);
    }
    static void json(Path root, String name, Object data) throws Exception { text(root, name, new String(StrictJson.write(data), StandardCharsets.UTF_8)); }
    static Map<String, Object> attribute(String id, String governance) {
        return Map.of("id", id, "resolution", "declared", "samlMapping", "mapped", "governance", governance,
            "classification", "personal", "purpose", "Federation", "updatedAt", "2026-08-01T00:00:00Z");
    }
    static Map<String, Object> entry(String name, String entity, String hash, String profile, String mfa, String file) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name); e.put("entityId", entity); e.put("status", "ACTIVE"); e.put("source", "local-file"); e.put("metadataSha256", hash);
        e.put("metadataFile", file); e.put("certificateFingerprints", List.of()); e.put("acsUrls", List.of("https://library.example.org/acs"));
        e.put("attributeProfile", profile); e.put("mfaProfile", mfa); e.put("cidrs", List.of());
        e.put("createdAt", "2026-08-01T00:00:00Z"); e.put("updatedAt", "2026-08-01T00:00:00Z"); e.put("currentRevision", 3L);
        e.put("legacyProviderId", ""); e.put("legacyMetadataFile", ""); e.put("legacyProviderXml", ""); e.put("legacyAttributeXml", ""); e.put("legacyMfaProfile", "");
        e.put("attributeProfileRevision", 0L); e.put("accessPolicyEnabled", false); e.put("accessPolicyRevision", 0L); return e;
    }
    static void registry(Path home, List<?> entries) throws Exception { json(home, ManagedData.REGISTRY, Map.of("schemaVersion", 2L, "entries", entries)); }
    static void base(Path root) throws Exception {
        var generic = new SourceAnalyzerTest(); generic.home = root; generic.fixture();
        text(root, ManagedData.DIR + "sp-management.properties", "graphicalmatrix.sp.management.enabled=true\ngraphicalmatrix.sp.access.enabled=true\ngraphicalmatrix.sp.metadata.allowedAcsHosts=library.example.org\n");
        text(root, ManagedData.DIR + "mfa-policy.properties", "# global defaults\n");
        json(root, ManagedData.DIR + "attribute-catalog.json", Map.of("schemaVersion", 1L,
            "attributes", List.of(attribute("uid", "release-approved"), attribute("mail", "release-approved"), attribute("businessCategory", "internal-only")), "profiles", List.of()));
        json(root, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of()));
        registry(root, List.of());
        text(root, "conf/attribute-resolver.xml", "<AttributeResolver/>");
        Files.createDirectories(root.resolve("metadata/2faskw-managed-sp"));
        text(root, "conf/metadata-providers.xml", """
            <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              id="GraphicalMatrixManagedSPMetadata" xsi:type="LocalDynamicMetadataProvider" sourceDirectory="%{idp.home}/metadata/2faskw-managed-sp"/>
            """);
    }
    static void source(Path root) throws Exception {
        base(root);
        text(root, "conf/attribute-filter.xml", SourceAnalyzerTest.FILTER.replace("id=\"library\"", "id=\"2faskw-library\""));
        String file = "metadata/2faskw-managed-sp/library.xml";
        text(root, file, SourceAnalyzerTest.METADATA);
        text(root, "conf/metadata-providers.xml", """
            <MetadataProvider xmlns="urn:mace:shibboleth:2.0:metadata" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              id="GraphicalMatrixManagedSPMetadata" xsi:type="LocalDynamicMetadataProvider" sourceDirectory="%{idp.home}/metadata/2faskw-managed-sp"/>
            """);
        registry(root, List.of(entry("library", SourceAnalyzerTest.ENTITY, SafeFiles.hash(SourceAnalyzerTest.METADATA.getBytes(StandardCharsets.UTF_8)), "uid-mail", "force", file)));
        text(root, ManagedData.DIR + "mfa-policy.properties", "graphicalmatrix.mfa.forceSPs=" + SourceAnalyzerTest.ENTITY + "\n");
    }
    static Map<String, Object> policy(String name, String entity, boolean enabled, String value) {
        return Map.of("name", name, "entityId", entity, "enabled", enabled, "mode", "restrict", "revision", 1L,
            "allow", List.of(Map.of("attributeId", "businessCategory", "values", List.of(value))), "deny", List.of(), "updatedAt", "2026-08-01T00:00:00Z");
    }
    static void access(Path root, boolean enabled) throws Exception {
        text(root, "conf/intercept/context-check-intercept-config.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"><bean id=\"shibboleth.context-check.Function\" class=\"io.github.yasakawa.faskw.GraphicalMatrixSpAccessFunction\"/></beans>");
        text(root, "conf/relying-party.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"><bean parent=\"SAML2.SSO\"><property name=\"postAuthenticationFlows\"><list><value>context-check</value></list></property></bean></beans>");
        json(root, ManagedData.DIR + "access-policy.json", Map.of("schemaVersion", 1L, "policies", List.of(policy("library", SourceAnalyzerTest.ENTITY, enabled, "AA"))));
        var doc = ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024);
        var e = StrictJson.object(StrictJson.array(doc, "entries").getFirst()); e.put("accessPolicyEnabled", enabled); e.put("accessPolicyRevision", 1L); json(root, ManagedData.REGISTRY, doc);
    }
    static ImportModel.Analysis analyze(Path root, String... extras) throws Exception {
        var args = new ArrayList<>(List.of("--source-idp-home", root.toString())); args.addAll(List.of(extras));
        return ManagedSourceAnalyzer.analyze(ImportOptions.parse(args.toArray(String[]::new)));
    }
    static final class Cli implements ExistingCli.Executor {
        final Path root;
        final List<List<String>> calls = new ArrayList<>();
        String failApply = "";
        Cli(Path root) { this.root = root; }
        @Override public ExistingCli.Response execute(List<String> full, Path idp) throws Exception {
            List<String> args = full.subList(1, full.size()); calls.add(List.copyOf(args));
            String key = String.join(" ", args); boolean apply = args.contains("--apply");
            if (apply && !failApply.isEmpty() && key.startsWith(failApply)) return new ExistingCli.Response(1, "ERROR: simulated failure\n");
            if (args.getFirst().equals("init")) return ok("metadata_provider=present\nattribute_block=present\n");
            if (key.startsWith("status --entity-id ")) {
                boolean exists = ManagedData.registry(root).stream().anyMatch(e -> e.entity().equals(args.get(2)));
                return ok(exists ? "MANAGED\n" : "status=NOT_FOUND entity_id=" + args.get(2) + "\n");
            }
            if (key.equals("access init")) return ok("context_function_managed=true\ncontext_condition_configured=false\nsaml2_sso_profiles=1\ncontext_check_profiles=1\n");
            if (key.equals("mfa show")) {
                StringBuilder b = new StringBuilder("managed_policy_drift=OK\n");
                for (var e : ManagedData.registry(root)) b.append("sp=").append(e.name()).append(" status=").append(e.status()).append(" mfa=").append(e.mfa()).append(" cidrs=").append(String.join(",", e.cidrs())).append('\n');
                return ok(b.toString());
            }
            if (key.startsWith("mfa test")) return ok("decision=REQUIRE\nrule=forceSPs\n");
            if (key.equals("attributes discover")) {
                StringBuilder b = new StringBuilder();
                for (var raw : StrictJson.array(catalog(), "attributes")) {
                    var a = StrictJson.object(raw); b.append(a.get("id")).append(" declared mapped ").append(a.get("governance")).append(" -\n");
                } return ok(b.toString());
            }
            if (key.equals("attributes profile list")) {
                StringBuilder b = new StringBuilder("none 0 builtin OK -\nuid 0 builtin OK uid\nuid-mail 0 builtin OK uid,mail\n");
                for (var raw : StrictJson.array(catalog(), "profiles")) { var p = StrictJson.object(raw); b.append(p.get("name")).append(" 1 managed OK ").append(String.join(",", StrictJson.strings(p, "attributes"))).append('\n'); }
                return ok(b.toString());
            }
            if (key.startsWith("attributes profile show ")) {
                var profile = ManagedData.profile(args.get(3), ManagedData.catalog(root), ManagedData.properties(root, ManagedData.DIR + "sp-management.properties", true));
                return ok("status=OK\nattributes=" + String.join(",", profile.attributes()) + "\ndescription=" + profile.description() + "\n");
            }
            if (key.startsWith("attributes show ")) {
                var a = ManagedData.catalog(root).attributes().get(args.get(2));
                return ok("governance=" + a.governance() + "\nsaml_mapping=mapped\nclassification=" + a.classification() + "\npurpose=" + a.purpose() + "\n");
            }
            if (key.startsWith("attributes approve ") && apply) {
                var doc = catalog(); var attrs = new ArrayList<Object>(StrictJson.array(doc, "attributes"));
                attrs.removeIf(v -> StrictJson.string(StrictJson.object(v), "id").equals(args.get(2)));
                var a = new LinkedHashMap<>(attribute(args.get(2), option(args, "--usage").equals("release") ? "release-approved" : "internal-only"));
                a.put("classification", option(args, "--classification")); a.put("purpose", option(args, "--purpose")); attrs.add(a); doc.put("attributes", attrs); json(root, ManagedData.DIR + "attribute-catalog.json", doc);
            } else if (key.startsWith("attributes profile create ") && apply) {
                var doc = catalog(); var profiles = new ArrayList<Object>(StrictJson.array(doc, "profiles"));
                profiles.add(Map.of("name", args.get(3), "attributes", List.of(option(args, "--attributes").split(",")), "description", option(args, "--description"), "revision", 1L, "source", "managed", "updatedAt", "2026-08-01T00:00:00Z"));
                doc.put("profiles", profiles); json(root, ManagedData.DIR + "attribute-catalog.json", doc);
            } else if (args.getFirst().equals("add")) {
                String entity = option(args, "--entity-id"), name = option(args, "--name"), file = option(args, "--metadata-file");
                String hash = SafeFiles.hash(Files.readAllBytes(Path.of(file)));
                if (apply) {
                    String relative = "metadata/2faskw-managed-sp/" + name + ".xml"; text(root, relative, Files.readString(Path.of(file)));
                    var e = entry(name, entity, hash, option(args, "--attribute-profile"), option(args, "--mfa"), relative);
                    if (args.contains("--cidrs")) e.put("cidrs", List.of(option(args, "--cidrs").split(",")));
                    var entries = entries(); entries.add(e); registry(root, entries);
                    var profile = ManagedData.profile(option(args, "--attribute-profile"), ManagedData.catalog(root),
                        ManagedData.properties(root, ManagedData.DIR + "sp-management.properties", true));
                    if (!profile.attributes().isEmpty()) {
                        StringBuilder policy = new StringBuilder("<AttributeFilterPolicy id=\"2faskw-" + name
                            + "\"><PolicyRequirementRule xsi:type=\"Requester\" value=\"" + entity.replace("&", "&amp;").replace("\"", "&quot;") + "\"/>");
                        for (String attribute : profile.attributes()) policy.append("<AttributeRule attributeID=\"").append(attribute)
                            .append("\"><PermitValueRule xsi:type=\"ANY\"/></AttributeRule>");
                        policy.append("</AttributeFilterPolicy>");
                        String filter = Files.readString(root.resolve("conf/attribute-filter.xml"));
                        text(root, "conf/attribute-filter.xml", filter.replace("</AttributeFilterPolicyGroup>", policy + "</AttributeFilterPolicyGroup>"));
                    }
                    StringBuilder mfa = new StringBuilder("# defaults\n");
                    for (var group : Map.of("force", "forceSPs", "required", "requiredSPs", "bypass", "bypassSPs").entrySet()) {
                        String value = entries.stream().map(StrictJson::object).filter(v -> v.get("mfaProfile").equals(group.getKey())).map(v -> (String) v.get("entityId")).collect(java.util.stream.Collectors.joining(","));
                        mfa.append("graphicalmatrix.mfa.").append(group.getValue()).append('=').append(value).append('\n');
                    } text(root, ManagedData.DIR + "mfa-policy.properties", mfa.toString());
                }
                return ok("entity_id=" + entity + "\nmetadata_sha256=" + hash + "\nresult=" + (apply ? "APPLY_OK" : "DRY_RUN_OK") + "\n");
            } else if (args.getFirst().equals("verify")) {
                var e = ManagedData.registry(root).stream().filter(v -> v.name().equals(args.get(1))).findFirst().orElseThrow();
                return ok("entity_id=OK expected=" + e.entity() + " actual=" + e.entity() + "\nmetadata_sha256=OK expected=" + e.hash() + " actual=" + e.hash()
                    + "\nattribute_profile=" + e.profile() + "\nmfa_profile=" + e.mfa() + "\nstatus=ACTIVE\nresult=OK\n");
            } else if (key.startsWith("access set ") && apply) {
                var e = ManagedData.registry(root).stream().filter(v -> v.name().equals(args.get(2))).findFirst().orElseThrow();
                List<Object> allow = new ArrayList<>(), deny = new ArrayList<>();
                for (String flag : List.of("--allow", "--deny")) {
                    Map<String, List<String>> values = new TreeMap<>();
                    for (int i = 0; i < args.size() - 1; i++) if (args.get(i).equals(flag)) {
                        String[] pair = args.get(i + 1).split("=", 2); values.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
                    }
                    values.forEach((id, list) -> (flag.equals("--allow") ? allow : deny).add(Map.of("attributeId", id, "values", list)));
                }
                var doc = policyDoc(); var policies = new ArrayList<Object>(StrictJson.array(doc, "policies"));
                policies.add(Map.of("name", e.name(), "entityId", e.entity(), "enabled", true, "revision", 1L, "mode", "restrict", "allow", allow, "deny", deny, "updatedAt", "2026-08-01T00:00:00Z"));
                doc.put("policies", policies); json(root, ManagedData.DIR + "access-policy.json", doc); registryAccess(e.name(), true, 1L);
            } else if (key.startsWith("access disable ") && apply) {
                var doc = policyDoc();
                for (var raw : StrictJson.array(doc, "policies")) { var p = StrictJson.object(raw); if (p.get("name").equals(args.get(2))) { p.put("enabled", false); p.put("revision", 2L); } }
                json(root, ManagedData.DIR + "access-policy.json", doc); registryAccess(args.get(2), false, 2L);
            }
            return ok("result=" + (apply ? "APPLY_OK" : "DRY_RUN_OK") + "\n");
        }
        private void registryAccess(String name, boolean enabled, long revision) throws Exception {
            var entries = entries(); for (var raw : entries) { var e = StrictJson.object(raw); if (e.get("name").equals(name)) { e.put("accessPolicyEnabled", enabled); e.put("accessPolicyRevision", revision); } } registry(root, entries);
        }
        private Map<String, Object> catalog() throws Exception { return ManagedData.json(root, ManagedData.DIR + "attribute-catalog.json", 1024 * 1024); }
        private Map<String, Object> policyDoc() throws Exception { return ManagedData.json(root, ManagedData.DIR + "access-policy.json", 1024 * 1024); }
        private ArrayList<Object> entries() throws Exception { return new ArrayList<>(StrictJson.array(ManagedData.json(root, ManagedData.REGISTRY, 2 * 1024 * 1024), "entries")); }
        private static String option(List<String> args, String key) { return args.get(args.indexOf(key) + 1); }
        private static ExistingCli.Response ok(String output) { return new ExistingCli.Response(0, output); }
    }
    private ManagedFixture() { }
}
