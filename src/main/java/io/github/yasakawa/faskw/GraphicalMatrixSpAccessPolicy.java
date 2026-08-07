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

package io.github.yasakawa.faskw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixSpAccessPolicy {
    static final int MAX_FILE_BYTES = 1_048_576;
    static final int MAX_POLICIES = 1_000;
    static final int MAX_ATTRIBUTES = 32;
    static final int MAX_VALUES = 100;
    static final int MAX_VALUE_LENGTH = 256;

    record Rule(String attributeId, List<String> values) {
        Rule {
            validateAttributeId(attributeId);
            if (values.isEmpty() || values.size() > MAX_VALUES) {
                throw new IllegalArgumentException("access rule value count must be 1.." + MAX_VALUES);
            }
            final Set<String> unique = new LinkedHashSet<>();
            for (final String value : values) {
                validateValue(value);
                if (!unique.add(value)) {
                    throw new IllegalArgumentException(
                        "duplicate access rule value for " + attributeId);
                }
            }
            values = List.copyOf(unique);
        }
    }

    record Policy(String name, String entityId, boolean enabled, String mode, int revision,
                  List<Rule> allow, List<Rule> deny, String updatedAt) {
        Policy {
            if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("invalid SP name in access policy: " + name);
            }
            if (entityId.isBlank() || entityId.length() > 2048 || hasControl(entityId)) {
                throw new IllegalArgumentException("invalid entityID in access policy: " + name);
            }
            if (!"restrict".equals(mode)) {
                throw new IllegalArgumentException("unsupported access policy mode: " + mode);
            }
            if (revision < 1) {
                throw new IllegalArgumentException("access policy revision must be positive");
            }
            allow = normalizeRules(allow, "allow");
            deny = normalizeRules(deny, "deny");
            if (allow.isEmpty()) {
                throw new IllegalArgumentException("access policy requires at least one allow rule");
            }
            try {
                Instant.parse(updatedAt);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("invalid access policy updatedAt", ex);
            }
        }

        Policy withEnabled(final boolean value, final Instant now) {
            return new Policy(name, entityId, value, mode, revision + 1, allow, deny,
                now.toString());
        }
    }

    private final Map<String, Policy> policies;

    private GraphicalMatrixSpAccessPolicy(final Map<String, Policy> values) {
        policies = Map.copyOf(values);
    }

    static GraphicalMatrixSpAccessPolicy empty() {
        return new GraphicalMatrixSpAccessPolicy(Map.of());
    }

    static GraphicalMatrixSpAccessPolicy load(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return empty();
        }
        final Map<String, Object> root = GraphicalMatrixJson.readObject(path, MAX_FILE_BYTES);
        GraphicalMatrixJson.rejectUnknown(root, "access policy root", "schemaVersion", "policies");
        if (GraphicalMatrixJson.integer(root, "schemaVersion", 0) != 1) {
            throw new IOException("unsupported or missing access policy schemaVersion");
        }
        final Map<String, Policy> policies = new LinkedHashMap<>();
        for (final Object value : GraphicalMatrixJson.array(root, "policies", true)) {
            final Policy policy = policy(GraphicalMatrixJson.asObject(value, "access policy"));
            if (policies.putIfAbsent(policy.name(), policy) != null) {
                throw new IOException("duplicate SP name in access policy: " + policy.name());
            }
            if (policies.values().stream().filter(item -> item.entityId().equals(policy.entityId()))
                    .count() > 1) {
                throw new IOException("duplicate entityID in access policy: " + policy.entityId());
            }
        }
        if (policies.size() > MAX_POLICIES) {
            throw new IOException("access policy count exceeds " + MAX_POLICIES);
        }
        return new GraphicalMatrixSpAccessPolicy(policies);
    }

    List<Policy> policies() {
        return policies.values().stream().sorted(Comparator.comparing(Policy::name)).toList();
    }

    Policy find(final String name) {
        return policies.get(name);
    }

    Policy findByEntityId(final String entityId) {
        for (final Policy policy : policies.values()) {
            if (policy.entityId().equals(entityId)) {
                return policy;
            }
        }
        return null;
    }

    GraphicalMatrixSpAccessPolicy with(final Policy policy) {
        final Map<String, Policy> copy = new LinkedHashMap<>(policies);
        final Policy sameEntity = findByEntityId(policy.entityId());
        if (sameEntity != null && !sameEntity.name().equals(policy.name())) {
            throw new IllegalArgumentException(
                "access policy entityID is already assigned to " + sameEntity.name());
        }
        copy.put(policy.name(), policy);
        return new GraphicalMatrixSpAccessPolicy(copy);
    }

    GraphicalMatrixSpAccessPolicy without(final String name) {
        final Map<String, Policy> copy = new LinkedHashMap<>(policies);
        if (copy.remove(name) == null) {
            throw new IllegalArgumentException("access policy is not configured: " + name);
        }
        return new GraphicalMatrixSpAccessPolicy(copy);
    }

    void save(final Path path) throws IOException {
        GraphicalMatrixSpFiles.atomicWrite(path, toJson());
    }

    String toJson() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1L);
        final List<Object> values = new ArrayList<>();
        for (final Policy policy : policies()) {
            values.add(policyObject(policy));
        }
        root.put("policies", values);
        return GraphicalMatrixJson.write(root);
    }

    static String singlePolicyJson(final Policy policy) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1L);
        root.put("policies", List.of(policyObject(policy)));
        return GraphicalMatrixJson.write(root);
    }

    static Policy singlePolicy(final Path path) throws IOException {
        final List<Policy> values = load(path).policies();
        if (values.size() != 1) {
            throw new IOException("revision access policy must contain exactly one policy");
        }
        return values.get(0);
    }

    private static Policy policy(final Map<String, Object> value) throws IOException {
        GraphicalMatrixJson.rejectUnknown(value, "access policy", "name", "entityId", "enabled",
            "mode", "revision", "allow", "deny", "updatedAt");
        try {
            return new Policy(GraphicalMatrixJson.string(value, "name", true),
                GraphicalMatrixJson.string(value, "entityId", true),
                GraphicalMatrixJson.bool(value, "enabled", true),
                GraphicalMatrixJson.string(value, "mode", true),
                GraphicalMatrixJson.integer(value, "revision", 0),
                rules(value, "allow"), rules(value, "deny"),
                GraphicalMatrixJson.string(value, "updatedAt", true));
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid access policy: " + ex.getMessage(), ex);
        }
    }

    private static List<Rule> rules(final Map<String, Object> policy, final String key)
            throws IOException {
        final List<Rule> out = new ArrayList<>();
        for (final Object value : GraphicalMatrixJson.array(policy, key, false)) {
            final Map<String, Object> rule = GraphicalMatrixJson.asObject(value, key + " rule");
            GraphicalMatrixJson.rejectUnknown(rule, key + " rule", "attributeId", "values");
            try {
                out.add(new Rule(GraphicalMatrixJson.string(rule, "attributeId", true),
                    GraphicalMatrixJson.strings(rule, "values")));
            } catch (IllegalArgumentException ex) {
                throw new IOException("invalid " + key + " rule: " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> policyObject(final Policy policy) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", policy.name());
        out.put("entityId", policy.entityId());
        out.put("enabled", policy.enabled());
        out.put("mode", policy.mode());
        out.put("revision", (long) policy.revision());
        out.put("allow", ruleObjects(policy.allow()));
        out.put("deny", ruleObjects(policy.deny()));
        out.put("updatedAt", policy.updatedAt());
        return out;
    }

    private static List<Object> ruleObjects(final List<Rule> rules) {
        final List<Object> out = new ArrayList<>();
        for (final Rule rule : rules) {
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("attributeId", rule.attributeId());
            value.put("values", rule.values());
            out.add(value);
        }
        return out;
    }

    private static List<Rule> normalizeRules(final List<Rule> source, final String label) {
        final Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (final Rule rule : source) {
            grouped.computeIfAbsent(rule.attributeId(), ignored -> new LinkedHashSet<>())
                .addAll(rule.values());
        }
        if (grouped.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(label + " attribute count exceeds " + MAX_ATTRIBUTES);
        }
        final List<Rule> out = new ArrayList<>();
        for (final Map.Entry<String, LinkedHashSet<String>> entry : grouped.entrySet()) {
            out.add(new Rule(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(out);
    }

    static void validateAttributeId(final String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid attribute ID: " + value);
        }
    }

    private static void validateValue(final String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH
                || !value.equals(value.strip()) || hasControl(value)) {
            throw new IllegalArgumentException("invalid access policy comparison value");
        }
    }

    private static boolean hasControl(final String value) {
        return value.chars().anyMatch(item -> item < 0x20 || item == 0x7f);
    }
}
