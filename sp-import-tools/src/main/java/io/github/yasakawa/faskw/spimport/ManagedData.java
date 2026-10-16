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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Read-only adapters for the 1.3.5/1.4.0 management file contracts. */
final class ManagedData {
    static final String DIR = "conf/graphicalmatrix/";
    static final String REGISTRY = DIR + "sp-management-registry.json";
    static final Set<String> MFA = Set.of("inherit", "force", "required", "bypass", "sp-cidr-bypass");
    static final Map<String, String> GLOBAL_DEFAULTS = Map.of(
        "default", "require", "policyOrder", "forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default",
        "bypassIPs", "", "bypassCIDRs", "", "useForwardedFor", "false", "missingEnrollmentPolicy", "deny");
    record Approval(String id, String governance, String classification, String purpose) { }
    record Access(boolean enabled, Map<String, List<String>> allow, Map<String, List<String>> deny) { }
    record ReleaseContract(List<String> profileAttributes, List<String> additionalAttributes) { }
    record Intent(String profile, String description, String mfa, List<String> cidrs,
                  List<Approval> approvals, Access access, boolean globalAccess, ReleaseContract release) {
        Intent(String profile, String description, String mfa, List<String> cidrs,
                List<Approval> approvals, Access access, boolean globalAccess) {
            this(profile, description, mfa, cidrs, approvals, access, globalAccess, null);
        }
    }
    static Set<String> profileAttributes(ImportModel.Candidate c) {
        return new java.util.TreeSet<>(c.managed.release() == null ? c.attributes : c.managed.release().profileAttributes());
    }
    record Entry(String name, String entity, String status, String hash, String file,
                 List<String> certificates, List<String> acs, String profile, String mfa,
                 List<String> cidrs, int profileRevision, int accessRevision, boolean accessEnabled) { }
    record Profile(List<String> attributes, String description, int revision) { }
    record Policy(String name, String entity, int revision, Access access) { }
    record Catalog(Map<String, Approval> attributes, Map<String, Profile> profiles) { }

    static Map<String, Object> json(Path root, String relative, int limit) throws Exception {
        return StrictJson.parse(SafeFiles.read(SafeFiles.contained(root, relative), limit));
    }
    static List<Entry> registry(Path root) throws Exception {
        Map<String, Object> doc = json(root, REGISTRY, 2 * 1024 * 1024);
        StrictJson.fields(doc, "schemaVersion", "entries");
        long schema = StrictJson.number(doc, "schemaVersion");
        if (schema != 1 && schema != 2) throw new IllegalArgumentException("REGISTRY_SCHEMA_UNSUPPORTED");
        List<Entry> result = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>(), entities = new java.util.HashSet<>();
        for (Object raw : StrictJson.array(doc, "entries")) {
            Map<String, Object> e = StrictJson.object(raw);
            StrictJson.fields(e, "name", "entityId", "status", "source", "metadataSha256", "metadataFile",
                "certificateFingerprints", "acsUrls", "attributeProfile", "mfaProfile", "cidrs", "createdAt", "updatedAt",
                "currentRevision", "legacyProviderId", "legacyMetadataFile", "legacyProviderXml", "legacyAttributeXml",
                "legacyMfaProfile", "attributeProfileRevision", "accessPolicyEnabled", "accessPolicyRevision");
            String name = name(StrictJson.string(e, "name")), entity = StrictJson.string(e, "entityId");
            if (entity.isBlank() || entity.length() > 2048 || !names.add(name) || !entities.add(entity)) throw new IllegalArgumentException("duplicate/invalid registry identity");
            String status = StrictJson.string(e, "status"), mfa = StrictJson.string(e, "mfaProfile");
            if (!Set.of("ACTIVE", "DISABLED", "REMOVED").contains(status) || !MFA.contains(mfa)) throw new IllegalArgumentException("unsupported registry state/MFA");
            List<String> cidrs = StrictJson.strings(e, "cidrs");
            if (mfa.equals("sp-cidr-bypass") != !cidrs.isEmpty()) throw new IllegalArgumentException("MFA/CIDR mismatch");
            for (String cidr : cidrs) cidr(cidr);
            String hash = StrictJson.string(e, "metadataSha256");
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid metadata digest");
            // The source registry keeps one ACS location per binding, including shared URLs.
            List<String> acs = StrictJson.stringsWithDuplicates(e, "acsUrls").stream().distinct().toList();
            result.add(new Entry(name, entity, status, hash, StrictJson.string(e, "metadataFile"),
                StrictJson.strings(e, "certificateFingerprints"), acs,
                name(StrictJson.string(e, "attributeProfile")), mfa, cidrs,
                schema == 2 ? revision(e, "attributeProfileRevision") : -1,
                schema == 2 ? revision(e, "accessPolicyRevision") : -1,
                schema == 2 && StrictJson.bool(e, "accessPolicyEnabled")));
            if (result.size() > 10000) throw new IllegalArgumentException("registry entry limit");
        }
        return List.copyOf(result);
    }

