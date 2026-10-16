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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Describes source authorization separately; never executes Spring, SpEL or user predicates. */
final class SourceAccessAnalyzer {
    private static final String C = "{http://www.springframework.org/schema/c}";
    private static final String P = "{http://www.springframework.org/schema/p}";
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    interface Reader { byte[] read(Path file) throws Exception; }
    private record Expr(String text, Boolean constant, boolean known) { }
    private final List<Node> rp;
    private final Path config;
    private final Reader reader;
    private final Path sourceHome;
    private Node root;
    private String failure;
    private boolean loaded;

    SourceAccessAnalyzer(List<Node> rp, Path config, Reader reader) {
        this(rp, config, reader, null);
    }

    SourceAccessAnalyzer(List<Node> rp, Path config, Reader reader, Path sourceHome) {
        this.rp = rp; this.config = config; this.reader = reader;
        this.sourceHome = sourceHome;
    }

    AccessAssessment assess(Candidate c) {
        List<String> evidence = new ArrayList<>();
        boolean unresolved = false;
        boolean context = false;
        boolean otherIntercept = false;
        Set<String> frameworkResources = new java.util.LinkedHashSet<>();
        int supportBeans = 0;
        List<Node> containers = rp.stream().filter(n -> Set.of("shibboleth.DefaultRelyingParty", "shibboleth.RelyingPartyOverrides").contains(n.attr("id"))).toList();
        for (Node container : rp) {
            if (!container.attr("class").isEmpty() && containers.stream().noneMatch(n -> n.descendants().contains(container))) {
                if (standardSupport(container)) supportBeans++;
                else if (standardFrameworkResource(container)) frameworkResources.add(container.source);
                else {
                    unresolved = true;
                    evidence.add("custom_bean_scope_unresolved=" + container.attr("class") + " at " + container.location + " (never executed)");
                }
            }
            if (container.attr("id").equals("shibboleth.DefaultRelyingParty")) {
                if (!container.namespace.equals(SPRING) || !container.name.equals("bean")
                        || !container.attr("class").isEmpty() || !container.attr("parent").equals("RelyingParty")) {
                    unresolved = true;
                    evidence.add("default_relying_party_shape_unresolved at " + container.location);
                }
                for (Node n : container.descendants()) {
                    String flows = n.attr(P + "postAuthenticationFlows");
                    if (!flows.isEmpty()) evidence.add("default_post_authentication_flows=" + flows + " at " + n.location);
                    if (!flows.isEmpty()) {
                        List<String> values = flowValues(flows);
                        context |= values == null || values.contains("context-check");
                        unresolved |= values == null;
                        otherIntercept |= values != null && values.stream().anyMatch(v -> !Set.of("attribute-release", "context-check", "").contains(v));
                    }
                    if (n.name.equals("property") && n.attr("name").equals("postAuthenticationFlows")) {
                        evidence.add("default_post_authentication_property_unresolved at " + n.location);
                        context = true; unresolved = true;
                    }
                }
            }
            if (!container.attr("id").equals("shibboleth.RelyingPartyOverrides")) continue;
            if (!container.namespace.equals("http://www.springframework.org/schema/util") || !container.name.equals("list")
                    || !attributes(container, Set.of("id"))) {
                unresolved = true;
                evidence.add("relying_party_overrides_shape_unresolved at " + container.location);
            }
            for (Node override : container.children) {
                List<String> ids = override.namespace.equals(SPRING) && override.name.equals("bean")
                    && override.attr("parent").equals("RelyingPartyByName") ? relyingIds(override) : null;
                if (ids != null && !ids.contains(c.entity)) continue;
                evidence.add("relying_party_override=" + (ids == null ? "scope unresolved" : "exact entityID match")
                    + " at " + override.location);
                unresolved |= ids == null;
                // All profile settings remain manual, even when the SP selector is literal.
                for (Node n : override.descendants()) {
                    if (!n.attr("parent").isEmpty()) evidence.add("profile_or_parent=" + n.attr("parent") + " at " + n.location);
                    for (var a : n.attributes.entrySet()) if (a.getKey().startsWith(P)) {
                        evidence.add("profile_setting=" + a.getKey().substring(P.length()) + "=" + a.getValue() + " at " + n.location);
                        if (a.getKey().equals(P + "postAuthenticationFlows")) {
                            List<String> flows = flowValues(a.getValue());
                            context |= flows == null || flows.contains("context-check");
                            unresolved |= flows == null;
                        }
                    }
                    if (n.namespace.equals(SPRING) && n.name.equals("property")) {
                        evidence.add("profile_property=" + n.attr("name") + " at " + n.location + " (not copied)");
                        if (n.attr("name").equals("postAuthenticationFlows")) {
                            context = true; unresolved = true;
                        }
                    }
                }
                if (ids == null) {
                    context = true;
                    evidence.add("unknown selectors/references may affect this SP; no expression is executed");
                }
            }
        }
        if (supportBeans > 0) evidence.add("standard_support_beans=" + supportBeans + " (scalar/X509 credential definitions; not authorization predicates)");
        for (String resource : frameworkResources) evidence.add("standard_framework_review=" + resource
            + " (framework/metadata-driven defaults not executed or copied; verify source version and target defaults)");
        boolean dependency = evidence.stream().anyMatch(s -> s.startsWith("relying_party_override=")) || context || otherIntercept || !frameworkResources.isEmpty();
        if (otherIntercept) evidence.add("custom_post_authentication_flow=manual review required; not executed or copied");
        if (context) {
            load();
            if (failure != null) {
                unresolved = true; evidence.add("context_check_unresolved=" + failure);
            } else {
                List<Node> functions = definitions("shibboleth.context-check.Function");
                List<Node> conditions = definitions("shibboleth.context-check.Condition");
                if (!functions.isEmpty() || conditions.size() != 1) {
                    unresolved = true;
                    evidence.add("context_check_unresolved=custom Function or missing/duplicate top-level Condition; never executed");
                } else {
                    Expr expression = expression(conditions.getFirst(), c.entity, 0);
                    unresolved |= !expression.known;
                    evidence.add("context_check_condition=" + expression.text + " at " + conditions.getFirst().location);
                }
                evidence.add("context_check_resource=" + config + " (static snapshot; runtime activation not verified)");
            }
        }
        evidence.add("access_control_migration=NOT_PERFORMED; no access policy or relying-party override is written");
        evidence.add("target_requires=equivalent resolver, attribute filtering, intercept activation and allow/deny browser tests before cutover");
        return new AccessAssessment(unresolved ? "UNRESOLVED" : dependency ? "MANUAL_REQUIRED" : "NONE_OBSERVED", evidence);
    }

