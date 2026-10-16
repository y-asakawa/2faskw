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
import static io.github.yasakawa.faskw.spimport.SecureXml.*;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

final class SourceAnalyzer {
    private final ImportOptions options;
    private final Analysis result = new Analysis();
    private final Instant checkedAt = Instant.now();
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<Path, Node> documents = new LinkedHashMap<>();
    private final Map<Path, byte[]> contents = new HashMap<>();
    private final Map<Candidate, Node> entityNodes = new HashMap<>();
    private final Map<String, List<Node>> beans = new LinkedHashMap<>();
    private final Map<Path, Path> pathMaps = new LinkedHashMap<>();
    private final Set<Path> active = new HashSet<>();
    private final List<Node> policies = new ArrayList<>();
    private final List<Node> rp = new ArrayList<>();
    private long inputBytes;
    private boolean separateAccess;
    private final Map<Diagnostic, Node> reviewFilters = new HashMap<>();

    SourceAnalyzer(ImportOptions options) { this.options = options; }

    Analysis analyze() throws Exception { return analyze(null); }

    Analysis analyzeReleases(List<Candidate> incoming) throws Exception { return analyze(incoming); }

    private Analysis analyze(List<Candidate> incoming) throws Exception {
        if (!Files.isDirectory(options.path("source-idp-home"))) throw new ImportException(2, "SOURCE_HOME_NOT_FOUND");
        result.source = options.path("source-idp-home").toRealPath();
        separateAccess = incoming == null && (options.get("source-type", "auto").equals("shibboleth")
            || !options.get("source-type", "auto").equals("2faskw")
                && !Files.exists(result.source.resolve(ManagedData.REGISTRY), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        if (options.flag("extract-aggregate") && !separateAccess)
            throw new ImportException(2, "--extract-aggregate is only available for generic Shibboleth analysis; managed source policy is unchanged");
        if (!Files.isDirectory(result.source)) throw new ImportException(2, "SOURCE_HOME_NOT_FOUND");
        properties.put("idp.home", result.source.toString());
        for (String mapping : options.all("path-map")) {
            int separator = mapping.indexOf('=');
            if (separator < 1) throw new ImportException(2, "--path-map must be OLD=NEW");
            Path old = Path.of(mapping.substring(0, separator)).normalize();
            Path target = Path.of(mapping.substring(separator + 1)).toAbsolutePath().normalize();
            if (!old.isAbsolute() || pathMaps.putIfAbsent(old, target) != null) throw new ImportException(2, "invalid/duplicate path-map");
        }
        loadProperties(result.source.resolve("conf/idp.properties"), new HashSet<>(), false);
        loadProperties(result.source.resolve("conf/services.properties"), new HashSet<>(), false);
        for (String file : options.all("property-source")) loadProperties(Path.of(file).toAbsolutePath(), new HashSet<>(), true);
        properties.put("idp.home", result.source.toString());
        walkConfig(result.source.resolve("conf/services.xml"), 0, false);
        Set<Path> metadata = resources("idp.service.metadata.resources", "shibboleth.MetadataResolverResources", "conf/metadata-providers.xml");
        Set<Path> filters = resources("idp.service.attribute.filter.resources", "shibboleth.AttributeFilterResources", "conf/attribute-filter.xml");
        Set<Path> relying = resources("idp.service.relyingparty.resources", "shibboleth.RelyingPartyResolverResources", "conf/relying-party.xml");
        for (String file : options.all("metadata-config")) metadata.add(Path.of(file).toAbsolutePath());
        for (Path file : filters) {
            Node root = walkConfig(file, 0, true);
            if (root != null) collectPolicies(root);
        }
        for (Path file : relying) {
            Node root = walkConfig(file, 0, true);
            if (root != null) collectRp(root);
        }
        Set<Node> inspected = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Path file : metadata) {
            walkConfig(file, 0, true);
            if (incoming == null) for (var entry : List.copyOf(documents.entrySet())) inspectProviders(entry.getValue(), entry.getKey(), inspected);
        }
        if (incoming != null) {
            List<Node> providers = new ArrayList<>();
            for (var entry : List.copyOf(documents.entrySet())) probeProviders(entry.getValue(), false, providers);
            if (providers.size() != 1) problem("BLOCK", "TARGET_MANAGED_PROVIDER_UNRESOLVED", result.source.toString(),
                "Expected one unfiltered managed LocalDynamicMetadataProvider.", "Review target provider and ancestor filter configuration.");
            for (Candidate c : incoming) {
                Node node = parse(c.bytes, "incoming metadata " + c.entity);
                if (!MD.equals(node.namespace) || !node.name.equals("EntityDescriptor") || !node.attr("entityID").equals(c.entity))
                    throw new ImportException(6, "BUNDLE_INVALID: incoming metadata identity");
                entity(node, c.bytes, "incoming snapshot", List.of(), true);
            }
        }
        for (String file : options.all("metadata-file")) {
            int before = result.candidates.size();
            metadata(Path.of(file).toAbsolutePath(), "manual", List.of());
            for (Candidate c : result.candidates.subList(before, result.candidates.size()))
                c.diagnostic("REVIEW", "MANUAL_SOURCE_UNCONFIRMED", c.source, "Manually supplied metadata is not proof of active source registration.", "Confirm provenance and source policies before registration.");
        }
        // Imported configuration documents may contain additional filter/RP resources.
        for (Node root : documents.values()) { collectPolicies(root); collectRp(root); }
        for (Candidate candidate : result.candidates) analyzePolicies(candidate);
        if (separateAccess) {
            Path accessConfig = options.has("context-check-config") ? options.path("context-check-config")
                : result.source.resolve("conf/intercept/context-check-intercept-config.xml");
            SourceAccessAnalyzer accessAnalyzer = new SourceAccessAnalyzer(rp, accessConfig, this::bytes, result.source);
            for (Candidate candidate : result.candidates) candidate.access = accessAnalyzer.assess(candidate);
        }
        Map<String, List<Candidate>> grouped = new HashMap<>();
        for (Candidate c : result.candidates) grouped.computeIfAbsent(c.entity, k -> new ArrayList<>()).add(c);
        for (List<Candidate> same : grouped.values()) if (same.size() > 1) {
            for (int i = 0; i < same.size(); i++) {
                Candidate c = same.get(i); c.name += "-" + (i + 1);
                c.diagnostic("BLOCK", "ENTITY_ID_DUPLICATE_SOURCE", c.source,
                    "Multiple source definitions for this entityID; chain/filter precedence is not inferred.", "Choose authoritative single metadata and resolve duplicate source references.");
            }
        }
        for (Candidate c : result.candidates) for (Diagnostic diagnostic : result.diagnostics) {
            if (Set.of("BLOCK", "ERROR").contains(diagnostic.severity())) c.diagnostics.add(diagnostic);
        }
        if (!options.all("entity-id").isEmpty()) {
            Set<String> selected = Set.copyOf(options.all("entity-id"));
            for (String entity : selected) if (result.candidates.stream().noneMatch(c -> c.entity.equals(entity)))
                problem("BLOCK", "ENTITY_NOT_FOUND", "selection", entity, "Check metadata inputs and exact entityID.");
            result.candidates.removeIf(c -> !selected.contains(c.entity));
        }
        if (result.candidates.isEmpty()) problem("BLOCK", "NO_SP_CANDIDATES", result.source.toString(),
            "No SP candidates were found. This does not establish that the source has no SPs.", "Review unresolved resources or specify --metadata-config/--metadata-file.");
        for (var input : contents.entrySet()) {
            String hash = SafeFiles.hash(input.getValue()); result.inputHashes.put(input.getKey(), hash);
            if (!hash.equals(SafeFiles.hash(SafeFiles.read(input.getKey(), SafeFiles.MAX_FILE)))) {
                Diagnostic changed = new Diagnostic("BLOCK", "SOURCE_CHANGED_DURING_ANALYSIS", input.getKey().toString(), "Input changed while exporting.", "Pause source management and take a consistent snapshot.");
                result.diagnostics.add(changed); result.candidates.forEach(c -> c.diagnostics.add(changed));
            }
        }
        return result;
    }

    private void probeProviders(Node node, boolean filtered, List<Node> found) {
        boolean provider = node.name.equals("MetadataProvider");
        boolean unsupportedAncestor = provider && !node.attr("id").equals("GraphicalMatrixManagedSPMetadata")
            && (!node.namespace.equals("urn:mace:shibboleth:2.0:metadata")
                || !node.typeNamespace().equals(node.namespace) || !node.type().equals("ChainingMetadataProvider"));
        boolean effectiveFilter = filtered || unsupportedAncestor
            || provider && node.children.stream().anyMatch(n -> n.name.equals("MetadataFilter"));
        if (provider && node.attr("id").equals("GraphicalMatrixManagedSPMetadata")) {
            Path directory = resolvePath(node.attr("sourceDirectory"), result.source.resolve("conf/metadata-providers.xml"), true);
            if (!effectiveFilter && node.namespace.equals("urn:mace:shibboleth:2.0:metadata")
                    && node.typeNamespace().equals(node.namespace) && node.type().equals("LocalDynamicMetadataProvider")
                    && result.source.resolve("metadata/2faskw-managed-sp").equals(directory)) found.add(node);
            else problem("BLOCK", "TARGET_MANAGED_PROVIDER_UNRESOLVED", node.location,
                "Managed metadata provider/ancestor filters or directory cannot be interpreted.", "Resolve target effective metadata; no provider filters are executed.");
        }
        for (Node child : node.children) probeProviders(child, effectiveFilter, found);
    }

    private void loadProperties(Path file, Set<Path> visiting, boolean required) throws Exception {
        if (!Files.exists(file)) {
            if (required) problem("BLOCK", "PROPERTY_SOURCE_NOT_FOUND", file.toString(), "Property source is missing.", "Check path-map and property-source.");
            return;
        }
        Path real = file.toRealPath();
        if (!visiting.add(real)) { problem("BLOCK", "CONFIG_REFERENCE_CYCLE", file.toString(), "Property include cycle.", "Remove cyclic additionalProperties."); return; }
        try {
            String text = new String(bytes(file), StandardCharsets.UTF_8);
            Properties parsed = new Properties();
            parsed.load(new StringReader(text));
            for (String key : parsed.stringPropertyNames()) {
                if (key.equals("idp.home")) continue;
                String previous = properties.putIfAbsent(key, parsed.getProperty(key));
                if (previous != null && !previous.equals(parsed.getProperty(key)))
                    problem("BLOCK", "PROPERTY_PRECEDENCE_UNKNOWN", file.toString(), "Conflicting definition of property " + key + " (values redacted).", "Supply an unambiguous source snapshot; do not guess precedence.");
            }
            // Preserve definition locations without ever recording property values.
            String[] lines = text.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].stripLeading();
                if (!line.isBlank() && !line.startsWith("#") && !line.startsWith("!"))
                    result.evidence.add(file + ":" + (i + 1) + " property definition (value omitted)");
            }
            for (String additional : parsed.getProperty("idp.additionalProperties", "").split(",")) if (!additional.isBlank()) {
                Path next = resolvePath(additional.trim(), file, true);
                if (next != null) loadProperties(next, visiting, true);
            }
        } finally { visiting.remove(real); }
    }

    private byte[] bytes(Path file) throws Exception {
        Path real = file.toRealPath();
        if (contents.containsKey(real)) return contents.get(real);
        if (contents.size() >= 1000) throw new ImportException(3, "RESOURCE_LIMIT_EXCEEDED: more than 1000 files");
        byte[] value = SafeFiles.read(real, SafeFiles.MAX_FILE);
        inputBytes += value.length;
        if (inputBytes > 256L * 1024 * 1024) throw new ImportException(3, "RESOURCE_LIMIT_EXCEEDED: total input exceeds 256 MiB");
        contents.put(real, value);
        boolean sensitive = real.toString().toLowerCase(java.util.Locale.ROOT).matches(".*(credentials|secrets|password|private).*" );
        result.evidence.add("read " + SafeFiles.display(file.toString()) + " real=" + SafeFiles.display(real.toString())
            + (sensitive ? " (sensitive file hash omitted)" : " sha256=" + SafeFiles.hash(value)));
        return value;
    }

    private Node walkConfig(Path file, int depth, boolean required) throws Exception {
        if (!Files.exists(file)) {
            if (required) problem("BLOCK", "CONFIG_IMPORT_NOT_FOUND", file.toString(), "Referenced configuration is missing.", "Restore source file or use --path-map.");
            return null;
        }
        Path real = file.toRealPath();
        if (active.contains(real)) { problem("BLOCK", "CONFIG_REFERENCE_CYCLE", file.toString(), "XML import cycle.", "Remove import cycle."); return null; }
        if (documents.containsKey(real)) return documents.get(real);
        if (depth >= 32) { problem("BLOCK", "RESOURCE_LIMIT_EXCEEDED", file.toString(), "Import depth exceeds 32.", "Split or simplify configuration."); return null; }
        active.add(real);
        try {
            Node root = parse(bytes(file), file.toString());
            documents.put(real, root);
            for (Node node : root.descendants()) {
                if (!node.attr("id").isEmpty()) beans.computeIfAbsent(node.attr("id"), k -> new ArrayList<>()).add(node);
                if (node.namespace.equals(SPRING) && node.name.equals("import")) {
                    Path next = resolvePath(node.attr("resource"), file, false);
                    if (next != null) walkConfig(next, depth + 1, true);
                }
            }
            return root;
        } catch (AccessDeniedException ex) {
            problem("BLOCK", "FILE_READ_DENIED", file.toString(), "Cannot read referenced configuration.", "Use appropriate read permissions; do not weaken file permissions.");
        } catch (Exception ex) {
            problem("BLOCK", "XML_PARSE_ERROR", file.toString(), SafeFiles.display(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), "Check XML line/column, external references and size limits.");
        } finally { active.remove(real); }
        return null;
    }

    private Set<Path> resources(String property, String defaultBean, String fallback) throws Exception {
        Set<Path> found = new java.util.LinkedHashSet<>();
        String configured = expand(properties.getOrDefault(property, defaultBean));
        if (configured == null) return found;
        for (String item : configured.split(",")) {
            String reference = item.trim();
            List<Node> definitions = beans.getOrDefault(reference, List.of());
            if (definitions.size() == 1) staticResources(definitions.getFirst(), found, new HashSet<>());
            else if (definitions.size() > 1) problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", reference, "Duplicate resource bean definitions.", "Resolve source bean overrides before migration.");
            else if (reference.equals(defaultBean)) {
                Path path = result.source.resolve(fallback);
                found.add(path);
                result.evidence.add("conventional fallback " + path + "; configured runtime resource list not proven");
                problem("REVIEW", "CONVENTIONAL_RESOURCE_FALLBACK", path.toString(), "Static service resource bean not found; conventional path used.", "Confirm source uses this resource; specify custom service resources if different.");
            } else {
                Path path = resolvePath(reference, result.source.resolve("conf/services.xml"), true);
                if (path != null && reference.endsWith(".xml")) found.add(path);
                else problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", reference, "Service resource bean cannot be resolved statically.", "Supply static service list or referenced XML.");
            }
        }
        return found;
    }

    private void staticResources(Node node, Set<Path> found, Set<String> chain) throws Exception {
        if (!Set.of(SPRING, "http://www.springframework.org/schema/util").contains(node.namespace)) {
            problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", node.location, "Unknown resource element namespace.", "Provide a static Spring resource list."); return;
        }
        String reference = node.attr("bean");
        if (reference.isEmpty()) reference = node.attr("local");
        if (!reference.isEmpty()) {
            if (!chain.add(reference)) { problem("BLOCK", "CONFIG_REFERENCE_CYCLE", node.location, "Resource bean cycle.", "Remove cyclic references."); return; }
            List<Node> definitions = beans.getOrDefault(reference, List.of());
            if (definitions.size() == 1) staticResources(definitions.getFirst(), found, chain);
            else problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", node.location, "Resource reference unresolved: " + reference, "Provide a static resource list.");
            chain.remove(reference);
        } else if (node.name.equals("value")) {
            Path value = resolvePath(node.text.toString().trim(), result.source.resolve("conf/services.xml"), true);
            if (value != null) found.add(value);
        } else if (Set.of("list", "set", "array").contains(node.name)) {
            for (Node child : node.children) staticResources(child, found, chain);
        } else {
            problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", node.location, "Dynamic/factory resource bean is not executed.", "Provide a static service resource list.");
        }
    }

    private String expand(String value) {
        Pattern pattern = Pattern.compile("%\\{([^}:]+)(?::([^}]*))?}");
        for (int depth = 0; depth < 32; depth++) {
            var matcher = pattern.matcher(value);
            if (!matcher.find()) {
                if (value.contains("#{") || value.contains("${") || value.contains("%{")) {
                    problem("BLOCK", "PROPERTY_UNRESOLVED", "resource", "Unsupported expression (value omitted).", "Supply statically resolvable properties/path."); return null;
                }
                return value;
            }
            String replacement = properties.getOrDefault(matcher.group(1), matcher.group(2));
            if (replacement == null) { problem("BLOCK", "PROPERTY_UNRESOLVED", "resource", "Missing property " + matcher.group(1), "Specify --property-source."); return null; }
            value = value.substring(0, matcher.start()) + replacement + value.substring(matcher.end());
        }
        problem("BLOCK", "CONFIG_REFERENCE_CYCLE", "resource", "Property recursion/depth limit.", "Remove cyclic property references.");
        return null;
    }

    private Path resolvePath(String value, Path config, boolean idpRelative) {
        String expanded = expand(value);
        if (expanded == null) return null;
        if (expanded.startsWith("file:")) expanded = expanded.substring(5);
        if (expanded.isBlank() || expanded.contains("://") || expanded.startsWith("classpath:") || expanded.contains("*") || expanded.contains("?")) {
            problem("BLOCK", "CONFIG_RESOURCE_UNRESOLVED", config.toString(), "Network/classpath/pattern resource is not read automatically.", "Provide a local static snapshot and explicit resource path."); return null;
        }
        Path path = Path.of(expanded);
        if (path.isAbsolute()) {
            for (Path old : pathMaps.keySet().stream().sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount())).toList()) {
                if (path.startsWith(old)) { path = pathMaps.get(old).resolve(old.relativize(path)).normalize(); break; }
            }
            if (expanded.startsWith("/conf/") || expanded.startsWith("/credentials/") || expanded.startsWith("/metadata/")) path = result.source.resolve(expanded.substring(1));
        } else path = (idpRelative ? result.source : config.toAbsolutePath().getParent()).resolve(path);
        path = path.toAbsolutePath().normalize();
        if (options.has("target-idp-home") && path.startsWith(options.path("target-idp-home"))) {
            problem("BLOCK", "PATH_MAP_REQUIRED", config.toString(), "Source reference points into target IdP.", "Map source snapshot paths explicitly; do not read target as source."); return null;
        }
        return path;
    }

    private void provider(Node node, Path config, List<Diagnostic> inherited) throws Exception {
        String type = node.type();
        if (!node.namespace.equals("urn:mace:shibboleth:2.0:metadata") || !node.typeNamespace().equals(node.namespace)) {
            problem("BLOCK", "PROVIDER_TYPE_UNSUPPORTED", node.location, "Unknown provider/type namespace.", "Resolve provider schema and effective source behavior."); return;
        }
        List<Diagnostic> diagnostics = new ArrayList<>(inherited);
        for (Node filter : node.children.stream().filter(n -> n.name.equals("MetadataFilter")).toList())
            describeFilter(filter, diagnostics);
        String id = node.attr("id") + " (" + type + ")";
        result.evidence.add("provider " + SafeFiles.display(id) + " at " + node.location);
        switch (type) {
            case "ChainingMetadataProvider" -> {
                // Descendants are visited separately; carry chain filters into each child's diagnostics.
                for (Node child : node.children) if (child.name.equals("MetadataProvider")) provider(child, config, diagnostics);
            }
            case "FilesystemMetadataProvider", "FileBackedHTTPMetadataProvider" -> {
                if (type.equals("FileBackedHTTPMetadataProvider")) diagnostics.add(refresh(node));
                String name = type.equals("FilesystemMetadataProvider") ? node.attr("metadataFile") : node.attr("backingFile");
                Path path = resolvePath(name, config, true);
                if (path != null) metadata(path, id, diagnostics);
            }
            case "LocalDynamicMetadataProvider" -> {
                Path directory = resolvePath(node.attr("sourceDirectory"), config, true);
                diagnostics.add(refresh(node));
                if (directory != null && Files.isDirectory(directory)) {
                    try (var files = Files.list(directory)) {
                        List<Path> entries = files.filter(Files::isRegularFile).sorted().limit(1001).toList();
                        if (entries.size() > 1000) problem("BLOCK", "RESOURCE_LIMIT_EXCEEDED", node.location, "Dynamic directory contains more than 1000 files.", "Narrow source snapshot.");
                        else for (Path file : entries) metadata(file, id, diagnostics);
                    }
                } else problem("BLOCK", "METADATA_FILE_NOT_FOUND", node.location, "Dynamic source directory not found.", "Provide source directory/path-map.");
            }
            case "InlineMetadataProvider" -> {
                List<Node> entities = node.descendants("EntityDescriptor").stream().filter(n -> MD.equals(n.namespace)).toList();
                for (Node entity : entities) entity(entity, null, id, diagnostics, false);
            }
            case "DynamicHTTPMetadataProvider" -> problem("REVIEW", "MDQ_LIST_INCOMPLETE", node.location,
                "Dynamic/MDQ resolver cannot enumerate all source SPs; no URL is fetched.", "Supply exact entity metadata and source policy context explicitly.");
            default -> problem("BLOCK", "PROVIDER_TYPE_UNSUPPORTED", node.location, "Unsupported provider " + type,
                "Supply static effective metadata and resolve provider semantics.");
        }
    }

    private void describeFilter(Node filter, List<Diagnostic> diagnostics) {
        String ns = "urn:mace:shibboleth:2.0:metadata";
        boolean standard = filter.namespace.equals(ns) && filter.typeNamespace().equals(ns);
        Set<String> allowed = new HashSet<>(Set.of("id", "{" + XSI + "}type"));
        String code = "METADATA_FILTER_UNSUPPORTED";
        if (separateAccess && standard) switch (filter.type()) {
            case "ChainingFilter" -> {
                if (filter.attributes.keySet().stream().allMatch(allowed::contains) && filter.text.toString().isBlank()
                        && !filter.children.isEmpty() && filter.children.stream().allMatch(n -> n.name.equals("MetadataFilter"))) {
                    for (Node child : filter.children) describeFilter(child, diagnostics);
                    return;
                }
            }
            case "SignatureValidation" -> {
                allowed.addAll(Set.of("certificateFile", "trustEngineRef", "requireSignedRoot", "requireSignedMetadata", "verifyBackup"));
                if (filter.children.isEmpty() && filter.text.toString().isBlank()
                        && filter.attributes.keySet().stream().allMatch(allowed::contains)
                        && List.of("requireSignedRoot", "requireSignedMetadata", "verifyBackup").stream().allMatch(k -> booleanSetting(filter.attr(k))))
                    code = "METADATA_SIGNATURE_VALIDATION_REQUIRED";
            }
            case "RequiredValidUntil" -> {
                allowed.add("maxValidityInterval");
                if (filter.children.isEmpty() && filter.text.toString().isBlank() && filter.attributes.keySet().stream().allMatch(allowed::contains))
                    code = "METADATA_VALIDITY_FILTER_REVIEW_REQUIRED";
            }
            case "EntityRole", "EntityRoleWhiteList" -> {
                allowed.addAll(Set.of("removeRolelessEntityDescriptors", "removeEmptyEntitiesDescriptors"));
                boolean spOnly = !filter.children.isEmpty() && filter.children.stream().allMatch(n -> n.namespace.equals(ns)
                    && n.name.equals("RetainedRole") && n.children.isEmpty() && n.attributes.isEmpty() && spRole(n));
                if (options.flag("extract-aggregate") && spOnly && filter.text.toString().isBlank()
                        && filter.attributes.keySet().stream().allMatch(allowed::contains)
                        && List.of("removeRolelessEntityDescriptors", "removeEmptyEntitiesDescriptors").stream().allMatch(k -> booleanSetting(filter.attr(k))))
                    code = "METADATA_ROLE_FILTER_REVIEW_REQUIRED";
            }
            default -> { }
        }
        boolean review = !code.equals("METADATA_FILTER_UNSUPPORTED");
        Map<String, String> settings = new java.util.TreeMap<>();
        filter.attributes.forEach((key, value) -> settings.put(key, allowed.contains(key) ? value : "<value omitted>"));
        String detail = "Provider filter: " + filter.type() + "; settings=" + settings
            + (filter.children.isEmpty() ? "" : "; retained/configured children=" + filter.children.stream()
                .map(n -> n.name.equals("RetainedRole") && spRole(n) ? n.name + "=" + n.text.toString().trim() : n.name + " (content omitted)").toList());
        Diagnostic d = new Diagnostic(review ? "REVIEW" : "BLOCK", code, filter.location, detail,
            review ? "Verify original metadata, source trust engine/validity and filter order independently; use --confirm-metadata-review only after verification."
                : "Unknown/mutating filter remains held. Role-only snapshots require --extract-aggregate and an explicit SPSSODescriptor-only whitelist; do not remove source filters.");
        diagnostics.add(d);
        if (review) reviewFilters.put(d, filter);
    }

    private static boolean booleanSetting(String value) { return Set.of("", "true", "false", "1", "0").contains(value); }
    private static boolean spRole(Node node) {
        String value = node.text.toString().trim();
        return value.substring(value.lastIndexOf(':') + 1).equals("SPSSODescriptor")
            && MD.equals(node.namespaces.getOrDefault(value.contains(":") ? value.substring(0, value.indexOf(':')) : "", ""));
    }

    private List<Diagnostic> validateReviewFilters(Node root, List<Diagnostic> inherited) {
        List<Diagnostic> diagnostics = new ArrayList<>(inherited);
        for (Diagnostic d : inherited) {
            Node filter = reviewFilters.get(d);
            if (filter == null) continue;
            String error = null;
            if (filter.type().equals("SignatureValidation") && List.of("requireSignedRoot", "requireSignedMetadata").stream()
                    .anyMatch(k -> Set.of("true", "1").contains(filter.attr(k)))
                    && root.children.stream().noneMatch(n -> n.name.equals("Signature") && n.namespace.equals(MetadataSnapshots.DS)))
                error = "reason=ROOT_SIGNATURE_MISSING; filter=" + filter.location
                    + "; requireSignedRoot=" + filter.attr("requireSignedRoot")
                    + "; requireSignedMetadata=" + filter.attr("requireSignedMetadata") + "; checked_at=" + checkedAt;
            if (filter.type().equals("RequiredValidUntil")) {
                String validity = root.attr("validUntil"), interval = filter.attr("maxValidityInterval");
                String reason = validity.isEmpty() ? "VALID_UNTIL_MISSING" : MetadataSnapshots.validityProblem(validity, "", checkedAt);
                String limit = "";
                if (reason == null && !interval.isEmpty()) {
                    try {
                        Duration duration = interval.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(interval)) : Duration.parse(interval);
                        if (duration.isNegative()) reason = "MAX_VALIDITY_INTERVAL_NEGATIVE";
                        else if (!duration.isZero()) {
                            Instant upperBound = checkedAt.plus(duration);
                            limit = "; allowed_until=" + upperBound;
                            if (Instant.parse(validity).isAfter(upperBound)) reason = "VALIDITY_WINDOW_EXCEEDED";
                        }
                    } catch (RuntimeException ex) { reason = "MAX_VALIDITY_INTERVAL_UNSUPPORTED"; }
                }
                // An omitted interval is version-dependent and remains an explicit review item.
                if (reason != null) error = "reason=" + reason + "; filter=RequiredValidUntil at " + filter.location
                    + "; maxValidityInterval=" + (interval.isEmpty() ? "<version-dependent default>" : SafeFiles.display(interval))
                    + "; " + MetadataSnapshots.validityEvidence(validity, root.attr("cacheDuration"), checkedAt) + limit;
            }
            if (error != null) diagnostics.add(new Diagnostic("BLOCK", "METADATA_SOURCE_FILTER_REJECTED", root.location, error,
                "Obtain current authoritative metadata and verify source filter configuration; do not bypass validation."));
        }
        return diagnostics;
    }

    private Diagnostic refresh(Node node) {
        return new Diagnostic("REVIEW", "METADATA_REFRESH_BEHAVIOR_CHANGED", node.location,
            "Dynamic/remote metadata becomes a static managed snapshot; no URL fetched or automatic refresh preserved.", "Confirm a snapshot update procedure or keep a separate dynamic provider.");
    }

    private void inspectProviders(Node node, Path config, Set<Node> inspected) throws Exception {
        if (node.name.equals("MetadataProvider")) {
            if (inspected.add(node)) provider(node, config, List.of());
            return;
        }
        for (Node child : node.children) inspectProviders(child, config, inspected);
    }

    private void metadata(Path file, String provider, List<Diagnostic> inherited) throws Exception {
        try {
            byte[] bytes = bytes(file);
            Node root = parse(bytes, file.toString());
            if (!MD.equals(root.namespace) || !Set.of("EntityDescriptor", "EntitiesDescriptor").contains(root.name))
                throw new IllegalArgumentException("not SAML metadata");
            List<Diagnostic> effective = validateReviewFilters(root, inherited);
            boolean roleSnapshot = effective.stream().anyMatch(d -> d.code().equals("METADATA_ROLE_FILTER_REVIEW_REQUIRED"));
            MetadataSnapshots snapshots = separateAccess && options.flag("extract-aggregate") ? new MetadataSnapshots(bytes, file.toString()) : null;
            int ordinal = 0;
            for (Node node : root.descendants("EntityDescriptor")) if (MD.equals(node.namespace)) {
                List<Diagnostic> context = new ArrayList<>(effective);
                if (root != node && snapshots == null) context.add(new Diagnostic("BLOCK", "METADATA_PARENT_CONTEXT_REQUIRED", root.location,
                    "Aggregate extraction may lose namespace/signature/group/validity context.", "Obtain an authoritative standalone EntityDescriptor; automatic extraction is not supported."));
                byte[] exported = bytes;
                boolean standalone = root == node;
                MetadataSnapshots.Snapshot snapshot = null;
                if (snapshots != null && (root != node || roleSnapshot)) {
                    try {
                        snapshot = snapshots.extract(ordinal, roleSnapshot, checkedAt);
                        exported = snapshot.bytes(); standalone = true;
                        context.add(new Diagnostic("REVIEW", "METADATA_AGGREGATE_EXTRACTED", node.location,
                            "Unsigned independent snapshot; namespaces and restrictive parent expiry are preserved, original signatures are not transferable.",
                            "Verify evidence/metadata-sources original XML and source hash/signature; apply requires --confirm-metadata-review and typed confirmation."));
                    } catch (MetadataSnapshots.Unsupported ex) {
                        Node failed = node;
                        for (int i = 0; i < ex.ancestorDepth && failed.parent != null; i++) failed = failed.parent;
                        context.add(new Diagnostic("BLOCK", ex.code, failed.location, ex.getMessage(), "Obtain authoritative standalone metadata or resolve parent/filter semantics manually."));
                        exported = null; standalone = false;
                    }
                }
                int before = result.candidates.size();
                Node analyzed = snapshot == null ? node : parse(exported, node.source);
                entity(analyzed, exported, provider, context, standalone);
                if (result.candidates.size() > before) {
                    Candidate c = result.candidates.getLast();
                    for (Node ancestor = node; ancestor != null && MD.equals(ancestor.namespace); ancestor = ancestor.parent)
                        c.facts.add("metadata_context=" + ancestor.name + " name=" + SafeFiles.display(ancestor.attr("Name"))
                            + " at " + ancestor.location + "; "
                            + MetadataSnapshots.validityEvidence(ancestor.attr("validUntil"), ancestor.attr("cacheDuration"), checkedAt));
                }
                if (snapshot != null && result.candidates.size() > before) {
                    Candidate c = result.candidates.getLast();
                    c.sourceMetadataHash = SafeFiles.hash(bytes);
                    result.metadataSources.putIfAbsent(c.sourceMetadataHash, bytes);
                    c.facts.addAll(snapshot.facts());
                    c.facts.add("source_entity_location=" + node.location);
                    c.facts.add("source_metadata_sha256=" + c.sourceMetadataHash);
                }
                ordinal++;
            }
        } catch (Exception ex) {
            problem("BLOCK", Files.exists(file) ? "XML_PARSE_ERROR" : "METADATA_FILE_NOT_FOUND", file.toString(),
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), "Check permissions, XML, size limits and source path mapping.");
        }
    }

    private void entity(Node node, byte[] bytes, String provider, List<Diagnostic> inherited, boolean standalone) {
        List<Node> roles = node.children.stream().filter(n -> MD.equals(n.namespace) && n.name.equals("SPSSODescriptor")).toList();
        if (roles.isEmpty()) { result.evidence.add("excluded IdP-only/non-SP entity at " + node.location); return; }
        Candidate c = new Candidate(node.attr("entityID"), node.location, provider);
        entityNodes.put(c, node);
        c.diagnostics.addAll(inherited);
        if (result.candidates.size() >= 10000) throw new IllegalStateException("RESOURCE_LIMIT_EXCEEDED: entity count");
        result.candidates.add(c);
        if (c.entity.isBlank() || c.entity.chars().anyMatch(v -> v < 32 || v == 127)) c.diagnostic("BLOCK", "ENTITY_NOT_SUPPORTED_BY_CLI", node.location, "Invalid entityID.", "Obtain valid metadata.");
        if (!standalone) c.diagnostic("BLOCK", "METADATA_EXTRACTION_UNSUPPORTED", node.location,
            "Inline/aggregate metadata is inventoried but not automatically extracted.", "Obtain authoritative standalone metadata and rerun with source context.");
        if (bytes != null && standalone) { c.bytes = bytes; c.hash = SafeFiles.hash(bytes); }
        if (roles.size() != 1 || node.children.stream().anyMatch(n -> n.name.equals("IDPSSODescriptor")))
            c.diagnostic("BLOCK", "ENTITY_NOT_SUPPORTED_BY_CLI", node.location, "Multiple or mixed roles.", "Use metadata supported by the existing add command.");
        for (Node role : roles) {
            c.facts.add("protocols=" + SafeFiles.display(role.attr("protocolSupportEnumeration")));
            c.facts.add("AuthnRequestsSigned=" + role.attr("AuthnRequestsSigned") + " WantAssertionsSigned=" + role.attr("WantAssertionsSigned"));
            for (Node endpoint : role.descendants()) if (Set.of("AssertionConsumerService", "SingleLogoutService", "NameIDFormat", "RequestedAttribute", "X509Certificate").contains(endpoint.name)) {
                switch (endpoint.name) {
                    case "X509Certificate" -> {
                        try {
                            byte[] der = Base64.getMimeDecoder().decode(endpoint.text.toString());
                            CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
                            c.facts.add("certificate_sha256=" + SafeFiles.hash(der));
                        } catch (Exception ex) { c.diagnostic("BLOCK", "CERTIFICATE_INVALID", endpoint.location, "Invalid X.509 certificate.", "Obtain valid SP metadata."); }
                    }
                    case "NameIDFormat" -> c.facts.add("NameIDFormat=" + SafeFiles.display(endpoint.text.toString().trim()));
                    case "RequestedAttribute" -> c.facts.add("RequestedAttribute=" + SafeFiles.display(endpoint.attr("Name")) + " (request only, not release approval)");
                    default -> c.facts.add(endpoint.name + " Location=" + SafeFiles.display(endpoint.attr("Location"))
                        + " Binding=" + SafeFiles.display(endpoint.attr("Binding")) + " index=" + endpoint.attr("index") + " isDefault=" + endpoint.attr("isDefault"));
                }
            }
        }
        c.facts.add("validUntil=" + node.attr("validUntil") + " cacheDuration=" + node.attr("cacheDuration"));
        if (!node.attr("validUntil").isBlank()) {
            String reason = MetadataSnapshots.validityProblem(node.attr("validUntil"), "", checkedAt);
            if (reason != null) c.diagnostic("BLOCK", "METADATA_EFFECTIVE_EXPIRED", node.location,
                "reason=" + reason + "; scope=EntityDescriptor; "
                    + MetadataSnapshots.validityEvidence(node.attr("validUntil"), node.attr("cacheDuration"), checkedAt),
                "Check the authoritative metadata timestamp and server clock; do not manually extend expiry.");
        }
        if (!node.descendants("Signature").isEmpty()) c.diagnostic("REVIEW", "METADATA_SIGNATURE_UNVERIFIED", node.location, "Signature exists but has not been cryptographically verified by this parser.", "Verify provenance/signature independently.");
        c.diagnostic("REVIEW", "SOURCE_TRUST_REVIEW_REQUIRED", node.location, "SHA-256 proves byte consistency only, not metadata authenticity or active source configuration.", "Confirm source trust, NameID/encryption requirements, and SP-side cutover.");
    }

    private void collectPolicies(Node root) {
        for (Node n : root.descendants("AttributeFilterPolicy")) if (AF.equals(n.namespace) && !policies.contains(n)) policies.add(n);
    }

    private void collectRp(Node root) {
        for (Node n : root.descendants()) if ((n.name.equals("bean") && !n.attr("class").isBlank())
                || n.attr("id").equals("shibboleth.DefaultRelyingParty") || n.attr("id").equals("shibboleth.RelyingPartyOverrides")) {
            if (!rp.contains(n)) rp.add(n);
        }
    }

    private enum Match { YES, NO, UNKNOWN }
    private Match condition(Node node, Candidate candidate) {
        if (node == null || !AF.equals(node.namespace) || !AF.equals(node.typeNamespace())) return Match.UNKNOWN;
        return switch (node.type()) {
            case "ANY" -> leaf(node, Set.of()) ? Match.YES : Match.UNKNOWN;
            case "Requester" -> leaf(node, Set.of("value")) && literal(node.attr("value"))
                ? (node.attr("value").equals(candidate.entity) ? Match.YES : Match.NO) : Match.UNKNOWN;
            case "EntityAttributeExactMatch" -> entityAttribute(node, candidate);
            case "OR", "AND" -> {
                if (!ruleAttributes(node, Set.of()) || node.children.stream().anyMatch(n -> !n.name.equals("Rule"))) yield Match.UNKNOWN;
                List<Match> matches = node.children.stream().map(n -> condition(n, candidate)).toList();
                if (matches.isEmpty()) yield Match.UNKNOWN;
                if (node.type().equals("OR") && matches.contains(Match.YES)) yield Match.YES;
                if (node.type().equals("AND") && matches.contains(Match.NO)) yield Match.NO;
                if (matches.contains(Match.UNKNOWN)) yield Match.UNKNOWN;
                yield node.type().equals("AND") ? Match.YES : Match.NO;
            }
            default -> Match.UNKNOWN;
        };
    }

    private boolean literal(String value) {
        return !value.isBlank() && !value.contains("%{") && !value.contains("#{") && !value.contains("${");
    }

    private boolean ruleAttributes(Node node, Set<String> allowed) {
        return node.text.toString().isBlank() && node.attributes.keySet().stream()
            .allMatch(key -> key.equals("id") || key.equals("{" + XSI + "}type") || allowed.contains(key));
    }

    private boolean leaf(Node node, Set<String> allowed) {
        return node.children.isEmpty() && ruleAttributes(node, allowed);
    }

    private Match entityAttribute(Node rule, Candidate candidate) {
        if (!leaf(rule, Set.of("attributeName", "attributeValue", "attributeNameFormat", "ignoreUnmappedEntityAttributes"))
                || !literal(rule.attr("attributeName")) || !literal(rule.attr("attributeValue"))
                || !literal(rule.attr("attributeNameFormat"))
                || !Set.of("", "false", "0").contains(rule.attr("ignoreUnmappedEntityAttributes"))
                || List.of("attributeName", "attributeValue", "attributeNameFormat").stream()
                    .anyMatch(key -> !rule.attr(key).equals(rule.attr(key).trim())))
            return unresolvedTag(rule, candidate, "requires literal Name/Value, explicit NameFormat and raw-XML mode; mapped registry/dynamic/unknown options are not evaluated");
        Node entity = entityNodes.get(candidate);
        if (candidate.bytes == null || entity == null || candidate.diagnostics.stream()
                .anyMatch(d -> d.code().equals("METADATA_FILTER_UNSUPPORTED")))
            return unresolvedTag(rule, candidate, "effective entity tags are unresolved: inline/aggregate context or unsupported provider filter");
        String tagsNamespace = "urn:oasis:names:tc:SAML:metadata:attribute";
        String assertionNamespace = "urn:oasis:names:tc:SAML:2.0:assertion";
        boolean found = false;
        boolean unknown = false;
        // Only entity-level tags are requester tags; role/requested attributes are not.
        for (Node extension : entity.children) if (MD.equals(extension.namespace) && extension.name.equals("Extensions")) {
            for (Node tags : extension.children) if (tagsNamespace.equals(tags.namespace) && tags.name.equals("EntityAttributes")) {
                for (Node attribute : tags.children) {
                    if (!assertionNamespace.equals(attribute.namespace) || !attribute.name.equals("Attribute")) {
                        unknown = true;
                        continue;
                    }
                    if (!attribute.attr("Name").equals(rule.attr("attributeName"))
                            || !attribute.attr("NameFormat").equals(rule.attr("attributeNameFormat"))) continue;
                    for (Node value : attribute.children) {
                        if (!assertionNamespace.equals(value.namespace) || !value.name.equals("AttributeValue")
                                || !value.children.isEmpty() || value.attributes.keySet().stream().anyMatch(k -> !k.equals("{" + XSI + "}type"))
                                || (!value.attr("{" + XSI + "}type").isEmpty()
                                    && (!value.type().equals("string") || !value.typeNamespace().equals("http://www.w3.org/2001/XMLSchema")))) {
                            unknown = true;
                            continue;
                        }
                        if (value.text.toString().equals(rule.attr("attributeValue"))) found = true;
                    }
                }
            }
        }
        if (unknown) return unresolvedTag(rule, candidate, "entity tags contain unsupported assertions, complex/nil/non-string values or unknown elements");
        Match match = found ? Match.YES : Match.NO;
        candidate.facts.add("entity_attribute_match=" + match + " name=" + SafeFiles.display(rule.attr("attributeName"))
            + " value=" + SafeFiles.display(rule.attr("attributeValue")) + " at " + rule.location);
        return match;
    }

    private Match unresolvedTag(Node rule, Candidate candidate, String reason) {
        candidate.facts.add("entity_attribute_match=UNKNOWN reason=" + reason + " at " + rule.location);
        return Match.UNKNOWN;
    }

    private Match permit(Node rule, Candidate candidate) {
        if (!AF.equals(rule.namespace) || !ruleAttributes(rule, Set.of("attributeID", "permitAny"))) return Match.UNKNOWN;
        String any = rule.attr("permitAny");
        if (Set.of("true", "1").contains(any)) return rule.children.isEmpty() ? Match.YES : Match.UNKNOWN;
        if (!Set.of("", "false", "0").contains(any) || rule.children.size() != 1
                || !rule.children.getFirst().name.equals("PermitValueRule")) return Match.UNKNOWN;
        return condition(rule.children.getFirst(), candidate);
    }

    private void analyzePolicies(Candidate c) {
        for (Node policy : policies) {
            List<Node> requirements = policy.children.stream().filter(n -> n.name.equals("PolicyRequirementRule") && AF.equals(n.namespace)).toList();
            Match match = requirements.size() == 1 ? condition(requirements.getFirst(), c) : Match.UNKNOWN;
            boolean managedShape = requirements.size() == 1 && requirements.getFirst().type().equals("Requester")
                && match == Match.YES && leaf(requirements.getFirst(), Set.of("value"))
                && ruleAttributes(policy, Set.of());
            Set<String> releasedAttributes = new java.util.TreeSet<>();
            if (match == Match.NO) {
                c.releasePolicies.add(new ReleasePolicy(policy.attr("id"), false, Set.of(), policy.location));
                continue;
            }
            if (match == Match.UNKNOWN) { c.diagnostic("BLOCK", "ATTRIBUTE_POLICY_UNRESOLVED", policy.location,
                "Policy applicability cannot be resolved: policy=" + SafeFiles.display(policy.attr("id"))
                    + "; requirement=" + (requirements.size() == 1 ? conditionEvidence(requirements.getFirst()) : "count=" + requirements.size()),
                "Check entity_attribute_match evidence; resolve effective metadata, explicit NameFormat or unsupported group/value/ref/script conditions before registration."); continue; }
            c.facts.add("attribute_policy=" + SafeFiles.display(policy.attr("id")) + " at " + policy.location);
            for (Node rule : policy.children) if (rule.name.equals("AttributeRule")) {
                Match released = permit(rule, c);
                managedShape &= allValueShape(rule);
                boolean validId = rule.attr("attributeID").matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}");
                if (!validId) {
                    String id = rule.attr("attributeID");
                    String reason = id.isBlank() ? "ATTRIBUTE_ID_MISSING"
                        : !id.equals(id.strip()) ? "ATTRIBUTE_ID_WHITESPACE" : "ATTRIBUTE_ID_INVALID";
                    attributeHold(c, policy, rule, reason,
                        "Compare the exact attributeID with the source resolver and registry. Back up and correct only after confirming administrator intent; no automatic trimming or renaming is performed.");
                }
                if (released == Match.UNKNOWN) attributeHold(c, policy, rule, attributeReason(rule),
                    "Preserve the original value/deny conditions. Plan equivalent target attribute filtering and filtered AACLI tests; do not replace conditions with ANY. Unsupported expressions require independent review.");
                if (validId && released == Match.YES) { c.attributes.add(rule.attr("attributeID")); releasedAttributes.add(rule.attr("attributeID")); }
            }
            if (policy.children.stream().anyMatch(n -> !Set.of("PolicyRequirementRule", "AttributeRule").contains(n.name)))
                c.diagnostic("BLOCK", "ATTRIBUTE_POLICY_UNRESOLVED", policy.location, "Unknown policy child/ref.", "Resolve full source policy composition.");
            c.releasePolicies.add(new ReleasePolicy(policy.attr("id"), managedShape, Set.copyOf(releasedAttributes), policy.location));
        }
        for (Node node : rp) {
            if (!separateAccess && node.attr("id").equals("shibboleth.RelyingPartyOverrides") && !node.children.isEmpty())
                c.diagnostic("BLOCK", "RP_DEPENDENCY_UNRESOLVED", node.location,
                    "Source relying-party overrides require manual equivalence review; no override is copied.", "Resolve target NameID/authentication/encryption/intercept requirements before registration.");
            if (node.attr("id").equals("shibboleth.DefaultRelyingParty"))
                c.facts.add("default_relying_party at " + node.location + " (not copied; review target defaults)");
        }
    }

    private boolean allValueShape(Node rule) {
        if (!AF.equals(rule.namespace) || !ruleAttributes(rule, Set.of("attributeID", "permitAny"))) return false;
        if (Set.of("true", "1").contains(rule.attr("permitAny"))) return rule.children.isEmpty();
        if (rule.children.size() != 1) return false;
        Node permit = rule.children.getFirst();
        return permit.name.equals("PermitValueRule") && AF.equals(permit.namespace) && AF.equals(permit.typeNamespace())
            && permit.type().equals("ANY") && leaf(permit, Set.of());
    }

    private void attributeHold(Candidate c, Node policy, Node rule, String reason, String remedy) {
        String policyId = policy.attr("id").isEmpty() ? "<unnamed at " + policy.location + ">" : policy.attr("id");
        c.diagnostic("BLOCK", "ATTRIBUTE_CONDITION_UNSUPPORTED", rule.location,
            "reason=" + reason + "; policy=" + SafeFiles.display(policyId)
                + "; attributeID=\"" + SafeFiles.display(rule.attr("attributeID")) + "\"; condition=" + conditionEvidence(rule), remedy);
    }

    private String attributeReason(Node rule) {
        if (!AF.equals(rule.namespace) || !ruleAttributes(rule, Set.of("attributeID", "permitAny"))
                || !booleanSetting(rule.attr("permitAny")))
            return "ATTRIBUTE_RULE_UNRESOLVED";
        if (!rule.children.isEmpty() && rule.children.stream().allMatch(n -> Set.of("PermitValueRule", "DenyValueRule").contains(n.name)
                && valueLimited(n)) && rule.children.stream().anyMatch(n -> n.name.equals("DenyValueRule")))
            return "ATTRIBUTE_DENY_RULE_MANUAL";
        if (Set.of("", "false", "0").contains(rule.attr("permitAny")) && rule.children.size() == 1
                && rule.children.getFirst().name.equals("PermitValueRule") && valueLimited(rule.children.getFirst()))
            return "ATTRIBUTE_VALUE_RULE_MANUAL";
        return "ATTRIBUTE_RULE_UNRESOLVED";
    }

    private boolean valueLimited(Node node) {
        if (!AF.equals(node.namespace) || !AF.equals(node.typeNamespace())) return false;
        return switch (node.type()) {
            case "ANY" -> leaf(node, Set.of());
            case "Value" -> leaf(node, Set.of("value", "ignoreCase")) && literal(node.attr("value")) && booleanSetting(node.attr("ignoreCase"));
            case "Regex" -> leaf(node, Set.of("regex")) && literal(node.attr("regex"));
            case "OR", "AND" -> ruleAttributes(node, Set.of()) && !node.children.isEmpty()
                && node.children.stream().allMatch(n -> n.name.equals("Rule") && valueLimited(n));
            default -> false;
        };
    }

    private static String conditionEvidence(Node rule) {
        StringBuilder out = new StringBuilder();
        appendCondition(rule, out, 0, new int[]{0});
        return out.length() > 4096 ? out.substring(0, 4096) + " [truncated; inspect source XML]" : out.toString();
    }

    private static void appendCondition(Node node, StringBuilder out, int depth, int[] count) {
        if (depth >= 8 || ++count[0] > 64 || out.length() > 4096) {
            out.append("[truncated; inspect source XML]"); return;
        }
        out.append(node.name).append("{namespace=").append(SafeFiles.display(node.namespace));
        Set<String> visible = Set.of("id", "attributeID", "permitAny", "value", "regex", "pattern", "ref",
            "attributeName", "attributeNameFormat", "attributeValue", "ignoreCase", "caseSensitive", "matchIfMetadataSilent");
        for (var attr : node.attributes.entrySet()) {
            out.append(", ").append(SafeFiles.display(attr.getKey())).append('=');
            out.append(visible.contains(attr.getKey()) || attr.getKey().equals("{" + XSI + "}type")
                ? SafeFiles.display(attr.getValue()) : "<value omitted>");
        }
        out.append('}');
        // Show rule structure and operands, never scripts, expressions or arbitrary element text.
        if (!node.text.toString().isBlank()) out.append(" [text omitted; inspect source XML]");
        if (!node.children.isEmpty()) {
            out.append('(');
            for (int i = 0; i < node.children.size(); i++) {
                if (i > 0) out.append(", ");
                appendCondition(node.children.get(i), out, depth + 1, count);
                if (count[0] > 64 || out.length() > 4096) break;
            }
            out.append(')');
        }
    }

    private void problem(String severity, String code, String location, String detail, String remedy) {
        result.diagnostics.add(new Diagnostic(severity, code, location, detail, remedy));
    }
}