    static Catalog catalog(Path root) throws Exception {
        Map<String, Object> doc = json(root, DIR + "attribute-catalog.json", 1024 * 1024);
        StrictJson.fields(doc, "schemaVersion", "attributes", "profiles"); schema(doc);
        Map<String, Approval> attributes = new TreeMap<>();
        Map<String, Profile> profiles = new TreeMap<>();
        for (Object raw : StrictJson.array(doc, "attributes")) {
            var e = StrictJson.object(raw);
            StrictJson.fields(e, "id", "resolution", "samlMapping", "governance", "classification", "purpose", "updatedAt");
            String id = attribute(StrictJson.string(e, "id"));
            String governance = StrictJson.string(e, "governance"), classification = StrictJson.string(e, "classification");
            if (!Set.of("candidate", "approval-required", "release-approved", "internal-only", "blocked").contains(governance)
                    || !Set.of("public", "personal", "internal", "restricted", "credential").contains(classification)
                    || !Set.of("declared", "runtime-observed", "conditional", "unknown").contains(StrictJson.string(e, "resolution"))
                    || !Set.of("mapped", "mapping-required", "unknown").contains(StrictJson.string(e, "samlMapping"))) throw new IllegalArgumentException("catalog enum unsupported");
            String purpose = bounded(StrictJson.string(e, "purpose"), 512);
            timestamp(e);
            if (attributes.putIfAbsent(id, new Approval(id, governance, classification, purpose)) != null) throw new IllegalArgumentException("duplicate catalog attribute");
        }
        for (Object raw : StrictJson.array(doc, "profiles")) {
            var e = StrictJson.object(raw);
            StrictJson.fields(e, "name", "attributes", "description", "revision", "source", "updatedAt");
            String name = name(StrictJson.string(e, "name")); List<String> attrs = StrictJson.strings(e, "attributes");
            if (attrs.isEmpty() || attrs.size() > 32) throw new IllegalArgumentException("profile count limit");
            attrs.forEach(ManagedData::attribute); timestamp(e);
            if (!Set.of("managed", "builtin", "legacy-properties").contains(StrictJson.string(e, "source"))) throw new IllegalArgumentException("unknown profile source");
            if (profiles.putIfAbsent(name, new Profile(attrs, bounded(StrictJson.string(e, "description"), 512), positive(e, "revision"))) != null) throw new IllegalArgumentException("duplicate profile");
        }
        return new Catalog(Map.copyOf(attributes), Map.copyOf(profiles));
    }
    static Map<String, Policy> policies(Path root) throws Exception {
        if (!Files.exists(root.resolve(DIR + "access-policy.json"))) return Map.of();
        Map<String, Object> doc = json(root, DIR + "access-policy.json", 1024 * 1024);
        StrictJson.fields(doc, "schemaVersion", "policies"); schema(doc);
        Map<String, Policy> result = new TreeMap<>(); Set<String> entities = new java.util.HashSet<>();
        for (Object raw : StrictJson.array(doc, "policies")) {
            var e = StrictJson.object(raw);
            StrictJson.fields(e, "name", "entityId", "enabled", "mode", "revision", "allow", "deny", "updatedAt");
            if (!StrictJson.string(e, "mode").equals("restrict")) throw new IllegalArgumentException("access mode unsupported");
            String name = name(StrictJson.string(e, "name")), entity = StrictJson.string(e, "entityId");
            Map<String, List<String>> allow = rules(e, "allow"), deny = rules(e, "deny");
            if (allow.isEmpty() || entity.isBlank() || entity.length() > 2048) throw new IllegalArgumentException("access identity/allow missing");
            timestamp(e);
            if (!entities.add(entity) || result.putIfAbsent(name, new Policy(name, entity, positive(e, "revision"), new Access(StrictJson.bool(e, "enabled"), allow, deny))) != null)
                throw new IllegalArgumentException("duplicate access identity");
            if (result.size() > 1000) throw new IllegalArgumentException("access policy limit");
        }
        return Map.copyOf(result);
    }
    private static Map<String, List<String>> rules(Map<String, Object> e, String key) {
        Map<String, List<String>> result = new TreeMap<>();
        for (Object raw : StrictJson.array(e, key)) {
            var rule = StrictJson.object(raw); StrictJson.fields(rule, "attributeId", "values");
            String id = attribute(StrictJson.string(rule, "attributeId")); List<String> values = StrictJson.strings(rule, "values");
            if (values.isEmpty() || values.size() > 100) throw new IllegalArgumentException("access value count");
            values.forEach(v -> { bounded(v, 256); if (v.isBlank()) throw new IllegalArgumentException("empty access value"); });
            if (result.putIfAbsent(id, values.stream().sorted().toList()) != null || result.size() > 32) throw new IllegalArgumentException("duplicate/access attribute limit");
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    static Properties properties(Path root, String file, boolean latin1) throws Exception {
        byte[] bytes = SafeFiles.read(SafeFiles.contained(root, file), 1024 * 1024);
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("PROPERTY_PRECEDENCE_UNKNOWN: duplicate decoded property key");
                return super.put(key, value);
            }
        };
        if (latin1) p.load(new ByteArrayInputStream(bytes));
        else if (!file.endsWith("mfa-policy.properties")) p.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        // Ambiguous duplicate definitions and continuation lines are held, not guessed.
        Set<String> seen = new java.util.HashSet<>();
        for (String line : new String(bytes, latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8).split("\\R")) {
            String s = line.trim(); if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) continue;
            int split = s.indexOf('=');
            if (split < 1 || s.endsWith("\\") || !seen.add(s.substring(0, split).trim())) throw new IllegalArgumentException("PROPERTY_PRECEDENCE_UNKNOWN: duplicate/nonliteral property");
            if (!latin1 && file.endsWith("mfa-policy.properties")) p.setProperty(s.substring(0, split).trim(), s.substring(split + 1).trim());
        }
        if (!latin1 && file.endsWith("mfa-policy.properties")) {
            // Runtime Properties.load and the CLI's literal reader must agree before migration.
            Properties runtime = new Properties(); runtime.load(new ByteArrayInputStream(bytes));
            if (!runtime.equals(p)) throw new IllegalArgumentException("PROPERTY_PRECEDENCE_UNKNOWN: MFA runtime/CLI decoding differs");
        }
        return p;
    }
    static Map<String, String> global(Properties p) {
        Map<String, String> result = new TreeMap<>();
        GLOBAL_DEFAULTS.forEach((key, value) -> result.put(key, p.getProperty("graphicalmatrix.mfa." + key, value).trim()));
        return java.util.Collections.unmodifiableMap(result);
    }
    static String inferredMfa(Properties p, String entity, List<String> cidrs) {
        List<String> modes = new ArrayList<>();
        for (var item : Map.of("forceSPs", "force", "bypassSPs", "bypass", "requiredSPs", "required").entrySet())
            if (List.of(p.getProperty("graphicalmatrix.mfa." + item.getKey(), "").split(",")).stream().map(String::trim).anyMatch(entity::equals)) modes.add(item.getValue());
        List<String> ranges = List.of();
        Set<String> seen = new java.util.HashSet<>();
        for (String rule : p.getProperty("graphicalmatrix.mfa.bypassSpCidrs", "").split(";")) {
            if (rule.isBlank()) continue;
            String[] parts = rule.trim().split("\\|", -1);
            if (parts.length != 2 || !seen.add(parts[0].trim())) throw new IllegalArgumentException("invalid bypassSpCidrs");
            List<String> list = List.of(parts[1].split(",")).stream().map(String::trim).toList(); list.forEach(ManagedData::cidr);
            if (parts[0].trim().equals(entity)) { modes.add("sp-cidr-bypass"); ranges = list; }
        }
        if (!ranges.equals(cidrs) || modes.size() > 1) throw new IllegalArgumentException("SOURCE_MFA_DRIFT: CIDRs or multiple modes");
        return modes.isEmpty() ? "inherit" : modes.getFirst();
    }
    static Profile profile(String name, Catalog catalog, Properties p) {
        Map<String, List<String>> builtin = Map.of("none", List.of(), "uid", List.of("uid"), "uid-mail", List.of("uid", "mail"));
        String legacy = p.getProperty("graphicalmatrix.sp.attributeProfile." + name);
        Profile managed = catalog.profiles().get(name);
        if ((builtin.containsKey(name) && (managed != null || legacy != null)) || (managed != null && legacy != null)) throw new IllegalArgumentException("SOURCE_PROFILE_CONFLICT");
        if (builtin.containsKey(name)) return new Profile(builtin.get(name), "", 0);
        if (managed != null) return managed;
        if (legacy != null) {
            if (!name.matches("[a-z0-9][a-z0-9-]{0,31}")) throw new IllegalArgumentException("legacy profile name");
            List<String> attrs = List.of(legacy.split(",", -1)).stream().map(String::trim).toList(); attrs.forEach(ManagedData::attribute);
            if (attrs.isEmpty() || attrs.size() > 32 || Set.copyOf(attrs).size() != attrs.size()) throw new IllegalArgumentException("legacy profile attributes");
            return new Profile(attrs, "Imported legacy profile", 0);
        }
        throw new IllegalArgumentException("SOURCE_PROFILE_MISSING: " + name);
    }
    static String name(String value) { if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid management name"); return value; }
    static String attribute(String value) { if (!value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("invalid attribute ID"); return value; }
    static String bounded(String value, int max) { if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("text limit/control character"); return value; }
    static void cidr(String value) {
        String[] parts = value.split("/", -1), octets = parts[0].split("\\.", -1);
        if (parts.length != 2 || octets.length != 4) throw new IllegalArgumentException("invalid IPv4 CIDR");
        for (String octet : octets) if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) throw new IllegalArgumentException("invalid IPv4 CIDR");
        if (!parts[1].matches("[0-9]{1,2}") || Integer.parseInt(parts[1]) > 32) throw new IllegalArgumentException("invalid IPv4 CIDR");
    }
    static boolean accessEnabled(Properties p) {
        String v = p.getProperty("graphicalmatrix.sp.access.enabled", "false").trim();
        if (!Set.of("true", "false").contains(v)) throw new IllegalArgumentException("invalid global access flag");
        return Boolean.parseBoolean(v);
    }
    static Map<String, Object> intentObject(Intent value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", value.profile()); out.put("description", value.description()); out.put("mfa", value.mfa());
        out.put("cidrs", value.cidrs()); out.put("globalAccess", value.globalAccess());
        out.put("approvals", value.approvals().stream().map(a -> Map.<String, Object>of("id", a.id(), "governance", a.governance(), "classification", a.classification(), "purpose", a.purpose())).toList());
        out.put("hasAccess", value.access() != null);
        if (value.release() != null) out.put("releaseContract", Map.of("profileAttributes", value.release().profileAttributes(),
            "additionalAttributes", value.release().additionalAttributes()));
        if (value.access() != null) {
            out.put("enabled", value.access().enabled());
            out.put("allow", ruleObjects(value.access().allow())); out.put("deny", ruleObjects(value.access().deny()));
        }
        return out;
    }
    static Intent intent(Map<String, Object> e) {
        StrictJson.fields(e, "profile", "description", "mfa", "cidrs", "globalAccess", "approvals", "hasAccess", "enabled", "allow", "deny", "releaseContract");
        String profile = name(StrictJson.string(e, "profile")), mfa = StrictJson.string(e, "mfa");
        List<String> cidrs = StrictJson.strings(e, "cidrs"); cidrs.forEach(ManagedData::cidr);
        if (!MFA.contains(mfa) || mfa.equals("sp-cidr-bypass") != !cidrs.isEmpty()) throw new IllegalArgumentException("invalid intent MFA/CIDRs");
        List<Approval> approvals = new ArrayList<>(); Set<String> ids = new java.util.HashSet<>();
        for (Object raw : StrictJson.array(e, "approvals")) {
            var a = StrictJson.object(raw); StrictJson.fields(a, "id", "governance", "classification", "purpose");
            String id = attribute(StrictJson.string(a, "id")), governance = StrictJson.string(a, "governance"), classification = StrictJson.string(a, "classification");
            if (!ids.add(id) || !Set.of("release-approved", "internal-only").contains(governance)
                    || !Set.of("public", "personal", "internal", "restricted", "credential").contains(classification)) throw new IllegalArgumentException("invalid intent approval");
            approvals.add(new Approval(id, governance, classification, bounded(StrictJson.string(a, "purpose"), 512)));
        }
        boolean hasAccess = StrictJson.bool(e, "hasAccess"), global = StrictJson.bool(e, "globalAccess");
        Access access = hasAccess ? new Access(StrictJson.bool(e, "enabled"), rules(e, "allow"), rules(e, "deny")) : null;
        if ((!hasAccess && (e.containsKey("allow") || e.containsKey("deny") || e.containsKey("enabled")))
                || (hasAccess && (access.allow().isEmpty() || access.enabled() && !global))) throw new IllegalArgumentException("invalid intent access state");
        ReleaseContract release = null;
        if (e.containsKey("releaseContract")) {
            var contract = StrictJson.object(e.get("releaseContract"));
            StrictJson.fields(contract, "profileAttributes", "additionalAttributes");
            var profileAttrs = StrictJson.strings(contract, "profileAttributes");
            var additional = StrictJson.strings(contract, "additionalAttributes");
            if (profileAttrs.size() > 32 || additional.size() > 256) throw new IllegalArgumentException("release contract attribute limit");
            profileAttrs.forEach(ManagedData::attribute); additional.forEach(ManagedData::attribute);
            release = new ReleaseContract(profileAttrs, additional);
        }
        return new Intent(profile, bounded(StrictJson.string(e, "description"), 512), mfa, cidrs, List.copyOf(approvals), access, global, release);
    }
    private static List<Map<String, Object>> ruleObjects(Map<String, List<String>> map) {
        return new TreeMap<>(map).entrySet().stream().map(e -> Map.<String, Object>of("attributeId", e.getKey(), "values", e.getValue())).toList();
    }
    private static void schema(Map<String, Object> doc) { if (StrictJson.number(doc, "schemaVersion") != 1) throw new IllegalArgumentException("MANAGEMENT_SCHEMA_UNSUPPORTED"); }
    private static int revision(Map<String, Object> e, String key) { long n = StrictJson.number(e, key); if (n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("revision range"); return (int) n; }
    private static int positive(Map<String, Object> e, String key) { int n = revision(e, key); if (n < 1) throw new IllegalArgumentException("positive revision required"); return n; }
    private static void timestamp(Map<String, Object> e) { java.time.Instant.parse(StrictJson.string(e, "updatedAt")); }
    private ManagedData() { }
}