    private boolean standardSupport(Node n) {
        boolean scalar = !n.attr("class").equals("java.lang.String") || n.children.stream().allMatch(child -> child.namespace.equals(SPRING)
            && child.name.equals("constructor-arg") && child.children.isEmpty()
            && child.attributes.keySet().stream().allMatch(k -> Set.of("value", "index", "type").contains(k))
            && !child.attr("value").contains("#{") && !child.attr("value").contains("${") && !child.attr("value").contains("%{"));
        return scalar && n.namespace.equals(SPRING) && n.name.equals("bean") && Set.of(
            "java.lang.String", "net.shibboleth.idp.profile.spring.factory.BasicX509CredentialFactoryBean").contains(n.attr("class"))
            && n.attr("factory-bean").isEmpty() && n.attr("factory-method").isEmpty()
            && n.attr("init-method").isEmpty() && n.attr("destroy-method").isEmpty()
            && !Set.of("shibboleth.DefaultRelyingParty", "shibboleth.RelyingPartyOverrides").contains(n.attr("id"));
    }

    private boolean standardFrameworkResource(Node n) {
        if (sourceHome == null || n.source == null || !n.namespace.equals(SPRING) || !n.name.equals("bean")) return false;
        Path path;
        try { path = Path.of(n.source).toAbsolutePath().normalize(); } catch (RuntimeException ex) { return false; }
        boolean expected = List.of("relying-party-system.xml", "relying-party-mddriven.xml", "metadata-providers-system.xml").stream()
            .anyMatch(file -> path.equals(sourceHome.resolve("system/conf").resolve(file).toAbsolutePath().normalize()));
        // Path alone is not trust: unfamiliar/custom classes remain unresolved even in system/conf.
        return expected && Set.of("net.shibboleth.", "org.opensaml.", "org.springframework.beans.factory.config.ListFactoryBean")
            .stream().anyMatch(prefix -> prefix.endsWith(".") ? n.attr("class").startsWith(prefix) : n.attr("class").equals(prefix));
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            root = parse(reader.read(config), config.toString());
            if (!root.namespace.equals(SPRING) || !root.name.equals("beans")) throw new IllegalArgumentException("not a Spring beans document");
            if (root.descendants().stream().anyMatch(n -> n.name.equals("import")))
                throw new IllegalArgumentException("imported context configuration requires manual review; specify a self-contained --context-check-config snapshot");
        } catch (Exception ex) {
            failure = config + ": " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private List<Node> definitions(String id) {
        return root.children.stream().filter(n -> n.namespace.equals(SPRING) && n.name.equals("bean") && n.attr("id").equals(id)).toList();
    }

    private Expr expression(Node n, String entity, int depth) {
        if (depth > 32 || !n.namespace.equals(SPRING) || !n.name.equals("bean")) return unknown(n);
        String parent = n.attr("parent");
        if (parent.equals("shibboleth.Conditions.RelyingPartyId")) {
            List<String> ids = selectorValues(n.attr(C + "candidates"));
            if (ids == null || !n.children.isEmpty() || !attributes(n, Set.of("id", "parent", C + "candidates"))) return unknown(n);
            boolean match = ids.contains(entity);
            return new Expr(match ? "TRUE(SP matches)" : "FALSE(other SP)", match, true);
        }
        if (Set.of("shibboleth.Conditions.AND", "shibboleth.Conditions.OR", "shibboleth.Conditions.NOT").contains(parent)) {
            if (!attributes(n, Set.of("id", "parent")) || n.children.size() != 1) return unknown(n);
            Node arg = n.children.getFirst();
            if (!arg.namespace.equals(SPRING) || !arg.name.equals("constructor-arg") || !arg.attributes.isEmpty() || arg.children.size() != 1) return unknown(n);
            Node list = arg.children.getFirst();
            if (!list.namespace.equals(SPRING) || !list.name.equals("list") || !list.attributes.isEmpty() || list.children.isEmpty()) return unknown(n);
            List<Expr> children = list.children.stream().map(child -> expression(child, entity, depth + 1)).toList();
            if (parent.endsWith(".NOT")) {
                if (children.size() != 1) return unknown(n);
                Expr child = children.getFirst();
                return new Expr("NOT(" + child.text + ")", child.constant == null ? null : !child.constant, child.known);
            }
            boolean and = parent.endsWith(".AND");
            if (children.stream().anyMatch(child -> child.constant != null && child.constant != and))
                return new Expr(and ? "FALSE(other SP branch)" : "TRUE(fallback permits this SP)", !and, true);
            List<Expr> dynamic = children.stream().filter(child -> child.constant == null).toList();
            if (dynamic.isEmpty()) return new Expr(and ? "TRUE" : "FALSE", and, true);
            return new Expr((and ? "AND(" : "OR(") + dynamic.stream().map(Expr::text).collect(java.util.stream.Collectors.joining(", ")) + ")",
                null, dynamic.stream().allMatch(Expr::known));
        }
        String type = n.attr("class");
        if (type.equals("net.shibboleth.idp.profile.logic.SimpleAttributePredicate")) {
            if (!attributes(n, Set.of("id", "class", P + "useUnfilteredAttributes")) || n.children.size() != 1) return unknown(n);
            Node property = n.children.getFirst();
            if (!property.namespace.equals(SPRING) || !property.name.equals("property") || !property.attr("name").equals("attributeValueMap")
                    || !attributes(property, Set.of("name")) || property.children.size() != 1) return unknown(n);
            Node map = property.children.getFirst();
            if (!map.namespace.equals(SPRING) || !map.name.equals("map") || !map.attributes.isEmpty() || map.children.isEmpty()) return unknown(n);
            List<String> rules = new ArrayList<>(); Set<String> keys = new java.util.HashSet<>();
            for (Node entry : map.children) {
                String key = entry.attr("key");
                if (!entry.namespace.equals(SPRING) || !entry.name.equals("entry") || !attributes(entry, Set.of("key"))
                        || !key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}") || !keys.add(key) || entry.children.size() != 1) return unknown(n);
                Node list = entry.children.getFirst();
                if (!list.namespace.equals(SPRING) || !list.name.equals("list") || !list.attributes.isEmpty() || list.children.isEmpty()) return unknown(n);
                List<String> values = new ArrayList<>();
                for (Node value : list.children) {
                    if (!value.namespace.equals(SPRING) || !value.name.equals("value") || !value.attributes.isEmpty() || !value.children.isEmpty()
                            || value.text.toString().contains("#{") || value.text.toString().contains("${") || value.text.toString().contains("%{")) return unknown(n);
                    values.add(value.text.toString());
                }
                rules.add(key + " IN " + values);
            }
            return new Expr("ATTRIBUTE_AND(" + String.join(", ", rules) + "; " + attributeSource(n) + ")", null, validSource(n));
        }
        if (type.equals("net.shibboleth.idp.profile.logic.RegexAttributePredicate") && n.children.isEmpty()
                && attributes(n, Set.of("id", "class", P + "useUnfilteredAttributes", P + "attributeId", P + "pattern"))
                && !n.attr(P + "attributeId").isEmpty() && !n.attr(P + "pattern").isEmpty()) {
            // Preserve the original pattern, including dots/anchors; do not replace it with a domain test.
            return new Expr("REGEX(" + n.attr(P + "attributeId") + ", " + n.attr(P + "pattern") + "; " + attributeSource(n) + ")",
                null, validSource(n) && !n.attr(P + "pattern").contains("#{") && !n.attr(P + "pattern").contains("%{")
                    && !n.attr(P + "pattern").contains("${") && literalValues(n.attr(P + "attributeId")) != null);
        }
        return unknown(n);
    }

    private static boolean attributes(Node n, Set<String> allowed) { return allowed.containsAll(n.attributes.keySet()); }
    private static boolean validSource(Node n) { return Set.of("", "true", "false").contains(n.attr(P + "useUnfilteredAttributes")); }
    private static String attributeSource(Node n) {
        String value = n.attr(P + "useUnfilteredAttributes");
        return "useUnfilteredAttributes=" + (value.isEmpty() ? "UNSPECIFIED(check source-version default)" : value);
    }
    private static Expr unknown(Node n) { return new Expr("UNRESOLVED(" + n.name + ", " + n.attr("parent") + ", " + n.attr("class") + " at " + n.location + ")", null, false); }

    private static List<String> relyingIds(Node n) {
        // Other selector, factory, activation or profile lookup customizations must not be ignored.
        if (!attributes(n, Set.of("id", "parent", C + "relyingPartyIds")) || n.children.stream().anyMatch(child ->
                !child.namespace.equals(SPRING) || !child.name.equals("property") || !child.attr("name").equals("profileConfigurations"))) return null;
        return selectorValues(n.attr(C + "relyingPartyIds"));
    }

    private static List<String> selectorValues(String raw) {
        // Plain multi-value conversion depends on Spring type conversion; never infer a non-match.
        String value = raw.trim();
        if (!value.contains("#{") && (value.contains(",") || value.chars().anyMatch(Character::isWhitespace))) return null;
        return literalValues(raw);
    }

    static List<String> literalValues(String raw) {
        if (raw.isBlank() || raw.contains("%{") || raw.contains("${")) return null;
        String value = raw.trim();
        if (!value.contains("#{")) return List.of(value);
        if (!value.startsWith("#{") || !value.endsWith("}")) return null;
        value = value.substring(2, value.length() - 1).trim();
        if (value.startsWith("{") && value.endsWith("}")) value = value.substring(1, value.length() - 1).trim();
        List<String> result = new ArrayList<>();
        var matcher = QUOTED.matcher(value); int end = 0;
        while (matcher.find()) {
            String gap = value.substring(end, matcher.start()).trim();
            if (!gap.equals(result.isEmpty() ? "" : ",")) return null;
            result.add(matcher.group(1)); end = matcher.end();
        }
        return !result.isEmpty() && value.substring(end).isBlank() ? List.copyOf(result) : null;
    }

    private static List<String> flowValues(String raw) {
        List<String> values = literalValues(raw);
        if (values == null || raw.contains("#{")) return values;
        return java.util.Arrays.stream(raw.split(",", -1)).map(String::trim).toList();
    }
}
